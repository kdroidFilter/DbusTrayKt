package io.github.kdroidfilter.dbustraykt

object IntrospectXml {
    val menuXml = """
<?xml version="1.0" encoding="UTF-8"?>
<node name="/" xmlns:dox="http://www.canonical.com/dbus/dox.dtd">
<interface name="com.canonical.dbusmenu">
<!--  Properties  -->
<property name="Version" type="u" access="read"/>
<property name="TextDirection" type="s" access="read"/>
<property name="Status" type="s" access="read"/>
<property name="IconThemePath" type="as" access="read"/>
<!--  Functions  -->
<method name="GetLayout">
<arg type="i" name="parentId" direction="in"/>
<arg type="i" name="recursionDepth" direction="in"/>
<arg type="as" name="propertyNames" direction="in"/>
<arg type="u" name="revision" direction="out"/>
<arg type="(ia{sv}av)" name="layout" direction="out"/>
</method>
<method name="GetGroupProperties">
<arg type="ai" name="ids" direction="in"/>
<arg type="as" name="propertyNames" direction="in"/>
<arg type="a(ia{sv})" name="properties" direction="out"/>
</method>
<method name="GetProperty">
<arg type="i" name="id" direction="in"/>
<arg type="s" name="name" direction="in"/>
<arg type="v" name="value" direction="out"/>
</method>
<method name="Event">
<arg type="i" name="id" direction="in"/>
<arg type="s" name="eventId" direction="in"/>
<arg type="v" name="data" direction="in"/>
<arg type="u" name="timestamp" direction="in"/>
</method>
<method name="EventGroup">
<arg type="a(isvu)" name="events" direction="in"/>
<arg type="ai" name="idErrors" direction="out"/>
</method>
<method name="AboutToShow">
<arg type="i" name="id" direction="in"/>
<arg type="b" name="needUpdate" direction="out"/>
</method>
<method name="AboutToShowGroup">
<arg type="ai" name="ids" direction="in"/>
<arg type="ai" name="updatesNeeded" direction="out"/>
<arg type="ai" name="idErrors" direction="out"/>
</method>
<!--  Signals  -->
<signal name="ItemsPropertiesUpdated">
<arg type="a(ia{sv})" name="updatedProps" direction="out"/>
<arg type="a(ias)" name="removedProps" direction="out"/>
</signal>
<signal name="LayoutUpdated">
<arg type="u" name="revision" direction="out"/>
<arg type="i" name="parent" direction="out"/>
</signal>
<signal name="ItemActivationRequested">
<arg type="i" name="id" direction="out"/>
<arg type="u" name="timestamp" direction="out"/>
</signal>
</interface>
</node>

        """.trimIndent()

    val itemXml = """
        <?xml version="1.0" encoding="UTF-8"?>
<node name="/StatusNotifierItem">
<!--  Based on:
 https://invent.kde.org/frameworks/knotifications/-/blob/master/src/org.kde.StatusNotifierItem.xml
 -->
<interface name="org.kde.StatusNotifierItem">
<property name="Category" type="s" access="read"/>
<property name="Id" type="s" access="read"/>
<property name="Title" type="s" access="read"/>
<property name="Status" type="s" access="read"/>
<property name="WindowId" type="i" access="read"/>
<!--  An additional path to add to the theme search path to find the icons specified above.  -->
<property name="IconThemePath" type="s" access="read"/>
<property name="Menu" type="o" access="read"/>
<property name="ItemIsMenu" type="b" access="read"/>
<!--  main icon  -->
<!--  names are preferred over pixmaps  -->
<property name="IconName" type="s" access="read"/>
<!-- struct containing width, height and image data -->
<property name="IconPixmap" type="a(iiay)" access="read">
<annotation name="org.qtproject.QtDBus.QtTypeName" value="KDbusImageVector"/>
</property>
<property name="OverlayIconName" type="s" access="read"/>
<property name="OverlayIconPixmap" type="a(iiay)" access="read">
<annotation name="org.qtproject.QtDBus.QtTypeName" value="KDbusImageVector"/>
</property>
<!--  Requesting attention icon  -->
<property name="AttentionIconName" type="s" access="read"/>
<!-- same definition as image -->
<property name="AttentionIconPixmap" type="a(iiay)" access="read">
<annotation name="org.qtproject.QtDBus.QtTypeName" value="KDbusImageVector"/>
</property>
<property name="AttentionMovieName" type="s" access="read"/>
<!--  tooltip data  -->
<!-- (iiay) is an image -->
<property name="ToolTip" type="(sa(iiay)ss)" access="read">
<annotation name="org.qtproject.QtDBus.QtTypeName" value="KDbusToolTipStruct"/>
</property>
<!--  interaction: the systemtray wants the application to do something  -->
<method name="ContextMenu">
<!--  we're passing the coordinates of the icon, so the app knows where to put the popup window  -->
<arg name="x" type="i" direction="in"/>
<arg name="y" type="i" direction="in"/>
</method>
<method name="Activate">
<arg name="x" type="i" direction="in"/>
<arg name="y" type="i" direction="in"/>
</method>
<method name="SecondaryActivate">
<arg name="x" type="i" direction="in"/>
<arg name="y" type="i" direction="in"/>
</method>
<method name="Scroll">
<arg name="delta" type="i" direction="in"/>
<arg name="orientation" type="s" direction="in"/>
</method>
<method name="ProvideXdgActivationToken">
<arg name="token" type="s" direction="in"/>
</method>
<!--  Signals: the client wants to change something in the status -->
<signal name="NewTitle"> </signal>
<signal name="NewIcon"> </signal>
<signal name="NewAttentionIcon"> </signal>
<signal name="NewOverlayIcon"> </signal>
<!--  We disable this as we don't support tooltip, so no need to go through it
    <signal name="NewToolTip">
    </signal>
     -->
<signal name="NewStatus">
<arg name="status" type="s"/>
</signal>
<!--  The following items are not supported by specs, but widely used  -->
<signal name="NewIconThemePath">
<arg type="s" name="icon_theme_path" direction="out"/>
</signal>
<signal name="NewMenu"/>
<!--  ayatana labels  -->
<!--  These are commented out because GDBusProxy would otherwise require them,
         but they are not available for KDE indicators
     -->
<!-- <signal name="XAyatanaNewLabel">
        <arg type="s" name="label" direction="out" />
        <arg type="s" name="guide" direction="out" />
    </signal>
    <property name="XAyatanaLabel" type="s" access="read" />
    <property name="XAyatanaLabelGuide" type="s" access="read" /> -->
</interface>
</node>
            """.trimIndent()
}