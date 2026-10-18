# Build status — tick each task when done (done = built + tests pass + verified where required + committed)

Do tasks top to bottom, or in parallel where `PARALLELISM.md` allows. `[ ]` = not started, `[~] <lane>` = claimed /
in progress, `[x]` = done. Update this file as part of each
task's commit. "Owner" tasks need the phone owner; "Agent" tasks the AI model does.

## Phase 0 — setup and decision tests (`P0_setup_and_tests.md`)
- [ ] P0-T1 Connect to the phone (owner gives wireless-debugging port)
- [ ] P0-T2 Install PAA + grant permissions (owner)
- [ ] P0-T3 StrongBox check (agent)
- [ ] P0-T4 Play Integrity verdicts (owner)
- [ ] P0-T5 Bank apps: accessibility / dev-options / Shizuku matrix (owner)
- [ ] P0-T6 Ask Axis in writing (owner)
- [ ] P0-T7 Bank apps in a separate profile (owner, if P0-T5b failed)
- [ ] P0-T8 Shell call-audio recording test (optional)
- [ ] Fill `results/phase0.md` and write the decisions

## Phase 1 — foundation (`P1_foundation.md`)
- [x] P1-T1 Action framework types
- [x] P1-T2 AgentSettings + ContactRule + Guard (+ DB v6)
- [~] P1-T3 BiometricGate + ConfirmActivity — code + tests done; phone fingerprint check pending (owner)
- [ ] P1-T4 Executor + SkillRegistry + audit log UI
- [ ] P1-T5 Route existing actions (message/alarm/call/auto-reply) through Executor
- [ ] P1-T6 Owner-controlled screen-reading switch (tile + settings + voice)
- [ ] P1-T7 Banking-separation guide (no automation)
- [ ] P1-T8 Instant app launcher
- [ ] P1-T9 SOS
- [ ] P1-T10 Text sanitiser
- [ ] P1-T11 Accessibility perception hardening
- [ ] P1-T12 Kill switch + Lockdown
- [ ] P1-T13 Developer-options reminder
- [ ] Phase 1 exit check

## Phase 2 — comms + money read-only (`P2_comms_money.md`)
- [ ] P2-T1 Notification-reply messaging
- [ ] P2-T2 SMS send + inbox read
- [ ] P2-T3 Email (share-intent)
- [ ] P2-T4 Bank SMS + UPI notification parser (+ DB v7)
- [ ] P2-T5 Expense categoriser (+ DB v8)
- [ ] P2-T6 Statement PDF import (optional)
- [ ] P2-T7 IOU ledger (+ DB v9)
- [ ] P2-T8 Ask-anything about money (local)
- [ ] P2-T9 Privacy audit
- [ ] Phase 2 exit check

## Phase 3 — orders + travel (`P3_orders_travel.md`)
- [ ] P3-T1 Payment hand-off
- [ ] P3-T2 Screen-flow engine
- [ ] P3-T3 Swiggy/Zomato via MCP (if terms allow — Test 13)
- [ ] P3-T4 Shopping flows (Blinkit→Zepto→Amazon→Flipkart→Myntra)
- [ ] P3-T5 Travel compare
- [ ] P3-T6 Reorder memory
- [ ] P3-T7 Order safety review
- [ ] Phase 3 exit check

## Phase 4 — calls + hub (`P4_calls_hub.md`)
- [ ] P4-T1 Hub skeleton
- [ ] P4-T2 Bluetooth hands-free bridge (Test 12)
- [ ] P4-T3 AI voice agent on the hub
- [ ] P4-T4 Live call understanding
- [ ] P4-T5 Cloud number (owner opt-in)
- [ ] P4-T6 Call safety + settings
- [ ] Phase 4 exit check

## Phase 5 — on-device intelligence (`P5_intelligence.md`)
- [ ] P5-T1 Ship task model to phone
- [ ] P5-T2 Learned task gate
- [ ] P5-T3 Intent + slots model
- [ ] P5-T4 Expense classifier model (optional)
- [ ] P5-T5 Generic screen agent
- [ ] P5-T6 Personal memory
- [ ] P5-T7 Shadow testing + benchmark
- [ ] P5-T8 Efficiency passes
- [ ] Phase 5 exit check

## Separate, already in motion (not part of the phase order)
- [~] On-device task model training on the DGX (`docs/MODEL_PLAN.md`) — data v1 done, training queued
