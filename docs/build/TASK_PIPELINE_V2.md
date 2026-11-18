# Chat → task pipeline v2 (design)

Written 2026-11-19 after reviewing a month of real use on the owner's phone (tasks with their source messages,
saved chat lines, the decision log). This repo is public: findings are given as categories and counts only — no
names or message text.

## 1. What went wrong (18 Oct – 18 Nov 2026)

56 tasks were created from real chats (11 more were leftover test scenarios, never cleaned up).

| Result | Count | Share |
|---|---|---|
| Correct task, usable title and time | 22 | 39 % |
| Real task, wrong title (mostly reversed: "Send X **to <owner>**") | 12 | 21 % |
| Should not exist | 22 | 39 % |

The 22 that should not exist:

| Cause | Count | Example (paraphrased) |
|---|---|---|
| Business / bot sender treated as a person | 9 | travel booking promos, delivery OTP, bank promo in Tamil, AMC reminder addressed to another person, shop receipt |
| Rule fallback on the owner's own messages (model unavailable) | 3 | a question the owner asked became "Submit … today" |
| Chat line that is not a request | 6 | a question, a parent saying "go to sleep", the owner's own emoji reaction, a received file, someone else's travel plan |
| Copied from an earlier ✓ example in the prompt | 2 | an old hotel-booking title reappeared on unrelated chats (and was caught twice more) |
| Garbled call transcript | 1 | |
| Job post for another batch | 1 | "graduating in 2027" not matched by the batch filter |

Missed tasks (from the log and saved lines; the log only keeps 200 entries, so these are lower bounds):
- **Requests spread over several messages** — e.g. "can't send the UPI id" → a payment QR → "do it, it'll expire":
  each piece was judged alone, the first as "no task", the rest as "skipped: no task words".
- "send it, I need it tomorrow"-type requests and "send me the details" were judged "no task".

Other faults:
- Tasks with no deadline get a made-up one (23:59 today, or +1 h in groups) → pointless alarms.
- A delivery OTP was copied into a task title (sensitive data in the task list and log).
- One long message made the model time out (120 s).
- System texts ("Checking for new calls", progress notifications) reach the pipeline.

## 2. Root causes

1. **No idea who is talking.** Every chat is treated as a person. Business accounts, bots and broadcast channels
   produce most false tasks.
