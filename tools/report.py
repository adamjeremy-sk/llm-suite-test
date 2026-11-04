#!/usr/bin/env python3
"""Helpers for tools/sweep.sh (standard library only).

  python3 tools/report.py configs <configs.txt>                 -> name<TAB>flags<TAB>note lines
  python3 tools/report.py edit <src script> <dst script> <log file> <flags...>
  python3 tools/report.py report <run folder>                   -> writes report.md there

"core" geo-mean = refactor, codegen, prose (the suite-v1 short prompts), so results stay comparable
across suite versions. Other short prompts (math, logic, ...) get their own columns.
"""
import csv, glob, math, os, re, statistics, sys

CORE = ("refactor", "codegen", "prose")


def parse_configs(path):
    out = []
    for line in open(path):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        while len(parts) < 3:
            parts.append("")
        out.append(tuple(parts[:3]))
    return out


def edit(src, dst, log, flags):
    lines = open(src).read().split("\n")

    def first_token(l):
        t = l.strip().split()
        return t[0] if t else ""

    for f in flags:
        k, _, v = f.partition("=")
        idx = [i for i, l in enumerate(lines) if first_token(l) == k]
        if v == "DEL":
            lines = [l for i, l in enumerate(lines) if i not in idx]
            continue
        new = f"  {k} {v} \\"
        if idx:
            for i in idx:
                lines[i] = new
        else:  # insert before --host (or before the "$@" line)
            at = next((i for i, l in enumerate(lines) if first_token(l) in ("--host", '"$@"')), None)
            if at is None:
                sys.exit(f"cannot place {k}: no --host or \"$@\" line in {src}")
            lines.insert(at, new)
    log_idx = [i for i, l in enumerate(lines) if first_token(l) == "--log-file"]
    for i in log_idx:
        lines[i] = f'  --log-file "{log}" \\'
    if not log_idx:
        sys.exit(f"no --log-file line in {src}")
    open(dst, "w").write("\n".join(lines))
    os.chmod(dst, 0o755)


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def fmt(x, d=1):
    return "n/a" if x is None else f"{x:.{d}f}"


def geo(xs):
    xs = [x for x in xs if x]
    return math.exp(sum(math.log(x) for x in xs) / len(xs)) if xs else None


