package io.paimon.fx991.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Unified system insets avoidance modifier (shared across all screens).
 *
 * The app runs edge-to-edge (see `enableEdgeToEdge()` in `MainActivity`), so the window
 * does not automatically pad for system bars; content must avoid them explicitly.
 * This uses Compose's official `WindowInsets.safeDrawing`:
 *
 *  - **Top**: Status bar (height varies by notch / cutout / device model, reported by the system, never hardcoded)
 *  - **Bottom**: Navigation bar / gesture bar (differs between 3-button and gesture navigation, reported by the system)
 *  - **Left / Right**: Cutouts and system gesture areas in landscape orientation
 *  - Avoids IME when the keyboard pops up (input fields in panels will not be covered by the keyboard)
 *
 * Simply apply this single modifier to the root composable of any screen that abuts system bars.
 * Avoid hardcoding fixed paddings elsewhere.
 */
@Composable
fun Modifier.safeAreaPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing)

