@file:OptIn(ExperimentalTime::class)

package com.healthguard.di

import com.healthguard.BuildConfig
import com.healthguard.activity.ActivityViewModel
import com.healthguard.activity.domain.ComputeActivityStateUseCase
import com.healthguard.activity.domain.LoadActivityDayDetailUseCase
import com.healthguard.chat.ChatAssistant
import com.healthguard.chat.ChatViewModel
import com.healthguard.chat.domain.BuildChatContextUseCase
import com.healthguard.confirm.AndroidImageEncoder
import com.healthguard.confirm.ConfirmViewModel
import com.healthguard.confirm.ImageEncoder
import com.healthguard.confirm.domain.ExtractMedicationUseCase
import com.healthguard.confirm.domain.SaveNewMedicationUseCase
import com.healthguard.detail.DetailViewModel
import com.healthguard.detail.domain.ComputeDetailStateUseCase
import com.healthguard.detail.domain.LoadDayDetailUseCase
import com.healthguard.detail.domain.SaveMedicationUseCase
import com.healthguard.home.HomeViewModel
import com.healthguard.home.domain.ActivateMedicationUseCase
import com.healthguard.home.domain.ComputeHomeStateUseCase
import com.healthguard.home.domain.DeleteMedicationUseCase
import com.healthguard.home.domain.RecordDoseUseCase
import com.healthguard.home.domain.RemoveDemoDataUseCase
import com.healthguard.home.domain.SeedDemoDataUseCase
import com.healthguard.home.domain.StopMedicationUseCase
import com.healthguard.home.domain.UndoDoseUseCase
import com.healthguard.data.DriverFactory
import com.healthguard.domain.model.MedicationId
import com.healthguard.domain.repository.DoseLogRepository
import com.healthguard.domain.repository.MedicationRepository
import com.healthguard.data.SqlDelightMedicationRepository
import com.healthguard.data.db.HealthGuardDb
import com.healthguard.domain.usecase.ObserveDataChangesUseCase
import com.healthguard.domain.usecase.ObserveMedicationsUseCase
import com.healthguard.data.ChatContextRenderer
import com.healthguard.data.ProxyChatAssistant
import com.healthguard.data.ProxyVisionExtractor
import com.healthguard.domain.extraction.VisionExtractor
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
import org.koin.dsl.binds
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
    single<ChatAssistant> {
        ProxyChatAssistant(
            get(),
            BuildConfig.PROXY_BASE_URL,
            ChatContextRenderer(TimeZone.currentSystemDefault()),
        )
    }

    single { DriverFactory(androidContext()) }
    single { HealthGuardDb(get<DriverFactory>().createDriver()) }
    // One store implements both repository roles; bind each interface to it.
    single { SqlDelightMedicationRepository(get(), Dispatchers.IO) } binds
        arrayOf(MedicationRepository::class, DoseLogRepository::class)

    single<() -> Instant> { { Clock.System.now() } }

    factory { ComputeHomeStateUseCase(get(), get(), TimeZone.currentSystemDefault()) }
    factory { RecordDoseUseCase(get(), get()) }
    factory { UndoDoseUseCase(get()) }
    factory { ActivateMedicationUseCase(get(), get()) }
    factory { StopMedicationUseCase(get(), get()) }
    factory { DeleteMedicationUseCase(get()) }
    factory { SeedDemoDataUseCase(get(), get(), TimeZone.currentSystemDefault()) }
    factory { RemoveDemoDataUseCase(get()) }

    factory { ObserveMedicationsUseCase(get()) }
    factory { ObserveDataChangesUseCase(get()) }

    factory { ComputeDetailStateUseCase(get(), get(), TimeZone.currentSystemDefault()) }
    factory { LoadDayDetailUseCase(get(), get(), TimeZone.currentSystemDefault()) }
    factory { SaveMedicationUseCase(get()) }

    factory { ComputeActivityStateUseCase(get(), get(), get(), TimeZone.currentSystemDefault()) }
    factory { LoadActivityDayDetailUseCase(get(), get(), get(), TimeZone.currentSystemDefault()) }

    factory { BuildChatContextUseCase(get(), get(), get(), TimeZone.currentSystemDefault()) }

    factory { ExtractMedicationUseCase(get(), Dispatchers.IO) }
    factory { SaveNewMedicationUseCase(get(), get()) }
    single<ImageEncoder> { AndroidImageEncoder(androidContext(), Dispatchers.IO) }

    viewModel { ConfirmViewModel(get(), get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ActivityViewModel(get(), get(), get()) }
    viewModel { ChatViewModel(get(), get(), get(), get(), TimeZone.currentSystemDefault()) }
    viewModel { (medicationId: MedicationId) ->
        DetailViewModel(
            computeDetailState = get(),
            loadDayDetail = get(),
            saveMedication = get(),
            recordDose = get(),
            undoDose = get(),
            activateMedication = get(),
            stopMedication = get(),
            deleteMedication = get(),
            observeMedications = get(),
            clock = get(),
            medicationId = medicationId,
        )
    }
}
