package ru.tvdoctor.diag

import ru.tvdoctor.diag.Analyzer.fmt
import ru.tvdoctor.diag.Analyzer.gb
import ru.tvdoctor.diag.Analyzer.gbBytes
import ru.tvdoctor.diag.Analyzer.mb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Текстовый отчёт, которым удобно поделиться (в том числе отправить на разбор). */
object Report {

    /** Только выводы — короткий текст для мессенджера. */
    fun findingsText(snap: SystemSnapshot?, boot: BootReport?, findings: List<Finding>): String = buildString {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        appendLine("ТВ-Доктор — выводы, $now")
        if (snap != null) appendLine("${snap.manufacturer} ${snap.model}, Android ${snap.android}, прошивка ${snap.build}")
        boot?.screenReadyMs?.let { appendLine("Загрузка до домашнего экрана: ${fmt(it / 1000.0)} с") }
        if (findings.isNotEmpty()) {
            val sc = Analyzer.score(findings)
            appendLine("Оценка: $sc/100 — ${Analyzer.scoreLabel(sc)}. ${Analyzer.summary(findings)}")
        }
        appendLine()
        if (findings.isEmpty()) {
            appendLine("Выводов пока нет — сначала соберите данные.")
        }
        for (x in findings) {
            val mark = when (x.severity) {
                Severity.CRIT -> "🔴"
                Severity.WARN -> "🟠"
                Severity.INFO -> "🔵"
                Severity.OK -> "🟢"
            }
            appendLine("$mark ${x.title}")
            x.choices.forEach { appendLine("   • ${it.label} (${it.pkg}) — ${it.note?.verdict?.text ?: ""}" + (it.lastUsed?.let { u -> ", $u" } ?: "")) }
            if (x.details.isNotBlank()) x.details.lines().forEach { appendLine("   $it") }
            appendLine()
        }
    }

