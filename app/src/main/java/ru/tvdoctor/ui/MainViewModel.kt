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
import ru.tvdoctor.adb.FoundService
import ru.tvdoctor.adb.NetCheck
import ru.tvdoctor.diag.Analyzer
import ru.tvdoctor.diag.AppInfo
import ru.tvdoctor.diag.BootReport
import ru.tvdoctor.diag.Diagnostics
import ru.tvdoctor.diag.ActionKind
import ru.tvdoctor.diag.Finding
import ru.tvdoctor.diag.FixAction
import ru.tvdoctor.diag.HistoryEntry
import ru.tvdoctor.diag.MeasuredBoot
import ru.tvdoctor.diag.Report
import ru.tvdoctor.diag.StorageTest
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
    val cancellable: Boolean = false,
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
    val storageTest: StorageTest? = null,
    val score: Int? = null,
    val summary: String = "",
    val changes: List<String> = emptyList(),
    val changesSince: Long? = null,
    val pendingAction: FixAction? = null,
    val netReport: String? = null,
    val found: List<FoundService> = emptyList(),
    val searched: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("tvdoctor", Context.MODE_PRIVATE)
    private val adb = AdbClient(app)
    private val diag = Diagnostics { cmd -> adb.shell(cmd) }
    private val net = NetCheck(app)

    /** Версия приложения, например «0.1.16». */
    val appVersion: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

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

    private var busyJob: kotlinx.coroutines.Job? = null

    private fun runBusy(label: String, cancellable: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        busyJob = viewModelScope.launch {
            _state.update { it.copy(busy = label, cancellable = cancellable) }
            try {
                block()
            } catch (e: CancellationException) {
                _state.update { it.copy(connected = adb.isConnected) }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = humanError(e), connected = adb.isConnected) }
            } finally {
                _state.update { it.copy(busy = null, cancellable = false) }
            }
        }
    }

    fun cancelBusy() {
        busyJob?.cancel()
    }

    private fun humanError(e: Throwable): String {
        val s = _state.value
        val where = "${s.host}:${s.port}"
        val chain = generateSequence(e) { it.cause }.take(5).toList()
        val text = chain.joinToString(" ") { (it.message ?: "") + " " + it.javaClass.simpleName }
        val hint = when {
            chain.any { it is ConnectException || it is NoRouteToHostException } || text.contains("ECONNREFUSED") ->
                "Не удалось подключиться к $where.\n\nТВ отказал в соединении: на этом порту отладка не слушает. " +
                    "Нажмите «Проверить связь» и «Найти ТВ в сети»."
            chain.any { it is SocketTimeoutException } ->
                "Телевизор не ответил вовремя ($where).\n\nЕсли на экране ТВ висит запрос «Разрешить отладку» — " +
                    "нажмите «Разрешить» (и «Всегда») и подключитесь снова."
            chain.any { it is UnknownHostException } -> "Неверный адрес: ${s.host}"
            else -> "Ошибка: " + (e.message ?: e.javaClass.simpleName) +
                if (!adb.isConnected && s.connected) "\n\nСоединение с ТВ потеряно." else ""
        }
        val details = chain.joinToString("\n") { "• ${it.javaClass.name}: ${it.message ?: ""}" }
        val stack = e.stackTrace.take(6).joinToString("\n") { "   at $it" }
        return "$hint\n\nПодробности (пришлите их мне):\n$details\n$stack"
    }

    private fun refreshFindings() {
        _state.update {
            val f = Analyzer.analyze(it.snapshot, it.boot, it.apps, it.storageTest)
            it.copy(findings = f, score = Analyzer.score(f), summary = Analyzer.summary(f))
        }
        recordHistory()
    }

    // ---------------------------------------------------------------- история замеров

    private fun loadHistory(): MutableList<HistoryEntry> {
        val list = ArrayList<HistoryEntry>()
        runCatching {
            val arr = org.json.JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun d(k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
                list += HistoryEntry(
                    timeMs = o.getLong("t"), score = o.optInt("score", 0), homeSec = d("home"), load1 = d("load"),
                    ramAvailPct = d("ram"), dataFreePct = d("data"), autostart = d("auto")?.toInt(),
                    seqMBs = d("seq"), smallMs = d("small"),
                )
            }
        }
        return list
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        val arr = org.json.JSONArray()
        for (e in list.takeLast(40)) {
            arr.put(
                org.json.JSONObject().apply {
                    put("t", e.timeMs); put("score", e.score)
                    put("home", e.homeSec ?: org.json.JSONObject.NULL); put("load", e.load1 ?: org.json.JSONObject.NULL)
                    put("ram", e.ramAvailPct ?: org.json.JSONObject.NULL); put("data", e.dataFreePct ?: org.json.JSONObject.NULL)
                    put("auto", e.autostart ?: org.json.JSONObject.NULL); put("seq", e.seqMBs ?: org.json.JSONObject.NULL)
                    put("small", e.smallMs ?: org.json.JSONObject.NULL)
                },
            )
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    /**
     * Сохраняет текущий замер. Замеры в пределах 15 минут сливаются в один «сеанс»,
     * сравнение идёт с последним сеансом до него.
     */
    private fun recordHistory() {
        val st = _state.value
        if (st.snapshot == null) return
        val cur = Analyzer.historyEntry(st.snapshot, st.boot, st.storageTest, st.findings)
        val list = loadHistory()
        val last = list.lastOrNull()
        val session = 15 * 60_000L
        if (last != null && cur.timeMs - last.timeMs < session) {
            list[list.lastIndex] = cur.copy(
                homeSec = cur.homeSec ?: last.homeSec,
                seqMBs = cur.seqMBs ?: last.seqMBs,
                smallMs = cur.smallMs ?: last.smallMs,
            )
        } else {
            list += cur
        }
        saveHistory(list)
        val prev = list.dropLast(1).lastOrNull()
        _state.update {
            it.copy(
                changes = if (prev != null) Analyzer.compare(prev, list.last()) else emptyList(),
                changesSince = prev?.timeMs,
            )
        }
    }

    // ---------------------------------------------------------------- действия из выводов

    fun onAction(a: FixAction) {
        if (a.kind == ActionKind.DISABLE_PACKAGES) {
            _state.update { it.copy(pendingAction = a) } // требует подтверждения
        } else {
            execute(a)
        }
    }

    fun confirmPendingAction() {
        val a = _state.value.pendingAction ?: return
        _state.update { it.copy(pendingAction = null) }
        execute(a)
    }

    fun dismissPendingAction() = _state.update { it.copy(pendingAction = null) }

    private fun execute(a: FixAction) {
        when (a.kind) {
            ActionKind.CLEAR_CACHE -> clearAllCaches()
            ActionKind.RESTRICT_AUTOSTART -> restrictAllAutostart(emptySet())
            ActionKind.FAST_ANIMATIONS -> fastAnimations()
            ActionKind.STORAGE_TEST -> runStorageTest()
            ActionKind.REBOOT_MEASURE -> {
                setTab(TabId.BOOT)
                rebootAndMeasure()
            }
            ActionKind.LOAD_APPS -> loadApps()
            ActionKind.DISABLE_PACKAGES -> disablePackages(a.packages)
        }
    }

    private fun fastAnimations() = runBusy("Ускоряю анимации…") {
        adb.shell(
            "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5",
        )
        collectSnapshot()
        _state.update { it.copy(message = "Анимации ускорены до 0.5x. Вернуть: «Для разработчиков» → «Масштаб анимации».") }
    }

    private fun disablePackages(pkgs: List<String>) = runBusy("Отключаю приложения…") {
        var ok = 0
        for (p in pkgs) {
            status("Отключаю $p")
            val out = adb.shell("pm disable-user --user 0 $p")
            if (out.contains("new state", true) || out.contains("disabled", true)) ok++
        }
        reloadApps()
        collectSnapshot()
        _state.update {
            it.copy(message = "Отключено приложений: $ok из ${pkgs.size}.\nВключить обратно: вкладка «Приложения» → «Все» → «Включить».")
        }
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
            try {
                adb.connect(s.host, port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Порт «Беспроводной отладки» мог смениться — ищем актуальный
                status("Порт $port не ответил, ищу актуальный порт отладки…")
                val newPort = connectViaDiscovery(s.host, setOf(port)) ?: throw e
                _state.update { it.copy(port = newPort.toString()) }
            }
            saveAddress()
            _state.update { it.copy(connected = true, tab = TabId.OVERVIEW) }
            collectSnapshot()
        }
    }

    private fun saveAddress() {
        val s = _state.value
        prefs.edit().putString("host", s.host).putString("port", s.port).apply()
    }

    /** Ищет через mDNS порт подключения на этом ТВ и пробует подключиться. Возвращает порт или null. */
    private suspend fun connectViaDiscovery(host: String, skip: Set<Int>): Int? {
        val ports = net.discover(3)
            .filter { it.host == host && it.kind != FoundService.KIND_PAIRING }
            .map { it.port }
            .filter { it !in skip }
            .distinct()
        for (p in ports) {
            try {
                adb.connect(host, p, connectTimeoutMs = 3_000)
                return p
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        return null
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

    fun checkNetwork() {
        val s = _state.value
        runBusy("Проверяю связь с телевизором…") {
            val r = net.check(s.host, s.port.toIntOrNull())
            _state.update { it.copy(netReport = r) }
        }
    }

    fun discover() = runBusy("Ищу телевизор в сети (6 секунд)…") {
        val list = net.discover()
        _state.update { it.copy(found = list, searched = true) }
    }

    /** Подставить найденную службу в поля ввода. */
    fun useFound(f: FoundService) = _state.update {
        when (f.kind) {
            FoundService.KIND_PAIRING -> it.copy(host = f.host, pairPort = f.port.toString())
            else -> it.copy(host = f.host, port = f.port.toString())
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

    fun runStorageTest() = runBusy("Тест памяти…") {
        if (_state.value.snapshot == null) collectSnapshot()
        val t = diag.storageTest { msg -> status(msg) }
        _state.update { it.copy(storageTest = t) }
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
        runBusy("Готовлю журнал к записи загрузки…", cancellable = true) {
            diag.prepareBootLogging()
            if (_state.value.snapshot == null) collectSnapshot()

            status("Перезагружаю телевизор…")
            val t0 = SystemClock.elapsedRealtime()
            adb.reboot()
            _state.update { it.copy(connected = false) }

            // Ждём, пока ТВ снова станет доступен. «Беспроводная отладка» после перезагрузки
            // выключается и получает новый порт — поэтому параллельно ищем ТВ через mDNS.
            delay(12_000)
            var toAdb: Double? = null
            var sameAddress = true
            while (SystemClock.elapsedRealtime() - t0 < 15 * 60_000) {
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000
                status(
                    "Жду телевизор… $sec с\n\nЕсли «Беспроводная отладка» на ТВ выключилась после перезагрузки — " +
                        "включите её снова (Для разработчиков → Беспроводная отладка). Приложение найдёт ТВ само.",
                )
                try {
                    adb.connect(s.host, port, connectTimeoutMs = 2_000)
                    toAdb = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
                val p = connectViaDiscovery(s.host, setOf(port))
                if (p != null) {
                    toAdb = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    sameAddress = false
                    _state.update { it.copy(port = p.toString()) }
                    saveAddress()
                    break
                }
                delay(1_000)
            }
            val adbSec: Double = toAdb ?: error(
                "Телевизор не появился в сети за 15 минут.\n\nВключите на ТВ отладку, подключитесь вручную " +
                    "и нажмите «Анализ текущей загрузки» — журнал загрузки сохранился.",
            )
            _state.update { it.copy(connected = true) }

            var toCompleted: Double? = null
            while (SystemClock.elapsedRealtime() - t0 < 20 * 60_000) {
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000
                status("Подключилась, жду окончания загрузки системы… $sec с")
                val v = runCatching { adb.shell("getprop sys.boot_completed").trim() }.getOrDefault("")
                if (v == "1") {
                    toCompleted = (SystemClock.elapsedRealtime() - t0) / 1000.0
                    break
                }
                delay(1_000)
            }

            status("Читаю журнал загрузки…")
            val report = diag.bootReport()

            // Нагрузку «сразу после загрузки» имеет смысл мерить, только если ТВ включился недавно
            val samples = 8
            val after = if (report.uptimeSec < 300) {
                diag.loadAfterBoot(samples) { i -> status("Смотрю нагрузку после загрузки… замер $i из $samples") }
            } else {
                emptyList()
            }

            // Замер телефоном честный, только если подключились без участия человека (тот же порт, «Отладка по сети»).
            // Иначе время включает паузу, пока включали отладку, — тогда опираемся только на журнал ТВ.
            val measured = if (sameAddress) MeasuredBoot(adbSec, toCompleted) else null
            _state.update { it.copy(boot = report.copy(measured = measured, afterBootLoad = after)) }
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

    /**
     * Запрет фоновой работы: приложение открывается как обычно, но не работает в фоне,
     * пока его не запустили. Обратимо кнопкой «Разрешить фон».
     */
    private fun restrictCmd(pkg: String) =
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore; cmd appops set $pkg RUN_IN_BACKGROUND ignore; " +
            "am set-standby-bucket $pkg restricted"

    private fun allowCmd(pkg: String) =
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; cmd appops set $pkg RUN_IN_BACKGROUND allow; " +
            "am set-standby-bucket $pkg active"

    fun restrictBackground(pkg: String) = runBusy("Запрещаю фоновую работу $pkg…") {
        adb.shell(restrictCmd(pkg))
        reloadApps()
        _state.update { it.copy(message = "$pkg больше не работает в фоне. Само приложение открывается как обычно.") }
    }

    fun allowBackground(pkg: String) = runBusy("Разрешаю фоновую работу $pkg…") {
        adb.shell(allowCmd(pkg))
        reloadApps()
        _state.update { it.copy(message = "Фоновая работа $pkg снова разрешена") }
    }

    /** Запретить фон всем сторонним приложениям из автозапуска, кроме указанных. */
    fun restrictAllAutostart(except: Set<String>) = runBusy("Запрещаю фоновую работу приложениям из автозапуска…") {
        if (_state.value.apps.isEmpty()) {
            status("Сначала собираю список приложений…")
            reloadApps()
        }
        val vpn = _state.value.apps.filter { it.pkg.contains("vpn", true) || it.pkg.contains("wireguard", true) }.map { it.pkg }
        val skip = except + vpn
        val targets = _state.value.apps.filter {
            it.thirdParty && it.bootReceiver && !it.disabled && !it.bgRestricted && it.pkg !in skip
        }.map { it.pkg }
        for (p in targets) {
            status("Запрещаю фон: $p")
            adb.shell(restrictCmd(p))
        }
        reloadApps()
        collectSnapshot()
        _state.update { it.copy(message = "Фоновая работа запрещена: ${targets.size} приложений.\nПерезагрузите ТВ и сравните время и нагрузку.") }
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

    /**
     * Ищет настройки, связанные с источником при включении. Команды простые (без кавычек, циклов и |),
     * а отбор строк — на телефоне: так не зависит от особенностей оболочки ТВ.
     */
    fun findStartupSettings() {
        _state.update { it.copy(terminalCmd = "", tab = TabId.TERMINAL) }
        runBusy("Ищу настройки источника при включении…") {
            val re = Regex("boot|power|source|input|startup|launch|home|tuner|tv_|channel", RegexOption.IGNORE_CASE)
            val sb = StringBuilder()
            for (t in listOf("global", "system", "secure")) {
                val out = adb.shell("settings list $t")
                val lines = out.lines().filter { re.containsMatchIn(it) }
                sb.appendLine("== $t: найдено ${lines.size} из ${out.lines().count { it.isNotBlank() }}")
                lines.forEach { sb.appendLine(it) }
                sb.appendLine()
            }
            // Какое приложение система считает «домашним» и что стоит первым на экране
            sb.appendLine("== домашний экран (HOME):")
            sb.appendLine(adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim())
            sb.appendLine()
            sb.appendLine("== приложения ТВ-тюнера / источников:")
            adb.shell("pm list packages").lines()
                .filter { Regex("tv|tuner|livetv|source|input|launcher", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .forEach { sb.appendLine(it.removePrefix("package:")) }
            _state.update { it.copy(terminalOut = sb.toString()) }
        }
    }

    // ---------------------------------------------------------------- отчёт

    fun buildFindings(): String {
        val s = _state.value
        return Report.findingsText(s.snapshot, s.boot, s.findings) + "\nТВ-Доктор $appVersion\n"
    }

    fun buildReport(includeRaw: Boolean): String {
        val s = _state.value
        return "Версия ТВ-Доктор: $appVersion\n" + Report.build(s.snapshot, s.boot, s.apps, s.findings, includeRaw)
    }

    override fun onCleared() {
        super.onCleared()
        kotlinx.coroutines.runBlocking { runCatching { adb.close() } }
    }
}
