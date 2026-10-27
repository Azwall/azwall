package dev.azwall.core.wallet

import dev.azwall.core.pxe.FeeJuiceDeposit
import dev.azwall.core.pxe.NetworkCheck
import dev.azwall.core.pxe.TokenBalance
import dev.azwall.core.pxe.TokenInfo
import java.math.BigInteger

/**
 * Aztec's fee token, shown first: every account has a Fee Juice balance (public only: it arrives bridged from Ethereum)
 * and spends it on fees. It cannot be sent, so it lives beside the followed tokens, under an id that is no address.
 */
object FeeJuice {
    const val ID = "fee-juice"
    val INFO = TokenInfo(ID, "Fee Juice", "FJ", 18)

    /** The account's Fee Juice as a token, its balance unknown until read. */
    fun token(units: String?): TokenView = TokenView(INFO, units?.let { TokenBalance(ID, "0", it) })

    fun isIt(token: TokenView): Boolean = token.info.address == ID
}

/**
 * What the wallet says about fees. Test networks have a Sponsored FPC that pays them all; elsewhere (mainnet) the
 * account pays with its own Fee Juice, bridged from Ethereum as AZTEC to its address, activation included.
 */
object Fees {
    const val SPONSORED = "None, paid by the network's sponsor"
    private const val DECIMALS = 18

    fun paysWithFeeJuice(check: NetworkCheck?): Boolean = check?.fees == "feeJuice"

    /** The Fee line of a confirmation; `feeJuice` is the account's balance in base units, when known. */
    fun line(check: NetworkCheck?, feeJuice: String?): String = when {
        !paysWithFeeJuice(check) -> SPONSORED
        feeJuice == null -> "Paid with your Fee Juice"
        isEmpty(feeJuice) -> "Paid with Fee Juice, which this account does not hold: the transaction will fail"
        else -> "Paid with your Fee Juice (you hold ${Amounts.display(BigInteger(feeJuice), DECIMALS, 4)} FJ)"
    }

    /** The Fee line of an activation that claims [claim]: the fee comes out of what it claims. */
    fun claimLine(claim: FeeJuiceDeposit): String =
        "Paid with the ${Amounts.display(claim.units, DECIMALS, 4)} FJ this activation claims from your bridge deposit"

    /**
     * Whether the account can pay its activation: a sponsor pays, it claims a deposit in the same transaction, or it
     * already holds Fee Juice. Otherwise it would only fail once proven, so Activate is not offered. Nor is it before
     * the network says how it pays fees ([check] is null while the PXE starts): offered then taken back, it would flicker.
     */
    fun canActivate(check: NetworkCheck?, feeJuice: String?, claim: FeeJuiceDeposit?): Boolean =
        check != null && (!paysWithFeeJuice(check) || claim != null || (feeJuice != null && !isEmpty(feeJuice)))

    /**
     * The text of the Activate notice: Fee Juice bridged and ready ([claim]) is claimed by the activation itself;
     * [arriving] base units are on their way from Ethereum.
     */
    fun activation(check: NetworkCheck?, feeJuice: String?, claim: FeeJuiceDeposit? = null, arriving: BigInteger = BigInteger.ZERO): String = when {
        check == null -> "Your address can already receive funds. Activating publishes your account contract, once the network is reached."
        claim != null -> "${Amounts.display(claim.units, DECIMALS, 4)} FJ bridged from Ethereum are ready: activating claims them and " +
            "pays its fee with them, in one transaction."
        arriving.signum() > 0 && paysWithFeeJuice(check) && (feeJuice == null || isEmpty(feeJuice)) ->
            "${Amounts.display(arriving, DECIMALS, 4)} FJ bridged from Ethereum are on their way: you can activate once they have arrived, in a few minutes."
        !paysWithFeeJuice(check) -> "Your address can already receive funds. Activating publishes your account contract; its fee is sponsored on this network."
        feeJuice != null && !isEmpty(feeJuice) -> "Your address can already receive funds. Activating publishes your account contract, paid with the Fee Juice it holds."
        else -> "Activating costs Fee Juice, which this account does not hold yet: bridge AZTEC from Ethereum to your address, then activate."
    }

    private fun isEmpty(units: String): Boolean = units.toBigIntegerOrNull()?.signum() != 1
}
