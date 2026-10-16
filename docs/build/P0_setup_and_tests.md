# Phase 0 — phone setup and the tests that decide the plan

Results of every test go into `docs/build/results/phase0.md` (create it from the template at the end). Write
*what happened*, never screenshots or text of bank screens.

---

## P0-T1 — Connect to the phone (agent + owner)
**Goal:** reliable adb access to the owner's iQOO Z7 Pro for installs and checks.

**Steps**
1. Ask the owner: "Open Settings → Developer options → Wireless debugging, and tell me the IP:port shown."
   (Phone IP has been `192.168.1.3`; the port changes every time.)
2. ```bash
   export PATH="$LOCALAPPDATA/Android/Sdk/platform-tools:$PATH" MSYS_NO_PATHCONV=1
   adb connect 192.168.1.3:<port>
   adb devices          # must list 192.168.1.3:<port>   device
   ```
3. Use `-s 192.168.1.3:<port>` on every adb command. If a command hangs >60 s, the connection dropped: ask for the
   new port.

**Done when:** `adb -s <serial> shell getprop ro.product.model` prints `I2301`.

---

## P0-T2 — Install PAA the safe way and grant its permissions (owner, agent guides)
**Why:** Android 15 blocks notification access, accessibility and SMS for sideloaded apps until "restricted
settings" are allowed. `adb install` also avoids Google's developer-verification flow.

**Steps (agent)**
1. Build, then `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.

**Steps (owner — send these exactly)**
1. Settings → Apps → PAA → ⋮ (top right) → **Allow restricted settings** → confirm with screen lock.
2. Settings → Apps → Special app access → **Notification access** → PAA → on.
3. Settings → Accessibility → Downloaded apps → **PAA** → on.
4. Settings → Apps → Special app access → **Usage access** → PAA → on (needed from P1-T6).
5. Open PAA once so it asks for microphone, contacts, notifications, location, calendar.

**Done when:** `adb -s <serial> shell dumpsys notification | grep -c com.paa.assistant` ≥ 1 and
`adb -s <serial> shell settings get secure enabled_accessibility_services` contains `com.paa.assistant`.

---

## P0-T3 — Test 9: StrongBox and keystore (agent, 2 min)
```bash
adb -s <serial> shell pm list features | grep -i -E "strongbox|keystore"
```
Record: `android.hardware.strongbox_keystore` present → yes/no. **Effect:** P1-T3 uses StrongBox when present,
otherwise the TEE (`setIsStrongBoxBacked(false)`).

---

## P0-T4 — Test 3: Play Integrity verdicts (owner, 5 min)
1. Owner installs **"Play Integrity API Checker"** (by Shuyan / `gr.nikolasspyr.integritycheck` or similar
   well-rated checker) from the Play Store.
2. Tap "Check". Record the three results: **basic**, **device**, **strong** (pass/fail) and the security patch date
   (Settings → About phone → Software).
3. Uninstall the checker afterwards (optional).

**Meaning:** device = pass is what banking apps normally need. If strong fails on this stock locked phone, write it
down; apps that demand strong may already fail today, independent of PAA.

---

## P0-T5 — Test 1 + Test 10: what makes bank apps object (owner, 30–40 min)
**Goal:** learn exactly which settings make each money app warn or refuse. Do it on a fresh boot.

**Apps:** Axis Mobile, BHIM, Google Pay, PhonePe, Paytm (only the ones the owner uses).

For **each** app and **each** row below: open the app, log in, open the payments / transactions screen, then note
`runs` / `warns: <exact words>` / `refuses: <exact words>` / `logs out`.

| # | Setup |
|---|---|
| a | Developer options **off**, PAA accessibility **off** (baseline) |
| b | Developer options off, PAA accessibility **on** |
| c | Developer options **on**, USB/wireless debugging off, PAA accessibility off |
| d | Developer options on, **wireless debugging on**, PAA accessibility off |
| e | Like d + Shizuku installed but not started (only if the owner wants to test Shizuku) |
| f | Like d + Shizuku running |

How to switch: Developer options = Settings → System → Developer options → top switch. PAA accessibility =
Settings → Accessibility → PAA.

**Decision rules (agent writes these into results):**
- If (b) = runs for every app → the owner can keep banking in the main profile; the P1-T6 manual tile is just
  convenience.
- If (b) fails for some app → that app needs banking in a **separate profile** (P0-T7), or the owner turns PAA's
  screen reading off (P1-T6 tile) before using it. We do **not** build anything to make the bank app stop
  detecting PAA.
- If (c)/(d) fail → Developer options must stay **off** in daily use; only switch on for adb sessions.
- If (e)/(f) fail → never keep Shizuku installed/running; use it only for one-off grants, then uninstall.

---

## P0-T6 — Test 16: ask Axis in writing (owner)
Message Axis support (in-app chat, email or branch). Text to send:
> "Does the Axis Mobile app block or warn when a third-party accessibility service is enabled, or when Developer
> options / USB debugging are on? Is there an allow-list for accessibility apps? Please reply in writing."

Paste their reply into `results/phase0.md`.

---

## P0-T7 — Test 15: bank apps in a separate profile (owner, only if P0-T5 (b) failed)
1. Settings → (vivo) **App clone / Users & accounts → Multiple users** or install "Shelter"/"Island" (work-profile
   apps from Play Store) — use the phone's own multiple-users feature if present; otherwise ask the agent.
2. Install the failing bank app inside the second profile/user. Do **not** enable PAA's accessibility there.
3. Keep PAA's accessibility **on** in the main profile. Open the bank app in the second profile.
4. Record runs / warns / refuses.

**If it runs:** the profile-split path works → note it; the agent adds a settings hint "Keep bank apps in profile X".

---

## P0-T8 — Test 4: can the shell record call audio? (agent + owner, optional, 15 min)
Only informative (P4 does not depend on it).
1. Agent downloads **scrcpy ≥ 3.2** for Windows from `https://github.com/Genymobile/scrcpy/releases`
   — **ask the owner before downloading** (state file name and size).
2. Owner enables USB debugging, connects USB cable, places a normal SIM call to their own second number/friend.
3. Agent runs, one at a time, 15 s each:
   ```bash
   scrcpy -s <serial> --no-video --no-playback --audio-source=voice-call-downlink --record=dl.opus --time-limit=15
   scrcpy -s <serial> --no-video --no-playback --audio-source=voice-call-uplink --record=ul.opus --time-limit=15
   ```
4. Owner listens to both files (agent does not open them). Record: far side audible yes/no; own voice yes/no.
5. Delete the recordings afterwards. Owner switches Developer options off again.

---

## Results template — create `docs/build/results/phase0.md`
```markdown
# Phase 0 results
Date:            Phone patch level:
T3 StrongBox: yes/no
T4 Play Integrity: basic __ device __ strong __
T5 bank apps (runs / warns "…" / refuses "…"):
| App | a | b | c | d | e | f |
|---|---|---|---|---|---|---|
| Axis Mobile | | | | | | |
| BHIM | | | | | | |
| Google Pay | | | | | | |
| PhonePe | | | | | | |
| Paytm | | | | | | |
T6 Axis reply:
T7 profile split:
T8 call audio: downlink __ uplink __
Decisions: (agent fills using the rules in P0-T5)
```
