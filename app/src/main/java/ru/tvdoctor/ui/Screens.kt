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
import ru.tvdoctor.diag.Analyzer
import ru.tvdoctor.diag.Finding
import ru.tvdoctor.diag.FixAction
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

/** Выгрузка результатов — реализуется в Activity. */
interface Exporter {
    fun shareFindings()
    fun shareReport()
    fun saveReport()
    fun copySummary()
    fun copyTerminal()
    fun shareTerminal()
}

@Composable
fun App(vm: MainViewModel, exporter: Exporter) {
    val s by vm.state.collectAsStateWithLifecycle()

    if (!s.connected) {
        ConnectScreen(s, vm)
    } else {
        MainScreen(s, vm, exporter)
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
    s.pendingAction?.let { a ->
        AlertDialog(
            onDismissRequest = vm::dismissPendingAction,
            title = { Text("${a.label}?") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text("Приложения перестанут запускаться и пропадут из меню. Вернуть можно во вкладке «Приложения» → «Все» → «Включить».\n")
                    a.packages.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmPendingAction) { Text("Отключить") } },
            dismissButton = { TextButton(onClick = vm::dismissPendingAction) { Text("Отмена") } },
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ТВ-Доктор", maxLines = 1)
                        Text("версия ${vm.appVersion}", style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        },
    ) { pad ->
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
private fun MainScreen(s: UiState, vm: MainViewModel, exporter: Exporter) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ТВ-Доктор ${vm.appVersion}", maxLines = 1)
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
                TabId.OVERVIEW -> OverviewTab(s, vm, exporter)
                TabId.BOOT -> BootTab(s, vm, exporter)
                TabId.APPS -> AppsTab(s, vm, exporter)
                TabId.MAINT -> MaintTab(s, vm)
                TabId.TERMINAL -> TerminalTab(s, vm, exporter)
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
private fun OverviewTab(s: UiState, vm: MainViewModel, exporter: Exporter) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::refreshOverview, enabled = s.busy == null) { Text("Обновить") }
                OutlinedButton(onClick = vm::runStorageTest, enabled = s.busy == null) { Text("Тест скорости памяти") }
            }
        }
        item { ExportRow(exporter, s.busy == null) }
        if (s.score != null) item { ScoreCard(s) }
        findingsSection(s.findings, s.busy == null, vm::onAction, s.selections, vm::setSelection)
        s.snapshot?.let { snap -> snapshotSection(snap) }
    }
}

private fun LazyListScope.findingsSection(
    findings: List<Finding>,
    enabled: Boolean,
    onAction: (FixAction) -> Unit,
    selections: Map<String, Set<String>>,
    onSelect: (String, Set<String>) -> Unit,
) {
    if (findings.isEmpty()) return
    item { SectionTitle("Выводы") }
    items(findings) { FindingCard(it, enabled, onAction, selections, onSelect) }
}

@Composable
private fun FindingCard(
    f: Finding,
    enabled: Boolean,
    onAction: (FixAction) -> Unit,
    selections: Map<String, Set<String>>,
    onSelect: (String, Set<String>) -> Unit,
) {
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
                if (f.choices.isNotEmpty()) {
                    val key = f.title.substringBefore(" (")
                    ChoiceList(f, enabled, onAction, selections[key].orEmpty()) { onSelect(key, it) }
                }
                f.action?.let { a ->
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(onClick = { onAction(a) }, enabled = enabled) { Text(a.label) }
                }
            }
        }
    }
}

