package com.aoooa.adb.ui.selection

internal object VisibleSelection {
    fun <T> count(visible: List<T>, selected: Collection<T>): Int {
        val ids = selected.toHashSet()
        return visible.count { it in ids }
    }

    fun <T> retainVisible(visible: List<T>, selected: Set<T>): Set<T> = selected.intersect(visible.toSet())

    fun <T> toggle(visible: List<T>, selected: Collection<T>): Set<T> {
        val ids = selected.toMutableSet()
        if (visible.all { it in ids }) ids.removeAll(visible.toSet()) else ids.addAll(visible)
        return ids
    }
}
