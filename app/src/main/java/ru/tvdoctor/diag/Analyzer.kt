package ru.tvdoctor.diag

import java.util.Locale

/** Превращает сырые цифры в понятные выводы. */
object Analyzer {

    fun analyze(snap: SystemSnapshot?, boot: BootReport?, apps: List<AppInfo>): List<Finding> {
        val f = ArrayList<Finding>()
        if (snap != null) analyzeSnapshot(snap, f)
        if (boot != null) analyzeBoot(boot, snap, f)
        if (apps.isNotEmpty()) analyzeApps(apps, f)
        if (f.none { it.severity == Severity.CRIT || it.severity == Severity.WARN } && (snap != null || boot != null)) {
            f += Finding(Severity.OK, "Явных проблем не найдено", "По собранным данным серьёзных отклонений нет.")
        }
        return f.sortedBy { it.severity.ordinal }
    }

    private fun analyzeSnapshot(s: SystemSnapshot, f: MutableList<Finding>) {
        // Встроенная память
        if (s.dataTotalKb > 0) {
            val freePct = s.dataAvailKb * 100.0 / s.dataTotalKb
            val txt = "Свободно ${gb(s.dataAvailKb)} из ${gb(s.dataTotalKb)} (${pct(freePct)})."
            when {
                freePct < 10 -> f += Finding(
                    Severity.CRIT, "Встроенная память почти заполнена",
                    "$txt При заполнении больше 90% флеш-память резко замедляется — это частая причина тормозов. " +
                        "Удалите ненужные приложения и очистите кэш (вкладка «Приложения»).",
                )
                freePct < 20 -> f += Finding(
                    Severity.WARN, "Мало свободной встроенной памяти",
                    "$txt Желательно держать свободными хотя бы 20%.",
                )
                else -> f += Finding(Severity.OK, "Встроенной памяти достаточно", txt)
            }
        }

        // Оперативная память
        if (s.memTotalKb > 0) {
            val availPct = s.memAvailKb * 100.0 / s.memTotalKb
            val txt = "Доступно ${mb(s.memAvailKb)} из ${mb(s.memTotalKb)} (${pct(availPct)})."
            when {
                availPct < 12 -> f += Finding(
                    Severity.CRIT, "Не хватает оперативной памяти",
                    "$txt Система постоянно выгружает и заново запускает приложения. " +
                        "Посмотрите, кто больше всех занимает память, во вкладке «Приложения».",
                )
                availPct < 25 -> f += Finding(Severity.WARN, "Оперативной памяти впритык", txt)
                else -> f += Finding(Severity.OK, "Оперативной памяти достаточно", txt)
            }
        }

        // Износ eMMC
        for ((name, value) in s.emmc) {
            val nums = Regex("0x([0-9a-fA-F]+)").findAll(value).map { it.groupValues[1].toInt(16) }.toList()
            if (nums.isEmpty()) continue
            val v = nums.max()
            if (name == "life_time") {
                val txt = if (v >= 11) "ресурс исчерпан" else "израсходовано примерно ${(v - 1) * 10}–${v * 10}% ресурса"
                f += when {
                    v >= 9 -> Finding(
                        Severity.CRIT, "Флеш-память (eMMC) сильно изношена",
                        "Показатель износа: $value — $txt. Программно это не лечится: тормоза будут нарастать. " +
                            "Помогает только замена памяти/платы в сервисе или новый ТВ.",
                    )
                    v >= 6 -> Finding(Severity.WARN, "Флеш-память заметно изношена", "Показатель износа: $value — $txt.")
                    else -> Finding(Severity.OK, "Флеш-память не изношена", "Показатель износа: $value — $txt.")
                }
            } else if (name == "pre_eol_info") {
                if (v >= 2) {
                    f += Finding(
                        Severity.CRIT, "Флеш-память на исходе резерва",
                        "pre_eol_info = $value (${if (v >= 3) "критично" else "предупреждение"}): " +
                            "контроллер eMMC израсходовал большую часть резервных блоков.",
                    )
                }
            }
        }
        if (s.emmc.isEmpty()) {
            f += Finding(
                Severity.INFO, "Износ памяти узнать не удалось",
                "Прошивка не отдаёт показатель износа eMMC без root. Косвенно о медленной памяти скажет анализ загрузки.",
            )
        }

        // Процессор
        if (s.cpuCount > 0 && s.load1 > s.cpuCount * 1.5) {
            f += Finding(
                Severity.WARN, "Процессор перегружен",
                "Средняя нагрузка ${fmt(s.load1)} при ${s.cpuCount} ядрах. " +
                    "Высокая нагрузка без активных процессов часто означает ожидание медленной памяти.",
            )
        }
        val hogs = s.topCpu.filter { it.cpu >= 25 && !it.name.startsWith("top") }
        if (hogs.isNotEmpty()) {
            f += Finding(
                Severity.WARN, "Кто-то занимает процессор прямо сейчас",
                hogs.take(5).joinToString("\n") { "• ${it.name} — ${fmt(it.cpu)}%" },
            )
        }

        // Температура
        val hot = s.temps.filter { it.second >= 80 }
        if (hot.isNotEmpty()) {
            f += Finding(
                Severity.WARN, "Высокая температура",
                hot.joinToString("\n") { "• ${it.first}: ${fmt(it.second)} °C" } +
                    "\nПри перегреве процессор снижает частоту. Проверьте вентиляцию и пыль.",
            )
        }

        // Автозапуск
        val thirdBoot = s.bootReceiverPackages.filter { it in s.thirdPartyPackages }
        if (thirdBoot.size >= 4) {
            f += Finding(
                Severity.WARN, "Много сторонних приложений в автозапуске (${thirdBoot.size})",
                thirdBoot.joinToString("\n") { "• $it" } +
                    "\nКаждое из них просыпается при включении ТВ и отнимает время загрузки.",
            )
        } else if (thirdBoot.isNotEmpty()) {
            f += Finding(Severity.INFO, "Сторонние приложения в автозапуске", thirdBoot.joinToString("\n") { "• $it" })
        }
    }

