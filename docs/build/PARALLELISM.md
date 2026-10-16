# Building PAA as fast as possible — run things in parallel

Three independent kinds of parallelism. Use all three at once.

1. **Compute** — train several models at the same time on the DGX (and the laptop GPU), sharing 1–2 cards with the
   LocalAI project.
2. **Build** — several AI agents build independent tasks at the same time, each in its own git worktree.
3. **Runtime** — the finished app does its on-phone work concurrently (already covered in `docs/MODEL_PLAN.md` Phase 2;
   summarised at the end here).

---

## 1. Compute parallelism (training the models)

**What can run at the same time** (all produce `train/` artifacts; see `docs/MODEL_PLAN.md`):

```
data-v1 (teacher, 1 GPU) ─┬─► train 1B  ─► eval 1B  ─► export 1B (CPU)
                          ├─► train 270M ─► eval 270M ─► export 270M (CPU)
                          ├─► train gate   (CPU/1 GPU, minutes)        ← P5-T2
                          ├─► train intent adapter (1 GPU)             ← P5-T3
                          └─► train expense classifier (CPU/1 GPU)     ← P5-T4
```
Once `data-v1` exists, **every training job below it is independent** — they only share the data, which is read-only.
So with 2 GPUs: train 1B on one card and 270M on the other **in parallel**; the small jobs (gate, classifier) fit on
the laptop GPU or a CPU box, or slot onto a DGX card between the big ones. Exports and evals are CPU/short and never
block a training card.

**How many cards we take — ONE budget for both projects (LocalAI + PAA together):**
| When (IST) | Total cards for both projects | Always idle for others | Guaranteed minimum |
|---|---|---|---|
| Day 07:00–19:00 | 2 | — | 2 |
| Night 19:00–07:00 | up to 4 | 1 | 2 |
- The rules live in **one place**: LocalAI's `queue.yaml` `limits:` (branch `dgx-queue` of `ShreyashPoddar/AIModel`),
  editable on GitHub from anywhere; both pollers apply the change within ~3 min. PAA reads them from the file LocalAI's
  poller writes (`~/localai/queue.parsed.limits`); if that's missing, the same rules are built in (`paa_policy.py`).
- **PAA is the yielding tenant.** `scripts/dgx/paa_policy.py` counts every card this account uses (both projects,
  incl. autoeval), launches PAA jobs only into what's left, and when the total goes over (e.g. at 07:00, or when
  LocalAI starts runs) it **pauses PAA's own jobs** — finished-and-holding ones first, then the newest. A paused job
  saves a checkpoint and exits; it **resumes automatically** (before any new job) when the budget allows. PAA never
  touches LocalAI's runs.
- `PAA_MAX_GPU_JOBS` (default 2) is only an extra PAA-side cap on top of the shared budget.
- **Never interrupt a running job of LocalAI's.** PAA only ever uses cards the shared budget leaves free.

**Launch it in parallel (from the laptop, any network):**
```bash
python tools/gpu.py pipeline v1 --parallel     # queues 1B and 270M as two independent card-holding chains
```
- `--parallel` (new): `data-v1` → then `chain-1b-v1` and `chain-270m-v1` run on **two cards at once**, each
  trains+evals+exports+holds its own card. Falls back to one card automatically if only one is free
  (`PAA_MAX_GPU_JOBS`/`PAA_LEAVE_FREE` gate each launch).
- Without `--parallel`: one card does 1B then 270M in sequence (the original chain) — use this when only one card
  is spare.
- The small jobs (gate, intent, classifier) are queued the same way with `tools/gpu.py add train ...` and run on any
  spare card or the laptop; they don't wait for the big ones.

**Laptop GPU (RTX 5050) as a second lane:** the gate (P5-T2), the expense classifier (P5-T4), data smoke-tests, and
eval runs are small enough to run on the laptop while the DGX trains the big models — pure extra throughput, nothing
leaves your machines. Run them directly (`python train/train_gate.py ...`) when the laptop is on.

---

## 2. Build parallelism (many agents building at once)

The task cards are written so independent tasks can be built **simultaneously by different agents**. To actually run
several agents, the owner starts multiple sessions (or asks for subagents — the owner decides, per their token
rule). Each agent takes one unclaimed task, works in its own worktree, and merges when green.

### Dependency graph — what can start in parallel

**Phase 1** (critical path is thin; most tasks fan out after T4):
```
T1 ─► T2 ─► T3 ─► T4 ─┬─► T5   (route existing actions)
                      ├─► T6 ─► T7   (screen-reading switch → banking guide)
                      ├─► T8   (app launcher)
                      ├─► T9   (SOS)
                      └─► T12  (kill/Lockdown)
T10 (sanitiser) ─► T11 (perception)      ← start immediately, parallel to T1–T4
T13 (dev-options reminder)               ← start anytime, no deps
```
So right after T4, **T5, T6, T8, T9, T12 can be five parallel agents**; T10/T11/T13 run alongside T1–T4 from the start.

