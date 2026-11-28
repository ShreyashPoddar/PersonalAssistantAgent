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
- [x] P1-T3 BiometricGate + ConfirmActivity
- [x] P1-T4 Executor + SkillRegistry + audit log UI
- [x] P1-T5 Route existing actions (message/alarm/call/auto-reply) through Executor
- [x] P1-T6 Owner-controlled screen-reading switch (tile + settings + voice)
- [~] P1-T7 Banking-separation guide (no automation) — guide + in-app dialog done; owner picks profile vs toggle
- [~] P1-T8 Instant app launcher — works on the phone ("open youtube" via PAA → YouTube in 3.8 s incl. cold popup + adb polling); owner stopwatch check of speech→app pending
- [~] P1-T9 SOS — code + tests done; owner sets contacts + runs a drill (sends a real SMS to own number)
- [x] P1-T10 Text sanitiser
- [~] P1-T11 Accessibility perception hardening — code + tests done; phone check (WhatsApp reading still works) pending
- [~] P1-T12 Kill switch + Lockdown — code + tests done; phone check pending
- [x] P1-T13 Developer-options reminder (mutable while developing)
- [ ] Phase 1 exit check

## Phase 2 — comms + money read-only (`P2_comms_money.md`)
- [~] P2-T1 Notification-reply messaging — WhatsAppSender already replies through the chat notification first; phone check pending
- [~] P2-T2 SMS send + inbox read — "sms X that …" + bank-SMS inbox; phone check pending
- [~] P2-T3 Email (share-intent) — "write an email to … saying …" opens Gmail filled in; phone check pending
- [~] P2-T4 Bank SMS + UPI notification parser (+ DB v7) — code + synthetic tests; phone Test 14 pending
- [~] P2-T5 Expense categoriser (in DB v7) — code + tests; phone check pending
- [ ] P2-T6 Statement PDF import (optional)
- [~] P2-T7 IOU ledger (in DB v7) — code + tests; phone check pending
- [~] P2-T8 Ask-anything about money (local) — code + tests; phone check pending
- [x] P2-T9 Privacy audit (MoneyPrivacyTest: no money in cloud code or logs; money answers kept out of cloud history)
- [ ] Phase 2 exit check

## Phase 3 — orders + travel (`P3_orders_travel.md`)
- [~] P3-T1 Payment hand-off — PaymentHandoff + PaymentSkill (opens a known UPI app or leaves the merchant on its payment step; PIN note on the confirm sheet; 8 tests). OrderConfirmationTracker reads the merchant/UPI app's next notification (30 min, amount within ₹1) and audits paid/failed/pending from the order's own fields (6 tests). Phone check pending
- [ ] P3-T2 Screen-flow engine
- [ ] P3-T3 Swiggy/Zomato via MCP (if terms allow — Test 13)
- [ ] P3-T4 Shopping flows (Blinkit→Zepto→Amazon→Flipkart→Myntra)
- [~] P3-T5 Travel compare — voice "bus/train/flight from X to Y on <day>" → web fares (route+date only) or search page; booking by owner; phone check pending
- [ ] P3-T6 Reorder memory
- [~] P3-T7 Order safety review — per-order cap (default ₹2,000, AgentSettings.orderCapPaise; 0 falls back to the default): above it the confirm sheet warns and the first Confirm only acknowledges, then "Yes, pay" → fingerprint (2 tests). OrderSafetyTest + cart read-back pending (needs T2 flow engine)
- [ ] Phase 3 exit check

## Phase 4 — calls + hub (`P4_calls_hub.md`)
- [~] P4-T1 Hub skeleton — hub/server.py (HTTPS, pinned self-signed cert, token; 4 tests incl. HTTPS round trip) + HubClient/HubCard on the phone (tests); owner pairs (Windows firewall prompt)
- [ ] P4-T2 Bluetooth hands-free bridge (Test 12)
- [ ] P4-T3 AI voice agent on the hub
- [ ] P4-T4 Live call understanding
- [ ] P4-T5 Cloud number (owner opt-in)
- [ ] P4-T6 Call safety + settings
- [ ] Phase 4 exit check

## Phase 5 — on-device intelligence (`P5_intelligence.md`)
- [~] P5-T1 Ship task model to phone — v7b on the phone (private set 46/57, phone eval 14/15 clean, 1.07 GB); v8/v9 training
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
