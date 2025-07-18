@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.github.kdroidfilter.dbustraykt

import com.sun.jna.Library
import com.sun.jna.Native
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/* ------------------------------------------------------------------------------------------------
 * Native glue (pid)
 * ------------------------------------------------------------------------------------------------ */
private interface LibC : Library {
    fun getpid(): Int
}
private val libc = Native.load("c", LibC::class.java)

/* ------------------------------------------------------------------------------------------------
 * Public Systray facade
 * ------------------------------------------------------------------------------------------------ */
object Systray {
    /* ---- Logger ---- */
    private val logger: Logger = LoggerFactory.getLogger(Systray::class.java)

    /* ---- DBus constants ---- */
    internal const val PATH_ITEM = "/StatusNotifierItem"
    internal const val PATH_MENU = "/StatusNotifierMenu"
    internal const val IFACE_SNI  = "org.kde.StatusNotifierItem"
    internal const val IFACE_MENU = "com.canonical.dbusmenu"

    /* ---- Runtime state ---- */
    private lateinit var conn: DBusConnection
    private lateinit var itemImpl: StatusNotifierItemImpl
    private lateinit var menuImpl: DbusMenu
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    /* ---- ID source for menu wrapper API ---- */
    private val idSrc = AtomicInteger(0)

    /* --------------------------------------------------------------------------------------------
     * Entry point
     * -------------------------------------------------------------------------------------------- */
    @JvmStatic
    fun run(
        iconBytes: ByteArray,
        title: String = "",
        tooltip: String = "",
        onClick: (() -> Unit)? = null,
        onDblClick: (() -> Unit)? = null,
        onRightClick: (() -> Unit)? = null
    ) {
        if (running) {
            logger.warn("Systray is already running, ignoring run request")
            return
        }
        logger.info("Starting Systray with title: {}", title)
        running = true

        /* 1. Connect to session bus */
        logger.debug("Connecting to DBus session bus")
        conn = DBusConnectionBuilder.forSessionBus().build()
        logger.debug("Connected to DBus session bus")

        /* 2. Export objects */
        logger.debug("Creating StatusNotifierItem and DbusMenu implementations")
        itemImpl = StatusNotifierItemImpl(iconBytes, title, tooltip, onClick, onDblClick, onRightClick)
        menuImpl = DbusMenu(conn, PATH_MENU)
        logger.debug("Exporting StatusNotifierItem at {}", PATH_ITEM)
        conn.exportObject(PATH_ITEM, itemImpl)
        logger.info("Successfully exported StatusNotifierItem at {}", PATH_ITEM)
        try {
            conn.exportObject(PATH_MENU, menuImpl)
            logger.info("Successfully exported DbusMenu at {}", PATH_MENU)
        } catch (e: Exception) {
            logger.error("Failed to export DbusMenu: {}", e.message, e)
        }
        /* 3. Own a well-known name */
        val pid = libc.getpid()
        val uniqueName = "org.kde.StatusNotifierItem-$pid-1"
        logger.debug("Requesting bus name: {}", uniqueName)
        conn.requestBusName(uniqueName)
        logger.info("Successfully acquired bus name: {}", uniqueName)

        /* 4. Register with watcher */
        logger.debug("Connecting to StatusNotifierWatcher")
        val watcher = conn.getRemoteObject(
            "org.kde.StatusNotifierWatcher",
            "/StatusNotifierWatcher",
            StatusNotifierWatcher::class.java
        )
        logger.debug("Registering StatusNotifierItem with watcher")
        watcher.RegisterStatusNotifierItem(PATH_ITEM)
        logger.info("Successfully registered StatusNotifierItem with watcher")

        /* 5. Kick icon fetch */
        logger.debug("Emitting initial signals")
        itemImpl.emitNewIcon()
        itemImpl.emitPropertiesChanged("IconPixmap", "ToolTip", "Title")
        logger.debug("Initial signals emitted")

        /* 6. Keep JVM alive */
        logger.debug("Setting up keep-alive mechanism")
        keepAlive()
        logger.info("Systray initialization complete")
    }

    /* --------------------------------------------------------------------------------------------
     * Shutdown
     * -------------------------------------------------------------------------------------------- */
    @JvmStatic
    fun quit() {
        if (!running) return
        running = false
        try {
            if (::conn.isInitialized && conn.isConnected) {
                conn.unExportObject(PATH_ITEM)
                conn.unExportObject(PATH_MENU)
                Thread.sleep(200) // allow inflight calls to complete
                conn.close()
            }
        } catch (e: Exception) {
            System.err.println("Systray.quit(): ${e.message}")
        }
        executor.shutdownNow()
    }