**Across phases:** once Phase 1's core (T1–T4) is in, Phase 2 and Phase 5 model work can begin in parallel with the
rest of Phase 1. Phase 3 needs P1-T3/T4 and P3-T2. Phase 4 (hub) is **fully independent of the app code** — the
`hub/` Python can be built in parallel with everything from day one.

Parallel lanes that rarely touch each other:
- **Lane A (core/safety):** P1-T1→T4, then T5, T12.
- **Lane B (perception):** P1-T10, T11; P2-T4/T5 parsers (pure Kotlin).
- **Lane C (skills/UI):** P1-T6/T7/T8/T9/T13; P2-T1/T2/T3.
- **Lane D (money):** P2-T4→T8 (shares the DB — see serialization).
- **Lane E (orders/flows):** P3-T1/T2, then per-app flows (each flow file is independent → many agents).
- **Lane F (hub):** all of P4 (`hub/`), independent of app code.
- **Lane G (models):** `train/` + P5, on the DGX/laptop (compute parallelism above).

### Rules so parallel work doesn't collide
1. **Claim a task** by setting it `[~]` in `STATUS.md` with your lane name, in a tiny commit to `main`, before you
   start. Don't pick a task whose deps aren't `[x]`.
2. **Work in a worktree + branch per task:** `build/<task-id>` (e.g. `build/p1-t9-sos`). Use the EnterWorktree tool
   or `git worktree add ../paa-<task-id> -b build/<task-id>`. Build and test there, so agents never fight over the
   same working tree or `build/` output.
3. **Merge small and often:** when the task is green, rebase on `main`, run the build once more, merge to `main`,
   push, set `[x]`. Short-lived branches = few conflicts.
4. **Shared files are reserved, not edited freely.** These change in almost every task, so edit them in a minimal,
   append-only way and expect to rebase:
   - `AndroidManifest.xml` — add your component in the obvious spot; never reorder others' entries.
   - `ui/main/MainScreen.kt` — each feature adds **one card**; keep each card self-contained so merges are clean, or
     split cards into separate `@Composable` files under `ui/main/cards/` and just add one line to the list.
   - `core/agent/SkillRegistry.kt` — one `when` arm per skill; append only.
   - `gradle/libs.versions.toml`, `app/build.gradle.kts` — add your dep, don't touch others'.
5. **The database is a single lane.** Room `version` numbers must be sequential and all live in one
   `AppDatabase.kt`, so two agents bumping the version collide badly. **Reserved version registry — do not reuse:**
   | version | task |
   |---|---|
   | 6 | P1-T2 (contact_rules, audit_log) |
   | 7 | P2-T4 (transactions) |
   | 8 | P2-T5 (merchant_rules) |
   | 9 | P2-T7 (ledger) |
   | 10 | P3-T6 (saved_orders) |
   | 11 | P4-T6 (ai_call_log) |
   Any new DB-touching task takes the **next free number here** and updates this table in the same commit. Do all DB
   migrations through `main` (not parallel long branches) to keep the sequence clean; if two land close together,
   the second rebases and bumps to the next number.
6. **Tests are isolated** (pure Kotlin, own file per task) → they never conflict. Each agent's `./gradlew
   testDebugUnitTest` must be green before merge; the merged `main` is the source of truth.

### Suggested fastest ordering with ~3–4 build agents + the DGX
- **Hour 0:** Agent-1 starts the critical path T1→T4. Agent-2 starts T10→T11. Agent-3 starts the `hub/` skeleton
  (P4-T1/T2). DGX starts `pipeline v1 --parallel` (two cards: 1B + 270M). Laptop trains the gate.
- **After T4 merges:** Agent-1→T5, Agent-2→T6→T7, Agent-3→T8, Agent-4→T9; then T12/T13.
- **Then** Phase 2 money lane (one agent owns the DB sequence) runs parallel to Phase 3 flow engine + hub work.
- Models arrive from the DGX and plug in at P5-T1 whenever they're ready — not on the critical path.

---

## 3. Runtime parallelism (recap — detail in `docs/MODEL_PLAN.md` Phase 2)
On the phone, keep the UI smooth by doing agent work concurrently and off the main thread: the learned gate (<5 ms)
filters most messages before the model runs; inference on a low-priority background thread on the GPU; group/promo
chats batched until the screen is off or charging; one message processed once (dedupe across notification +
accessibility); urgent contacts first. The cascade (gate → 270M → 1B only when unsure) is itself a form of "use the
least compute that works". These are built in Phase 1–2 and Phase 5, not here.

---

## One-line summary for each knob
- `tools/gpu.py pipeline v1 --parallel` — two GPUs train 1B + 270M at once.
- `PAA_MAX_GPU_JOBS` (DGX `secrets.env`) — how many PAA GPU jobs at once (default 2).
- `PAA_LEAVE_FREE` (DGX `secrets.env`) — idle cards reserved for LocalAI (default 2).
- `STATUS.md` `[~]` + worktree per task — how build agents stay out of each other's way.
- DB version registry (above) — the one thing agents must coordinate.
