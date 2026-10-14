# FIDScript bug report — runtime transact writes are never queryable

**Client:** TickEdge (native Android, Kotlin, `com.tickedge`)
**FIDScript app:** `elven` / `ce219821-50b6-4ffd-9e9f-d6ec44e6b7e9`
**API host:** `https://apiinstant.fidscript.com`
**Reported:** 2026-10-14
**Context:** raw wire-protocol client (no JS SDK available for Kotlin)

---

## TL;DR

`POST /runtime/:app_id/transact` returns **HTTP 200 with a real `tx-id` and `isn`**, but the
data never becomes visible to any query — not through `POST /runtime/framework/query`, not
through the ISN-pinned read, and not through the admin/PAT tool.

The WebSocket `transact` path behaves identically. **The HTTP path did not bypass the issue.**

---

## Confirmed working (so the surface is clear)

These all work today and need no action:

| Operation | Endpoint | Status |
|---|---|---|
| Guest sign-in | `POST /runtime/auth/sign_in_guest` | ✅ 200, returns `user.id` + `refresh_token` |
| OAuth id_token sign-in | `POST /runtime/oauth/id_token` | route exists (not yet exercised — no live Deriv token) |
| WS handshake | `wss://…/runtime/session` → `{op:"init","app-id":…,"refresh-token":…}` | ✅ `init-ok` |
| WS read | `{op:"add-query", q:{ns:{}}}` | ✅ `add-query-ok` + `join-rows` |
| WS transact validation | your `assert-coll!` fix | ✅ map-shaped steps now 400, not 500 |
| Tx-step vocabulary | `deep-merge-triple` / `add-triple` / `retract-triple` / `delete-entity` | ✅ all accepted, perms pass |
| HTTP transact | `POST /runtime/:app_id/transact` | ✅ 200, returns `tx-id` + `isn` |

`auth.id` semantics, `refresh_token` lifecycle, and nonce/PKCE handling are answered in the
new `/docs/runtime-api` and are consistent with what we observed — thank you.

---

## The blocking bug

### Minimal repro

```bash
API=https://apiinstant.fidscript.com
APP=ce219821-50b6-4ffd-9e9f-d6ec44e6b7e9

# 1. guest sign-in
RT=$(curl -s -X POST "$API/runtime/auth/sign_in_guest" \
      -H 'Content-Type: application/json' \
      -d "{\"app-id\":\"$APP\"}" | jq -r .user.refresh_token)
ME=$(curl -s -X POST "$API/runtime/auth/sign_in_guest" \
      -H 'Content-Type: application/json' \
      -d "{\"app-id\":\"$APP\"}" | jq -r .user.id)
EID=$(uuidgen)

# 2. write  (user_settings.userId = ad67e9e2-c4ae-46c2-bead-42043b7ab9c8
#            user_settings.market = 0926933c-7303-421e-8831-5669089d4169)
curl -s -X POST "$API/runtime/$APP/transact" \
  -H 'Content-Type: application/json' \
  -H "app-id: $APP" \
  -H "Authorization: Bearer $RT" \
  -d "{\"tx-steps\":[
        [\"deep-merge-triple\",\"$EID\",\"ad67e9e2-c4ae-46c2-bead-42043b7ab9c8\",\"$ME\"],
        [\"deep-merge-triple\",\"$EID\",\"0926933c-7303-421e-8831-5669089d4169\",\"R_25\"]]}"
# → 200 {"tx-id":"2206","isn":"2/0/12E84F00"}

# 3. read
curl -s -X POST "$API/runtime/framework/query" \
  -H 'Content-Type: application/json' \
  -H "app-id: $APP" \
  -H "Authorization: Bearer $RT" \
  -H 'Instant-ISN: 2/0/12E84F00' \
  -d '{"query":{"user_settings":{}}}'
# → 200 {"result":[{"data":{"datalog-result":{"join-rows":[[]]}},"child-nodes":[]}], ...}
#                                   ^^^^^^^^^^^^^^^ empty — the row we just wrote is not there
```

### Observed across many attempts

```
transact HTTP 200  {"tx-id":2206,"isn":"2/0/12E84F00"}

framework/query + Instant-ISN, polled:
  +0ms     rows=0  ours=false
  +500ms   rows=0  ours=false
  +1500ms  rows=0  ours=false
  +3000ms  rows=0  ours=false

admin/PAT query (instant-self MCP `query` tool):  user_settings: []
```

