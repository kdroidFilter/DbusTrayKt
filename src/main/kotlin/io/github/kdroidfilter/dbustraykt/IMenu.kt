package io.github.kdroidfilter.dbustraykt

/**
 * Interface for menu operations, similar to the Go implementation.
 */
interface IMenu {
    /**
     * Shows the menu.
     * This is called when the user right-clicks on the tray icon.
     */
    fun showMenu()
}