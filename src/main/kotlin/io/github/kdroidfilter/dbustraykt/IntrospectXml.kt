package io.github.kdroidfilter.dbustraykt

object IntrospectXml {
    val menuXml = """
        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
        "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
        <node name="$PATH_MENU">
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

    val itemXml = """
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