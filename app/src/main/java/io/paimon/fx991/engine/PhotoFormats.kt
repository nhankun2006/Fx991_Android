package io.paimon.fx991.engine

// ---------------------------------------------------------------------------
// Photo solving · Image format compatibility pure logic layer (Batch K3, no Android dependency -> JVM testable)
//
// Reason for extraction: Format detection / downsampling selection / error message mapping do not touch Bitmap,
// all can be tested with pure logic; Android side (ui/PhotoImage.kt) only performs actual decoding.
//
// Design principles:
//  - Do not rely on file extension to determine format (photo gallery often returns extensionless Uri); always sniff magic numbers + MIME fallback.
//  - Honestly flag platform capabilities: HEIC/HEIF platform decoder is only available in API 28+, AVIF in API 31+,
//    TIFF is not guaranteed by platform (BitmapFactory/ImageDecoder generally do not support it) — provide user-friendly messages when unsupported,
//    without adding heavy third-party decoding libraries.
// ---------------------------------------------------------------------------

object PhotoFormats {

    /** Recognizable image formats */
    enum class Format { JPEG, PNG, GIF, BMP, WEBP, HEIC, AVIF, TIFF, UNKNOWN }

    // -----------------------------------------------------------------------
    // Magic number sniffing (reads file header, ignores extension)
    // -----------------------------------------------------------------------

    /** Determine format from file header bytes; [len] is the actual number of bytes read. Returns UNKNOWN if unrecognized. */
    fun sniff(h: ByteArray, len: Int): Format {
        fun at(i: Int, b: Int) = len > i && h[i] == b.toByte()
        fun strAt(off: Int, s: String): Boolean {
            if (len < off + s.length) return false
            for (k in s.indices) if (h[off + k] != s[k].code.toByte()) return false
            return true
        }
        // JPEG: FF D8 FF
        if (at(0, 0xFF) && at(1, 0xD8) && at(2, 0xFF)) return Format.JPEG
        // PNG: 89 50 4E 47 0D 0A 1A 0A (byte-by-byte comparison to avoid \u001A preprocessing issues)
        if (at(0, 0x89) && strAt(1, "PNG") && at(4, 0x0D) && at(5, 0x0A)
            && at(6, 0x1A) && at(7, 0x0A)) return Format.PNG
        // GIF: "GIF87a" / "GIF89a"
        if (strAt(0, "GIF8")) return Format.GIF
        // BMP: "BM"
        if (strAt(0, "BM")) return Format.BMP
        // WebP: "RIFF"????"WEBP"
        if (strAt(0, "RIFF") && strAt(8, "WEBP")) return Format.WEBP
        // TIFF: "II*\0" (little-endian) or "MM\0*" (big-endian)
        if (strAt(0, "II") && at(2, 0x2A) && at(3, 0x00)) return Format.TIFF
        if (strAt(0, "MM") && at(2, 0x00) && at(3, 0x2A)) return Format.TIFF
        // ISO BMFF family (HEIC/HEIF/AVIF): offset 4 is "ftyp", major brand follows immediately
        if (strAt(4, "ftyp") && len >= 12) {
            val brand = String(h, 8, 4, Charsets.US_ASCII)
            when (brand) {
                // HEIF major brand family (including iPhone's heic / sequence hevc, etc.)
                "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
                "mif1", "msf1" -> return Format.HEIC
                // AVIF (avis is sequence)
                "avif", "avis" -> return Format.AVIF
            }
        }
        return Format.UNKNOWN
    }

    /** MIME type fallback detection (used when magic number is not recognized; gallery may return MIME like image/heic) */
    fun fromMime(mime: String?): Format = when (mime?.lowercase()?.trim()) {
        "image/jpeg", "image/jpg", "image/pjpeg" -> Format.JPEG
        "image/png" -> Format.PNG
        "image/gif" -> Format.GIF
        "image/bmp", "image/x-ms-bmp" -> Format.BMP
        "image/webp" -> Format.WEBP
        "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence" -> Format.HEIC
        "image/avif", "image/avif-sequence" -> Format.AVIF
        "image/tiff", "image/tif" -> Format.TIFF
        else -> Format.UNKNOWN
    }

