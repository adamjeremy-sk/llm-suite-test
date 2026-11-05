# llm-suite-test

Repeatable **speed** benchmarks for local LLMs on llama.cpp: same prompts, same seeds, one server
setting changed at a time, with the results published per model so others can tune similar hardware.

It answers questions like: *how many speculative draft tokens should I use, does probabilistic drafting
help, what does 50k tokens of context cost me, does this flag change anything at all?*

## Results so far

| Model | Hardware | Best setting found | Short prompts | 22k ctx | 50k ctx | No-drafting baseline | Write-up |
|---|---|---|---|---|---|---|---|
| Qwen3.8-27B Q4_K_M | RX 7900 XTX 24 GB, Vulkan | MTP, `--spec-draft-n-max 5 --spec-draft-sampling probabilistic` | 90 t/s (99–110 code) | 87 t/s | 71 t/s | 39 t/s | [report](models/qwen3.8-27b/REPORT_2026-11-04.md) |

Numbers are llama.cpp's own generation speed (`predicted_per_second`), medians, with thinking on.
"Short prompts" is the geometric mean of the refactor / codegen / prose prompts.

## Quick start

Requirements: Linux, bash, Python 3 (standard library only), `curl`, a llama.cpp build with
`llama-server` (or the `llama server` launcher), and a **server launch script** for your model.

```bash
# 1. Your server script: one flag per line ending in " \", with --alias, --port and --log-file.
#    Example: models/qwen3.8-27b/server/qwen-server
# 2. Stop any running llama.cpp server, then:
SERVER_SCRIPT=~/.local/bin/my-model-server bash tools/sweep.sh configs/baseline.txt
# 3. The report lands in models/<alias>/results/<date>_baseline.md
```

Then try a plan that changes things, e.g. `configs/draft-length.txt` (speculative draft length 0–8)
or `configs/mtp-tuning.txt`. Each line of a plan is one server config; see [TESTING.md](TESTING.md).

A full sweep takes ~5–7 minutes per config (most of it on the 22k/50k-token prompts). For a quick look,
`NO_LONG=1 RUNS=1` cuts that to about a minute.

## Layout

```
tools/        bench.py (client), sweep.sh (runs a plan), report.py (builds the report, edits configs)
configs/      test plans: one config per line
prompts/      frozen prompt set (VERSION, short/*.txt, long-context task templates, ANSWERS.md)
corpus/       frozen long-context material (5 Kotlin files, GPL-3.0, see corpus/kotlin/SOURCE.md)
models/<m>/   per model: README, REPORT_*.md write-ups, server/ scripts, results/ (one .md per sweep)
runs/         raw output: CSVs, model responses, server logs (git-ignored)
ctxmon_gtk.py, ctxmon.svg   optional live monitor for the running server (see below)
```

## Contributing results

Run the plans on your hardware and add `models/<your-model-alias>/` with your server script and
results. Put your GPU/driver tuning in `HW_NOTES` so it lands in the report header. Results are only
comparable when the prompt set version (`prompts/VERSION`) and the plan match; llama.cpp version and
hardware are printed in every report.

## ctxmon: live server monitor (optional)

`ctxmon_gtk.py` is a small GTK window that shows what your local `llama-server` is doing while you
benchmark or work: model and port, context usage, and generation speed. It is not needed to run the suite.

- **Context usage:** percent, tokens used / total, tokens free (bar turns orange at 70%, red at 90%).
  While a request is generating the count is an estimate, marked with `~`.
- **Speed:** live tokens/sec while generating, then the final figure; prompt-processing speed from the last finished request.
- **No server running:** everything reads zero.

It polls the server's `/slots` and `/props` endpoints and reads the server's log file for token counts
and final speeds, so the server must be started with `--log-file`. It only talks to your local server.

```bash
python3 ctxmon_gtk.py                          # defaults: http://127.0.0.1:8080, ~/llama-server-qwen.log
python3 ctxmon_gtk.py --log ~/llama-server-qwen.log   # server log file
python3 ctxmon_gtk.py --url http://127.0.0.1:8080     # server address / port
python3 ctxmon_gtk.py --ctx 131072                    # fallback context size
python3 ctxmon_gtk.py --interval 0.5                  # seconds between updates
```

Needs `python3-gi` and `gir1.2-gtk-3.0` (already on Ubuntu desktop). The **Options** button sets
Always on top and window opacity (30-100%), saved in `~/.config/ctxmon/settings.json`. `ctxmon.svg` is the icon.

Troubleshooting:
- *Context stays at 0 / "no request in log yet":* check the log format with
  `grep -E "n_tokens|n_past" ~/llama-server-qwen.log | tail -3`
- *Speed never says "(live)":* run `curl -s localhost:8080/slots | python3 -m json.tool` while generating and look for `n_decoded`.
- *"server busy / not responding":* the server is slow to answer (often a long prompt); the last numbers stay on screen.

## Methodology and handover

[TESTING.md](TESTING.md) explains how the measurements work, how to read a report, the pitfalls we hit,
and how to run a new model. [HANDOFF.md](HANDOFF.md) is the continuity log: where things stand, the
test machine, open items. Both are written so another person or AI agent can pick the work up cold.

## Licence

GPL-3.0 (see [LICENSE](LICENSE)). The Kotlin files in `corpus/kotlin/` come from
[WeatherAPP Libre](https://github.com/adamjeremy-sk/WeatherAPP), also GPL-3.0.
