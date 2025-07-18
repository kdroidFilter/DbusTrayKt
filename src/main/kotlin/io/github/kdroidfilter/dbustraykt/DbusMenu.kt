package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class DbusMenu(private val conn: DBusConnection, private val objectPath: String = PATH_MENU) :
    DbusMenuMinimal, Properties, Introspectable {

    class LayoutUpdatedSignal(path: String, revision: UInt32, parent: Int = ROOT_ID) :
        DBusSignal(path, IFACE_MENU, "LayoutUpdated", revision, parent), DBusInterface {
        override fun getObjectPath(): String = path
    }

    class RemovedProperty(
        @field:Position(0) val id: Int,
        @field:Position(1) val properties: Array<String>
    ) : Struct()

    class ItemsPropertiesUpdatedSignal(
        path: String,
        updated: Array<MenuProperty>,
        removed: Array<RemovedProperty>
    ) : DBusSignal(path, IFACE_MENU, "ItemsPropertiesUpdated", updated, removed), DBusInterface {
        override fun getObjectPath(): String = path
    }

    class PropertiesChangedSignal(path: String, iface: String, changed: Map<String, Variant<*>>) :
        DBusSignal(path, "org.freedesktop.DBus.Properties", "PropertiesChanged", iface, changed, emptyArray<String>()), DBusInterface {
        override fun getObjectPath(): String = path
    }

    private val lock = ReentrantReadWriteLock()
    private val items = LinkedHashMap<Int, MenuEntry>()
    private var menuVersion: UInt = 1u

    init {
        items[ROOT_ID] = MenuEntry(ROOT_ID, label = "root", visible = false)
    }

    fun addItem(
        id: Int,
        label: String,
        checkable: Boolean = false,
        checked: Boolean = false,
        parent: Int = ROOT_ID,
        onClick: (() -> Unit)? = null,
    ): Int {
        lock.write {
            items[id] = MenuEntry(id, label, true, true, checkable, checked, false,
                onClick = onClick, parent = parent)
            items[parent]?.children?.add(id)
            bumpVersionLocked()
        }
        emitLayoutUpdated()
        return id
    }

    fun addSeparator(id: Int, parent: Int = ROOT_ID): Int {
        lock.write {
            items[id] = MenuEntry(id, label = "", enabled = false, sep = true, parent = parent)
            items[parent]?.children?.add(id)
            bumpVersionLocked()
        }
        emitLayoutUpdated()
        return id
    }

    fun setLabel(id: Int, label: String) = mutate(id) { it.label = label ; listOf("label") }
    fun setEnabled(id: Int, enabled: Boolean) = mutate(id) { it.enabled = enabled ; listOf("enabled") }
    fun setVisible(id: Int, visible: Boolean) = mutate(id) { it.visible = visible ; listOf("visible") }
    fun setChecked(id: Int, checked: Boolean) = mutate(id) {
        if (it.checkable) { it.checked = checked ; listOf("toggle-state") } else emptyList() }

    fun resetMenu() {
        lock.write {
            items.clear()
            items[ROOT_ID] = MenuEntry(ROOT_ID, label = "root", visible = false)
            bumpVersionLocked()
        }
        emitLayoutUpdated()
    }

    override fun GetLayout(parentID: Int, recursionDepth: Int, propertyNames: Array<String>): LayoutReply =
        lock.read {
            val node = buildLayoutNodeLocked(parentID, recursionDepth)
            LayoutReply(UInt32(menuVersion.toLong()), node)
        }

    override fun GetGroupProperties(ids: Array<Int>, propertyNames: Array<String>): Array<MenuProperty> =
        lock.read {
            ids.mapNotNull { id ->
                items[id]?.let { MenuProperty(it.id, propsLocked(it)) }  // Ignore propertyNames to match Go (return all)
            }.toTypedArray()
        }

    override fun GetProperty(id: Int, name: String): Variant<*> =
        lock.read {
            items[id]?.let { propsLocked(it)[name] } ?: Variant(null)
        }

    override fun Event(id: Int, eventID: String, data: Variant<*>, timestamp: UInt32) {
        handleEvent(id, eventID)
    }

    override fun EventGroup(events: Array<EventStruct>): Array<Int> {
        events.forEach { handleEvent(it.v0, it.v1) }
        return emptyArray()
    }

    override fun AboutToShow(id: Int): Boolean = false
    override fun AboutToShowGroup(ids: Array<Int>): ShowGroupReply = ShowGroupReply(emptyArray(), emptyArray())
    override fun getObjectPath(): String = objectPath

    private fun handleEvent(id: Int, eventID: String) {
        if (eventID != "clicked") return
        val entryCopy: MenuEntry?
        lock.write {
            val e = items[id] ?: return
            if (e.sep) return
            println("Menu item clicked: id=$id, label=${e.label}")
            entryCopy = e.copy()
        }
        entryCopy?.onClick?.invoke()
    }

    private fun buildLayoutNodeLocked(id: Int, depth: Int): LayoutNode {
        val e = items[id] ?: items[ROOT_ID]!!
        val childVariants: Array<Variant<*>> = if (depth == 0) emptyArray() else {
            e.children.map { Variant(buildLayoutNodeLocked(it, depth - 1)) as Variant<*> }.toTypedArray()
        }
        return LayoutNode(e.id, propsLocked(e), childVariants)
    }

    private fun propsLocked(e: MenuEntry): Map<String, Variant<*>> {
        val p = LinkedHashMap<String, Variant<*>>()
        fun put(key: String, v: Any) { p[key] = Variant(v) }

        if (e.sep) {
            put("type", "separator")
            return p
        }
        if (e.id != ROOT_ID) {  // Skip unnecessary props for root
            put("label", e.label)
            put("enabled", e.enabled)
            put("visible", e.visible)
        }
        if (e.checkable) {
            put("toggle-type", "checkmark")
            put("toggle-state", if (e.checked) 1 else 0)
        }
        if (e.children.isNotEmpty()) put("children-display", "submenu")
        return p
    }

    private fun mutate(id: Int, op: (MenuEntry) -> List<String>): Unit {
        val changedKeys: List<String>
        lock.write {
            val e = items[id] ?: return
            changedKeys = op(e)
            if (changedKeys.isNotEmpty()) bumpVersionLocked()
        }
        if (changedKeys.isNotEmpty()) emitLayoutUpdated()
    }

    private fun bumpVersionLocked() {
        menuVersion++
        emitMenuPropertiesChanged(mapOf("Version" to Variant(UInt32(menuVersion.toLong()))))
    }

    internal fun emitLayoutUpdated() {
        runCatching {
            conn.sendMessage(LayoutUpdatedSignal(objectPath, UInt32(menuVersion.toLong()), ROOT_ID))
        }.onFailure { System.err.println("emitLayoutUpdated(): ${it.message}") }
    }

    fun emitMenuPropertiesChanged(changed: Map<String, Variant<*>>) {
        runCatching {
            conn.sendMessage(PropertiesChangedSignal(objectPath, IFACE_MENU, changed))
        }.onFailure { System.err.println("emitMenuPropertiesChanged(): ${it.message}") }
    }

    override fun <T : Any?> Get(iface: String?, prop: String?): T {
        require(iface == IFACE_MENU)
        @Suppress("UNCHECKED_CAST")
        return when (prop) {
            "Version"       -> UInt32(menuVersion.toLong())
            "Status"        -> "normal"
            "TextDirection" -> "ltr"
            "IconThemePath" -> emptyArray<String>()
            else             -> Variant(null)
        } as T
    }

    override fun <A : Any?> Set(iface: String?, prop: String?, value: A?) {}

    override fun GetAll(iface: String?): Map<String, Variant<*>> = mapOf(
        "Version" to Variant(UInt32(menuVersion.toLong())),
        "Status" to Variant("normal"),
        "TextDirection" to Variant("ltr"),
        "IconThemePath" to Variant(emptyArray<String>())
    )

    override fun Introspect(): String = IntrospectXml.menuXml
}