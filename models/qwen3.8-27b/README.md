# Qwen3.8-27B (Q4_K_M)

- **Best config so far:** MTP drafting, `--spec-draft-n-max 5 --spec-draft-sampling probabilistic`
  → ~90 t/s short prompts (99–110 on code), 87 t/s at 22k tokens, 71 t/s at 50k; 39 t/s without drafting.
- **Hardware:** RX 7900 XTX 24 GB, Vulkan (RADV), llama.cpp build 12019 (1052f7e1d). Full setup and GPU
  tuning in the report.
- **Full write-up:** [REPORT_2026-11-04.md](REPORT_2026-11-04.md)
- **Server script:** [server/qwen-server](server/qwen-server) (current) ·
  [server/qwen-server.before-2026-11-04](server/qwen-server.before-2026-11-04)
- **Raw reports:** [results/](results/) — the three `*-v1.md` files were produced with the suite's
  earlier tools and v1 prompts (refactor/codegen/prose + long context); their numbers are directly
  comparable with later runs' "core geo-mean" and longctx/xlctx columns.

| Report | What |
|---|---|
| `20261104_101700_draft-length-v1.md` | N = 0…8, greedy drafting |
| `20261104_110100_speed-round1-v1.md` | 14 configs: p-min, n-min, -ub, KV f16, min-p, N8 |
| `20261104_131700_speed-round2-v1.md` | 13 configs at 22k/50k context: N4–7, p-min, probabilistic, n-gram |
