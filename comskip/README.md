# SageTV-NG Comskip profile pack

The effective content profiles are BASE plus six specialized deltas:

- `comskip_drama.delta.ini`
- `comskip_sitcom.delta.ini`
- `comskip_sports.delta.ini`
- `comskip_news.delta.ini`
- `comskip_game_reality.delta.ini`
- `comskip_movie.delta.ini`

These are tuned starting values, not official Comskip genre presets.
Documentary/factual, children, family, cooking, lifestyle, travel, true crime,
and special use BASE unless another exact format label wins.

The profile merge is part of commercial detection and has no separate enable
switch. When Comskip is enabled, SageTV-NG selects the base plus the applicable
content delta. Administrators who want base-only behavior can empty the active
settings from each `comskip_*.delta.ini` file and customize
`comskip_base.ini`; an empty delta is a valid no-op layer.

## Category precedence

Routing uses exact normalized labels and evaluates all categories:

1. MOVIE
2. explicit event sports (`Sports event`, `Playoff sports`)
3. NEWS
4. GAME_REALITY
5. SITCOM
6. DRAMA_SCRIPTED
7. explicit factual/BASE format
8. generic sport-name topic
9. broad BASE fallback

Examples:

- Movie + Comedy -> MOVIE
- Documentary + Football -> BASE
- News + Baseball -> NEWS
- Reality + Auto racing -> GAME_REALITY
- Sports event + Football -> SPORTS
- Family + Sitcom -> SITCOM

The complete mapping is in `category-profile-map.json`.

## Semantic merge order

Generated INIs contain one active definition per key. The intended future
overlay order is:

1. base
2. genuinely technical capture-source overlay
3. content profile
4. network/station overlay
5. exact channel overlay
6. show-specific overlay
7. runtime/live-TV overrides

`channel_override.example.delta.ini` is an inactive skeleton only. It is not
automatically selected by the current resolver.

## Safety deviation from the supplied pack

The SageTV-NG baseline pins `lowres=0`. `lowres=1` was reproduced causing heap
corruption in bundled Comskip 0.83 on MPEG-PS recordings. Content deltas inherit
the safe base value rather than repeating it.

## Validation

Run:

```bash
python3 validate_profiles.py
```
