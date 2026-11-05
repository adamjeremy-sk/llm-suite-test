#!/usr/bin/env python3
"""Context Monitor: a small GTK 3 window showing llama-server context usage and speed.

Per server (one panel each, any number side by side):
- Context tokens and the finished-request speeds come from the server log (--log-file).
- Model name, context size, busy/idle state and tokens generated so far come from the
  HTTP API (/props, /slots); the live speed is derived from how fast that number grows.
- Context-over-time and speed-over-time graphs, a list of recent requests, and
  warnings (in the panel and as desktop notifications) before the context fills up.
- The token count says whether it is exact (from the log) or an estimate, and why.
- If nothing is listening on the port, everything reads zero.

Shared: GPU memory (AMD sysfs or nvidia-smi) and an optional system tray icon.

Requires: python3-gi and gir1.2-gtk-3.0 (preinstalled on Ubuntu desktop).
Optional: python3-gi-cairo (graphs), gir1.2-ayatanaappindicator3-0.1 (tray icon),
          libnotify-bin / notify-send (desktop notifications).

Usage:  ./ctxmon_gtk.py [--log PATH] [--url URL] [--ctx N] [--interval SEC]
        ./ctxmon_gtk.py --server name=qwen,url=http://127.0.0.1:8080,log=~/qwen.log \\
                        --server name=other,url=http://127.0.0.1:8081,log=~/other.log
"""
import argparse
import glob
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.error
from collections import deque
from urllib.parse import urlparse
from urllib.request import urlopen

try:
    import gi

    gi.require_version("Gtk", "3.0")
    gi.require_version("Gdk", "3.0")
    from gi.repository import Gdk, GLib, Gtk  # noqa: E402

    HAVE_GTK = True
except (ImportError, ValueError):  # the logic below stays importable (and testable) without GTK
    HAVE_GTK = False

TAIL_BYTES = 512 * 1024  # how much of the log's end to scan per poll
LIVE_WINDOW = 2.0        # seconds of samples used for the live speed
MAX_REQUESTS = 50        # request history length per server
DEFAULT_WARN = (80, 95)  # context percentages that raise a warning
ETA_WARN_SECONDS = 60    # warn when generating at this speed would fill the context within this

# llama.cpp has used different wording over time; accept all of them.
#   new:  "slot release: ... stop processing: n_tokens = 1234, ..."   -> final (request finished)
#   new:  "prompt processing done, n_tokens = 1234, ..."              -> prompt (generation starting)
#   old:  "... n_past = 1234" (also appears in "prompt processing progress")  -> past (ambiguous)
TOKEN_RE = re.compile(
    r"stop processing: n_tokens = (?P<final>\d+)"
    r"|prompt processing done, n_tokens = (?P<prompt>\d+)"
    r"|\bn_past = (?P<past>\d+)"
)

# Timing lines printed when a request finishes:
#   prompt eval time =  123.45 ms /  50 tokens (  2.47 ms per token,  405.00 tokens per second)
#          eval time = 1234.56 ms / 100 tokens ( 12.35 ms per token,   81.00 tokens per second)
_SPEED = (r"=\s*([\d.]+) ms /\s*(\d+) (?:tokens|runs)\s*"
          r"\(\s*[\d.]+ ms per token,\s*([\d.]+) tokens per second\)")
GEN_RE = re.compile(r"(?<!prompt )eval time " + _SPEED)
PROMPT_RE = re.compile(r"prompt eval time " + _SPEED)


SETTINGS_PATH = os.path.join(
    os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "ctxmon", "settings.json"
)
MIN_OPACITY = 30  # percent; lower than this and the window is effectively invisible


def load_settings(path=SETTINGS_PATH):
    """Saved window options, with defaults for anything missing or unreadable."""
    s = {"always_on_top": False, "opacity": 100, "warnings": True}
    try:
        with open(path) as f:
            saved = json.load(f)
        s["always_on_top"] = bool(saved.get("always_on_top", False))
        s["opacity"] = max(MIN_OPACITY, min(100, int(saved.get("opacity", 100))))
        s["warnings"] = bool(saved.get("warnings", True))
    except (OSError, ValueError, TypeError, AttributeError):
        pass
    return s


def save_settings(settings, path=SETTINGS_PATH):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(settings, f)
    except OSError:
        pass  # not being able to remember settings is not worth interrupting for


# --------------------------------------------------------------------------- servers

class ServerCfg:
    def __init__(self, url, log, ctx, interval, name=None):
        self.url, self.log, self.ctx, self.interval, self.name = url, log, ctx, interval, name

    @property
    def endpoint(self):
        u = urlparse(self.url)
        return f"{u.hostname or '127.0.0.1'}:{u.port or (443 if u.scheme == 'https' else 80)}"


def parse_server_spec(spec, default_ctx, interval):
    """'name=qwen,url=http://127.0.0.1:8080,log=~/qwen.log,ctx=131072' -> ServerCfg."""
    kv = {}
    for part in spec.split(","):
        if "=" not in part:
            raise ValueError(f"bad --server part {part!r} (expected key=value)")
        k, v = part.split("=", 1)
        kv[k.strip().lower()] = v.strip()
    unknown = set(kv) - {"name", "url", "log", "ctx"}
    if unknown:
        raise ValueError(f"unknown --server key(s): {', '.join(sorted(unknown))}")
    if "url" not in kv:
        raise ValueError("--server needs url=...")
    return ServerCfg(
        url=kv["url"],
        log=os.path.expanduser(kv.get("log", "")) or None,
        ctx=int(kv["ctx"]) if "ctx" in kv else default_ctx,
        interval=interval,
        name=kv.get("name"),
    )


# --------------------------------------------------------------------------- log + API reading

