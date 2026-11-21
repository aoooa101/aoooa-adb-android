package com.aoooa.adb.ui.tools

internal object VisibleSelection {
    fun count(visible: List<String>, selected: Collection<String>): Int {
        val ids = selected.toHashSet()
        return visible.count { it in ids }
    }

    fun toggle(visible: List<String>, selected: Collection<String>): Set<String> {
        val ids = selected.toMutableSet()
        if (visible.all { it in ids }) ids.removeAll(visible.toSet()) else ids.addAll(visible)
        return ids
    }
}
