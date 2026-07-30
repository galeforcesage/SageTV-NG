# NG Stream Format Hint (`ng_fmt`)

## Protocol Change Summary

The SageTV server now appends an `ng_fmt` MIME format hint to `openURL` messages for NG-capable sessions in **both** push and pull modes. This provides container, video, and audio MIME type information so the client can configure its decoder pipeline immediately without probing/sniffing the stream (which previously added 15-20 seconds of latency).

### Pull Mode

The MIME triplet is appended as a query parameter to the playback URL:

```
?ng_fmt=containerMime,videoMime,audioMime
```

- Appended with `?` if the URL has no existing query string, or `&` if one exists

### Push Mode

The MIME triplet is appended after the SageTV format string, separated by `|`:

```
push:f=MPEG2-TS;[bf=vid;f=HEVC;][bf=aud;f=AC-4;]|ng_fmt=video/mp2t,video/hevc,audio/ac4
```

- Uses `|` as separator (since the format string uses `;` internally and `?` could be ambiguous)
- The MIME types are derived from the **wire format** (i.e., transcoded output when transcoding), not the source file

### Common Format

In both modes, the value is a comma-separated positional triplet:

```
containerMime,videoMime,audioMime
```

- Any field may be empty if the server cannot determine the format
- Fields are positional — always 3 parts separated by 2 commas

## Example URLs

### Pull Mode

```
stv://<host>/path/file.ts?ng_fmt=video/mp2t,video/hevc,audio/ac4
stv://<host>/path/file.mkv?ng_fmt=video/x-matroska,video/hevc,audio/eac3
/path/file.ts?ng_fmt=video/mp2t,video/hevc,audio/ac4
stv://<host>/path/file.mp4?ng_fmt=video/mp4,video/avc,audio/mp4a-latm
stv://<host>/path/file.ts?ng_fmt=video/mp2t,video/hevc,
```

The last example shows an empty audio field (server couldn't determine audio format).

### Push Mode

```
push:f=MPEG2-TS;[bf=vid;f=HEVC;][bf=aud;f=AC-4;]|ng_fmt=video/mp2t,video/hevc,audio/ac4
push:f=MPEG2-TS;[bf=vid;f=H.264;][bf=aud;f=EAC3;]|ng_fmt=video/mp2t,video/avc,audio/eac3
push:f=MPEG2-TS;dur=3600;[bf=vid;f=HEVC;][bf=aud;f=AAC;]|ng_fmt=video/mp2t,video/hevc,audio/mp4a-latm
```

## Client Parsing Pseudocode

### Pull Mode (URL with `ng_fmt` query param)

```
url = received openURL string
if url contains "?ng_fmt=" or "&ng_fmt=":
    mimeStr = extract value after "ng_fmt=" (up to next '&' or end of string)
    parts = mimeStr.split(",")
    containerMime = parts[0] if non-empty else null
    videoMime = parts[1] if non-empty else null
    audioMime = parts[2] if non-empty else null
    // Strip ng_fmt from the URL before passing to the media player
    playbackUrl = url with ng_fmt param removed
```

### Push Mode (format string with `|ng_fmt=` suffix)

```
openUrlArg = received openURL argument (starts with "push:")
formatAndHint = openUrlArg.removePrefix("push:")
if formatAndHint contains "|ng_fmt=":
    pipeIdx = formatAndHint.indexOf("|ng_fmt=")
    formatString = formatAndHint.substring(0, pipeIdx)
    mimeStr = formatAndHint.substring(pipeIdx + 8)  // length of "|ng_fmt="
    parts = mimeStr.split(",")
    containerMime = parts[0] if non-empty else null
    videoMime = parts[1] if non-empty else null
    audioMime = parts[2] if non-empty else null
else:
    formatString = formatAndHint
    // no MIME hint available, use SageTV format string as before
```

## ExoPlayer (Android) Usage

- Use `containerMime` to select the correct `Extractor` / `MediaSource` type without sniffing:
  - `video/mp2t` → `TsExtractor`
  - `video/x-matroska` → `MatroskaExtractor`
  - `video/mp4` → `Mp4Extractor` or `FragmentedMp4Extractor`
  - `video/mpeg` → `PsExtractor`

- Use `videoMime` with `MediaFormat.createVideoFormat(videoMime, width, height)` to pre-configure the video decoder. Width/height can be 0 if unknown — ExoPlayer adapts on first keyframe.

- Use `audioMime` with `MediaFormat.createAudioFormat(audioMime, sampleRate, channelCount)` to pre-configure the audio decoder.

- If `audioMime` is present, the client can validate decoder availability via `MediaCodecList.findDecoderForFormat()` **before** playback starts. If null (no suitable decoder), tell the server to transcode audio instead of attempting playback and failing.

## PWA (Browser) Usage

- Use `containerMime` + codecs to build a `MediaSource.isTypeSupported()` check:
  ```javascript
  // Example: check if browser can handle HEVC in MPEG-TS
  const supported = MediaSource.isTypeSupported('video/mp2t; codecs="hvc1.1.6.L150"');
  ```

- Container → demuxer mapping:
  - `video/mp2t` → use TS demuxer (e.g., mux.js or transmuxer to fMP4)
  - `video/x-matroska` → use MKV/WebM demuxer
  - `video/mp4` → native MSE support (if codecs are compatible)

- Use `videoMime`/`audioMime` to configure `SourceBuffer` codec string without waiting for initialization segment sniffing.

- If `MediaSource.isTypeSupported()` returns false for the given codecs, request server-side transcoding before attempting playback.

## Windows (Future)

- Same URL parsing as above
- Use MIME types to select the appropriate DirectShow filter graph or Media Foundation topology:
  - `video/hevc` → configure HEVC decoder MFT
  - `video/avc` → configure H.264 decoder MFT
  - `audio/eac3` / `audio/ac3` → configure Dolby audio decoder
- Pre-validate decoder availability via `MFTEnumEx()` before playback

## Backward Compatibility

- If `ng_fmt` is **absent**, the client must fall back to existing probe/sniff behavior
- The server only appends `ng_fmt` for NG sessions (`ngSession=true`)
- Legacy (non-NG) clients never see this parameter
- Clients should treat `ng_fmt` as an optimization hint — if parsing fails or a field is empty, fall back to probing

## MIME Type Mapping Table

Full mapping of SageTV internal format names to MIME types:

| SageTV Format | MIME Type | Category |
|---------------|-----------|----------|
| MPEG2-TS | video/mp2t | Container |
| MPEG2-PS | video/mpeg | Container |
| MATROSKA | video/x-matroska | Container |
| MP4 | video/mp4 | Container |
| AVI | video/x-msvideo | Container |
| Quicktime | video/quicktime | Container |
| HEVC | video/hevc | Video |
| H.264 | video/avc | Video |
| MPEG2-Video | video/mpeg2 | Video |
| MPEG4-Video | video/mp4v-es | Video |
| VC1 | video/x-ms-wmv | Video |
| AC-4 | audio/ac4 | Audio |
| EAC3 | audio/eac3 | Audio |
| AC3 | audio/ac3 | Audio |
| AAC | audio/mp4a-latm | Audio |
| MP3 | audio/mpeg | Audio |
| MP2 | audio/mpeg-L2 | Audio |
| FLAC | audio/flac | Audio |
| DTS | audio/vnd.dts | Audio |
| DTS-HD | audio/vnd.dts.hd | Audio |
| DTS-MA | audio/vnd.dts.hd | Audio |
| Vorbis | audio/vorbis | Audio |
| ALAC | audio/alac | Audio |
