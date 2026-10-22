package dev.azwall.core.wallet

import dev.azwall.core.pxe.NetworkCheck
import java.math.BigInteger

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

    /** The text of the Activate notice. */
    fun activation(check: NetworkCheck?, feeJuice: String?): String = when {
        !paysWithFeeJuice(check) -> "Your address can already receive funds. Activating publishes your account contract; its fee is sponsored on this network."
        feeJuice != null && !isEmpty(feeJuice) -> "Your address can already receive funds. Activating publishes your account contract, paid with the Fee Juice it holds."
        else -> "Activating costs Fee Juice, which this account does not hold yet: bridge AZTEC from Ethereum to your address, then activate."
    }

    private fun isEmpty(units: String): Boolean = units.toBigIntegerOrNull()?.signum() != 1
}
