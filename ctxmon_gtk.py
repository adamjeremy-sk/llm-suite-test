#!/usr/bin/env python3
"""Context Monitor: a small GTK 3 window showing llama-server context usage and speed.

- Context tokens and the final tokens/sec of finished requests come from the
  server log (the file given to --log-file).
- Model name, context size, busy/idle state and the number of tokens generated so
  far by the running request come from the server's HTTP API (/props, /slots);
  the live speed is derived from how fast that number grows.
- If nothing is listening on the port, everything reads zero.

Requires: python3-gi and gir1.2-gtk-3.0 (both preinstalled on Ubuntu desktop).

Usage:  ./ctxmon_gtk.py [--log PATH] [--url URL] [--ctx N] [--interval SEC]
"""
import argparse
import json
import os
import re
import socket
import threading
import time
import urllib.error
from collections import deque
from urllib.parse import urlparse
from urllib.request import urlopen

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

TAIL_BYTES = 512 * 1024  # how much of the log's end to scan per poll
LIVE_WINDOW = 2.0        # seconds of samples used for the live speed

# llama.cpp has used different wording over time; accept all of them.
#   new:  "slot release: ... stop processing: n_tokens = 1234, ..."
#   new:  "prompt processing done, n_tokens = 1234, ..."
#   old:  "... n_past = 1234" (also appears in "prompt processing progress")
TOKEN_RE = re.compile(
    r"stop processing: n_tokens = (\d+)"
    r"|prompt processing done, n_tokens = (\d+)"
    r"|\bn_past = (\d+)"
)

# Timing lines printed when a request finishes:
#   prompt eval time =  123.45 ms /  50 tokens (  2.47 ms per token,  405.00 tokens per second)
#          eval time = 1234.56 ms / 100 tokens ( 12.35 ms per token,   81.00 tokens per second)
_SPEED = r"=\s*[\d.]+ ms /\s*\d+ (?:tokens|runs)\s*\(\s*[\d.]+ ms per token,\s*([\d.]+) tokens per second\)"
GEN_RE = re.compile(r"(?<!prompt )eval time " + _SPEED)
PROMPT_RE = re.compile(r"prompt eval time " + _SPEED)


SETTINGS_PATH = os.path.join(
    os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), "ctxmon", "settings.json"
)
MIN_OPACITY = 30  # percent; lower than this and the window is effectively invisible


def load_settings(path=SETTINGS_PATH):
    """Saved window options, with defaults for anything missing or unreadable."""
    s = {"always_on_top": False, "opacity": 100}
    try:
        with open(path) as f:
            saved = json.load(f)
        s["always_on_top"] = bool(saved.get("always_on_top", False))
        s["opacity"] = max(MIN_OPACITY, min(100, int(saved.get("opacity", 100))))
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


def last_tokens(path, floor=0):
    """Return (context_tokens_or_None, log_found), ignoring log content before byte `floor`."""
    try:
        size = os.path.getsize(path)
        if floor > size:  # log was truncated (server restarted)
            floor = 0
        with open(path, "rb") as f:
            f.seek(max(floor, size - TAIL_BYTES))
            text = f.read().decode("utf-8", errors="replace")
    except OSError:
        return None, False
    last = None
    for m in TOKEN_RE.finditer(text):
        last = next(g for g in m.groups() if g is not None)
    return (int(last) if last is not None else None), True


