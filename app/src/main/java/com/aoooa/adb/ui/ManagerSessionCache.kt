package com.aoooa.adb.ui

import androidx.compose.runtime.mutableStateOf
import com.aoooa.adb.files.RemoteEntry
import com.aoooa.adb.ui.app.AppFilter
import com.aoooa.adb.ui.app.ManagedAppItem

internal class SessionListing<T>(private val retain: (T) -> Boolean) {
    val state = mutableStateOf<T?>(null)
    val loading = mutableStateOf(false)
    private var closed = false

    @Synchronized fun start(force: Boolean): Boolean {
        if (closed || loading.value || (!force && state.value != null)) return false
        loading.value = true
        return true
    }

    @Synchronized fun finish(value: T?): Boolean {
        if (closed) return false
        if (value != null) state.value = value.takeIf(retain)
        loading.value = false
        return true
    }

    @Synchronized fun close() {
        closed = true
        state.value = null
        loading.value = false
    }
}

internal data class AppListing(val apps: List<ManagedAppItem>, val ram: Pair<Long, Long>?)
internal data class DirectoryListing(val path: String, val entries: List<RemoteEntry>)

internal class ManagerSessionCache {
    val apps = SessionListing<AppListing> { it.apps.size <= 3000 && it.apps.sumOf { item -> item.packageName.length.toLong() + item.label.length } <= 512_000 }
    val directory = SessionListing<DirectoryListing> {
        it.entries.size <= 2000 && it.entries.sumOf { entry -> entry.name.length.toLong() + entry.path.length } <= 512_000
    }
    val appQuery = mutableStateOf("")
    val appFilter = mutableStateOf(AppFilter.ALL)
    val appScroll = mutableStateOf(0 to 0)
    val directoryPath = mutableStateOf("/sdcard")
    val directoryQuery = mutableStateOf("")
    val directoryScroll = mutableStateOf(0 to 0)

    fun close() {
        apps.close()
        directory.close()
        appQuery.value = ""
        appFilter.value = AppFilter.ALL
        appScroll.value = 0 to 0
        directoryPath.value = "/sdcard"
        directoryQuery.value = ""
        directoryScroll.value = 0 to 0
    }
}
