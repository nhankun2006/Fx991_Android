package io.paimon.fx991.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.paimon.fx991.CalcViewModel
import io.paimon.fx991.Overlay
import io.paimon.fx991.Screen
import io.paimon.fx991.engine.PhotoApiException
import io.paimon.fx991.engine.PhotoHttpException
import io.paimon.fx991.engine.PhotoNet
import io.paimon.fx991.engine.PhotoSolve
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Photo Solve Screen (Batch E: API vision model; Batch F: Added local offline OCR path)
//
// Both paths coexist with clear UI labels:
//   Local Recognition (Offline · Free) — Default. ML Kit bundled model runs locally on device;
//       recognized text is shown to the user for confirmation (editable) before evaluating.
//   API Recognition (Higher accuracy · Requires setup) — Batch E vision model fallback path.
//
// Flow (Local): Capture from camera/gallery → Compress (≤1600px) → ML Kit offline OCR →
//   Clean and merge text (via PhotoSolve.normalizeExpr) → User confirmation →
//   Insert into main line and evaluateNow().
// Flow (API): Capture → Compress → base64 → POST {base}/chat/completions →
//   Parse <EXPR>/<RESULT>/<EXPLAIN> → Populate main line and evaluate.
// ---------------------------------------------------------------------------

private enum class PhotoPhase { IDLE, BUSY, ERROR, CONFIRM, DONE }
private enum class SolveMode { LOCAL, API }

