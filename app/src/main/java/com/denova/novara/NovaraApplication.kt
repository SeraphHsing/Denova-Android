package com.denova.novara

import android.app.Application

/** 应用入口：持有 [AppContainer]，让组合根的生命周期与进程一致。 */
class NovaraApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
