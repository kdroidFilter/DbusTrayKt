package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder

object Systray {
    internal lateinit var conn: DBusConnection
    lateinit var itemImpl: StatusNotifierItemImpl
    private lateinit var menuImpl: DbusMenu
    @Volatile internal var running = false

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

        // 2. Objects
        itemImpl = StatusNotifierItemImpl(iconBytes, title, tooltip, onClick, onDblClick, onRightClick)
        menuImpl = DbusMenu(conn, PATH_MENU)

        // 3. Export objects with all interfaces
        conn.exportObject(PATH_ITEM, itemImpl) // Exports StatusNotifierItem, Properties, and Introspectable
        conn.exportObject(PATH_MENU, menuImpl) // Exports DbusMenuMinimal, Properties, and Introspectable

        // 4. Unique name
        val uniqueName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        conn.requestBusName(uniqueName)

        // 5. Register watcher
        runCatching {
            val watcher = conn.getRemoteObject(
                "org.kde.StatusNotifierWatcher",
                "/StatusNotifierWatcher",
                StatusNotifierWatcher::class.java
            )
            watcher.RegisterStatusNotifierItem(PATH_ITEM)
            println("Successfully registered with StatusNotifierWatcher")
        }.onFailure {
            System.err.println("Failed to register with StatusNotifierWatcher: ${it.message}. Continuing without watcher.")
        }
    }

    @JvmStatic fun quit() {
        if (!running) return
        running = false
        runCatching {
            if (conn.isConnected) {
                conn.unExportObject(PATH_ITEM)
                conn.unExportObject(PATH_MENU)
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