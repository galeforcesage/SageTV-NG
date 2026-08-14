/*
 * SageTV Streaming protocol for FFmpeg
 * Copyright (C) Jeffrey Kardatzke - 07/2006
 * Ported to modern FFmpeg 7.x API - 08/2026
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

/*
 * Stream files from a SageTV MediaServer over its TCP protocol (port 7818).
 * Protocol: OPEN path\r\n -> OK\r\n -> READ offset count\r\n -> data
 *           SIZE\r\n -> "avail total\r\n"   QUIT\r\n
 *
 * Handles live/circular-buffer files (active files) where the size
 * grows while ffmpeg is reading.
 */

#include "config_components.h"

#include "url.h"
#include "libavutil/avstring.h"
#include "libavutil/mem.h"
#include "libavutil/opt.h"

#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <inttypes.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>

/* No extern dependency on fftools — the -activefile flag is mapped
 * to the "follow" protocol option by ffmpeg_demux.c, or can be
 * set explicitly via -follow 1 on the command line.              */

#define STV_READAHEAD  65536
#define STV_FLUSH_BUF  4096

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

typedef struct STVContext {
    const AVClass *class;
    char  host[256];
    int   port;
    char  url[1024];
    int64_t actual_size;
    int64_t pos;
    int   fd;
    int   readahead;
    unsigned int readahead_factor;
    unsigned long long ahead_discarded;
    unsigned char flush_buf[STV_FLUSH_BUF];
    int   active_file;        /* 1 when input is a live/growing file */
    int   follow;             /* set via -follow 1 or format_opts */
} STVContext;

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

static int flush_readahead(STVContext *p)
{
    p->ahead_discarded += p->readahead;
    while (p->readahead > 0) {
        int n = recv(p->fd, p->flush_buf,
                     FFMIN(p->readahead, STV_FLUSH_BUF), 0);
        if (n <= 0) {
            av_log(NULL, AV_LOG_ERROR, "stv: flush_readahead failed\n");
            return AVERROR(EIO);
        }
        p->readahead -= n;
    }
    p->readahead_factor = 0;
    return 0;
}

/* Read a \r\n-terminated line from the socket.  Returns bytes consumed
 * (including the terminator) or < 0 on error / EOF. The terminator is
 * stripped and a NUL placed after the last payload byte.                */
static int sock_readline(int fd, char *buf, int buf_len)
{
    int offset = 0;
    for (;;) {
        int n = recv(fd, buf + offset, buf_len - offset, MSG_PEEK);
        if (n <= 0)
            return AVERROR(EIO);

        for (int i = 0; i < offset + n - 1; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                /* consume up to and including \r\n */
                int total = i + 2;
                int consumed = recv(fd, buf, total - offset, 0);
                if (consumed <= 0)
                    return AVERROR(EIO);
                /* strip terminator */
                buf[i] = '\0';
                return total;
            }
        }
        /* haven't found \r\n yet — consume what we peeked and continue */
        n = recv(fd, buf + offset, n, 0);
        if (n <= 0)
            return AVERROR(EIO);
        offset += n;
        if (offset >= buf_len - 2)
            return AVERROR(ENOSPC);
    }
}

static int open_connection(STVContext *p)
{
    int fd = -1;
    struct sockaddr_in addr;
    struct hostent *hp;
    char data[512];
    int data_len, res;
    int window = 256 * 1024;

    fd = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0)
        return AVERROR(errno);

    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &window, sizeof(window));

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(p->port);

    hp = gethostbyname(p->host);
    if (!hp) {
        close(fd);
        return AVERROR(ENOENT);
    }
    memcpy(&addr.sin_addr.s_addr, hp->h_addr_list[0], hp->h_length);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return AVERROR(errno);
    }

    snprintf(data, sizeof(data), "OPEN %s\r\n", p->url);
    data_len = strlen(data);
    if (send(fd, data, data_len, MSG_NOSIGNAL) < data_len) {
        close(fd);
        return AVERROR(EIO);
    }

    res = sock_readline(fd, data, sizeof(data));
    if (res < 0 || strcmp(data, "OK") != 0) {
        close(fd);
        return AVERROR(EIO);
    }

    p->fd = fd;
    p->readahead = 0;
    p->readahead_factor = 0;
    return 0;
}

