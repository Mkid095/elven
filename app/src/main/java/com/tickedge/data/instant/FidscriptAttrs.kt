package com.tickedge.data.instant

/**
 * FIDScript attribute identifiers for the `elven` app.
 *
 * These are the server-assigned attribute ids. They are NOT field names: a tx-step
 * addresses an attribute by its uuid, and the namespace lives inside that id (there is
 * no namespace field in the wire format).
 *
 * GENERATED + VERIFIED: every id below was round-tripped against the live server
 * (write, then read back) on 2026-10-14. To regenerate after a schema change, read
 * the blobs out of `get-schema` (the MCP tool, NOT the admin schema endpoint, which
 * omits blobs) and re-verify before trusting new ids.
 */
object FidscriptAttrs {

    // ---- user_settings ----
    const val NS_USER_SETTINGS = "user_settings"
    const val USER_SETTINGS_ACCOUNTID = "cde90545-0521-4362-8f58-07658dc674fd"
    const val USER_SETTINGS_ACTIVEACCOUNTKIND = "a05f4d4e-a1c7-4f70-979c-36d5a4c38ad6"
    const val USER_SETTINGS_ANALYSISMETRIC = "35390ea6-5953-4001-911c-3d6f49c09b02"
    const val USER_SETTINGS_BASESTAKE = "211fd32f-73a7-4c19-bcb7-e5a446902355"
    const val USER_SETTINGS_COMPARISONOPERATOR = "9c737df3-78c5-47a5-8ca1-4189f63da48f"
    const val USER_SETTINGS_CONTRACTTYPE = "a89fd608-f842-4f05-8bd0-3bb49cfa1eb4"
    const val USER_SETTINGS_DIGITSELECTIONMODE = "1f1ee641-a9d3-46af-9ce2-7339f1d7ccc4"
    const val USER_SETTINGS_DURATIONUNIT = "f004e2cd-797e-48d3-9a23-1b39133adbff"
    const val USER_SETTINGS_DURATIONVALUE = "97077554-ac6d-4c73-a610-132e6c1fe3f3"
    const val USER_SETTINGS_ID = "ed6db0b2-b5e2-4e97-8734-f3ee75de76f1"
    const val USER_SETTINGS_MARKET = "0926933c-7303-421e-8831-5669089d4169"
    const val USER_SETTINGS_MARTINGALEENABLED = "618d4218-95d3-4482-919a-7640913c6431"
    const val USER_SETTINGS_MAXDAILYLOSS = "5d44507e-69d0-44ec-8eca-54e383f20cec"
    const val USER_SETTINGS_MAXMARTINGALESTEPS = "2f713d95-5fd0-43e9-9186-9549bad97cf5"
    const val USER_SETTINGS_MAXTRADESPERMARKET = "a7dfccc1-e9a8-49e8-b4c2-4ae4fe0598da"
    const val USER_SETTINGS_MINSAMPLESIZE = "0a85feba-3362-4630-a162-08267c69d534"
    const val USER_SETTINGS_RANDOMMARKETS = "d4125943-cdd0-42e2-8baf-14e9c7c2a8f8"
    const val USER_SETTINGS_REALARMED = "4a9484e3-0c30-49bc-8aad-fd14e781030d"
    const val USER_SETTINGS_RECOVERYDIGIT = "06b82fb5-d18b-4c48-aa78-0c4eda151ed5"
    const val USER_SETTINGS_RECOVERYDIRECTION = "2737154a-4e78-4fa9-ba87-3014841ad5ff"
    const val USER_SETTINGS_RECOVERYCOMPARISONOPERATOR = "5ca643f8-8c5a-410b-bc3e-877ee57b02fb"
    const val USER_SETTINGS_RECOVERYTHRESHOLD = "0604cd73-ef46-4789-a87c-32ae60561e63"
    const val USER_SETTINGS_REQUIREPERORDERCONFIRM = "5710f942-2527-4fc3-b249-a3ba95293e1c"
    const val USER_SETTINGS_STOPLOSS = "a50efe03-deac-4e05-8983-d995c36b5df3"
    const val USER_SETTINGS_STOPTRADINGONLIMIT = "2f60cb10-885f-4078-a448-adee7ccc57af"
    const val USER_SETTINGS_TAKEPROFIT = "68e6db96-5249-462b-b05c-5311915d9cca"
    const val USER_SETTINGS_TARGETDIGIT = "26ef83f2-9c99-4101-912f-ef9f17489c85"
    const val USER_SETTINGS_THRESHOLDPERCENT = "46bc3e11-5e0e-4117-a1b5-e8f839e83cba"
    const val USER_SETTINGS_UPDATEDAT = "be94a2de-53ee-49bd-b7ec-c5a18b6b58e4"
    const val USER_SETTINGS_USERID = "ad67e9e2-c4ae-46c2-bead-42043b7ab9c8"
    const val USER_SETTINGS_WINDOWSIZE = "cd4153c7-19da-4207-af48-ea165743617d"

