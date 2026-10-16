# Phase 4 — Calls and the home hub

Needs Phase 1. This phase adds the **home hub** (a device the owner owns — laptop first) so an AI can speak inside a
normal SIM call on the owner's own number, which no on-phone app can legitimately do. When away from the hub, a
cloud number handles AI calls. Everything an AI says on a call opens with a spoken disclosure that it is an assistant.

**Disclosure + consent:** every AI-handled call starts with "Hi, this is <owner>'s assistant calling on their
behalf." Recording/transcribing a call is treated as personal data (DPDP); keep two-party awareness. Don't build
anything that hides that an assistant is on the line.

Order: T1 → T6.

---

## P4-T1 — Hub skeleton (local, owned device)
**Goal:** a small program on the owner's laptop that the phone can talk to over the local network, encrypted.
**Depends on:** —
**Steps**
1. `hub/` (Python) — `hub/server.py`: a localhost/LAN service (FastAPI or aiohttp) with TLS (self-signed cert
   pinned by the phone) and a shared token (generated once, shown as a QR the phone scans). Endpoints are added by
   later tasks; start with `/health` and `/pair`.
2. `core/hub/HubClient.kt` on the phone — pair (scan QR → store host+token+cert pin in Keystore), `health()`.
   `core/hub/HubSettings` — host, token, "hub enabled".
3. Discovery: owner types the laptop's LAN IP, or NSD/mDNS (`_paahub._tcp`). Keep it simple: manual IP first.
4. **Privacy:** the hub is the owner's device; data to it stays on the owner's LAN. Nothing from the hub goes to a
   third party unless a later task explicitly asks a cloud model, and only with the owner's setting on.
**Tests:** `HubClientTest` (pairing payload parse, token/cert pin handling) with a fake server.
**Verify:** phone pairs with the laptop; `health()` green on the same Wi-Fi.

---

## P4-T2 — Bluetooth hands-free bridge (the core trick)
**Goal:** the hub registers to the phone as a Bluetooth **Hands-Free (HFP)** device (like a car kit), so during a
normal SIM call the call's audio flows to/from the hub, where the AI listens and speaks. (Research: Sipfront /
ParrotVoice do exactly this; limits: 8/16 kHz audio, some latency, hub must be near the phone.)
**Depends on:** P4-T1.
**Steps**
1. On the hub (laptop): set up an HFP **audio gateway/handsfree** stack. On Linux (recommended hub = Raspberry Pi
   or a Linux box) use **oFono + PipeWire/PulseAudio** as in the Sipfront write-up; on Windows this is harder —
   document that a Linux hub (Pi 5) is the supported path and the laptop is for development.
2. `hub/call_bridge.py` — pair with the phone as HFP, capture downlink (far side) and inject uplink (AI voice);
   expose start/stop and audio streams to the AI layer (T3).
3. The **phone** does nothing special here except be paired over Bluetooth and route call audio to the hands-free
   device (standard Android behaviour when an HFP device is connected). PAA on the phone only tells the hub "a call
   with X started/ended" (via `HubClient`), using the existing call detection.
**Verify (Test 12):** pair the hub, place a call to a second phone, play a test tone from the hub into the call and
record the far side — both directions audible, latency noted.
**If the iQOO doesn't expose full call audio over HFP:** record the result; fall back to the cloud number (T5) for
AI calls. This is a hardware unknown (research couldn't confirm MediaTek/vivo), so treat T2 as "try, measure, decide".

---

## P4-T3 — AI voice agent on the hub
**Goal:** the hub holds a short spoken conversation for the owner (e.g. "call the restaurant and ask if they're open
till 11 and book a table for 4 at 9").
**Depends on:** P4-T2.
**Steps**
1. `hub/voice_agent.py` — pipeline: speech-to-text (local: faster-whisper) → a small LLM (local via Ollama, or a
   cloud model if the owner allows) given the **task brief from the phone** → text-to-speech (local: Piper) → into
   the call. Keeps a transcript.
2. The **task brief** is created on the phone by the owner ("ask if open till 11, book table for 4 at 9pm") — a
   typed, bounded goal, not free rein. The agent follows the brief, confirms outcomes, and ends the call.
3. Outcomes (table booked at 9) come back to the phone → PAA creates the reminder/task (PRIVATE_REVERSIBLE).
4. Keep all audio/transcript on the hub by default; show the transcript to the owner on the phone.
**Verify:** hub calls the owner's second phone, follows a scripted brief, returns the outcome.

---

## P4-T4 — Live call understanding (both sides), hub path
**Goal:** transcribe the owner's own calls (both sides) so "Oyee PA" after a call can schedule what was agreed —
the thing blocked on-device today.
**Depends on:** P4-T2.
**Steps:** when a normal call is active and the hub is connected, the hub transcribes both directions; on "Oyee PA"
(or after the call) the transcript (text only) goes to the phone's existing call-transcript → task pipeline
(`ChatApp.CALL`). No audio stored unless the owner asks.
**Verify:** a test call; after it, the agreed task appears.

---

## P4-T5 — Cloud number for AI calls away from home
**Goal:** AI calls when the phone isn't near the hub.
**Depends on:** P4-T3.
**Owner decision + cost.** Only build if the owner opts in.
**Steps**
1. Trial Exotel or Plivo (research prices ~₹0.60/min + ~₹250/mo). `hub/cloud_telephony.py` bridges the provider's
   realtime audio to the same voice agent (T3).
2. The callee sees the **business number**, not the owner's (can't spoof the owner's caller ID — stated to the owner).
3. Follow TRAI rules for automated/AI-voice calls (A2P, 18 Sep 2026); disclosure line mandatory; personal,
   low-volume use only. If unsure whether a use counts as A2P, ask a telecom lawyer (research flagged this).
**Verify (Test 8):** call the owner's second phone from the cloud number; measure delay; check spam flagging.

---

## P4-T6 — Call feature safety + settings
**Goal:** keep calls controlled and consented.
**Steps**
1. "Calls" settings: hub on/off, cloud number on/off, "always disclose AI" (locked on), per-contact "allowed for AI
   calls" (default off — AI calls only to contacts the owner marks, or numbers the owner dictates).
2. `Action.AiCall(target, brief)` tier OUTWARD → Guard confirm; MONEY if it could spend (e.g. booking that charges) →
   biometric.
3. Audit every AI call (who, brief, outcome, transcript link).
**Exit Phase 4:** Test 12 result recorded (hub bridge works or not); if it works, an AI call to a friend follows a
brief and returns an outcome; if not, the cloud-number path does, with disclosure.
