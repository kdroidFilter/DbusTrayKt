package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

class StatusNotifierItemImpl(
    private var iconBytes: ByteArray,
    private var title: String,
    private var tooltip: String,
    private val onClick: (() -> Unit)?,
    private val onDblClick: (() -> Unit)?,
    private val onRightClick: (() -> Unit)?,
) : StatusNotifierItem, Properties, Introspectable {

    private var lastClick = 0L
    private var iconPixmaps: Array<PxStruct> = buildPixmaps(iconBytes)

    class NewIconSignal(path: String) : DBusSignal(path, IFACE_SNI, "NewIcon"), DBusInterface {
        override fun getObjectPath(): String = path
    }
    class NewTitleSignal(path: String) : DBusSignal(path, IFACE_SNI, "NewTitle"), DBusInterface {
        override fun getObjectPath(): String = path
    }
    class NewMenuSignal(path: String) : DBusSignal(path, IFACE_SNI, "NewMenu"), DBusInterface {
        override fun getObjectPath(): String = path
    }

    override fun Activate(x: Int, y: Int) {
        val now = System.currentTimeMillis()
        if (now - lastClick < 400) onDblClick?.invoke() else onClick?.invoke()
        lastClick = now
    }
    override fun SecondaryActivate(x: Int, y: Int) { onRightClick?.invoke() }
    override fun ContextMenu(x: Int, y: Int) {
        throw DBusException("Unknown method")
    }
    override fun Scroll(delta: Int, orientation: String) {
        throw DBusException("Unknown method")
    }

    private fun propertyValue(name: String): Any = when (name) {
        "Status"      -> "Active"
        "Title"       -> title
        "Id"          -> "1"
        "Category"    -> "ApplicationStatus"
        "IconName"    -> ""
        "IconPixmap"  -> iconPixmaps
        "ItemIsMenu"  -> true
        "Menu"        -> DBusPath(PATH_MENU)
        "ToolTip"     -> TooltipStruct("", iconPixmaps.toList(), tooltip, "")
        else           -> Variant(null)
    }

    override fun <T : Any?> Get(iface: String?, prop: String?): T {
        @Suppress("UNCHECKED_CAST")
        return when (val v = propertyValue(prop!!)) {
            is Array<*> -> Variant(v) as T
            is List<*>  -> Variant(v.toTypedArray()) as T
            else        -> v as T
        }
    }

    override fun <A : Any?> Set(iface: String?, prop: String?, value: A?) {
        when (prop) {
            "Title"       -> setTitle(value as String)
            "ToolTip"     -> setTooltip((value as TooltipStruct).v2)
            "IconPixmap"  -> if (value is Array<*>) {
                val px = value.firstOrNull()
                if (px is PxStruct) iconBytes = argbToPng(px)
            }
        }
    }

    override fun GetAll(iface: String?): Map<String, Variant<*>> =
        listOf("Status","Title","Id","Category","IconName","IconPixmap",
            "ItemIsMenu","Menu","ToolTip").associateWith {
            val value = propertyValue(it)
            when (value) {
                is Array<*> -> Variant(value)
                is List<*>  -> Variant(value.toTypedArray())
                else        -> Variant(value)
            }
        }

    fun setIcon(bytes: ByteArray) {
        iconBytes = bytes; iconPixmaps = buildPixmaps(iconBytes)
        emitNewIcon(); emitPropertiesChanged("IconPixmap")
    }
    fun setTitle(t: String) { title = t; emitNewTitle(); emitPropertiesChanged("Title") }
    fun setTooltip(t: String) { tooltip = t; emitPropertiesChanged("ToolTip") }

    fun emitNewIcon() = sendSignalSafe(NewIconSignal(PATH_ITEM))
    fun emitNewTitle() = sendSignalSafe(NewTitleSignal(PATH_ITEM))
    fun emitNewMenu() = sendSignalSafe(NewMenuSignal(PATH_ITEM))
    fun emitPropertiesChanged(vararg names: String) {
        val changed = names.associateWith {
            val value = propertyValue(it)
            when (value) {
                is Array<*> -> Variant(value)
                is List<*>  -> Variant(value.toTypedArray())
                else        -> Variant(value)
            }
        }
        sendSignalSafe(DbusMenu.PropertiesChangedSignal(PATH_ITEM, IFACE_SNI, changed))
    }
    private fun sendSignalSafe(sig: DBusSignal) = runCatching {
        if (Systray.running && Systray.conn.isConnected) Systray.conn.sendMessage(sig)
    }.onFailure { System.err.println("Signal error: ${it.message}") }

    override fun getObjectPath(): String = PATH_ITEM

    override fun Introspect(): String = IntrospectXml.itemXml
}