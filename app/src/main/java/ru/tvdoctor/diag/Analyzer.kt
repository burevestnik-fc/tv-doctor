package ru.tvdoctor.diag

import java.util.Locale

/** Превращает сырые цифры в понятные выводы. */
object Analyzer {

    fun analyze(snap: SystemSnapshot?, boot: BootReport?, apps: List<AppInfo>, storage: StorageTest? = null): List<Finding> {
        val f = ArrayList<Finding>()
        if (snap != null) analyzeSnapshot(snap, f, storage != null)
        if (storage != null) analyzeStorage(storage, f)
        if (boot != null) analyzeBoot(boot, snap, f)
        if (apps.isNotEmpty()) {
            analyzeApps(apps, f)
        } else if (snap != null) {
            f += Finding(
                Severity.INFO, "Приложения ещё не проверены",
                "Загрузите список приложений — найду неиспользуемые, бесполезные и тяжёлые.",
                FixAction(ActionKind.LOAD_APPS, "Проверить приложения"),
            )
        }
        if (f.none { it.severity == Severity.CRIT || it.severity == Severity.WARN } && (snap != null || boot != null)) {
            f += Finding(Severity.OK, "Явных проблем не найдено", "По собранным данным серьёзных отклонений нет.")
        }
        return f.sortedBy { it.severity.ordinal }
    }

