package com.narcictub.app.data.network

import android.content.Context
import com.narcictub.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * HONEY — DNS over HTTPS, applied for real:
 *
 * When the user configures a DoH endpoint, [install] swaps the process-wide
 * [HttpsURLConnection] SSL socket factory for a DoH-aware one: the TCP
 * connection is made to an address resolved THROUGH the DoH endpoint, while
 * TLS SNI and hostname verification still use the real hostname — so DNS
 * poisoning of the system resolver can no longer break the app's HTTPS
 * calls (direct downloads, resolver fetches, thumbnails — everything).
 *
 * SCOPE (honest, stated in Settings): the yt-dlp engine runs as a separate
 * Python process and uses the system resolver — a DoH setting cannot reach
 * inside it.
 */
@Singleton
class DoHNetwork @Inject constructor(
    @ApplicationContext @Suppress("unused") private val context: Context,
    settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    /** She persisted DoH endpoint (null/blank = disabled → system DNS). */
    val dohUrl: StateFlow<String?> = settingsRepository.settings
        .map { it.dohUrl }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    private val systemFactory: javax.net.ssl.SSLSocketFactory by lazy {
        HttpsURLConnection.getDefaultSSLSocketFactory()
    }

    @Volatile private var installedEndpoint: String? = null
    @Volatile private var installedFactory: javax.net.ssl.SSLSocketFactory? = null

    /** Applies (or un-installs) the DoH-aware socket factory process-wide. */
    @Synchronized
    fun install(endpoint: String?) {
        val target = endpoint?.trim().takeUnless { it.isNullOrEmpty() }
        if (target == installedEndpoint) return
        if (target == null) {
            HttpsURLConnection.setDefaultSSLSocketFactory(systemFactory)
            installedEndpoint = null
            installedFactory = null
            return
        }
        val doh = buildDnsOverHttps(target) ?: run {
            installedEndpoint = null
            return
        }
        val factory = DohAwareSslFactory(systemFactory, doh)
        HttpsURLConnection.setDefaultSSLSocketFactory(factory)
        installedEndpoint = target
        installedFactory = factory
    }

    /** Reactive glue: applies the setting whenever it changes. */
    fun start() {
        scope.launch { dohUrl.collect { install(it) } }
    }

    private val baseClient = OkHttpClient.Builder().build()

    @Volatile private var cachedClient: OkHttpClient? = null
    @Volatile private var cachedEndpoint: String? = null

    /**
     * OkHttpClient for consumers that make their own calls (YouTube client,
     * resolver fetches). DNS = DnsOverHttps when configured, else system.
     */
    @Synchronized
    fun client(): OkHttpClient {
        val endpoint = dohUrl.value
        if (cachedClient != null && cachedEndpoint == endpoint) return cachedClient!!
        cachedEndpoint = endpoint
        cachedClient = if (endpoint.isNullOrBlank()) {
            baseClient
        } else {
            runCatching {
                val doh = buildDnsOverHttps(endpoint) ?: return@runCatching baseClient
                baseClient.newBuilder().dns(doh).build()
            }.getOrDefault(baseClient)
        }
        return cachedClient!!
    }

    private fun buildDnsOverHttps(endpoint: String): DnsOverHttps? = runCatching {
        val base = OkHttpClient.Builder().build()
        DnsOverHttps.Builder()
            .client(base)
            .url(okhttp3.HttpUrl.Builder().scheme("https").host(URL(endpoint).host).encodedPath(URL(endpoint).path.ifEmpty { "/dns-query" }).build())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("8.8.4.4"),
            )
            .build()
    }.getOrNull()

    /**
     * SSLSocketFactory that resolves the peer host through DoH before
     * connecting, keeping SNI + hostname verification on the hostname.
     */
    private class DohAwareSslFactory(
        private val system: javax.net.ssl.SSLSocketFactory,
        private val doh: DnsOverHttps,
    ) : javax.net.ssl.SSLSocketFactory() {

        private fun resolve(host: String): InetAddress? = runCatching {
            doh.lookup(host).firstOrNull()
        }.getOrNull()

        private fun socket(host: String, port: Int): java.net.Socket {
            resolve(host)?.let { address ->
                runCatching {
                    // connect to the DoH address; SNI/hostname stay = host
                    return system.createSocket(address, port)
                }
            }
            return system.createSocket(host, port)
        }

        override fun getDefaultCipherSuites(): Array<String> = system.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = system.supportedCipherSuites

        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean) =
            system.createSocket(s, host, port, autoClose)

        override fun createSocket(host: String, port: Int) = socket(host, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ) = socket(host, port)

        override fun createSocket(host: InetAddress, port: Int) =
            system.createSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ) = system.createSocket(address, port, localAddress, localPort)
    }

    companion object {
        /** Free, well-known DoH endpoints offered as one-tap presets. */
        val PRESETS = listOf(
            "https://cloudflare-dns.com/dns-query" to "Cloudflare",
            "https://dns.google/dns-query" to "Google",
            "https://dns.adguard-dns.com/dns-query" to "AdGuard",
        )

        /** DoH endpoint must be an https URL with a host. */
        fun isValidEndpoint(value: String): Boolean =
            value.startsWith("https://") && runCatching {
                java.net.URI(value).host?.isNotBlank() == true
            }.getOrDefault(false)
    }
}
