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
    const val USER_SETTINGS_BASESTAKE = "211fd32f-73a7-4c19-bcb7-e5a446902355"
    const val USER_SETTINGS_CONTRACTTYPE = "a89fd608-f842-4f05-8bd0-3bb49cfa1eb4"
    const val USER_SETTINGS_ID = "ed6db0b2-b5e2-4e97-8734-f3ee75de76f1"
    const val USER_SETTINGS_MARKET = "0926933c-7303-421e-8831-5669089d4169"
    const val USER_SETTINGS_MAXDAILYLOSS = "5d44507e-69d0-44ec-8eca-54e383f20cec"
    const val USER_SETTINGS_MAXMARTINGALESTEPS = "2f713d95-5fd0-43e9-9186-9549bad97cf5"
    const val USER_SETTINGS_REALARMED = "4a9484e3-0c30-49bc-8aad-fd14e781030d"
    const val USER_SETTINGS_RECOVERYDIGIT = "06b82fb5-d18b-4c48-aa78-0c4eda151ed5"
    const val USER_SETTINGS_RECOVERYDIRECTION = "2737154a-4e78-4fa9-ba87-3014841ad5ff"
    const val USER_SETTINGS_REQUIREPERORDERCONFIRM = "5710f942-2527-4fc3-b249-a3ba95293e1c"
    const val USER_SETTINGS_STOPLOSS = "a50efe03-deac-4e05-8983-d995c36b5df3"
    const val USER_SETTINGS_TAKEPROFIT = "68e6db96-5249-462b-b05c-5311915d9cca"
    const val USER_SETTINGS_TARGETDIGIT = "26ef83f2-9c99-4101-912f-ef9f17489c85"
    const val USER_SETTINGS_UPDATEDAT = "be94a2de-53ee-49bd-b7ec-c5a18b6b58e4"
    const val USER_SETTINGS_USERID = "ad67e9e2-c4ae-46c2-bead-42043b7ab9c8"

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
}