def last_tokens(path, floor=0):
    """Return (tokens_or_None, kind_or_None, log_found) from the newest token line in the log.

    kind is "final" (request finished), "prompt" (prompt done, generating) or "past"
    (old-style line; progress or final, the caller decides from the busy state).
    Log content before byte `floor` is ignored.
    """
    if not path:
        return None, None, False
    try:
        size = os.path.getsize(path)
        if floor > size:  # log was truncated (server restarted)
            floor = 0
        with open(path, "rb") as f:
            f.seek(max(floor, size - TAIL_BYTES))
            text = f.read().decode("utf-8", errors="replace")
    except OSError:
        return None, None, False
    val = kind = None
    for m in TOKEN_RE.finditer(text):
        kind = m.lastgroup
        val = int(m.group(kind))
    return val, kind, True


def last_speeds(text):
    """Return (gen_tps, prompt_tps) of the last matches in text (None if absent)."""
    g = [float(m.group(3)) for m in GEN_RE.finditer(text)]
    p = [float(m.group(3)) for m in PROMPT_RE.finditer(text)]
    return (g[-1] if g else None), (p[-1] if p else None)


def parse_requests(text):
    """Finished requests found in log text, oldest first.

    Each is a dict: prompt_tokens, prompt_tps, gen_tokens, gen_tps, ctx (context size
    after the request, when the log said so). A generation line without a preceding
    prompt line keeps the prompt fields as None.
    """
    events = []
    for m in TOKEN_RE.finditer(text):
        events.append((m.start(), "tok", m))
    for m in PROMPT_RE.finditer(text):
        events.append((m.start(), "prompt", m))
    for m in GEN_RE.finditer(text):
        events.append((m.start(), "gen", m))
    events.sort(key=lambda e: e[0])
    out, pending, awaiting = [], None, None
    for _pos, kind, m in events:
        if kind == "tok":
            # llama.cpp prints the timings first and "stop processing: n_tokens" right after,
            # so the first final-style token line after a timing line is that request's context size.
            if awaiting is not None and m.lastgroup in ("final", "past"):
                awaiting["ctx"] = int(m.group(m.lastgroup))
                awaiting = None
        elif kind == "prompt":
            pending = (int(m.group(2)), float(m.group(3)))
        else:
            pt, pps = pending if pending else (None, None)
            awaiting = dict(prompt_tokens=pt, prompt_tps=pps, gen_tokens=int(m.group(2)),
                            gen_tps=float(m.group(3)), ctx=None)
            out.append(awaiting)
            pending = None
    return out


def slot_info(url):
    """Return (n_ctx, is_processing, n_decoded, state).

    state is "up" (server answered), "down" (nothing listening) or "slow"
    (no timely answer: a busy server can be slow, so that is NOT treated as down).
    """
    try:
        with urlopen(url.rstrip("/") + "/slots", timeout=2) as r:
            s = json.load(r)[0]
    except urllib.error.HTTPError:
        return None, None, None, "up"  # it answered, just not with slot data
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, "reason", e)
        if isinstance(reason, (TimeoutError, socket.timeout, ConnectionResetError)):
            return None, None, None, "slow"
        return None, None, None, "down"
    except (ValueError, IndexError, KeyError):
        return None, None, None, "up"
    nt = s.get("next_token")
    if isinstance(nt, list):
        nt = nt[0] if nt else {}
    n_decoded = nt.get("n_decoded") if isinstance(nt, dict) else None
    return s.get("n_ctx"), s.get("is_processing"), n_decoded, "up"


def server_info(url):
    """Return the running model's name (alias, else file name), or None."""
    base = url.rstrip("/")
    try:
        with urlopen(base + "/props", timeout=2) as r:
            p = json.load(r)
        name = p.get("model_alias") or os.path.basename(p.get("model_path") or "")
        if name:
            return name
    except Exception:
        pass
    try:
        with urlopen(base + "/v1/models", timeout=2) as r:
            return json.load(r)["data"][0]["id"]
    except Exception:
        return None


def live_speed(samples, window=LIVE_WINDOW):
    """Tokens/sec from (time, n_decoded) samples within the window, or None.

    Needs at least two samples and some growth. Short windows are bursty with
    speculative decoding, which is why a couple of seconds are averaged.
    """
    if len(samples) < 2:
        return None
    t_end = samples[-1][0]
    recent = [s for s in samples if t_end - s[0] <= window]
    if len(recent) < 2:
        return None
    (t0, n0), (t1, n1) = recent[0], recent[-1]
    if t1 <= t0 or n1 <= n0:
        return None
    return (n1 - n0) / (t1 - t0)


