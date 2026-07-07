package com.mappo.ui.component

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * Renders [content] as a QR code at [sizePx]. Re-encodes when [content]
 * or [sizePx] change. Suitable for Steam Guard challenge URLs (~120 chars)
 * — output is solid B&W, no quiet-zone margin overrides.
 */
@Composable
fun QrCodeImage(
    content: String,
    sizePx: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content, sizePx) { encodeQr(content, sizePx) }
    Image(
        painter = BitmapPainter(bitmap.asImageBitmap()),
        contentDescription = null,
        modifier = modifier,
    )
}

private fun encodeQr(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp[x, y] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return bmp
}
