"""Laptop WhatsApp reader (read-only): polls WhatsApp Desktop's chat list through wa_desktop.ps1 and turns preview
changes into new-message events for the phone. Owner's choice (2026-11-28): previews only, never open a chat (opening
marks it read). Messages are kept in memory only (never written to disk) and only until the phone collects them.

Row text looks like  "<chat> <time|Yesterday|date> [Muted chat] [You | <sender> :] <preview>"  (WhatsApp Desktop,
WebView2). A preview that differs from the last one seen for that chat is a new message; the first snapshot only seeds.
"""
import json
import re
import subprocess
import threading
import time
from collections import deque
from pathlib import Path

PS1 = Path(__file__).with_name("wa_desktop.ps1")
TIME = re.compile(r"\s(\d{1,2}:\d{2}\s?(?:am|pm|AM|PM)?|Yesterday|Today|\d{1,2}/\d{1,2}/\d{2,4}|"
                  r"Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)(?=\s|$)")
UI_NOISE = re.compile(r"\s*(Muted chat|Pinned chat|\d+ unread messages?|unread messages?|Draft)\s*")
SENDER = re.compile(r"^(~?[^:]{1,40}?)\s:\s+(.*)$", re.S)
# Shown in place of the preview for a few seconds; the old preview comes back after. Never a message, never stored.
TRANSIENT = re.compile(r"^(?:.{0,40}\s)?(?:is\s)?(?:typing|recording audio|recording video)(?:…|\.\.\.)?$", re.I)
# Placeholders that replace a real preview: remembered (so the next real one is compared to them), never an event.
PLACEHOLDER = re.compile(r"^(This message was deleted|You deleted this message|Waiting for this message.*|"
                         r"Missed (voice|video) call|Voice call|Video call)$", re.I)
TOP_ROWS = 3        # a chat first seen after seeding only counts if it is near the top (new messages move it up)
HISTORY = 4         # previews remembered per chat: one that comes back (after typing…, an edit) is not new again


def parse_row(row: str):
    """-> {chat, sender, text, outgoing, group} or None (no time anchor / empty preview)."""
    m = TIME.search(row)
    if not m:
        return None
    chat = row[:m.start()].strip()
    rest = UI_NOISE.sub(" ", row[m.end():]).strip().lstrip("|").strip()
    if not chat or not rest:
        return None
    outgoing, sender = False, None
    if rest.startswith("You"):
        outgoing, rest = True, re.sub(r"^You\s*:?\s*", "", rest)
    else:
        s = SENDER.match(rest)
        if s:
            sender, rest = s.group(1).lstrip("~").strip(), s.group(2).strip()
    if not rest:
        return None
    return {"chat": chat, "sender": sender, "text": rest, "outgoing": outgoing, "group": sender is not None}


class Inbox:
    """New-message events for the phone, in memory, oldest dropped (max 300, max 1 h old)."""

    def __init__(self, cap=300, ttl=3600):
        self.items, self.seq, self.ttl, self.lock = deque(maxlen=cap), 0, ttl, threading.Lock()

    def add(self, item: dict):
        with self.lock:
            self.seq += 1
            self.items.append({**item, "seq": self.seq, "at": int(time.time())})

    def after(self, seq: int) -> dict:
        now = time.time()
        with self.lock:
            fresh = [i for i in self.items if i["seq"] > seq and now - i["at"] < self.ttl]
            return {"items": fresh, "last": self.seq}


class Reader:
    def __init__(self, inbox: Inbox, every: float = 20, snapshot=None):
        self.inbox, self.every = inbox, every
        self.snapshot = snapshot or self._ps_snapshot
        self.last: dict = {}   # chat -> recent previews seen (newest last), in memory only
        self.seeded = False

    @staticmethod
    def _ps_snapshot() -> list | None:
        r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(PS1)],
                           capture_output=True, timeout=60)
        out = json.loads(r.stdout.decode("utf-8", "replace") or "{}")
        return (out.get("rows") or []) if out.get("window") else None

    def step(self) -> int:
        rows = self.snapshot()
        if rows is None:          # WhatsApp window closed: keep the old state, try again later
            return 0
        new = 0
        for i, row in enumerate(rows):
            p = parse_row(row)
            if not p or TRANSIENT.match(p["text"]):
                continue
            seen = self.last.get(p["chat"])
            if seen is not None and p["text"] in seen:
                continue
            fresh_chat = seen is None
            if seen is None:
                seen = self.last[p["chat"]] = deque(maxlen=HISTORY)
            seen.append(p["text"])
            if (self.seeded and not p["outgoing"] and not PLACEHOLDER.match(p["text"])
                    and (not fresh_chat or i < TOP_ROWS)):   # a chat scrolled into view is old, not new
                self.inbox.add(p)
                new += 1
        self.seeded = True
        return new

    def run_forever(self):
        while True:
            try:
                self.step()
            except Exception as e:   # never take the hub down; no message text in the log
                print(f"wa reader: {type(e).__name__}", flush=True)
            time.sleep(self.every)
