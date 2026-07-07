package com.medguard

import android.app.Application
import com.medguard.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MedGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MedGuardApplication)
            modules(appModule)
        }
    }
}