    private fun analyzeBoot(b: BootReport, s: SystemSnapshot?, f: MutableList<Finding>) {
        if (!b.logsCoverBoot) {
            f += Finding(
                Severity.INFO, "Журнал загрузки не сохранился",
                "Начало загрузки уже вытеснено из журнала. Нажмите «Перезагрузить и измерить» — " +
                    "приложение увеличит журнал и снимет загрузку заново.",
            )
        }

        b.measured?.let { m ->
            val total = m.toCompletedSec ?: m.toAdbSec
            val sev = when {
                total > 120 -> Severity.CRIT
                total > 70 -> Severity.WARN
                else -> Severity.OK
            }
            f += Finding(
                sev, "Полная загрузка: ${fmt(total)} с",
                "От команды перезагрузки до готовности системы (замер телефоном, включая выключение). " +
                    "Нормально для Android-ТВ: 40–70 с.",
            )
        }

        val ready = b.screenReadyMs
        if (ready != null) {
            val sec = ready / 1000.0
            if (b.measured == null) {
                f += Finding(
                    when {
                        sec > 90 -> Severity.CRIT
                        sec > 50 -> Severity.WARN
                        else -> Severity.OK
                    },
                    "Домашний экран появился через ${fmt(sec)} с после включения", "По журналу загрузки.",
                )
            }
            // Самый длинный этап
            val gaps = b.stages.zipWithNext { a, c -> Triple(a, c, c.atMs - a.atMs) }
            val worst = gaps.maxByOrNull { it.third }
            if (worst != null && worst.third > 10_000 && worst.third > ready * 0.3) {
                val isScan = worst.first.key.contains("pms")
                f += Finding(
                    Severity.WARN,
                    "Самый долгий этап: «${worst.first.title}» — ${fmt(worst.third / 1000.0)} с",
                    if (isScan) {
                        "Долгое сканирование приложений обычно означает медленную флеш-память или очень много установленных приложений."
                    } else {
                        "Этот этап занимает ${pct(worst.third * 100.0 / ready)} всей загрузки."
                    },
                )
            }
        }

        val initStart = b.initStartMs
        if (initStart != null && initStart > 8_000) {
            f += Finding(
                Severity.WARN, "Ядро стартует долго: ${fmt(initStart / 1000.0)} с",
                "До запуска init прошло много времени — это до Android, на уровне ядра и драйверов. Часто признак медленной памяти.",
            )
        }

        val third = s?.thirdPartyPackages.orEmpty()
        if (third.isNotEmpty() && b.procStarts.isNotEmpty()) {
            val started = b.procStarts.map { it.process.substringBefore(':') }.filter { it in third }.distinct()
            if (started.size >= 5) {
                f += Finding(
                    Severity.WARN, "При загрузке запустилось ${started.size} сторонних приложений",
                    started.take(12).joinToString("\n") { "• $it" },
                )
            }
        }

        if (b.dmesgIssues.any { Diagnostics.DMESG_PATTERNS[0].containsMatchIn(it) || Diagnostics.DMESG_PATTERNS[1].containsMatchIn(it) }) {
            f += Finding(
                Severity.CRIT, "Ошибки флеш-памяти в журнале ядра",
                b.dmesgIssues.takeLast(6).joinToString("\n") +
                    "\nОшибки и таймауты eMMC — признак аппаратной проблемы с памятью.",
            )
        }

        val busy = b.afterBootLoad.filter { it.cpu >= 20 }
        if (busy.isNotEmpty()) {
            f += Finding(
                Severity.WARN, "После загрузки процессор ещё долго занят",
                busy.take(6).joinToString("\n") { "• ${it.name} — в среднем ${fmt(it.cpu)}%" },
            )
        }
    }

    private fun analyzeApps(apps: List<AppInfo>, f: MutableList<Finding>) {
        val cache = apps.sumOf { it.cacheBytes ?: 0 }
        if (cache > 500L * 1024 * 1024) {
            f += Finding(
                Severity.INFO, "Кэш приложений: ${gbBytes(cache)}",
                "Его можно очистить кнопкой «Очистить кэш всех» — это безопасно.",
            )
        }
        val heavy = apps.filter { it.thirdParty && it.pssKb > 150_000 }
        if (heavy.isNotEmpty()) {
            f += Finding(
                Severity.INFO, "Тяжёлые сторонние приложения в памяти",
                heavy.take(6).joinToString("\n") { "• ${it.pkg} — ${mb(it.pssKb)}" },
            )
        }
    }

    // ---------------------------------------------------------------- форматирование

    fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
    fun pct(v: Double): String = "${v.toInt()}%"
    fun mb(kb: Long): String = "${kb / 1024} МБ"
    fun gb(kb: Long): String = String.format(Locale.US, "%.1f ГБ", kb / 1024.0 / 1024.0)
    fun gbBytes(b: Long): String =
        if (b >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f ГБ", b / 1024.0 / 1024 / 1024)
        else "${b / 1024 / 1024} МБ"
}