    // -----------------------------------------------------------------------
    // Platform capabilities: required system versions per format (hard facts, no guessing)
    // -----------------------------------------------------------------------

    /** Minimum API for platform decoder support; TIFF is not guaranteed by platform -> MAX_VALUE */
    fun minApi(f: Format): Int = when (f) {
        Format.JPEG, Format.PNG, Format.GIF, Format.BMP, Format.WEBP -> 1
        Format.HEIC -> 28   // HEIF decoder in BitmapFactory/ImageDecoder was added in Android 9
        Format.AVIF -> 31   // Android 12 added platform AVIF decoding
        Format.TIFF -> Int.MAX_VALUE  // Platform decoders generally do not support TIFF
        Format.UNKNOWN -> 1 // Do not intercept unrecognized formats, let the decoder try
    }

    /** Whether this device ([sdkInt]) can theoretically decode this format */
    fun decodableOn(f: Format, sdkInt: Int): Boolean = sdkInt >= minApi(f)

    /** ImageDecoder (API 28+) preferred; lower versions fall back to BitmapFactory */
    fun preferImageDecoder(sdkInt: Int): Boolean = sdkInt >= 28

    // -----------------------------------------------------------------------
    // Downsampling selection (OOM prevention): power of 2 inSampleSize, decoded size still >= target size
    // -----------------------------------------------------------------------

    /**
     * Choose the largest power-of-2 sample rate s such that doubling it would make at least one dimension smaller than target.
     * Guarantees decoded bitmap is not smaller than target (for subsequent precise scaling) while saving memory.
     */
    fun inSampleSize(ow: Int, oh: Int, tw: Int, th: Int): Int {
        if (ow <= 0 || oh <= 0 || tw <= 0 || th <= 0) return 1
        var s = 1
        while (ow / (s * 2) >= tw && oh / (s * 2) >= th) s *= 2
        return s
    }

    // -----------------------------------------------------------------------
    // User-facing messages
    // -----------------------------------------------------------------------

    /** Format display name (for user display) */
    fun displayName(f: Format): String = when (f) {
        Format.JPEG -> "JPEG"
        Format.PNG -> "PNG"
        Format.GIF -> "GIF"
        Format.BMP -> "BMP"
        Format.WEBP -> "WebP"
        Format.HEIC -> "HEIC/HEIF"
        Format.AVIF -> "AVIF"
        Format.TIFF -> "TIFF"
        Format.UNKNOWN -> "Unknown format"
    }

    /** API level -> user-friendly Android version name */
    fun androidName(sdkInt: Int): String = when (sdkInt) {
        26 -> "Android 8.0"
        27 -> "Android 8.1"
        28 -> "Android 9"
        29 -> "Android 10"
        30 -> "Android 11"
        31 -> "Android 12"
        32 -> "Android 12L"
        33 -> "Android 13"
        34 -> "Android 14"
        35 -> "Android 15"
        36 -> "Android 16"
        else -> if (sdkInt > 36) "Android (API $sdkInt)" else "API $sdkInt"
    }

    /** User-friendly message when device cannot decode format (distinguishes format and OS version, gives actionable options) */
    fun unsupportedText(f: Format, sdkInt: Int): String = when (f) {
        Format.HEIC ->
            "This image is in HEIC/HEIF format (default format for iPhone and some Android cameras), " +
                "which requires Android 9 or higher to decode. Current device is ${androidName(sdkInt)}.\n" +
                "Workaround: In your photo gallery, \"Save As/Export as JPG\", or switch camera settings to take JPEG photos."
        Format.AVIF ->
            "This image is in AVIF format, which requires Android 12 or higher to decode. " +
                "Current device is ${androidName(sdkInt)}. Please convert it to JPG or PNG before selecting."
        Format.TIFF ->
            "This image is in TIFF format. Android built-in decoders generally do not support TIFF.\n" +
                "Please convert it to JPG or PNG before selecting."
        else ->
            "This image format (${displayName(f)}) cannot be decoded on this device (${androidName(sdkInt)}). " +
                "Please convert it to JPG before selecting."
    }
}
