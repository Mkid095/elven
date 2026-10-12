# TickEdge - Product Vision and Architecture

## Product Vision
TickEdge (styled **ELVEN**) is a mobile-first fintech application that visualizes authoritative
last-digit statistics from **live Deriv synthetic-index tick data** and runs a real, automated
trading strategy (Digit Differs base + Digits Over/Under Martingale recovery) on the user's
authorized Deriv accounts. It monitors five volatility index feeds (`R_10`, `R_25`, `R_50`,
`R_75`, `R_100`), computes rolling digit distributions over configurable windows (1–1,000 ticks),
and places **real** orders on the selected account (demo by default; live only when armed).

## Design Identity
- Primary background: near-black (`#0D0E12`); surfaces: charcoal/dark graphite.
- Accent: vivid yellow (`#FFD200`); positive = emerald green; negative/warn = crimson red.

## Key Architectural Modules
1. **Market-Data Layer** (`DerivWebSocketService`): V1 options **public** WebSocket
   (`wss://api.derivws.com/trading/v1/options/ws/public`). Subscribes to `ticks_history` + streams
   `tick` for the five markets. No synthetic/offline ticks: down feeds are marked STALE/OFFLINE.
   Reconnects with exponential backoff + jitter; releases subscriptions with `forget`.
2. **Statistical Engine** (`DigitAnalysisEngine`): decimal-safe last-digit extraction (pip-size
   aware, trailing zeros preserved), incremental 10-digit frequencies + trend, Over/Under stats,
   strict threshold comparisons, Martingale risk table.
3. **Execution Engine** (`TradingEngine`): real order lifecycle per step — `proposal` (price) →
   `buy` (on the active account's trading WebSocket) → `open_contract` settlement. Martingale
   doubling is capped; take-profit / stop-loss / daily-loss stop the loop. **Safety:** demo is
   the default account; live execution requires an explicit "arm real trading" toggle and a
   per-order confirmation.
4. **Auth & Identity** (`com.example.auth`): Deriv OAuth2 Authorization Code + PKCE (public client).
   Per-user access tokens are stored encrypted (Android Keystore + AES-GCM). Explicit `AuthState`
   lifecycle (Unauthenticated / Authenticating / Authenticated / SessionExpired / Reconnecting /
   Error) drives navigation. The embedded PAT is a **dev-only fallback** when OAuth is unconfigured.
5. **Accounts & Trading API** (`com.example.data.deriv`): `DerivRestClient` (accounts, per-account
   trading-WS URL via OTP), `DerivTradingClient` (proposal/buy/open_contract), `DerivClient`
   (active account + connected trading socket), all driven by a token provider (OAuth token,
   else dev PAT).
6. **Cross-Device Persistence** (`com.example.data.instant.InstantDbClient`): optional FIDScript
   InstantDB backend for user-scoped settings/history sync. Requires your InstantDB app + user
   token; the schema is pushed via the instant-self MCP.
7. **Local Persistence** (Room): user settings, analysis events, trade records (with account id,
   contract id, real flag). Destructive migration is used only because no production user data
   exists yet; a safe `Migration` is the required pattern going forward.

## Verified API facts (confirmed against the live Deriv V1 API this session)
- `GET https://api.derivws.com/trading/v1/options/accounts` (Bearer token + `Deriv-App-ID`) → `data[]`
  with `account_id, account_type(demo/real), currency, balance, status`.
- `POST .../accounts/{id}/otp` → `data.url` = per-account trading WebSocket
  (`wss://.../trading/v1/options/ws/{demo|real}?otp=...`).
- Public WS + proposal use **V3-style** request shapes: `ticks_history` as the symbol with sibling
  params; `proposal` fields as siblings (`contract_type`, `underlying_symbol`, `currency`, `amount`,
  `basis`, `barrier`, `duration`, `duration_unit`). Responses carry `msg_type` + `req_id`.
- Digit contracts: `DIGITDIFF`, `DIGITOVER`, `DIGITUNDER`; target digit = `barrier`;
  `duration_unit "t"` = ticks. `buy` takes the proposal `id` + `price` (ask).