    // ---- trade_history ----
    const val NS_TRADE_HISTORY = "trade_history"
    const val TRADE_HISTORY_ACCOUNTID = "ce7fcc57-2866-4df9-adc7-7801f788c051"
    const val TRADE_HISTORY_CONTRACTID = "d42fc8e1-a187-488f-9ebb-6d02eabd0f55"
    const val TRADE_HISTORY_CREATEDAT = "7f8097e6-c32b-4a0e-ae1f-3bc2c4ee409a"
    const val TRADE_HISTORY_CURRENCY = "fe5e0459-062c-4baf-94df-103b49e41c1f"
    const val TRADE_HISTORY_DIGIT = "88e42360-ed93-45d3-9c7e-d8891a23d369"
    const val TRADE_HISTORY_ID = "57e244a9-d9fe-421e-9443-a992957e29c5"
    const val TRADE_HISTORY_ISREAL = "d09f2c91-c889-42a1-81fa-e13f09e645b2"
    const val TRADE_HISTORY_MARKET = "364f9a6c-ad24-450d-aa18-c5592190b70b"
    const val TRADE_HISTORY_NET = "00fc6ec6-d993-4069-b97a-38c0b5e1aff6"
    const val TRADE_HISTORY_NOTE = "55dff53b-3b29-4176-8d39-2c868e3c8f5c"
    const val TRADE_HISTORY_OUTCOME = "1361bef5-3900-4b0b-a8f1-fb2bd50f0772"
    const val TRADE_HISTORY_STAKE = "eb057c8d-5278-42e4-8476-2d7e3a527d11"
    const val TRADE_HISTORY_USERID = "c70ca4f0-adfe-4cdd-b33a-2f7915061891"

    // Trade-detail attributes, pushed 2026-10-16 and read back from the live schema. Deliberately
    // NOT the UUIDs the dry run previewed — those are proposals; the server assigns the real ones,
    // and using a previewed id silently writes to an attribute that does not exist.
    const val TRADE_HISTORY_ASKPRICE = "432ec74f-5c0d-4e55-8146-05249b978f5a"
    const val TRADE_HISTORY_PAYOUT = "7593816e-d3cd-4c1d-a354-bbebefb6645a"

    /** Profit returned per unit staked. Absent on records taken before the live quote was wired in. */
    const val TRADE_HISTORY_PAYOUTRATIO = "805ad62c-24e4-4c74-a0e7-6a1bb5e6eea9"
    const val TRADE_HISTORY_CONTRACTTYPE = "0bd8e89b-a30d-4804-ade6-f2b78bf5c7bd"

    // ---- tickedge_users ----
    //
    // The account record for a TickEdge person, written on sign-up. One row per FIDScript user,
    // carrying the name the person gave when they registered: the sign-in screen's "create your
    // account" flow is what this row exists for. Pushed 2026-10-31 and read back from the live
    // schema, the same round-trip discipline as the trade-detail attributes above.
    const val NS_TICKEDGE_USERS = "tickedge_users"
    const val TICKEDGE_USERS_ID = "72affd82-f493-4d6e-9eba-c3bbdbe4663e"
    const val TICKEDGE_USERS_USERID = "1e3eba44-8eb0-4f2b-8103-3399b2e7073a"
    const val TICKEDGE_USERS_FIRSTNAME = "05830ac3-ca57-4831-92b6-204d8c91d27f"
    const val TICKEDGE_USERS_LASTNAME = "2acaa66b-d986-47ee-9ee1-0061c9b225a6"
    const val TICKEDGE_USERS_USERNAME = "c5c678ae-739f-4a76-b9d0-34e8d30d7d7d"
    const val TICKEDGE_USERS_EMAIL = "f0470c9f-4584-4632-b537-0f35ee0efba9"
    const val TICKEDGE_USERS_CREATEDAT = "43d606b9-6b1b-4c0f-9964-438a36ea3ccb"

    /**
     * Which ladder rung placed the trade — a snapshot taken at placement, never recomputed.
     *
     * Recalculating later would describe a past trade using a ladder that has since moved on, which
     * is exactly the kind of number that looks authoritative and is not.
     */
    const val TRADE_HISTORY_RECOVERYSTEP = "b41f7d35-33f3-45c9-945a-69e6baa66cfb"

    /** Loss outstanding when this trade was placed — what the rung was trying to clear. */
    const val TRADE_HISTORY_OUTSTANDINGBEFORE = "5d832d07-2550-44e8-b503-62375279af3f"
    const val TRADE_HISTORY_OPENEDAT = "ddd72330-c93e-4774-a3f5-4446afe8bf97"
}
