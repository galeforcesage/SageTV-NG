# AC-4 decoder fixes

Applied as `PATCH 8` (frame-loss) and `PATCH 9` (resampling mode) in
[`docker/build-sagetv-ffmpeg.sh`](../docker/build-sagetv-ffmpeg.sh).

The sections below cover PATCH 8 first (frame-loss concealment); PATCH 9
(resampling mode) is documented at the end.

---

# AC-4 decoder frame-loss fixes (PATCH 8)

## Symptom

Audio drifting out of sync with video on ATSC 3.0 (HEVC + AC-4 5.1) playback in
the browser MSE client, most visible on the `browserhd_copyv` path where video is
copied and only audio is transcoded.

## Root cause

Not a SageTV-NG bug, and not source corruption. The AC-4 decoder in the pinned
`elliotclee/FFmpeg` fork discards whole 32 ms frames on two conditions that do
not actually invalidate the PCM it has *already decoded*.

On a 1758 s capture the 5.1 track lost **61 frames (~2 s of dialogue)** while the
AC-4 stereo track in the same file lost none.

### 8a — `substream audio data overread` (52 frames)

```c
consumed = (get_bits_count(gb) >> 3) - offset;
if (consumed > audio_size) {
    av_log(..., AV_LOG_ERROR, "substream audio data overread: %d\n", ...);
    return AVERROR_INVALIDDATA;   /* frame discarded */
}
```

This is a **post-hoc byte-accounting check**. `audio_data()` has already run and
returned success, so the samples exist; the frame is thrown away over a 1–9 byte
bookkeeping mismatch. Upstream (librempeg) deliberately downgraded this to a
warning. PATCH 8a matches upstream.

### 8b — `invalid aspx num env` (8 frames)

A-SPX is AC-4's high-frequency extension. Crucially it is parsed **after** the
core spectral data:

```c
five_channel_data(...)            /* core waveform, all channels — succeeds */
if (ss->codec_mode == CM_ASPX) {
    aspx_data_2ch(ssch0, ssch1)   /* fails here */
```

So when A-SPX framing is corrupt the core waveform for that frame is already
decoded — only the high band is unrecoverable. PATCH 8b keeps the frame and
rebuilds the high band from the previous frame's A-SPX envelope (which the
decoder already retains for delta-coding), i.e. standard SBR-style concealment.

Note this bound is **not** over-strict and must not simply be raised: librempeg
has the identical `> 5` check, `ptr_bits` is derived from it, and the envelope
arrays are sized `[5]`. Values of 6–7 are genuine corruption.

## Results

Measured by summing decoded `nb_samples`, with the file's clean AC-4 **stereo**
track as a control. Frame counts and last-PTS drift are both misleading metrics —
last-PTS drift reads `+0.000 ms` even on the broken decoder.

| build | frames | decoded audio | deficit |
| --- | --- | --- | --- |
| baseline | 52,629 | 1684.128 s | −1.984 s |
| + 8a | 52,681 | 1685.792 s | −0.320 s |
| **+ 8a + 8b** | **52,689** | **1686.048 s** | **−0.064 s** |
| stereo control | 52,691 | 1686.112 s | — |

The 2 residual frames are **end-of-recording truncation**, not decoder behaviour:
the final packet is 354 bytes against a ~805 byte mean, and the 5.1 PID simply
holds one packet fewer than the stereo PID. There are no transport continuity
errors anywhere in the file, so no bytes were lost mid-stream — the recording
just stops mid-frame. Nothing is recoverable there and it falls after content end.

### Regression evidence

- The clean stereo track's decoded PCM is **bit-identical** before and after
  (same MD5), so neither patch changes behaviour on error-free streams.
- Whole-track `astats` peak level, RMS peak, flat factor and peak count are
  unchanged; overall RMS moves only in the 4th decimal. The concealed frames
  introduce no bursts or artifacts.

## Reproducing

`PATCH 8` is idempotent and asserts on failure — if the fork's source drifts so a
hunk no longer matches exactly once, the build fails loudly rather than silently
producing an unpatched binary.

## Related

- Frames dropped by the decoder preserve timing (the PTS step doubles rather than
  collapsing), so they present as isolated dropouts rather than cumulative drift.
  The `ffmpeg/aresample_async_ac4` property exists to re-align if needed; with
  PATCH 8 in place there is essentially nothing left for it to correct.
