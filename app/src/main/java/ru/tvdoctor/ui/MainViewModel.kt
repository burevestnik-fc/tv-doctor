package ru.tvdoctor.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.tvdoctor.adb.AdbClient
import ru.tvdoctor.diag.Analyzer
import ru.tvdoctor.diag.AppInfo
import ru.tvdoctor.diag.BootReport
import ru.tvdoctor.diag.Diagnostics
import ru.tvdoctor.diag.Finding
import ru.tvdoctor.diag.MeasuredBoot
import ru.tvdoctor.diag.Report
import ru.tvdoctor.diag.SystemSnapshot
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class TabId(val title: String) {
    OVERVIEW("Обзор"), BOOT("Загрузка"), APPS("Приложения"), TERMINAL("Терминал")
}

data class UiState(
    val host: String = "",
    val port: String = "5555",
    val pairPort: String = "",
    val pairCode: String = "",
    val connected: Boolean = false,
    val busy: String? = null,
    val error: String? = null,
    val message: String? = null,
    val tab: TabId = TabId.OVERVIEW,
    val snapshot: SystemSnapshot? = null,
    val boot: BootReport? = null,
    val apps: List<AppInfo> = emptyList(),
    val thirdPartyOnly: Boolean = true,
    val findings: List<Finding> = emptyList(),
    val terminalCmd: String = "",
    val terminalOut: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("tvdoctor", Context.MODE_PRIVATE)
    private val adb = AdbClient(app)
    private val diag = Diagnostics { cmd -> adb.shell(cmd) }

    private val _state = MutableStateFlow(
        UiState(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getString("port", "5555") ?: "5555",
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ---------------------------------------------------------------- поля ввода

    fun setHost(v: String) = _state.update { it.copy(host = v.trim()) }
    fun setPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun setPairPort(v: String) = _state.update { it.copy(pairPort = v.filter(Char::isDigit)) }
    fun setPairCode(v: String) = _state.update { it.copy(pairCode = v.filter(Char::isDigit)) }
    fun setTab(t: TabId) = _state.update { it.copy(tab = t) }
    fun setTerminalCmd(v: String) = _state.update { it.copy(terminalCmd = v) }
    fun toggleThirdPartyOnly() = _state.update { it.copy(thirdPartyOnly = !it.thirdPartyOnly) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun status(text: String) = _state.update { it.copy(busy = text) }

    private fun runBusy(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            status(label)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = humanError(e), connected = adb.isConnected) }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    private fun humanError(e: Throwable): String {
        val s = _state.value
        val where = "${s.host}:${s.port}"
        return when (e) {
            is ConnectException, is NoRouteToHostException ->
                "Не удалось подключиться к $where.\n\nПроверьте, что ТВ включён, находится в той же Wi-Fi сети, " +
                    "в режиме разработчика включена отладка по сети и адрес указан верно."
            is SocketTimeoutException ->
                "Телевизор не ответил вовремя ($where).\n\nЕсли на экране ТВ висит запрос «Разрешить отладку» — " +
                    "нажмите «Разрешить» (и галочку «Всегда») и подключитесь снова."
            is UnknownHostException -> "Неверный адрес: ${s.host}"
            else -> (e.message ?: e.javaClass.simpleName) +
                if (!adb.isConnected && s.connected) "\n\nСоединение с ТВ потеряно." else ""
        }
    }

    private fun refreshFindings() = _state.update {
        it.copy(findings = Analyzer.analyze(it.snapshot, it.boot, it.apps))
    }

    // ---------------------------------------------------------------- подключение

    fun connect() {
        val s = _state.value
        val port = s.port.toIntOrNull()
        if (s.host.isBlank() || port == null) {
            _state.update { it.copy(error = "Укажите IP-адрес телевизора и порт") }
            return
        }
        runBusy("Подключаюсь… Если на экране ТВ появится запрос — нажмите «Разрешить» и отметьте «Всегда».") {
            adb.connect(s.host, port)
            prefs.edit().putString("host", s.host).putString("port", s.port).apply()
            _state.update { it.copy(connected = true, tab = TabId.OVERVIEW) }
            collectSnapshot()
        }
    }

    fun pair() {
        val s = _state.value
        val port = s.pairPort.toIntOrNull()
        if (s.host.isBlank() || port == null || s.pairCode.length != 6) {
            _state.update { it.copy(error = "Для сопряжения нужны IP-адрес, порт сопряжения и 6-значный код с экрана ТВ") }
            return
        }
        runBusy("Сопряжение…") {
            adb.pair(s.host, port, s.pairCode)
            _state.update {
                it.copy(
                    pairCode = "",
                    message = "Сопряжение выполнено. Теперь впишите в поле «Порт» порт подключения " +
                        "(он показан на экране «Беспроводная отладка», не путать с портом сопряжения) и нажмите «Подключиться».",
                )
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            adb.close()
            _state.update { it.copy(connected = false) }
        }
    }

    // ---------------------------------------------------------------- обзор

    fun refreshOverview() = runBusy("Собираю данные о системе…") { collectSnapshot() }

    private suspend fun collectSnapshot() {
        status("Собираю данные о системе…")
        val snap = diag.snapshot()
        _state.update { it.copy(snapshot = snap) }
        refreshFindings()
    }

    // ---------------------------------------------------------------- загрузка

    fun analyzeCurrentBoot() = runBusy("Читаю журнал загрузки…") {
        if (_state.value.snapshot == null) collectSnapshot()
        status("Читаю журнал загрузки…")
        val report = diag.bootReport()
        _state.update { it.copy(boot = report) }
        refreshFindings()
    }

    fun rebootAndMeasure() {
        val s = _state.value
        val port = s.port.toIntOrNull() ?: return
        runBusy("Готовлю журнал к записи загрузки…") {
            diag.prepareBootLogging()
            if (_state.value.snapshot == null) collectSnapshot()

            status("Перезагружаю телевизор…")
            val t0 = SystemClock.elapsedRealtime()
            adb.reboot()
            _state.update { it.copy(connected = false) }

            // Ждём, пока ТВ выключится и снова появится в сети
            delay(12_000)
            var toAdb: Double? = null
            while (SystemClock.elapsedRealtime() - t0 < 6 * 60_000) {
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000
                status("Жду, когда телевизор загрузится… $sec с")
                try {
                    adb.connect(s.host, port, connectTimeoutMs = 2_000)
                    toAdb = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    delay(1_500)
                }
            }
            val adbSec: Double = toAdb ?: error(
                "Телевизор не появился в сети за 6 минут.\n\nВозможно, после перезагрузки отладка по сети выключилась " +
                    "или сменился порт («Беспроводная отладка» каждый раз выдаёт новый порт). " +
                    "Включите её снова и подключитесь — затем нажмите «Анализ текущей загрузки».",
            )
            _state.update { it.copy(connected = true) }

            var toCompleted: Double? = null
            while (SystemClock.elapsedRealtime() - t0 < 8 * 60_000) {
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000
                status("Подключилась, жду окончания загрузки системы… $sec с")
                val v = runCatching { adb.shell("getprop sys.boot_completed").trim() }.getOrDefault("")
                if (v == "1") {
                    toCompleted = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    break
                }
                delay(1_000)
            }

            val completedSec: Double? = toCompleted

            status("Читаю журнал загрузки…")
            val report = diag.bootReport()

            val samples = 8
            val after = diag.loadAfterBoot(samples) { i ->
                status("Смотрю нагрузку после загрузки… замер $i из $samples")
            }

            _state.update {
                it.copy(boot = report.copy(measured = MeasuredBoot(adbSec, completedSec), afterBootLoad = after))
            }
            collectSnapshot()
        }
    }

    // ---------------------------------------------------------------- приложения

    fun loadApps() = runBusy("Собираю список приложений (до 30 секунд)…") { reloadApps() }

    private suspend fun reloadApps() {
        val old = _state.value.apps.associateBy { it.pkg }
        val apps = diag.apps().map { a -> a.copy(launchMs = old[a.pkg]?.launchMs) }
        _state.update { it.copy(apps = apps) }
        refreshFindings()
    }

    fun stopApp(pkg: String) = runBusy("Останавливаю $pkg…") {
        adb.shell("am force-stop $pkg")
        _state.update { it.copy(message = "Приложение $pkg остановлено") }
    }

    fun disableApp(pkg: String) = runBusy("Отключаю $pkg…") {
        val out = adb.shell("pm disable-user --user 0 $pkg")
        reloadApps()
        _state.update { it.copy(message = out.trim().ifEmpty { "Готово" }) }
    }

    fun enableApp(pkg: String) = runBusy("Включаю $pkg…") {
        val out = adb.shell("pm enable $pkg")
        reloadApps()
        _state.update { it.copy(message = out.trim().ifEmpty { "Готово" }) }
    }

    fun clearAllCaches() = runBusy("Очищаю кэш всех приложений…") {
        adb.shell("pm trim-caches 999G")
        if (_state.value.apps.isNotEmpty()) reloadApps()
        collectSnapshot()
        _state.update { it.copy(message = "Кэш всех приложений очищен") }
    }

    fun measureLaunch(pkg: String) = runBusy("Замеряю холодный запуск $pkg (смотрите на ТВ)…") {
        val (ms, out) = diag.measureLaunch(pkg)
        if (ms == null) {
            _state.update { it.copy(message = "Не удалось замерить: ${out.take(300)}") }
        } else {
            _state.update { st ->
                st.copy(
                    apps = st.apps.map { if (it.pkg == pkg) it.copy(launchMs = ms) else it },
                    message = "$pkg запустился за ${Analyzer.fmt(ms / 1000.0)} с",
                )
            }
        }
    }

    // ---------------------------------------------------------------- терминал

    fun runTerminal(cmd: String? = null) {
        val c = (cmd ?: _state.value.terminalCmd).trim()
        if (c.isEmpty()) return
        if (cmd != null) _state.update { it.copy(terminalCmd = cmd) }
        runBusy("Выполняю: $c") {
            val out = adb.shell(c)
            _state.update { it.copy(terminalOut = "$ $c\n" + out.take(150_000)) }
        }
    }

    // ---------------------------------------------------------------- отчёт

    fun buildReport(includeRaw: Boolean): String {
        val s = _state.value
        return Report.build(s.snapshot, s.boot, s.apps, s.findings, includeRaw)
    }

    override fun onCleared() {
        super.onCleared()
        kotlinx.coroutines.runBlocking { runCatching { adb.close() } }
    }
}