    /* --------------------------------------------------------------------------------------------
     * Public mutators (icon/title/tooltip)
     * -------------------------------------------------------------------------------------------- */
    @JvmStatic fun setIcon(bytes: ByteArray) = itemImpl.setIcon(bytes)
    @JvmStatic fun setTitle(t: String)       = itemImpl.setTitle(t)
    @JvmStatic fun setTooltip(t: String)     = itemImpl.setTooltip(t)

    /* --------------------------------------------------------------------------------------------
     * Public menu wrapper (minimal)
     * -------------------------------------------------------------------------------------------- */
    /** Add a simple clickable menu item. Returns the item id. */
    @JvmStatic
    fun addMenuItem(label: String, onClick: (() -> Unit)? = null): Int =
        menuImpl.addItem(
            id = idSrc.incrementAndGet(),
            label = label,
            checkable = false,
            checked = false,
            onClick = onClick
        )

    /** Add a checkable menu item (checkbox). */
    @JvmStatic
    fun addMenuItemCheckbox(
        label: String,
        checked: Boolean = false,
        onToggle: ((Boolean) -> Unit)? = null
    ): Int =
        menuImpl.addItem(
            id = idSrc.incrementAndGet(),
            label = label,
            checkable = true,
            checked = checked,
            onToggle = onToggle
        )

    /** Add a separator line. */
    @JvmStatic
    fun addSeparator(): Int =
        menuImpl.addSeparator(idSrc.incrementAndGet())

    @JvmStatic fun setMenuItemLabel(id: Int, label: String)   = menuImpl.setLabel(id, label)
    @JvmStatic fun setMenuItemEnabled(id: Int, enabled: Boolean) = menuImpl.setEnabled(id, enabled)
    @JvmStatic fun setMenuItemChecked(id: Int, checked: Boolean) = menuImpl.setChecked(id, checked)
    @JvmStatic fun setMenuItemVisible(id: Int, visible: Boolean) = menuImpl.setVisible(id, visible)

    /* --------------------------------------------------------------------------------------------
     * Interfaces (remote)
     * -------------------------------------------------------------------------------------------- */
    @DBusInterfaceName("org.kde.StatusNotifierWatcher")
    private interface StatusNotifierWatcher : DBusInterface {
        fun RegisterStatusNotifierItem(itemPath: String)
    }

    @DBusInterfaceName(IFACE_SNI)
    interface StatusNotifierItem : DBusInterface {
        fun Activate(x: Int, y: Int)
        fun SecondaryActivate(x: Int, y: Int)
    }

