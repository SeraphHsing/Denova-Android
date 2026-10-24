package com.denova.novara

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器测试冒烟（本仓第一个 androidTest）：冷启动 MainActivity，确认能到 RESUMED 且重建后仍存活。
 *
 * 只验证「应用能起来」这一层：未登录时落在连接页，不需要服务器，也不依赖网络。
 * Compose 内容不在 View 树里，所以这里不做文案断言，避免引入 Espresso + compose-ui-test 的额外依赖。
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun launchesToResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun survivesRecreate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
