# Effective content-profile differences

Each row lists values that differ from `comskip_base.ini`. All unlisted values,
including the verified-safe `lowres=0`, are inherited from the base.

| Profile | Key differences |
|---|---|
| DRAMA_SCRIPTED | `detect_method=123`, `min_silence=20`, `min_commercialbreak=20`, `max_commercial_size=300`, `min_show_segment_length=120`, `punish_no_logo=1`, `logo_filter=4` |
| SITCOM | `detect_method=123`, `min_silence=15`, `min_commercialbreak=15`, `max_commercial_size=300`, `min_show_segment_length=90`, `punish_no_logo=1`, `logo_filter=4` |
| SPORTS | `detect_method=43`, `min_commercialbreak=60`, `max_commercial_size=300`, `min_show_segment_length=90`, `logo_filter=4`, `cut_on_ar_change=0` |
| NEWS | `detect_method=123`, `min_silence=20`, `min_commercialbreak=20`, `max_commercial_size=300`, `min_show_segment_length=60`, `logo_filter=4`, `cut_on_ar_change=0` |
| GAME_REALITY | `detect_method=123`, `min_silence=15`, `min_commercialbreak=20`, `max_commercial_size=300`, `min_show_segment_length=90`, `logo_filter=4`, `cut_on_ar_change=0` |
| MOVIE | `detect_method=123`, `min_silence=30`, `min_commercialbreak=30`, `min_commercial_size=10`, `max_commercial_size=300`, `min_show_segment_length=180`, `punish_no_logo=1`, `logo_filter=4` |

The network/channel layer is a skeleton only. No channel classification or
automatic overlay selection is active.