static int reopen_connection(STVContext *p)
{
    int readahead_val = p->readahead;
    char data[512];
    int data_len, ret;

    if (p->fd >= 0)
        close(p->fd);
    p->fd = -1;

    ret = open_connection(p);
    if (ret < 0)
        return ret;

    /* re-request the same read-ahead window so the server is in sync */
    if (readahead_val > 0) {
        snprintf(data, sizeof(data), "READ %" PRId64 " %d\r\n",
                 p->pos, readahead_val);
        data_len = strlen(data);
        if (send(p->fd, data, data_len, MSG_NOSIGNAL) < data_len)
            return AVERROR(EIO);
        p->readahead = readahead_val;
    }
    return 0;
}

/* Query the server for the current file size.
 * Returns total size, writes available bytes to *avail. */
static int64_t query_size(STVContext *p, int64_t *avail)
{
    char data[512];
    int data_len, n;
    char *sp;
    int64_t total, a;

    snprintf(data, sizeof(data), "SIZE\r\n");
    data_len = strlen(data);

    if (send(p->fd, data, data_len, MSG_NOSIGNAL) < data_len) {
        if (reopen_connection(p) < 0)
            return 0;
        if (send(p->fd, data, data_len, MSG_NOSIGNAL) < data_len)
            return 0;
    }

    flush_readahead(p);

    n = sock_readline(p->fd, data, sizeof(data));
    if (n <= 0) {
        if (reopen_connection(p) < 0)
            return 0;
        snprintf(data, sizeof(data), "SIZE\r\n");
        if (send(p->fd, data, data_len, MSG_NOSIGNAL) < data_len)
            return 0;
        flush_readahead(p);
        n = sock_readline(p->fd, data, sizeof(data));
        if (n <= 0)
            return 0;
    }

    sp = strchr(data, ' ');
    if (!sp)
        return 0;
    *sp = '\0';

    a = strtoll(data, NULL, 10);
    total = strtoll(sp + 1, NULL, 10);

    if (avail)
        *avail = a;

    /* if total != available, file is still being written (active) */
    if (total != a)
        p->active_file = 1;

    return total;
}

/* ------------------------------------------------------------------ */
/* URLProtocol callbacks                                              */
/* ------------------------------------------------------------------ */

static int stv_open(URLContext *h, const char *filename, int flags)
{
    STVContext *p = h->priv_data;
    const char *rest;
    const char *slash;
    int ret;

    if (flags & AVIO_FLAG_WRITE)
        return AVERROR(ENOSYS);

    if (!av_strstart(filename, "stv://", &rest))
        return AVERROR(EINVAL);

    slash = strchr(rest, '/');
    if (!slash)
        return AVERROR(EINVAL);

    av_strlcpy(p->host, rest, FFMIN((int)(slash - rest) + 1, (int)sizeof(p->host)));

    /* check for optional :port */
    p->port = 7818;
    {
        char *colon = strchr(p->host, ':');
        if (colon) {
            *colon = '\0';
            p->port = atoi(colon + 1);
            if (p->port <= 0)
                p->port = 7818;
        }
    }

    av_strlcpy(p->url, slash + 1, sizeof(p->url));

    ret = open_connection(p);
    if (ret < 0)
        return ret;

    /* pick up the follow option (set by -activefile → follow=1 in ffmpeg_demux) */
    p->active_file = p->follow;

    if (p->active_file) {
        p->actual_size = 0;
        h->is_streamed = 0;   /* we support seeking in active files */
    } else {
        query_size(p, &p->actual_size);
    }

    return 0;
}

