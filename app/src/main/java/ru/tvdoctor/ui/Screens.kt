package ru.tvdoctor.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.tvdoctor.diag.Analyzer.fmt
import ru.tvdoctor.diag.Analyzer.gb
import ru.tvdoctor.diag.Analyzer.gbBytes
import ru.tvdoctor.diag.Analyzer.mb
import ru.tvdoctor.diag.AppInfo
import ru.tvdoctor.diag.BootReport
import ru.tvdoctor.diag.Finding
import ru.tvdoctor.diag.Severity
import ru.tvdoctor.diag.SystemSnapshot

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel, onShareReport: () -> Unit, onCopySummary: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()

    if (!s.connected) {
        ConnectScreen(s, vm)
    } else {
        MainScreen(s, vm, onShareReport, onCopySummary)
    }

    s.error?.let { err ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("Понятно") } },
            title = { Text("Что-то пошло не так") },
            text = {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(err, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
        )
    }
    s.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

// ============================================================ Подключение

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectScreen(s: UiState, vm: MainViewModel) {
    Scaffold(topBar = { TopAppBar(title = { Text("ТВ-Доктор") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BusyBar(s.busy, s.cancellable, vm::cancelBusy) }
            item {
                Text("Подключение к телевизору", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = s.host, onValueChange = vm::setHost,
                    label = { Text("IP-адрес ТВ (например 192.168.1.50)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = s.port, onValueChange = vm::setPort,
                    label = { Text("Порт (обычно 5555)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(onClick = vm::connect, enabled = s.busy == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Подключиться")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = vm::checkNetwork, enabled = s.busy == null, modifier = Modifier.weight(1f)) {
                        Text("Проверить связь")
                    }
                    OutlinedButton(onClick = vm::discover, enabled = s.busy == null, modifier = Modifier.weight(1f)) {
                        Text("Найти ТВ в сети")
                    }
                }
            }
            s.netReport?.let { r -> item { MonoCard(r) } }
            if (s.searched && s.found.isEmpty()) {
                item {
                    InfoCard(
                        "ТВ в сети не найден",
                        "Телевизор не объявляет службы отладки. Обычно это значит, что отладка по сети на ТВ выключена, " +
                            "или телефон и ТВ в разных сетях (гостевой Wi-Fi, VPN на телефоне).",
                    )
                }
            }
            items(s.found) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.title, fontWeight = FontWeight.SemiBold)
                            Text("${f.host}:${f.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text(f.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { vm.useFound(f) }) { Text("Подставить") }
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text("Сопряжение по коду (если на ТВ «Беспроводная отладка»)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "На ТВ: Беспроводная отладка → «Подключить по коду». Введите порт и код из этого окна. " +
                        "Делается один раз.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.pairPort, onValueChange = vm::setPairPort,
                        label = { Text("Порт сопряжения") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = s.pairCode, onValueChange = vm::setPairCode,
                        label = { Text("Код (6 цифр)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                OutlinedButton(onClick = vm::pair, enabled = s.busy == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Сопрячь")
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                InfoCard(
                    "Как подготовить телевизор",
                    "1. Настройки → Об устройстве → 7 раз нажмите «Сборка», чтобы включить режим разработчика.\n" +
                        "2. В меню «Для разработчиков» включите «Отладку по сети» (или «Беспроводную отладку»).\n" +
                        "3. IP-адрес ТВ: Настройки → Сеть → ваша Wi-Fi сеть.\n" +
                        "4. Телефон и ТВ должны быть в одной сети.\n" +
                        "5. При первом подключении на ТВ появится запрос — нажмите «Разрешить» и отметьте «Всегда».",
                )
            }
        }
    }
}

// ============================================================ Главный экран

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(s: UiState, vm: MainViewModel, onShareReport: () -> Unit, onCopySummary: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ТВ-Доктор", maxLines = 1)
                        Text(
                            s.snapshot?.let { "${it.manufacturer} ${it.model} · ${s.host}" } ?: s.host,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = { TextButton(onClick = vm::disconnect) { Text("Отключить") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = s.tab.ordinal, edgePadding = 8.dp) {
                TabId.entries.forEach { t ->
                    Tab(selected = s.tab == t, onClick = { vm.setTab(t) }, text = { Text(t.title) })
                }
            }
            BusyBar(s.busy, s.cancellable, vm::cancelBusy)
            when (s.tab) {
                TabId.OVERVIEW -> OverviewTab(s, vm, onShareReport, onCopySummary)
                TabId.BOOT -> BootTab(s, vm)
                TabId.APPS -> AppsTab(s, vm)
                TabId.TERMINAL -> TerminalTab(s, vm)
            }
        }
    }
}

@Composable
private fun BusyBar(busy: String?, cancellable: Boolean, onCancel: () -> Unit) {
    if (busy == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(busy, style = MaterialTheme.typography.bodySmall)
        if (cancellable) {
            TextButton(onClick = onCancel) { Text("Отменить") }
        }
    }
}

// ============================================================ Обзор

@Composable
private fun OverviewTab(s: UiState, vm: MainViewModel, onShareReport: () -> Unit, onCopySummary: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::refreshOverview, enabled = s.busy == null) { Text("Обновить") }
                OutlinedButton(onClick = onShareReport, enabled = s.busy == null) { Text("Отчёт файлом") }
                OutlinedButton(onClick = onCopySummary, enabled = s.busy == null) { Text("Копировать") }
            }
        }
        findingsSection(s.findings)
        s.snapshot?.let { snap -> snapshotSection(snap) }
    }
}

private fun LazyListScope.findingsSection(findings: List<Finding>) {
    if (findings.isEmpty()) return
    item { SectionTitle("Выводы") }
    items(findings) { FindingCard(it) }
}

@Composable
private fun FindingCard(f: Finding) {
    val (bg, mark) = when (f.severity) {
        Severity.CRIT -> Color(0x33E53935) to "‼"
        Severity.WARN -> Color(0x33FB8C00) to "!"
        Severity.INFO -> Color(0x221E88E5) to "i"
        Severity.OK -> Color(0x2243A047) to "✓"
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            Text(mark, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(24.dp))
            Column {
                Text(f.title, fontWeight = FontWeight.SemiBold)
                if (f.details.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(f.details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun LazyListScope.snapshotSection(snap: SystemSnapshot) {
    item { SectionTitle("Система") }
    item {
        InfoCard(
            "${snap.manufacturer} ${snap.model}",
            "Android ${snap.android}, прошивка ${snap.build}\nПлатформа: ${snap.platform}\n" +
                "Работает с включения: ${(snap.uptimeSec / 60).toInt()} мин",
        )
    }
    item {
        val usedFrac = if (snap.dataTotalKb > 0) snap.dataUsedKb.toFloat() / snap.dataTotalKb else 0f
        MeterCard("Встроенная память", "занято ${gb(snap.dataUsedKb)} из ${gb(snap.dataTotalKb)}", usedFrac)
    }
    item {
        val usedFrac = if (snap.memTotalKb > 0) 1f - snap.memAvailKb.toFloat() / snap.memTotalKb else 0f
        MeterCard(
            "Оперативная память",
            "доступно ${mb(snap.memAvailKb)} из ${mb(snap.memTotalKb)}" +
                if (snap.swapTotalKb > 0) ", swap занят ${mb(snap.swapTotalKb - snap.swapFreeKb)}" else "",
            usedFrac,
        )
    }
    item {
        val frac = (snap.load1 / snap.cpuCount.coerceAtLeast(1)).toFloat()
        MeterCard("Нагрузка процессора", "${snap.load1} (1 мин) / ${snap.load5} (5 мин), ядер: ${snap.cpuCount}", frac)
    }
    if (snap.temps.isNotEmpty()) {
        item { InfoCard("Температура", snap.temps.joinToString("\n") { "${it.first}: ${fmt(it.second)} °C" }) }
    }
    item {
        InfoCard(
            "Флеш-память (eMMC)",
            if (snap.emmc.isEmpty()) "Прошивка не отдаёт показатель износа" else snap.emmc.joinToString("\n") { "${it.first} = ${it.second}" },
        )
    }
    if (snap.topCpu.isNotEmpty()) {
        item { SectionTitle("Процессор прямо сейчас") }
        item {
            MonoCard(snap.topCpu.take(12).joinToString("\n") { "${fmt(it.cpu).padStart(5)}%  ${mb(it.resKb).padStart(7)}  ${it.name}" })
        }
    }
    if (snap.bootReceiverPackages.isNotEmpty()) {
        item { SectionTitle("Подписаны на автозапуск (${snap.bootReceiverPackages.size})") }
        item {
            MonoCard(
                snap.bootReceiverPackages.sortedBy { it !in snap.thirdPartyPackages }.joinToString("\n") {
                    (if (it in snap.thirdPartyPackages) "★ " else "  ") + it
                } + "\n\n★ — сторонние приложения",
            )
        }
    }
}

// ============================================================ Загрузка

@Composable
private fun BootTab(s: UiState, vm: MainViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            InfoCard(
                "Как это работает",
                "«Перезагрузить и измерить» — ТВ перезагрузится, приложение дождётся его в сети, замерит время, " +
                    "прочитает журнал загрузки и минуту понаблюдает за нагрузкой. Займёт 2–4 минуты.\n\n" +
                    "Если на ТВ «Беспроводная отладка», после перезагрузки она выключится. Как только ТВ загрузится, " +
                    "включите её снова — приложение само найдёт новый порт и продолжит.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::rebootAndMeasure, enabled = s.busy == null) { Text("Перезагрузить и измерить") }
            }
        }
        item {
            OutlinedButton(onClick = vm::analyzeCurrentBoot, enabled = s.busy == null) { Text("Анализ текущей загрузки") }
        }
        val boot = s.boot
        if (boot != null) {
            findingsSection(s.findings.filter { it.severity != Severity.OK })
            bootSection(boot, s.snapshot?.thirdPartyPackages.orEmpty())
        }
    }
}

private fun LazyListScope.bootSection(b: BootReport, third: Set<String>) {
    b.measured?.let { m ->
        item {
            InfoCard(
                "Замер телефоном",
                "ТВ снова в сети через ${fmt(m.toAdbSec)} с\nСистема полностью загружена через ${m.toCompletedSec?.let { fmt(it) } ?: "?"} с",
            )
        }
    }
    item { SectionTitle("Этапы загрузки") }
    if (b.stages.isEmpty()) {
        item { InfoCard("Нет данных", "В журнале не найдено меток загрузки. Попробуйте «Перезагрузить и измерить».") }
    } else {
        val total = (b.stages.maxOf { it.atMs }).coerceAtLeast(1)
        b.initStartMs?.let { init ->
            item { StageRow("Ядро и драйверы (до init)", 0, init, total) }
        }
        val list = b.stages
        items(list.size) { i ->
            val st = list[i]
            val prev = if (i == 0) (b.initStartMs ?: 0) else list[i - 1].atMs
            StageRow(st.title, prev, st.atMs, total)
        }
        item {
            Text(
                "Полоса — длительность этапа; цифра справа — момент от включения.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (b.afterBootLoad.isNotEmpty()) {
        item { SectionTitle("Нагрузка в первую минуту после загрузки") }
        item { MonoCard(b.afterBootLoad.joinToString("\n") { "${fmt(it.cpu).padStart(5)}%  ${it.name}" }) }
    }

    if (b.procStarts.isNotEmpty()) {
        val grouped = b.procStarts.groupBy { it.process }
            .map { (proc, list) -> Triple(proc, list.size, list.first()) }
            .sortedBy { it.third.atMs ?: Long.MAX_VALUE }
        item { SectionTitle("Запущено при загрузке: ${grouped.size} процессов") }
        item {
            MonoCard(
                grouped.joinToString("\n") { (proc, n, first) ->
                    val t = first.atMs?.let { fmt(it / 1000.0) + "с" } ?: "?"
                    val star = if (proc.substringBefore(':') in third) "★" else " "
                    "${t.padStart(7)} $star ${proc}${if (n > 1) " ×$n" else ""} (${first.type})"
                } + "\n\n★ — сторонние приложения",
            )
        }
    }

    if (b.cpuTimes.isNotEmpty()) {
        item { SectionTitle("Процессорное время с включения") }
        item {
            MonoCard(
                (if (b.uptimeSec > 1800) "ТВ работает уже ${(b.uptimeSec / 60).toInt()} мин — цифры за всё это время.\n\n" else "") +
                    b.cpuTimes.take(20).joinToString("\n") { "${(it.cpuSec.toString() + " с").padStart(7)}  ${it.name}" },
            )
        }
    }

    item { SectionTitle("Журнал ядра") }
    item {
        MonoCard(
            when {
                !b.dmesgAvailable -> "Недоступен на этой прошивке без root."
                b.dmesgIssues.isEmpty() -> "Ошибок памяти и перегрева не найдено."
                else -> b.dmesgIssues.joinToString("\n")
            },
        )
    }

    if (b.initServices.isNotEmpty()) {
        item { SectionTitle("Системные службы, стартовавшие позже всех") }
        item {
            MonoCard(b.initServices.sortedByDescending { it.atMs }.take(15).joinToString("\n") { "${fmt(it.atMs / 1000.0).padStart(6)}с  ${it.name}" })
        }
    }
}

@Composable
private fun StageRow(title: String, fromMs: Long, toMs: Long, totalMs: Long) {
    val dur = (toMs - fromMs).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${fmt(toMs / 1000.0)} с", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = (dur.toFloat() / totalMs).coerceIn(0.01f, 1f))
                        .fillMaxHeight()
                        .padding(start = 0.dp)
                        .background(
                            if (dur > 10_000) Color(0xFFFB8C00) else MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
            Text(
                " +${fmt(dur / 1000.0)} с",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

// ============================================================ Приложения

@Composable
private fun AppsTab(s: UiState, vm: MainViewModel) {
    var confirmDisable by remember { mutableStateOf<AppInfo?>(null) }
    val list = if (s.thirdPartyOnly) s.apps.filter { it.thirdParty || it.disabled } else s.apps

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::loadApps, enabled = s.busy == null) {
                    Text(if (s.apps.isEmpty()) "Загрузить список" else "Обновить")
                }
                OutlinedButton(onClick = vm::clearAllCaches, enabled = s.busy == null) { Text("Очистить кэш всех") }
            }
        }
        if (s.apps.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.thirdPartyOnly, onClick = vm::toggleThirdPartyOnly, label = { Text("Только сторонние") })
                    FilterChip(selected = !s.thirdPartyOnly, onClick = vm::toggleThirdPartyOnly, label = { Text("Все (${s.apps.size})") })
                }
            }
            item {
                Text(
                    "Сортировка по занятой оперативной памяти. «Отключить» безопасно для сторонних приложений — " +
                        "их можно включить обратно. Системные отключайте, только если точно знаете, что это.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(list, key = { it.pkg }) { app ->
                AppCard(
                    app = app,
                    enabled = s.busy == null,
                    onStop = { vm.stopApp(app.pkg) },
                    onDisable = { confirmDisable = app },
                    onEnable = { vm.enableApp(app.pkg) },
                    onMeasure = { vm.measureLaunch(app.pkg) },
                )
            }
        }
    }

    confirmDisable?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmDisable = null },
            title = { Text("Отключить приложение?") },
            text = {
                Text(
                    app.pkg + "\n\n" +
                        if (app.thirdParty) {
                            "Приложение перестанет запускаться и исчезнет из меню. Включить обратно можно здесь же."
                        } else {
                            "Это СИСТЕМНОЕ приложение. Отключение может сломать функции телевизора " +
                                "(вплоть до необходимости сброса). Делайте это, только если уверены."
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisable = null
                    vm.disableApp(app.pkg)
                }) { Text("Отключить") }
            },
            dismissButton = { TextButton(onClick = { confirmDisable = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AppCard(
    app: AppInfo,
    enabled: Boolean,
    onStop: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onMeasure: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(app.pkg, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val tags = buildList {
                if (!app.thirdParty) add("системное")
                if (app.bootReceiver) add("автозапуск")
                if (app.disabled) add("отключено")
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            val size = if (app.appBytes == null) "размер: нет данных"
            else "приложение ${gbBytes(app.appBytes)}, данные ${gbBytes(app.dataBytes ?: 0)}, кэш ${gbBytes(app.cacheBytes ?: 0)}"
            Text(
                "ОЗУ: ${if (app.pssKb > 0) mb(app.pssKb) else "не запущено"}\n$size" +
                    (app.launchMs?.let { "\nХолодный запуск: ${fmt(it / 1000.0)} с" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (app.disabled) {
                    OutlinedButton(onClick = onEnable, enabled = enabled) { Text("Включить") }
                } else {
                    OutlinedButton(onClick = onStop, enabled = enabled) { Text("Остановить") }
                    OutlinedButton(onClick = onMeasure, enabled = enabled) { Text("Замер запуска") }
                    OutlinedButton(onClick = onDisable, enabled = enabled) { Text("Отключить") }
                }
            }
        }
    }
}

// ============================================================ Терминал

private val QUICK_COMMANDS = listOf(
    "df -h" to "Место",
    "cat /proc/meminfo | head -n 8" to "ОЗУ",
    "top -b -n 1 -m 15" to "top",
    "pm list packages -3" to "Сторонние",
    "logcat -b events -d | grep boot_progress" to "Загрузка",
    "getprop | grep -iE 'model|version|board'" to "Версия",
)

@Composable
private fun TerminalTab(s: UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = s.terminalCmd, onValueChange = vm::setTerminalCmd,
            label = { Text("Команда (adb shell)") },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { vm.runTerminal() }, enabled = s.busy == null) { Text("Выполнить") }
            QUICK_COMMANDS.forEach { (cmd, label) ->
                AssistChip(onClick = { vm.runTerminal(cmd) }, label = { Text(label) }, enabled = s.busy == null)
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            SelectionContainer {
                LazyColumn(Modifier.padding(8.dp).horizontalScroll(rememberScrollState())) {
                    val lines = s.terminalOut.lines()
                    items(lines.size) { i ->
                        Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
                    }
                }
            }
        }
    }
}

// ============================================================ Общие элементы

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MeterCard(title: String, subtitle: String, fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    val color = when {
        fraction >= 0.9f -> Color(0xFFE53935)
        fraction >= 0.75f -> Color(0xFFFB8C00)
        else -> MaterialTheme.colorScheme.primary
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${(fraction * 100).toInt()}%", fontWeight = FontWeight.SemiBold, color = color)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { f }, color = color, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MonoCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
        }
    }
}