class Poller(threading.Thread):
    """Polls one server's log + API off the UI thread and hands snapshots to a callback."""

    def __init__(self, cfg, callback, dispatch=None):
        super().__init__(daemon=True)
        self.cfg = cfg
        self.callback = callback
        self.dispatch = dispatch or (GLib.idle_add if HAVE_GTK else (lambda f, *a: f(*a)))
        self.offset = None      # how far into the log we've read
        self.floor = 0          # ignore log content before this byte (older server sessions)
        self.last_gen = None    # tokens/sec of the latest finished request
        self.last_prompt = None
        self.prev_state = None
        self.model = None
        self.model_checked = 0.0
        self.new_reqs = []      # finished requests not yet handed to the UI

    def new_session(self):
        """The server just came up after being down: forget everything from the old one."""
        try:
            size = os.path.getsize(self.cfg.log)
        except (OSError, TypeError):
            size = 0
        self.floor = size
        self.offset = size
        self.last_gen = None
        self.last_prompt = None

    def read_new(self):
        """Read complete new log lines; remember the latest speeds and finished requests."""
        path = self.cfg.log
        if not path:
            return
        try:
            size = os.path.getsize(path)
        except OSError:
            return
        seed = False
        if self.offset is None:
            # First look: take the latest values from history.
            with open(path, "rb") as f:
                f.seek(max(self.floor, size - TAIL_BYTES))
                text = f.read().decode("utf-8", errors="replace")
            self.offset = size
            seed = True
        else:
            if size < self.offset:  # log was truncated/rotated (server restarted)
                self.offset = 0
                self.floor = 0
            if size == self.offset:
                return
            with open(path, "rb") as f:
                f.seek(self.offset)
                chunk = f.read(size - self.offset)
            end = chunk.rfind(b"\n") + 1  # only consume complete lines
            if end == 0:
                return
            self.offset += end
            text = chunk[:end].decode("utf-8", errors="replace")
        g, p = last_speeds(text)
        if g is not None:
            self.last_gen = g
        if p is not None:
            self.last_prompt = p
        for r in parse_requests(text):
            r["seed"] = seed  # history from before we started watching: time unknown
            self.new_reqs.append(r)

    def poll_once(self):
        n_ctx, busy, n_decoded, state = slot_info(self.cfg.url)
        now = time.time()
        if state == "down":
            self.model = None
            self.last_gen = self.last_prompt = None
        elif state == "up":
            if self.prev_state == "down":
                self.new_session()
            age = now - self.model_checked
            if (self.model is None and age > 5) or age > 30:
                self.model = server_info(self.cfg.url) or self.model
                self.model_checked = now
        if state != "slow":
            self.prev_state = state

        used, kind, log_found = last_tokens(self.cfg.log, self.floor)
        if state == "up":
            try:
                self.read_new()
            except OSError:
                pass
        reqs, self.new_reqs = self.new_reqs, []
        return dict(used=used, used_kind=kind, n_ctx=n_ctx, busy=busy, n_decoded=n_decoded,
                    state=state, model=self.model, log_found=log_found,
                    gen=self.last_gen, prompt=self.last_prompt, requests=reqs)

    def run(self):
        while True:
            self.dispatch(self.callback, self.poll_once())
            time.sleep(self.cfg.interval)


# --------------------------------------------------------------------------- per-server logic (no GTK)

def fmt_duration(sec):
    sec = int(round(sec))
    return f"{sec}s" if sec < 90 else f"{sec // 60}m{sec % 60:02d}s"


class ServerState:
    """Turns raw snapshots into what the panel shows, and keeps the histories.

    Token count honesty: "exact" only when the request has finished and the log says how many
    tokens the context holds. While a request runs the number is an estimate (the log's prompt
    size plus what /slots says has been generated), and the `source` text says which parts of it
    are known.
    """

    def __init__(self, default_ctx, warn_levels=DEFAULT_WARN, history_seconds=600):
        self.default_ctx = default_ctx
        self.levels = sorted(warn_levels)
        self.window = history_seconds
        self.ctx_hist = deque()        # (time, tokens)
        self.speed_hist = deque()      # (time, tokens/sec or None = gap)
        self.requests = deque(maxlen=MAX_REQUESTS)  # newest last
        self.samples = deque(maxlen=64)
        self.last_ctx = None
        self.warned = set()
        self.eta_warned = False
        self.first = True
        self.was_busy = False
        self.last_live = None

    # histories -------------------------------------------------------------
    def _prune(self, hist, now):
        # keep one point older than the window so lines enter from the left edge
        while len(hist) > 1 and hist[1][0] < now - self.window:
            hist.popleft()

    def _push_ctx(self, now, val):
        if not self.ctx_hist or val != self.ctx_hist[-1][1] or now - self.ctx_hist[-1][0] >= 2:
            self.ctx_hist.append((now, val))
        self._prune(self.ctx_hist, now)

    def _push_speed(self, now, val):
        h = self.speed_hist
        if val is None:
            if h and h[-1][1] is not None:
                h.append((now, None))
        elif not h or h[-1][1] is None or now - h[-1][0] >= 1:
            h.append((now, val))
        self._prune(h, now)

    def _add_requests(self, reqs, now):
        for r in reqs:
            r = dict(r)
            r["t"] = None if r.get("seed") else now
            self.requests.append(r)
            if not r.get("seed") and r.get("gen_tps"):
                self._push_speed(now, r["gen_tps"])

    # main entry ------------------------------------------------------------
    def update(self, s, now=None):
        """Digest one snapshot. Returns a dict for the UI; {"state": "slow"} means keep the old numbers."""
        now = time.time() if now is None else now
        state = s["state"]
        if state == "slow":
            return {"state": "slow"}
        self._add_requests(s.get("requests") or [], now)

        if state == "down":
            ctx = self.last_ctx or self.default_ctx
            self._push_ctx(now, 0)
            self._push_speed(now, None)
            self.samples.clear()
            self.warned.clear()
            self.eta_warned = False
            self.first = True
            self.was_busy = False
            return dict(state="down", ctx=ctx, shown=0, frac=0.0, kind="down", source="", estimating=False,
                        live=None, gen=None, prompt=None, eta=None, busy=False, level=0, alerts=[],
                        model=None)

        used, ukind, busy, n_dec = s["used"], s.get("used_kind"), s["busy"], s["n_decoded"]
        ctx = s["n_ctx"] or self.default_ctx
        self.last_ctx = ctx

        # live speed from tokens generated so far by the running request
        live = None
        if busy and n_dec is not None:
            if self.samples and n_dec < self.samples[-1][1]:
                self.samples.clear()  # a new request started
            self.samples.append((now, n_dec))
            live = live_speed(self.samples)
        else:
            self.samples.clear()
        if live is not None:
            self._push_speed(now, live)
        elif self.was_busy and not busy:
            self._push_speed(now, None)  # break the line between requests
        self.was_busy = bool(busy)

        # exact vs estimate
        gen_now = n_dec if (busy and n_dec) else 0
        if used is None:
            kind, shown, source = "none", gen_now, "no request in the log yet"
            estimating = bool(gen_now)
            if estimating:
                source = f"estimate: {gen_now:,} generated, prompt size not in the log yet"
        elif not busy:
            kind, shown, source, estimating = "exact", used, "exact: from the server log", False
        elif ukind == "final" and not gen_now:
            # busy, but the newest log line is the previous request's end: its prompt is not known yet
            kind, shown, estimating = "processing", used, True
            source = f"estimate: processing a new prompt; showing the previous request's {used:,}"
        elif not gen_now:
            kind, shown, estimating = "processing", used, True
            source = f"estimate: processing prompt, {used:,} tokens so far"
        else:
            kind, shown, estimating = "estimate", used + gen_now, True
            source = f"estimate: prompt {used:,} + {gen_now:,} generated"

        frac = min(shown / ctx, 1.0) if ctx else 0.0
        free = max(ctx - shown, 0)
        eta = free / live if (busy and live and free > 0) else None
        self._push_ctx(now, shown)

        alerts, level = self._warnings(frac, shown, ctx, free, eta, busy)
        return dict(state="up", ctx=ctx, shown=shown, frac=frac, kind=kind, source=source,
                    estimating=estimating, live=live, gen=s["gen"], prompt=s["prompt"], eta=eta,
                    busy=bool(busy), level=level, alerts=alerts, model=s["model"],
                    log_found=s["log_found"])

    def _warnings(self, frac, shown, ctx, free, eta, busy):
        pct = frac * 100
        alerts = []
        if self.first:
            self.warned = {lv for lv in self.levels if pct >= lv}  # don't alert about the past
            self.first = False
        else:
            newly = [lv for lv in self.levels if pct >= lv and lv not in self.warned]
            self.warned.update(newly)
            if newly:
                lv = max(newly)
                alerts.append((lv, f"Context {pct:.0f}% full ({shown:,} / {ctx:,} tokens, {free:,} free)",
                               lv >= self.levels[-1]))
        for lv in list(self.warned):  # hysteresis: re-arm once usage drops well below the level
            if pct < lv - 10:
                self.warned.discard(lv)
        if not busy:
            self.eta_warned = False
        elif eta is not None and eta < ETA_WARN_SECONDS and not self.eta_warned:
            self.eta_warned = True
            alerts.append(("eta", f"Context will be full in ~{fmt_duration(eta)} at the current speed "
                                  f"({free:,} tokens free)", True))
        level = max([lv for lv in self.levels if pct >= lv], default=0)
        return alerts, level


