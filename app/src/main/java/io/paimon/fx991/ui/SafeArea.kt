package io.paimon.fx991.ui

/**
 * Layout strategy after system insets avoidance — pure Kotlin, no Compose / Android dependencies,
 * directly testable via JVM regression tests (see `tools/InsetsTest.java`).
 *
 * On the Compose side, `Modifier.safeAreaPadding()` handles padding according to actual system insets;
 * this class handles layout decisions such as whether the remaining height (after subtracting the safe area)
 * can accommodate the keypad:
 *
 *  - Normal portrait devices (abundant height) -> Weighted layout: utility bar fixed, LCD takes [LCD_WEIGHT],
 *    keypad takes the remainder;
 *  - Landscape / small screens (insufficient available height) -> Compact layout: LCD and key rows use minimum heights,
 *    entire layout becomes scrollable to prevent keys from becoming unclickable or squeezed offscreen.
 */
object SafeArea {

    /** Number of keypad rows (matches `keypadRows()` in `ui/Keys.kt`: 9 rows below utility bar) */
    const val KEYPAD_ROWS = 9

    /** Utility bar (Menu / PRO / Σ / Settings / Camera / More) fixed height (dp) */
    const val UTILITY_BAR_DP = 46

    /** Minimum LCD height in dp under compact layout */
    const val MIN_LCD_DP = 150

    /** Minimum single key row height in dp under compact layout — keys become unclickable below this */
    const val MIN_ROW_DP = 34

    /** Fraction of height allocated to LCD in portrait weighted layout (remainder given to keypad) */
    const val LCD_WEIGHT = 0.30f

    /** Minimum total height required (dp) for compact layout: utility bar + LCD + all keypad rows */
    @JvmStatic
    @JvmOverloads
    fun requiredHeightDp(
        rows: Int = KEYPAD_ROWS,
        lcdMinDp: Int = MIN_LCD_DP,
        rowMinDp: Int = MIN_ROW_DP,
        utilityBarDp: Int = UTILITY_BAR_DP,
    ): Int = utilityBarDp + lcdMinDp + rows * rowMinDp

    /**
     * Checks if available height (safe area subtracted) is too small for fixed row heights.
     * true -> Use compact layout (fixed row height + whole screen scrollable); false -> Use weighted layout.
     */
    @JvmStatic
    @JvmOverloads
    fun needsScrollLayout(
        availableHeightDp: Int,
        rows: Int = KEYPAD_ROWS,
        lcdMinDp: Int = MIN_LCD_DP,
        rowMinDp: Int = MIN_ROW_DP,
        utilityBarDp: Int = UTILITY_BAR_DP,
    ): Boolean = availableHeightDp < requiredHeightDp(rows, lcdMinDp, rowMinDp, utilityBarDp)

    /** Keypad row height fallback: reverts to minimum if requested height is invalid (<= 0) */
    @JvmStatic
    fun rowHeightDp(availableRowDp: Int): Int =
        if (availableRowDp <= 0) MIN_ROW_DP else availableRowDp
}