    private fun analyzeSnapshot(s: SystemSnapshot, f: MutableList<Finding>, storageTested: Boolean) {
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
        if (s.emmc.isEmpty() && !storageTested) {
            f += Finding(
                Severity.INFO, "Прямой показатель износа памяти недоступен",
                "Прошивка закрывает его без root. Оценить состояние памяти можно тестом скорости (около 30 секунд)." +
                    if (s.storageInfo.isNotEmpty()) "\n" + s.storageInfo.joinToString("\n") else "",
                FixAction(ActionKind.STORAGE_TEST, "Запустить тест памяти"),
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

        // Сжатая память (swap/zram): активное использование = ОЗУ не хватает
        if (s.swapTotalKb > 0) {
            val usedPct = (s.swapTotalKb - s.swapFreeKb) * 100.0 / s.swapTotalKb
            if (usedPct > 60) {
                f += Finding(
                    Severity.WARN, "Система активно сжимает память",
                    "Сжатая память (swap) занята на ${pct(usedPct)}. Значит, ОЗУ постоянно не хватает, и процессор тратит время " +
                        "на сжатие и распаковку. Помогает уменьшить число приложений, работающих в фоне: " +
                        "выберите ненужные в карточке автозапуска или на вкладке «Приложения».",
                )
            }
        }

        // Анимации: на слабых ТВ уменьшение до 0.5x заметно ускоряет отклик
        if (s.animScales.isNotEmpty() && s.animScales.all { (it ?: 1.0) >= 1.0 }) {
            f += Finding(
                Severity.INFO, "Анимации интерфейса на обычной скорости",
                "Если уменьшить их до 0.5x, меню и переходы станут отзывчивее — на слабом процессоре это заметно. " +
                    "Вернуть можно в «Для разработчиков».",
                FixAction(ActionKind.FAST_ANIMATIONS, "Ускорить анимации (0.5x)"),
            )
        }

        // Давно без перезагрузки
        val days = s.uptimeSec / 86_400
        if (days >= 7) {
            f += Finding(
                Severity.INFO, "ТВ не перезагружался ${days.toInt()} дн.",
                "Режим ожидания — не выключение: за дни накапливаются «зависшие» процессы и утечки памяти. " +
                    "Перезагрузка раз в неделю освежает систему.",
                FixAction(ActionKind.REBOOT_MEASURE, "Перезагрузить и измерить"),
            )
        }

        // Автозапуск
        val thirdBoot = s.bootReceiverPackages.filter { it in s.thirdPartyPackages && it !in s.bgRestricted }
        if (thirdBoot.size >= 4) {
            f += Finding(
                Severity.WARN, "Много сторонних приложений в автозапуске (${thirdBoot.size})",
                "Каждое из них просыпается при включении ТВ и отнимает время загрузки. Отметьте нужные: " +
                    "ненужные — отключите, нужным — запретите фоновую работу (сами приложения будут открываться как обычно).",
                choices = thirdBoot.map { Choice(it, it) },
                choiceActions = listOf(
                    FixAction(ActionKind.RESTRICT_AUTOSTART, "Запретить фон"),
                    FixAction(ActionKind.DISABLE_PACKAGES, "Отключить"),
                ),
            )
        } else if (thirdBoot.isNotEmpty()) {
            f += Finding(Severity.INFO, "Сторонние приложения в автозапуске", thirdBoot.joinToString("\n") { "• $it" })
        }
    }

    private fun analyzeStorage(t: StorageTest, f: MutableList<Finding>) {
        val parts = ArrayList<String>()
        var sev = Severity.OK
        fun worse(x: Severity) { if (x.ordinal < sev.ordinal) sev = x }
        t.seqWriteMBs?.let {
            parts += "Последовательная запись: ${fmt(it)} МБ/с (норма для eMMC ТВ: 20–80)"
            when {
                it < 8 -> worse(Severity.CRIT)
                it < 18 -> worse(Severity.WARN)
            }
        }
        t.smallSyncMs?.let {
            parts += "Мелкая запись с сохранением: ${fmt(it)} мс на операцию (норма: до 10–15)"
            when {
                it > 40 -> worse(Severity.CRIT)
                it > 18 -> worse(Severity.WARN)
            }
        }
        if (parts.isEmpty()) {
            f += Finding(Severity.INFO, "Тест скорости памяти не удался", t.raw.take(400))
            return
        }
        val title = when (sev) {
            Severity.CRIT -> "Память очень медленная — похоже на износ eMMC"
            Severity.WARN -> "Память медленнее нормы"
            else -> "Скорость памяти в норме"
        }
        val tail = when (sev) {
            Severity.CRIT -> "\nИменно медленные мелкие записи дают долгую загрузку и долгое открытие приложений. " +
                "Если память к тому же заполнена — сначала освободите место (это ускоряет eMMC) и повторите тест."
            Severity.WARN -> "\nПовторите тест после очистки памяти: при заполненной памяти eMMC работает медленнее."
            else -> ""
        }
        f += Finding(sev, title, parts.joinToString("\n") + tail)
    }

    private fun analyzeBoot(b: BootReport, s: SystemSnapshot?, f: MutableList<Finding>) {
        if (!b.logsCoverBoot) {
            f += Finding(
                Severity.INFO, "Журнал загрузки не сохранился",
                "Начало загрузки уже вытеснено из журнала. Перезагрузите ТВ через приложение — " +
                    "оно увеличит журнал и снимет загрузку заново.",
                FixAction(ActionKind.REBOOT_MEASURE, "Перезагрузить и измерить"),
            )
        }

        val ready = b.screenReadyMs
        b.measured?.let { m ->
            val total = m.toCompletedSec ?: m.toAdbSec
            if (ready == null) {
                // Журнала нет — остаётся только замер телефоном
                f += Finding(
                    when {
                        total > 120 -> Severity.WARN
                        else -> Severity.INFO
                    },
                    "Перезагрузка целиком: ${fmt(total)} с (замер телефоном)",
                    "От команды перезагрузки до момента, когда ТВ снова доступен по сети. Включает выключение " +
                        "и подключение к сети, поэтому всегда больше реальной загрузки.",
                )
            } else {
                f += Finding(
                    Severity.INFO,
                    "Перезагрузка целиком: ${fmt(total)} с (замер телефоном)",
                    "Включает выключение ТВ и ожидание сети, поэтому больше реальной загрузки " +
                        "(${fmt(ready / 1000.0)} с по журналу ТВ). " +
                        if (total - ready / 1000.0 > 50) {
                            "Большой разрыв значит, что ТВ долго не появляется в сети после загрузки — " +
                                "частая причина: VPN-клиент в автозапуске."
                        } else {
                            ""
                        },
                )
            }
        }

        if (ready != null) {
            val sec = ready / 1000.0
            f += Finding(
                when {
                    sec > 90 -> Severity.CRIT
                    sec > 50 -> Severity.WARN
                    else -> Severity.OK
                },
                "Загрузка до домашнего экрана: ${fmt(sec)} с",
                "По журналу ТВ, от включения питания. Нормально для Android-ТВ: 25–50 с.",
            )
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
            val restricted = s?.bgRestricted.orEmpty()
            val started = b.procStarts.map { it.process.substringBefore(':') }
                .filter { it in third && it !in restricted }.distinct()
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

    /** Приложения, которые на ТВ заведомо бесполезны: сервисы закрыты или не работают в РФ. */
    private val USELESS = mapOf(
        "com.google.android.videos" to "Google Play Фильмы — сервис закрыт",
        "com.google.android.music" to "Google Play Музыка — сервис закрыт",
        "com.google.android.apps.mediashell" to "",
        "com.google.android.play.games" to "Google Play Игры — на ТВ почти не нужны",
        "com.netflix.ninja" to "Netflix — не работает в России",
        "com.amazon.amazonvideo.livingroom" to "Prime Video — не работает в России",
        "com.disney.disneyplus" to "Disney+ — не работает в России",
        "com.spotify.tv.android" to "Spotify — не работает в России",
        "com.hbo.hbonow" to "HBO — не работает в России",
    ).filterValues { it.isNotEmpty() }

    private fun analyzeApps(apps: List<AppInfo>, f: MutableList<Finding>) {
        val active = apps.filter { !it.disabled }

        val useless = active.filter { it.pkg in USELESS }
        if (useless.isNotEmpty()) {
            f += Finding(
                Severity.WARN, "Бесполезные на ТВ приложения (${useless.size})",
                "Они занимают место и память, а пользы не приносят. Отметьте, какие отключить. Отключение обратимо.",
                choices = useless.map { Choice(it.pkg, USELESS[it.pkg] ?: it.pkg) },
                choiceActions = listOf(FixAction(ActionKind.DISABLE_PACKAGES, "Отключить")),
            )
        }

        if (apps.any { it.lastUsedMs != null }) {
            val now = System.currentTimeMillis()
            val month = 30L * 86_400_000
            val unused = active.filter {
                it.thirdParty && it.pkg !in USELESS && !it.pkg.contains("vpn", true) && !it.pkg.contains("wireguard", true) &&
                    (it.lastUsedMs == null || now - it.lastUsedMs > month)
            }.sortedBy { it.lastUsedMs ?: 0 }
            if (unused.isNotEmpty()) {
                val fmtDate = java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                f += Finding(
                    if (unused.count { it.bootReceiver } >= 2) Severity.WARN else Severity.INFO,
                    "Не открывались больше месяца (${unused.size})",
                    "Кандидаты на отключение: освободят память и ускорят загрузку. Отметьте, какие отключить. Отключение обратимо.",
                    choices = unused.map {
                        val last = it.lastUsedMs?.let { t -> "последний раз ${fmtDate.format(java.util.Date(t))}" } ?: "ни разу"
                        Choice(it.pkg, "${it.pkg} — $last" + if (it.bootReceiver && !it.bgRestricted) ", в автозапуске" else "")
                    },
                    choiceActions = listOf(
                        FixAction(ActionKind.DISABLE_PACKAGES, "Отключить"),
                        FixAction(ActionKind.RESTRICT_AUTOSTART, "Запретить фон"),
                    ),
                )
            }
        }

        val bigCache = active.filter { (it.cacheBytes ?: 0) > 200L * 1024 * 1024 }
        if (bigCache.isNotEmpty()) {
            f += Finding(
                Severity.INFO, "Приложения с большим кэшем",
                bigCache.sortedByDescending { it.cacheBytes }.take(6)
                    .joinToString("\n") { "• ${it.pkg} — ${gbBytes(it.cacheBytes ?: 0)}" },
                FixAction(ActionKind.CLEAR_CACHE, "Очистить кэш"),
            )
        }

        val cache = apps.sumOf { it.cacheBytes ?: 0 }
        if (cache > 500L * 1024 * 1024) {
            f += Finding(
                Severity.INFO, "Кэш приложений: ${gbBytes(cache)}",
                "Очистка безопасна: приложения просто заново скачают временные файлы.",
                FixAction(ActionKind.CLEAR_CACHE, "Очистить кэш"),
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

    // ---------------------------------------------------------------- оценка и история

    /** Оценка 0–100: критичные проблемы −25, предупреждения −10. */
    fun score(findings: List<Finding>): Int {
        var v = 100
        for (x in findings) {
            v -= when (x.severity) {
                Severity.CRIT -> 25
                Severity.WARN -> 10
                else -> 0
            }
        }
        return v.coerceIn(0, 100)
    }

    fun scoreLabel(score: Int): String = when {
        score >= 85 -> "Отличное состояние"
        score >= 65 -> "Есть что улучшить"
        score >= 40 -> "Заметные проблемы"
        else -> "Серьёзные проблемы"
    }

    /** Итог в одну строку: главная проблема. */
    fun summary(findings: List<Finding>): String {
        val top = findings.firstOrNull { it.severity == Severity.CRIT }
            ?: findings.firstOrNull { it.severity == Severity.WARN }
            ?: return "Серьёзных проблем не найдено."
        val more = findings.count { it.severity == Severity.CRIT || it.severity == Severity.WARN } - 1
        return "Главное: ${top.title.replaceFirstChar { it.lowercase() }}" + if (more > 0) " (и ещё $more)" else ""
    }

    fun historyEntry(
        snap: SystemSnapshot?, boot: BootReport?, storage: StorageTest?, findings: List<Finding>,
    ): HistoryEntry = HistoryEntry(
        timeMs = System.currentTimeMillis(),
        score = score(findings),
        homeSec = boot?.screenReadyMs?.let { it / 1000.0 },
        load1 = snap?.load1,
        ramAvailPct = snap?.takeIf { it.memTotalKb > 0 }?.let { it.memAvailKb * 100.0 / it.memTotalKb },
        dataFreePct = snap?.takeIf { it.dataTotalKb > 0 }?.let { it.dataAvailKb * 100.0 / it.dataTotalKb },
        autostart = snap?.let { sn -> sn.bootReceiverPackages.count { it in sn.thirdPartyPackages && it !in sn.bgRestricted } },
        seqMBs = storage?.seqWriteMBs,
        smallMs = storage?.smallSyncMs,
    )

    /** Что изменилось между двумя замерами — человеческим языком. */
    fun compare(prev: HistoryEntry, cur: HistoryEntry): List<String> {
        val out = ArrayList<String>()
        fun line(name: String, a: Double?, b: Double?, unit: String, lowerIsBetter: Boolean, minDelta: Double) {
            if (a == null || b == null) return
            val d = b - a
            if (kotlin.math.abs(d) < minDelta) return
            val better = if (lowerIsBetter) d < 0 else d > 0
            val sign = if (d > 0) "+" else "−"
            out += "${if (better) "▲ лучше" else "▼ хуже"}: $name ${fmt(a)} → ${fmt(b)}$unit ($sign${fmt(kotlin.math.abs(d))})"
        }
        line("загрузка до экрана", prev.homeSec, cur.homeSec, " с", true, 1.0)
        line("нагрузка", prev.load1, cur.load1, "", true, 1.0)
        line("свободно ОЗУ", prev.ramAvailPct, cur.ramAvailPct, "%", false, 3.0)
        line("свободно места", prev.dataFreePct, cur.dataFreePct, "%", false, 2.0)
        line("приложений в автозапуске", prev.autostart?.toDouble(), cur.autostart?.toDouble(), "", true, 1.0)
        line("мелкая запись", prev.smallMs, cur.smallMs, " мс", true, 2.0)
        line("оценка", prev.score.toDouble(), cur.score.toDouble(), "", false, 1.0)
        return out
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
