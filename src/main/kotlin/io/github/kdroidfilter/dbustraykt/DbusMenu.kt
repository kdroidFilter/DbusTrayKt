package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val ROOT_ID = 0

@DBusInterfaceName(Systray.IFACE_MENU)
interface DbusMenuMinimal : DBusInterface {
    fun getLayout(parentID: Int, recursionDepth: Int, propertyNames: Array<String>): LayoutReply
    fun getGroupProperties(ids: Array<Int>, propertyNames: Array<String>): Array<MenuProperty>
    fun getProperty(id: Int, name: String): Variant<*>
    fun event(id: Int, eventID: String, data: Variant<*>, timestamp: UInt32)
    fun eventGroup(events: Array<EventStruct>): Array<Int>
    fun aboutToShow(id: Int): Boolean
    fun aboutToShowGroup(ids: Array<Int>): ShowGroupReply
}

data class MenuEntry(
    val id: Int,
    var label: String,
    var enabled: Boolean = true,
    var visible: Boolean = true,
    var checkable: Boolean = false,
    var checked: Boolean = false,
    var sep: Boolean = false,
    val children: MutableList<Int> = mutableListOf(),
    val onClick: (() -> Unit)? = null,
    val onToggle: ((Boolean) -> Unit)? = null,
    val parent: Int = ROOT_ID
)

class DbusMenu(private val conn: DBusConnection, private val objectPath: String = Systray.PATH_MENU) : DbusMenuMinimal, Properties {
    private val logger: Logger = LoggerFactory.getLogger(DbusMenu::class.java)

    class LayoutUpdatedSignal(path: String, revision: UInt32, parent: Int)
        : DBusSignal(path, Systray.IFACE_MENU, "LayoutUpdated", revision, parent), DBusInterface {
        override fun getObjectPath(): String = path
    }

    class ItemsPropertiesUpdatedSignal(
        path: String,
        updated: Array<Array<Any>>,
        removed: Array<Array<Any>>
    ) : DBusSignal(path, Systray.IFACE_MENU, "ItemsPropertiesUpdated", updated, removed), DBusInterface {
        override fun getObjectPath(): String = path
    }

    private val lock = ReentrantReadWriteLock()
    private val items = LinkedHashMap<Int, MenuEntry>()
    @Volatile private var revision: UInt = 1u
    @Volatile private var version: UInt32 = UInt32(revision.toLong())

    init {
        logger.debug("Initializing DbusMenu at path: {}", objectPath)
        items[ROOT_ID] = MenuEntry(ROOT_ID, label = "root", visible = false)
        logger.debug("Created root menu entry with ID: {}", ROOT_ID)
    }

    fun addItem(id: Int, label: String, checkable: Boolean, checked: Boolean, onClick: (() -> Unit)? = null, onToggle: ((Boolean) -> Unit)? = null, parent: Int = ROOT_ID): Int {
        logger.debug("Adding menu item: '{}' with id {} to parent {}, checkable: {}, checked: {}", 
            label, id, parent, checkable, checked)
        lock.write {
            val e = MenuEntry(id, label, true, true, checkable, checked, false, mutableListOf(), onClick, onToggle, parent)
            items[id] = e
            items[parent]?.children?.add(id)
            bumpRevisionLocked()
            logger.trace("Menu item added to internal structure, revision bumped")
        }
        logger.debug("Emitting layout updated signal for new menu item")
        emitLayoutUpdated()
        return id
    }

    fun addSeparator(id: Int, parent: Int = ROOT_ID): Int {
        logger.debug("Adding separator with id {} to parent {}", id, parent)
        lock.write {
            val e = MenuEntry(id, label = "", sep = true, enabled = false, visible = true, parent = parent)
            items[id] = e
            items[parent]?.children?.add(id)
            bumpRevisionLocked()
            logger.trace("Separator added to internal structure, revision bumped")
        }
        logger.debug("Emitting layout updated signal for new separator")
        emitLayoutUpdated()
        return id
    }

