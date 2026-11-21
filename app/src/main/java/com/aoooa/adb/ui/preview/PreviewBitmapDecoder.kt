package com.aoooa.adb.ui.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal fun previewSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while ((width.toLong() + sample - 1) / sample > 2048 ||
        (height.toLong() + sample - 1) / sample > 2048 ||
        ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) > 2_000_000L
    ) sample *= 2
    return sample
}

internal fun decodePreviewBitmap(bytes: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inSampleSize = previewSampleSize(options.outWidth, options.outHeight)
    options.inJustDecodeBounds = false
    options.inPreferredConfig = Bitmap.Config.ARGB_8888
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
