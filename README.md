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

`ctxmon_gtk.py` is a GTK window that shows what your local `llama-server` is doing while you benchmark or
work. It is not needed to run the suite. It shows, per server:

- **Context usage:** percent, tokens used / total, tokens free; bar turns orange at 70%, red at 90%.
- **Exact vs estimate:** a line under the count says where the number comes from. *Exact* means the request
  finished and the server log gave the context size. *Estimate* (shown with `~`) means a request is running:
  the log's prompt size plus the tokens `/slots` says were generated so far, or, while a new prompt is still
  being processed, the previous request's size.
- **Speed:** live tokens/sec while generating, then the final figure; prompt-processing speed of the last request.
- **Graphs:** context over time and speed over time (last 10 minutes by default; `--history MIN`).
  Needs `python3-gi-cairo`; without it the rest still works.
- **Recent requests:** a table of the last 50 finished requests (prompt tokens and speed, generated tokens
  and speed, context size afterwards). Requests already in the log when ctxmon starts show "earlier". The
  server log has token counts and speeds but not the prompt text, so prompts are tracked by size.
- **Warnings:** a banner in the panel and a desktop notification (`notify-send`) when context passes 80% and 95%
  (`--warn 80,95`), and when generating at the current speed would fill the context within a minute.
  Warnings re-arm once usage drops 10 points below a level. Notifications can be switched off in Options.
- **GPU memory:** used / total VRAM of the largest GPU (AMD via sysfs, or `nvidia-smi`), shared by all panels. The
  bar is coloured by vendor, not by how full it is (a full GPU is normal when a model is loaded): red for AMD,
  green for NVIDIA, blue for Intel. Intel iGPUs share system RAM and expose no VRAM counters, so blue only
  appears if a driver provides the same sysfs files.
- **Layout:** each server is a compact card: model and run state on top; context (percentage, bar, details)
  beside generation and prompt speed; the two graphs side by side; then the request table. Everything sits on
  the same two-column split: the speed block starts above the speed graph, its figure shares a baseline with
  the percentage, and the request table's six equal columns span the full width. The exact/estimate
  line is a single line (full text in its tooltip) placed last in its block, so it can never shift anything.
- **No scroll bars:** the window opens at the size of its content (labels reserve room for their longest normal
  text) and resizes when you collapse, expand or hide a section. The request table is a fixed grid of the newest
  8 requests (`--rows N`, up to 50 are kept in memory). With several servers the cards sit side by side, so the
  window gets about 520 px wider per server.
- **Several servers:** one card per server, side by side.
- **System tray:** an icon with a menu (show/hide window, per-server percentages, quit) and the worst context
  percentage as its label. "Hide to tray" keeps it running without a window; `--minimized` starts hidden. Left-click
  opens the icon's menu (Show window / Hide window / Quit); middle-click shows the window.
  The window's own Close button still quits. Uses AppIndicator (`gir1.2-ayatanaappindicator3-0.1`, with the
  AppIndicator shell extension on stock Ubuntu GNOME), falls back to the old GTK status icon, and is skipped
  if neither works (`--no-tray` turns it off).
- **Terminal:** a collapsible section in a pane you can drag to resize, with a fresh shell (your `$SHELL`, started
  in the directory ctxmon was launched from) so you can run commands without leaving the window. Needs
  `gir1.2-vte-2.91`. It starts the first time you open it. The **+** button (or Ctrl+Shift+T) adds another tab with
  its own shell; a tab's x button (or Ctrl+Shift+W) closes it, and a tab closes by itself when its shell exits.
  Tabs can be dragged to reorder and are named after the shell's title. Ctrl+Shift+C / Ctrl+Shift+V copy and
  paste. Tabs are not remembered between runs.
- **Show / hide and collapse:** Options has a checkbox for every part of the window (model and port, percentage
  and bar, details, generation speed, both graphs, recent requests, GPU memory, terminal, status line).
  Details, both graphs, recent requests, GPU memory and the terminal also have a chevron to collapse or expand
  them; GPU memory keeps its numbers in the title when collapsed. Both choices are remembered.
- **No server running:** everything reads zero.

It polls each server's `/slots` and `/props` endpoints and reads its log file for token counts and finished
requests, so every server must be started with `--log-file`. It only talks to your local servers.

```bash
python3 ctxmon_gtk.py                                  # defaults: http://127.0.0.1:8080, ~/llama-server-qwen.log
python3 ctxmon_gtk.py --log ~/llama-server-qwen.log    # server log file
python3 ctxmon_gtk.py --url http://127.0.0.1:8080      # server address / port
python3 ctxmon_gtk.py --ctx 131072                     # fallback context size
python3 ctxmon_gtk.py --interval 0.5                   # seconds between updates

# several servers at once (replaces --url/--log; name and ctx are optional)
python3 ctxmon_gtk.py \
  --server name=qwen,url=http://127.0.0.1:8080,log=~/llama-server-qwen.log \
  --server name=other,url=http://127.0.0.1:8081,log=~/llama-server-other.log,ctx=32768

python3 ctxmon_gtk.py --warn 70,90 --history 30 --rows 12 --no-tray --minimized   # other options
```

Needs `python3-gi` and `gir1.2-gtk-3.0` (already on Ubuntu desktop). Optional: `python3-gi-cairo` (graphs),
`gir1.2-ayatanaappindicator3-0.1` (tray), `gir1.2-vte-2.91` (terminal), `libnotify-bin` (notifications). The **Options** button sets Always on
top, window opacity (30-100%), notifications and which sections are shown, saved in `~/.config/ctxmon/settings.json`. `ctxmon.svg` is the icon.

Troubleshooting:
- *Context stays at 0 / "no request in the log yet":* check the log format with
  `grep -E "n_tokens|n_past" ~/llama-server-qwen.log | tail -3`
- *Speed never says "(live)":* run `curl -s localhost:8080/slots | python3 -m json.tool` while generating and look for `n_decoded`.
- *"server busy / not responding":* the server is slow to answer (often a long prompt); the last numbers stay on screen.
- *Tray icon can't bring the window back:* use the menu's "Show window" (or middle-click the icon); if the menu does
  nothing, tell us which desktop you use.
- *No tray icon:* install the AppIndicator extension/library, or run with `--no-tray`; the window works either way.
- *"GPU memory: not available":* only AMD (sysfs) and `nvidia-smi` are read.

## Methodology and handover

[TESTING.md](TESTING.md) explains how the measurements work, how to read a report, the pitfalls we hit,
and how to run a new model. [HANDOFF.md](HANDOFF.md) is the continuity log: where things stand, the
test machine, open items. Both are written so another person or AI agent can pick the work up cold.

## Licence

GPL-3.0 (see [LICENSE](LICENSE)). The Kotlin files in `corpus/kotlin/` come from
[WeatherAPP Libre](https://github.com/adamjeremy-sk/WeatherAPP), also GPL-3.0.
