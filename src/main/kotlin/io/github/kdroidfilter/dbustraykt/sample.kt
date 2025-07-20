package io.github.kdroidfilter.dbustraykt

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

fun main() {
    startDBusMonitor("dbus_log.txt")
    val iconBytes = generateIcon()

    // Démarrer le systray
    Systray.run(
        iconBytes = iconBytes,
        title = "Tray Demo",
        tooltip = "Systray demo (Kotlin/DBus)",
        onClick = { println("Primary click") },
        onRightClick = { println("Right click - menu should appear") }
    )

    // IMPORTANT: Attendre un peu que le systray soit complètement initialisé
    Thread.sleep(500)

    // Construire le menu APRÈS que le systray soit initialisé
    println("Building menu...")
    val helloId = Systray.addMenuItem("Say Hello") {
        println("Hello from tray menu")
    }
    val featureId = Systray.addMenuItemCheckbox("Enable Feature", checked = false) {
        println("Feature toggled")
    }
    Systray.addSeparator()
    val quitId = Systray.addMenuItem("Quit") {
        println("Quitting…")
        Systray.quit()
        exitProcess(0)
    }

    println("Menu built with ${3} items plus separator")

    // Garder le programme en vie
    while (Systray.running) {
        Thread.sleep(1000)
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
                "type=signal",
                "type=method_call"
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