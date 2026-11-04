# Kotlin corpus (frozen)

These five files are the long-context material for the `longctx` and `xlctx` prompts.

- Source: WeatherAPP Libre (Android, Kotlin) — https://github.com/adamjeremy-sk/WeatherAPP
- Path in that repo: `app/src/main/java/com/weatherapp/libre/`
- Commit: `b169fa0` (2026-11-04). Copied verbatim; **never edit them**, or results stop being comparable.
- Licence: GPL-3.0 (see `LICENSE` in this folder), same as the source project.

`ORDER.txt` fixes the order they are concatenated in for `xlctx`. `longctx` uses only the first file
(SettingsDialogManager.kt, ~22k tokens with the Qwen3 tokenizer); `xlctx` uses all five (~50k tokens).
Token counts differ between tokenizers, so the report always prints the prompt size each model actually saw.
