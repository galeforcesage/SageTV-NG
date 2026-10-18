# Comskip option validation

The profile pack targets the bundled Comskip 0.83.001 option set. Active keys
are checked by `validate_profiles.py`; unknown keys and duplicate active keys
fail validation.

## Content-delta options

| Option | Purpose |
|---|---|
| `detect_method` | Selects the enabled detector bitmask. Values must be 0-255. |
| `min_silence` | Minimum silence length used as detector evidence. |
| `min_commercialbreak` | Rejects shorter candidate commercial blocks. |
| `min_commercial_size` | Rejects shorter individual commercial candidates. |
| `max_commercial_size` | Retains long pods whose internal boundaries are weak. |
| `min_show_segment_length` | Protects likely program segments from fragmentation. |
| `punish_no_logo` | Uses reliable logo absence as commercial evidence. |
| `logo_filter` | Smooths transient bad or missing logo matches. |
| `cut_on_ar_change` | Controls direct cuts on aspect-ratio transitions. |

`lowres=0` is pinned in the base because bundled Comskip has reproduced heap
corruption with `lowres=1` on MPEG-PS input.

## Channel skeleton options

The commented channel skeleton also illustrates supported station-specific
options: `ticker_tape`, `ticker_tape_percentage`, `logo_at_bottom`,
`max_avg_brightness`, and `max_volume`. These require measurements from the
target station and are intentionally inactive.
