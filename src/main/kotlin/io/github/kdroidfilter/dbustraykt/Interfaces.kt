package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.types.UInt32

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

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(itemPath: String)
}

@DBusInterfaceName(IFACE_SNI)
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
}