package io.paimon.fx991

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Selected text -> Calculate.
 *
 * An entry point in the system text selection menu that forwards the selected text/plain to MainActivity.
 * Displays no UI itself, finishing immediately after forwarding.
 * The text is normalized and evaluated in MainActivity -> IncomingText -> CalcViewModel.applyIncomingText
 * (handling – ✕ ⁄ full-width parentheses pasted from browsers or documents).
 */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_CALCULATE)
                    .putExtra(MainActivity.EXTRA_EXPRESSION, text)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
