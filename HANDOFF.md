# HANDOFF.md — llm-suite-test continuity log

For the next agent (or person) working on this suite with Adam. **Read order:** README.md → TESTING.md
(method, rules, pitfalls) → this file (where things stand, the environment, how we work, what's next).
Update §1 and add a §6 entry whenever a session ends.

## 1. Current state  *(overwrite every update)*

| Field | Value |
|---|---|
| Updated | 2026-11-05 ~16:00 |
| Repo | github.com/adamjeremy-sk/llm-suite-test (public, GPL-3.0), local `~/Projects/llm-suite-test`, branch `main` |
| Prompt set | v2 (v1 = refactor, codegen, prose + longctx/xlctx; v2 added math, logic). Core geo-mean = v1 short prompts |
| Models tested | qwen3.8-27b (complete: see models/qwen3.8-27b/REPORT_2026-11-04.md) |
| Adopted config | Qwen3.8-27B: MTP `--spec-draft-n-max 5 --spec-draft-sampling probabilistic` (~90 t/s short, 87 / 71 t/s at 22k / 50k context; 39 t/s without drafting) |
| Active task | None |
| Tools | Also in repo: `ctxmon_gtk.py` + `ctxmon.svg`, an optional GTK live monitor (context + speed graphs, request history, multi-server, VRAM, warnings, tray; documented in README.md, **GUI untested on real GTK**, see §6; the `.desktop` entry and `ctxmon` launcher are intentionally not in the repo) |
| **Caveat** | The current `tools/` (bench.py, sweep.sh, report.py) were rebuilt for this repo on 2026-11-04 and so far tested only against a fake server. All Qwen3.8 results came from their direct predecessors (same prompts, byte-identical, same method). **First real run should be a quick baseline** to confirm: `SERVER_SCRIPT=~/.local/bin/qwen-server NO_LONG=1 RUNS=1 bash tools/sweep.sh configs/baseline.txt` |
| Next action | Adam's choice. Candidates in §5; the planned one is benchmarking a Qwen 4 27B when released (same llama.cpp build first). |

## 2. Adam's environment

| | |
|---|---|
| Machine | Ubuntu desktop; Intel CPU with UHD 770 iGPU; AMD Radeon RX 7900 XTX 24 GB |
| GPU tuning (all published runs) | power limit 300 W (stock 325), voltage −25 mV, core 2800 MHz (stock 2900), memory offset +1300 (stock +1250). Pass as `HW_NOTES="PL 300W, -25mV, core 2800, mem +1300"` |
| Backend | **Vulkan (RADV)**. Vulkan0 = 7900 XTX, Vulkan1 = the iGPU → every server script pins `--device Vulkan0`. ROCm was benchmarked on 2026-11-03 (+20% prompt processing, −6% generation) and **dropped by Adam; don't re-propose unless he asks** |
| llama.cpp | prebuilt Vulkan binary `~/.local/bin/llama`, run as `llama server` (process name `llama`); `version 0.8.0-dev (build 12019, commit 1052f7e1d)` |
| Server scripts | `~/.local/bin/qwen-server` (current; copy in models/qwen3.8-27b/server/), `~/.local/bin/qwen-server.bak-20261104` (before probabilistic drafting). Others present: `gemma-server`, `nanbeige-server` |
| Model files | Hugging Face cache, e.g. `~/.cache/huggingface/hub/models--Abiray--Qwen3.8-27B-Q4_K_M-GGUF/...` |
| Port | 8080 for every server; only one runs at a time |
| Stopping servers | `pkill -f 'llama[ -]server'` → `ps -A | grep llama` → `kill -9 <pid>` for any survivor (a stopping server can keep its VRAM; see TESTING.md §6) |
| Raw output | `runs/` in the repo (git-ignored). The earliest raw data (2026-11-04) is in `~/Downloads/{mtp,speed}_sweep_*` and `~/AndroidStudioProjects/server-tests/`; the reports in models/qwen3.8-27b/results/ contain every per-request row anyway |

## 3. How we work

- **Adam runs the sweeps** on his machine. The agent can't reach his `localhost:8080` (neither from the
  cloud workspace nor from the desktop-link VM), so the agent writes plans/tools, **tests them against a
  fake server in its own workspace first**, then gives Adam the exact command. Adam reports progress and
  sends the report (or the agent reads it from `models/<m>/results/` when the chat is linked to his
  computer and `~/Projects` is connected).