2. **Messages are judged in 20-second bursts.** A request whose parts arrive minutes apart is split into pieces
   that are each judged alone. Three rules then make it worse:
   - the model is trained to *never create a task from earlier lines* (so the follow-up can't complete it),
   - a keyword pre-filter drops short follow-ups ("do it", "it'll expire") before the model sees them,
   - in groups, a follow-up from the person who asked is dropped unless it @mentions the owner again.
3. **The model doesn't know the owner's name appears in messages.** Training data rarely put the owner's name in
   the message, so "<owner>, send the ppt" becomes "Send ppt to <owner>".
4. **Few-shot ✓/✗ examples in the prompt leak.** A 1B model copies their titles into unrelated chats. The
   "copied from examples" check only catches some.
5. **Fallbacks invent tasks.** When the model fails, a rule extractor turns the owner's sentences into promises.
6. **Missing deadline = invented deadline.**
7. **The 15-scenario phone test is too small** to have shown any of this.

## 3. Design

```
notification / screen  →  1 Ingest  →  2 Who & what  →  3 Conversation window  →  4 Understand (model)
                                                                                  →  5 Check  →  6 Decide  →  tasks
                                                       owner ✓ / ✗  →  7 Learn (data, not prompt)
```

### 1 Ingest
- One event per real message: app, chat, sender, own/other, group, time, text. Drop app/system texts (call
  checks, progress, "N new messages"), reactions, stickers, deleted messages.
- Same message seen by notification *and* screen → one event (hash of chat + sender + text + minute).

### 2 Who & what (deterministic, before any model)
- **Sender kind:** `contact` (in the phone's contacts), `unknown number`, `business` (WhatsApp business/verified
  name, no number, broadcast/channel), `group`.
- `business` and channels never create tasks on their own. Exceptions are explicit, owner-controlled lists
  (e.g. a job-updates channel → "job post" path with the batch filter).
- **Sensitive content:** OTPs, card/account numbers, UPI PINs → never stored in tasks or the log (masked).
- Long messages are cut to 600 characters for the model.

### 3 Conversation window (replaces 20 s bursts)
- Per chat: the last 60 minutes, at most 20 lines, each numbered and marked `NEW` or `seen`, plus this chat's
  open tasks (numbered).
- Every time a chat goes quiet for 20 s, the **whole window** is re-read, not just the new lines. A request whose
  parts arrive minutes apart is therefore read together.
- No keyword pre-filter. The model is skipped only for chats whose window has no new line from a person.
- Groups: a window is read when a NEW line mentions the owner, **or** comes from someone who mentioned the owner
  earlier in the window, **or** an open task came from this group.

### 4 Understand (on-device model)
Output = operations on the owner's task list, each pointing at its evidence:

```json
{"ops": [
  {"op": "create", "title": "Send notes to Riya", "when": "kal 5 baje", "who": "Riya",
   "evidence": [3, 5], "confidence": 0.9},
  {"op": "update", "task": 1, "when": "monday", "evidence": [7]},
  {"op": "done", "task": 2, "evidence": [8]}
], "reason": "…"}
```

Rules the model is trained on (v6 data):
- A task may be created from **any** lines in the window if at least one evidence line is NEW (the old "never
  from earlier lines" rule goes; re-creating an open task is prevented by step 5 instead).
- The owner's name in a message means *addressed to the owner*; titles never contain the owner's name. `who` is
  the requester / recipient.
- Business, bot and broadcast text, questions, plans of other people, files received, greetings → no op.

### 5 Check (deterministic, catches model mistakes)
- Every evidence line exists, and at least one is NEW.
- Every name and number in the title and `when` appears in the evidence lines (kills copied and invented
  details).
- The title doesn't contain the owner's name. If it does, the op is rewritten with `who` or dropped.
- `create` that matches an open task → becomes `update`.
- Job posts: batch filter (also "graduating in 20xx", "20xx passouts").

### 6 Decide
- confidence ≥ 0.75 → task; 0.4–0.75 → ask (notification with ✓/✗); lower → log only.
- **No deadline said → no deadline.** Such tasks go in the morning/evening digest, not an alarm.
- If the model fails or times out → the window stays pending and is retried (max 2). No rule fallback.

### 7 Learn
- ✓ / ✗ / edits are stored on the phone as labelled windows. They are **not** put in the prompt.
- They form the owner's private test set (stays on the owner's devices: the phone and the laptop, never the
  DGX). The DGX trains only on synthetic data shaped after the failure categories above.

## 4. Model plan (v6)

- Data: new generator categories that mirror section 1: business/bot/promo/OTP/receipt messages; the owner's
  name inside requests; multi-message requests spread over a window with NEW markers and evidence ids; follow-ups
  without a mention in groups; parents/friends small talk; the owner's own questions; received files.
- Output format = the ops JSON above (shorter than today's, so faster on the phone).
- Eval: (a) synthetic held-out by category, (b) the owner's private real test set on the laptop (5070 Ti) only,
  (c) the phone golden set grown from 15 to ~60 scenarios, one per failure category. Report per category with
  confidence intervals (method from LocalAI's B-lane).
- Ship rule: a new model replaces the phone model only if it beats it on (b) and (c) and stays ≤ 1.2 GB PSS.

## 5. Reused from LocalAI / llm-firewall

| Part | Use in PAA | When |
|---|---|---|
| llm-firewall (DeBERTa-v3-small injection detector, int8 TFLite) | Score untrusted text before anything can act on it: chat-triggered suggestions, auto-replies, the screen agent, orders. | P3/P5 (the Guard already blocks chat-triggered sends) |
| Memory-table design (rows with provenance, delete, snapshot/rollback) | Personal memory (P5-T6): every remembered fact has its source message and can be deleted. | P5 |
| Eval method (source-held-out splits, CIs, AgentDojo-style injection tests) | Section 4 eval; injection tests for P3/P5. | now |
| The LocalAI model itself (OLMoE-1B-7B + memory tables + JEPA) | **Not used** — 7B total parameters, research-grade, no phone runtime. | — |

## 6. Order of work

1. **Stop the damage (app only, no new model):** sender kind filter; no rule fallback; no invented deadlines;
   OTP masking; prompt without ✓/✗ examples; owner-name title check; evidence/grounding check; remove leftover
   test tasks (owner approves); system-text filter; 600-char cut.
2. **Conversation window** + group follow-up rule + no keyword pre-filter (works with today's model; the old
   "no earlier lines" habit of v4 limits it until v6).
3. **v6 data + training + export** on the DGX (ops format, new categories).
4. **Eval sets** (private real set on the laptop, golden set to ~60) and the ship rule.
5. **Ship v6** after it wins; then P1-T7 onward.
