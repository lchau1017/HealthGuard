@file:OptIn(ExperimentalTime::class)

package com.healthguard.di

import com.healthguard.BuildConfig
import com.healthguard.activity.ActivityViewModel
import com.healthguard.confirm.ConfirmViewModel
import com.healthguard.detail.DetailViewModel
import com.healthguard.detail.domain.ComputeDetailStateUseCase
import com.healthguard.detail.domain.LoadDayDetailUseCase
import com.healthguard.detail.domain.SaveMedicationUseCase
import com.healthguard.home.HomeViewModel
import com.healthguard.home.domain.ActivateMedicationUseCase
import com.healthguard.home.domain.ComputeHomeStateUseCase
import com.healthguard.home.domain.DeleteMedicationUseCase
import com.healthguard.home.domain.RecordDoseUseCase
import com.healthguard.home.domain.StopMedicationUseCase
import com.healthguard.home.domain.UndoDoseUseCase
import com.healthguard.shared.data.DriverFactory
import com.healthguard.shared.data.MedicationRepository
import com.healthguard.shared.data.SqlDelightMedicationRepository
import com.healthguard.shared.db.HealthGuardDb
import com.healthguard.shared.extraction.ProxyVisionExtractor
import com.healthguard.shared.extraction.VisionExtractor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.TimeZone
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
    single { HealthGuardDb(get<DriverFactory>().createDriver()) }
    single<MedicationRepository> { SqlDelightMedicationRepository(get(), Dispatchers.IO) }

    single<() -> Instant> { { Clock.System.now() } }

    factory { ComputeHomeStateUseCase(get(), get(), TimeZone.currentSystemDefault()) }
    factory { RecordDoseUseCase(get(), get()) }
    factory { UndoDoseUseCase(get()) }
    factory { ActivateMedicationUseCase(get(), get()) }
    factory { StopMedicationUseCase(get(), get()) }
    factory { DeleteMedicationUseCase(get()) }

    factory { ComputeDetailStateUseCase(get(), get(), TimeZone.currentSystemDefault()) }
    factory { LoadDayDetailUseCase(get(), get(), TimeZone.currentSystemDefault()) }
    factory { SaveMedicationUseCase(get()) }

    viewModel { ConfirmViewModel(get(), get(), Dispatchers.IO, get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ActivityViewModel(get(), get()) }
    viewModel { (medicationId: String) ->
        DetailViewModel(
            computeDetailState = get(),
            loadDayDetail = get(),
            saveMedication = get(),
            recordDose = get(),
            undoDose = get(),
            activateMedication = get(),
            stopMedication = get(),
            deleteMedication = get(),
            repository = get(),
            clock = get(),
            medicationId = medicationId,
        )
    }
}
