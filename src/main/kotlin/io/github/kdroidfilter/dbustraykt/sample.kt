package io.github.kdroidfilter.dbustraykt

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

fun main() {
    startDBusMonitor("dbus_log.txt")  // Remplacez par le fichier que vous fournirez
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
    Systray.addMenuItemCheckbox("Enable Feature", checked = false) {
        println("Feature toggled")

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

fun startDBusMonitor(logFile: String) {
    Thread {
        try {
            val process = ProcessBuilder(
                "dbus-monitor", "--session",
                "interface=org.kde.StatusNotifierItem,path=/StatusNotifierItem",
                "interface=com.canonical.dbusmenu,path=/StatusNotifierMenu",
                "type=signal",  // Optionnel: filtrer sur les signaux uniquement, pour encore plus court
                "type=method_call"  // Et les appels de méthodes
            )
                .redirectOutput(java.io.File(logFile))
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            println("dbus-monitor stopped.")
        } catch (e: Exception) {
            System.err.println("Error running dbus-monitor: ${e.message}")
        }
    }.start()
}