def report(d):
    meta = []
    with open(os.path.join(d, "meta.tsv")) as f:
        meta = list(csv.DictReader(f, delimiter="\t"))
    header = open(os.path.join(d, "header.txt")).read()
    runs = {}
    for m in meta:
        p = os.path.join(d, f"bench_{m['name']}.csv")
        if os.path.exists(p):
            with open(p) as f:
                runs[m["name"]] = list(csv.DictReader(f))
    prompts = []
    for rs in runs.values():
        for r in rs:
            if r["prompt"] not in prompts:
                prompts.append(r["prompt"])
    short = [p for p in prompts if p in CORE]

    def per(name, p, key):
        return [num(r[key]) for r in runs.get(name, []) if r["prompt"] == p]

    ref = meta[0]["name"] if meta else None

    def derived(name, p):
        """per-request: tokens per check, ms per check, avg drafted per check"""
        ml, ms, dl = [], [], []
        for r in runs.get(name, []):
            if r["prompt"] != p:
                continue
            pred, acc, dn, tps = num(r["predicted_n"]), num(r["draft_n_accepted"]) or 0, num(r["draft_n"]) or 0, num(r["predicted_per_second"])
            if not pred or not tps:
                continue
            checks = pred - acc
            if checks <= 0:
                continue
            ml.append(pred / checks); ms.append(1000 * pred / checks / tps); dl.append(dn / checks)
        return med(ml), med(ms), med(dl)

    def cold_pp(name, p):  # prompt processing only from runs that read the whole prompt
        return med([num(r["prompt_per_second"]) for r in runs.get(name, [])
                    if r["prompt"] == p and (num(r["prompt_n"]) or 0) > 1000])

    longs = [p for p in prompts if p in ("longctx", "xlctx")]
    refa = geo([med(per(ref, p, "predicted_per_second")) for p in longs]) if (meta and longs) else None

    out = []; w = out.append
    w("# Speed sweep report\n"); w(header.strip() + "\n")
    refg = geo([med(per(ref, p, "predicted_per_second")) for p in short]) if ref in runs else None
    refl = med(per(ref, "longctx", "predicted_per_second")) if ref in runs else None

    w("## 1. Overview (median generation t/s per prompt; core geo-mean = refactor/codegen/prose)\n")
    w("| config | status | " + " | ".join(prompts) + " | core geo-mean | vs ref | longctx vs ref | longctx cold pp t/s | load s | VRAM MiB |")
    w("|" + "---|" * (8 + len(prompts)))
    for m in meta:
        n = m["name"]
        meds = [med(per(n, p, "predicted_per_second")) for p in prompts]
        g = geo([x for p, x in zip(prompts, meds) if p in short])
        lg = med(per(n, "longctx", "predicted_per_second"))
        vs = f"{100 * (g - refg) / refg:+.1f}%" if g and refg else "n/a"
        vl = f"{100 * (lg - refl) / refl:+.1f}%" if lg and refl else "n/a"
        w(f"| {n} | {m['status']} | " + " | ".join(fmt(x) for x in meds) +
          f" | {fmt(g)} | {vs} | {vl} | {fmt(cold_pp(n, 'longctx'), 0)} | {m['load_s']} | {m['vram_mib']} |")

    if longs:
        w("\n## 1b. Agent-like view: long-context generation and cold prompt processing\n")
        w("| config | " + " | ".join(f"{p} gen t/s" for p in longs) + " | long geo-mean | vs ref | " +
          " | ".join(f"{p} cold pp t/s / prompt tokens" for p in longs) + " |")
        w("|" + "---|" * (3 + 2 * len(longs)))
        for m in meta:
            n = m["name"]
            lm = [med(per(n, p, "predicted_per_second")) for p in longs]
            g = geo(lm)
            vs = f"{100 * (g - refa) / refa:+.1f}%" if g and refa else "n/a"
            pps = []
            for p in longs:
                pn = [num(r["prompt_n"]) for r in runs.get(n, []) if r["prompt"] == p and (num(r["prompt_n"]) or 0) > 1000]
                pps.append(f"{fmt(cold_pp(n, p), 0)} / {fmt(med(pn), 0)}")
            w(f"| {n} | " + " | ".join(fmt(x) for x in lm) + f" | {fmt(g)} | {vs} | " + " | ".join(pps) + " |")

    w("\n## 2. Configs\n")
    w("| config | flags | note |\n|---|---|---|")
    for m in meta:
        w(f"| {m['name']} | `{m['flags']}` | {m['note']} |")

    w("\n## 3. Spread of generation t/s (min / median / max)\n")
    w("| config | " + " | ".join(prompts) + " |"); w("|" + "---|" * (1 + len(prompts)))
    for m in meta:
        cells = []
        for p in prompts:
            xs = [x for x in per(m["name"], p, "predicted_per_second") if x]
            cells.append(f"{min(xs):.1f} / {statistics.median(xs):.1f} / {max(xs):.1f}" if xs else "n/a")
        w(f"| {m['name']} | " + " | ".join(cells) + " |")

    w("\n## 4. Drafting detail per prompt: acceptance / tokens per check / ms per check / avg drafted per check\n")
    w("| config | " + " | ".join(prompts) + " |"); w("|" + "---|" * (1 + len(prompts)))
    for m in meta:
        cells = []
        for p in prompts:
            acc = med(per(m["name"], p, "accept_rate"))
            ml, ms, dl = derived(m["name"], p)
            cells.append(f"{fmt(acc, 2)} / {fmt(ml, 2)} / {fmt(ms)} / {fmt(dl, 2)}")
        w(f"| {m['name']} | " + " | ".join(cells) + " |")

    w("\n## 5. Tokens generated (median) and prompt tokens\n")
    w("| config | " + " | ".join(f"{p} gen / prompt" for p in prompts) + " |"); w("|" + "---|" * (1 + len(prompts)))
    for m in meta:
        w(f"| {m['name']} | " + " | ".join(f"{fmt(med(per(m['name'], p, 'predicted_n')), 0)} / {fmt(med(per(m['name'], p, 'prompt_n')), 0)}" for p in prompts) + " |")

    bad = [m for m in meta if m["status"] != "ok"]
    if bad:
        w("\n## 6. Not run / failed\n")
        for m in bad:
            w(f"### {m['name']}: {m['status']}")
            lp = os.path.join(d, f"server_{m['name']}.log")
            if os.path.exists(lp) and not m["status"].startswith("skipped"):
                w("```\n" + "".join(open(lp, errors="replace").readlines()[-25:]).rstrip() + "\n```")

    w("\n## Appendix A. llama.cpp --help lines about drafting/speculation/sampling\n```")
    hp = os.path.join(d, "help_spec.txt")
    w(open(hp).read().rstrip() if os.path.exists(hp) else "(none)")
    w("```\n\n## Appendix B. Server log draft lines per config\n")
    for m in meta:
        lp = os.path.join(d, f"server_{m['name']}.log")
        lines = [l.rstrip() for l in open(lp, errors="replace")] if os.path.exists(lp) else []
        dl = [l for l in lines if "draft acceptance" in l]
        w(f"### {m['name']}\n```\n" + ("\n".join(dl) if dl else "(no draft lines)") + "\n```")
    w("\n## Appendix C. All per-request rows\n```")
    first = True
    for m in meta:
        for r in runs.get(m["name"], []):
            if first:
                w(",".join(r.keys())); first = False
            w(",".join(str(v) for v in r.values()))
    w("```")
    text = "\n".join(out) + "\n"
    open(os.path.join(d, "report.md"), "w").write(text)


def main():
    a = sys.argv[1:]
    if a[:1] == ["configs"]:
        for c in parse_configs(a[1]):  # "-" stands in for an empty field (bash read collapses tabs)
            print("\t".join(x if x else "-" for x in c))
    elif a[:1] == ["edit"]:
        edit(a[1], a[2], a[3], a[4:])
    elif a[:1] == ["report"]:
        report(a[1])
    else:
        print(__doc__); sys.exit(1)


if __name__ == "__main__":
    main()
