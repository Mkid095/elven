package com.tickedge.data.deriv

/** Kind of a real Deriv account, mapped to the app's Demo / Live switcher. */
enum class DerivAccountKind { DEMO, REAL }

/** A real Deriv account, fetched from GET /trading/v1/options/accounts. */
data class DerivAccount(
    val id: String,
    val kind: DerivAccountKind,
    val currency: String = "USD",
    val balance: Double = 0.0,
    val status: String = "active"
) {
    val displayName: String
        get() = if (kind == DerivAccountKind.DEMO) "Demo Account" else "Live Account"
}

/** Deriv options contract types used by the strategies (V1 options contract_type codes). */
enum class DerivContractType(val code: String, val label: String) {
    DIGIT_DIFFERS("DIGITDIFF", "Digit Differs"),
    DIGIT_OVER("DIGITOVER", "Digits Over"),
    DIGIT_UNDER("DIGITUNDER", "Digits Under"),
    /**
     * Wins when the last digit of the final tick is even ({0,2,4,6,8}). No digit barrier:
     * [DerivContractParams.barrier] is ignored for this type, so callers pass 0.
     */
    DIGIT_EVEN("DIGITEVEN", "Digit Even"),
    /**
     * Wins when the last digit of the final tick is odd ({1,3,5,7,9}). No digit barrier, same
     * convention as [DIGIT_EVEN].
     */
    DIGIT_ODD("DIGITODD", "Digit Odd")
}

/** Parameters for a digit contract. Maps 1:1 to the V3-sibling `proposal` request fields. */
data class DerivContractParams(
    val contractType: DerivContractType,
    val underlyingSymbol: String,
    val currency: String,
    val amount: Double,      // stake
    val barrier: Int,        // target digit
    val duration: Int,
    val durationUnit: String // "t" | "s" | "m"
)

/** A priced contract returned by a `proposal` request. */
data class DerivProposal(
    val id: String,
    val askPrice: Double,
    val payout: Double,
    val spot: Double,
    val longcode: String = ""
)

/** An opened contract returned by a `buy`. */
data class DerivBuyResult(
    val contractId: String,
    val buyPrice: Double,
    val payout: Double
)

/** A settled contract snapshot. [settled] is false when polling timed out without a final state. */
data class DerivSettlement(
    val contractId: String,
    val profit: Double,
    val payout: Double,
    val isWin: Boolean,
    val settled: Boolean
)