Same result on the WebSocket transact path (tx-ids 2144, 2148, 2149, 2156–2159, 2162, 2165,
2169, 2173, 2185, 2191, 2194, 2198, 2202, 2206).

### The sharpest evidence: it's the *write path*, not the read path

Data written through the **admin/PAT path** *is* immediately queryable through the WebSocket
`add-query`. Data written through the **runtime path** (HTTP or WS) is not queryable through
any path. Same namespace, same attributes, same app.

Observed earlier — admin write, then WS read:

```
add-query -> {"op":"add-query-ok","result":[{"data":{"datalog-result":{"join-rows":[[
  ["11111111-2222-3333-4444-555555555555","a89fd608-…","Differs",1791954361904],
  ["11111111-2222-3333-4444-555555555555","0926933c-…","R_25",1791954374189],
  …]]}}]}
```

### Ruled out

| Hypothesis | How tested | Result |
|---|---|---|
| WS session rs/store cache desync | Repeated over **HTTP**, which doesn't touch that cache | ❌ still empty |
| Propagation / index lag | Fresh sessions at 0/1/2.5/5s, and `Instant-ISN` pinned reads | ❌ always empty |
| Row perms filtering the read | `view: auth.id != null` | ❌ still empty |
| Session scoping | Admin/PAT query on the same data | ❌ still empty |
| Invalid / missing attr-id | `get-schema` confirms both attr ids exist; a bad attr returns an error, not a silent 200 | ❌ ruled out |
| Perms rejecting the write | `transact-ok` returned → perms passed | ❌ ruled out |

Since the read is pinned by ISN to the exact transaction that wrote the row and still returns
nothing, the write is either landing somewhere the query path doesn't consult, or being
discarded after the ISN is assigned.

---

## Two doc corrections in `/docs/runtime-api`

Both cost us a debug cycle; worth fixing since the page is the reference for non-JS clients.

**1. Guest sign-in example is missing `app-id`.** The page shows:

```bash
curl -X POST "$API_URL/runtime/auth/sign_in_guest" -H "Content-Type: application/json" -d '{}'
```

That returns:

```json
400 {"type":"param-missing","message":"Missing parameter: [\"body\" \"app-id\"]",
     "hint":{"in":["body","app-id"]}}
```

It needs `-d '{"app-id":"<app-id>"}'`.

**2. `framework/query` example omits the required `app-id`.** Without it you get:

```json
400 {"type":"param-missing","message":"Missing parameter: [\"headers\" \"app-id\"]",
     "hint":{"in":["headers","app-id"],"possible-ins":[["query-params","app_id"]]}}
```

Add `-H "app-id: $APP_ID"` or `?app_id=$APP_ID`.

---

## Correction to an earlier diagnosis

> *"the elven app has zero user-defined namespaces, so anything the developer was writing to a
> non-existent attr would have been rejected"*

`get-schema` returns **two** user namespaces with real attributes:

| Namespace | Attribute count | Notable attrs |
|---|---|---|
| `user_settings` | 18 | `userId`, `market`, `baseStake`, `contractType`, `targetDigit`, `recoveryDigit`, `recoveryDirection`, `stopLoss`, `takeProfit`, `maxDailyLoss`, `maxMartingaleSteps`, `realArmed`, `requirePerOrderConfirm`, `activeAccountKind`, `accountId`, `updatedAt`, `id`, … |
| `trade_history` | 13 | `userId`, `market`, `digit`, `stake`, `net`, `payout`, `outcome`, `currency`, `isReal`, `contractId`, `createdAt`, `note`, `id` |

Every attribute we write resolves against the schema and passes the attr-exists check. So the
"writing to a non-existent attr" hypothesis doesn't apply here — and note that a genuinely
invalid attr-id *does* produce an error rather than a silent success, which is what makes this
case anomalous.

---

## What we need to unblock

1. **Runtime transact writes must become queryable.** This is the only blocker. Once a
   `transact` returns 200 and its data is visible to `framework/query` at the returned ISN, the
   client is complete.
2. If `restore-attr` is intended as the materialization trigger, it needs to be reachable —
   `["restore-attr", "<eid>"]` currently returns `Permission denied: not perms-pass?` under our
   `auth.id == data.userId` rule. Confirming whether clients are expected to call it would let
   us rule it in or out immediately.
3. If the runtime transact pipeline is meant to apply to the same store that
   `framework/query` reads, a pointer to where the two diverge would save us a lot of guessing.

We're happy to re-test immediately — the probe scripts are self-contained (Node, ~100 lines,
no SDK) and a full round-trip check is one command.