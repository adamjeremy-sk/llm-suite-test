#!/usr/bin/env python3
"""Speed benchmark for a running llama.cpp server (OpenAI-compatible /v1/chat/completions).

Usage:
  python3 tools/bench.py run <label> [--url http://127.0.0.1:8080] [--runs 3] [--long-runs 5]
                                     [--max-tokens 1024] [--no-long] [--suite <suite root>]
  python3 tools/bench.py compare <a.csv> <b.csv>

Writes bench_<label>.csv (one row per request) and responses_<label>.jsonl (the answers, for
optional quality checks) in the current directory. Standard library only.

Prompts come from <suite>/prompts (frozen; see prompts/VERSION):
  short/NN-name.txt      each run --runs times, prompt cache OFF (fresh every time)
  long_task.txt          "longctx": the task + the first corpus file (~22k tokens)
  long_task_multi.txt    "xlctx":   the task + all corpus files in corpus/kotlin/ORDER.txt (~50k tokens)
Long prompts run --long-runs times with the prompt cache ON: run 1 reads the prompt cold (measures
prompt processing); runs 2+ reuse it, so they measure generation speed at that context depth cheaply.
Seeds are 1001, 1002, ... for every config, so configs see identical requests.
"""
import csv, json, os, statistics, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FIELDS = ["label", "prompt", "run", "seed", "wall_s", "prompt_n", "prompt_per_second",
          "predicted_n", "predicted_per_second", "draft_n", "draft_n_accepted", "accept_rate"]
LONG = ("longctx", "xlctx")


def read(path):
    with open(path, errors="replace") as f:
        return f.read()


def load_prompts(suite, with_long=True):
    """Returns [(name, text, kind)] in run order. kind is 'short' or 'long'."""
    out = []
    sd = os.path.join(suite, "prompts", "short")
    for fn in sorted(os.listdir(sd)):
        if fn.endswith(".txt"):
            name = fn[:-4].split("-", 1)[-1]          # 01-refactor.txt -> refactor
            out.append((name, read(os.path.join(sd, fn)).rstrip("\n"), "short"))
    if with_long:
        cd = os.path.join(suite, "corpus", "kotlin")
        order = [l.strip() for l in read(os.path.join(cd, "ORDER.txt")).splitlines() if l.strip()]
        files = [os.path.join(cd, f) for f in order]
        task = read(os.path.join(suite, "prompts", "long_task.txt")).rstrip("\n")
        multi = read(os.path.join(suite, "prompts", "long_task_multi.txt")).rstrip("\n")
        out.append(("longctx", task.format(src=read(files[0])), "long"))
        parts = [f"// ===== {os.path.basename(fp)} =====\n" + read(fp) for fp in files]
        out.append(("xlctx", multi.format(src="\n\n".join(parts)), "long"))
    return out


def post(url, body, timeout=1800):
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def show_props(base):
    try:
        with urllib.request.urlopen(base + "/props", timeout=30) as r:
            props = json.loads(r.read())
    except Exception as e:
        print(f"(could not read /props: {e})")
        return
    found = {}

    def walk(o, path=""):
        if isinstance(o, dict):
            for k, v in o.items():
                p = f"{path}.{k}" if path else k
                if any(t in k.lower() for t in ("spec", "draft", "mtp")):
                    found[p] = v
                walk(v, p)
    walk(props)
    if found:
        print("Server speculative settings reported by /props (may not reflect CLI flags):")
        for k, v in found.items():
            print(f"  {k} = {json.dumps(v)[:200]}")


