package dev.azwall.core.wallet

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Token amounts: base units (BigInteger) on the wire, decimal strings for people. */
object Amounts {
    /** `1234500000, 6` → `1,234.5`. At most [maxFraction] decimals, rounded down; trailing zeros dropped. */
    fun format(value: BigInteger, decimals: Int, maxFraction: Int = 6): String {
        val exact = BigDecimal(value, decimals)
        val shown = exact.setScale(minOf(decimals, maxFraction), RoundingMode.DOWN).stripTrailingZeros()
        val plain = if (shown.signum() == 0) BigDecimal.ZERO else shown
        val intPart = plain.toBigInteger()
        val grouped = "%,d".format(java.util.Locale.ROOT, intPart)
        val fraction = plain.toPlainString().substringAfter('.', "")
        return if (fraction.isEmpty()) grouped else "$grouped.$fraction"
    }

    /** `"1.5", 6` → `1500000`. Accepts `.` or `,` as the decimal separator; rejects more decimals than the token has. */
    fun parse(input: String, decimals: Int): BigInteger {
        val text = input.trim().replace(',', '.')
        require(Regex("^\\d+(\\.\\d*)?$|^\\.\\d+$").matches(text)) { "enter a number" }
        val value = BigDecimal(if (text.startsWith(".")) "0$text" else text)
        require(value.scale() <= decimals) { "at most $decimals decimals" }
        val units = value.movePointRight(decimals).toBigIntegerExact()
        require(units.signum() > 0) { "enter an amount above zero" }
        return units
    }
}
