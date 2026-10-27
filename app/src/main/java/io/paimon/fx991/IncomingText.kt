package io.paimon.fx991

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bridge between external text inputs (ProcessText / shortcuts / system share) and Compose.
 *
 * MainActivity writes text into pending in onCreate / onNewIntent;
 * CalcApp consumes it in LaunchedEffect(pending) and calls consume() to clear it.
 * Uses Compose mutableState so when Activity is already foregrounded,
 * onNewIntent triggers recomposition without needing Activity recreation.
 */
object IncomingText {
    var pending by mutableStateOf<String?>(null)
        private set

    fun offer(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isNotEmpty()) pending = t
    }

    fun consume() {
        pending = null
    }
}
