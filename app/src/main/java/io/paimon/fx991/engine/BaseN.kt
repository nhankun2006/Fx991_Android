package io.paimon.fx991.engine

import java.math.BigInteger

// ---------------------------------------------------------------------------
// Batch D: Base conversions BASE-N
//
// DEC / HEX / BIN / OCT conversions + bitwise operations (and / or / xor / xnor / not / neg),
// selectable word length 16 / 32 / 64 bits, negative numbers stored and displayed in two's complement.
//
// Internal representation: Long stores raw bit pattern (two's complement), all operations truncated to word length (consistent with reference calculator).
// DEC mode displays signed value; HEX / BIN / OCT display two's complement bit pattern.
// ---------------------------------------------------------------------------

object BaseN {

    val WORD_SIZES = listOf(16, 32, 64)

    /** base values: 2 / 8 / 10 / 16 */
    val BASE_LABELS = listOf("DEC", "HEX", "BIN", "OCT")
    val BASE_VALUES = listOf(10, 16, 2, 8)

    private fun mask(bits: Int): Long = if (bits >= 64) -1L else (1L shl bits) - 1L

    private fun signBit(bits: Int): Long =
        if (bits >= 64) Long.MIN_VALUE else 1L shl (bits - 1)

    /** Truncate by word length (two's complement wrap-around) */
    fun wrap(v: Long, bits: Int): Long = v and mask(bits)

    /** Raw bit pattern → signed value (used for DEC display) */
    fun toSigned(raw: Long, bits: Int): Long {
        val r = wrap(raw, bits)
        return if (r and signBit(bits) != 0L) r or mask(bits).inv() else r
    }

    /** Raw bit pattern → unsigned BigInteger (used for HEX / BIN / OCT bit pattern display) */
    private fun toUnsignedBig(raw: Long, bits: Int): BigInteger {
        val r = wrap(raw, bits)
        var b = BigInteger.valueOf(r)
        if (b.signum() < 0) b = b.add(BigInteger.ONE.shiftLeft(64)) // only negative when bits == 64
        return b
    }

    /**
     * Parses input string. May have leading `-` / `−` for negative values (stored in two's complement).
     * Magnitude must be < 2^bits, otherwise NumericError; throws error on invalid digits for the base.
     */
    fun parse(text: String, base: Int, bits: Int): Long {
        var s = text.trim().uppercase()
        if (s.isEmpty()) throw NumericError("Please enter a value")
        var neg = false
        if (s.startsWith("-") || s.startsWith("−")) {
            neg = true
            s = s.substring(1)
        }
        if (s.isEmpty()) throw NumericError("Please enter a value")
        val digits = when (base) {
            2 -> "01"
            8 -> "01234567"
            10 -> "0123456789"
            16 -> "0123456789ABCDEF"
            else -> throw NumericError("Unsupported base")
        }
        if (s.any { it !in digits }) throw NumericError("Invalid digit for radix")
        val m = try {
            BigInteger(s, base)
        } catch (_: Exception) {
            throw NumericError("Not a valid number")
        }
        val cap = BigInteger.ONE.shiftLeft(bits)
        if (m >= cap) throw NumericError("Exceeds $bits-bit word length")
        var raw = m.toLong() // m < 2^64; when bits==64 and m >= 2^63, toLong gives its two's complement bit pattern
        if (neg) raw = -raw
        return wrap(raw, bits)
    }

    /**
     * Formats raw bit pattern according to base (minimum digits, no leading zeros).
     * DEC displays signed value; HEX / BIN / OCT display two's complement bit pattern (uppercase).
     */
    fun format(raw: Long, base: Int, bits: Int): String = when (base) {
        10 -> toSigned(raw, bits).toString()
        16 -> toUnsignedBig(raw, bits).toString(16).uppercase()
        8 -> toUnsignedBig(raw, bits).toString(8)
        2 -> toUnsignedBig(raw, bits).toString(2)
        else -> throw NumericError("Unsupported base")
    }

    /** When signed value is negative, attach an unsigned magnitude row (auxiliary display in UI) */
    fun unsignedDec(raw: Long, bits: Int): String = toUnsignedBig(raw, bits).toString()

    // ---- Bitwise operations (results truncated to word length) ----

    fun bitAnd(a: Long, b: Long, bits: Int): Long = wrap(a and b, bits)

    fun bitOr(a: Long, b: Long, bits: Int): Long = wrap(a or b, bits)

    fun bitXor(a: Long, b: Long, bits: Int): Long = wrap(a xor b, bits)

    fun bitXnor(a: Long, b: Long, bits: Int): Long = wrap((a xor b).inv(), bits)

    fun bitNot(a: Long, bits: Int): Long = wrap(a.inv(), bits)

    /** Two's complement negate */
    fun neg(a: Long, bits: Int): Long = wrap(-a, bits)
}