- `FFMPEGTranscoder.getTranscoderPath()` — all transcoding uses the single
  unified binary, so this fix covers every AC-4 path.

---

# AC-4 resampling mode (PATCH 9)

Applied as `PATCH 9` in
[`docker/build-sagetv-ffmpeg.sh`](../docker/build-sagetv-ffmpeg.sh), editing
`libavcodec/ac4dec.c`.

## Symptom

Regular **sub-second audio breaks** on ATSC 3.0 (AC-4) playback through the
server transcode path — a short silence roughly every ~4.6 s. The nVidia Shield
(hardware AC-4 decoder) plays the same channel clean, so the defect is specific
to the software decode path.

## Root cause

Not a SageTV-NG bug, not the async resampler, and not the A-SPX/overread errors
addressed by PATCH 8 (the deficit is identical on a 0-error track).

AC-4 defines a *resampling mode* for frame-rate-locked streams so a fixed-size
MDCT frame maps to the correct number of 48 kHz output samples. For a
29.97 fps-locked stream `frame_rate_index = 3` selects `frame_len_base = 1536`
with `resampling_ratio = 25025/24000`, i.e. each 1536-sample frame actually
represents `1536 * 25025/24000 = 1601.6` samples of real time (48000 / 29.97).

The fork computes `s->resampling_ratio` in `ac4_toc()` but **never applies it** —
the apply step at the end of `ac4_decode_frame()` is commented out — so it emits
the raw 1536 samples labelled 48000 Hz. Every frame is then 4.27 % too short.
Over a programme the decoded audio runs ~4 % shorter than the video, and the
downstream `aresample=async` drift corrector pads the growing deficit with
~102 ms of silence every ~4.6 s — the audible breaks.

## Fix

Report the *effective* (lower) sample rate for resampling-mode frames so the
already-decoded 1536 samples carry their true duration:

```c
if (s->resampling_ratio.num != s->resampling_ratio.den)
    avctx->sample_rate = av_rescale_rnd(avctx->sample_rate,
                                        s->resampling_ratio.den,
                                        s->resampling_ratio.num,
                                        AV_ROUND_NEAR_INF);
frame->nb_samples = s->frame_len_base;
```

`libavcodec` may not depend on `libswresample` (ffmpeg layering), so the decoder
does **not** resample in-place. Instead the 48 kHz resample SageTV already
performs before the audio encoder (`-ar 48000` plus the `aresample` filter in
`FFMPEGTranscoder.buildAudioResampleFilter`) restores the nominal 48 kHz grid
exactly where a valid encoder rate is required. The E-AC-3 encoder asserts a
standard sample rate, so this effective rate must never reach an encoder
un-resampled; every SageTV AC-4 re-encode path resamples to 48000 first.

For the 29.97 fps case the effective rate is
`av_rescale_rnd(48000, 24000, 25025, AV_ROUND_NEAR_INF) = 46034 Hz`.

## Results

Measured on an ATSC 3.0 AC-4 5.1 capture (`frame_rate_index 3`), 180 s slice,
comparing the deployed binary against the PATCH 9 binary:

| build | decoded duration | reported rate | injected silence gaps¹ |
| --- | --- | --- | --- |
| deployed (no PATCH 9) | 172.63 s | 48000 Hz | 39 |
| **+ PATCH 9** | **180.00 s** | **46034 Hz** | **3** |

¹ Gaps counted via `silencedetect` on the production chain
`aformat=channel_layouts=stereo,aresample=async=1000` + `-ar 48000 -c:a eac3`.
The 3 residual on the fixed build are real content silences (present with the
async filter removed entirely); the other 36 were injected padding.

## Reproducing

Like PATCH 8, the hunk asserts on failure — if the fork source drifts so the
`frame->nb_samples = s->frame_len_base;` line no longer matches exactly once, the
build fails loudly rather than producing an unpatched binary.

## Related

- With the decoder fixed, the `ffmpeg/aresample_async_ac4` async compensation is
  no longer needed to hide the deficit (verified: 0 injected gaps with or without
  it). It is left in place as harmless defence-in-depth.
