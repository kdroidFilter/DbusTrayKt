package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface

object Systray : IMenu {
    /**
     * Shows the menu by simulating the sequence of DBus method calls that would normally
     * be triggered when the user right-clicks on the tray icon.
     */
    override fun showMenu() {
        if (!::menuImpl.isInitialized) return
        
        println("Showing menu...")
        
        try {
            // Simply emit a layout updated signal to refresh the menu
            // This is similar to what the Go implementation would do
            menuImpl.emitLayoutUpdated()
        } catch (e: Exception) {
            System.err.println("Error showing menu: ${e.message}")
        }
    }
    internal lateinit var conn: DBusConnection
    lateinit var itemImpl: StatusNotifierItemImpl
    lateinit var menuImpl: DbusMenu
    @Volatile internal var running = false
    private lateinit var serviceName: String

    @JvmStatic fun run(
        iconBytes: ByteArray,
        title: String = "",
        tooltip: String = "",
        onClick: (() -> Unit)? = null,
        onDblClick: (() -> Unit)? = null,
        onRightClick: (() -> Unit)? = null,
    ) {
        if (running) return
        running = true

        // 1. Connection
        conn = DBusConnectionBuilder.forSessionBus().build()

        // 2. Create objects
        menuImpl = DbusMenu(conn, PATH_MENU)
        itemImpl = StatusNotifierItemImpl(iconBytes, title, tooltip, onClick, onDblClick, onRightClick)

        // 3. Export objects BEFORE requesting the bus name
        conn.exportObject(PATH_MENU, menuImpl)
        conn.exportObject(PATH_ITEM, itemImpl)
        println("Exported objects at $PATH_ITEM and $PATH_MENU")

        // 4. Request the bus name
        serviceName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        conn.requestBusName(serviceName)
        println("Requested bus name: $serviceName")

        // 5. Register with watcher
        runCatching {
            val watcher = conn.getRemoteObject(
                "org.kde.StatusNotifierWatcher",
                "/StatusNotifierWatcher",
                StatusNotifierWatcher::class.java
            )
            // Try registering with just the service name
            watcher.RegisterStatusNotifierItem(serviceName)
            println("Registered with watcher: $serviceName")
        }.onFailure { e1 ->
            println("First registration failed: ${e1.message}")
            // Try with full path
            runCatching {
                val watcher = conn.getRemoteObject(
                    "org.kde.StatusNotifierWatcher",
                    "/StatusNotifierWatcher",
                    StatusNotifierWatcher::class.java
                )
                watcher.RegisterStatusNotifierItem("$serviceName$PATH_ITEM")
                println("Registered with watcher: $serviceName$PATH_ITEM")
            }.onFailure { e2 ->
                System.err.println("Failed to register: ${e2.message}")
            }
        }

        // Debug: print our connection info
        println("Our unique bus name: ${conn.uniqueName}")
        println("Our service name: $serviceName")
    }

    @JvmStatic fun quit() {
        if (!running) return
        running = false
        runCatching {
            if (::conn.isInitialized && conn.isConnected) {
                conn.unExportObject(PATH_ITEM)
                conn.unExportObject(PATH_MENU)
                if (::serviceName.isInitialized) {
                    conn.releaseBusName(serviceName)
                }
                Thread.sleep(200)
                conn.close()
            }
        }.onFailure { System.err.println("Systray.quit(): ${it.message}") }
    }

    @JvmStatic fun setIcon(bytes: ByteArray) = itemImpl.setIcon(bytes)
    @JvmStatic fun setTitle(t: String) = itemImpl.setTitle(t)
    @JvmStatic fun setTooltip(t: String) = itemImpl.setTooltip(t)

    @JvmStatic fun addMenuItem(label: String, onClick: (() -> Unit)? = null): Int =
        menuImpl.addItem(label, onClick = onClick)

    @JvmStatic fun addMenuItemCheckbox(label: String, checked: Boolean = false,
                                       onClick: (() -> Unit)? = null): Int =
        menuImpl.addItem(label, checkable = true, checked = checked, onClick = onClick)

    @JvmStatic fun addSeparator(): Int = menuImpl.addSeparator()

    @JvmStatic fun setMenuItemLabel(id: Int, label: String) = menuImpl.setLabel(id, label)
    @JvmStatic fun setMenuItemEnabled(id: Int, enabled: Boolean) = menuImpl.setEnabled(id, enabled)
    @JvmStatic fun setMenuItemChecked(id: Int, checked: Boolean) = menuImpl.setChecked(id, checked)
    @JvmStatic fun setMenuItemVisible(id: Int, visible: Boolean) = menuImpl.setVisible(id, visible)

    @JvmStatic fun resetMenu() = menuImpl.resetMenu()
}