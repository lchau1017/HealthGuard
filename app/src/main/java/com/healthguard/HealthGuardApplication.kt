package com.healthguard

import android.app.Application
import com.healthguard.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class HealthGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@HealthGuardApplication)
            modules(appModule)
        }
    }
}
