package io.paimon.fx991.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import io.paimon.fx991.engine.PhotoFormats
import io.paimon.fx991.engine.PhotoSolve
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

// ---------------------------------------------------------------------------
// Photo Solve · Image Pipeline (Batch K3: Format compatibility enhancements — HEIC/HEIF, etc.)
//
// Pipeline: Magic number sniffing (ignores file extension) -> Platform capability check (clear error message if unsupported)
//   -> ImageDecoder (API 28+, automatic EXIF application, decodes directly to target size to save memory)
//   -> BitmapFactory fallback (API 26/27, maintains manual EXIF correction path)
//   -> Proportional downscale to <= 1600px -> JPEG 85.
//
// Pure logic for format detection / downsampling / error messages is in engine/PhotoFormats.kt (regression-tested on JVM);
// Bitmap operations in this file can only be verified on real devices / emulators.
// ---------------------------------------------------------------------------

object PhotoImage {

    class PhotoImageError(message: String) : Exception(message)

    data class Prepared(
        val bitmap: Bitmap,      // Compressed bitmap (for preview)
        val jpeg: ByteArray,     // JPEG ~85 bytes
        val width: Int,          // Scaled width
        val height: Int,         // Scaled height
        val origWidth: Int,
        val origHeight: Int,
    ) {
        val dataUri: String get() = PhotoSolve.jpegDataUri(jpeg)
    }

    /**
     * Reads and compresses image from content URI: longest edge <= [PhotoSolve.MAX_EDGE], JPEG quality [PhotoSolve.JPEG_QUALITY].
     * Must be invoked off the main thread.
     */
    fun prepare(resolver: ContentResolver, uri: Uri): Prepared {
        // 1. Sniff actual format (file header magic number, ignores extension; MIME used only as fallback)
        val format = detectFormat(resolver, uri)

        // 2. Hard check platform capability: HEIC requires API 28, AVIF requires API 31, TIFF is not guaranteed by platform.
        //    If unsupported, state reason and solution clearly rather than failing silently with black screen / crash / generic read error.
        if (!PhotoFormats.decodableOn(format, Build.VERSION.SDK_INT)) {
            throw PhotoImageError(PhotoFormats.unsupportedText(format, Build.VERSION.SDK_INT))
        }

        // 3. Decoding: API 28+ prefers ImageDecoder (broader format support, decodes to target size saving memory,
        //    automatically applies EXIF orientation); older versions use BitmapFactory + manual EXIF correction.
        val bmp: Bitmap
        val ow: Int
        val oh: Int
        if (PhotoFormats.preferImageDecoder(Build.VERSION.SDK_INT)) {
            try {
                val r = decodeViaImageDecoder(resolver, uri)
                bmp = r.first; ow = r.second; oh = r.third
            } catch (e: ImageDecoder.DecodeException) {
                throw PhotoImageError(decodeFailText(format, e))
            } catch (e: IOException) {
                throw PhotoImageError(decodeFailText(format, e))
            } catch (e: OutOfMemoryError) {
                throw PhotoImageError("This image is too large and the device ran out of memory while decoding (Out of Memory). Please export it to a smaller JPG in your gallery and try again.")
            }
        } else {
            val r = decodeViaBitmapFactory(resolver, uri)
            bmp = r.first; ow = r.second; oh = r.third
        }

        // 4. Precise scaling to target dimensions + JPEG compression
        return finish(bmp, ow, oh)
    }

    // -----------------------------------------------------------------------
    // Format sniffing: Read header magic number first; fallback to ContentResolver MIME if unrecognized
    // -----------------------------------------------------------------------

    private fun detectFormat(resolver: ContentResolver, uri: Uri): PhotoFormats.Format {
        val head = ByteArray(32)
        val n = try {
            resolver.openInputStream(uri)?.use { it.read(head) } ?: -1
        } catch (e: SecurityException) {
            throw PhotoImageError("Permission denied when reading this image (URI authorization may have expired). Please choose again.")
        } catch (e: FileNotFoundException) {
            throw PhotoImageError("Cannot read this image (file may have been moved or deleted). Please choose again.")
        } catch (e: IOException) {
            throw PhotoImageError("Error reading image (${e.message ?: "I/O error"}). Please choose again.")
        }
        if (n <= 0) throw PhotoImageError("Cannot read this image (content is empty or URI has expired). Please choose again.")
        val sniffed = PhotoFormats.sniff(head, n)
        if (sniffed != PhotoFormats.Format.UNKNOWN) return sniffed
        return PhotoFormats.fromMime(resolver.getType(uri))
    }

