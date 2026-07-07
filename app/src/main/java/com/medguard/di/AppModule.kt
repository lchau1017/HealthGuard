@file:OptIn(ExperimentalTime::class)

package com.medguard.di

import com.medguard.BuildConfig
import com.medguard.confirm.ConfirmViewModel
import com.medguard.detail.DetailViewModel
import com.medguard.home.HomeViewModel
import com.medguard.shared.data.DriverFactory
import com.medguard.shared.data.MedicationRepository
import com.medguard.shared.db.MedGuardDb
import com.medguard.shared.extraction.ProxyVisionExtractor
import com.medguard.shared.extraction.VisionExtractor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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

    single<() -> Instant> { { Clock.System.now() } }

    viewModel { ConfirmViewModel(get(), get(), Dispatchers.IO, get()) }
    viewModel { HomeViewModel(get(), get()) }
    viewModel { (medicationId: String) ->
        DetailViewModel(repository = get(), clock = get(), medicationId = medicationId)
    }
}
