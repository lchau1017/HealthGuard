package com.medguard.di

import com.medguard.BuildConfig
import com.medguard.confirm.ConfirmViewModel
import com.medguard.shared.data.DriverFactory
import com.medguard.shared.data.MedicationRepository
import com.medguard.shared.db.MedGuardDb
import com.medguard.shared.extraction.ProxyVisionExtractor
import com.medguard.shared.extraction.VisionExtractor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single {
        HttpClient(OkHttp) {
            // ProxyVisionExtractor requires the caller to bound request time;
            // without this a hung proxy would suspend extraction forever.
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
            }
        }
    }

    single<VisionExtractor> { ProxyVisionExtractor(get(), BuildConfig.PROXY_BASE_URL) }

    single { DriverFactory(androidContext()) }
    single { MedGuardDb(get<DriverFactory>().createDriver()) }
    single { MedicationRepository(get(), Dispatchers.IO) }

    viewModel { ConfirmViewModel(get(), Dispatchers.IO) }
}
