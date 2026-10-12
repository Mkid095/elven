# TickEdge - Implementation Progress

## Status
- [x] Repository assessment + metadata
- [x] Design system & core domain engine (DigitAnalysisEngine)
- [x] Room persistence (v4, real trade fields)
- [x] **V1 market-data ingestion** (public WS, no fabricated ticks)
- [x] **Real execution engine** (proposal→buy→settlement, Martingale + risk caps)
- [x] **All seeded/demo data removed** (synthetic ticker, seed balances, fake account codes,
      fake P/L + history)
- [x] **Deriv OAuth (PKCE)** app-side foundation + secure token store + redirect + auth states
- [x] **Multi-user identity** (DerivUser stable key; accounts via token; PAT = dev fallback)
- [x] UI wired to real data + real orders (Home, Trade + confirm dialog + account/arming,
      Settings, History, Login)
- [~] InstantDB cross-device sync: **client written + config placeholders**; schema + app +
      per-user token still required from the user (instant-self MCP)
- [ ] Per-user Room scoping (userId) + safe non-destructive migration (current destructive is
      acceptable only because no user data exists yet)
- [ ] Unit tests for OAuth PKCE + engine (mocked provider)
- [ ] Verify on device: V1 public feed, safe demo order end-to-end
- [ ] Verify OAuth end-to-end: **blocked on registering a redirect URI + scopes** for the app

## Blocked on user-supplied configuration
1. **Deriv OAuth**: the app dashboard shows Redirect URLs + Scopes as empty ("–"). Register the
   redirect (e.g. `elven://oauth/callback`) + client id + scopes so the flow can complete.
2. **InstantDB (FIDScript)**: create the backend app (instant-self MCP) and set
   `INSTANT_APP_ID` + a user auth token; push the user-scoped schema.