# --------------------------------------------------------------------------- graph geometry (no GTK)

def graph_segments(points, now, span, ymax, w, h, step=False):
    """Pixel polylines for (time, value) points; None values break the line.

    x runs over the last `span` seconds, y is value/ymax from the bottom. With step=True the
    value is held until the next point and up to `now` (right for "tokens in context").
    """
    if span <= 0 or ymax <= 0 or w <= 0 or h <= 0:
        return []
    t0 = now - span

    def X(t):
        return max(0.0, min(w, (t - t0) / span * w))

    def Y(v):
        return h - min(max(v, 0) / ymax, 1.0) * h

    segs, cur, prev = [], [], None
    for t, v in points:
        if v is None:
            if cur:
                if step:
                    cur.append((w if t >= now else X(t), cur[-1][1]))
                segs.append(cur)
            cur, prev = [], None
            continue
        if t < t0:
            prev = v
            continue
        if not cur and prev is not None:
            cur.append((0.0, Y(prev)))
        if step and cur:
            cur.append((X(t), cur[-1][1]))
        cur.append((X(t), Y(v)))
    if cur:
        if step:
            cur.append((w, cur[-1][1]))
        segs.append(cur)
    return segs


def nice_max(v, floor=10):
    """Round a speed ceiling up to a tidy number."""
    v = max(v, floor)
    for step in (10, 20, 50, 100, 200, 500):
        if v <= step * 5:
            return -(-v // step) * step
    return -(-v // 1000) * 1000


def graph_span(points, now, window, minimum=60):
    """Time span to show: as much history as exists, between `minimum` and `window` seconds."""
    if not points:
        return minimum
    return max(minimum, min(window, now - points[0][0]))


# --------------------------------------------------------------------------- GPU memory (no GTK)

def read_vram(drm_root="/sys/class/drm"):
    """(used_bytes, total_bytes, name) of the GPU with the most memory, or None.

    AMD cards are read from sysfs; if there are none, nvidia-smi is tried.
    """
    best = None
    for dev in glob.glob(os.path.join(drm_root, "card[0-9]*", "device")):
        try:
            with open(os.path.join(dev, "mem_info_vram_total")) as f:
                total = int(f.read())
            with open(os.path.join(dev, "mem_info_vram_used")) as f:
                used = int(f.read())
        except (OSError, ValueError):
            continue
        if best is None or total > best[1]:
            best = (used, total, os.path.basename(os.path.dirname(dev)))
    if best:
        return best
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=name,memory.used,memory.total", "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=3).stdout
        rows = []
        for line in out.strip().splitlines():
            name, used, total = [x.strip() for x in line.rsplit(",", 2)]
            rows.append((int(used) * 1048576, int(total) * 1048576, name))
        return max(rows, key=lambda r: r[1]) if rows else None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def fmt_gib(n):
    return f"{n / 1073741824:.1f}"


# --------------------------------------------------------------------------- GTK

CSS = b"""
.model { font-size: 18px; font-weight: bold; }
.pct { font-size: 44px; font-weight: bold; }
.sub { font-size: 15px; }
.speed { font-size: 22px; font-weight: bold; }
.dim { opacity: 0.65; font-size: 12px; }
.alert { color: #ffffff; background-color: #d64541; border-radius: 6px; padding: 4px 8px; font-weight: bold; }
.alertwarn { background-color: #e0962b; }
progressbar trough, progressbar progress { min-height: 18px; border-radius: 9px; }
progressbar.ok progress   { background-image: none; background-color: #2e9e5b; }
progressbar.warn progress { background-image: none; background-color: #e0962b; }
progressbar.crit progress { background-image: none; background-color: #d64541; }
progressbar.vram trough, progressbar.vram progress { min-height: 10px; }
"""

if HAVE_GTK:
    try:
        gi.require_foreign("cairo")
        HAVE_CAIRO = True
    except Exception:
        HAVE_CAIRO = False


def make_label(text="", css=None, wrap=False):
    lbl = Gtk.Label(label=text)
    lbl.set_xalign(0)
    if css:
        lbl.get_style_context().add_class(css)
    if wrap:
        lbl.set_line_wrap(True)
    return lbl


def set_bar_level(bar, frac, warn=0.7, crit=0.9):
    bar.set_fraction(frac)
    style = bar.get_style_context()
    for c in ("ok", "warn", "crit"):
        style.remove_class(c)
    style.add_class("crit" if frac >= crit else "warn" if frac >= warn else "ok")


if HAVE_GTK:
    class Graph(Gtk.DrawingArea):
        """Small time-series chart. Needs python3-gi-cairo."""

        LINE = (0.23, 0.51, 0.96)

        def __init__(self, title, unit="", step=False, lines=()):
            super().__init__()
            self.title, self.unit, self.step, self.lines = title, unit, step, lines
            self.points, self.now, self.window, self.ymax, self.latest = [], time.time(), 60, 1, ""
            self.set_size_request(300, 84)
            self.connect("draw", self.on_draw)

        def set_data(self, points, now, window, ymax, latest):
            self.points, self.now, self.window, self.ymax, self.latest = list(points), now, window, ymax, latest
            self.queue_draw()

        def on_draw(self, widget, cr):
            try:
                self.paint(widget, cr)
            except Exception:  # a drawing glitch should never take the monitor down
                pass
            return False

        def paint(self, widget, cr):
            W, H = widget.get_allocated_width(), widget.get_allocated_height()
            top, bottom = 14, 13
            h = H - top - bottom
            if h <= 10 or W <= 20:
                return
            c = widget.get_style_context().get_color(Gtk.StateFlags.NORMAL)
            fg = (c.red, c.green, c.blue)
            span = graph_span(self.points, self.now, self.window)
            # grid + reference lines (fractions of ymax)
            cr.set_line_width(1)
            for frac, col in ((0.0, None), (0.5, None), (1.0, None)) + tuple(self.lines):
                y = top + h - frac * h
                if col:
                    cr.set_source_rgba(*col, 0.55)
                    cr.set_dash([3, 3])
                else:
                    cr.set_source_rgba(*fg, 0.15)
                    cr.set_dash([])
                cr.move_to(0, y)
                cr.line_to(W, y)
                cr.stroke()
            cr.set_dash([])
            segs = graph_segments(self.points, self.now, span, self.ymax, W, h, self.step)
            cr.set_source_rgba(*self.LINE, 1)
            cr.set_line_width(1.6)
            for seg in segs:
                if len(seg) < 2:
                    if seg:  # lone sample: draw a dot
                        cr.arc(seg[0][0], top + seg[0][1], 2, 0, 6.2832)
                        cr.fill()
                    continue
                cr.move_to(seg[0][0], top + seg[0][1])
                for x, y in seg[1:]:
                    cr.line_to(x, top + y)
                cr.stroke_preserve()
                cr.line_to(seg[-1][0], top + h)
                cr.line_to(seg[0][0], top + h)
                cr.close_path()
                cr.set_source_rgba(*self.LINE, 0.15)
                cr.fill()
                cr.set_source_rgba(*self.LINE, 1)
            cr.set_source_rgba(*fg, 0.75)
            cr.set_font_size(10)
            cr.move_to(2, 10)
            cr.show_text(f"{self.title}  max {self.ymax:,.0f}{self.unit}")
            ext = cr.text_extents(self.latest)
            cr.move_to(W - ext.width - 2, 10)
            cr.show_text(self.latest)
            cr.move_to(2, H - 2)
            cr.show_text(f"-{fmt_duration(span)}")
            ext = cr.text_extents("now")
            cr.move_to(W - ext.width - 2, H - 2)
            cr.show_text("now")

    class ServerPanel(Gtk.Box):
        def __init__(self, cfg, state, window_seconds):
            super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            self.cfg, self.state, self.window_seconds = cfg, state, window_seconds
            self.set_size_request(340, -1)

            self.model = make_label("connecting...", "model")
            self.port = make_label(cfg.endpoint + (f"   ({cfg.name})" if cfg.name else ""), "dim")
            self.pct = make_label("0.0%", "pct")
            self.bar = Gtk.ProgressBar()
            self.tokens = make_label("0 tokens", "sub")
            self.source = make_label("", "dim", wrap=True)
            self.free = make_label("", "sub")
            self.alert = make_label("", "alert", wrap=True)
            self.alert.set_no_show_all(True)
            self.gen = make_label("Generation: 0.0 t/s", "speed")
            self.prompt = make_label("", "dim")
            self.status = make_label("", "dim", wrap=True)

            for w in (self.model, self.port, Gtk.Separator(), self.pct, self.bar, self.tokens, self.source,
                      self.free, self.alert):
                self.pack_start(w, False, False, 4 if isinstance(w, Gtk.Separator) else 0)
            self.pack_start(Gtk.Separator(), False, False, 4)
            self.pack_start(self.gen, False, False, 0)
            self.pack_start(self.prompt, False, False, 0)

            if HAVE_CAIRO:
                self.ctx_graph = Graph("Context", " tok", step=True,
                                       lines=((0.7, (0.88, 0.59, 0.17)), (0.9, (0.84, 0.27, 0.25))))
                self.speed_graph = Graph("Speed", " t/s")
                self.pack_start(self.ctx_graph, False, False, 2)
                self.pack_start(self.speed_graph, False, False, 2)
            else:
                self.ctx_graph = self.speed_graph = None
                self.pack_start(make_label("Graphs need the python3-gi-cairo package", "dim"), False, False, 0)

            self.store = Gtk.ListStore(str, str, str, str, str, str)
            tree = Gtk.TreeView(model=self.store)
            for i, title in enumerate(("Time", "Prompt tok", "PP t/s", "Gen tok", "Gen t/s", "Context")):
                r = Gtk.CellRendererText()
                r.set_property("xalign", 0.0 if i == 0 else 1.0)
                col = Gtk.TreeViewColumn(title, r, text=i)
                tree.append_column(col)
            sw = Gtk.ScrolledWindow()
            sw.set_min_content_height(140)
            sw.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
            sw.add(tree)
            self.expander = Gtk.Expander(label="Recent requests (0)")
            self.expander.add(sw)
            self.pack_start(self.expander, False, False, 0)
            self.pack_end(self.status, False, False, 0)
            self.nreq = 0

        @staticmethod
        def _n(v, spec=","):
            return "-" if v is None else format(v, spec)

        def refresh_requests(self):
            reqs = self.state.requests
            if len(reqs) == self.nreq and self.nreq < MAX_REQUESTS:
                return
            self.nreq = len(reqs)
            self.store.clear()
            for r in reversed(reqs):  # newest first
                t = "earlier" if r["t"] is None else time.strftime("%H:%M:%S", time.localtime(r["t"]))
                self.store.append([t, self._n(r["prompt_tokens"]), self._n(r["prompt_tps"], ".1f"),
                                   self._n(r["gen_tokens"]), self._n(r["gen_tps"], ".1f"), self._n(r["ctx"])])
            self.expander.set_label(f"Recent requests ({len(reqs)})")

        def show_view(self, v, now):
            if v["state"] == "slow":
                self.status.set_text(f"server busy / not responding  |  {time.strftime('%H:%M:%S')}")
                return
            if v["state"] == "down":
                ctx = v["ctx"]
                self.model.set_text("No server running")
                self.port.set_text(f"{self.cfg.endpoint}  (not listening)")
                self.pct.set_text("0.0%")
                set_bar_level(self.bar, 0.0)
                self.tokens.set_text(f"0 / {ctx:,} tokens")
                self.source.set_text("")
                self.free.set_text(f"{ctx:,} free")
                self.alert.hide()
                self.gen.set_text("Generation: 0.0 t/s")
                self.prompt.set_text("Prompt processing: 0.0 t/s")
                self.status.set_text(f"server not running  |  checked {time.strftime('%H:%M:%S')}")
            else:
                ctx, shown = v["ctx"], v["shown"]
                self.model.set_text(v["model"] or "server running")
                self.port.set_text(self.cfg.endpoint + (f"   ({self.cfg.name})" if self.cfg.name else ""))
                self.pct.set_text(f"{'~' if v['estimating'] else ''}{v['frac'] * 100:.1f}%")
                set_bar_level(self.bar, v["frac"])
                self.tokens.set_text(f"{'~' if v['estimating'] else ''}{shown:,} / {ctx:,} tokens")
                self.source.set_text(v["source"])
                free = max(ctx - shown, 0)
                eta = f"   (full in ~{fmt_duration(v['eta'])} at this speed)" if v["eta"] and v["eta"] < 600 else ""
                self.free.set_text(f"{'~' if v['estimating'] else ''}{free:,} free{eta}")
                if v["level"]:
                    crit = v["level"] >= self.state.levels[-1]
                    self.alert.set_text(f"{'Context almost full' if crit else 'Context filling up'}: "
                                        f"{v['frac'] * 100:.0f}% used, {free:,} tokens left")
                    ctxs = self.alert.get_style_context()
                    (ctxs.remove_class if crit else ctxs.add_class)("alertwarn")
                    self.alert.show()
                else:
                    self.alert.hide()
                if v["live"] is not None:
                    self.gen.set_text(f"Generation: {v['live']:.1f} t/s (live)")
                elif v["gen"] is not None:
                    self.gen.set_text(f"Generation: {v['gen']:.1f} t/s")
                else:
                    self.gen.set_text("Generation: 0.0 t/s")
                self.prompt.set_text(f"Prompt processing: {v['prompt']:.1f} t/s (last request)"
                                     if v["prompt"] is not None else "")
                run = "generating" if v["busy"] else "idle"
                if not v["log_found"]:
                    self.status.set_text(f"{run}  |  log not found: {self.cfg.log}")
                else:
                    self.status.set_text(f"{run}  |  updated {time.strftime('%H:%M:%S')}")

            if self.ctx_graph:
                ctx = v.get("ctx") or self.cfg.ctx
                last = self.state.ctx_hist[-1][1] if self.state.ctx_hist else 0
                self.ctx_graph.set_data(self.state.ctx_hist, now, self.window_seconds, ctx, f"{last:,}")
                sp = [p[1] for p in self.state.speed_hist if p[1] is not None]
                cur = next((p[1] for p in reversed(self.state.speed_hist) if p[1] is not None), None)
                self.speed_graph.set_data(self.state.speed_hist, now, self.window_seconds,
                                          nice_max(max(sp, default=0)), "" if cur is None else f"{cur:.1f}")
            self.refresh_requests()

    class Tray:
        """System tray icon: AppIndicator if available, else the old StatusIcon, else nothing."""

        def __init__(self, window, names, icon_dir):
            self.ok = False
            self.window = window
            self.indicator = self.status_icon = None
            try:
                theme = Gtk.IconTheme.get_default()
                theme.append_search_path(icon_dir)
                icon = "ctxmon" if theme.has_icon("ctxmon") else "utilities-system-monitor"
                self.menu = Gtk.Menu()
                toggle = Gtk.MenuItem(label="Show / hide window")
                toggle.connect("activate", lambda _i: self.toggle())
                self.menu.append(toggle)
                self.info = []
                for n in names:
                    it = Gtk.MenuItem(label=n)
                    it.set_sensitive(False)
                    self.menu.append(it)
                    self.info.append(it)
                self.menu.append(Gtk.SeparatorMenuItem())
                quit_item = Gtk.MenuItem(label="Quit")
                quit_item.connect("activate", lambda _i: Gtk.main_quit())
                self.menu.append(quit_item)
                self.menu.show_all()
                mod = self._indicator_module()
                if mod is not None:
                    self.indicator = mod.Indicator.new_with_path(
                        "ctxmon", icon, mod.IndicatorCategory.APPLICATION_STATUS, icon_dir)
                    self.indicator.set_status(mod.IndicatorStatus.ACTIVE)
                    self.indicator.set_menu(self.menu)
                    self.ok = True
                else:
                    self.status_icon = Gtk.StatusIcon.new_from_icon_name(icon)
                    self.status_icon.connect("activate", lambda _i: self.toggle())
                    self.status_icon.connect(
                        "popup-menu", lambda i, b, t: self.menu.popup(None, None, Gtk.StatusIcon.position_menu, i, b, t))
                    self.ok = True  # embedded state is reported late, so don't wait for it
            except Exception:
                self.ok = False

        @staticmethod
        def _indicator_module():
            import importlib
            for ns in ("AyatanaAppIndicator3", "AppIndicator3"):
                try:
                    gi.require_version(ns, "0.1")
                    return importlib.import_module("gi.repository." + ns)
                except (ValueError, ImportError):
                    continue
            return None

        def toggle(self):
            if self.window.get_visible():
                self.window.hide()
            else:
                self.window.show()
                self.window.present()

        def update(self, lines, worst_pct):
            if not self.ok:
                return
            for it, text in zip(self.info, lines):
                it.set_label(text)
            label = f"{worst_pct:.0f}%"
            tip = "ctxmon: " + "; ".join(lines)
            try:
                if self.indicator:
                    self.indicator.set_label(label, "100%")
                    self.indicator.set_title(tip)
                elif self.status_icon:
                    self.status_icon.set_tooltip_text(tip)
            except Exception:
                pass

    class VramPoller(threading.Thread):
        def __init__(self, callback):
            super().__init__(daemon=True)
            self.callback = callback

        def run(self):
            while True:
                GLib.idle_add(self.callback, read_vram())
                time.sleep(2)

    class MonitorWindow(Gtk.Window):
        def __init__(self, cfgs, args):
            super().__init__(title="llama-server monitor")
            self.cfgs = cfgs
            self.window_seconds = int(args.history * 60)
            self.set_default_size(min(380 * len(cfgs) + 40, 1300), 860)
            self.set_border_width(14)

            css = Gtk.CssProvider()
            css.load_from_data(CSS)
            Gtk.StyleContext.add_provider_for_screen(
                Gdk.Screen.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

            outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            self.add(outer)

            row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=16)
            self.panels = []
            for i, cfg in enumerate(cfgs):
                if i:
                    row.pack_start(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL), False, False, 0)
                panel = ServerPanel(cfg, ServerState(cfg.ctx, args.warn, self.window_seconds), self.window_seconds)
                self.panels.append(panel)
                row.pack_start(panel, True, True, 0)
            sw = Gtk.ScrolledWindow()
            sw.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
            sw.add(row)
            outer.pack_start(sw, True, True, 0)

            # GPU memory (shared by all servers)
            self.vram_label = make_label("GPU memory: n/a", "sub")
            self.vram_bar = Gtk.ProgressBar()
            self.vram_bar.get_style_context().add_class("vram")
            outer.pack_start(Gtk.Separator(), False, False, 2)
            outer.pack_start(self.vram_label, False, False, 0)
            outer.pack_start(self.vram_bar, False, False, 0)

            # Options popup
            saved = load_settings()
            self.top = Gtk.CheckButton(label="Always on top")
            self.top.set_active(saved["always_on_top"])
            self.warn = Gtk.CheckButton(label="Desktop notifications for context warnings")
            self.warn.set_active(saved["warnings"])
            self.opacity = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, MIN_OPACITY, 100, 5)
            self.opacity.set_value(saved["opacity"])
            self.opacity.set_draw_value(True)
            self.opacity.set_value_pos(Gtk.PositionType.RIGHT)
            self.opacity.set_size_request(220, -1)
            self.opacity.connect("format-value", lambda _s, v: f"{v:.0f}%")
            pop_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            pop_box.set_border_width(12)
            pop_box.pack_start(self.top, False, False, 0)
            pop_box.pack_start(make_label("Opacity (when on top)"), False, False, 0)
            pop_box.pack_start(self.opacity, False, False, 0)
            pop_box.pack_start(self.warn, False, False, 4)
            pop_box.show_all()
            popover = Gtk.Popover()
            popover.add(pop_box)
            options = Gtk.MenuButton(label="Options")
            options.set_popover(popover)
            options.set_direction(Gtk.ArrowType.UP)
            self.top.connect("toggled", self.apply_options)
            self.warn.connect("toggled", self.apply_options)
            self.opacity.connect("value-changed", self.apply_options)
            self.apply_options()

            names = [c.name or c.endpoint for c in cfgs]
            self.tray = None
            if not args.no_tray:
                self.tray = Tray(self, names, os.path.dirname(os.path.abspath(__file__)))
                if not self.tray.ok:
                    self.tray = None

            close = Gtk.Button(label="Close")
            close.connect("clicked", lambda _b: self.destroy())
            bottom = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
            bottom.pack_start(options, False, False, 0)
            bottom.pack_end(close, False, False, 0)
            if self.tray:
                hide = Gtk.Button(label="Hide to tray")
                hide.connect("clicked", lambda _b: self.hide())
                bottom.pack_end(hide, False, False, 0)
            outer.pack_end(bottom, False, False, 0)

            self.connect("destroy", Gtk.main_quit)
            self.summary = {}
            for i, cfg in enumerate(cfgs):
                Poller(cfg, lambda snap, i=i: self.on_update(i, snap)).start()
            VramPoller(self.on_vram).start()

        def apply_options(self, *_a):
            on = self.top.get_active()
            pct = int(self.opacity.get_value())
            self.opacity.set_sensitive(on)
            self.set_keep_above(on)
            self.set_opacity(pct / 100 if on else 1.0)  # normal windows stay fully opaque
            save_settings({"always_on_top": on, "opacity": pct, "warnings": self.warn.get_active()})

        def on_vram(self, info):
            if info is None:
                self.vram_label.set_text("GPU memory: not available (needs AMD sysfs or nvidia-smi)")
                self.vram_bar.set_fraction(0)
            else:
                used, total, name = info
                frac = used / total if total else 0
                self.vram_label.set_text(f"GPU memory ({name}): {fmt_gib(used)} / {fmt_gib(total)} GiB  "
                                         f"({frac * 100:.0f}%)")
                set_bar_level(self.vram_bar, frac, warn=0.85, crit=0.95)
            return False

        def notify(self, title, body, urgent):
            try:
                subprocess.Popen(["notify-send", "-a", "ctxmon", "-u", "critical" if urgent else "normal",
                                  "-i", "dialog-warning", title, body],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except OSError:
                pass  # no notify-send: the in-window banner still shows the warning

        def on_update(self, i, snap):
            panel = self.panels[i]
            now = time.time()
            v = panel.state.update(snap, now)
            panel.show_view(v, now)
            if v["state"] != "slow":
                name = self.cfgs[i].name or v.get("model") or self.cfgs[i].endpoint
                if self.warn.get_active():
                    for _lv, msg, urgent in v["alerts"]:
                        self.notify(f"Context warning: {name}", msg, urgent)
                self.summary[i] = (name, v["frac"] * 100, v["estimating"])
                lines = [f"{n}: {'~' if e else ''}{p:.0f}%" for n, p, e in
                         (self.summary.get(k, (self.cfgs[k].name or self.cfgs[k].endpoint, 0, False))
                          for k in range(len(self.cfgs)))]
                worst = max(p for _n, p, _e in self.summary.values())
                self.set_title(f"llama-server monitor  {worst:.0f}%")
                if self.tray:
                    self.tray.update(lines, worst)
            return False


def main():
    ap = argparse.ArgumentParser(description="llama-server context / speed monitor")
    ap.add_argument("--log", default=os.path.expanduser("~/llama-server-qwen.log"))
    ap.add_argument("--url", default="http://127.0.0.1:8080")
    ap.add_argument("--ctx", type=int, default=131072, help="fallback context size if /slots is unavailable")
    ap.add_argument("--interval", type=float, default=0.5, help="seconds between polls (default 0.5)")
    ap.add_argument("--server", action="append", metavar="SPEC",
                    help="monitor a server: name=NAME,url=URL,log=PATH[,ctx=N]; repeat for several "
                         "(replaces --url/--log)")
    ap.add_argument("--warn", default="80,95", help="context %% levels that raise warnings (default 80,95)")
    ap.add_argument("--history", type=float, default=10, help="minutes of graph history (default 10)")
    ap.add_argument("--no-tray", action="store_true", help="do not create a system tray icon")
    ap.add_argument("--minimized", action="store_true", help="start hidden in the tray (needs a working tray)")
    args = ap.parse_args()
    try:
        args.warn = tuple(sorted({float(x) for x in args.warn.split(",") if x.strip()}))
        if not args.warn or any(not 0 < x <= 100 for x in args.warn):
            raise ValueError
    except ValueError:
        ap.error("--warn needs percentages between 0 and 100, e.g. 80,95")
    try:
        cfgs = ([parse_server_spec(s, args.ctx, args.interval) for s in args.server] if args.server
                else [ServerCfg(args.url, args.log, args.ctx, args.interval)])
    except ValueError as e:
        ap.error(str(e))

    if not HAVE_GTK:
        raise SystemExit("GTK 3 for Python is missing. On Ubuntu: sudo apt install python3-gi gir1.2-gtk-3.0")

    # Lets the taskbar match this window to ctxmon.desktop and show the icon.
    GLib.set_prgname("ctxmon")
    GLib.set_application_name("llama-server monitor")
    Gtk.Window.set_default_icon_name("ctxmon")

    win = MonitorWindow(cfgs, args)
    win.show_all()
    if args.minimized and win.tray:
        win.hide()
    Gtk.main()


if __name__ == "__main__":
    main()