    fun build(
        snap: SystemSnapshot?,
        boot: BootReport?,
        apps: List<AppInfo>,
        findings: List<Finding>,
        includeRaw: Boolean = true,
    ): String = buildString {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
        appendLine("ОТЧЁТ ТВ-ДОКТОР — $now")
        appendLine("=".repeat(40))

        if (snap != null) {
            appendLine("Устройство: ${snap.manufacturer} ${snap.model}, Android ${snap.android}")
            appendLine("Прошивка: ${snap.build}  Платформа: ${snap.platform}")
            appendLine("Работает с включения: ${(snap.uptimeSec / 60).toInt()} мин")
            appendLine("Встроенная память: свободно ${gb(snap.dataAvailKb)} из ${gb(snap.dataTotalKb)}")
            appendLine("ОЗУ: доступно ${mb(snap.memAvailKb)} из ${mb(snap.memTotalKb)}, swap ${mb(snap.swapTotalKb - snap.swapFreeKb)}/${mb(snap.swapTotalKb)}")
            appendLine("Нагрузка: ${snap.load1} / ${snap.load5}, ядер: ${snap.cpuCount}, в состоянии D: ${snap.dStateCount}, реальная: ${"%.1f".format(snap.realLoad)}" +
                (snap.cpuIdlePct?.let { ", простой процессора: ${"%.0f".format(it)}%" } ?: ""))
            if (snap.temps.isNotEmpty()) appendLine("Температуры: " + snap.temps.joinToString { "${it.first} ${fmt(it.second)}°C" })
            appendLine("eMMC: " + if (snap.emmc.isEmpty()) "нет данных" else snap.emmc.joinToString { "${it.first}=${it.second}" })
            appendLine()
        }

        if (findings.isNotEmpty()) {
            val sc = Analyzer.score(findings)
            appendLine("Оценка: $sc/100 — ${Analyzer.scoreLabel(sc)}. ${Analyzer.summary(findings)}")
            appendLine()
        }
        appendLine("ВЫВОДЫ")
        appendLine("-".repeat(40))
        for (x in findings) {
            val mark = when (x.severity) {
                Severity.CRIT -> "[!!]"
                Severity.WARN -> "[!]"
                Severity.INFO -> "[i]"
                Severity.OK -> "[ok]"
            }
            appendLine("$mark ${x.title}")
            x.choices.forEach { appendLine("     • ${it.label} (${it.pkg}) — ${it.note?.verdict?.text ?: ""}" + (it.lastUsed?.let { u -> ", $u" } ?: "")) }
            x.details.lines().forEach { appendLine("     $it") }
        }
        appendLine()

        if (snap != null && snap.topCpu.isNotEmpty()) {
            appendLine("ПРОЦЕССОР СЕЙЧАС")
            snap.topCpu.take(10).forEach { appendLine("  ${fmt(it.cpu).padStart(5)}%  ${mb(it.resKb).padStart(7)}  ${it.name}") }
            appendLine()
        }

        if (boot != null) {
            appendLine("ЗАГРУЗКА")
            appendLine("-".repeat(40))
            boot.measured?.let {
                appendLine("Замер телефоном: ТВ снова в сети через ${fmt(it.toAdbSec)} с, система готова через ${it.toCompletedSec?.let { v -> fmt(v) } ?: "?"} с")
            }
            boot.initStartMs?.let { appendLine("Ядро → init: ${fmt(it / 1000.0)} с") }
            var prev: Long? = null
            for (st in boot.stages) {
                val delta = prev?.let { " (+${fmt((st.atMs - it) / 1000.0)} с)" } ?: ""
                appendLine("  ${fmt(st.atMs / 1000.0).padStart(6)} с  ${st.title}$delta")
                prev = st.atMs
            }
            if (!boot.logsCoverBoot) appendLine("  (начало загрузки в журнале не сохранилось)")
            appendLine()

            if (boot.procStarts.isNotEmpty()) {
                appendLine("ЗАПУЩЕНО ПРИ ЗАГРУЗКЕ (${boot.procStarts.size})")
                boot.procStarts.take(80).forEach {
                    val t = it.atMs?.let { ms -> fmt(ms / 1000.0) + " с" } ?: "?"
                    appendLine("  ${t.padStart(8)}  ${it.type.padEnd(12)} ${it.process}")
                }
                appendLine()
            }
            if (boot.cpuTimes.isNotEmpty()) {
                appendLine("ПРОЦЕССОРНОЕ ВРЕМЯ С ВКЛЮЧЕНИЯ (топ)")
                boot.cpuTimes.take(20).forEach { appendLine("  ${(it.cpuSec.toString() + " с").padStart(8)}  ${it.name}") }
                appendLine()
            }
            if (boot.afterBootLoad.isNotEmpty()) {
                appendLine("НАГРУЗКА ПОСЛЕ ЗАГРУЗКИ (среднее)")
                boot.afterBootLoad.forEach { appendLine("  ${fmt(it.cpu).padStart(5)}%  ${it.name}") }
                appendLine()
            }
            val lateServices = boot.initServices.sortedByDescending { it.atMs }.take(15)
            if (lateServices.isNotEmpty()) {
                appendLine("СЛУЖБЫ INIT, ЗАПУЩЕННЫЕ ПОЗЖЕ ВСЕХ")
                lateServices.forEach { appendLine("  ${fmt(it.atMs / 1000.0).padStart(6)} с  ${it.name}") }
                appendLine()
            }
            appendLine("Журнал ядра: " + if (boot.dmesgAvailable) "${boot.dmesgIssues.size} подозрительных строк" else "недоступен без root")
            boot.dmesgIssues.forEach { appendLine("  $it") }
            appendLine()
        }

        if (apps.isNotEmpty()) {
            appendLine("ПРИЛОЖЕНИЯ (по занимаемой памяти)")
            apps.take(40).forEach {
                val flags = buildList {
                    if (it.thirdParty) add("стороннее")
                    if (it.bootReceiver) add("автозапуск")
                    if (it.disabled) add("отключено")
                }.joinToString(",")
                appendLine("  ${mb(it.pssKb).padStart(7)}  ${gbBytes(it.totalBytes).padStart(8)}  ${it.pkg}  $flags")
            }
            appendLine()
        }

        if (includeRaw) {
            appendLine("СЫРЫЕ ДАННЫЕ")
            appendLine("=".repeat(40))
            val raws = LinkedHashMap<String, String>()
            snap?.raw?.forEach { (k, v) -> raws["system/$k"] = v }
            boot?.raw?.forEach { (k, v) -> raws["boot/$k"] = v }
            for ((k, v) in raws) {
                appendLine("##### $k")
                appendLine(v.take(80_000))
                appendLine()
            }
        }
    }
}
