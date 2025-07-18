package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.imageio.ImageIO
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val ROOT_ID = 0
internal const val PATH_ITEM = "/StatusNotifierItem"
internal const val PATH_MENU = "/StatusNotifierMenu"
internal const val IFACE_SNI = "org.kde.StatusNotifierItem"
internal const val IFACE_MENU = "com.canonical.dbusmenu"

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

@DBusInterfaceName(IFACE_MENU)
interface DbusMenuMinimal : DBusInterface {
    fun GetLayout(parentID: Int, recursionDepth: Int, propertyNames: Array<String>): LayoutReply
    fun GetGroupProperties(ids: Array<Int>, propertyNames: Array<String>): Array<MenuProperty>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventID: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: Array<EventStruct>): Array<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: Array<Int>): ShowGroupReply
}

class DbusMenu(private val conn: DBusConnection, private val objectPath: String = PATH_MENU) :
    DbusMenuMinimal, Properties, Introspectable {

    class LayoutUpdatedSignal(path: String, revision: UInt32, parent: Int = ROOT_ID) :
        DBusSignal(path, IFACE_MENU, "LayoutUpdated", revision, parent), DBusInterface {
        override fun getObjectPath(): String = path
    }

    // New struct for removed properties
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
        onClick: (() -> Unit)? = null,
        onToggle: ((Boolean) -> Unit)? = null,
        parent: Int = ROOT_ID,
    ): Int {
        lock.write {
            items[id] = MenuEntry(id, label, true, true, checkable, checked, false,
                onClick = onClick, onToggle = onToggle, parent = parent)
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
                items[id]?.let { MenuProperty(it.id, propsLocked(it, propertyNames.toList())) }
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
        var toggleChanged = false
        val entryCopy: MenuEntry?
        lock.write {
            val e = items[id] ?: return
            if (e.sep) return
            println("Menu item clicked: id=$id, label=${e.label}")
            if (e.checkable) {
                e.checked = !e.checked
                toggleChanged = true
            }
            entryCopy = e.copy()
        }
        entryCopy?.onClick?.invoke()
        if (toggleChanged) {
            entryCopy?.onToggle?.invoke(entryCopy.checked)
        }
    }

    private fun buildLayoutNodeLocked(id: Int, depth: Int): LayoutNode {
        val e = items[id] ?: items[ROOT_ID]!!
        val childVariants: Array<Variant<*>> = if (depth == 0) emptyArray() else {
            e.children.map { Variant(buildLayoutNodeLocked(it, depth - 1)) as Variant<*> }.toTypedArray()
        }
        return LayoutNode(e.id, propsLocked(e), childVariants)
    }

    private fun propsLocked(e: MenuEntry, filter: List<String> = emptyList()): Map<String, Variant<*>> {
        val p = LinkedHashMap<String, Variant<*>>()
        fun put(key: String, v: Any) { if (filter.isEmpty() || key in filter) p[key] = Variant(v) }

        if (e.sep) {
            put("type", "separator")
            return p
        }
        if (e.id == ROOT_ID) {
            put("children-display", "submenu")
        }
        put("label", e.label)
        put("enabled", e.enabled)
        put("visible", e.visible)
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
            Systray.itemImpl.emitNewMenu()
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

    override fun Introspect(): String {
        return """
        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
        "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
        <node name="$objectPath">
            <interface name="org.freedesktop.DBus.Introspectable">
                <method name="Introspect">
                    <arg name="xml_data" type="s" direction="out"/>
                </method>
            </interface>
            <interface name="org.freedesktop.DBus.Properties">
                <method name="Get">
                    <arg name="interface_name" type="s" direction="in"/>
                    <arg name="property_name" type="s" direction="in"/>
                    <arg name="value" type="v" direction="out"/>
                </method>
                <method name="Set">
                    <arg name="interface_name" type="s" direction="in"/>
                    <arg name="property_name" type="s" direction="in"/>
                    <arg name="value" type="v" direction="in"/>
                </method>
                <method name="GetAll">
                    <arg name="interface_name" type="s" direction="in"/>
                    <arg name="properties" type="a{sv}" direction="out"/>
                </method>
            </interface>
            <interface name="com.canonical.dbusmenu">
                <method name="GetLayout">
                    <arg name="parentId" type="i" direction="in"/>
                    <arg name="recursionDepth" type="i" direction="in"/>
                    <arg name="propertyNames" type="as" direction="in"/>
                    <arg name="revision" type="u" direction="out"/>
                    <arg name="layout" type="(ia{sv}av)" direction="out"/>
                </method>
                <method name="GetGroupProperties">
                    <arg name="ids" type="ai" direction="in"/>
                    <arg name="propertyNames" type="as" direction="in"/>
                    <arg name="properties" type="a(ia{sv})" direction="out"/>
                </method>
                <method name="GetProperty">
                    <arg name="id" type="i" direction="in"/>
                    <arg name="name" type="s" direction="in"/>
                    <arg name="value" type="v" direction="out"/>
                </method>
                <method name="Event">
                    <arg name="id" type="i" direction="in"/>
                    <arg name="eventId" type="s" direction="in"/>
                    <arg name="data" type="v" direction="in"/>
                    <arg name="timestamp" type="u" direction="in"/>
                </method>
                <method name="EventGroup">
                    <arg name="events" type="a(isvu)" direction="in"/>
                    <arg name="idErrors" type="ai" direction="out"/>
                </method>
                <method name="AboutToShow">
                    <arg name="id" type="i" direction="in"/>
                    <arg name="needUpdate" type="b" direction="out"/>
                </method>
                <method name="AboutToShowGroup">
                    <arg name="ids" type="ai" direction="in"/>
                    <arg name="updatesNeeded" type="ai" direction="out"/>
                    <arg name="idErrors" type="ai" direction="out"/>
                </method>
                <signal name="ItemsPropertiesUpdated">
                    <arg name="updatedProps" type="a(ia{sv})"/>
                    <arg name="removedProps" type="a(ias)"/>
                </signal>
                <signal name="LayoutUpdated">
                    <arg name="revision" type="u"/>
                    <arg name="parent" type="i"/>
                </signal>
                <property name="Version" type="u" access="read"/>
                <property name="TextDirection" type="s" access="read"/>
                <property name="Status" type="s" access="read"/>
                <property name="IconThemePath" type="as" access="read"/>
            </interface>
        </node>
        """.trimIndent()
    }
}

class LayoutReply(@field:Position(0) val revision: UInt32,
                  @field:Position(1) val root: LayoutNode) : Struct()

class LayoutNode(@field:Position(0) val id: Int,
                 @field:Position(1) val properties: Map<String, Variant<*>>,
                 @field:Position(2) val children: Array<Variant<*>>) : Struct()

class MenuProperty(@field:Position(0) val v0: Int,
                   @field:Position(1) val v1: Map<String, Variant<*>>) : Struct()

class EventStruct(@field:Position(0) val v0: Int,
                  @field:Position(1) val v1: String,
                  @field:Position(2) val v2: Variant<*>,
                  @field:Position(3) val v3: UInt32) : Struct()

class ShowGroupReply(@field:Position(0) val updatesNeeded: Array<Int>,
                     @field:Position(1) val idErrors: Array<Int>) : Struct()

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(itemPath: String)
}

