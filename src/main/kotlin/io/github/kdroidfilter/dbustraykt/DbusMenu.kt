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

    private val lock = ReentrantReadWriteLock()
    private val items = LinkedHashMap<Int, MenuEntry>()
    private var menuVersion: UInt = 1u
    private var nextId = 1

    init {
        // Initialize root menu item - CRITICAL: must have correct structure
        items[ROOT_ID] = MenuEntry(ROOT_ID, label = "", visible = true) // visible=true pour le root
    }

    fun addItem(
        label: String,
        checkable: Boolean = false,
        checked: Boolean = false,
        parent: Int = ROOT_ID,
        onClick: (() -> Unit)? = null,
    ): Int {
        val id = nextId++
        lock.write {
            items[id] = MenuEntry(id, label, true, true, true, checkable, checked, false,
                onClick = onClick, parent = parent)
            items[parent]?.children?.add(id)
            bumpVersionLocked()
        }
        emitLayoutUpdated()
        
        // Also emit ItemsPropertiesUpdated signal for the new item
        val propertyKeys = listOf("label", "enabled", "visible", "toggle-type", "toggle-state")
        emitItemsPropertiesUpdated(id, propertyKeys)
        
        return id
    }

    fun addSeparator(parent: Int = ROOT_ID): Int {
        val id = nextId++
        lock.write {
            items[id] = MenuEntry(id, label = "", enabled = true, visible = true, visibleSet = true, sep = true, parent = parent)
            items[parent]?.children?.add(id)
            bumpVersionLocked()
        }
        emitLayoutUpdated()
        
        // Also emit ItemsPropertiesUpdated signal for the new separator
        val propertyKeys = listOf("type", "visible")
        emitItemsPropertiesUpdated(id, propertyKeys)
        
        return id
    }

    fun setLabel(id: Int, label: String) = mutate(id) { it.label = label ; listOf("label") }
    fun setEnabled(id: Int, enabled: Boolean) = mutate(id) { it.enabled = enabled ; listOf("enabled") }
    fun setVisible(id: Int, visible: Boolean) = mutate(id) { it.visible = visible ; it.visibleSet = true ; listOf("visible") }
    fun setChecked(id: Int, checked: Boolean) = mutate(id) {
        if (it.checkable) { it.checked = checked ; listOf("toggle-state") } else emptyList() }

    fun resetMenu() {
        lock.write {
            items.clear()
            items[ROOT_ID] = MenuEntry(ROOT_ID, label = "", visible = true)
            nextId = 1
            bumpVersionLocked()
        }
        emitLayoutUpdated()
        
        // Also emit ItemsPropertiesUpdated signal for the root menu item
        // This ensures clients know the menu has been completely reset
        val propertyKeys = listOf("children-display")
        emitItemsPropertiesUpdated(ROOT_ID, propertyKeys)
    }

    override fun GetLayout(parentID: Int, recursionDepth: Int, propertyNames: Array<String>): LayoutReply {
        println("GetLayout called: parentID=$parentID, recursionDepth=$recursionDepth, propertyNames=${propertyNames.joinToString()}")
        
        return lock.read {
            // Check if the requested parent ID exists
            val parentExists = items.containsKey(parentID)
            println("  -> Parent ID $parentID exists: $parentExists")
            
            if (!parentExists) {
                println("  -> WARNING: Requested parent ID $parentID does not exist in menu!")
                // Return an empty layout if the parent doesn't exist
                return@read LayoutReply(UInt32(menuVersion.toLong()), LayoutNode(0, emptyMap(), emptyArray()))
            }
            
            // Build the layout node
            val node = buildLayoutNodeLocked(parentID, recursionDepth)
            
            // Log the layout being returned
            println("  -> Returning layout with revision=${menuVersion}")
            println("  -> Root node: id=${node.id}, properties=${node.properties.keys.joinToString()}, children=${node.children.size}")
            
            // Log the menu structure
            println("  -> Menu structure being returned:")
            logMenuStructure(parentID, 0)
            
            LayoutReply(UInt32(menuVersion.toLong()), node)
        }
    }

    override fun GetGroupProperties(ids: Array<Int>, propertyNames: Array<String>): Array<MenuProperty> =
        lock.read {
            ids.mapNotNull { id ->
                items[id]?.let { MenuProperty(it.id, propsLocked(it)) }
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

    override fun AboutToShow(id: Int): Boolean {
        println("AboutToShow called for id=$id")
        // Simply return false, like the Go implementation
        // This tells the system that no update is needed
        return false
    }
    override fun AboutToShowGroup(ids: Array<Int>): ShowGroupReply = ShowGroupReply(emptyArray(), emptyArray())
    override fun getObjectPath(): String = objectPath

    private fun handleEvent(id: Int, eventID: String) {
        println("Event received: id=$id, eventID=$eventID")
        
        // Only handle "clicked" events, like the Go implementation
        if (eventID == "clicked") {
            val onClick: (() -> Unit)?
            lock.read {
                val e = items[id] ?: return
                if (e.sep) return
                println("Menu item clicked: id=$id, label=${e.label}")
                onClick = e.onClick
            }
            onClick?.invoke()
        }
    }

    private fun buildLayoutNodeLocked(id: Int, depth: Int): LayoutNode {
        val e = items[id] ?: return LayoutNode(0, emptyMap(), emptyArray())

        val childVariants: Array<Variant<*>> = if (depth == 0 || e.children.isEmpty()) {
            emptyArray()
        } else {
            val childDepth = if (depth > 0) depth - 1 else depth
            e.children.map { childId ->
                Variant(buildLayoutNodeLocked(childId, childDepth))
            }.toTypedArray()
        }

        return LayoutNode(e.id, propsLocked(e), childVariants)
    }

    private fun propsLocked(e: MenuEntry): Map<String, Variant<*>> {
        val p = LinkedHashMap<String, Variant<*>>()
        if (e.sep) {
            p["type"] = Variant("separator")
            p["visible"] = Variant(true)
            return p
        }
        if (e.id != ROOT_ID) {  // Skip non-essential props for root
            p["label"] = Variant(e.label)
            p["enabled"] = Variant(e.enabled)
            p["visible"] = Variant(e.visible)
            if (e.checkable) {
                p["toggle-type"] = Variant("checkmark")
                p["toggle-state"] = Variant(if (e.checked) 1 else 0)
            } else {
                p["toggle-type"] = Variant("")
                p["toggle-state"] = Variant(0)
            }
        }
        if (e.children.isNotEmpty()) {
            p["children-display"] = Variant("submenu")
        }
        return p
    }

    private fun mutate(id: Int, op: (MenuEntry) -> List<String>) {
        val changedKeys: List<String>
        val entry: MenuEntry?
        lock.write {
            val e = items[id] ?: return
            changedKeys = op(e)
            if (changedKeys.isNotEmpty()) bumpVersionLocked()
            entry = e
        }
        if (changedKeys.isNotEmpty()) {
            emitLayoutUpdated()
            // Also emit ItemsPropertiesUpdated signal to notify about specific property changes
            if (entry != null) {
                emitItemsPropertiesUpdated(id, changedKeys)
            }
        }
    }

    private fun bumpVersionLocked() {
        menuVersion++
    }

    internal fun emitLayoutUpdated() {
        println("Emitting LayoutUpdated signal with version=${menuVersion}")
        runCatching {
            // Update the Version property
            val versionVariant = Variant(UInt32(menuVersion.toLong()))
            
            // Emit the LayoutUpdated signal
            conn.sendMessage(LayoutUpdatedSignal(objectPath, UInt32(menuVersion.toLong()), ROOT_ID))
            println("  -> Sent LayoutUpdatedSignal with revision=${menuVersion}, parent=${ROOT_ID}")
            
            // Also emit a PropertiesChanged signal to notify clients that the Version property has changed
            val changedProps = mapOf("Version" to versionVariant)
            val signal = org.freedesktop.dbus.interfaces.Properties.PropertiesChanged(
                objectPath, IFACE_MENU, changedProps, emptyList()
            )
            conn.sendMessage(signal)
            println("  -> Sent PropertiesChanged signal for Version property")
            
            // Log the current menu structure
            lock.read {
                println("  -> Current menu structure:")
                logMenuStructure(ROOT_ID, 0)
            }
        }.onFailure { System.err.println("ERROR in emitLayoutUpdated(): ${it.message}") }
    }
    
    private fun logMenuStructure(id: Int, indent: Int) {
        val entry = items[id] ?: return
        val indentStr = "    ".repeat(indent)
        if (entry.sep) {
            println("$indentStr- Separator (id=${entry.id})")
        } else {
            val childrenInfo = if (entry.children.isNotEmpty()) ", children=${entry.children.size}" else ""
            println("$indentStr- ${entry.label} (id=${entry.id}, enabled=${entry.enabled}, visible=${entry.visible}$childrenInfo)")
            entry.children.forEach { childId ->
                logMenuStructure(childId, indent + 1)
            }
        }
    }
    
    /**
     * This method is intentionally disabled to match the Go implementation,
     * which doesn't emit the ItemsPropertiesUpdated signal.
     * The Go implementation only uses the LayoutUpdated signal to notify clients about changes.
     */
    internal fun emitItemsPropertiesUpdated(id: Int, changedKeys: List<String>) {
        // Do nothing - this signal is not used in the Go implementation
    }

    override fun <T : Any?> Get(iface: String?, prop: String?): T {
        require(iface == IFACE_MENU) { "Unknown interface: $iface" }
        @Suppress("UNCHECKED_CAST")
        return when (prop) {
            "Version"       -> UInt32(menuVersion.toLong()) as T  // Current menu version
            "Status"        -> "normal" as T
            "TextDirection" -> "ltr" as T
            "IconThemePath" -> emptyArray<String>() as T
            else            -> throw IllegalArgumentException("Unknown property: $prop")
        }
    }

    override fun <A : Any?> Set(iface: String?, prop: String?, value: A?) {
        // Menu properties are read-only
    }

    override fun GetAll(iface: String?): Map<String, Variant<*>> = mapOf(
        "Version" to Variant(UInt32(menuVersion.toLong())),  // Current menu version
        "Status" to Variant("normal"),
        "TextDirection" to Variant("ltr"),
        "IconThemePath" to Variant(emptyArray<String>())
    )

    override fun Introspect(): String = IntrospectXml.menuXml
    
    /**
     * Updates all menu item properties and emits the appropriate signals.
     * This is useful when the menu needs to be refreshed completely.
     */
    fun updateAllMenuItemProperties() {
        println("Updating all menu item properties")
        lock.read {
            items.values.forEach { entry ->
                val propertyKeys = if (entry.sep) {
                    listOf("type", "visible")
                } else {
                    listOf("label", "enabled", "visible", "toggle-type", "toggle-state", "children-display")
                }
                emitItemsPropertiesUpdated(entry.id, propertyKeys)
            }
        }
        println("All menu item properties updated")
    }
}