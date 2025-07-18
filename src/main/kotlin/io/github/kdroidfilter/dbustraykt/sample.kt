package io.github.kdroidfilter.dbustraykt

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

fun main() {
    val iconBytes = generateIcon()

    Systray.run(
        iconBytes = iconBytes,
        title = "Tray Demo",
        tooltip = "Systray demo (Kotlin/DBus)",
        onClick = { println("Primary click") },
        onDblClick = { println("Double click") },
        onRightClick = { println("Right click") }
    )

    // Build a minimal functional menu
    val helloId = Systray.addMenuItem("Say Hello") {
        println("Hello from tray menu")
    }
    Systray.addMenuItemCheckbox("Enable Feature", checked = false) { checked ->
        println("Feature toggled: $checked")
    }
    Systray.addSeparator()
    Systray.addMenuItem("Quit") {
        println("Quitting…")
        Systray.quit()
        exitProcess(0)
    }

    println("Tray running. Press ENTER to update label; 'q'+ENTER to quit.")
    while (true) {
        val line = readlnOrNull() ?: break
        if (line.equals("q", ignoreCase = true)) {
            Systray.quit()
            break
        }
        Systray.setMenuItemLabel(helloId, "Hello (${System.currentTimeMillis()})")
    }
}

/* ------------------------------------------------------------------------------------------------
 * Icon loading helpers
 * ------------------------------------------------------------------------------------------------ */

private fun generateIcon(): ByteArray {
    val size = 32
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = Color(0, 120, 215)
    g.fillRect(0, 0, size, size)
    g.color = Color.WHITE
    g.drawString("K", 10, 20)
    g.dispose()
    return ByteArrayOutputStream().use {
        ImageIO.write(img, "png", it)
        it.toByteArray()
    }
}