    fun setLabel(id: Int, label: String) = mutate(id) { it.label = label; "label" }
    fun setEnabled(id: Int, enabled: Boolean) = mutate(id) { it.enabled = enabled; "enabled" }
    fun setVisible(id: Int, visible: Boolean) = mutate(id) { it.visible = visible; "visible" }
    fun setChecked(id: Int, checked: Boolean) = mutate(id) {
        if (it.checkable) {
            it.checked = checked
            "toggle-state"
        } else null
    }

    private fun mutate(id: Int, op: (MenuEntry) -> String?): Unit {
        logger.debug("Mutating menu item with id {}", id)
        var key: String? = null
        var changed = false
        lock.write {
            val e = items[id] ?: run {
                logger.warn("Attempted to mutate non-existent menu item with id {}", id)
                return
            }
            key = op(e)
            if (key != null) {
                logger.debug("Changed property '{}' for menu item {}", key, id)
                changed = true
                bumpRevisionLocked()
                logger.trace("Menu item updated in internal structure, revision bumped")
            } else {
                logger.debug("No changes made to menu item {}", id)
            }
        }
        if (changed) {
            logger.debug("Emitting properties updated signal for menu item {}, property: {}", id, key)
            emitItemsPropertiesUpdated(listOf(id), listOfNotNull(key))
        }
    }

    override fun getLayout(parentID: Int, recursionDepth: Int, propertyNames: Array<String>): LayoutReply =
        lock.read {
            val rootNode = buildLayoutNodeLocked(parentID, recursionDepth)
            LayoutReply(UInt32(revision.toLong()), rootNode)
        }

    override fun getGroupProperties(ids: Array<Int>, propertyNames: Array<String>): Array<MenuProperty> =
        lock.read {
            ids.mapNotNull { id -> items[id]?.let { MenuProperty(it.id, propsLocked(it, propertyNames.toList())) } }.toTypedArray()
        }

    override fun getProperty(id: Int, name: String): Variant<*> =
        lock.read { items[id]?.let { propsLocked(it)[name] } ?: Variant(null) }

    override fun event(id: Int, eventID: String, data: Variant<*>, timestamp: UInt32) { handleEvent(id, eventID) }
    override fun eventGroup(events: Array<EventStruct>): Array<Int> { events.forEach { handleEvent(it.v0, it.v1) }; return emptyArray() }
    override fun aboutToShow(id: Int): Boolean = false
    override fun aboutToShowGroup(ids: Array<Int>): ShowGroupReply = ShowGroupReply(emptyArray(), emptyArray())
    override fun getObjectPath(): String = objectPath

    private fun handleEvent(id: Int, eventID: String) {
        if (eventID != "clicked") return
        var toggleChanged = false
        var eCopy: MenuEntry? = null
        lock.write {
            val e = items[id] ?: return
            eCopy = e
            if (e.sep) return
            if (e.checkable) {
                e.checked = !e.checked
                toggleChanged = true
            }
        }
        eCopy?.onClick?.invoke()
        if (toggleChanged) {
            eCopy?.onToggle?.invoke(eCopy!!.checked)
            emitItemsPropertiesUpdated(listOf(id), listOf("toggle-state"))
        }
    }

    private fun buildLayoutNodeLocked(id: Int, depth: Int): LayoutNode {
        val e = items[id] ?: items[ROOT_ID]!!
        val childrenVariants: Array<Variant<*>> = if (depth == 0) {
            emptyArray()
        } else {
            e.children.map { Variant<Any>(buildLayoutNodeLocked(it, depth - 1)) as Variant<*> }.toTypedArray()
        }
        return LayoutNode(e.id, propsLocked(e), childrenVariants)
    }