    /* --------------------------------------------------------------------------------------------
     * StatusNotifierItem implementation
     * -------------------------------------------------------------------------------------------- */
    private class StatusNotifierItemImpl(
        private var iconBytes: ByteArray,
        private var title: String,
        private var tooltip: String,
        private val onClick: (() -> Unit)?,
        private val onDblClick: (() -> Unit)?,
        private val onRightClick: (() -> Unit)?
    ) : StatusNotifierItem, Properties {

        private var lastClick = 0L
        private var iconPixmaps: List<PxStruct> = buildPixmaps(iconBytes)

        /* ---- Signal classes ---- */
        class NewIconSignal(path: String) : DBusSignal(path, IFACE_SNI, "NewIcon"), DBusInterface {
            override fun getObjectPath(): String = path
        }

        class NewTitleSignal(path: String) : DBusSignal(path, IFACE_SNI, "NewTitle"), DBusInterface {
            override fun getObjectPath(): String = path
        }

        class PropertiesChangedSignal(
            path: String,
            iface: String,
            changed: Map<String, Variant<*>>,
        ) : DBusSignal(
            path,
            "org.freedesktop.DBus.Properties",
            "PropertiesChanged",
            arrayOf(iface, changed, emptyList<String>())
        ), DBusInterface {
            override fun getObjectPath(): String = path
        }

        /* ---- DBus methods from panel ---- */
        override fun Activate(x: Int, y: Int) {
            val now = System.currentTimeMillis()
            if (now - lastClick < 400) onDblClick?.invoke() else onClick?.invoke()
            lastClick = now
        }
        override fun SecondaryActivate(x: Int, y: Int) { onRightClick?.invoke() }

        /* ---- Properties helper ---- */
        private fun propertyValue(name: String): Any = when (name) {
            "Status"     -> "Active"
            "Title"      -> title
            "Id"         -> "1"
            "Category"   -> "ApplicationStatus"
            "IconName"   -> ""             // force pixmap
            "IconPixmap" -> iconPixmaps
            "ItemIsMenu" -> true           // we *do* have an exported menu
            "Menu"       -> DBusPath(PATH_MENU)
            "ToolTip"    -> TooltipStruct("", iconPixmaps, tooltip, "")
            else         -> Variant(null)
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> Get(iface: String?, prop: String?): T =
            propertyValue(prop!!).let { it as T }

        override fun <A : Any?> Set(iface: String?, prop: String?, value: A?) {
            when (prop) {
                "Title"   -> setTitle(value as String)
                "ToolTip" -> setTooltip((value as TooltipStruct).v2)
                "IconPixmap" -> if (value is List<*>) {
                    val px = value.firstOrNull()
                    if (px is PxStruct) iconBytes = argbToPng(px)
                }
            }
        }

        override fun GetAll(iface: String?): Map<String, Variant<*>> =
            setOf(
                "Status","Title","Id","Category",
                "IconName","IconPixmap","ItemIsMenu",
                "Menu","ToolTip"
            ).associateWith { 
                val value = propertyValue(it)
                when (value) {
                    is List<*> -> {
                        // Convert List to Array for DBus compatibility
                        if (value.isNotEmpty() && value[0] is PxStruct) {
                            Variant((value as List<PxStruct>).toTypedArray())
                        } else {
                            Variant(value.toTypedArray())
                        }
                    }
                    else -> Variant(value)
                }
            }

        /* ---- Public mutators ---- */
        fun setIcon(bytes: ByteArray) {
            iconBytes = bytes
            iconPixmaps = buildPixmaps(iconBytes)
            emitNewIcon()
            emitPropertiesChanged("IconPixmap")
        }
        fun setTitle(t: String) {
            title = t
            emitNewTitle()
            emitPropertiesChanged("Title")
        }
        fun setTooltip(t: String) {
            tooltip = t
            emitPropertiesChanged("ToolTip")
        }

        /* ---- Signals ---- */
        fun emitNewIcon()  = sendSignalSafe(NewIconSignal(PATH_ITEM))
        fun emitNewTitle() = sendSignalSafe(NewTitleSignal(PATH_ITEM))

        fun emitPropertiesChanged(vararg names: String) {
            val changed = names.associateWith { 
                val value = propertyValue(it)
                when (value) {
                    is List<*> -> {
                        // Convert List to Array for DBus compatibility
                        if (value.isNotEmpty() && value[0] is PxStruct) {
                            Variant((value as List<PxStruct>).toTypedArray())
                        } else {
                            Variant(value.toTypedArray())
                        }
                    }
                    else -> Variant(value)
                }
            }
            sendSignalSafe(PropertiesChangedSignal(PATH_ITEM, IFACE_SNI, changed))
        }

        private fun sendSignalSafe(sig: DBusSignal) {
            try {
                if (running && ::conn.isInitialized && conn.isConnected) {
                    conn.sendMessage(sig)
                }
            } catch (e: Exception) {
                System.err.println("Systray signal error (${sig::class.simpleName}): ${e.message}")
            }
        }

        override fun getObjectPath(): String = PATH_ITEM
    }

    /* --------------------------------------------------------------------------------------------
     * Helper: pixel conversion
     * -------------------------------------------------------------------------------------------- */
    private fun buildPixmaps(src: ByteArray): List<PxStruct> {
        if (src.isEmpty()) return emptyList()
        val img = ImageIO.read(ByteArrayInputStream(src)) ?: return emptyList()
        val sizes = intArrayOf(16, 22, 24, 32, 48)
        return sizes.map { sz ->
            val scaled = BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics()
                g.drawImage(img, 0, 0, sz, sz, null)
                g.dispose()
            }
            val pix = ByteArray(sz * sz * 4)
            var i = 0
            for (y in 0 until sz) for (x in 0 until sz) {
                val argb = scaled.getRGB(x, y)
                pix[i++] = ((argb ushr 24) and 0xFF).toByte() // A
                pix[i++] = ((argb ushr 16) and 0xFF).toByte() // R
                pix[i++] = ((argb ushr  8) and 0xFF).toByte() // G
                pix[i++] = ( argb         and 0xFF).toByte()  // B
            }
            PxStruct(sz, sz, pix)
        }
    }

    private fun argbToPng(px: PxStruct): ByteArray {
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
        return ByteArrayOutputStream().use {
            ImageIO.write(img, "png", it)
            it.toByteArray()
        }
    }

    /* --------------------------------------------------------------------------------------------
     * Keep JVM alive (non-daemon)
     * -------------------------------------------------------------------------------------------- */
    private fun keepAlive() {
        val t = Thread({
            while (running && ::conn.isInitialized && conn.isConnected) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            }
        }, "Systray-keepalive")
        t.isDaemon = false
        t.start()
    }
}


/* -------------------------------------------------------------------------------------------------
 * Tooltip / Pixmap structs
 * ------------------------------------------------------------------------------------------------ */
open class PxStruct(
    @field:Position(0) val w: Int,
    @field:Position(1) val h: Int,
    @field:Position(2) val pix: ByteArray
) : Struct()

class TooltipStruct(
    @field:Position(0) val v0: String,
    @field:Position(1) val v1: List<PxStruct>,
    @field:Position(2) val v2: String,
    @field:Position(3) val v3: String
) : Struct()
