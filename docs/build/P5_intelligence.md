# Phase 5 — On-device intelligence

The models that make PAA fast, private and smart. The task-extraction model pipeline already exists in `train/` and
`scripts/dgx/` (`docs/MODEL_PLAN.md`); this phase ships it to the phone and adds the gate, the intent model, the
screen agent, and on-phone measurement. Order roughly T1 → T8, but T1–T3 can follow `MODEL_PLAN.md` phases directly.

---

## P5-T1 — Ship the task model to the phone
**Goal:** the fine-tuned model (`paa-*.task`) runs on the phone, fast, using the compact prompt.
**Depends on:** `train/` pipeline (the DGX produces the `.task`).
**Steps**
1. When the DGX finishes (`tools/gpu.py fetch export-1b-v1` / `export-270m-v1`), install on the phone:
   `adb push paa-tasks-1b-v1.task /sdcard/Android/data/com.paa.assistant/files/models/local_model.task` then
   `adb shell touch .../local_model.task.paa` (the `.paa` marker switches `LocalLlm` to the compact prompt — already built).
2. Measure on the phone: time per message, peak RAM (`MainViewModel.memoryMb`), battery. Record in `docs/MODEL_PLAN.md` status.
3. Gate: `tools/eval_chats.py --serial <serial>` must stay 15/15. Ship the smaller model (270M) if it passes.
**Verify on phone:** real chats scheduled correctly; no hang; time/RAM noted.

---

## P5-T2 — Learned "is this a task?" gate
**Goal:** a <5 ms, ~1–3 MB classifier that stops ~70–80% of messages (ok, memes, ads) before the model runs — the
biggest CPU/battery saving (`MODEL_PLAN.md` Phase 2.1).
**Depends on:** the synthetic data (`train/`).
**Steps**
1. Train a logistic-regression / tiny n-gram model on the synthetic data (label: has-task vs not). Add a
   `train/train_gate.py` that outputs weights as a small asset (JSON/flatbuffer).
2. `core/brain/TaskGate.kt` — pure Kotlin: hash char/word n-grams, dot with weights, sigmoid; `fun score(text): Float`.
   Replaces the hand-written `TaskSignal` as the first filter in `ChatMessageProcessor` (keep `TaskSignal` as a
   fallback if the asset is missing).
3. Tune the threshold on the validation set for high recall (don't miss real tasks).
**Tests:** `TaskGateTest` on held-out synthetic examples; recall ≥ 0.97 on task-bearing messages.
**Verify on phone:** most trivial messages never wake the model (check the 🧠 log stats).

---

## P5-T3 — Intent + slots model (voice commands)
**Goal:** replace brittle regexes for commands ("order 2 dosa from X", "open insta", "message papa …", "cheapest
train to Hyderabad Friday") with a small fine-tuned model that outputs a typed `Action`.
**Depends on:** P5-T1 infra, P1 actions.
**Steps**
1. Extend the synthetic data generator with command examples → `(utterance → Action JSON)` pairs covering every
   `Action` subclass. Train a LoRA adapter on the same base model (one base in RAM, adapters per job — `MODEL_PLAN.md` 5.1).
2. `core/brain/IntentModel.kt` — runs the adapter, parses to an `Action` (validated; unknown → fall back to
   `IntentRouter` regex, then cloud).
3. Wire into `VoiceOverlayViewModel.processCommand` before the regex router.
**Tests:** `IntentModelParseTest` (model output JSON → Action) on synthetic utterances.
**Verify on phone:** the command set works in Hinglish; latency acceptable.

---

## P5-T4 — Expense classifier model (optional upgrade)
**Goal:** better categories than keyword rules (`P2-T5`).
**Steps:** train a tiny classifier on synthetic + the owner's corrections (on-phone fine-tune later); drop in behind
`Categorizer` as the first choice, rules as fallback. Keep it optional — rules already work.

---

## P5-T5 — Generic screen agent ("do anything")
**Goal:** handle apps/tasks with no scripted flow, by reading the screen and deciding the next step.
**Depends on:** P3-T2 (FlowEngine primitives), P1-T11 (hardening).
**Steps**
1. `core/agent/ScreenAgent.kt` — loop: read the (sanitised, filtered) accessibility tree → ask a model "next step
   toward goal G" → the model returns a **typed** step (tap id/text, type, scroll, done, ask-owner) → FlowEngine
   executes it → repeat, with a step cap and a visible "PAA is acting" indicator.
2. Model: on-device intent model for simple screens; cloud model only for hard ones **and only on non-sensitive
   apps** (never on bank/UPI/chat apps — a hard allowlist check on the foreground package before any screen text
   leaves the phone). The same owner-only/confirm rules apply to any action it proposes.
3. **Promote to a skill:** when a screen-agent run for a task succeeds a few times, offer to save it as a scripted
   flow (P3-T2) so it's fast and reliable next time.
**Tests:** `ScreenAgentStepTest` (model step JSON → validated step; payment nodes refused; sensitive-app guard).
**Verify on phone:** one unfamiliar task on a safe app, with confirmations.

---

## P5-T6 — Personal memory (inspectable, deletable)
**Goal:** PAA learns the owner's habits (who matters, usual orders, how they word tasks) without retraining, and the
owner can see and delete everything (`MODEL_PLAN.md` 5.2).
**Depends on:** existing `MemoryFactEntity` + DAO.
**Steps:** a `core/brain/PersonalMemory.kt` that stores verdicts/corrections/preferences as rows, retrieves the most
similar few for the current message (cheap embedding or keyword match), and feeds them into the compact prompt
(`learnedExamples`, already supported). UI "What PAA remembers" with per-item delete and a "forget all" button that
really deletes.
**Tests:** retrieval returns the most relevant items; delete removes them.

---

## P5-T7 — Shadow testing + on-phone benchmark
**Goal:** upgrade models safely and see real performance.
**Steps**
1. Shadow mode: a new model runs silently beside the current one for a week; PAA records agreement/disagreement
   **counts** (no message text) in the 🧠 card. Owner sees "new model agrees 96%" and approves the switch.
2. Benchmark: per-message time, peak RAM, battery-per-100-messages in the 🧠 card (extends the existing S0 stats).
**Tests:** shadow-compare bookkeeping (pure).

---

## P5-T8 — Efficiency passes (as measurements justify)
From `MODEL_PLAN.md` Phase 3–4, do the ones the phone numbers call for, each gated on eval staying 15/15:
one-word "no task" answer · shorter answer format · pre-filled answer start · stop-when-complete · vocabulary
trimming (270M) · 4-bit 1B · cascade (gate→270M→1B). Each is a `train/` change + an app parser tweak; retrain on the
DGX; re-measure on the phone.
**Exit Phase 5:** the shipped model passes eval 15/15, runs without hanging the phone, and the gate + intent model
cut most messages from ever waking the big model.
