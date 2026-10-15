# Upscale + Recording — capacity planning

Working notes on how many concurrent live GPU upscales an RTX-class GPU may
sustain, and how that interacts with recording OTA to disk. The figures below
are illustrative estimates, not guarantees; deployments should use measured
capacity calibration for their own GPU, driver, source, and encoder settings.

> **Scaler backend:** these numbers are for the GPU **Lanczos** path shipping today
> (`scale_npp` + `hevc_nvenc`), which is NVENC-bound. An AI VSR tier (Maxine SuperRes /
> RTX Video SDK), if integrated later, is Tensor-core-bound and will have a **much lower**
> concurrency ceiling — do not read this table as AI-VSR capacity.

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

## Capacity considerations

Real-time delivery is normally paced, so instantaneous encoder load can be
bursty rather than steady. Capacity depends on sustained throughput, aligned
burst peaks, VRAM per session, decoder pressure, recording I/O, source cadence,
and the selected enhancement provider. The governor must therefore use runtime
measurement and conservative admission rather than a GPU-model lookup table.

## Cross-GPU projection

NVENC-bound, one engine per encode session. Generations and engine counts matter more than raw CUDA:

| GPU | NVENC gen | Engines | VRAM | Est. concurrent **4K** real-time upscales | Est. **1080p** |
| --- | --- | --- | --- | --- | --- |
| RTX 5090 / 5080 | 9th (Blackwell) | 2+ | 16–32 GB | Benchmark locally | Benchmark locally |
| RTX 4090 / 4080 | 8th (Ada) | 2 | 16–24 GB | ~4–7 | ~12+ |
| RTX 4070 | 8th (Ada) | 1 | 12 GB | ~2–4 | ~8 |
| RTX 3080 / 3090 | 7th (Ampere) | 1 | 10–24 GB | ~2–3 | ~6 |
| **RTX 2060** | 7th (Turing) | **1** | **6 GB** | **1 comfortably, 2 tight** | ~4–5 |

The ranges are planning estimates only. Independent viewers may smooth burst
load, but aligned GOP work can also produce short periods of contention.
Calibrate with the exact live-OTA and recording workload before raising limits.

### Notes on the low end (RTX 2060)

- One NVENC engine and older HEVC throughput mean a single 4K60 real-time stream
  may use a large fraction of available encoder capacity.
- 6 GB VRAM is the harder limit — each session is ~1.5–2 GB with CUDA context,
  so 2–3 sessions pressures VRAM, especially alongside anything else on the GPU.
- Turing consumer cards historically carry a driver-side NVENC session cap
  (fine for 1–2). No AV1 encode, but HEVC is unaffected.
- Practical guidance: cap at **1** concurrent 4K stream, or 2 only if restricted
  to the ≤1080p tier and VRAM is clear.