@Composable
fun PhotoSolveScreen(vm: CalcViewModel, onBack: () -> Unit) {
    val c = LocalCalcColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(SolveMode.LOCAL) }
    var phase by remember { mutableStateOf(PhotoPhase.IDLE) }
    var prepared by remember { mutableStateOf<PhotoImage.Prepared?>(null) }
    var message by remember { mutableStateOf("") }
    var solvedExpr by remember { mutableStateOf("") }
    var solvedResult by remember { mutableStateOf("") }
    var solvedExplain by remember { mutableStateOf("") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    // Intermediate results for local recognition
    var localRaw by remember { mutableStateOf("") }
    var localModel by remember { mutableStateOf("") }
    var confirmText by remember { mutableStateOf("") }

    fun fail(t: Throwable) {
        message = when (t) {
            is PhotoHttpException -> t.message ?: "HTTP ${t.code}"
            is PhotoApiException -> t.message ?: "Request failed"
            is PhotoImage.PhotoImageError -> t.message ?: "Image processing failed"
            is MlKitOcr.MlKitOcrError -> t.message ?: "Local recognition failed"
            else -> "Unexpected error: ${t.message ?: t.javaClass.simpleName}"
        }
        phase = PhotoPhase.ERROR
    }

    /** API path: send vision model request (Batch E original pipeline) */
    fun solveWithApi(p: PhotoImage.Prepared) {
        phase = PhotoPhase.BUSY
        message = ""
        scope.launch {
            try {
                val cfg = vm.apiConfig()
                // Network + JSON parsing on IO thread
                val content = withContext(Dispatchers.IO) { PhotoNet.solve(cfg, p.dataUri) }
                when (val r = PhotoSolve.parseReply(content)) {
                    is PhotoSolve.Reply.Solved -> {
                        solvedExpr = r.expr
                        solvedResult = r.result
                        solvedExplain = r.explain
                        // Insert directly into main line and evaluate; explanation goes to note
                        val note = buildString {
                            if (r.result.isNotBlank()) append("Model calculation: ").append(r.result)
                            if (r.explain.isNotBlank()) {
                                if (isNotEmpty()) append("; ")
                                append(r.explain)
                            }
                        }
                        vm.applyPhotoExpr(r.expr, note)
                        phase = PhotoPhase.DONE
                    }
                    PhotoSolve.Reply.NoFormula -> {
                        message = "Could not detect a solvable expression in this image. Please take a clear, well-lit photo facing the equation and try again."
                        phase = PhotoPhase.ERROR
                    }
                    is PhotoSolve.Reply.BadFormat -> {
                        message = r.detail + "\n(Try another model or take a clearer photo)"
                        phase = PhotoPhase.ERROR
                    }
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    /** Local path: ML Kit offline recognition → text merge & clean → user confirmation */
    fun solveLocally(p: PhotoImage.Prepared) {
        phase = PhotoPhase.BUSY
        message = ""
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { MlKitOcr.recognize(p.bitmap) }
                localRaw = r.rawText
                localModel = r.model
                confirmText = r.cleaned
                phase = PhotoPhase.CONFIRM
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun processUri(uri: Uri) {
        phase = PhotoPhase.BUSY
        message = ""
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { PhotoImage.prepare(ctx.contentResolver, uri) }
                prepared = p
                if (mode == SolveMode.LOCAL) solveLocally(p) else solveWithApi(p)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    // Gallery (Batch K3): Use system Photo Picker (PickVisualMedia).
    // Legacy GetContent(ACTION_GET_CONTENT) fails to list HEIC on some devices;
    // Photo Picker supports HEIC/HEIF/AVIF natively; older devices without Photo Picker
    // automatically fall back to ACTION_OPEN_DOCUMENT via androidx.
    // Does not request storage permissions.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) processUri(uri) }

    // Camera: TakePicture = MediaStore.ACTION_IMAGE_CAPTURE + FileProvider, does not request CAMERA permission
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val u = pendingCameraUri
        if (ok && u != null) processUri(u) else if (!ok) phase = PhotoPhase.IDLE
    }

    fun launchCamera() {
        try {
            val dir = File(ctx.cacheDir, "photo").apply { mkdirs() }
            val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (t: Throwable) {
            message = "Cannot launch system camera: ${t.message ?: "Please select from gallery instead"}"
            phase = PhotoPhase.ERROR
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.body)
            .safeAreaPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            // Top bar: Back + Title
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, c.keyEdge),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.keyNeutral, contentColor = c.bodyInk,
                    ),
                    modifier = Modifier.height(38.dp),
                ) { Text("← Back", fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                Text("Photo Solve", color = c.bodyInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(38.dp))
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // ---- Recognition path choice: both paths coexist with clear labels ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { mode = SolveMode.LOCAL },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp, if (mode == SolveMode.LOCAL) ShiftOrange else c.keyEdge,
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == SolveMode.LOCAL) ShiftOrange else c.keyNeutral,
                            contentColor = if (mode == SolveMode.LOCAL) ShiftOrangeInk else c.bodyInk,
                        ),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Local Recognition\nOffline · Free", fontSize = 12.5.sp, lineHeight = 16.sp) }
                    Button(
                        onClick = { mode = SolveMode.API },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp, if (mode == SolveMode.API) ShiftOrange else c.keyEdge,
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == SolveMode.API) ShiftOrange else c.keyNeutral,
                            contentColor = if (mode == SolveMode.API) ShiftOrangeInk else c.bodyInk,
                        ),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("API Recognition\nAccurate · Needs Setup", fontSize = 12.5.sp, lineHeight = 16.sp) }
                }
                Spacer(Modifier.height(10.dp))

                // ---- API path not configured: state clearly ----
                if (mode == SolveMode.API && !vm.apiReady()) {
                    Text(
                        "Solver API is not configured yet. This path is unavailable (Local recognition works directly without setup).",
                        color = c.lcdError, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "API recognition sends photos to a vision model service compatible with OpenAI's chat/completions API. " +
                            "Please configure Base URL, API Key, and Model in Settings (presets available).",
                        color = c.keyNeutralInk, fontSize = 12.5.sp, lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            vm.goto(Screen.CALC)
                            vm.openOverlay(Overlay.SETTINGS)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ShiftOrange, contentColor = ShiftOrangeInk,
                        ),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) { Text("Go to Settings to configure API", fontSize = 14.sp) }
                    Spacer(Modifier.height(16.dp))
                    return@Column
                }

                // API path: current configuration summary (masked Key)
                if (mode == SolveMode.API) {
                    Text(
                        "Current service: ${vm.apiBaseUrl.trim()} | Model: ${vm.apiModel.trim()} | " +
                            "Key: ${maskKey(vm.apiKey.trim())} (stored locally)",
                        color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // Image input entry points (shared by both paths)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { launchCamera() },
                        enabled = phase != PhotoPhase.BUSY,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.keyNeutral, contentColor = c.bodyInk,
                        ),
                        border = BorderStroke(1.dp, c.keyEdge),
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KeyGlyph(KeyIcon.CAMERA, c.bodyInk, 22.dp)
                            Text("Camera", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = phase != PhotoPhase.BUSY,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.keyNeutral, contentColor = c.bodyInk,
                        ),
                        border = BorderStroke(1.dp, c.keyEdge),
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KeyGlyph(KeyIcon.GALLERY, c.bodyInk, 22.dp)
                            Text("Gallery", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Image preview + compression info
                prepared?.let { p ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            bitmap = p.bitmap.asImageBitmap(),
                            contentDescription = "Image for recognition",
                            modifier = Modifier
                                .height(110.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, c.keyEdge, RoundedCornerShape(8.dp)),
                        )
                        Column {
                            Text(
                                "Original ${p.origWidth}×${p.origHeight} → Compressed ${p.width}×${p.height}",
                                color = c.keyNeutralInk, fontSize = 11.sp,
                            )
                            Text(
                                "JPEG ${p.jpeg.size / 1024} KB (base64 ~${PhotoSolve.base64Length(p.jpeg.size) / 1024} KB)",
                                color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 11.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                when (phase) {
                    PhotoPhase.BUSY -> Text(
                        when {
                            prepared == null -> "Processing image…"
                            mode == SolveMode.LOCAL -> "Running offline recognition (${
                                if (localModel.isBlank()) "ML Kit" else localModel
                            })…"
                            else -> "Recognizing and solving (${vm.apiModel.trim()})…"
                        },
                        color = c.keyNeutralInk, fontSize = 13.sp,
                    )
                    PhotoPhase.ERROR -> {
                        Text(message, color = c.lcdError, fontSize = 12.5.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tap \"Camera / Gallery\" above to pick another image.",
                            color = c.keyNeutralInk.copy(alpha = 0.65f), fontSize = 11.sp,
                        )
                    }
                    PhotoPhase.CONFIRM -> {
                        // Local recognition: user verification before evaluation
                        Text(
                            "Recognition result ($localModel), please review before evaluating:",
                            color = c.bodyInk, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        // Batch K3-B: result confirmation via natural input field
                        NumField("Expression (edit before evaluating)", confirmText, { confirmText = it }, Modifier.fillMaxWidth())
                        if (localRaw.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.lcdBg)
                                    .border(1.dp, c.lcdEdge, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                            ) {
                                Text(
                                    "Raw OCR text: ${localRaw.trim()}",
                                    color = c.lcdDim, fontSize = 11.5.sp, lineHeight = 16.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val expr = confirmText.trim()
                                if (expr.isEmpty()) {
                                    message = "Expression is empty, please enter one or select another image."
                                    phase = PhotoPhase.ERROR
                                    return@Button
                                }
                                solvedExpr = expr
                                solvedResult = ""
                                solvedExplain = ""
                                vm.applyPhotoExpr(expr, "Local OCR (ML Kit · $localModel)")
                                phase = PhotoPhase.DONE
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.keyEquals, contentColor = c.keyEqualsInk,
                            ),
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        ) { Text("Confirm: Insert & Evaluate", fontSize = 14.sp) }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                phase = PhotoPhase.IDLE
                                prepared = null
                                localRaw = ""
                                confirmText = ""
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, c.keyEdge),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.keyNeutral, contentColor = c.bodyInk,
                            ),
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                        ) { Text("Incorrect, choose another image", fontSize = 13.sp) }
                    }
                    PhotoPhase.DONE -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.lcdBg)
                                .border(1.dp, c.lcdEdge, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                        ) {
                            Column {
                                Text("Recognized expression: $solvedExpr", color = c.lcdFg, fontFamily = Mono, fontSize = 14.sp)
                                if (solvedResult.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Model result: $solvedResult", color = c.lcdFg, fontFamily = Mono, fontSize = 14.sp)
                                }
                                if (solvedExplain.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(solvedExplain, color = c.lcdDim, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Expression has been inserted into the main calculation line and evaluated.",
                            color = c.keyNeutralInk, fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onBack,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = c.keyEquals, contentColor = c.keyEqualsInk,
                            ),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) { Text("Return to calculator to view result", fontSize = 14.sp) }
                    }
                    PhotoPhase.IDLE -> Text(
                        if (mode == SolveMode.LOCAL) {
                            "Take a photo of a math expression (or pick from gallery). Local offline OCR will show the recognized expression for confirmation before evaluating."
                        } else {
                            "Take a photo of a math expression (or pick from gallery). API recognition will automatically solve it and populate the calculation line."
                        },
                        color = c.keyNeutralInk.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    if (mode == SolveMode.LOCAL) {
                        "Local recognition runs completely offline on your device (bundled ML Kit model). Photos are not uploaded. " +
                            "Best suited for printed math; handwriting, radicals, and fractions have limited accuracy."
                    } else {
                        "Note: Photos are compressed and sent to your configured provider in Settings (max 1600px, JPEG 85)."
                    },
                    color = c.keyNeutralInk.copy(alpha = 0.55f), fontSize = 10.5.sp, lineHeight = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Mask key (only first 4 characters visible) */
private fun maskKey(k: String): String =
    if (k.isEmpty()) "(not set)" else if (k.length <= 4) "****" else k.substring(0, 4) + "****"
