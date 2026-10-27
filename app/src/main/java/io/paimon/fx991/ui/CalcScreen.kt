package io.paimon.fx991.ui

import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.paimon.fx991.AppTheme
import io.paimon.fx991.CalcViewModel
import io.paimon.fx991.DisplayMode
import io.paimon.fx991.FuncKind
import io.paimon.fx991.Overlay
import io.paimon.fx991.PrecisionMode
import io.paimon.fx991.Screen
import io.paimon.fx991.engine.AngleMode
import io.paimon.fx991.engine.API_PRESETS
import io.paimon.fx991.engine.ApiConfig
import io.paimon.fx991.engine.ReleaseInfo
import io.paimon.fx991.engine.NumberNotation
import io.paimon.fx991.engine.SciConstants
import io.paimon.fx991.engine.SiPrefixes
import io.paimon.fx991.engine.UnitConvert
import io.paimon.fx991.engine.label

import io.paimon.fx991.BuildConfig

/** Runtime version taken directly from build artifact to avoid hardcoded discrepancy with versionName */
val APP_VERSION: String = BuildConfig.VERSION_NAME
const val APP_REPO = "https://github.com/xiaozishan/fx991-android"

@Composable
fun CalcApp(vm: CalcViewModel = viewModel()) {
    // Incoming external text (ProcessText selected text / shortcuts) → main line and immediate evaluation
    val incoming = io.paimon.fx991.IncomingText.pending
    LaunchedEffect(incoming) {
        if (incoming != null) {
            vm.applyIncomingText(incoming)
            io.paimon.fx991.IncomingText.consume()
        }
    }
    val dark = when (vm.settings.theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    CalcTheme(darkTheme = dark) {
        val c = LocalCalcColors.current
        // Root Surface fills entire screen (including status bar / nav bar area) — edge-to-edge background color,
        // child elements use Modifier.safeAreaPadding() to handle system insets.
        CompositionLocalProvider(LocalNatInput provides vm.natInput) {
        Surface(Modifier.fillMaxSize(), color = c.body) {
            Box(
                Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Input experience: Ctrl+Z / Ctrl+Y (external keyboard undo/redo)
                    if (ev.isCtrlPressed) {
                        when (ev.key) {
                            ComposeKey.Z -> { vm.undoEdit(); return@onPreviewKeyEvent true }
                            ComposeKey.Y -> { vm.redoEdit(); return@onPreviewKeyEvent true }
                        }
                    }
                    val a: KeyAction? = when (ev.key) {
                        ComposeKey.Zero, ComposeKey.NumPad0 -> KeyAction.Insert("0")
                        ComposeKey.One, ComposeKey.NumPad1 -> KeyAction.Insert("1")
                        ComposeKey.Two, ComposeKey.NumPad2 -> KeyAction.Insert("2")
                        ComposeKey.Three, ComposeKey.NumPad3 -> KeyAction.Insert("3")
                        ComposeKey.Four, ComposeKey.NumPad4 -> KeyAction.Insert("4")
                        ComposeKey.Five, ComposeKey.NumPad5 -> KeyAction.Insert("5")
                        ComposeKey.Six, ComposeKey.NumPad6 -> KeyAction.Insert("6")
                        ComposeKey.Seven, ComposeKey.NumPad7 -> KeyAction.Insert("7")
                        ComposeKey.Eight, ComposeKey.NumPad8 -> KeyAction.Insert("8")
                        ComposeKey.Nine, ComposeKey.NumPad9 -> KeyAction.Insert("9")
                        ComposeKey.Period, ComposeKey.NumPadDot -> KeyAction.Insert(".")
                        ComposeKey.Plus, ComposeKey.NumPadAdd -> KeyAction.Insert("+")
                        ComposeKey.Minus, ComposeKey.NumPadSubtract -> KeyAction.Insert("-")
                        ComposeKey.Multiply, ComposeKey.NumPadMultiply -> KeyAction.Insert("×")
                        ComposeKey.Slash, ComposeKey.NumPadDivide -> KeyAction.Insert("/")
                        ComposeKey.LeftBracket -> KeyAction.Insert("(")
                        ComposeKey.RightBracket -> KeyAction.Insert(")")
                        ComposeKey.Equals -> KeyAction.Insert("=")
                        ComposeKey.Enter, ComposeKey.NumPadEnter -> KeyAction.Equals
                        ComposeKey.Backspace -> KeyAction.Del
                        ComposeKey.Escape -> KeyAction.Ac
                        ComposeKey.DirectionLeft -> KeyAction.PadLeft
                        ComposeKey.DirectionRight -> KeyAction.PadRight
                        ComposeKey.DirectionUp -> KeyAction.PadUp
                        ComposeKey.DirectionDown -> KeyAction.PadDown
                        else -> null
                    }
                    if (a == null) false else { vm.onAction(a); true }
                }
            ) {
                when (vm.screen) {
                    Screen.ODE -> OdeScreen(onBack = { vm.goto(Screen.CALC) })
                    Screen.CMPLX -> CmplxScreen(onBack = { vm.goto(Screen.CALC) })
                    Screen.MATRIX -> MatrixScreen(onBack = { vm.goto(Screen.CALC) }, store = vm.matrixStore)
                    Screen.VECTOR -> VectorScreen(onBack = { vm.goto(Screen.CALC) }, store = vm.vectorStore)
                    Screen.STAT -> StatScreen(onBack = { vm.goto(Screen.CALC) })
                    Screen.DISTR -> DistrScreen(onBack = { vm.goto(Screen.CALC) })
                    Screen.FUNC_HELP -> FuncHelpScreen(vm, onBack = { vm.goto(Screen.CALC) })
                    // ---- Batch D ----
                    Screen.EQUATION -> EquationScreen(vm, onBack = { vm.goto(Screen.CALC) })
                    Screen.BASEN -> BaseNScreen(onBack = { vm.goto(Screen.CALC) })
                    Screen.TABLE -> TableScreen(onBack = { vm.goto(Screen.CALC) })
                    Screen.RATIO -> RatioScreen(onBack = { vm.goto(Screen.CALC) })
                    // ---- Batch E ----
                    Screen.PHOTO_SOLVE -> PhotoSolveScreen(vm, onBack = { vm.goto(Screen.CALC) })
                    // ---- Batch G ----
                    Screen.ALGEBRA -> AlgebraScreen(vm, onBack = { vm.goto(Screen.CALC) })
                    Screen.FOURIER -> FourierScreen(onBack = { vm.goto(Screen.CALC) })
                    Screen.CPLXF -> CplxFuncScreen(onBack = { vm.goto(Screen.CALC) })
                    else -> CalcSurface(vm)
                }
                when (vm.overlay) {
                    Overlay.NONE -> Unit
                    Overlay.MODE -> ModeMenuOverlay(vm)
                    Overlay.SETTINGS -> SettingsPanel(vm)
                    Overlay.MORE -> MorePanel(vm)
                    Overlay.PRO -> ProPanel(vm)
                    Overlay.HISTORY -> HistoryPanel(vm)
                    Overlay.FUNC -> FuncPanel(vm)
                    Overlay.HELP -> HelpPanel(vm)
                    Overlay.STO -> StoPanel(vm)
                    Overlay.CONST -> ConstPanel(vm)
                    Overlay.CONV -> UnitConvPanel(vm)
                    Overlay.SI -> SiPanel(vm)
                    Overlay.CLRCONFIRM -> ClearAllConfirmPanel(vm)
                }
                // Batch K3-B: When secondary screen fields are focused, show app's own keyboard panel
                // (no system IME; rendered on top of overlays so dialog fields can use it too)
                val ni = vm.natInput
                if (ni.focused != null) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        NatKeyboardPanel(ni, Modifier.fillMaxWidth())
                    }
                }
                // Batch K5: Silent update check found new version → show prompt (same version dismissed with "Later" is not reshown;
                // doesn't steal focus if other overlays are open)
                val ua = vm.updateAvailable
                if (ua != null && ua.tag != vm.updateDismissedTag && vm.overlay == Overlay.NONE) {
                    UpdatePromptPanel(vm, ua)
                }
            }
        }
        }
    }
}