/** Галочки по пунктам вывода. По умолчанию ничего не отмечено — действие только к выбранным. */
@Composable
private fun ChoiceList(
    f: Finding,
    enabled: Boolean,
    onAction: (FixAction) -> Unit,
    stored: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    // Только пакеты, которые ещё есть в карточке (после отключения список меняется)
    val present = f.choices.map { it.pkg }.toSet()
    val selected = stored intersect present
    val safe = f.choices.filter { it.note?.verdict != ru.tvdoctor.diag.Verdict.KEEP }.map { it.pkg }.toSet()
    Spacer(Modifier.height(6.dp))
    Text(
        "Все приложения здесь сторонние: телевизор работает без любого из них, оба действия обратимы.\n" +
            "«Запретить фон» — приложение открывается как обычно, но не работает в фоне. При полной загрузке ТВ система всё же ненадолго будит его.\n" +
            "«Отключить» — приложение пропадает из меню, пока не включите обратно.",
        style = MaterialTheme.typography.labelSmall,
    )
    Spacer(Modifier.height(4.dp))
    f.choices.forEach { c ->
        val note = c.note
        val danger = note?.verdict == ru.tvdoctor.diag.Verdict.KEEP
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Checkbox(
                checked = c.pkg in selected,
                onCheckedChange = { on -> onChange(if (on) selected + c.pkg else selected - c.pkg) },
                enabled = enabled,
            )
            Column(Modifier.padding(top = 4.dp)) {
                Text(c.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    c.pkg, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note != null) {
                    Text(note.about, style = MaterialTheme.typography.bodySmall)
                    val (dot, col) = when (note.verdict) {
                        ru.tvdoctor.diag.Verdict.DISABLE_OK -> "●" to Color(0xFF2E7D32)
                        ru.tvdoctor.diag.Verdict.BG_ONLY -> "●" to Color(0xFFEF6C00)
                        ru.tvdoctor.diag.Verdict.KEEP -> "●" to Color(0xFFC62828)
                    }
                    Text("$dot ${note.verdict.text}", color = col, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                }
                c.lastUsed?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                if (danger && c.pkg in selected) {
                    Text("Внимание: вы отметили приложение, которое лучше не трогать.", color = Color(0xFFC62828), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    TextButton(
        onClick = { onChange(if (selected.isNotEmpty()) emptySet() else safe) },
        enabled = enabled,
    ) { Text(if (selected.isNotEmpty()) "Снять все" else "Выбрать все, кроме «не трогать»") }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        f.choiceActions.forEach { a ->
            FilledTonalButton(
                onClick = {
                    val pkgs = f.choices.map { it.pkg }.filter { it in selected }
                    onAction(a.copy(packages = pkgs))
                    onChange(emptySet())
                },
                enabled = enabled && selected.isNotEmpty(),
            ) { Text(if (selected.isEmpty()) a.label else "${a.label} (${selected.size})") }
        }
    }
}

@Composable
private fun ScoreCard(s: UiState) {
    val score = s.score ?: return
    val color = when {
        score >= 85 -> Color(0xFF43A047)
        score >= 65 -> Color(0xFF7CB342)
        score >= 40 -> Color(0xFFFB8C00)
        else -> Color(0xFFE53935)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$score", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
                Text(" / 100", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(Analyzer.scoreLabel(score), fontWeight = FontWeight.SemiBold)
                    Text(s.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
            LinearProgressIndicator(
                progress = { score / 100f }, color = color,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (s.changes.isNotEmpty() && s.changesSince != null) {
                Spacer(Modifier.height(10.dp))
                val since = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.changesSince))
                Text("Изменения с прошлого замера ($since)", style = MaterialTheme.typography.labelMedium)
                s.changes.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.startsWith("▲")) Color(0xFF43A047) else Color(0xFFE53935),
                    )
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
        val busy = snap.cpuBusyPct
        val frac = busy?.let { (it / 100).toFloat() } ?: (snap.realLoad / snap.cpuCount.coerceAtLeast(1)).toFloat()
        MeterCard(
            "Нагрузка процессора",
            (busy?.let { "занят на ${fmt(it)}% · " } ?: "") +
                (snap.cpuIowPct?.takeIf { it >= 1 }?.let { "ждёт диск ${fmt(it)}% · " } ?: "") +
                "реальная нагрузка ${fmt(snap.realLoad)} при ${snap.cpuCount} ядрах" +
                if (snap.dStateCount > 0) " (системная ${snap.load1}, из неё ${snap.dStateCount} — ждущие драйверы)" else "",
            frac,
        )
    }
    if (snap.temps.isNotEmpty()) {
        item { InfoCard("Температура", snap.temps.joinToString("\n") { "${it.first}: ${fmt(it.second)} °C" }) }
    }
    item {
        InfoCard(
            "Флеш-память (eMMC)",
            buildString {
                if (snap.emmc.isEmpty()) append("Прямой показатель износа прошивка без root не отдаёт.")
                else append(snap.emmc.joinToString("\n") { "${it.first} = ${it.second}" })
                snap.storageInfo.forEach { append("\n").append(it) }
                if (snap.emmc.isEmpty()) append("\nОценить состояние памяти можно кнопкой «Тест скорости памяти» вверху.")
            },
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
private fun BootTab(s: UiState, vm: MainViewModel, exporter: Exporter) {
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
            item { ExportRow(exporter, s.busy == null) }
            findingsSection(s.findings.filter { it.severity != Severity.OK }, s.busy == null, vm::onAction, s.selections, vm::setSelection)
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
private fun AppsTab(s: UiState, vm: MainViewModel, exporter: Exporter) {
    var confirmDisable by remember { mutableStateOf<AppInfo?>(null) }
    var confirmBulk by remember { mutableStateOf(false) }
    // VPN-приложениям фон нужен для работы — их не трогаем
    val vpnLike = s.apps.filter { it.pkg.contains("vpn", true) || it.pkg.contains("wireguard", true) }.map { it.pkg }.toSet()
    val bulkTargets = s.apps.filter {
        it.thirdParty && it.bootReceiver && !it.disabled && !it.bgRestricted && it.pkg !in vpnLike
    }
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
            if (bulkTargets.isNotEmpty()) {
                item {
                    Button(onClick = { confirmBulk = true }, enabled = s.busy == null, modifier = Modifier.fillMaxWidth()) {
                        Text("Запретить фон всем из автозапуска (${bulkTargets.size})")
                    }
                }
            }
            item { ExportRow(exporter, s.busy == null) }
            item { ReturnableCard(s, vm) }
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
                    onRestrictBg = { vm.restrictBackground(app.pkg) },
                    onAllowBg = { vm.allowBackground(app.pkg) },
                )
            }
        }
    }

    if (confirmBulk) {
        AlertDialog(
            onDismissRequest = { confirmBulk = false },
            title = { Text("Запретить фоновую работу?") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Эти приложения перестанут просыпаться и работать в фоне. Открываться и работать, " +
                            "когда вы их запускаете, они будут как обычно. Отменить можно кнопкой «Разрешить фон».\n",
                    )
                    bulkTargets.forEach { Text("• ${it.pkg}", style = MaterialTheme.typography.bodySmall) }
                    if (vpnLike.isNotEmpty()) {
                        Text(
                            "\nVPN-приложения (${vpnLike.joinToString()}) не трогаю — без фона VPN не работает. " +
                                "Его автозапуск выключается в настройках самого VPN («Восстанавливать при загрузке»).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBulk = false
                    vm.restrictAllAutostart(vpnLike)
                }) { Text("Запретить") }
            },
            dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Отмена") } },
        )
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
    onRestrictBg: () -> Unit,
    onAllowBg: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(app.pkg, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val tags = buildList {
                if (!app.thirdParty) add("системное")
                if (app.bootReceiver) add("автозапуск")
                if (app.disabled) add("отключено")
                if (app.bgRestricted) add("фон запрещён")
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
                    if (app.bgRestricted) {
                        OutlinedButton(onClick = onAllowBg, enabled = enabled) { Text("Разрешить фон") }
                    } else {
                        OutlinedButton(onClick = onRestrictBg, enabled = enabled) { Text("Запретить фон") }
                    }
                    OutlinedButton(onClick = onStop, enabled = enabled) { Text("Остановить") }
                    OutlinedButton(onClick = onMeasure, enabled = enabled) { Text("Замер запуска") }
                    OutlinedButton(onClick = onDisable, enabled = enabled) { Text("Отключить") }
                }
            }
        }
    }
}

/**
 * Всё, что выключали и можно вернуть: отключённые приложения, с запретом фона
 * и удалённые «для пользователя». Сверху вкладки, чтобы не искать в общем списке.
 */
@Composable
private fun ReturnableCard(s: UiState, vm: MainViewModel) {
    val enabled = s.busy == null
    val disabled = s.apps.filter { it.disabled }
    val restricted = s.apps.filter { it.bgRestricted && !it.disabled }
    val removed = s.removedPkgs
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text("Отключённые — можно вернуть", fontWeight = FontWeight.SemiBold)
            if (disabled.isEmpty() && restricted.isEmpty() && removed.isEmpty()) {
                Text("Ничего не отключено и не ограничено.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            @Composable
            fun row(pkg: String, button: String, onClick: () -> Unit) {
                val note = ru.tvdoctor.diag.KnownApps.note(pkg)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(note.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            pkg, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClick, enabled = enabled) { Text(button) }
                }
            }
            if (disabled.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Отключены (${disabled.size}) — не видны в меню и не запускаются", style = MaterialTheme.typography.labelMedium)
                disabled.forEach { a -> row(a.pkg, "Включить") { vm.enableApp(a.pkg) } }
                if (disabled.size > 1) {
                    OutlinedButton(onClick = { vm.enableAll(disabled.map { it.pkg }) }, enabled = enabled) {
                        Text("Включить все (${disabled.size})")
                    }
                }
            }
            if (restricted.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Запрещён фон (${restricted.size}) — открываются как обычно", style = MaterialTheme.typography.labelMedium)
                restricted.forEach { a -> row(a.pkg, "Разрешить фон") { vm.allowBackground(a.pkg) } }
            }
            if (removed.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Удалены для пользователя (${removed.size}) — файлы остались в прошивке, можно вернуть",
                    style = MaterialTheme.typography.labelMedium,
                )
                removed.forEach { p -> row(p, "Вернуть") { vm.restoreApp(p) } }
            }
        }
    }
}

