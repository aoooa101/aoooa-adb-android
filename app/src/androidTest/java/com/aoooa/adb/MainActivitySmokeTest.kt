package com.aoooa.adb

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主界面启动冒烟测试：验证 Activity 可正常创建且未提前结束。
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun activityLaunchesWithoutFinishing() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                assertNotNull(activity)
                assertFalse("主界面启动后不应处于结束状态", activity.isFinishing)
            }
        } finally {
            scenario.close()
        }
    }
}