- Long sweeps run ~5–7 min per config; tell Adam the expected duration. He watches the terminal and will
  kill stuck servers himself; the harness now handles that too.
- **Git:** Adam owns the repo. When he asks, the agent may commit (repo-local identity
  `adamjeremy-sk <adamjeremy-sk@users.noreply.github.com>` is set in this repo) and push. Pushing from the
  desktop-link VM has no credentials: commit there, `git bundle` the new commits into the cloud workspace,
  push from there (fast-forward only), then `git update-ref refs/remotes/origin/main <sha>` locally.
  Adam's *global* git identity is a placeholder ("Your Name"); his call whether to fix it.
- **When copying files to Adam's machine via the desktop link, verify the checksum afterwards** — on
  2026-11-04 a reported-successful copy hadn't landed when the next command ran (it was fine on retry).
- Adam's style: plain explanations, tables for numbers, recommend one option clearly; when he asks for
  "exhaustive", give the full list ranked by payoff. He decides; don't run more tests than he asked for.
- **Gemma is permanently retired (Adam, 2026-11-03): never benchmark, suggest or prompt for it.** Its
  results are kept only in WeatherAPP's history.
- Related project: WeatherAPP Libre (`~/AndroidStudioProjects/WeatherAPP`, its own claude.ai Project and
  HANDOFF.md). It uses Qwen3.8-27B as its coding agent, which is why this suite exists. Keep the two
  separate: WeatherAPP's handoff only records the adopted server config.

## 4. Decision log  *(append only, newest last)*

- 2026-11-04 — Speculative decoding is lossless by design, so tuning it is a **speed** question: measure
  t/s and acceptance, not quality (quality checks optional via saved responses).
- 2026-11-04 — Qwen3.8-27B draft length: N=5 best; **N≥8 is unusable on this Vulkan build** (verify step
  jumps from 54 to ~128 ms at a 9-token batch). Keep N ≤ 7 in plans for this hardware.