object Systray {
    private lateinit var conn: DBusConnection
    lateinit var itemImpl: StatusNotifierItemImpl
    private lateinit var menuImpl: DbusMenu
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private val idSrc = AtomicInteger(0)

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

        keepAlive()
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
        executor.shutdownNow()
    }

    @JvmStatic fun setIcon(bytes: ByteArray) = itemImpl.setIcon(bytes)
    @JvmStatic fun setTitle(t: String) = itemImpl.setTitle(t)
    @JvmStatic fun setTooltip(t: String) = itemImpl.setTooltip(t)

    @JvmStatic fun addMenuItem(label: String, onClick: (() -> Unit)? = null): Int =
        menuImpl.addItem(idSrc.incrementAndGet(), label, onClick = onClick)

    @JvmStatic fun addMenuItemCheckbox(label: String, checked: Boolean = false,
                                       onToggle: ((Boolean) -> Unit)? = null): Int =
        menuImpl.addItem(idSrc.incrementAndGet(), label, checkable = true, checked = checked,
            onToggle = onToggle)

    @JvmStatic fun addSeparator(): Int = menuImpl.addSeparator(idSrc.incrementAndGet())

    @JvmStatic fun setMenuItemLabel(id: Int, label: String) = menuImpl.setLabel(id, label)
    @JvmStatic fun setMenuItemEnabled(id: Int, enabled: Boolean) = menuImpl.setEnabled(id, enabled)
    @JvmStatic fun setMenuItemChecked(id: Int, checked: Boolean) = menuImpl.setChecked(id, checked)
    @JvmStatic fun setMenuItemVisible(id: Int, visible: Boolean) = menuImpl.setVisible(id, visible)

    @DBusInterfaceName(IFACE_SNI)
    interface StatusNotifierItem : DBusInterface {
        fun Activate(x: Int, y: Int)
        fun SecondaryActivate(x: Int, y: Int)
        fun ContextMenu(x: Int, y: Int)
        fun Scroll(delta: Int, orientation: String)
    }

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
            if (running && conn.isConnected) conn.sendMessage(sig)
        }.onFailure { System.err.println("Signal error: ${it.message}") }

        override fun getObjectPath(): String = PATH_ITEM

        override fun Introspect(): String {
            return """
            <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
            "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
            <node name="$PATH_ITEM">
                <interface name="org.freedesktop.DBus.Introspectable">
                    <method name="Introspect">
                        <arg name="xml_data" type="s" direction="out"/>
                    </method>
                </interface>
                <interface name="org.freedesktop.DBus.Properties">
                    <method name="Get">
                        <arg name="interface_name" type="s" direction="in"/>
                        <arg name="property_name" type="s" direction="in"/>
                        <arg name="value" type="v" direction="out"/>
                    </method>
                    <method name="Set">
                        <arg name="interface_name" type="s" direction="in"/>
                        <arg name="property_name" type="s" direction="in"/>
                        <arg name="value" type="v" direction="in"/>
                    </method>
                    <method name="GetAll">
                        <arg name="interface_name" type="s" direction="in"/>
                        <arg name="properties" type="a{sv}" direction="out"/>
                    </method>
                </interface>
                <interface name="org.kde.StatusNotifierItem">
                    <method name="Activate">
                        <arg name="x" type="i" direction="in"/>
                        <arg name="y" type="i" direction="in"/>
                    </method>
                    <method name="SecondaryActivate">
                        <arg name="x" type="i" direction="in"/>
                        <arg name="y" type="i" direction="in"/>
                    </method>
                    <method name="ContextMenu">
                        <arg name="x" type="i" direction="in"/>
                        <arg name="y" type="i" direction="in"/>
                    </method>
                    <method name="Scroll">
                        <arg name="delta" type="i" direction="in"/>
                        <arg name="orientation" type="s" direction="in"/>
                    </method>
                    <signal name="NewIcon"/>
                    <signal name="NewTitle"/>
                    <signal name="NewMenu"/>
                    <property name="Status" type="s" access="read"/>
                    <property name="Title" type="s" access="readwrite"/>
                    <property name="Id" type="s" access="read"/>
                    <property name="Category" type="s" access="read"/>
                    <property name="IconName" type="s" access="read"/>
                    <property name="IconPixmap" type="a(iiay)" access="readwrite"/>
                    <property name="ItemIsMenu" type="b" access="read"/>
                    <property name="Menu" type="o" access="read"/>
                    <property name="ToolTip" type="(sa(iiay)ss)" access="readwrite"/>
                </interface>
            </node>
            """.trimIndent()
        }
    }

    open class PxStruct(@field:Position(0) val w: Int,
                        @field:Position(1) val h: Int,
                        @field:Position(2) val pix: ByteArray) : Struct()

    class TooltipStruct(@field:Position(0) val v0: String,
                        @field:Position(1) val v1: List<PxStruct>,
                        @field:Position(2) val v2: String,
                        @field:Position(3) val v3: String) : Struct()

    private fun buildPixmaps(src: ByteArray): Array<PxStruct> {
        if (src.isEmpty()) return emptyArray()
        val img = ImageIO.read(ByteArrayInputStream(src)) ?: return emptyArray()
        val sizes = intArrayOf(16, 22, 24, 32, 48)
        return sizes.map { sz ->
            val scaled = BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB).apply {
                val g = createGraphics(); g.drawImage(img, 0, 0, sz, sz, null); g.dispose()
            }
            val pix = ByteArray(sz * sz * 4)
            var i = 0
            for (y in 0 until sz) for (x in 0 until sz) {
                val argb = scaled.getRGB(x, y)
                pix[i++] = ((argb ushr 24) and 0xFF).toByte()
                pix[i++] = ((argb ushr 16) and 0xFF).toByte()
                pix[i++] = ((argb ushr 8) and 0xFF).toByte()
                pix[i++] = (argb and 0xFF).toByte()
            }
            PxStruct(sz, sz, pix)
        }.toTypedArray()
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
        return ByteArrayOutputStream().use { ImageIO.write(img, "png", it); it.toByteArray() }
    }

    private fun keepAlive() {
        val t = Thread({ while (running && conn.isConnected) Thread.sleep(5000) }, "Systray-keepalive")
        t.isDaemon = false; t.start()
    }
}