def last_speeds(text):
    """Return (gen_tps, prompt_tps) of the last matches in text (None if absent)."""
    g = GEN_RE.findall(text)
    p = PROMPT_RE.findall(text)
    return (float(g[-1]) if g else None), (float(p[-1]) if p else None)


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
    """Polls log + server off the UI thread and hands results to a callback."""

    def __init__(self, args, callback):
        super().__init__(daemon=True)
        self.args = args
        self.callback = callback
        self.offset = None      # how far into the log we've read
        self.floor = 0          # ignore log content before this byte (older server sessions)
        self.last_gen = None    # tokens/sec of the latest finished request
        self.last_prompt = None
        self.prev_state = None
        self.model = None
        self.model_checked = 0.0

    def new_session(self):
        """The server just came up after being down: forget everything from the old one."""
        try:
            size = os.path.getsize(self.args.log)
        except OSError:
            size = 0
        self.floor = size
        self.offset = size
        self.last_gen = None
        self.last_prompt = None

    def read_new(self):
        """Read complete new log lines and remember the latest finished-request speeds."""
        path = self.args.log
        try:
            size = os.path.getsize(path)
        except OSError:
            return
        if self.offset is None:
            # First look: take the latest values from history.
            with open(path, "rb") as f:
                f.seek(max(self.floor, size - TAIL_BYTES))
                text = f.read().decode("utf-8", errors="replace")
            self.offset = size
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

    def run(self):
        while True:
            n_ctx, busy, n_decoded, state = slot_info(self.args.url)
            now = time.time()
            if state == "down":
                self.model = None
                self.last_gen = self.last_prompt = None
            elif state == "up":
                if self.prev_state == "down":
                    self.new_session()
                age = now - self.model_checked
                if (self.model is None and age > 5) or age > 30:
                    self.model = server_info(self.args.url) or self.model
                    self.model_checked = now
            if state != "slow":
                self.prev_state = state

            used, log_found = last_tokens(self.args.log, self.floor)
            if state == "up":
                try:
                    self.read_new()
                except OSError:
                    pass
            snap = dict(used=used, n_ctx=n_ctx, busy=busy, n_decoded=n_decoded,
                        state=state, model=self.model, log_found=log_found,
                        gen=self.last_gen, prompt=self.last_prompt)
            GLib.idle_add(self.callback, snap)
            time.sleep(self.args.interval)


CSS = b"""
.model { font-size: 18px; font-weight: bold; }
.pct { font-size: 44px; font-weight: bold; }
.sub { font-size: 15px; }
.speed { font-size: 22px; font-weight: bold; }
.dim { opacity: 0.65; font-size: 12px; }
progressbar trough, progressbar progress { min-height: 18px; border-radius: 9px; }
progressbar.ok progress   { background-image: none; background-color: #2e9e5b; }
progressbar.warn progress { background-image: none; background-color: #e0962b; }
progressbar.crit progress { background-image: none; background-color: #d64541; }
"""


def make_label(text="", css=None, wrap=False):
    lbl = Gtk.Label(label=text)
    lbl.set_xalign(0)
    if css:
        lbl.get_style_context().add_class(css)
    if wrap:
        lbl.set_line_wrap(True)
    return lbl