- 2026-11-04 — (Adam) Adopted N5 + `--spec-draft-sampling probabilistic` without further confirmation runs.
- 2026-11-04 — Ruled out on this setup: p-min early stopping (once probabilistic is on), n-min 2, KV f16
  (doesn't fit at 131k ctx), -ub 1024/2048, MTP + ngram-mod, min-p 0.05 (no speed effect).
- 2026-11-04 — (Adam) Suite spun off from WeatherAPP into this public repo, GPL-3.0, so others can tune
  similar hardware. Usernames/paths in reports are fine to publish.
- 2026-11-04 — Prompt files and corpus are frozen; new prompts = new files + bump prompts/VERSION. Core
  geo-mean stays on refactor/codegen/prose so results remain comparable across versions.
- 2026-11-04 — Published reports are immutable; corrections go in a note at the top or in the REPORT file.
- 2026-11-04 — Each sweep repeats its reference config first and last (drift check).

- 2026-11-05 — (Adam) ctxmon (live context/speed monitor) joins the repo as an optional tool; its docs live in
  README.md (no separate README-ctxmon.md). `.desktop` file intentionally left out.

- 2026-11-05 — (Adam) ctxmon gets: context/speed graphs, multi-server, VRAM, context warnings, request
  history, exact-vs-estimate token display, system tray. Request history tracks prompt *sizes*, not text (the
  server log doesn't contain prompts). Tray close behaviour: the Close button quits; "Hide to tray" hides.

- 2026-11-05 — (Adam) ctxmon also gets an embedded terminal (VTE, collapsed by default), Options checkboxes to
  show/hide each section, and chevrons on more sections. The exact/estimate line has a fixed two-line slot
  (Adam's screenshot showed it wrapping and pushing the lines below it).

- 2026-11-05 — (Adam) ctxmon layout redone as compact two-column cards (context | speed, graphs side by side,
  terminal in a draggable pane). GPU memory bar colour = vendor (AMD red, NVIDIA green, Intel blue), not fullness.
  GPU memory stays one shared window-level strip, not per card.

- 2026-11-05 — (Adam) ctxmon: no scroll windows; window opens at natural size and refits when sections are
  toggled. Request table is a fixed grid of the newest N rows (`--rows`, default 8), not a scrolled list.

- 2026-11-05 — (Adam) ctxmon: terminal gets tabs (+ button, Ctrl+Shift+T/W), not persisted. Context/speed/graphs and the
  request table share one two-column grid. Section toggles resize the window synchronously (no flash).

- 2026-11-05 — (Adam) ctxmon: dropped the Gtk.Paned (it left dead space and, after collapse/expand cycles, a clipped
  layout). Terminal is sized in text rows (setting `terminal_rows`, Options spinner, default 18) and takes spare
  height only while open. Window refit runs immediately and once more from idle (no-op if already right).
  Tokens and free are one line; the "full in ..." estimate moved under the prompt speed.

- 2026-11-05 — (Adam) ctxmon: context bar spans the full width; generation / prompt-processing / time-until-full are
  three tiles in a strip below it (replaces the right-hand column and the 2-column stats grid).

## 5. Open items / backlog

- **First real run of the rebuilt tools** (see §1 caveat).
- **Same-session A/B** of N5 greedy vs N5 probabilistic to pin the gain (single run so far; likely 3–7%):
  `configs/mtp-tuning.txt` with `ONLY="ref n5_prob ref_end"`.
- **Memory overclock check:** +1300 vs stock +1250 (generation is bandwidth-bound; GDDR6 error retries can
  make an over-limit memory clock slower without crashing). Run `configs/baseline.txt` at each setting,
  same session.
- **Prompt-cache test** (biggest real-world lever): agent turns re-read 22–50k tokens cold (30–82 s).
  Build a plan/prompt that mimics agent turns — fixed long prefix, growing suffix — and measure how much
  `--cache-reuse` / `--cache-ram` save.
- **Quality check of probabilistic drafting:** compare saved `responses_*.jsonl` (math/logic have answers
  in prompts/ANSWERS.md). Could become an automatic grader.
- **Qwen3.8 thinking-mode sampling:** the server uses temp 0.7 / top-p 0.80 / top-k 20 / min-p 0; earlier
  Qwen3 cards recommended 0.6 / 0.95 / 20 with thinking on. Check the Qwen3.8 card (affects quality, and
  acceptance rates).
- Untested flags seen in this build: `--spec-draft-type-k/-v` (draft KV cache type), `--spec-draft-n-ubatch`,
  `--spec-draft-p-split`.
- **Qwen 4 27B** (when released): new `models/<alias>/`, its own server script, baseline → draft-length →
  tuning. Re-run Qwen3.8's baseline on the same build if llama.cpp is updated first.
- **First real run of the new ctxmon** (see §6 2026-11-05): graphs, tray, notifications and multi-server were never run on real GTK.
- Tooling: VRAM reading is AMD-sysfs or `nvidia-smi` only; add others if contributors need them.

## 6. Session log  *(newest first)*

### 2026-11-05 — README rewrite + screenshot
- README's ctxmon section restructured (what it shows / window behaviour / usage / troubleshooting) and now has
  docs/ctxmon.png (Adam's screenshot, pixel-identical to what he attached; the transfer re-encoded the PNG).
  The screenshot's terminal tab shows another of Adam's projects (vermilion-editor) and a commit message, so
  check that is OK before pushing a public repo.

### 2026-11-05 — ctxmon full-width bar and speed tiles
- Replaced the stats grid with: pct + full-width bar, Details, then a homogeneous 3-tile strip (title, value, note).
  "Time until full" shows "–" unless generating (was text under the prompt speed). Logic tests + stubbed-GTK smoke pass;
  look at the real window.

### 2026-11-05 — ctxmon dead space / collapse-expand layout bug / taller terminal
- From Adam's screenshots: big empty area under a short terminal, and after collapsing and re-opening sections the
  top of the window was clipped. Both came from the Paned (GTK gives the whole leftover height to pane 2 until the
  position is set, and pane 1 may shrink below its content). Removed it. Unverified on real GTK: check collapse /
  re-open cycles of every section, the terminal rows spinner, and dragging the window taller.

### 2026-11-05 — ctxmon alignment, flash fix, terminal tabs
- Alignment (from Adam's screenshot): stats are one homogeneous 2-column Grid (same split as the graph row;
  percentage and speed baseline-aligned); request grid has 6 equal columns across the full width.
- Flash on expand/collapse: the window refit ran from an idle callback, so one frame was drawn at the old size;
  it now runs synchronously inside the toggle handler. Not verified visually (no real GTK here).
- Terminal: Gtk.Notebook of Vte terminals, "+" action button, closable/reorderable tabs, tab named from the shell
  title, Ctrl+Shift+T/W. Verified only against stubs (new/close bookkeeping); real VTE untested here.
- File copy to Adam's machine again needed a second device_commit_files; checksum check caught it.

### 2026-11-05 — ctxmon sizing without scroll bars; tray reopen bug fixed
- **Tray bug (Adam: "closing to tray works, opening back up does not")**: `MonitorWindow.show` was an attribute
  (visibility settings dict) that shadowed `Gtk.Window.show()`, so the tray's Show crashed. Renamed to `shown`;
  also renamed `notify` (it shadowed `GObject.notify`) to `send_notification`. Lesson: never name an attribute
  on a Gtk subclass after a Gtk method. Tray also now updates only when its text changes (it was sending D-Bus
  updates twice a second), has separate Show/Hide menu items, and middle-click shows the window.
- Sizing: removed ScrolledWindows, label min widths reserve room, request table is a Grid, window refits when
  sections are toggled. Not verified on real GTK (sizes are by character width, so check the screenshot).
- On opening the repo, `ctxmon_gtk.py` was missing from the working tree (`git status` showed it deleted; not done
  by the agent, probably moved by Adam). The updated file was written back to the same path.

### 2026-11-05 — ctxmon compact layout, vendor-coloured GPU bar
- Rebuilt the ServerPanel/MonitorWindow layout (see decision log). Logic tests + stubbed-GTK smoke pass; the real
  rendering (sizes, Paned behaviour when the terminal expands, graph heights) is unverified here - Adam's
  screenshots are the check. `read_vram()` now returns (used, total, name, vendor).
- File copy to Adam's machine failed silently on the first attempt again (checksum showed the old file); a
  second device_commit_files fixed it. Always verify the checksum after copying.

### 2026-11-05 — ctxmon terminal, show/hide options, more chevrons, layout fix
- Added: VTE terminal section, per-section visibility checkboxes (settings.json keys `show`, `expanded`),
  expanders for details/graphs/requests/GPU memory/terminal. Fixed the layout shift from the wrapping
  estimate line (fixed 2-line slot, ellipsis) and shortened its text. Logic tests and a stubbed-GTK smoke run
  pass; the terminal (VTE) path and the show/hide wiring have not run on real GTK here. Adam's screenshot
  shows the earlier version does run on his machine.

### 2026-11-05 — ctxmon feature pass (graphs, multi-server, VRAM, warnings, history, tray)
- Rewrote ctxmon_gtk.py: logic (state/estimates/warnings/graph geometry/VRAM/log parsing) is separate from
  GTK and imports without it. Tested here against a fake HTTP server + growing log and a stubbed-GTK smoke
  run (catches typos only). **Not tested: real drawing, tray, notifications, layout, multi-panel sizing.**
- Behaviour change: request ctx size = the `stop processing` line *after* the timing lines (llama.cpp prints
  timings first). Warn levels 80/95 + an "will fill within 60 s" warning; re-arm 10 points lower.
- No tests are in the repo (they live in the agent's scratchpad); say if they should be added under tools/.

### 2026-11-05 — ctxmon added to the repo; docs updated
- Read HANDOFF.md and the new README-ctxmon.md. Merged the ctxmon docs into README.md (new section, Layout
  entry; run via `python3 ctxmon_gtk.py`), deleted README-ctxmon.md, Adam added ctxmon.svg.
- Not run: no real benchmark this session. The §1 caveat (rebuilt tools tested only against a fake server)
  and all §5 items still stand. ctxmon was not run either (no GTK in the agent's VM); docs were checked
  against the script's argparse defaults and `qwen-server`'s `--log-file` path only.

### 2026-11-04 — Qwen3.8-27B speed tuning; suite created
- Started inside WeatherAPP: Adam changed MTP draft length to 5 and asked for a benchmark. Six "different"
  runs were identical — the server script hard-coded `--spec-draft-n-max 5`; caught from the log's
  `mean len`. Built a proper harness (one server start per config, from a temp copy of the script).
- Passes: draft length 0–8 → speed round 1 (14 configs, 22k context) → round 2 (13 configs, 22k + 50k,
  cached reruns) → `n5_prob` rerun. Winner adopted; full story in models/qwen3.8-27b/REPORT_2026-11-04.md.
- Incident: a stopping server held VRAM and the next config spilled to RAM (~4 t/s); harness hardened.
- Report bug fixed: round 2's short geo-mean had included the 50k prompt; report rebuilt from raw rows.
- Moved everything into this repo, made the tools model-agnostic, added math/logic prompts (answers
  verified by brute force), wrote README/TESTING, set the GPL-3.0 licence. Adam created the GitHub repo
  and pushed the first commit (488e47d).
