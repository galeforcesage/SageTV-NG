# Upscale + Recording — capacity planning

Working notes on how many concurrent live GPU upscales an RTX-class GPU can
sustain, and how that interacts with recording OTA to disk. **Everything below
the measured RTX 5080 line is an estimate** and will be replaced with real data
once we benchmark upscale-while-recording on live OTA.

## Status / plan

- We are **not** wiring any GPU-aware concurrency governor yet. The current
  server keeps the enhancement concurrency ceiling at a conservative value and
  drops it further while a tuner is recording.
- The next milestone is **upscale during live OTA**. For benchmarking we assume
  that *watching* live OTA and *recording* live OTA both write to disk while the
  upscale runs, and that both paths have the **same tolerance** to upscale (i.e.
  we treat the recording-to-disk write and the enhanced-playback write as
  equivalent load for sizing purposes).
- Only after we have live-OTA numbers (encoder load with a tuner actively
  recording, disk throughput, VRAM under simultaneous record+upscale) will we
  revisit auto-sizing the governor per host.

## Measured baseline — RTX 5080

Single live `720p → 2160p60` HEVC upscale, `scale_npp` + `hevc_nvenc`, ~40 Mbps,
paced to real time (`speed ≈ 1.06x`):

| Metric | Value |
| --- | --- |
| SM / CUDA (scale_npp resize) | 1–4% |
| NVENC (encoder) | **~21% avg, bursts to ~50%** |
| NVDEC (decoder, MPEG-2 in) | 1–4% |
| VRAM | ~5 GB / 16 GB |
| Power | ~46–49 W / 360 W |
| Temp | ~49–51 °C |

Sustained 14 min continuous: **~21% avg NVENC, bursts to ~50%, no VRAM growth
(flat ~5 GB) and no thermal drift (~51 °C)**; encoder held `speed ≈ 1.01x` the
whole time (tracking real time exactly, never falling behind).

**Caveat:** these numbers are the *real-time-paced* cost, not max throughput.
ffmpeg is throttled to delivery, so the GPU is far from saturated. NVENC load is
**bursty, not steady** — a sawtooth of ~50% peaks and near-0% idles as it encodes
a GOP then waits for the next delivery window. The dominant engine is NVENC; the
actual upscale filter and decode are nearly free. Capacity scales roughly as
NVENC throughput ÷ per-stream NVENC cost, but the burst peaks matter: concurrent
streams whose bursts align contend more than the ~21% average implies.

## Cross-GPU projection

NVENC-bound, one engine per encode session. Generations and engine counts matter more than raw CUDA:

| GPU | NVENC gen | Engines | VRAM | Est. concurrent **4K** real-time upscales | Est. **1080p** |
| --- | --- | --- | --- | --- | --- |
| **RTX 5080** (this box) | 9th (Blackwell) | 2 | 16 GB | **~4 (burst) – ~6–8 (avg)**, measured ~21% avg / ~50% peak per stream | ~15+ |
| RTX 4090 / 4080 | 8th (Ada) | 2 | 16–24 GB | ~4–7 | ~12+ |
| RTX 4070 | 8th (Ada) | 1 | 12 GB | ~2–4 | ~8 |
| RTX 3080 / 3090 | 7th (Ampere) | 1 | 10–24 GB | ~2–3 | ~6 |
| **RTX 2060** | 7th (Turing) | **1** | **6 GB** | **1 comfortably, 2 tight** | ~4–5 |

The 4K range reflects the bursty NVENC profile measured on the 5080: the upper
bound is **average-limited** (~21%/stream → ~6–8), the lower bound is
**peak/burst-limited** when independent streams' ~50% bursts align (~4 per
engine). Independent viewers jitter and smooth out in practice, so the real
ceiling sits between — to be pinned down by the live-OTA + recording benchmark.

### Notes on the low end (RTX 2060)

- One NVENC engine, ~half the per-engine HEVC throughput of Blackwell, so a
  single 4K60 real-time stream is expected to use a large fraction of its one
  encoder rather than the ~26% seen here.
- 6 GB VRAM is the harder limit — each session is ~1.5–2 GB with CUDA context,
  so 2–3 sessions pressures VRAM, especially alongside anything else on the GPU.
- Turing consumer cards historically carry a driver-side NVENC session cap
  (fine for 1–2). No AV1 encode, but HEVC is unaffected.
- Practical guidance: cap at **1** concurrent 4K stream, or 2 only if restricted
  to the ≤1080p tier and VRAM is clear.
