package ru.tvdoctor.adb

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File

/**
 * Всё общение с телевизором по протоколу ADB собрано здесь,
 * чтобы остальной код не зависел от конкретной библиотеки.
 */
class AdbClient(context: Context) {

    @Volatile
    private var kadb: Kadb? = null
    private val mutex = Mutex()

    init {
        configureKeys(context.applicationContext)
    }

    val isConnected: Boolean get() = kadb != null

    // Куда подключены — чтобы переподключиться, если соединение «засорилось»
    @Volatile private var lastHost: String? = null
    @Volatile private var lastPort: Int = 0
    @Volatile private var lastTimeout: Int = 8_000

    /** Подключение. Первый раз на экране ТВ появится запрос «Разрешить отладку». */
    suspend fun connect(host: String, port: Int, connectTimeoutMs: Int = 8_000) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                closeLocked()
                val k = Kadb.create(
                    host = host,
                    port = port,
                    connectTimeout = connectTimeoutMs,
                    socketTimeout = 90_000,
                )
                try {
                    val r = k.shell("echo tvdoctor_ok")
                    if (!r.output.contains("tvdoctor_ok")) {
                        error("Неожиданный ответ телевизора: ${r.output.take(200)}")
                    }
                } catch (e: Throwable) {
                    runCatching { k.close() }
                    throw e
                }
                kadb = k
                lastHost = host
                lastPort = port
                lastTimeout = connectTimeoutMs
            }
        }

    /** Сопряжение для «Беспроводной отладки» (Android 11+): порт и 6-значный код с экрана ТВ. */
    suspend fun pair(host: String, port: Int, code: String) = withContext(Dispatchers.IO) {
        Kadb.pair(host, port, code, "TV-Doctor")
    }

    /**
     * Выполнить команду на ТВ. Ошибки команды попадают в вывод (2>&1).
     *
     * Команда идёт через службу exec: (без интерактивной оболочки и эха). В конец добавляется
     * уникальная метка: если её нет в ответе, значит пришёл «мусор» от прошлой команды —
     * тогда соединение пересоздаётся и команда повторяется.
     */
    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val k = kadb ?: throw IllegalStateException("Нет подключения к телевизору")
            val marker = "__TVD_END_${System.nanoTime()}__"
            val script = "( $command ) 2>&1; echo $marker"
            try {
                val first = runOnce(k, script)
                cutAtMarker(first, marker)?.let { return@withLock it }

                // Ответ без метки — пересоздаём соединение и пробуем ещё раз
                val fresh = reconnectLocked()
                val second = runOnce(fresh, script)
                cutAtMarker(second, marker)
                    ?: error("Телевизор вернул неожиданный ответ. Переподключитесь к ТВ.\n\n${second.take(300)}")
            } catch (e: Throwable) {
                // Соединение оборвалось — сбрасываем, чтобы интерфейс это увидел
                closeLocked()
                throw e
            }
        }
    }

    /** exec: — простой запуск через sh -c без PTY; если служба недоступна — обычный shell. */
    private fun runOnce(k: Kadb, script: String): String =
        try {
            k.open("exec:$script").use { it.source.readUtf8() }
        } catch (e: java.io.IOException) {
            if (e is java.net.SocketTimeoutException) throw e
            k.shell(script).output
        }

    private fun cutAtMarker(out: String, marker: String): String? {
        val i = out.lastIndexOf(marker)
        if (i < 0) return null
        return out.substring(0, i).trimEnd('\n', '\r') + "\n"
    }

    private fun reconnectLocked(): Kadb {
        val host = lastHost ?: throw IllegalStateException("Нет подключения к телевизору")
        closeLocked()
        val k = Kadb.create(host = host, port = lastPort, connectTimeout = lastTimeout, socketTimeout = 90_000)
        kadb = k
        return k
    }

    /** Перезагрузка ТВ. */
    suspend fun reboot() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val k = kadb ?: return@withLock
            val viaService = runCatching { k.open("reboot:").close() }.isSuccess
            if (!viaService) {
                runCatching { k.shell("svc power reboot") }
            }
            delay(500)
            closeLocked()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { kadb?.close() }
        kadb = null
    }

    companion object {
        private val keyLock = Any()
        @Volatile
        private var keysConfigured = false

        /** Ключ хранится в памяти приложения, поэтому ТВ «запоминает» телефон. */
        private fun configureKeys(context: Context) {
            synchronized(keyLock) {
                if (keysConfigured) return
                val keyFile = File(context.filesDir, "adb_private_key.pem")
                KadbCert.configure(
                    store = OkioFilePrivateKeyStore(privateKeyPath = keyFile.absolutePath.toPath()),
                    policy = KadbCertPolicy(),
                    additionalPrivateKeysPem = emptyList(),
                )
                keysConfigured = true
            }
        }
    }
}
