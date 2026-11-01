# Design tokens

`design/tokens.json` (W3C Design Tokens format) is the source of truth for the app's colours. Edit it, then run

```sh
scripts/gen_tokens.py          # writes theme/Color.kt and design/tokens.css
scripts/gen_tokens.py --check  # CI: fails if the generated files are stale
```

- `app/.../ui/theme/Color.kt` is generated (Kotlin names: `Primary40`, `BaselineColor`, ...). `Theme.kt` uses them.
- `design/tokens.css` holds the same values as CSS custom properties (`--klang-data-baseline`) for HTML prototypes.
  `tokens.json` can also be imported by tools that read the DTCG format (Style Dictionary, Figma token plugins).

Scope: only what the app defines itself. The seed colours (tone 40 light, tone 80 dark) feed the Material 3
scheme; every other role is the Material 3 default or follows the wallpaper (Material You, Android 12+), and the
typography is the unchanged Material 3 scale. A prototype therefore gets the data colours exactly and the
seed colours approximately. Add tokens (surfaces, type, spacing, shapes) here when the app starts overriding them.
