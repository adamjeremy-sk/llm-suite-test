# TESTING.md — how this suite works and how to continue it

Written for a person or an AI agent picking this up with no other context. Read this whole file before
running or changing anything.

## 1. What it measures

Generation speed (tokens/second) and prompt-processing speed of a **llama.cpp server**, under one server
configuration at a time, on a fixed set of requests. It does **not** grade answers; model responses are
saved (`runs/.../responses_<config>.jsonl`) so quality can be checked separately (`prompts/ANSWERS.md`
has the expected answers for the math and logic prompts).

Everything comes from llama.cpp's own per-request `timings` (`predicted_per_second`, `prompt_n`,
`prompt_per_second`, `draft_n`, `draft_n_accepted`) and its server log, not from wall-clock guesses.

## 2. How a sweep runs

`tools/sweep.sh <plan>` with `SERVER_SCRIPT=<your launch script>`:

1. Stops any llama.cpp server and waits until the **processes** are gone (see §6, pitfall 2).
2. For each line `name | flags | note` of the plan:
   - copies your server script into the run folder and applies the flag edits
     (`--flag=value` replaces that flag's line or adds it before `--host`; `--flag=DEL` removes it;
     `--log-file` is pointed at the run folder). **The original script is never modified.**
   - skips the config if any flag isn't in this llama.cpp build's `--help` (reported, not fatal);
   - starts the server, waits for `/health`, records load time and VRAM;
   - runs a 64-token speed check and skips the config if it's under `MIN_TPS` (default 20): that
     almost always means the model spilled out of VRAM;
   - runs `tools/bench.py` (below), then stops the server.
3. Builds `report.md` from the CSVs, logs and metadata, and copies it to
   `models/<model>/results/<date>_<plan>.md`. Raw files stay in `runs/` (git-ignored).

`tools/bench.py` sends, per config, in this order:
- each `prompts/short/*.txt` prompt `RUNS` times (default 3), **prompt cache off**;
- `longctx` (task + first corpus file, ~22k tokens) and `xlctx` (task + all 5 corpus files, ~50k tokens)
  `LONG_RUNS` times (default 5) with the **prompt cache on**: run 1 reads the prompt cold (that run's
  `prompt_per_second` is the prompt-processing figure); runs 2+ reuse it, so they measure generation at
  that context depth without paying 30–80 s each time.
- `max_tokens` 1024, seeds 1001, 1002, … identical for every config. With reasoning models the generated
  tokens include thinking.

## 3. Reading a report

- **Overview**: median generation t/s per prompt. **Core geo-mean** = geometric mean of refactor,
  codegen and prose (the original v1 short set; kept fixed so results stay comparable when prompts are
  added). "vs ref" compares with the plan's first line.
- **Agent-like view**: long-context generation and **cold** prompt processing with the prompt size.
- **Spread**: min / median / max per prompt. Treat differences smaller than the spread, or under ~5%
  between separate sessions, as noise.
- **Drafting detail**: `acceptance / tokens per check / ms per check / avg drafted per check`.
  - tokens per check = predicted ÷ (predicted − accepted); ms per check = tokens per check ÷ t/s.
  - **avg drafted per check is your proof that a draft setting took effect** (it should be ≈ N, or less
    with early stopping).
- **Appendix A** lists this build's speculative/sampling flags; **B** the server's draft log lines;
  **C** every request's raw numbers, so a report can be re-analysed without the raw folder.

The speculative-decoding cost model that explained everything on Qwen3.8-27B (RX 7900 XTX, Vulkan):
time per check ≈ fixed cost (≈25.6 ms short context, more at long context) + ≈3.8 ms per drafted token.
A drafted token only pays if it is reached-and-accepted often enough to cover its own cost, which is why
code (predictable) wants longer drafts than prose. See `models/qwen3.8-27b/REPORT_2026-11-04.md`.

## 4. Procedure for a new model

1. Write a server script for it (copy `models/qwen3.8-27b/server/qwen-server` as a template): one flag
   per line, `--alias <name>` (used as the results folder), `--port`, `--log-file`, `"$@"` last.
2. Make sure it fits: load it once by hand, check VRAM headroom, `--device` pinning if you have an iGPU.
3. `configs/baseline.txt` — the script as-is. Gives the reference numbers.
4. If the model has MTP heads (or you use another speculative type): `configs/draft-length.txt`, then
   `configs/mtp-tuning.txt` or your own plan around the winners. Put the reference first **and last**.
5. Change one thing per config. Re-test winners in the same session before adopting them.
6. Write `models/<model>/README.md` (best setting, hardware, links) and a `REPORT_<date>.md` with the
   story: setup, method, each pass's table, what was ruled out, open questions.

To compare two models fairly: same llama.cpp build, same GPU tuning, same `prompts/VERSION`, same plan.
If you update llama.cpp, re-run the previous model's baseline on the new build too.

## 5. Rules (to keep results comparable)

- **Never edit** `prompts/short/*.txt`, `prompts/long_task*.txt` or `corpus/`. To add or change prompts,
  add new files and bump `prompts/VERSION`; the core geo-mean only uses refactor/codegen/prose.
- **Never edit published reports** in `models/*/results/`. Corrections go in a note at the top or in
  the model's REPORT file.
- Keep one variable per config; keep the reference config first and last in every plan.
- Record GPU tuning (`HW_NOTES`) — power limits and memory clocks change results.

## 6. Pitfalls we actually hit

1. **The setting wasn't applied.** A hard-coded flag in the server script overrode the "changed" value;
   six "different" configs were identical. Always check "avg drafted per check" (or, in the server log,
   effective draft length = (mean len − 1) ÷ acceptance).
2. **A stopped server still held VRAM.** It closed its port, ignored SIGTERM, and kept ~22 GB while
   exiting; the next config spilled into system RAM (~4 t/s). The harness now waits for the processes,
   escalates to SIGKILL, and won't start while one survives. If you stop things by hand:
   `pkill -f 'llama[ -]server'`, then `ps -A | grep llama`, then `kill -9 <pid>` if needed.
3. **`/props` is not the truth.** It reported `speculative.types = "none"` while MTP was clearly active.
   Use the response `timings` and the server log.
4. **VRAM spill looks like a terrible config.** f16 KV at 131k context used 24.3 of 24.5 GB and ran 72%
   slower. A long load time (27 s instead of 6) is the giveaway; the 64-token speed check catches it.
5. **Short prompts flatter long-context use.** Generation fell from ~100 t/s (short) to 83 (22k) to 67
   (50k), and a cold 22k/50k prompt took ~30/82 s to read — more than generating 1,024 tokens.
6. **`pkill -f llama server`** (unquoted) is an error; use `pkill -f 'llama[ -]server'`.

## 7. Status and next steps (as of 2026-11-04)

- Qwen3.8-27B tuned: N5 + probabilistic drafting. Open: same-session A/B confirmation, memory-overclock
  check, prompt-cache hit rate in real agent sessions, quality check of probabilistic drafting.
- Next planned: the same procedure on a Qwen 4 27B when available (same llama.cpp build first).
- Ideas not yet built: a prompt-cache test (shared prefix, growing suffix, like agent turns); grading the
  math/logic answers automatically; NVIDIA VRAM reading is basic (`nvidia-smi`), other vendors print n/a.
