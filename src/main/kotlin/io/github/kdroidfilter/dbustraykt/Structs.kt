package io.github.kdroidfilter.dbustraykt

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

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

open class PxStruct(@field:Position(0) val w: Int,
                    @field:Position(1) val h: Int,
                    @field:Position(2) val pix: ByteArray) : Struct()

class TooltipStruct(@field:Position(0) val v0: String,
                    @field:Position(1) val v1: List<PxStruct>,
                    @field:Position(2) val v2: String,
                    @field:Position(3) val v3: String) : Struct()

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
    val parent: Int = ROOT_ID
)