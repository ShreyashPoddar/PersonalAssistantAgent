"""The compact prompt the fine-tuned on-device model is trained on and served with.

MUST stay identical to LocalLlm.buildCompactPrompt (Kotlin). `python train/paa_format.py --golden` rewrites
train/golden_prompt.txt; the Kotlin unit test CompactPromptTest compares its own output to that file, so any
drift between training and the phone fails the build.

A fine-tuned model has learned the rules, so the prompt carries only the facts (≈150 tokens instead of ≈750):
that is most of the speed-up on the phone, since reading the prompt dominates CPU/GPU time.
"""
import json
from datetime import datetime

TURN_PREFIX = "<start_of_turn>user\n"
TURN_SUFFIX = "<end_of_turn>\n<start_of_turn>model\n"
STOP = "<end_of_turn>"


def _clean(s: str) -> str:
    return s[:200].replace('"', "'").replace("\n", " ")


def _cap(n: str) -> str:
    return n[:1].upper() + n[1:]


def build_prompt(message: str, ctx: dict, now: datetime, names: list, grad_year: int) -> str:
    """ctx keys: outgoing, chat_name, sender, is_group, recent, learned [(msg, title|None)], is_call, open_tasks, burst."""
    chat = ctx.get("chat_name") or "?"
    lines = [f"Now: {now:%A, %Y-%m-%d %H:%M}", f"Owner: {' or '.join(_cap(n) for n in names)}, batch {grad_year}"]
    if ctx.get("is_call"):
        src = "call transcript (both speakers mixed, may have recognition errors)"
    elif ctx.get("burst"):
        src = f'new lines of chat "{chat}"' + (" (group)" if ctx.get("is_group") else "") + ", oldest first, 'You' is the owner"
    elif ctx.get("outgoing"):
        src = "sent by the owner" + (f" to {ctx['chat_name']}" if ctx.get("chat_name") else "")
    elif ctx.get("is_group"):
        src = f'received in group "{chat}"' + (f" from {ctx['sender']}" if ctx.get("sender") else "")
    else:
        who = ctx.get("sender") or ctx.get("chat_name")
        src = "received" + (f" from {who}" if who else "")
    lines.append("Source: " + src)
    recent = (ctx.get("recent") or [])[-8:]
    if recent:
        lines.append("Earlier:")
        lines += ["- " + _clean(m) for m in recent]
    open_tasks = ctx.get("open_tasks") or []
    if open_tasks:
        lines.append("Open tasks:")
        lines += [f"{i + 1}. {_clean(t)}" for i, t in enumerate(open_tasks)]
    learned = ctx.get("learned") or []
    if learned:
        lines.append("Owner's past judgements:")
        lines += [f'- "{_clean(m)}" -> ' + (f"task: {t}" if t else "not a task") for m, t in learned]
    burst = bool(ctx.get("burst"))
    msg = message[: 1400 if (ctx.get("is_call") or burst) else 800].replace('"', "'")
    if not burst:
        msg = msg.replace("\n", " ")
    lines.append(f'Message: "{msg}"')
    return "\n".join(lines)


def wrap(prompt: str) -> str:
    """Gemma chat turn, exactly as the app sends it (MediaPipe adds <bos> itself)."""
    return TURN_PREFIX + prompt + TURN_SUFFIX


def canonical_target(t: dict) -> str:
    """The JSON the model learns to write: the schema LocalLlm.parseVerdict already reads, keys in a fixed order."""
    tasks = [{"title": x["title"], "when": x.get("when"), "priority": int(x.get("priority", 1)),
              "registration": bool(x.get("registration", False)), "confidence": round(float(x.get("confidence", 0.8)), 2),
              "reason": x.get("reason") or ""} for x in (t.get("tasks") or [])]
    update = [{"n": int(u["n"]), "when": u.get("when"), "title": u.get("title")} for u in (t.get("update") or [])]
    out = {"tasks": tasks, "reason": "" if tasks else (t.get("reason") or ""),
           "done": [int(n) for n in (t.get("done") or [])], "update": update}
    return json.dumps(out, ensure_ascii=False)


def record_prompt(r: dict) -> str:
    """A dataset record (see gen_data.py) -> the user-turn text."""
    return build_prompt(r["message"], r["ctx"], datetime.fromisoformat(r["now"]), r["owner_names"], r["grad_year"])


GOLDEN_CASES = [  # (message, ctx) — mirrored in CompactPromptTest.kt
    ("@shreyash bring the projector at 1145", {"chat_name": "Project Group", "sender": "Rohit", "is_group": True,
                                              "recent": ["Aman: ppt done?", 'Rohit: "almost"\nfinal']}),
    ("You: haan\nZoya: parso tak\nZoya: 11 baje se pehle", {"chat_name": "Zoya", "burst": True,
                                                          "open_tasks": ["Send essay to Zoya (kal)"]}),
    ("kal bhej dunga", {"chat_name": "Papa", "outgoing": True, "learned": [("ok", None), ("send it", "Send the file")]}),
    ("so tum kal tak form bhar dena", {"is_call": True}),
    ("hi", {}),
]
GOLDEN_NOW = datetime(2026, 10, 12, 21, 5)

if __name__ == "__main__":
    import sys
    from pathlib import Path
    if "--golden" in sys.argv:
        text = "\n=====\n".join(wrap(build_prompt(m, c, GOLDEN_NOW, ["shreyash", "shreyas"], 2028)) for m, c in GOLDEN_CASES)
        Path(__file__).with_name("golden_prompt.txt").write_text(text, encoding="utf-8", newline="\n")
        print("wrote golden_prompt.txt")
