package com.medguard.di

import com.medguard.BuildConfig
import com.medguard.shared.extraction.ProxyVisionExtractor
import com.medguard.shared.extraction.VisionExtractor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * Minimal manual wiring for the capture/extract slice. Swap for a real DI
 * container once the object graph grows beyond a couple of nodes.
 */
object ServiceLocator {

    val visionExtractor: VisionExtractor by lazy {
        ProxyVisionExtractor(httpClient, BuildConfig.PROXY_BASE_URL)
    }

    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            // ProxyVisionExtractor requires the caller to bound request time;
            // without this a hung proxy would suspend extraction forever.
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
            }
        }
    }
}