    // -----------------------------------------------------------------------
    // Path A: ImageDecoder (API 28+)
    //   - Direct setTargetSize to decode at target dimensions: 100MP HEIC won't load full-size into memory
    //   - Automatically applies EXIF orientation without manual rotation
    //   - GIF / animated WebP decodes to AnimatedImageDrawable -> draw first frame onto bitmap
    // -----------------------------------------------------------------------

    private fun decodeViaImageDecoder(resolver: ContentResolver, uri: Uri): Triple<Bitmap, Int, Int> {
        if (Build.VERSION.SDK_INT < 28) throw IllegalStateException("ImageDecoder requires API 28+")
        var ow = 0
        var oh = 0
        val src = ImageDecoder.createSource(resolver, uri)
        val drawable = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
            ow = info.size.width
            oh = info.size.height
            val (tw, th) = PhotoSolve.scaledSize(ow, oh)
            decoder.setTargetSize(tw, th)
            // Software allocation: needed for compress + preview later, avoiding hardware bitmap readback issues
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (ow <= 0 || oh <= 0) throw PhotoImageError("Cannot recognize the format of this image (not a valid image file).")
        val bmp = when (drawable) {
            is AnimatedImageDrawable -> {
                // GIF / animated image: decoder pauses at first frame, draw directly
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(c)
                b
            }
            is BitmapDrawable -> drawable.bitmap
            else -> throw PhotoImageError("Image decoding produced an unexpected result type. Please try another image.")
        }
        return Triple(bmp, ow, oh)
    }

    // -----------------------------------------------------------------------
    // Path B: BitmapFactory (API 26/27 fallback)
    //   These versions cannot decode HEIC (intercepted in step 2), reaching here only for
    //   JPEG/PNG/GIF(first frame)/BMP/WebP. Preserves manual EXIF correction.
    // -----------------------------------------------------------------------

    private fun decodeViaBitmapFactory(resolver: ContentResolver, uri: Uri): Triple<Bitmap, Int, Int> {
        // Measure bounds first to get original size (inJustDecodeBounds prevents OOM by not allocating pixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw PhotoImageError("Cannot read this image (may be an expired temporary file). Please choose again.")
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) throw PhotoImageError("Cannot recognize the format of this image (not a valid image file, or file is corrupted).")

        // EXIF orientation (phone cameras often contain rotation tags)
        val rotation = resolver.openInputStream(uri)?.use { ins ->
            try {
                when (ExifInterface(ins).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (_: Exception) {
                0
            }
        } ?: 0

        // Coarse downsampling first (powers of 2; pure logic in PhotoFormats.inSampleSize, regression-tested on JVM)
        val (tw, th) = PhotoSolve.scaledSize(
            if (rotation == 90 || rotation == 270) oh else ow,
            if (rotation == 90 || rotation == 270) ow else oh,
        )
        val opts = BitmapFactory.Options().apply {
            inSampleSize = PhotoFormats.inSampleSize(ow, oh, tw, th)
        }
        var bmp = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: OutOfMemoryError) {
            throw PhotoImageError("This image is too large and the device ran out of memory while decoding (Out of Memory). Please export it to a smaller JPG in your gallery and try again.")
        } ?: throw PhotoImageError("Image decoding failed (file is corrupted or format is not supported by this device).")

        // EXIF rotation correction
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        return Triple(bmp, ow, oh)
    }

    // -----------------------------------------------------------------------
    // Common finalization: Precise scaling + JPEG compression
    // -----------------------------------------------------------------------

    private fun finish(bmpIn: Bitmap, ow: Int, oh: Int): Prepared {
        var bmp = bmpIn
        val (fw, fh) = PhotoSolve.scaledSize(bmp.width, bmp.height)
        if (fw != bmp.width || fh != bmp.height) {
            val scaled = Bitmap.createScaledBitmap(bmp, fw, fh, true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        val baos = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, PhotoSolve.JPEG_QUALITY, baos)) {
            throw PhotoImageError("JPEG compression failed.")
        }
        return Prepared(bmp, baos.toByteArray(), bmp.width, bmp.height, ow, oh)
    }

    /** User-friendly error message on decoder exception (distinguishes "format unsupported" vs "file corrupted") */
    private fun decodeFailText(format: PhotoFormats.Format, e: Exception): String {
        val name = PhotoFormats.displayName(format)
        return if (format == PhotoFormats.Format.UNKNOWN) {
            "Cannot recognize the format of this image (not a valid image file, or rare format). Converting to JPG is recommended."
        } else {
            "$name image decoding failed: file may be corrupted, or this device (${PhotoFormats.androidName(android.os.Build.VERSION.SDK_INT)}) " +
                "has an incomplete implementation for this format (${e.message?.take(60) ?: "decoder error"}). Converting to JPG is recommended."
        }
    }
}
