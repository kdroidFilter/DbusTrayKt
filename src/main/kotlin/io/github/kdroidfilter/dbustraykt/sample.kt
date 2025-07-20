package io.github.kdroidfilter.dbustraykt

import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.system.exitProcess

// Reference to our Swing window
private lateinit var frame: JFrame
private var windowVisible = false

fun main() {
    startDBusMonitor("dbus_log.txt")
    val iconBytes = generateIcon()

    // Create the Swing window on the Event Dispatch Thread
    SwingUtilities.invokeLater {
        createAndShowGUI()
    }

    // Démarrer le systray
    Systray.run(
        iconBytes = iconBytes,
        title = "Tray Demo",
        tooltip = "Systray demo (Kotlin/DBus)",
        onClick = { 
            println("Primary click - showing window if hidden")
            showWindowIfHidden() 
        }
        // Removed onRightClick callback to let the system handle right-click events
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
    
    // Add menu item to toggle window visibility
    val showWindowId = Systray.addMenuItem("Show Window") {
        toggleWindowVisibility()
    }
    
    Systray.addSeparator()
    val quitId = Systray.addMenuItem("Quit") {
        println("Quitting…")
        frame.dispose() // Properly dispose of the Swing window
        Systray.quit()
        exitProcess(0)
    }

    println("Menu built with ${4} items plus separator")
    Systray.menuImpl.emitLayoutUpdated()
    
    // Garder le programme en vie
    while (Systray.running) {
        Thread.sleep(1000)
    }
}

/* ------------------------------------------------------------------------------------------------
 * Swing window functions
 * ------------------------------------------------------------------------------------------------ */

/**
 * Creates and displays the Swing window.
 * This function should be called on the Event Dispatch Thread.
 */
private fun createAndShowGUI() {
    // Create the main frame
    frame = JFrame("DBus Tray Kotlin Demo")
    frame.defaultCloseOperation = JFrame.HIDE_ON_CLOSE // Hide instead of exit
    frame.layout = FlowLayout(FlowLayout.CENTER, 20, 20)
    frame.preferredSize = Dimension(400, 300)
    
    // Add a title label
    val titleLabel = JLabel("DBus Tray Demo Window")
    titleLabel.font = Font("Sans", Font.BOLD, 18)
    frame.add(titleLabel)
    
    // Add a description label
    val descLabel = JLabel("This window is integrated with the system tray icon")
    frame.add(descLabel)
    
    // Add a button that does something
    val actionButton = JButton("Click Me")
    actionButton.addActionListener {
        JOptionPane.showMessageDialog(
            frame,
            "Button clicked!",
            "Action",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    frame.add(actionButton)
    
    // Add a button to hide the window
    val hideButton = JButton("Hide Window")
    hideButton.addActionListener {
        toggleWindowVisibility()
    }
    frame.add(hideButton)
    
    // Finalize and show the frame
    frame.pack()
    frame.setLocationRelativeTo(null) // Center on screen
    frame.isVisible = true
    windowVisible = true
    
    // Add window listener to update state when closed by window controls
    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
        override fun windowClosing(e: java.awt.event.WindowEvent) {
            windowVisible = false
            updateShowWindowMenuItem()
        }
    })
}

/**
 * Toggles the visibility of the Swing window and updates the menu item text.
 */
private fun toggleWindowVisibility() {
    SwingUtilities.invokeLater {
        windowVisible = !windowVisible
        frame.isVisible = windowVisible
        updateShowWindowMenuItem()
    }
}

/**
 * Shows the window if it's currently hidden.
 * Does nothing if the window is already visible.
 */
private fun showWindowIfHidden() {
    SwingUtilities.invokeLater {
        if (!windowVisible) {
            windowVisible = true
            frame.isVisible = true
            updateShowWindowMenuItem()
        }
    }
}

/**
 * Updates the "Show Window" menu item text based on window visibility.
 */
private fun updateShowWindowMenuItem() {
    val menuItemText = if (windowVisible) "Hide Window" else "Show Window"
    // Find the menu item by its position (index 2, after Hello and Feature items)
    val menuItemId = 3 // This is the ID of our "Show Window" menu item
    Systray.setMenuItemLabel(menuItemId, menuItemText)
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