def run(label, base, runs, long_runs, max_tokens, suite, with_long):
    show_props(base)
    url = base + "/v1/chat/completions"
    print("Warm-up request (discarded)...")
    post(url, {"messages": [{"role": "user", "content": "Say OK."}], "max_tokens": 16})
    rows = []
    with open(f"responses_{label}.jsonl", "w") as resp_f:
        for name, text, kind in load_prompts(suite, with_long):
            n = long_runs if kind == "long" else runs
            for i in range(1, n + 1):
                seed = 1000 + i
                body = {"messages": [{"role": "user", "content": text}], "max_tokens": max_tokens,
                        "seed": seed, "cache_prompt": kind == "long", "stream": False}
                t0 = time.time()
                resp = post(url, body)
                wall = time.time() - t0
                t = resp.get("timings", {}) or {}
                dn, da = t.get("draft_n"), t.get("draft_n_accepted")
                row = {"label": label, "prompt": name, "run": i, "seed": seed, "wall_s": round(wall, 2),
                       "prompt_n": t.get("prompt_n"),
                       "prompt_per_second": round(t.get("prompt_per_second", 0) or 0, 2),
                       "predicted_n": t.get("predicted_n"),
                       "predicted_per_second": round(t.get("predicted_per_second", 0) or 0, 2),
                       "draft_n": dn, "draft_n_accepted": da,
                       "accept_rate": round(da / dn, 3) if dn else None}
                rows.append(row)
                msg = (resp.get("choices") or [{}])[0].get("message", {})
                resp_f.write(json.dumps({"label": label, "prompt": name, "run": i, "seed": seed,
                                         "content": msg.get("content"),
                                         "reasoning": msg.get("reasoning_content")}) + "\n")
                print(f"  {name:9s} run {i}: {row['predicted_per_second']:7.2f} t/s, "
                      f"{row['predicted_n']} tok, accept {row['accept_rate']}")
    out = f"bench_{label}.csv"
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    print(f"\nWrote {out}")
    summarize({label: rows})


def load(path):
    with open(path) as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        for k in ("predicted_per_second", "accept_rate", "predicted_n"):
            r[k] = float(r[k]) if r[k] not in ("", "None", None) else None
    return rows


def summarize(groups):
    labels = list(groups)
    prompts = list(dict.fromkeys(r["prompt"] for g in groups.values() for r in g))
    print("\nMedian per prompt:\n" + f"{'prompt':10s}" + "".join(f"{l + ' t/s':>14s}{l + ' acc':>11s}" for l in labels))
    totals = {l: [] for l in labels}
    for p in prompts:
        line = f"{p:10s}"
        for l in labels:
            rs = [r for r in groups[l] if r["prompt"] == p]
            tps = [r["predicted_per_second"] for r in rs if r["predicted_per_second"]]
            acc = [r["accept_rate"] for r in rs if r["accept_rate"] is not None]
            mt = statistics.median(tps) if tps else float("nan")
            totals[l].append(mt)
            line += f"{mt:14.2f}" + (f"{statistics.median(acc):11.2f}" if acc else f"{'n/a':>11s}")
        print(line)
    if len(labels) == 2:
        a, b = labels
        print(f"\nChange in t/s, {b} vs {a}:")
        for p, x, y in zip(prompts, totals[a], totals[b]):
            print(f"  {p:9s} {100 * (y - x) / x:+6.1f}%")


def main():
    a = sys.argv[1:]
    if not a or a[0] not in ("run", "compare"):
        print(__doc__); sys.exit(1)
    if a[0] == "compare":
        ga, gb = load(a[1]), load(a[2])
        summarize({ga[0]["label"]: ga, gb[0]["label"]: gb}); return
    label, rest = a[1], a[2:]
    flags = {x for x in rest if x == "--no-long"}
    rest = [x for x in rest if x not in flags]
    o = dict(zip(rest[::2], rest[1::2]))
    run(label, o.get("--url", "http://127.0.0.1:8080").rstrip("/"), int(o.get("--runs", 3)),
        int(o.get("--long-runs", 5)), int(o.get("--max-tokens", 1024)),
        o.get("--suite", os.path.dirname(HERE)), "--no-long" not in flags)


if __name__ == "__main__":
    main()