class MonitorWindow(Gtk.Window):
    def __init__(self, args):
        super().__init__(title="llama-server monitor")
        self.args = args
        u = urlparse(args.url)
        self.endpoint = f"{u.hostname or '127.0.0.1'}:{u.port or (443 if u.scheme == 'https' else 80)}"
        self.samples = deque(maxlen=64)  # (time, n_decoded) while a request is generating
        self.last_ctx = None
        self.set_default_size(380, 380)
        self.set_border_width(18)

        css = Gtk.CssProvider()
        css.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.add(box)

        self.model = make_label("connecting...", "model")
        self.port = make_label(self.endpoint, "dim")
        self.pct = make_label("0.0%", "pct")
        self.bar = Gtk.ProgressBar()
        self.tokens = make_label("0 tokens", "sub")
        self.free = make_label("", "sub")
        self.gen = make_label("Generation: 0.0 t/s", "speed")
        self.prompt = make_label("", "dim")
        self.status = make_label("", "dim", wrap=True)

        box.pack_start(self.model, False, False, 0)
        box.pack_start(self.port, False, False, 0)
        box.pack_start(Gtk.Separator(), False, False, 6)
        box.pack_start(self.pct, False, False, 0)
        box.pack_start(self.bar, False, False, 4)
        box.pack_start(self.tokens, False, False, 0)
        box.pack_start(self.free, False, False, 0)
        box.pack_start(Gtk.Separator(), False, False, 6)
        box.pack_start(self.gen, False, False, 0)
        box.pack_start(self.prompt, False, False, 0)

        # Options popup: always-on-top toggle + opacity slider (slider only while on top).
        saved = load_settings()
        self.top = Gtk.CheckButton(label="Always on top")
        self.top.set_active(saved["always_on_top"])
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
        pop_box.show_all()
        popover = Gtk.Popover()
        popover.add(pop_box)
        options = Gtk.MenuButton(label="Options")
        options.set_popover(popover)
        options.set_direction(Gtk.ArrowType.UP)

        self.top.connect("toggled", self.apply_options)
        self.opacity.connect("value-changed", self.apply_options)
        self.apply_options()

        close = Gtk.Button(label="Close")
        close.connect("clicked", lambda _b: self.destroy())
        bottom = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        bottom.pack_start(options, False, False, 0)
        bottom.pack_end(close, False, False, 0)
        box.pack_end(bottom, False, False, 0)
        box.pack_end(self.status, False, False, 0)

        self.connect("destroy", Gtk.main_quit)
        Poller(args, self.on_update).start()

    def apply_options(self, *_args):
        on = self.top.get_active()
        pct = int(self.opacity.get_value())
        self.opacity.set_sensitive(on)
        self.set_keep_above(on)
        self.set_opacity(pct / 100 if on else 1.0)  # normal windows stay fully opaque
        save_settings({"always_on_top": on, "opacity": pct})

    def set_level(self, frac):
        self.bar.set_fraction(frac)
        style = self.bar.get_style_context()
        for c in ("ok", "warn", "crit"):
            style.remove_class(c)
        style.add_class("crit" if frac >= 0.9 else "warn" if frac >= 0.7 else "ok")

    def show_zero(self):
        """Nothing is listening: everything reads zero."""
        ctx = self.last_ctx or self.args.ctx
        self.samples.clear()
        self.model.set_text("No server running")
        self.port.set_text(f"{self.endpoint}  (not listening)")
        self.pct.set_text("0.0%")
        self.set_level(0.0)
        self.tokens.set_text(f"0 / {ctx:,} tokens")
        self.free.set_text(f"{ctx:,} free")
        self.gen.set_text("Generation: 0.0 t/s")
        self.prompt.set_text("Prompt processing: 0.0 t/s")
        self.status.set_text(f"server not running  |  checked {time.strftime('%H:%M:%S')}")

    def on_update(self, s):
        state = s["state"]
        if state == "down":
            self.show_zero()
            return False
        if state == "slow":
            # A busy server can be slow to answer; keep the last numbers on screen.
            self.status.set_text(f"server busy / not responding  |  {time.strftime('%H:%M:%S')}")
            return False

        used, busy, n_decoded = s["used"], s["busy"], s["n_decoded"]
        ctx = s["n_ctx"] or self.args.ctx
        self.last_ctx = ctx

        self.model.set_text(s["model"] or "server running")
        self.port.set_text(self.endpoint)

        # Live speed from tokens generated so far by the running request.
        live = None
        if busy and n_decoded is not None:
            if self.samples and n_decoded < self.samples[-1][1]:
                self.samples.clear()  # a new request started
            self.samples.append((time.time(), n_decoded))
            live = live_speed(self.samples)
        else:
            self.samples.clear()

        if live is not None:
            self.gen.set_text(f"Generation: {live:.1f} t/s (live)")
        elif s["gen"] is not None:
            self.gen.set_text(f"Generation: {s['gen']:.1f} t/s")
        else:
            self.gen.set_text("Generation: 0.0 t/s")
        if s["prompt"] is not None:
            self.prompt.set_text(f"Prompt processing: {s['prompt']:.1f} t/s (last request)")
        else:
            self.prompt.set_text("")

        run_state = "generating" if busy else "idle"
        if not s["log_found"]:
            self.status.set_text(f"{run_state}  |  log not found: {self.args.log}")
        elif used is None:
            self.status.set_text(f"{run_state}  |  no request in log yet  |  {time.strftime('%H:%M:%S')}")
        else:
            self.status.set_text(f"{run_state}  |  updated {time.strftime('%H:%M:%S')}")

        # A server with no requests yet has an empty context.
        base = used or 0
        # While generating, the log still shows the prompt size; add what's been decoded.
        estimating = bool(busy and n_decoded)
        shown = base + n_decoded if estimating else base
        frac = min(shown / ctx, 1.0)
        self.pct.set_text(f"{frac * 100:.1f}%")
        self.set_level(frac)
        self.tokens.set_text(f"{'~' if estimating else ''}{shown:,} / {ctx:,} tokens")
        self.free.set_text(f"{max(ctx - shown, 0):,} free")
        return False  # run once per idle_add


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=os.path.expanduser("~/llama-server-qwen.log"))
    ap.add_argument("--url", default="http://127.0.0.1:8080")
    ap.add_argument("--ctx", type=int, default=131072,
                    help="fallback context size if /slots is unavailable")
    ap.add_argument("--interval", type=float, default=0.5,
                    help="seconds between polls (default 0.5)")
    args = ap.parse_args()

    # Lets the taskbar match this window to ctxmon.desktop and show the icon.
    GLib.set_prgname("ctxmon")
    GLib.set_application_name("llama-server monitor")
    Gtk.Window.set_default_icon_name("ctxmon")

    win = MonitorWindow(args)
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
