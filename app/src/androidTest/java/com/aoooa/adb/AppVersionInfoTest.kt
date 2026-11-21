package com.aoooa.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.pm.PackageInfoCompat
import com.aoooa.adb.util.AppVersionInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppVersionInfoTest {
    @Suppress("DEPRECATION")
    @Test fun readsInstalledApplicationWithoutAdb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = context.packageManager.getPackageInfo(context.packageName, 0)
        val actual = AppVersionInfo.read(context)
        assertEquals(expected.versionName, actual.name)
        assertEquals(PackageInfoCompat.getLongVersionCode(expected), actual.code)
        assertTrue(actual.logHeader().contains("versionName=${actual.name}"))
        assertTrue(actual.logHeader().contains("versionCode=${actual.code}"))
    }

    @Test fun includesUnixSecondsInFileHeader() {
        val header = AppVersionInfo("test", 42L).logHeader(createdUnixSec = 1_795_234_135L)
        assertTrue(header.endsWith("logCreatedUnixSec=1795234135 ===\n"))
    }

    @Test fun preservesLongCodeAndUnknownValues() {
        assertEquals("test (4294967296)", AppVersionInfo("test", 4294967296L).display("—"))
        assertEquals("— (—)", AppVersionInfo(null, null).display("—"))
        assertTrue(AppVersionInfo(null, null).logHeader().contains("versionCode=unknown"))
    }
}
