package io.github.kdroidfilter.dbustraykt


import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal fun buildPixmaps(src: ByteArray): Array<PxStruct> {
    if (src.isEmpty()) return emptyArray()
    val img = ImageIO.read(ByteArrayInputStream(src)) ?: return emptyArray()
    val sizes = intArrayOf(16, 22, 24, 32, 48)
    return sizes.map { sz ->
        val scaled = BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics(); g.drawImage(img, 0, 0, sz, sz, null); g.dispose()
        }
        val pix = ByteArray(sz * sz * 4)
        var i = 0
        for (y in 0 until sz) for (x in 0 until sz) {
            val argb = scaled.getRGB(x, y)
            pix[i++] = ((argb ushr 24) and 0xFF).toByte()
            pix[i++] = ((argb ushr 16) and 0xFF).toByte()
            pix[i++] = ((argb ushr 8) and 0xFF).toByte()
            pix[i++] = (argb and 0xFF).toByte()
        }
        PxStruct(sz, sz, pix)
    }.toTypedArray()
}

internal fun argbToPng(px: PxStruct): ByteArray {
    if (px.w == 0 || px.h == 0) return ByteArray(0)
    val img = BufferedImage(px.w, px.h, BufferedImage.TYPE_INT_ARGB)
    var i = 0
    for (y in 0 until px.h) for (x in 0 until px.w) {
        val a = (px.pix[i++].toInt() and 0xFF) shl 24
        val r = (px.pix[i++].toInt() and 0xFF) shl 16
        val g = (px.pix[i++].toInt() and 0xFF) shl 8
        val b = (px.pix[i++].toInt() and 0xFF)
        img.setRGB(x, y, a or r or g or b)
    }
    return ByteArrayOutputStream().use { ImageIO.write(img, "png", it); it.toByteArray() }
}