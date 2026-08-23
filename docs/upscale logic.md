# Upscale Logic

> ## ⚠️ Implementation status — read first
>
> **What the server actually does today is GPU _Lanczos_ upscaling, not AI super-resolution.**
> The shipping live/recorded enhancement pipeline is
> `-hwaccel cuda -hwaccel_output_format cuda` → `yadif_cuda`/`bwdif_cuda` (deinterlace) →
> `scale_npp=…:interp_algo=lanczos` (or `scale_cuda` where NPP is absent) → `hevc_nvenc`.
> This is a fast, deterministic, **non-AI** classical resampler running on CUDA/NPP and the
> NVENC encoder. It is a real quality win over most client scalers for interlaced and
> compressed HD, but it is **not** a neural network and does not reconstruct detail.
>
> **"RTX VSR" / "RTX Video Super Resolution" and every `RTX_VSR_*` mode name in this document
> describe the _planned_ AI premium tier, which is not yet implemented.** Real server-side AI
> upscaling means integrating NVIDIA's **Maxine Video Effects SDK "Super Resolution"** (or the
> newer RTX Video SDK / NIM VSR) between decode and NVENC — there is no ffmpeg VSR filter and
> `scale_npp` is not it. That integration (TensorRT + Maxine SDK + NGC models) is **not present
> in the deployment** as of this writing.
>
> So, when reading this document:
> - Treat each `RTX_VSR_TO_*` mode as a **tier intent** ("server upscales to 2160p/1080p"),
>   whose scaler backend is **Lanczos (`scale_npp`) today** and **AI VSR when integrated.**
> - The decision logic below (when to upscale on the server vs let the client scale) is
>   backend-agnostic and applies to the Lanczos tier shipping now; the AI backend, once real,
>   raises the quality ceiling of the *same* decision, it does not change the decision shape.
> - Do **not** cite this document as evidence that AI VSR is running. See
>   [Important implementation distinction](#important-implementation-distinction).

## Purpose

Decide when the server should upscale (today via GPU **Lanczos**; via **AI VSR** once that
premium tier is integrated) instead of letting the playback client scale. Engage server-side
upscaling only when the final displayed result is expected to be meaningfully better for the
user than the upscaling performed by the playback client.

If client-side upscaling is expected to be visually equivalent, prefer the client path because it avoids unnecessary GPU work, network bitrate, latency, and an additional lossy encode generation.

Deinterlacing is treated separately from upscaling. When a source must be deinterlaced but the client can provide equivalent spatial upscaling, the server should deinterlace or inverse-telecine the source and let the client upscale the resulting progressive video.

Supported client classes include:

- PWA using AVPlay on a 2024 Samsung Tizen television
- 2015 NVIDIA Shield
- 2019 NVIDIA Shield Pro
- Recognized generic Android TV devices whose decoder and scaler capabilities can be identified and exploited
- Unknown or unverified Android TV devices using a conservative fallback profile

This same decision applies to **both** playback scenarios:

- **Recorded playback** — a finished recording pulled on demand, typically accompanied by comskip commercial-marker and closed-caption sidecars.
- **Live OTA** — an in-progress broadcast watched from the live buffer.

See [Applicability: recorded playback and live OTA](#applicability-recorded-playback-and-live-ota) for how category resolution and timeline preservation differ between the two.

---

## Decision Principles

The playback decision consists of three independent questions:

1. **Can the client decode and play the source reliably?**
2. **What preprocessing is mandatory?**
   - Deinterlacing
   - Inverse telecine
   - Codec conversion
   - Container conversion or remuxing
   - Bitrate reduction
3. **Would RTX VSR provide a noticeable improvement over the identified client scaler after accounting for re-encoding loss, latency, GPU cost, network requirements, and trick-play behavior?**

A device being capable of client-side upscaling does not imply that it is equal to RTX VSR. It means that its result may be sufficiently close that server-side RTX VSR does not improve the user's actual viewing experience enough to justify the extra processing.

The default tie-breaker is:

> **When the expected user experience is effectively equal, let the client upscale.**

---

## Processing Modes

| Mode | Server work | Client work | Intended use |
|---|---|---|---|
| `DIRECT_CLIENT_SCALE` | None, or remux only | Decode and upscale | The client's scaler is sufficient |
| `TRANSCODE_NATIVE_CLIENT_SCALE` | Convert codec or reduce bitrate while retaining source resolution | Decode and upscale | Client needs a compatible stream, but server scaling is unnecessary |
| `DEINTERLACE_CLIENT_SCALE` | Deinterlace or IVTC while retaining native spatial resolution | Decode progressive output and upscale | Client scaler is sufficient after interlace correction |
| `RTX_VSR_TO_1080_CLIENT_SCALE` | Enhance or upscale lower-resolution material to 1080p | Finish scaling to 4K | Optional middle path for poor 720p material |
| `RTX_VSR_TO_2160` | Deinterlace if needed, upscale, encode 4K | Decode the final 4K result | Server result is expected to be visibly better |

> **Backend note:** the `RTX_VSR_*` tokens are tier *intents*, not a claim about the scaler.
> Their backend today is GPU **Lanczos** (`scale_npp`); the AI VSR backend replaces only the
> scaling stage when integrated. See the status banner at the top of this document.
| `DIRECT_NATIVE_4K` | None, or remux only | Decode native 4K | Source is already 4K and compatible |
| `COMPATIBILITY_TRANSCODE` | Minimum processing required for reliable playback | Decode and perform normal display scaling | Client capabilities are limited or unknown |

`TRANSCODE_NATIVE_CLIENT_SCALE` must remain distinct from `RTX_VSR_TO_2160`. A codec incompatibility alone does not justify converting a 720p or 1080p recording into 4K.

---

## Normalized Client Capability Profile

Device names should resolve to normalized capability profiles rather than being embedded throughout the processing logic.

```text
ClientVideoCapabilities
{
    clientClass
    deviceManufacturer
    deviceModel
    platform
    platformVersion

    displayWidth
    displayHeight
    displayRefreshRate

    codecs[]
    codecProfiles[]
    maxDecodeWidth
    maxDecodeHeight
    maxDecodeFps
    maxBitrateKbps
    supports10Bit
    supportsHDR10

    acceptsInterlaced
    deinterlaceQuality

    upscaleClass
    upscaleMaxInputFps
    upscaleEligibleInputMin
    upscaleEligibleInputMax

    prefersNativeResolutionInput
    measuredNetworkKbps
    capabilityConfidence
}
```

### Upscale classes

```text
NONE
BASIC
GOOD_TV
SHIELD_ENHANCED
SHIELD_AI
VENDOR_AI
UNKNOWN
```

`VENDOR_AI` must only be assigned when the capability is verified to:

- Apply to third-party video playback
- Apply to the source resolution and frame rate
- Operate while the SageTV-NG client is playing
- Produce a tested and useful quality improvement

An Android device advertising "AI" is not sufficient evidence by itself.

Codec capability and scaler quality are separate. Runtime codec APIs can establish decode support and performance, but they do not establish the quality of the device's scaler. Scaler quality requires a known-device profile, a verified vendor capability, or SageTV-NG testing.

---

## Initial Client Profiles

| Client | Decode profile | Upscale profile | Default progressive path | Default interlaced path |
|---|---|---|---|---|
| PWA on 2024 Samsung Tizen | Model-specific AVPlay profile | `GOOD_TV` until device-specific testing changes it | Client-scale clean 1080p; policy decision for 720p | Server deinterlaces; television scales |
| 2015 Shield | Known Tegra X1 decode profile | `SHIELD_ENHANCED` | Client-scale clean 1080p; RTX VSR candidate for 720p | Server deinterlaces; compare client scaling with RTX VSR |
| 2019 Shield Pro | Known Tegra X1+ decode profile | `SHIELD_AI` | Client AI scale most clean 720p and 1080p input | Server deinterlaces or applies IVTC; Shield AI scales unless RTX VSR materially wins |
| Recognized Android TV with verified scaler | Runtime codec profile plus model override | `VENDOR_AI` or tested class | Apply model-specific crossover policy | Server deinterlaces if necessary, then apply model policy |
| Generic Android TV with known hardware decoder but unknown scaler | Runtime MediaCodec profile | `UNKNOWN` | Prefer RTX VSR for 720p; configurable for 1080p | Server deinterlaces, then RTX VSR or conservative 1080p output |
| Android TV with insufficient decode capability | Limited profile | Irrelevant | Compatibility transcode | Compatibility transcode plus deinterlace |
| Any capable client receiving native 4K | Verify 4K decode | No upscale required | Direct play or remux | Not applicable |

### Capability-resolution priority

```text
1. Exact known-device profile
2. Device-family profile
3. Client-reported runtime capabilities
4. Codec and platform API probes
5. Conservative generic profile
```

For Tizen, key profiles by the most reliable available combination of:

```text
platform = tizen
model code
model year
AVPlay version, when available
panel resolution
```

For Android TV, report available information such as:

- Hardware-accelerated versus software decoder
- Supported codecs and profiles
- Maximum resolution and frame rate
- Published codec performance points
- Bit depth and HDR support
- Current display resolution and refresh rate

Missing capability data must not automatically be interpreted as lack of capability. It lowers `capabilityConfidence` and invokes a conservative policy.

---

## Source Inspection

Before selecting a processing path, inspect and retain:

```text
width
height
scanType
frameRate
fieldRate
codec
codecProfile
codecLevel
bitDepth
HDR or SDR
bitrate
container
estimatedCompressionQuality
filmCadenceConfidence
```

For 1080i29.97 source material, retain both values:

```text
codedFrameRate = 29.97
fieldRate = 59.94
```

High-motion deinterlacing should normally preserve field-rate motion, producing approximately 1080p59.94 rather than 1080p29.97.

Do not use the EPG genre as a substitute for source inspection. Genre remains useful for motion classification and encoding bitrate only after the processing path has been selected.

---

## Source-Quality Classes

```text
CLEAN_HIGH_BITRATE
NORMAL_BROADCAST
COMPRESSED
SEVERELY_ARTIFACTED
GRAIN_OR_INTENTIONAL_TEXTURE
UNKNOWN
```

RTX VSR has its strongest justification for compressed and artifact-heavy material. Clean 1080p material presented to a capable client scaler has the weakest automatic justification for server-side 4K generation.

Grain and intentional texture require conservative handling because enhancement can mistake intended texture for noise or exaggerate it.

---

## Mandatory Preprocessing

Mandatory preprocessing is selected before choosing the spatial scaler.

```text
if source is interlaced:
    preprocessing = DEINTERLACE or IVTC

if source codec, profile, or container is unsupported:
    preprocessing += TRANSCODE or REMUX

if source bitrate exceeds client or network admission:
    preprocessing += BITRATE_REDUCTION

if source is already 2160p and is playable:
    return DIRECT_NATIVE_4K
```

Mandatory transcoding does not automatically mean that the server should upscale. The server may transcode at the native spatial resolution and let the client upscale.

---

## Deinterlacing and IVTC

Deinterlacing is a source-correction decision. Upscaling is a separate spatial-scaling decision.

### Film cadence

When film cadence is detected with sufficient confidence:

```text
1080i telecine source
→ inverse telecine to approximately 1080p23.976
→ client upscale if the client profile is approved
→ otherwise RTX VSR to 2160p23.976
```

### Sports and live video

For field-rate motion:

```text
1080i29.97
→ motion-adaptive deinterlace to 1080p59.94
→ client upscale when expected quality is equivalent
→ RTX VSR only when expected visible gain exceeds re-encode and delivery cost
```

The client should not receive interlaced material by default if SageTV-NG cannot rely on the client's deinterlacing quality.

---

## Server-versus-Client Spatial Scaling Decision

Conceptually compare:

```text
serverBenefit =
    expected RTX VSR quality
    - re-encode loss
    - latency penalty
    - trick-play penalty
    - playback risk
    - network cost

clientBenefit =
    known client-scaler quality
    + preservation of an earlier source generation
    + lower server cost
    + lower network cost
    + lower playback latency
```

The first implementation can use a policy matrix rather than a numeric score. Any later scoring system should remain explainable and allow per-device and per-source overrides.

---

## 720p Progressive to a 4K Display

| Client scaler | Clean 720p24/30 | Normal 720p broadcast | 720p50/60 sports | Compressed 720p |
|---|---|---|---|---|
| 2024 Tizen television scaler | Client scale initially | RTX VSR favored | RTX VSR favored, subject to latency and GPU admission | RTX VSR |
| 2015 Shield enhanced | Client or RTX VSR according to quality policy | RTX VSR favored | RTX VSR favored | RTX VSR |
| 2019 Shield Pro AI | Shield AI | Shield AI unless testing predicts a meaningful RTX VSR win | Shield AI on a quality tie; RTX VSR only for a proven gain | RTX VSR favored |
| Verified Android vendor AI | Device-specific policy | Device-specific policy | Verify frame-rate eligibility | RTX VSR unless testing approves client scaling |
| Generic Android `BASIC` or `UNKNOWN` | RTX VSR | RTX VSR | RTX VSR | RTX VSR |

---

## 1080p Progressive to a 4K Display

| Client scaler | Clean 1080p | Normal broadcast 1080p | Compressed 1080p |
|---|---|---|---|
| PWA-Tizen 2024 | Television/client scale by default | Policy comparison; default to client on tie | RTX VSR |
| 2015 Shield | Client scale on tie | RTX VSR optional | RTX VSR |
| 2019 Shield Pro | Shield AI by default | Shield AI unless artifact level is high | RTX VSR |
| Verified Android vendor AI | Client scale if approved for the model | Device policy | RTX VSR |
| Generic Android basic or unknown | RTX VSR optional | RTX VSR | RTX VSR |

For clean 1080p, the burden of proof is on server-side RTX VSR. If the expected result is not meaningfully distinguishable at normal viewing conditions, use client scaling.

---

## 1080i to a 4K Display

| Client | Clean news/talk | Sports/live | Compressed or noisy |
|---|---|---|---|
| PWA-Tizen 2024 | Deinterlace to 1080p; television scales | Deinterlace to 1080p59.94; television scale or RTX policy | Deinterlace plus RTX VSR |
| 2015 Shield | Deinterlace; Shield scales | Compare RTX VSR with 1080p59.94 client-scale path | RTX VSR |
| 2019 Shield Pro | Deinterlace; Shield AI scales | Deinterlace to 1080p59.94; Shield AI wins a quality tie | Deinterlace plus RTX VSR |
| Verified vendor-AI Android | Deinterlace, then device policy | Preserve field-rate motion | RTX VSR |
| Generic Android | Deinterlace plus RTX VSR | Deinterlace plus RTX VSR | Deinterlace plus RTX VSR |

---

## Decision Pseudocode

```text
function chooseVideoPath(source, client, network, preferences):

    if source.isNative4K
       and client.canDecode(source)
       and network.canCarry(source):
        return DIRECT_NATIVE_4K

    mandatory = determineMandatoryProcessing(source, client, network)
    progressive = source

    if source.isInterlaced:
        if source.hasReliableFilmCadence:
            progressive = IVTC_TO_NATIVE_PROGRESSIVE
        else:
            progressive = DEINTERLACE_TO_FIELD_RATE

    if client.requiresVideoTranscode(progressive):
        mandatory.add(VIDEO_TRANSCODE)

    scaler = resolveClientScaler(client)

    if scaler == SHIELD_AI:

        if progressive.height >= 1080
           and source.quality in [CLEAN_HIGH_BITRATE, NORMAL_BROADCAST]:
            return applyMandatoryThenClientScale(mandatory)

        if progressive.height == 720
           and source.quality == CLEAN_HIGH_BITRATE:
            return applyMandatoryThenClientScale(mandatory)

        if source.quality in [COMPRESSED, SEVERELY_ARTIFACTED]:
            return RTX_VSR_TO_2160

        return comparePolicyOrClientScaleOnTie()

    if scaler in [GOOD_TV, SHIELD_ENHANCED, VENDOR_AI]:

        if progressive.height >= 1080
           and source.quality == CLEAN_HIGH_BITRATE:
            return applyMandatoryThenClientScale(mandatory)

        if deviceSpecificPolicyApprovesClientScale(
                client,
                progressive,
                source.quality):
            return applyMandatoryThenClientScale(mandatory)

        return RTX_VSR_TO_2160

    if scaler in [BASIC, NONE, UNKNOWN]:
        if progressive.height < client.displayHeight:
            return RTX_VSR_TO_2160

    return applyMandatoryThenClientScale(mandatory)
```

### Explicit deinterlace cost optimization

```text
if source.isInterlaced
   and clientScalerExpectedEquivalent(client, progressive):
    return DEINTERLACE_CLIENT_SCALE
```

This is the intended path when deinterlacing is required but the attached client can provide an effectively equivalent final 4K presentation.

---

## Relationship to EPG Genre and Motion Logic

The current EPG category logic remains useful, but it must run **after** the processing mode is selected. EPG genre must not decide whether RTX VSR runs; it controls bitrate and possibly enhancement strength only after RTX VSR has justified itself.

### Category resolution

Categories come from the same `Wiz.bin` record playback already resolves:

```text
source file
→ Wizard.getFileForFilePath(file)      → MediaFile
→ MediaFile.getContentAiring()          → Airing
→ Airing.getShow()                      → Show
→ Show.getCategories()                  → String[]   (primary category first)
```

Matching is **case-insensitive substring**, categories are scanned **in order** (the primary/first category wins), and within a category the **first keyword hit wins**. A missing show, empty category list, or unresolved file is not an error — it falls through to the frame-rate proxy below.

### Full category-to-capability map

Every profile carries a `MotionClass`, which is the single lever the encoder acts on: it selects a rung in the [RTX VSR 4K bitrate table](#rtx-vsr-4k-bitrate-table). `SPORTS`/`NATURE` claim more bits for motion and texture; `NEWS_TALK` claims fewer for the same perceived quality; `FILM`/`GENERAL` sit in the middle.

| EPG primary category (representative Gracenote / Schedules Direct values) | Keyword(s) matched | Profile | Motion class |
|---|---|---|---|
| Sports event, Sports non-event, Sports talk, Football, Basketball, Baseball, Hockey, Soccer, Auto racing, Boxing, Wrestling, Olympics | `sport`, `football`, `basketball`, `baseball`, `hockey`, `soccer`, `olympic`, `racing`, `boxing`, `wrestling` | `SPORTS` | `HIGH` |
| News, Newsmagazine, Public affairs, Weather | `news`, `public affairs`, `weather` | `NEWS_TALK` | `LOW` |
| Talk, Talk show, Interview | `talk`, `interview` | `NEWS_TALK` | `LOW` |
| Nature, Wildlife, Outdoors | `nature`, `wildlife`, `outdoor` | `NATURE` | `HIGH` |
| Documentary, Science, History, Travel | `documentary`, `science`, `history`, `travel` | `NATURE` | `HIGH` |
| Movie, Feature film | `movie`, `film` | `FILM` | `MEDIUM` |
| Sitcom, Comedy, Drama, Reality, Game show, Children, Animated, Music, Cooking, Home improvement, Shopping, Religious, Awards, Special, Variety, Health, Entertainment, Biography, and anything unmatched | — | `GENERAL` | `MEDIUM` |
| No match, empty category, or no EPG | — | `GENERAL` | `MEDIUM` |

If `Wiz.bin` cannot resolve the file at all, use the frame-rate proxy instead of a profile:

```text
fps >= 50 → HIGH
otherwise → MEDIUM
```

### How motion class actually reaches the encoder

The profile's motion class is resolved, then reconciled with the frame-rate proxy and, finally, with the bandwidth the client advisor already admitted:

```text
motion = (profile != null and profile != GENERAL)
             ? profile.motionClass()
             : (fps >= 50 ? HIGH : MEDIUM)

genreEst = bitrateTable[tier][motion]        (plus override + admin cap)
fpsEst   = bitrateTable[tier][fpsProxy]       (the figure the advisor approved)

selectedBitrate = min(genreEst, fpsEst)
```

Because of the final `min()`, **genre can only ever lower the bitrate below the frame-rate estimate the advisor approved — never raise it.** The practical consequence today:

- `NEWS_TALK` at 24–30 fps is the behavior-changing case: it drops from the `MEDIUM` rung to the `LOW` rung and saves bits.
- `SPORTS` at 50–60 fps stays on the `HIGH` rung (unchanged).
- `NATURE`/`FILM`/`GENERAL` at 24–30 fps land on the `MEDIUM` rung, identical to the frame-rate proxy.

Raising a high-detail genre (for example `NATURE` at 30 fps) **above** the frame-rate estimate would require making the advisor genre-aware so the OFFER and the encode agree on the envelope. That is a documented follow-up, deliberately not enabled here so the enhanced stream can never exceed the bandwidth the client was admitted at.

### Correct decision order

```text
source inspection
→ client capability resolution
→ mandatory preprocessing
→ client-versus-RTX scaling decision
→ EPG profile and motion class
→ bitrate calculation
→ overrides and clamps
```

---

## Applicability: recorded playback and live OTA

The category-to-capability map above is intended to apply identically to on-demand playback of a finished recording and to a live OTA show. The differences are in *where* the metadata comes from and *what must be preserved*, not in the mapping itself.

### Recorded playback (comskip / CC sidecars)

- **Category resolution is fully populated.** A finished recording has its complete `Show` record in `Wiz.bin`, so `Show.getCategories()` returns the broadcaster's genre list and the profile map resolves exactly.
- **Sidecars key off the presentation timeline.** comskip commercial markers (`.edl` / `.txt`) and caption sidecars (`.srt`, `.eng.srt`, `.cc.srt`, `.vtt`, discovered by `FFMPEGTranscoder.findExistingCaptionSidecar`) are expressed as timestamps against the program timeline. Server-side enhancement re-encodes the video, so it **must preserve that presentation timeline end to end** — a re-encode that resets output timestamps toward zero would slide every comskip cut point and every caption cue out of sync, and would also break resume/seek (see the trickplay note below). Enhancement changes pixels and bitrate only; it must not renumber time.
- **Trick-play and Resume.** FF/REW/JUMP and "Resume Playback" seek by restarting the transcode from an offset. For the enhanced (re-encoded) path this only behaves correctly when the timeline is preserved across the restart, exactly as the sidecars require. Timeline preservation for the seek path is tracked separately from this document.
- **Recording integrity is absolute.** Enhancement is a playback-only transform. It never rewrites the recording, never touches the recording mux, and never runs against an active recording file (`activeFile`); the sidecars on disk are read-only inputs to playback.

### Live OTA

- **Category resolution comes from the guide airing.** A live OTA show resolves its `Show` through the currently-airing `Airing` on the channel, so the same profile map applies. If the guide data is thin or the airing cannot be resolved, the frame-rate proxy takes over (60 fps OTA sports → `HIGH`), which is the correct conservative default.
- **Latency dominates.** The live path keeps `-bf 0`, a short GOP, and the opt-in-only `-rc-lookahead`/`-multipass` (off by default) so trick-play and live latency are protected. Motion class only moves the bitrate rung; it does not add encode latency.
- **Admission still governs.** Live enhancement is subject to the same GPU governor admission and recording-priority rules as recorded playback, so a live upscale never competes with an in-progress recording.

In both cases the pipeline is identical after the mode decision: resolve profile → motion class → bitrate rung → clamp. Only the metadata source (finished `Show` versus live airing) and the artifacts that must stay aligned (sidecars on a recording) differ.

---

## 4K Upscale Bitrate Table

This table is used only when server-side enhancement has been selected. Bitrates are a
property of the HEVC/NVENC encode and are the same whether the scaler stage is Lanczos
(today) or AI VSR (planned) — the scaler changes picture quality, not the target bitrate.

| Tier / `scale_npp` target | HIGH | MEDIUM | LOW |
|---|---:|---:|---:|
| `enhance_2160p` at 3840×2160 | 40000 kbps | 28000 kbps | 20000 kbps |
| `enhance_1440p` at 2560×1440 | 24000 kbps | 17000 kbps | 13000 kbps |
| `enhance_1080p` at 1920×1080 | 14000 kbps | 10000 kbps | 7000 kbps |
| `deinterlace_only` | Source-bitrate anchored, minimum 6000 kbps | Source-bitrate anchored, minimum 6000 kbps | Source-bitrate anchored, minimum 6000 kbps |

Derived values:

```text
-maxrate = 1.5 × selected bitrate
-bufsize = 2 × maxrate
```

Clamp order:

```text
1. Base table cell
2. Per-cell override:
   playback/gpu_enhance/bitrate/<tier>/<motion>
3. Administrative cap:
   max_bitrate_kbps
4. Final advisor clamp:
   min(genreEst, fpsEst)
```

The final `min()` guarantees that genre classification can lower the estimate but cannot exceed bandwidth already admitted by the client advisor.

### Mode-specific bitrate behavior

```text
if selectedMode == RTX_VSR_TO_2160:
    genre → motion class
    tier = enhance_2160p
    apply bitrate table, override, cap, and advisor clamp

if selectedMode == RTX_VSR_TO_1080_CLIENT_SCALE:
    genre → motion class
    tier = enhance_1080p
    apply bitrate table, override, cap, and advisor clamp

if selectedMode == DEINTERLACE_CLIENT_SCALE:
    estimate bitrate for progressive native-resolution output
    apply the source-complexity minimum and admission cap
    do not use the 4K bitrate cells

if selectedMode == DIRECT_CLIENT_SCALE:
    do not select an enhancement bitrate
```

---

## Encoder Settings

For enhanced files:

```text
-c:v hevc_nvenc
-preset p4
-tune hq
-rc vbr
-bf 0
-g <fps*2>
-spatial_aq 1
-temporal_aq 1
-fps_mode passthrough
-tag:v hvc1
```

Opt-in and off by default for the live and trick-play path:

```text
-aq-strength
-rc-lookahead
-multipass
```

These options remain disabled by default when their additional latency is undesirable.

### Important implementation distinction

`scale_npp` and `hevc_nvenc` do not by themselves mean that RTX Video Super Resolution is active.

The implementation must distinguish:

```text
RTX 5080 + scale_npp
    = conventional NPP GPU scaling plus NVENC encoding

RTX 5080 + integrated RTX Video SDK/VSR path
    = RTX AI artifact reduction and super-resolution processing

RTX 5080 + another AI model
    = quality determined by that model and its settings
```

Only a genuine AI VSR integration (Maxine SuperRes / RTX Video SDK) should be reported to
users or telemetry as "AI"/"VSR" enhancement. The `scale_npp` (Lanczos) path shipping today
is classified as GPU-Lanczos enhancement — it may still populate a `RTX_VSR_TO_*` tier
*intent*, but it must never be labelled as AI super-resolution anywhere user- or
operator-visible.

---

## Recommended Defaults

```text
client_upscale_policy = automatic
quality_tie_preference = client
unknown_android_scaler = server
shield_2019_ai_scaler = trusted
shield_2015_scaler = conditionally_trusted
tizen_2024_scaler = conditionally_trusted
interlaced_client_input = disallowed_by_default
deinterlace_output_rate = preserve_field_motion
rtx_vsr_for_compressed_source = preferred
rtx_vsr_for_clean_1080p_to_shield_2019 = disabled
rtx_vsr_for_clean_720p_to_shield_2019 = auto
```

### Administrator policy levels

| Setting | Behavior |
|---|---|
| `efficiency` | Use client upscaling whenever the recognized client is competent |
| `automatic` | Use the source, client, and quality crossover matrices in this document |
| `maximum_quality` | Prefer RTX VSR whenever GPU load, admission, latency, and playback stability permit |

---

## Final Policy Summary

1. Inspect actual source resolution, scan type, cadence, frame or field rate, codec, bitrate, and compression quality.
2. Resolve an exact or conservative client capability profile.
3. Apply only mandatory preprocessing first.
4. Treat deinterlacing and upscaling as separate decisions.
5. Use RTX VSR only when it is expected to produce a meaningfully better displayed result than the attached client's scaler.
6. For clean 720p or 1080p sent to a 2019 Shield Pro, let Shield AI upscale when the expected result is effectively tied.
7. For compressed or artifact-heavy material, favor RTX VSR when its artifact reduction and reconstruction produce a visible improvement.
8. For 1080i, deinterlace or IVTC on the server; let a capable client upscale when the final user experience is effectively equivalent.
9. For unknown or basic client scalers, use a conservative server-enhancement policy.
10. Run EPG motion classification and bitrate selection only after deciding that server-side enhancement is warranted.
11. Preserve direct-play and remux paths whenever additional processing does not improve actual viewing experience.
12. Apply the same category-to-capability map to both recorded playback and live OTA; preserve the presentation timeline so comskip and caption sidecars stay aligned.
13. Never equate the presence of an RTX GPU and `scale_npp` with the use of RTX Video Super Resolution.
```
