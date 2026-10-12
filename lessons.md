# TickEdge - Lessons Learned & Architecture Patterns

1. **Room Schema Versioning & Integrity**: when adding/changing Entity fields, bump
   `@Database(version = …)` so `fallbackToDestructiveMigration()` can recreate tables; otherwise
   Room throws "cannot verify the data integrity."

2. **Precision-Safe Digit Extraction**: format with the market's authoritative `pipSize`
   (`String.format("%.${pipSize}f", quote)`) and read the last numeric char — never multiply/truncate
   a raw `Double`. Prefer the `pip_size` the API returns in the message over a hardcoded default.

3. **Dual-Mode / Real-Only Market Data**: a resilient WS layer used to fabricate offline ticks;
   that "demo data" is removed. When the feed is down, mark markets STALE/OFFLINE instead of
   inventing data.

4. **Deriv V1 options API is V3-shaped on the wire** (confirmed live):
   - `ticks_history` request: symbol is the VALUE of the `ticks_history` key with sibling params
     (`count`, `end`, `style`, `subscribe`) — NOT a nested object.
   - `proposal`/`buy` requests: fields are siblings of the op (`{proposal:1, contract_type, …}`),
     **not** nested under a `parameters` object.
   - Responses carry `msg_type` + echo `req_id`; use a `req_id` map to correlate.
   - Digit contracts: `DIGITDIFF`/`DIGITOVER`/`DIGITUNDER`; target digit = `barrier`;
     `duration_unit "t"` = ticks.
   - Per-account trading WS: `POST /accounts/{id}/otp` → `data.url` (short-lived; re-fetch as needed).

5. **OAuth (public client, PKCE)**: use the system browser (never a WebView) for the authorize
   step; validate `state`; exchange the code with the original `code_verifier`. Store tokens in
   Keystore-backed encrypted storage. Keep a dev PAT fallback so an unconfigured build still works,
   but never ship a PAT for a public app.

6. **`security-crypto` version churn**: the alpha versions changed the `EncryptedSharedPreferences`
   create() signature/constants. Sidestepped it by implementing the secret store directly with
   Android Keystore (AES-GCM) — no third-party secure-storage library.

7. **Build toolchain**: this project resolves AGP 9.3.3, which **requires Gradle 9.5.0** (not the
   9.3.1 in the original wrapper). Use the cached `gradle-9.5.0` dist + the Gradle-managed JDK 17
   in `~/.gradle/jdks`; the system `java` (1.8) is too old. A pipeline masks Gradle's exit code —
   check `build_check.log`, not the shell's `$?`.

8. **Confirm exact API shapes against the live API before coding** — the `llms.txt` guide names some
   endpoint files that 404; a quick script against the real endpoint (public ticks, a read-only
   `proposal`) de-risks the whole data + execution layer.
