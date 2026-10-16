# Phase 3 — Ordering and travel

Needs Phase 1. Order: T1 → T7. **Money rule throughout:** PAA fills carts and reaches the payment step, then hands
off — the owner confirms with fingerprint (Guard, MONEY tier) and types the UPI PIN. PAA never types a PIN/CVV/OTP,
never drives a bank/UPI app, and places one order at a time on the owner's command (never bulk, never unattended).

---

## P3-T1 — Payment hand-off (shared by every order flow)
**Goal:** one safe, reusable "now you pay" step.
**Depends on:** P1-T3 (biometric), P1-T4 (executor).
**Steps**
1. `core/money/PaymentHandoff.kt` — `suspend fun handoff(ctx, orderSummary: String, amountPaise: Long, upiAppPkg: String?)`:
   - the `Action.PlaceOrder` already passed Guard (MONEY → biometric confirm with the exact amount in the summary);
   - show a full-screen `ConfirmActivity`-style sheet: order summary + amount + **"You'll type your UPI PIN
     yourself."**;
   - on confirm, record audit, then either let the merchant app continue to its own UPI step, or open the chosen
     UPI app (`getLaunchIntentForPackage`) for the owner — **PAA stops here**; it does not touch the PIN screen.
   - after the owner returns, read the merchant's confirmation notification / order screen to log success.
2. The owner must still turn PAA's screen reading off for the UPI step if their setup needs it (P1-T6) — remind once.
**Tests:** `PaymentHandoffTest` — amount/summary formatting; that handoff is only reachable for a confirmed MONEY action.

---

## P3-T2 — Screen-flow engine (scripted app automation)
**Goal:** a safe, data-driven way to drive an app's UI (tap, type, scroll, wait-for) without hardcoding each app in
Kotlin, so shopping flows are maintainable JSON the owner/agent can fix when an app changes.
**Depends on:** P1-T4, P1-T11 (node hardening).
**Read first:** `services/ChatTaskAccessibilityService.kt` (how nodes are found/clicked today).
**Steps**
1. `assets/flows/<app>.json` — a flow = ordered steps:
   ```json
   {"app":"com.example","version":"1","steps":[
     {"do":"launch"},
     {"do":"tap","find":{"idEndsWith":"search_button"}},
     {"do":"type","find":{"idEndsWith":"search_src_text"},"text":"$query"},
     {"do":"waitFor","find":{"textContains":"$item"},"timeoutMs":8000},
     {"do":"tap","find":{"textContains":"$item"}},
     {"do":"tap","find":{"textContains":"ADD"}},
     {"do":"stopBeforePayment","find":{"textContains":"Proceed to Pay"}}
   ]}
   ```
2. `core/flows/FlowEngine.kt` — interprets steps against the accessibility tree: find by `idEndsWith` /
   `textContains` / `descContains`; `tap`, `type` (set text via `ACTION_SET_TEXT`), `scroll`, `waitFor`, `back`.
   **Hard stops in code:** the engine refuses to tap any node whose text matches a payment/PIN pattern
   (`pay`, `pin`, `cvv`, `proceed to pay`, `place order` beyond the summary) — it can only *reach* `stopBeforePayment`
   and then call `PaymentHandoff`. `stopBeforePayment` is mandatory; a flow without it is rejected at load.
3. Flows run only for `Action.PlaceOrder`/`TravelSearch` after Guard; each step is logged (type only, no text).
4. Safety: the engine only operates on the flow's declared package (checked against the foreground root), stops on
   any unexpected screen (asks the owner), and never runs unattended (screen must be on, owner present).
**Tests:** `FlowEngineTest` with a fake tree: finds nodes, respects `stopBeforePayment`, refuses payment-labelled
taps, rejects a flow with no stop.
**Verify on phone:** a trivial flow on a safe app (e.g. open Settings, navigate) before any shopping app.

---

## P3-T3 — Swiggy / Zomato via official MCP (preferred ordering route)
**Goal:** order food without screen automation, using the official agent servers (research: Swiggy MCP Jan 2026,
Zomato MCP Sep 2025; Swiggy allows personal use "connect your AI client, no access required").
**Depends on:** P3-T1.
**Important:** verify the current terms yourself at build time (Test 13). If personal use is **not** allowed, skip
this task and use the accessibility flow (T4) instead — do not work around their access rules.
**Steps**
1. `core/mcp/McpClient.kt` — minimal MCP over HTTPS (JSON-RPC): connect, list tools, call tool. OAuth via the
   device browser (Custom Tab) with a redirect to an app link PAA registers; store the token in Keystore.