    private fun propsLocked(e: MenuEntry, filter: List<String> = emptyList()): Map<String, Variant<*>> {
        val m = LinkedHashMap<String, Variant<*>>()
        fun put(k: String, v: Any?) { if (filter.isEmpty() || k in filter) m[k] = Variant(v) }
        if (e.sep) put("type", "separator") else {
            put("label", e.label)
            put("enabled", e.enabled)
            put("visible", e.visible)
            if (e.checkable) {
                put("toggle-type", "checkmark")
                put("toggle-state", if (e.checked) 1 else 0)
            }
            if (e.children.isNotEmpty()) put("children-display", "submenu")
        }
        return m
    }

    private fun bumpRevisionLocked() {
        revision++
        version = UInt32(revision.toLong())
        logger.trace("Revision bumped to {}", revision)
    }

    internal fun emitLayoutUpdated(parent: Int = ROOT_ID) {
        logger.trace("Emitting LayoutUpdated signal for parent {}, revision {}", parent, revision)
        try { 
            conn.sendMessage(LayoutUpdatedSignal(objectPath, UInt32(revision.toLong()), parent))
            logger.trace("LayoutUpdated signal sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to emit LayoutUpdated signal: {}", e.message, e)
        }
    }

    internal fun emitItemsPropertiesUpdated(ids: List<Int>, keys: List<String>) {
        logger.trace("Emitting ItemsPropertiesUpdated signal for ids: {}, keys: {}", ids, keys)
        val updatedList = mutableListOf<Array<Any>>()
        lock.read {
            ids.forEach { id ->
                items[id]?.let { 
                    logger.trace("Adding properties for menu item {}", id)
                    updatedList += arrayOf(id, propsLocked(it, keys)) 
                } ?: logger.warn("Attempted to emit properties for non-existent menu item with id {}", id)
            }
        }
        
        if (updatedList.isEmpty()) {
            logger.debug("No properties to update, skipping signal emission")
            return
        }
        
        val removedList = emptyArray<Array<Any>>()
        try {
            logger.trace("Sending ItemsPropertiesUpdated signal with {} updated items", updatedList.size)
            conn.sendMessage(ItemsPropertiesUpdatedSignal(objectPath, updatedList.toTypedArray(), removedList))
            logger.trace("ItemsPropertiesUpdated signal sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to emit ItemsPropertiesUpdated signal: {}", e.message, e)
        }
    }

    override fun <T : Any?> Get(iface: String?, prop: String?): T {
        require(iface == Systray.IFACE_MENU)
        @Suppress("UNCHECKED_CAST")
        return when (prop) {
            "Version"        -> version
            "Status"         -> "normal"
            "TextDirection"  -> "ltr"
            "IconThemePath"  -> emptyArray<String>()
            else              -> Variant(null)
        } as T
    }

    override fun <A : Any?> Set(iface: String?, prop: String?, value: A?) {}

    override fun GetAll(iface: String?): Map<String, Variant<*>> = mapOf(
        "Version" to Variant(version),
        "Status" to Variant("normal"),
        "TextDirection" to Variant("ltr"),
        "IconThemePath" to Variant(emptyArray<String>())
    )
}

class LayoutReply(@field:Position(0) val revision: UInt32, @field:Position(1) val root: LayoutNode) : Struct()
class LayoutNode(@field:Position(0) val id: Int, @field:Position(1) val properties: Map<String, Variant<*>>, @field:Position(2) val children: Array<Variant<*>>) : Struct()
class MenuProperty(@field:Position(0) val v0: Int, @field:Position(1) val v1: Map<String, Variant<*>>) : Struct()
class EventStruct(@field:Position(0) val v0: Int, @field:Position(1) val v1: String, @field:Position(2) val v2: Variant<*>, @field:Position(3) val v3: UInt32) : Struct()
class ShowGroupReply(@field:Position(0) val updatesNeeded: Array<Int>, @field:Position(1) val idErrors: Array<Int>) : Struct()
