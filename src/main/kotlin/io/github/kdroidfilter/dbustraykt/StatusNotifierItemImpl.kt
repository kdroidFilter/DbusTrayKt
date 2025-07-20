package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.types.Variant

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
        println("Activate called at ($x, $y)")
        val now = System.currentTimeMillis()
        if (onClick == null) {
            throw UnknownMethodException("Unknown method")
        }
        if (now - dActivateTime < 500 && onDblClick != null) {
            onDblClick.invoke()
        } else {
            onClick.invoke()
        }
        dActivateTime = now
    }

    override fun SecondaryActivate(x: Int, y: Int) {
        println("SecondaryActivate called at ($x, $y)")
        throw UnknownMethodException("Unknown method")
    }

    override fun ContextMenu(x: Int, y: Int) {
        println("ContextMenu called at ($x, $y)")
        // Ne PAS lever d'exception - laisser le système afficher le menu
        onRightClick?.invoke()
    }

    override fun Scroll(delta: Int, orientation: String) {
        println("Scroll called: delta=$delta, orientation=$orientation")
        throw UnknownMethodException("Unknown method")
    }

    override fun <T> Get(iface: String?, prop: String?): T {
        println("Get called: iface=$iface, prop=$prop")
        require(iface == IFACE_SNI) { "Unknown interface: $iface" }
        @Suppress("UNCHECKED_CAST")
        return when (prop) {
            "Category" -> "ApplicationStatus" as T
            "Id" -> "1" as T
            "Title" -> title as T
            "Status" -> "Active" as T
            "WindowId" -> 0 as T
            "IconName" -> "" as T
            "IconPixmap" -> buildPixmaps(iconBytes) as T
            "OverlayIconName" -> "" as T
            "OverlayIconPixmap" -> emptyArray<PxStruct>() as T
            "AttentionIconName" -> "" as T
            "AttentionIconPixmap" -> emptyArray<PxStruct>() as T
            "AttentionMovieName" -> "" as T
            "IconThemePath" -> "" as T
            "ItemIsMenu" -> {
                println("  -> ItemIsMenu requested, returning false")
                false as T
            }
            "Menu" -> DBusPath(PATH_MENU) as T
            "ToolTip" -> TooltipStruct("", emptyList(), tooltip, "") as T
            else -> throw IllegalArgumentException("Unknown property: $prop")
        }
    }

    override fun <A> Set(iface: String?, prop: String?, value: A?) {
        println("Set called: iface=$iface, prop=$prop, value=$value")
        require(iface == IFACE_SNI) { "Unknown interface: $iface" }
        when (prop) {
            "Title" -> if (value is String) title = value
            "IconPixmap" -> @Suppress("UNCHECKED_CAST")
            if (value is Array<*> && value.isNotEmpty() && value[0] is PxStruct) {
                iconBytes = argbToPng(value[0] as PxStruct)
            }
            "ToolTip" -> if (value is TooltipStruct) tooltip = value.v2
        }
    }

    override fun GetAll(iface: String?): Map<String, Variant<*>> {
        println("GetAll called for iface=$iface")
        require(iface == IFACE_SNI) { "Unknown interface: $iface" }
        val result = mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("1"),
            "Title" to Variant(title),
            "Status" to Variant("Active"),
            "WindowId" to Variant(0),
            "IconName" to Variant(""),
            "IconPixmap" to Variant(buildPixmaps(iconBytes)),
            "OverlayIconName" to Variant(""),
            "OverlayIconPixmap" to Variant(emptyArray<PxStruct>()),
            "AttentionIconName" to Variant(""),
            "AttentionIconPixmap" to Variant(emptyArray<PxStruct>()),
            "AttentionMovieName" to Variant(""),
            "IconThemePath" to Variant(""),
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(DBusPath(PATH_MENU)),
            "ToolTip" to Variant(TooltipStruct("", emptyList(), tooltip, ""))
        )
        println("  -> Returning properties with Menu=$PATH_MENU")
        return result
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
    }

    override fun getObjectPath(): String = PATH_ITEM
}