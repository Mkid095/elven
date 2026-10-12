# TickEdge - Completed Work & Verification Evidence

## This session (V1 real system + OAuth + multi-user)
- **Confirmed the live Deriv V1 API** with the real credentials (App ID `34EBfV9KENCCnCcjlA5Vf` +
  PAT): `GET /accounts` → HTTP 200 with demo `DOT9388705` ($11,157.83) and real `ROT92285673`
  ($0.00). Confirmed the per-account trading WS URL via `POST .../otp` → `data.url`, and the
  exact public-WS + `proposal` request/response shapes (V3-style `msg_type`/`req_id`).
- **New `com.example.data.deriv`**: `DerivAccount`, `DerivContractType/Params`, `DerivProposal`,
  `DerivBuyResult`, `DerivSettlement`, `DerivRestClient` (accounts + trading-WS URL, token provider),
  `DerivTradingClient` (proposal/buy/open_contract over the per-account WS), `DerivClient`
  (accounts + active kind + connected trading socket).
- **V1 market-data layer**: `DerivWebSocketService` now targets the V1 public WS; parses
  `ticks_history`/`tick` (V3-style), uses authoritative `pip_size` from the message, captures
  `subscription.id` for `forget` cleanup, reconnects with backoff. **Removed the synthetic fallback
  ticker** (no fabricated data; down feeds are STALE/OFFLINE).
- **Real execution**: `TradingEngine` (replaces the local-settling `SimulationEngine`): per step it
  prices a `DIGITDIFF`/`DIGITOVER`/`DIGITUNDER` order, places a real `buy` on the active account,
  reads settlement via `open_contract`, tracks streak + daily loss, caps Martingale, and refreshes
  the real balance. No `10000 + netResult` fake writes.
- **Removed all seeded/demo data**: MainViewModel seed (profile + 3 demo positions), entity default
  balances, fake account codes (`VRTC…`/`CR829…`), MainActivity P/L fallbacks (68/18),
  HistoryScreen fake buckets/chart, SettingsScreen V3 endpoint card + "non-executing" wording.
- **OAuth + multi-user** (`com.example.auth`): `Pkce` (verifier/challenge/state), `AuthState`
  machine, `SecureTokenStore` (Android Keystore + AES-GCM), `DerivOAuthClient` (auth URL, callback
  validation, code→token exchange, refresh, logout; dev-PAT fallback when unconfigured),
  `DerivOAuthRedirectActivity` + manifest intent filter (`elven://oauth/callback`). `MainViewModel`
  exposes `authState`, `launchDerivAuth`, `handleOAuthRedirect`, `derivLogout`, `refreshAuthState`;
  the login screen (`AuthScreen`) is now a real "Continue with Deriv" with state-driven UI.
- **Safety UI**: Trade screen shows the real account id + "DEMO / LIVE ARMED / LIVE OFF" status, an
  "Allow real-money orders" switch (live only), and a per-order confirmation dialog.
- **InstantDB** (`com.example.data.instant.InstantDbClient`): query/transact client + config
  placeholders for cross-device, user-scoped persistence.
- Room schema bumped to **v4** (real `accountId`/`contractId`/`isReal` + safety settings fields).
- Build: `:app:compileDebugKotlin` → **BUILD SUCCESSFUL** (Gradle 9.5.0, AGP 9.3.3, JDK 17).

## Not yet verified / blocked
- OAuth end-to-end (needs a registered redirect URI + client id + scopes in the Deriv dashboard).
- InstantDB sync (needs the FIDScript app id + user token + pushed schema).
- On-device run: V1 public feed streaming + a real demo order end-to-end.
- The PAT is embedded in the APK (dev fallback) — acceptable for a personal app, not for a public
  release (see the backend/OAuth note in the docs).
