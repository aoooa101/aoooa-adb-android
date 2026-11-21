package com.aoooa.adb

import com.aoooa.adb.ui.AdbPrivilegedApp
import com.aoooa.adb.ui.KNOWN_ADB_APPS
import com.aoooa.adb.ui.tools.installedCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationCandidatesTest {
    private fun app(id: String, pkg: String) = AdbPrivilegedApp(id, pkg, id, id, "", "", emptyList())

    @Test fun emptyDeviceNeverListsTemplates() {
        assertTrue(installedCandidates(emptySet(), KNOWN_ADB_APPS).isEmpty())
    }

    @Test fun unrelatedInstalledPackagesProduceNoCandidates() {
        assertTrue(installedCandidates(setOf("test.unrelated"), KNOWN_ADB_APPS).isEmpty())
    }

    @Test fun onlyInstalledMatchesRemainAndDuplicateIdsAreRemoved() {
        val known = app("known", "test.known")
        val dynamic = app("dyn_test.dynamic", "test.dynamic")
        val absent = app("absent", "test.absent")
        assertEquals(listOf(dynamic, known), installedCandidates(setOf(known.packageName, dynamic.packageName), listOf(known, absent, dynamic, known)))
    }
}
