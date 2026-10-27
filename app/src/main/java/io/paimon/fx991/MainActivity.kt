package io.paimon.fx991

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.paimon.fx991.ui.CalcApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge: window occupies the entire display (including status/nav bar areas),
        // with Compose Modifier.safeAreaPadding() insetting based on real system insets.
        // No status bar heights are hardcoded; cutouts and gesture bars are reported dynamically.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        routeIntent(intent)
        setContent {
            CalcApp()
        }
    }

    /**
     * Handles external text intents (PROCESS_TEXT / shortcuts) when already in foreground.
     * Forwards text directly to Compose layer without recreating the Activity.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        if (intent == null) return
        val text: String? = when {
            intent.action == Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            intent.hasExtra(EXTRA_EXPRESSION) -> intent.getStringExtra(EXTRA_EXPRESSION)
            else -> null
        }
        IncomingText.offer(text)
    }

    companion object {
        const val ACTION_CALCULATE = "io.paimon.fx991.action.CALCULATE"
        const val EXTRA_EXPRESSION = "io.paimon.fx991.extra.EXPRESSION"
    }
}