2. `core/skills/OrderSkill.kt` — `Skill<Action.PlaceOrder>` with a `provider` field: `MCP(swiggy|zomato)` → search,
   build cart via MCP tools, read back the total → `Action.PlaceOrder(amount)` already confirmed → MCP checkout
   that yields a UPI step the owner completes (QR/UPI intent). Never store a wallet balance large enough to matter;
   if the provider offers a "pay from wallet without per-order approval", **disable it** — PAA must force its own
   confirm each time.
3. Parsing a food order from speech ("order 2 masala dosa from the usual place") → the intent model (P5) or a
   simple parser → `Action.PlaceOrder(provider, cartSummary, amount)` after the cart is priced.
**Tests:** `OrderParseTest` (speech → structured cart); MCP client request building (mock server).
**Verify on phone (Test 13):** build a cart via the client, reach the price, stop before payment.

---

## P3-T4 — Shopping flows via screen automation (Blinkit, Zepto, Amazon, Flipkart, Myntra)
**Goal:** order on apps with no official agent route — one flow file per app, on command, human pace.
**Depends on:** P3-T2.
**Terms warning (tell the owner once, record acknowledgement):** Amazon, Flipkart and others forbid automated
access in their terms; this is for the owner's personal use at human pace and carries a small account-action risk
(system plan §9). PAA places single orders only, never bulk, never unattended.
**Steps**
1. One `assets/flows/<app>.json` per app, starting with **Blinkit** (simplest). Find real view ids on the phone
   with `uiautomator dump` (print only ids). Each flow ends in `stopBeforePayment` → `PaymentHandoff`.
2. `OrderSkill` provider `FLOW(app)` → `FlowEngine.run(flow, vars)`.
3. Build and verify one app end-to-end before starting the next. If an app's screens change, the fix is editing its
   JSON, not code.
**Verify on phone:** one real low-value order per app, each stopping at payment for the owner's PIN. Record which
apps work and which ids drift.

---

## P3-T5 — Travel compare (buses, trains, flights)
**Goal:** "cheapest train/bus/flight Chennai→Hyderabad this Friday, I prefer morning and AC" → PAA reads several
sources and recommends. **Read-only** (IRCTC automation is banned; booking taps stay with the owner).
**Depends on:** P3-T2 (for reading result screens) or the web route.
**Steps**
1. `core/skills/TravelSkill.kt` — `Action.TravelSearch(from, to, dateMillis, modes, prefs)` (tier READ).
2. Two readers:
   - **Web/agent:** ask Gemini-with-search (or a browser agent) to fetch fares from redBus / ixigo / MakeMyTrip /
     ConfirmTkt for the route+date — this is a web query (no personal data), allowed.
   - **App read:** open each app's search (flow "search only", no booking) and read the result list via accessibility.
3. `core/skills/TravelRanker.kt` — pure: rank options by the owner's prefs (price weight, time-of-day, duration,
   class/AC, past choices). Output a short ranked list with why.
4. Present in the overlay / a card; "book this" → opens that app's page at the option (deep link if available) for
   the owner to book. For IRCTC, only open the search — the owner logs in and books.
**Tests:** `TravelRankerTest` (synthetic options, prefs change the ranking).
**Verify on phone:** one real route; the ranked list matches what the apps show.

---

## P3-T6 — "Order the usual" and reorder memory
**Goal:** remember frequent orders so "order my usual breakfast" works.
**Depends on:** T3/T4.
**Steps:** `data/models/SavedOrderEntity.kt` (label, provider, cart JSON) + DAO + migration; "save this as my
usual X" after an order; "order my usual X" → rebuild the cart → price → confirm → hand-off.
**Tests:** save/lookup; cart rebuild.

---

## P3-T7 — Order safety review
**Goal:** make the money path airtight.
**Steps**
1. `OrderSafetyTest`: no `Action.PlaceOrder` executes without a biometric-confirmed verdict; `FlowEngine` never taps
   a payment/PIN node; amount in the confirm equals the cart total read back (mismatch → abort).
2. A per-order cap setting (default ₹2,000) above which PAA always re-confirms and warns; owner can raise it.
3. Audit entry for every order with the final amount.
**Exit Phase 3:** one real order placed through each working route with only fingerprint+PIN from the owner; travel
compare returns a sensible ranked list; all order-safety tests pass.