// ============================================================ Обслуживание

@Composable
private fun MaintTab(s: UiState, vm: MainViewModel) {
    val enabled = s.busy == null
    // Подтверждение, если долгую процедуру делали меньше недели назад
    var confirmRepeat by remember { mutableStateOf<Pair<ru.tvdoctor.diag.MaintTask, Long>?>(null) }
    fun startOrConfirm(t: ru.tvdoctor.diag.MaintTask, run: () -> Unit) {
        val d = s.daysSince(t.id)
        if (d != null && d < ru.tvdoctor.diag.Maintenance.MIN_GAP_DAYS) confirmRepeat = t to d else run()
    }
    confirmRepeat?.let { (t, d) ->
        AlertDialog(
            onDismissRequest = { confirmRepeat = null },
            title = { Text("Повторять рано") },
            text = {
                Text(
                    "«${t.title}» делали ${if (d == 0L) "сегодня" else "$d дн. назад"}. " +
                        "Рекомендуется: ${t.howOften.lowercase()}. Частые повторы пользы не дают. Всё равно запустить?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRepeat = null
                    when (t.id) {
                        ru.tvdoctor.diag.Maintenance.TRIM -> vm.maintTrim()
                        ru.tvdoctor.diag.Maintenance.DEXOPT -> vm.maintDexopt()
                    }
                }) { Text("Всё равно запустить") }
            },
            dismissButton = { TextButton(onClick = { confirmRepeat = null }) { Text("Отмена") } },
        )
    }
    val fmtDate = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "Регулярные процедуры, которые Android на ТВ сам выполняет редко или никогда. " +
                    "Всё безопасно: данные, входы в аккаунты и настройки не затрагиваются.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Полное обслуживание", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Все пункты по очереди: закрыть фон → очистить кэш → обслуживание памяти → оптимизация приложений. " +
                            "Займёт 15–50 минут, ТВ в это время лучше не трогать. В конце — перезагрузка. " +
                            "Пункты, которые делали меньше недели назад, будут пропущены.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = vm::maintAll, enabled = enabled) { Text("Запустить всё") }
                }
            }
        }
        items(ru.tvdoctor.diag.Maintenance.TASKS) { t ->
            val last = s.lastDone(t.id)
            val days = s.daysSince(t.id)
            val overdue = t.everyDays > 0 && (days == null || days >= t.everyDays)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (overdue) Color(0x22FB8C00) else MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(t.title, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(t.what, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Как часто: ${t.howOften} · длится: ${t.duration}", style = MaterialTheme.typography.labelSmall)
                    val lastText = if (last == null || days == null) "Последний раз: нет данных" else
                        "Последний раз: ${fmtDate.format(java.util.Date(last))} (${if (days == 0L) "сегодня" else "$days дн. назад"})"
                    val nextText = when {
                        t.everyDays <= 0 -> ""
                        overdue -> " · пора сделать"
                        else -> " · следующее через ${t.everyDays - (days ?: 0)} дн."
                    }
                    Text(
                        lastText + nextText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) Color(0xFFEF6C00) else Color(0xFF2E7D32),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (t.id == ru.tvdoctor.diag.Maintenance.TRIM) {
                        Text(
                            "Учитывается и автоматическое обслуживание Android. По данным системы: ${s.systemMaint ?: "…"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = s.trimWithTest, onCheckedChange = { vm.toggleTrimWithTest() }, enabled = enabled)
                            Text("Замерить скорость памяти до и после", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        when (t.id) {
                            ru.tvdoctor.diag.Maintenance.TRIM ->
                                FilledTonalButton(onClick = { startOrConfirm(t, vm::maintTrim) }, enabled = enabled) { Text("Запустить") }
                            ru.tvdoctor.diag.Maintenance.DEXOPT -> {
                                FilledTonalButton(onClick = { startOrConfirm(t, vm::maintDexopt) }, enabled = enabled) { Text("Запустить") }
                                OutlinedButton(onClick = vm::maintDexoptStatus, enabled = enabled) { Text("Проверить оптимизацию") }
                            }
                            ru.tvdoctor.diag.Maintenance.CACHE ->
                                FilledTonalButton(onClick = vm::maintCache, enabled = enabled) { Text("Очистить") }
                            ru.tvdoctor.diag.Maintenance.KILL ->
                                FilledTonalButton(onClick = vm::maintKill, enabled = enabled) { Text("Закрыть") }
                        }
                    }
                }
            }
        }
        item { BigFilesCard(s, vm, enabled) }
        item { FirmwareCard(s, vm, enabled) }
        if (s.maintLog.isNotEmpty()) {
            item { SectionTitle("Результаты") }
            items(s.maintLog) { line ->
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BigFilesCard(s: UiState, vm: MainViewModel, enabled: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    val fmtDate = remember { java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()) }
    fun mbStr(b: Long) = if (b >= 1_073_741_824) "${Analyzer.fmt(b / 1_073_741_824.0)} ГБ" else "${b / 1_048_576} МБ"
    val files = s.bigFiles
    val selected = files.orEmpty().filter { it.path in s.bigSelected }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text("Большие файлы", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Ищет во встроенной памяти файлы от 20 МБ и установщики приложений (APK): забытые видео, загрузки, " +
                    "APK, которые после установки уже не нужны. Удаляется только то, что отметите. " +
                    "Данные приложений и игр (папка Android) не показываются и не трогаются.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = vm::findBigFiles, enabled = enabled) { Text(if (files == null) "Найти" else "Искать заново") }
            when {
                files == null -> {}
                files.isEmpty() -> Text("Больших файлов и APK не найдено.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text(
                        "Найдено ${files.size}, всего ${mbStr(files.sumOf { it.size })}:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    files.forEach { f ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Checkbox(
                                checked = f.path in s.bigSelected,
                                onCheckedChange = { on ->
                                    vm.setBigSelected(if (on) s.bigSelected + f.path else s.bigSelected - f.path)
                                },
                                enabled = enabled,
                            )
                            Column(Modifier.padding(top = 4.dp)) {
                                Text("${f.name} — ${mbStr(f.size)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    f.kind + if (f.mtimeSec > 0) " · от ${fmtDate.format(java.util.Date(f.mtimeSec * 1000))}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    f.path.substringBeforeLast('/'), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { vm.setBigSelected(if (s.bigSelected.isEmpty()) files.filter { it.kind.startsWith("Установщик") }.map { it.path }.toSet() else emptySet()) },
                            enabled = enabled,
                        ) { Text(if (s.bigSelected.isEmpty()) "Отметить все APK" else "Снять все") }
                    }
                    FilledTonalButton(onClick = { confirm = true }, enabled = enabled && selected.isNotEmpty()) {
                        Text(if (selected.isEmpty()) "Удалить" else "Удалить (${selected.size}, ${mbStr(selected.sumOf { it.size })})")
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Удалить ${selected.size} файл(ов)?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Удаление необратимо — в корзину файлы не попадут.\n")
                    selected.forEach { Text("• ${it.name} — ${mbStr(it.size)}", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.deleteSelectedFiles() }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FirmwareCard(s: UiState, vm: MainViewModel, enabled: Boolean) {
    val fw = s.firmware
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text("Обновление прошивки", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Производитель не даёт проверить обновления со стороны, поэтому ТВ-Доктор показывает текущую версию " +
                    "и открывает проверку обновлений прямо на экране ТВ — дальше пультом.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (fw != null) {
                Spacer(Modifier.height(6.dp))
                Text("Прошивка: ${fw.version.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
                Text("Собрана: ${fw.buildDate.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
                Text("Android ${fw.android} · патч безопасности: ${fw.securityPatch.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
                val patchAgeDays = runCatching {
                    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(fw.securityPatch)!!.time
                    (System.currentTimeMillis() - d) / 86_400_000L
                }.getOrNull()
                if (patchAgeDays != null && patchAgeDays > 365) {
                    Text(
                        "Патчу безопасности больше ${patchAgeDays / 365} г. — стоит проверить обновления.",
                        color = Color(0xFFEF6C00), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Программа обновления: " + (fw.updaters.joinToString(", ").ifEmpty { "не найдена" }),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = vm::checkFirmware, enabled = enabled) { Text("Показать версию") }
                FilledTonalButton(onClick = vm::openUpdater, enabled = enabled) { Text("Открыть проверку на ТВ") }
            }
        }
    }
}

// ============================================================ Терминал

@Composable
private fun TerminalTab(s: UiState, vm: MainViewModel, exporter: Exporter) {
    var category by remember { mutableStateOf<LibCategory?>(null) }
    var wrap by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<LibCmd?>(null) }
    val enabled = s.busy == null

    fun run(c: LibCmd) {
        if (c.changes) confirm = c else vm.runTerminal(c.command)
    }

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
            Button(onClick = { vm.runTerminal() }, enabled = enabled) { Text("Выполнить") }
            AssistChip(onClick = vm::analyzeStartup, label = { Text("Что открывается при включении") }, enabled = enabled)
        }
        Spacer(Modifier.height(4.dp))
        Text("Справочник команд", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COMMAND_LIBRARY.forEach { cat ->
                FilterChip(
                    selected = category == cat,
                    onClick = { category = if (category == cat) null else cat },
                    label = { Text(cat.title) },
                )
            }
        }
        category?.let { cat ->
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(cat.commands) { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.title + if (c.changes) "  ✎" else "",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(c.description, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    c.command, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { run(c) }, enabled = enabled) { Text("Запуск") }
                        }
                    }
                }
                item {
                    Text(
                        "✎ — команда меняет что-то на ТВ, перед запуском спрошу подтверждение.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val allLines = remember(s.terminalOut) { s.terminalOut.lines() }
        val shown = remember(allLines) { allLines.take(TerminalText.MAX_SCREEN_LINES) }
        val hasOut = s.terminalOut.isNotBlank()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = exporter::copyTerminal, enabled = hasOut) { Text("Скопировать в буфер") }
            FilterChip(selected = wrap, onClick = { wrap = !wrap }, label = { Text("Перенос строк") })
            OutlinedButton(onClick = exporter::shareTerminal, enabled = hasOut) { Text("Отправить файлом") }
        }
        if (allLines.size > shown.size) {
            Text(
                "На экране первые ${shown.size} строк из ${allLines.size}. Полностью — «Скопировать в буфер» или «Отправить файлом».",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            // Один блок текста, а не список строк: выделение по списку с прокруткой роняло приложение
            val text = remember(shown) { shown.joinToString("\n") }
            val box = Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState())
            Box(if (wrap) box else box.horizontalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = wrap)
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${c.title}?") },
            text = { Text(c.description + "\n\nКоманда:\n" + c.command) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.runTerminal(c.command)
                }) { Text("Выполнить") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

// ============================================================ Общие элементы

@Composable
private fun ExportRow(exporter: Exporter, enabled: Boolean) {
    Column {
        Text("Выгрузить", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = exporter::shareFindings, enabled = enabled) { Text("Поделиться выводами") }
            OutlinedButton(onClick = exporter::saveReport, enabled = enabled) { Text("Сохранить в Загрузки") }
            OutlinedButton(onClick = exporter::shareReport, enabled = enabled) { Text("Отчёт файлом") }
            OutlinedButton(onClick = exporter::copySummary, enabled = enabled) { Text("Копировать") }
        }
    }
}

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