// ---------------------------------------------------------------------------
// Main calculation screen: system insets avoidance + adaptive height
// ---------------------------------------------------------------------------

/**
 * Main calculator interface.
 *
 * **Insets**: uses [safeAreaPadding] (`WindowInsets.safeDrawing`) to avoid status bar (top),
 * navigation bar / gesture bar (bottom), landscape cutouts (sides), and IME keyboard when open —
 * all from true system insets without hardcoding heights, so top bar keys (Menu / PRO / Σ / Settings / Camera / More) are never covered.
 *
 * **Adaptive**: selects layout based on available height after safe area padding:
 *  - Ample height (standard portrait) → weighted layout: utility bar fixed, LCD [SafeArea.LCD_WEIGHT], keypad fills the rest;
 *  - Landscape / compact screens → compact scroll layout: LCD and key rows use minimum heights, scrollable overall without squash or overflow.
 */
@Composable
private fun CalcSurface(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding()
    ) {
        if (SafeArea.needsScrollLayout(maxHeight.value.toInt())) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                UtilityBar(vm)
                LcdDeck(vm, Modifier.fillMaxWidth().height(SafeArea.MIN_LCD_DP.dp))
                KeypadDeck(
                    vm = vm,
                    modifier = Modifier.fillMaxWidth(),
                    rowHeight = SafeArea.rowHeightDp(0).dp,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                UtilityBar(vm)
                LcdDeck(vm, Modifier.fillMaxWidth().weight(SafeArea.LCD_WEIGHT))
                KeypadDeck(vm, Modifier.fillMaxWidth().weight(1f - SafeArea.LCD_WEIGHT))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Generic overlay frame
// ---------------------------------------------------------------------------

@Composable
private fun PanelCard(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalCalcColors.current
    // Scrim covers entire screen (including system bars); panel itself is padded to safe area — never obscured.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .safeAreaPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.86f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.body)
                    .border(1.dp, c.keyEdge, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Text(title, color = c.bodyInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(subtitle, color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = ShiftOrange,
                    ),
                    border = BorderStroke(1.dp, ShiftOrange),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                ) { Text("Close", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun MenuButton(label: String, hint: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalCalcColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, c.keyEdge),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.keyNeutral,
            contentColor = c.bodyInk,
            disabledContainerColor = c.keyNeutral.copy(alpha = 0.4f),
            disabledContentColor = c.bodyInk.copy(alpha = 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.fillMaxWidth().height(46.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 14.sp, maxLines = 1)
            if (hint != null) Text(hint, fontSize = 11.sp, color = c.keyNeutralInk.copy(alpha = 0.7f))
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        color = LocalCalcColors.current.keyNeutralInk,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
    )
    Spacer(Modifier.height(10.dp))
}

// ---------------------------------------------------------------------------
// ① Menu: lists all modes (all available after Batch D)
// ---------------------------------------------------------------------------

@Composable
private fun ModeMenuOverlay(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    PanelCard(
        title = "Select Mode MODE",
        subtitle = "All modes implemented",
        onClose = { vm.closeOverlay() },
    ) {
        modeEntries().forEach { e ->
            MenuButton(
                label = e.title,
                hint = "Enter",
                enabled = e.screen != null,
                onClick = { e.screen?.let { vm.goto(it) } },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Batch D: Equation EQN · Base-N BASE-N · Function Table TABLE · Ratio RATIO; " +
                "Batch C: Complex / Matrix / Vector / Stat / Distr / Function Help (direct keys via SHIFT 2 / 4 / 5 / 1 / 3 / 6)",
            color = c.keyNeutralInk.copy(alpha = 0.65f),
            fontSize = 11.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// ① Settings panel
// ---------------------------------------------------------------------------

@Composable
private fun SettingsPanel(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    PanelCard(title = "Settings", subtitle = "Theme · Angle Unit · Display Precision · Fraction Display · Vibration · Check Updates", onClose = { vm.closeOverlay() }) {
        Text("Theme", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChoiceChips(
            options = AppTheme.entries.map { it.title },
            selected = AppTheme.entries.indexOf(vm.settings.theme),
            onSelect = { vm.setTheme(AppTheme.entries[it]) },
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = c.keyEdge)
        Spacer(Modifier.height(14.dp))

        Text("Angle Unit", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChoiceChips(
            options = AngleMode.entries.map { it.label },
            selected = AngleMode.entries.indexOf(vm.angleMode),
            onSelect = { vm.setAngle(AngleMode.entries[it]) },
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = c.keyEdge)
        Spacer(Modifier.height(14.dp))

        Text("Display Precision", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChoiceChips(
            options = PrecisionMode.entries.map { it.title },
            selected = PrecisionMode.entries.indexOf(vm.settings.precision),
            onSelect = { vm.setPrecision(PrecisionMode.entries[it]) },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (vm.settings.precision == PrecisionMode.DECIMALS) "Decimal places" else "Significant digits",
                color = c.chromeInk, fontSize = 12.sp,
            )
            Stepper(
                value = if (vm.settings.precision == PrecisionMode.DECIMALS) vm.settings.decimals else vm.settings.sigDigits,
                onDec = {
                    if (vm.settings.precision == PrecisionMode.DECIMALS) vm.setDecimals(vm.settings.decimals - 1)
                    else vm.setSigDigits(vm.settings.sigDigits - 1)
                },
                onInc = {
                    if (vm.settings.precision == PrecisionMode.DECIMALS) vm.setDecimals(vm.settings.decimals + 1)
                    else vm.setSigDigits(vm.settings.sigDigits + 1)
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = c.keyEdge)
        Spacer(Modifier.height(14.dp))

        Text("Fraction Display (linked with S⇔D)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChoiceChips(
            options = listOf("Improper", "Mixed", "Decimal"),
            selected = DisplayMode.entries.indexOf(vm.displayMode),
            onSelect = { vm.chooseDisplayMode(DisplayMode.entries[it]) },
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = c.keyEdge)
        Spacer(Modifier.height(14.dp))

        Text("Number Display Mode (scientific notation, linked with ENG)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChoiceChips(
            options = listOf("Normal", "Scientific", "Engineering"),
            selected = NumberNotation.entries.indexOf(vm.settings.notation),
            onSelect = { vm.setNotation(NumberNotation.entries[it]) },
        )
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = c.keyEdge)
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("DMS Output", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Display result as 12°34′56″; off for decimal",
                    color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
                )
            }
            Switch(checked = vm.settings.dmsOutput, onCheckedChange = { vm.setDmsOutput(it) })
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = c.keyEdge)
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Key Vibration", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Haptic feedback on key press", color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp)
            }
            Switch(checked = vm.settings.vibration, onCheckedChange = { vm.setVibration(it) })
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = c.keyEdge)
        Spacer(Modifier.height(14.dp))

        // ---- Batch K5: Check for updates ----
        Text("Check for Updates", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Current version: $APP_VERSION", color = c.bodyInk, fontSize = 12.sp)
        if (vm.latestVersionText.isNotEmpty()) {
            Text("Latest version: ${vm.latestVersionText}", color = c.bodyInk, fontSize = 12.sp)
        }
        if (vm.updateCheckResult.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                vm.updateCheckResult,
                color = if (vm.updateAvailable != null) ShiftOrange else c.bodyInk,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.checkUpdateNow() },
                enabled = !vm.updateCheckBusy,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ShiftOrange),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ShiftOrange,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(40.dp),
            ) { Text(if (vm.updateCheckBusy) "Checking…" else "Check Now", fontSize = 13.sp) }
            vm.updateAvailable?.let { info ->
                val ctx = LocalContext.current
                Button(
                    onClick = {
                        val url = info.apkUrl.ifEmpty { info.pageUrl }
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ShiftOrange,
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(40.dp),
                ) { Text("Download ${info.tag}", fontSize = 13.sp, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        ApiSettingsSection(vm)
    }
}

// ---------------------------------------------------------------------------
// Batch K5: New version update prompt dialog (shared between startup silent check and manual check)
// ---------------------------------------------------------------------------

@Composable
private fun UpdatePromptPanel(vm: CalcViewModel, info: ReleaseInfo) {
    val ctx = LocalContext.current
    val c = LocalCalcColors.current
    val sizeText = if (info.apkSize > 0) {
        " · " + String.format(java.util.Locale.US, "%.1f", info.apkSize / 1048576.0) + " MB"
    } else ""
    PanelCard(
        title = "New Version Available ${info.tag}",
        subtitle = "Current version $APP_VERSION",
        onClose = { vm.dismissUpdate() },
    ) {
        if (info.title.isNotEmpty()) {
            Text(info.title, color = c.chromeInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        val notes = info.notes.trim()
        if (notes.isNotEmpty()) {
            Text("Release Notes", color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 11.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                notes,
                color = c.keyNeutralInk,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
        } else {
            BodyText("You can view the release notes on the release page, or directly download the latest APK to install.")
        }
        MenuButton("Download APK$sizeText", hint = if (info.apkUrl.isEmpty()) "No APK asset in this release" else "Open direct link in browser to download and install") {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))) }
        }
        Spacer(Modifier.height(8.dp))
        MenuButton("Open Release Page", hint = "View full release notes and previous releases") {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl))) }
        }
        Spacer(Modifier.height(8.dp))
        MenuButton("Later") { vm.dismissUpdate() }
        Spacer(Modifier.height(8.dp))
        MenuButton("Skip This Version", hint = "Only prompt again when a newer version is released") { vm.skipUpdate() }
    }
}

// ---------------------------------------------------------------------------
// Batch E: "Solver API" section in settings panel (for photo solve)
// ---------------------------------------------------------------------------

@Composable
private fun ApiSettingsSection(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = c.keyEdge)
    Spacer(Modifier.height(14.dp))

    Text("Solver API (for Photo Solve)", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(
        "⚠️ API Key is stored locally in plaintext (SharedPreferences), unencrypted, and never uploaded to any server we control; " +
            "do not enter your key on public or shared devices.",
        color = c.lcdError, fontSize = 11.sp, lineHeight = 15.sp,
    )
    Spacer(Modifier.height(8.dp))

    ApiField("Base URL", vm.apiBaseUrl) { vm.updateApiBase(it) }
    ApiField("API Key", vm.apiKey, mask = true) { vm.updateApiKey(it) }
    ApiField("Model Name", vm.apiModel) { vm.updateApiModel(it) }
    ApiField("Timeout in seconds (${ApiConfig.MIN_TIMEOUT_SEC}–${ApiConfig.MAX_TIMEOUT_SEC})", vm.apiTimeoutText) {
        vm.updateApiTimeout(it)
    }

    Spacer(Modifier.height(2.dp))
    Text("Presets autofill (fills Base URL and Model Name; enter Key manually):",
        color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        API_PRESETS.take(2).forEach { p ->
            Button(
                onClick = { vm.applyApiPreset(p) },
                modifier = Modifier.weight(1f).height(38.dp),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, c.keyEdge),
                colors = ButtonDefaults.buttonColors(containerColor = c.keyNeutral, contentColor = c.bodyInk),
            ) { Text(p.name, fontSize = 12.sp, maxLines = 1) }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        API_PRESETS.drop(2).forEach { p ->
            Button(
                onClick = { vm.applyApiPreset(p) },
                modifier = Modifier.weight(1f).height(38.dp),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, c.keyEdge),
                colors = ButtonDefaults.buttonColors(containerColor = c.keyNeutral, contentColor = c.bodyInk),
            ) { Text(p.name, fontSize = 12.sp, maxLines = 1) }
        }
    }
    Spacer(Modifier.height(10.dp))

    Button(
        onClick = { vm.testApiConnection() },
        enabled = !vm.apiTestBusy,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.keyEquals, contentColor = c.keyEqualsInk),
        modifier = Modifier.fillMaxWidth().height(42.dp),
    ) { Text(if (vm.apiTestBusy) "Testing connection…" else "Test Connection", fontSize = 13.sp) }
    if (vm.apiTestResult.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        val ok = vm.apiTestResult.startsWith("Connection successful")
        Text(
            vm.apiTestResult,
            color = if (ok) c.keyNeutralInk else c.lcdError,
            fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ApiField(label: String, value: String, mask: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        visualTransformation = if (mask) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalCalcColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Button(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(9.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, c.keyEdge),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (sel) ShiftOrange else c.keyNeutral,
                    contentColor = if (sel) ShiftOrangeInk else c.bodyInk,
                ),
            ) { Text(o, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun Stepper(value: Int, onDec: () -> Unit, onInc: () -> Unit) {
    val c = LocalCalcColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepButton("\u2212", onDec)
        Text(
            "$value",
            color = c.bodyInk,
            fontFamily = Mono,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp),
        )
        StepButton("+", onInc)
    }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    val c = LocalCalcColors.current
    Button(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, c.keyEdge),
        colors = ButtonDefaults.buttonColors(containerColor = c.keyNeutral, contentColor = c.bodyInk),
    ) { Text(glyph, fontSize = 16.sp) }
}

// ---------------------------------------------------------------------------
// ① More / About (PRO) / Photo Instructions / History
// ---------------------------------------------------------------------------

@Composable
private fun MorePanel(vm: CalcViewModel) {
    PanelCard(title = "More", onClose = { vm.closeOverlay() }) {
        MenuButton("About / Version") { vm.openOverlay(Overlay.PRO) }
        MenuButton("Help & Guide") { vm.openOverlay(Overlay.HELP) }
        MenuButton("History (${vm.history.size} items)") { vm.openOverlay(Overlay.HISTORY) }
    }
}

@Composable
private fun ProPanel(vm: CalcViewModel) {
    PanelCard(title = "About App", subtitle = "PRO", onClose = { vm.closeOverlay() }) {
        BodyText("Name: Scientific Calculator (Natural Display LCD)")
        BodyText("Version: $APP_VERSION")
        BodyText("Build: Gradle 8.14 · AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.09.00")
        BodyText("Engine: Exact rational (BigInteger) + floating-point dual track; handwritten parser, RK4, and numerical algorithms")
        BodyText("Open Source: $APP_REPO")
        BodyText(
            "Unofficial Disclaimer: This application is a UI replica created for personal learning purposes, with no affiliation to any brand or manufacturer. " +
                "It does not use any manufacturer trademarks, logos, fonts, or official assets; all UI components and icons are drawn via code."
        )
    }
}

@Composable
private fun HistoryPanel(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    PanelCard(
        title = "History",
        subtitle = "Tap an item to recall into input (keeps up to 40 items)",
        onClose = { vm.closeOverlay() },
    ) {
        if (vm.history.isEmpty()) {
            BodyText("No history yet.")
        } else {
            vm.history.forEachIndexed { i, h ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.selectHistory(h.expr) },
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, c.keyEdge),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.keyNeutral,
                            contentColor = c.bodyInk,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("${i + 1}. ${h.expr}", fontFamily = Mono, fontSize = 12.sp, maxLines = 1)
                            Text("= ${h.result}", fontFamily = Mono, fontSize = 11.sp, maxLines = 1,
                                color = c.keyNeutralInk.copy(alpha = 0.7f))
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { vm.removeHistory(i) },
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, c.keyEdge),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = c.lcdError,
                        ),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(52.dp).height(44.dp),
                    ) { Text("Delete", fontSize = 11.sp, maxLines = 1) }
                }
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
private fun HelpPanel(vm: CalcViewModel) {
    PanelCard(title = "Help & Guide", onClose = { vm.closeOverlay() }) {
        BodyText("Variables: Independent variable in expressions is uniformly written as x (int / deriv / sum / lim / solve all recognize x).")
        BodyText("Batch K3: Function keys insert inline calls with parentheses directly on the main line instead of dialogs — cursor lands inside the parentheses to edit in 2D.")
        BodyText("∫dx: Inserts 2D integral template int(expr, lower, upper), displayed as ∫_lower^upper expr dx, e.g. int(x^2, 0, 1).")
        BodyText("d/dx (SHIFT layer of ∫dx): deriv(expr, xVal), e.g. deriv(sin(x), 1), using central difference + Richardson extrapolation.")
        BodyText("Σ (SHIFT layer of logₓy): sum(expr, lower, upper), e.g. sum(x, 1, 10).")
        BodyText("SOLVE (SHIFT layer of CALC): solve(eqn), enter equations directly inside parentheses, e.g. solve(2x+3=7); without '=' it solves f(x)=0.")
        BodyText("Limit (SHIFT layer of 9): lim(expr, x→), e.g. lim(sin(x)/x, 0); reports if left and right limits disagree.")
        BodyText("CALC: calc(expr) — Evaluates expression by substituting current x / y / variable values.")
        BodyText("°′″: Press once for degrees, twice for minutes, three times for seconds (e.g. 12°34′56″); dms(deg, min, sec) function is also available.")
        BodyText("hyp: Inserts sinh(); repeatedly pressing while cursor is at call site cycles sinh / cosh / tanh; write asinh( etc. for inverse.")
        BodyText("Polar coordinates: Enter directly with ∠, e.g. 2∠60 gives Cartesian 1 + 1.732050808i; press S⇔D to toggle back to r∠θ.")
        BodyText("Pol / Rec (SHIFT layer of + / −): pol(x, y) provides r and θ (toggle polar display with S⇔D); rec(r, θ) gives x + yi.")
        BodyText("STO (SHIFT layer of RCL): sto(expr, var) inline assignment, e.g. sto(5+3, A); variables A–F / x / y / M. CLRv (ALPHA layer of RCL) clears all variables; x⇄y (SHIFT layer of S⇔D) swaps x and y.")
        BodyText("Unified input surface (no mode switching needed): Write directly on main line: MatA×MatB, det(MatA), inv(MatA), trn(MatA), VctA·VctB, cross(VctA,VctB), abs(VctA), mean(1,2,3), sd(…), ssd(…), normcdf(0,1,1), binompdf(10,3,0.5), etc.; ∠ is a true operator supporting chained expressions (9∠60+5∠6).")
        BodyText("Main line solving: Press = with unknowns (x/y/z) and '=' to solve equations. E.g. 2x+3=7, x²-3x+2=0 (all roots including complex), sin(x)=0.5 (multi-point root scan), 2x+y=5, x-y=1 (systems of equations separated by ',' or ';'). No solution / infinitely many solutions are clearly indicated. Enter '=' via ALPHA + CALC.")
        BodyText("Equation mode (MODE menu): Polynomial equations degree 2/3/4 (including complex roots, exact when possible); simultaneous linear systems 2–4 unknowns (Gaussian elimination + exact fractions, reports no solution / infinite solutions). Tap a solution to insert into main line.")
        BodyText("Base-N conversion (MODE menu): DEC / HEX / BIN / OCT conversions, bitwise AND / OR / XOR / XNOR / NOT / NEG, word sizes 16 / 32 / 64-bit, two's complement for negatives.")
        BodyText("Function Table (MODE menu): Enter f(x) with start / end / step to generate table; supports optional g(x) comparison, 8 rows per page.")
        BodyText("Ratio (MODE menu): Solves a:b = c:x and a:b = x:d with exact fraction solutions.")
        BodyText("ENG (Engineering notation): Press to toggle exponent multiples of 3, press again to return to normal; number format can also be set in Settings.")
        BodyText("CONST / CONV / SI (SHIFT, ALPHA layers of 7 / 8): const(symbol) inline scientific constants (e.g. const(g), const(NA)); conv(val, from, to) unit conversion (e.g. conv(5, km, mi), temperatures as C / F / K); si(val, prefix) SI prefixes (si(1500, k) = 1.5).")
        BodyText("Ran# / RanInt (SHIFT / ALPHA layer of .): Ran# inserts 0–1 random float; ranint(lower, upper) random integer.")
        BodyText("COPY / PASTE (SHIFT / ALPHA layer of 0): Copy / paste main line expression; PreAns (ALPHA layer of Ans): previous answer; History (SHIFT layer of =): history page to recall / delete.")
        BodyText("CLR ALL (SHIFT layer of AC): Clear all (history / variables / M / settings) — shows confirmation dialog first; M− is on SHIFT layer of M+.")
        BodyText("Integers and rounding: gcd( / lcm( (ALPHA layer of × / ÷) · ceil( / floor( (ALPHA layer of + / −) · mod( (ALPHA layer of √x); Cot / Cot⁻¹ on ALPHA layer of ENG / (; imaginary unit i on SHIFT layer of ENG.")
        BodyText("Function syntax: logb(base, antilog) · root(n, x) · cbrt(x) · abs(x) · npr(n,r) · ncr(n,r) · sinh/cosh/tanh & asinh/acosh/atanh · 10^ · exp(")
        BodyText("SHIFT layer: Orange text above keys; 2nd is equivalent to SHIFT. ALPHA layer is purple text.")
    }
}

// ---------------------------------------------------------------------------
// ③ Batch B: STO / CONST / CONV / SI / CLR ALL
// ---------------------------------------------------------------------------

@Composable
private fun StoPanel(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    PanelCard(
        title = "Store Variable STO",
        subtitle = "Store current result or insert variable into expression",
        onClose = { vm.closeOverlay() },
    ) {
        val now = vm.resultText.ifEmpty { vm.previewText.ifEmpty { "(empty)" } }
        BodyText("Current result: $now")
        if (vm.storeMessage.isNotEmpty()) {
            Text(vm.storeMessage, color = c.keyEquals, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        }
        listOf("A", "B", "C", "D", "E", "F", "x", "y", "M").forEach { name ->
            val stored = vm.variables[name]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, fontFamily = Mono, color = c.bodyInk, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp),
                )
                Text(
                    if (stored != null) vm.formatNumber(stored) else "—",
                    fontFamily = Mono, color = c.keyNeutralInk.copy(alpha = 0.7f),
                    fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.storeVariable(name) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, c.keyEdge),
                    colors = ButtonDefaults.buttonColors(containerColor = c.keyNeutral, contentColor = c.bodyInk),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(56.dp).height(38.dp),
                ) { Text("Store", fontSize = 11.sp) }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { vm.insertVariable(name) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, c.keyEdge),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent, contentColor = ShiftOrange,
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(56.dp).height(38.dp),
                ) { Text("Insert", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(7.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "M is the memory register (shared with M+ / M− / MRC); A–F and x / y can be directly referenced in expressions.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
        )
    }
}

@Composable
private fun ConstPanel(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    PanelCard(
        title = "Scientific Constants CONST",
        subtitle = "${SciConstants.COUNT} constants in total; tap to insert into expression",
        onClose = { vm.closeOverlay() },
    ) {
        SciConstants.ALL.forEach { k ->
            Button(
                onClick = { vm.insertConstant(k.value) },
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, c.keyEdge),
                colors = ButtonDefaults.buttonColors(containerColor = c.keyNeutral, contentColor = c.bodyInk),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text("${k.symbol}   ${k.name}", fontSize = 12.sp, maxLines = 1)
                    Text(
                        "${CalcEngineFmt(k.value)}  ${k.unit}",
                        fontFamily = Mono, fontSize = 11.sp, maxLines = 1,
                        color = c.keyNeutralInk.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}

/** Formats numbers for display in constants table (using up to 10 significant digits) */
private fun CalcEngineFmt(v: Double): String = io.paimon.fx991.engine.CalcEngine.format(v, 10, null)

@Composable
private fun UnitConvPanel(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    var catIdx by remember { mutableStateOf(0) }
    var fromIdx by remember { mutableStateOf(0) }
    var toIdx by remember { mutableStateOf(1) }
    var value by remember { mutableStateOf("1") }

    val cat = UnitConvert.CATEGORIES[catIdx]
    PanelCard(
        title = "Unit Conversion CONV",
        subtitle = "Length / Mass / Time / Area / Volume / Temperature",
        onClose = { vm.closeOverlay() },
    ) {
        Text("Category", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChoiceChips(
            options = UnitConvert.CATEGORIES.map { it.name },
            selected = catIdx,
            onSelect = {
                catIdx = it
                fromIdx = 0
                toIdx = 1
            },
        )
        Spacer(Modifier.height(12.dp))
        Text("From Unit", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChipWrap(cat.units.map { "${it.name} ${it.symbol}" }, fromIdx) { fromIdx = it }
        Text("To Unit", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChipWrap(cat.units.map { "${it.name} ${it.symbol}" }, toIdx) { toIdx = it }
        Spacer(Modifier.height(8.dp))
        NumField("Value", value, { value = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        val parsed = value.trim().toDoubleOrNull()
        if (parsed == null) {
            Text("Please enter a valid number", color = c.lcdError, fontSize = 13.sp)
        } else {
            val r = UnitConvert.convert(cat, fromIdx, toIdx, parsed)
            Text(
                "= ${vm.formatNumber(r)} ${cat.units[toIdx].symbol}",
                color = c.bodyInk, fontFamily = Mono, fontSize = 16.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Temperature is an affine conversion (°C / °F / K non-linear offsets) and is handled separately.",
            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
        )
    }
}

@Composable
private fun SiPanel(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    var fromIdx by remember { mutableStateOf(SiPrefixes.NONE_INDEX) }
    var toIdx by remember { mutableStateOf(SiPrefixes.ALL.indexOfFirst { it.factor == 1e3 }.coerceAtLeast(0)) }
    var value by remember { mutableStateOf("1") }

    PanelCard(
        title = "SI Prefix Conversion",
        subtitle = "p / n / μ / m / c / k / M / G …",
        onClose = { vm.closeOverlay() },
    ) {
        Text("From Prefix", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChipWrap(SiPrefixes.ALL.map { it.symbol.ifEmpty { "—" } }, fromIdx) { fromIdx = it }
        Text("To Prefix", color = c.chromeInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        ChipWrap(SiPrefixes.ALL.map { it.symbol.ifEmpty { "—" } }, toIdx) { toIdx = it }
        Spacer(Modifier.height(8.dp))
        NumField("Value", value, { value = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        val parsed = value.trim().toDoubleOrNull()
        if (parsed == null) {
            Text("Please enter a valid number", color = c.lcdError, fontSize = 13.sp)
        } else {
            val r = SiPrefixes.convert(fromIdx, toIdx, parsed)
            val f = SiPrefixes.ALL[fromIdx]
            val t = SiPrefixes.ALL[toIdx]
            Text(
                "= ${vm.formatNumber(r)}    (${f.symbol.ifEmpty { "None" }} → ${t.symbol.ifEmpty { "None" }})",
                color = c.bodyInk, fontFamily = Mono, fontSize = 15.sp,
            )
        }
    }
}

/** Multi-row chip wrap (for units / prefixes) */
@Composable
private fun ChipWrap(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalCalcColors.current
    Column(Modifier.fillMaxWidth()) {
        options.chunked(5).forEachIndexed { rowIdx, chunk ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                chunk.forEachIndexed { i, o ->
                    val idx = rowIdx * 5 + i
                    val sel = idx == selected
                    Button(
                        onClick = { onSelect(idx) },
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        border = BorderStroke(1.dp, c.keyEdge),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sel) ShiftOrange else c.keyNeutral,
                            contentColor = if (sel) ShiftOrangeInk else c.bodyInk,
                        ),
                    ) { Text(o, fontSize = 10.5.sp, maxLines = 1) }
                }
                repeat(5 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
private fun ClearAllConfirmPanel(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    PanelCard(
        title = "Clear All CLR ALL",
        subtitle = "This action cannot be undone",
        onClose = { vm.closeOverlay() },
    ) {
        BodyText("The following items will be cleared:")
        BodyText("· Current expression and result")
        BodyText("· All calculation history (${vm.history.size} items)")
        BodyText("· Stored variables A–F / x / y")
        BodyText("· Memory register M")
        BodyText("· All settings (angle unit / precision / fraction display / number notation / theme / vibration)")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { vm.closeOverlay() },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, c.keyEdge),
                colors = ButtonDefaults.buttonColors(containerColor = c.keyNeutral, contentColor = c.bodyInk),
            ) { Text("Cancel", fontSize = 13.sp) }
            Button(
                onClick = { vm.doClearAll() },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.keyAccent, contentColor = c.keyAccentInk,
                ),
            ) { Text("Confirm Clear", fontSize = 13.sp) }
        }
    }
}

// ---------------------------------------------------------------------------
// ② Numerical function dialogs (∫dx / d/dx / Σ / SOLVE / Limit / CALC / °′″ / hyp / Pol / Rec / RanInt)
// ---------------------------------------------------------------------------

@Composable
private fun FuncPanel(vm: CalcViewModel) {
    val d = vm.funcDialog ?: return
    val c = LocalCalcColors.current
    PanelCard(title = d.kind.title, onClose = { vm.closeOverlay() }) {
        if (d.kind == FuncKind.HYPER) {
            BodyText("Select a hyperbolic function to insert into the expression (inverses included):")
            listOf("sinh", "cosh", "tanh", "asinh", "acosh", "atanh").forEach { name ->
                MenuButton(label = "$name(", hint = "Insert") { vm.insertHyper(name) }
            }
            return@PanelCard
        }
        val labels = d.fields
        if (labels.isNotEmpty()) FuncField(labels[0], d.f) { d.f = it }
        if (labels.size > 1) FuncField(labels[1], d.a) { d.a = it }
        if (labels.size > 2) FuncField(labels[2], d.b) { d.b = it }
        if (labels.size > 3) FuncField(labels[3], d.c) { d.c = it }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { vm.runFunc() },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.keyEquals, contentColor = c.keyEqualsInk),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text(if (d.kind == FuncKind.DMS) "Convert" else "Calculate", fontSize = 14.sp) }

        Spacer(Modifier.height(10.dp))
        if (d.error.isNotEmpty()) {
            Text(d.error, color = c.lcdError, fontSize = 13.sp)
        } else if (d.result.isNotEmpty()) {
            Text(d.result, color = c.bodyInk, fontFamily = Mono, fontSize = 16.sp)
        }
    }
}

@Composable
private fun FuncField(label: String, value: String, onValueChange: (String) -> Unit) {
    // Batch K3-B: Function dialog fields also use natural display inputs (custom keypad + cursor)
    NumField(label, value, onValueChange, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
}

// ---------------------------------------------------------------------------
// Top bar: Menu · PRO · Σ · Settings · ± · Camera | MORE · DEG
// ---------------------------------------------------------------------------

@Composable
private fun UtilityBar(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    val click = rememberKeyClick(vm)
    val items = remember(vm.angleMode) { utilityBar(vm.angleMode) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SafeArea.UTILITY_BAR_DP.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { k ->
            val isPill = k.kind == KeyKind.PRO || k.kind == KeyKind.MORE
            val container = when (k.kind) {
                KeyKind.PRO -> c.chromeActive
                KeyKind.MORE -> Color.Transparent
                else -> c.keyNeutral
            }
            val ink = when (k.kind) {
                KeyKind.PRO -> c.keyShiftInk
                KeyKind.MORE -> ShiftOrange
                else -> c.chromeInk
            }
            Button(
                onClick = { click(k) },
                modifier = Modifier
                    .weight(if (isPill) 1.6f else 1f)
                    .fillMaxHeight(),
                shape = if (isPill) RoundedCornerShape(50) else CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = ink,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (isPill) 0.dp else 1.dp,
                    pressedElevation = 3.dp,
                ),
                contentPadding = PaddingValues(0.dp),
                border = if (k.kind == KeyKind.MORE) BorderStroke(1.dp, ShiftOrange) else null,
            ) {
                if (k.icon != KeyIcon.NONE) {
                    KeyGlyph(k.icon, ink, 16.dp)
                } else {
                    Text(
                        text = k.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// LCD Display Area
// ---------------------------------------------------------------------------

@Composable
private fun LcdDeck(vm: CalcViewModel, modifier: Modifier) {
    val c = LocalCalcColors.current
    Box(
        modifier
            .background(c.bodyEdge)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        LcdScreen(vm, Modifier.fillMaxSize())
    }
}

@Composable
private fun LcdScreen(vm: CalcViewModel, modifier: Modifier) {
    val c = LocalCalcColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(c.lcdBg)
            .border(1.dp, c.lcdEdge, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        StatusBar(vm)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(vm.expression) {
                    detectTapGestures { off ->
                        if (size.width > 0) vm.tapToCursor(off.x / size.width)
                    }
                },
            contentAlignment = Alignment.TopStart,
        ) {
            // Batch K3-A: Blinking cursor during editing; cursor hidden after evaluation (viewing results)
            // K6: Input starts at top-left by default (previously bottom-right)
            LcdMathLine(
                expr = vm.expression,
                style = NatStyle(lcdFont(vm.expression.length), c.lcdFg),
                modifier = Modifier.fillMaxSize(),
                cursor = if (vm.justEvaluated || vm.overlay != Overlay.NONE) -1 else vm.cursor,
                cursorExit = if (vm.justEvaluated || vm.overlay != Overlay.NONE) 0 else vm.cursorExit,
                align = Alignment.TopStart,
                rowAlign = Alignment.Top,
                initialScroll = 0,
            )
        }
        Spacer(Modifier.height(2.dp))
        ResultLine(vm)
    }
}

private fun lcdFont(len: Int) = when {
    len <= 14 -> 25.sp
    len <= 26 -> 20.sp
    len <= 44 -> 16.5.sp
    else -> 13.5.sp
}

@Composable
private fun StatusBar(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusChip("S", c, vm.shiftActive)
            StatusChip("A", c, vm.alphaActive)
            StatusChip("STO", c, vm.stoActive)
            StatusChip("RCL", c, vm.rclActive)
            StatusChip("M", c, vm.memorySet)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            StatusChip(
                if (vm.settings.precision == PrecisionMode.DECIMALS) "FIX-${vm.settings.decimals}"
                else "NORM-${vm.settings.sigDigits}",
                c, true,
            )
            StatusChip(vm.angleMode.label, c, true)
            StatusChip("MATH", c, true)
            if (vm.settings.notation != NumberNotation.NORM) {
                StatusChip(vm.settings.notation.shortLabel, c, true)
            }
            StatusChip(vm.displayMode.shortLabel, c, true)
            // Input redesign: Undo / Redo (single tap trigger, no long-press)
            Text(
                "↶",
                color = if (vm.canUndo) c.lcdFg else c.lcdDim.copy(alpha = 0.28f),
                fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(enabled = vm.canUndo) { vm.undoEdit() }
                    .padding(horizontal = 2.dp, vertical = 1.dp),
            )
            Text(
                "↷",
                color = if (vm.canRedo) c.lcdFg else c.lcdDim.copy(alpha = 0.28f),
                fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(enabled = vm.canRedo) { vm.redoEdit() }
                    .padding(horizontal = 2.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, c: CalcColors, on: Boolean) {
    Text(
        text = text,
        color = if (on) c.lcdFg else c.lcdDim.copy(alpha = 0.45f),
        fontFamily = Mono,
        fontSize = 9.5.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ResultLine(vm: CalcViewModel) {
    val c = LocalCalcColors.current
    val showResult = vm.resultText.isNotEmpty()
    val text = if (showResult) vm.resultText else vm.previewText
    val color = when {
        vm.isError -> c.lcdError
        showResult -> c.lcdFg
        else -> c.lcdDim
    }
    Column(Modifier.fillMaxWidth()) {
        // Multi-solution equations: Individually insertable (REFERENCE item 7)
        if (vm.solutionItems.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                vm.solutionItems.forEach { s ->
                    val ins = s.insert
                    Button(
                        onClick = { ins?.let { vm.insertSolution(it) } },
                        enabled = !ins.isNullOrEmpty(),
                        shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        border = BorderStroke(1.dp, c.lcdEdge),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = c.lcdFg,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = c.lcdDim.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.height(26.dp),
                    ) { Text(s.label, fontFamily = Mono, fontSize = 10.5.sp, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.BottomStart) {
            if (text.contains('\n')) {
                // Matrix result: multi-line plain text
                Text(
                    text,
                    color = color,
                    fontFamily = Mono,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LcdMathLine(
                    expr = text,
                    style = NatStyle(resultFont(text.length), color, bold = showResult && !vm.isError),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (vm.resultNote.isNotEmpty()) {
            Text(vm.resultNote, color = c.lcdDim, fontSize = 9.5.sp, maxLines = 2)
        }
    }
}

private fun resultFont(len: Int) = when {
    len <= 12 -> 30.sp
    len <= 22 -> 24.sp
    else -> 19.sp
}

// ---------------------------------------------------------------------------
// Keypad deck: 9 rows, each key is a Material button
// ---------------------------------------------------------------------------

@Composable
private fun KeypadDeck(vm: CalcViewModel, modifier: Modifier, rowHeight: Dp? = null) {
    val rows = remember { keypadRows() }
    val click = rememberKeyClick(vm)
    Column(modifier.padding(start = 4.dp, end = 4.dp, top = 3.dp, bottom = 5.dp)) {
        // ---- Rows 1 & 2 merged: D-Pad spans two rows, four function keys flanking left and right ----
        if (rows.size >= 2) {
            val top = rows[0]
            val bottom = rows[1]
            val topSides = top.filter { it.kind != KeyKind.NAV }
            val leftTop = topSides.take(2)          // SHIFT / ALPHA
            val rightTop = topSides.drop(2)         // MODE / 2nd
            val leftBottom = bottom.take(2)         // CALC / ∫dx
            val rightBottom = bottom.drop(2)        // x⁻¹ / logₓy
            val blockModifier = if (rowHeight != null) {
                Modifier.fillMaxWidth().height(rowHeight * 2f)
            } else {
                Modifier.fillMaxWidth().weight(2f)
            }
            Row(blockModifier) {
                Column(Modifier.weight(2f).fillMaxHeight()) {
                    Row(Modifier.weight(1f)) { leftTop.forEach { KeyCell(it, click) } }
                    Row(Modifier.weight(1f)) { leftBottom.forEach { KeyCell(it, click) } }
                }
                DPad(
                    click = click,
                    // Height doubled, width adjusted to 2.2 units, nearly square overall
                    modifier = Modifier.weight(2.2f).fillMaxHeight().padding(2.dp),
                )
                Column(Modifier.weight(2f).fillMaxHeight()) {
                    Row(Modifier.weight(1f)) { rightTop.forEach { KeyCell(it, click) } }
                    Row(Modifier.weight(1f)) { rightBottom.forEach { KeyCell(it, click) } }
                }
            }
        }
        // ---- Remaining rows as usual ----
        rows.drop(2).forEach { row ->
            val rowModifier = if (rowHeight != null) {
                Modifier.fillMaxWidth().height(rowHeight)
            } else {
                Modifier.fillMaxWidth().weight(1f)
            }
            Row(rowModifier) {
                row.forEach { key -> KeyCell(key, click) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyCell(key: Key, click: (Key) -> Unit) {
    KeyCapButton(
        key = key,
        modifier = Modifier.weight(key.weight).fillMaxHeight().padding(2.dp),
        onClick = { click(key) },
        onHold = key.hold?.let { h -> { click(Key(key.label, key.kind, h.action)) } },
    )
}

@Composable
private fun keyFace(kind: KeyKind, c: CalcColors): Pair<Color, Color> {
    // MD3: Key surfaces layered by tonal surface, distinguished by tonal contrast rather than borders
    val m = MaterialTheme.colorScheme
    return when (kind) {
        KeyKind.SHIFT -> c.keyShift to c.keyShiftInk
        KeyKind.ALPHA -> c.keyAlpha to c.keyAlphaInk
        KeyKind.DANGER -> c.keyAccent to c.keyAccentInk          // Preserves orange-red accent color
        KeyKind.DIGIT -> m.surfaceContainerLowest to m.onSurface  // Brightest = digits
        KeyKind.OP -> m.surfaceContainer to m.onSurface
        KeyKind.FUNC -> m.surfaceContainerHigh to m.onSurface
        KeyKind.EQUALS -> m.inverseSurface to m.inverseOnSurface  // Inverse surface = execute key
        KeyKind.NAV -> m.surfaceContainer to m.onSurface
        else -> m.surfaceContainerHigh to m.onSurface
    }
}

@Composable
private fun KeyCapButton(
    key: Key,
    modifier: Modifier,
    onClick: () -> Unit,
    onHold: (() -> Unit)? = null,
) {
    val c = LocalCalcColors.current
    val (bg, fg) = keyFace(key.kind, c)
    val shape = RoundedCornerShape(14.dp)          // MD3 medium shape
    var pressed by remember { mutableStateOf(false) }
    var holdFired by remember { mutableStateOf(false) }
    val repeatable = key.action.isRepeatable()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "keyScale")

    // Long-press layer: secondary key -> 420ms triggers hold action; otherwise DEL / D-Pad key repeating
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        when {
            onHold != null -> {
                delay(420)
                if (pressed) {
                    holdFired = true
                    onHold()
                }
            }
            repeatable -> {
                delay(380)
                while (pressed) {
                    onClick()
                    delay(62)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (pressed) lerp(bg, fg, 0.12f) else bg)   // MD3 state layer
            .border(0.5.dp, c.keyEdge, shape)
            .pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    holdFired = false
                    if (onHold == null) onClick()
                    waitForUpOrCancellation()
                    pressed = false
                    if (onHold != null && !holdFired) onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (key.shift != null || key.alpha != null) {
                // Batch K4: Orange SHIFT text on orange keys (DANGER) blends in; use key ink to ensure readability
                val shiftInk = if (key.kind == KeyKind.DANGER) fg else ShiftOrange
                val alphaInk = if (key.kind == KeyKind.DANGER) fg else AlphaPurple
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    key.shift?.let {
                        Text(it.label, color = shiftInk, fontSize = 8.5.sp, maxLines = 1,
                            fontWeight = FontWeight.Bold)
                    }
                    key.alpha?.let {
                        Text(it.label, color = alphaInk, fontSize = 8.5.sp, maxLines = 1,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (key.icon != KeyIcon.NONE) {
                KeyGlyph(key.icon, fg, 19.dp)
            } else {
                Text(
                    text = key.label,
                    color = fg,
                    fontFamily = Mono,
                    fontSize = keyFontSize(key.label.length) * key.labelScale,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Hold indicator dot removed: no small gray dot on keycap to keep face clean
    }
}

private fun KeyAction.isRepeatable(): Boolean =
    this is KeyAction.Del || this is KeyAction.PadLeft || this is KeyAction.PadRight ||
        this is KeyAction.PadUp || this is KeyAction.PadDown

private fun keyFontSize(len: Int) = when {
    len <= 1 -> 20.sp
    len <= 2 -> 17.sp
    len <= 3 -> 14.5.sp
    len <= 4 -> 12.sp
    else -> 11.sp
}

// ---------------------------------------------------------------------------
// 5-way D-Pad (with center OK button)
// ---------------------------------------------------------------------------

@Composable
private fun DPad(click: (Key) -> Unit, modifier: Modifier) {
    val c = LocalCalcColors.current
    Surface(
        modifier = modifier,
        // Unified with surrounding keys: MD3 medium rounded corners + uniform border, no elevation shadow
        shape = RoundedCornerShape(14.dp),
        color = c.keyNeutral,
        border = BorderStroke(0.5.dp, c.keyEdge),
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(1f))
                PadKey("\u25B2", c, Modifier.weight(1f), KeyAction.PadUp, click)
                Box(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                PadKey("\u25C0", c, Modifier.weight(1f), KeyAction.PadLeft, click)
                PadKey("\u25CF", c, Modifier.weight(1f), KeyAction.PadOk, click, center = true)
                PadKey("\u25B6", c, Modifier.weight(1f), KeyAction.PadRight, click)
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(1f))
                PadKey("\u25BC", c, Modifier.weight(1f), KeyAction.PadDown, click)
                Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PadKey(
    glyph: String,
    c: CalcColors,
    modifier: Modifier,
    action: KeyAction,
    click: (Key) -> Unit,
    center: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed || !action.isRepeatable()) return@LaunchedEffect
        delay(380)
        while (pressed) {
            click(Key(glyph, KeyKind.NAV, action))
            delay(62)
        }
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(1.dp)
            .clip(CircleShape)
            .background(
                when {
                    pressed && center -> lerp(c.keyDigit, Color.Black, 0.14f)
                    pressed -> c.keyEdge.copy(alpha = 0.55f)
                    center -> c.keyDigit
                    else -> Color.Transparent
                }
            )
            .pointerInput(action) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    click(Key(glyph, KeyKind.NAV, action))
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = if (center) 16.sp else 15.sp, color = c.keyNeutralInk)
    }
}

// ---------------------------------------------------------------------------
// Action dispatch: SHIFT/ALPHA layers take precedence, rest to ViewModel (all keys have true actions, no placeholders)
// ---------------------------------------------------------------------------

@Composable
private fun rememberKeyClick(vm: CalcViewModel): (Key) -> Unit {
    val haptics = LocalHapticFeedback.current
    val vibrate = vm.settings.vibration
    // Vibrate periodically during repeating long-press, avoiding numbness at 16 times/sec
    var lastHaptic by remember { mutableStateOf(0L) }
    return { key ->
        val action = when {
            vm.alphaActive && key.alpha != null -> key.alpha!!.action
            vm.shiftActive && key.shift != null -> key.shift!!.action
            else -> key.action
        }
        val now = System.currentTimeMillis()
        if (vibrate && now - lastHaptic > 120) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastHaptic = now
        }
        vm.onAction(action)
    }
}
