// StatusNotifierItemImpl.kt (added UnknownMethodException and changed throws to use it for proper DBus error name)
package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.annotations.DBusInterfaceName
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class NewIconSignal(path: String) : DBusSignal(path, IFACE_SNI, "NewIcon"), DBusInterface {
    override fun getObjectPath(): String = path
}

class NewTitleSignal(path: String) : DBusSignal(path, IFACE_SNI, "NewTitle"), DBusInterface {
    override fun getObjectPath(): String = path
}

@DBusInterfaceName("org.freedesktop.DBus.Error.UnknownMethod")
class UnknownMethodException(message: String) : DBusExecutionException(message)

class StatusNotifierItemImpl(
    private var iconBytes: ByteArray,
    private var title: String,
    private var tooltip: String,
    private val onClick: (() -> Unit)?,
    private val onDblClick: (() -> Unit)?,
    private val onRightClick: (() -> Unit)?
) : StatusNotifierItem, Properties, Introspectable {

    private var dActivateTime: Long = 0L

    override fun Activate(x: Int, y: Int) {
        val now = System.currentTimeMillis()
        if (dActivateTime == 0L) {
            dActivateTime = now
            return
        }
        if (now - dActivateTime < 500) {  // Hardcoded to match Go default
            onDblClick?.invoke()
            dActivateTime = now
            return
        } else {
            dActivateTime = now
        }
        onClick?.invoke() ?: throw UnknownMethodException("Unknown method")
    }

    override fun SecondaryActivate(x: Int, y: Int) {
        throw UnknownMethodException("Unknown method")
    }

    override fun ContextMenu(x: Int, y: Int) {
        if (onRightClick != null) {
            onRightClick.invoke()
        } else {
            throw UnknownMethodException("Unknown method")
        }
    }

    override fun Scroll(delta: Int, orientation: String) {
        throw UnknownMethodException("Unknown method")
    }

    override fun <T> Get(iface: String?, prop: String?): T {
        require(iface == IFACE_SNI) { "Unknown interface: $iface" }
        @Suppress("UNCHECKED_CAST")
        return when (prop) {
            "Category" -> "ApplicationStatus"
            "Id" -> "1"
            "Title" -> title
            "Status" -> "Active"
            "IconPixmap" -> arrayOf(convertToPixels(iconBytes))
            "IconThemePath" -> ""
            "ItemIsMenu" -> true
            "Menu" -> PATH_MENU
            "ToolTip" -> TooltipStruct("", emptyList(), tooltip, "")
            else -> throw IllegalArgumentException("Unknown property: $prop")
        } as T
    }

    override fun <A> Set(iface: String?, prop: String?, value: A?) {
        require(iface == IFACE_SNI) { "Unknown interface: $iface" }
        when (prop) {
            "Title" -> if (value is String) title = value
            // Add other writable props if needed
        }
    }

    override fun GetAll(iface: String?): Map<String, Variant<*>> {
        require(iface == IFACE_SNI) { "Unknown interface: $iface" }
        return mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("1"),
            "Title" to Variant(title),
            "Status" to Variant("Active"),
            "IconPixmap" to Variant(arrayOf(convertToPixels(iconBytes))),
            "IconThemePath" to Variant(""),
            "ItemIsMenu" to Variant(true),
            "Menu" to Variant(PATH_MENU),
            "ToolTip" to Variant(TooltipStruct("", emptyList(), tooltip, ""))
        )
    }

    override fun Introspect(): String = IntrospectXml.itemXml

    fun setIcon(bytes: ByteArray) {
        iconBytes = bytes
        runCatching {
            Systray.conn.sendMessage(NewIconSignal(PATH_ITEM))
        }.onFailure { System.err.println("setIcon: ${it.message}") }
    }

    fun setTitle(t: String) {
        title = t
        runCatching {
            Systray.conn.sendMessage(NewTitleSignal(PATH_ITEM))
        }.onFailure { System.err.println("setTitle: ${it.message}") }
    }

    fun setTooltip(t: String) {
        tooltip = t
        // No signal emitted, matching Go
    }

    private fun convertToPixels(bytes: ByteArray): PxStruct {
        if (bytes.isEmpty()) return PxStruct(0, 0, byteArrayOf())
        try {
            val input = ByteArrayInputStream(bytes)
            val img: BufferedImage = ImageIO.read(input)
            val w = img.width
            val h = img.height
            val pix = ByteArray(w * h * 4)
            var index = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val argb = img.getRGB(x, y)
                    pix[index++] = ((argb shr 24) and 0xFF).toByte() // A
                    pix[index++] = ((argb shr 16) and 0xFF).toByte() // R
                    pix[index++] = ((argb shr 8) and 0xFF).toByte() // G
                    pix[index++] = (argb and 0xFF).toByte() // B
                }
            }
            return PxStruct(w, h, pix)
        } catch (e: Exception) {
            System.err.println("Failed to decode icon: ${e.message}")
            return PxStruct(0, 0, byteArrayOf())
        }
    }

    override fun getObjectPath(): String = PATH_ITEM
}