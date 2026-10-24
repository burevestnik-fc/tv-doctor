package ru.tvdoctor.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.coroutines.resume

/** Найденная в сети служба отладки ADB. */
data class FoundService(val kind: String, val name: String, val host: String, val port: Int) {
    val title: String
        get() = when (kind) {
            KIND_PAIRING -> "Сопряжение по коду"
            KIND_CONNECT -> "Беспроводная отладка (подключение)"
            else -> "Отладка по сети (классическая)"
        }

    companion object {
        const val KIND_PAIRING = "_adb-tls-pairing._tcp"
        const val KIND_CONNECT = "_adb-tls-connect._tcp"
        const val KIND_CLASSIC = "_adb._tcp"
    }
}

/** Сетевая диагностика: доступен ли ТВ, открыт ли порт, что объявляет ТВ в сети. */
class NetCheck(private val context: Context) {

    /** Пошаговая проверка связи с ТВ, человеческим языком. */
    suspend fun check(host: String, port: Int?): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // Сеть телефона
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val myIps = localIpv4()
        sb.appendLine("Телефон: ${if (wifi) "Wi-Fi" else "НЕ Wi-Fi (!)"}, адрес ${myIps.joinToString().ifEmpty { "?" }}")
        if (vpn) sb.appendLine("⚠ На телефоне включён VPN — он часто блокирует доступ к устройствам домашней сети. Отключите его на время проверки.")

        if (host.isBlank()) {
            sb.appendLine("IP-адрес ТВ не указан.")
            return@withContext sb.toString()
        }

        // Тот же ли сегмент сети
        val tvPrefix = host.substringBeforeLast('.', "")
        if (myIps.isNotEmpty() && myIps.none { it.substringBeforeLast('.') == tvPrefix }) {
            sb.appendLine("⚠ ТВ ($host) и телефон, похоже, в разных подсетях. Проверьте адрес ТВ: Настройки → Сеть.")
        }

        // Доступность
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
        if (addr == null) {
            sb.appendLine("✗ Адрес «$host» не распознан.")
            return@withContext sb.toString()
        }
        val reachable = runCatching { addr.isReachable(2_000) }.getOrDefault(false)
        sb.appendLine(if (reachable) "✓ ТВ отвечает в сети" else "• ТВ не ответил на ping (это не всегда проблема — многие ТВ его игнорируют)")

        // Порты
        val ports = linkedSetOf<Int>()
        if (port != null) ports += port
        ports += 5555
        for (p in ports) {
            val (open, err) = probe(host, p)
            sb.appendLine(
                if (open) "✓ Порт $p открыт — отладка слушает"
                else "✗ Порт $p закрыт ($err)",
            )
        }
        sb.toString()
    }

    private fun probe(host: String, port: Int): Pair<Boolean, String> =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 2_500) }
            true to ""
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            false to when {
                m.contains("ECONNREFUSED") || m.contains("refused", true) -> "ТВ отказал: отладка на этом порту выключена"
                m.contains("timed out", true) || m.contains("ETIMEDOUT") -> "нет ответа: ТВ недоступен или порт фильтруется"
                m.contains("EHOSTUNREACH") || m.contains("unreachable", true) -> "ТВ недоступен из этой сети"
                else -> m.ifEmpty { e.javaClass.simpleName }
            }
        }

    private fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
    }.getOrDefault(emptyList())

    /**
     * Ищет в сети службы отладки ADB (mDNS), которые объявляет ТВ.
     * Так находятся случайные порты «Беспроводной отладки».
     */
    suspend fun discover(seconds: Int = 6): List<FoundService> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = java.util.Collections.synchronizedList(ArrayList<NsdServiceInfo>())
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        for (type in listOf(FoundService.KIND_PAIRING, FoundService.KIND_CONNECT, FoundService.KIND_CLASSIC)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo != null) found += serviceInfo
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { listeners += l }
        }
        delay(seconds * 1000L)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }

        val result = ArrayList<FoundService>()
        val snapshot = synchronized(found) { found.toList() }
        for (info in snapshot.distinctBy { it.serviceName + it.serviceType }) {
            val resolved = resolve(nsd, info) ?: continue
            @Suppress("DEPRECATION")
            val h = resolved.host?.hostAddress ?: continue
            val kind = when {
                info.serviceType.contains("pairing") -> FoundService.KIND_PAIRING
                info.serviceType.contains("tls-connect") -> FoundService.KIND_CONNECT
                else -> FoundService.KIND_CLASSIC
            }
            result += FoundService(kind, info.serviceName, h.removePrefix("/"), resolved.port)
        }
        return result.distinct()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                            if (cont.isActive) cont.resume(serviceInfo)
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
}