static int stv_read(URLContext *h, unsigned char *buf, int size)
{
    STVContext *p = h->priv_data;
    char data[512];
    int data_len;
    int bytes_read = 0;
    int request_len = size;

    /* For active files, refresh the size if we'd read past what we know */
    if (p->active_file && (size + p->pos) > p->actual_size) {
        int64_t total = query_size(p, &p->actual_size);
        if (total == p->actual_size)
            p->active_file = 0;  /* file is complete */
    }

    /* For non-active files, enable read-ahead after 2 sequential reads */
    if (!p->active_file && p->actual_size > 0) {
        p->readahead_factor++;
        if (p->readahead_factor > 2)
            request_len += STV_READAHEAD - p->readahead;

        if (request_len + p->pos > p->actual_size) {
            request_len = p->actual_size - p->pos;
            if (request_len <= 0)
                return AVERROR_EOF;
        }
        p->readahead += request_len;
    } else {
        p->readahead = request_len;
    }

    if (request_len <= 0)
        return 0;

    if (size > request_len)
        size = request_len;

    snprintf(data, sizeof(data), "READ %" PRId64 " %d\r\n",
             p->pos + p->readahead - request_len, request_len);
    data_len = strlen(data);

    if (send(p->fd, data, data_len, MSG_NOSIGNAL) < data_len) {
        if (reopen_connection(p) < 0)
            return AVERROR(EIO);
    }

    while (bytes_read < size) {
        int n = recv(p->fd, buf + bytes_read, size - bytes_read, 0);
        if (n <= 0) {
            if (reopen_connection(p) < 0)
                return bytes_read > 0 ? bytes_read : AVERROR(EIO);
            n = recv(p->fd, buf + bytes_read, size - bytes_read, 0);
            if (n <= 0)
                return bytes_read > 0 ? bytes_read : AVERROR(EIO);
        }
        bytes_read += n;
        p->pos += n;
        p->readahead -= n;
    }

    return bytes_read;
}

static int64_t stv_seek(URLContext *h, int64_t pos, int whence)
{
    STVContext *p = h->priv_data;
    int64_t avail_size;

    flush_readahead(p);

    /* Active file: short-circuit forward seeks (data will come as we read) */
    if (pos >= 0 && p->active_file &&
        whence != SEEK_END && whence != AVSEEK_SIZE) {
        if (whence == SEEK_CUR)
            p->pos += pos;
        else if (whence == SEEK_SET)
            p->pos = pos;
        return p->pos;
    }

    if (p->active_file) {
        /* must ask the server for the current size */
        query_size(p, &avail_size);
    } else {
        avail_size = p->actual_size;
    }

    if (whence == AVSEEK_SIZE)
        return avail_size;

    if (whence == SEEK_CUR)
        pos += p->pos;
    else if (whence == SEEK_END)
        pos += avail_size;

    if (pos >= 0 && (pos <= avail_size || p->active_file)) {
        p->pos = pos;
        return pos;
    }

    return p->pos;
}

static int stv_close(URLContext *h)
{
    STVContext *p = h->priv_data;
    if (p->fd >= 0) {
        flush_readahead(p);
        send(p->fd, "QUIT\r\n", 6, MSG_NOSIGNAL);
        close(p->fd);
        p->fd = -1;
    }
    return 0;
}

static const AVOption stv_options[] = {
    { "follow", "Follow a file as it is being written (active/live file)",
      offsetof(STVContext, follow), AV_OPT_TYPE_INT, { .i64 = 0 }, 0, 1,
      AV_OPT_FLAG_DECODING_PARAM },
    { NULL }
};

static const AVClass stv_class = {
    .class_name = "stv",
    .item_name  = av_default_item_name,
    .option     = stv_options,
    .version    = LIBAVUTIL_VERSION_INT,
};

const URLProtocol ff_stv_protocol = {
    .name            = "stv",
    .url_open        = stv_open,
    .url_read        = stv_read,
    .url_seek        = stv_seek,
    .url_close       = stv_close,
    .priv_data_size  = sizeof(STVContext),
    .priv_data_class = &stv_class,
    .flags           = URL_PROTOCOL_FLAG_NETWORK,
};
