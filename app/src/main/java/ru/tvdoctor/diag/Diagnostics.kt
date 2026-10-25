package ru.tvdoctor.diag

import kotlinx.coroutines.delay

/** Сбор данных с телевизора. sh — выполнить команду и получить вывод. */
class Diagnostics(private val sh: suspend (String) -> String) {

    private suspend fun safe(cmd: String): String =
        try {
            sh(cmd)
        } catch (e: IllegalStateException) {
            throw e // нет подключения — пусть увидит интерфейс
        } catch (e: Exception) {
            "ОШИБКА: ${e.message ?: e.javaClass.simpleName}"
        }

    // ---------------------------------------------------------------- Обзор системы

    suspend fun snapshot(): SystemSnapshot {
        val raw = LinkedHashMap<String, String>()
        suspend fun run(key: String, cmd: String): String = safe(cmd).also { raw[key] = it }

        val props = Parsers.parseProps(run("getprop", "getprop"))
        val mem = Parsers.parseMeminfo(run("meminfo", "cat /proc/meminfo"))
        val df = Parsers.parseDf(run("df", "df -k /data"))
        val load = run("loadavg", "cat /proc/loadavg").trim().split(Regex("\\s+"))
        val cpuCount = run("nproc", "nproc").trim().toIntOrNull()
            ?: run("cpuinfo", "grep -c ^processor /proc/cpuinfo").trim().toIntOrNull()
            ?: 1
        val uptime = run("uptime", "cat /proc/uptime").trim().split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0
        // Часы ТВ против часов телефона: неверное время ломает HTTPS и даёт даты «из будущего»
        val tvEpoch = run("clock", "date +%s").trim().lines().lastOrNull()?.trim()?.toLongOrNull()
        val clockSkew = tvEpoch?.let { it - System.currentTimeMillis() / 1000 }

        val temps = run(
            "thermal",
            "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null)=\$(cat \$z/temp 2>/dev/null)\"; done",
        ).lines().mapNotNull { line ->
            val name = line.substringBefore('=').trim()
            val v = line.substringAfter('=', "").trim().toDoubleOrNull() ?: return@mapNotNull null
            val c = if (v > 1000) v / 1000.0 else v
            if (c <= 0 || c > 150) null else (name.ifEmpty { "датчик" } to c)
        }

        val emmc = run(
            "emmc",
            "for f in /sys/class/mmc_host/mmc*/mmc*/life_time /sys/class/mmc_host/mmc*/mmc*/pre_eol_info " +
                "/sys/block/mmcblk0/device/life_time /sys/block/mmcblk0/device/pre_eol_info " +
                "/sys/bus/mmc/devices/*/life_time /sys/bus/mmc/devices/*/pre_eol_info " +
                "/sys/class/block/mmcblk0/device/life_time /sys/class/block/mmcblk0/device/pre_eol_info " +
                "/sys/devices/platform/*/*/mmc_host/mmc*/mmc*/life_time /sys/devices/platform/*/*/mmc_host/mmc*/mmc*/pre_eol_info " +
                "/sys/devices/platform/*/mmc_host/mmc*/mmc*/life_time /sys/devices/platform/*/mmc_host/mmc*/mmc*/pre_eol_info; " +
                "do [ -r \"\$f\" ] && echo \"\$f=\$(cat \$f)\"; done",
        ).lines().filter { it.contains('=') && !it.startsWith("ОШИБКА") }.map {
            it.substringBefore('=').substringAfterLast('/') to it.substringAfter('=').trim()
        }.distinctBy { it.first }

        // Без root износ иногда отдаёт системная служба здоровья (Health HAL) или видна задержка записи
        val healthHal = run(
            "health_hal",
            "dumpsys android.hardware.health.IHealth/default 2>/dev/null | grep -iE 'eol|life|storage' | head -n 10",
        )
        val diskLatency = run("diskstats_head", "dumpsys diskstats | head -n 4")
        val storageInfo = buildList {
            healthHal.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("ОШИБКА") && !it.contains("Can't find service", true) }
                .forEach { add("Health HAL: $it") }
            Regex("Latency:\\s*(\\d+)\\s*ms").find(diskLatency)?.let {
                add("Задержка записи (замер Android): ${it.groupValues[1]} мс")
            }
        }

        val thirdParty = Parsers.parsePackages(run("packages3", "pm list packages -3"))
        val receivers = (
            Parsers.parseReceiverPackages(
                run("boot_receivers", "cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED"),
            ) + Parsers.parseReceiverPackages(
                run("locked_boot_receivers", "cmd package query-receivers --brief -a android.intent.action.LOCKED_BOOT_COMPLETED"),
            )
            ).distinct()

        val top = topSample(raw)
        val idlePct = raw["top"]?.let { Parsers.parseTopIdlePct(it) }
        val iowPct = raw["top"]?.let { Parsers.parseTopPct(it, "iow") }
        val dCount = run("d_state", "ps -A -o STAT | grep -c '^D'").trim().lines().lastOrNull()?.trim()?.toIntOrNull() ?: 0
        val restricted = bgRestricted(thirdParty)
        val anim = run(
            "animations",
            "settings get global window_animation_scale; settings get global transition_animation_scale; " +
                "settings get global animator_duration_scale",
        ).lines().map { it.trim() }.filter { it.isNotEmpty() }.take(3).map { it.toDoubleOrNull() }

        return SystemSnapshot(
            manufacturer = props["ro.product.manufacturer"].orEmpty(),
            model = props["ro.product.model"].orEmpty(),
            android = props["ro.build.version.release"].orEmpty(),
            build = props["ro.build.display.id"].orEmpty(),
            platform = props["ro.board.platform"]?.ifEmpty { null } ?: props["ro.hardware"].orEmpty(),
            uptimeSec = uptime,
            memTotalKb = mem["MemTotal"] ?: 0,
            memAvailKb = mem["MemAvailable"] ?: 0,
            swapTotalKb = mem["SwapTotal"] ?: 0,
            swapFreeKb = mem["SwapFree"] ?: 0,
            dataTotalKb = df?.first ?: 0,
            dataUsedKb = df?.second ?: 0,
            dataAvailKb = df?.third ?: 0,
            load1 = load.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            load5 = load.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            cpuCount = cpuCount,
            dStateCount = dCount,
            cpuIdlePct = idlePct,
            cpuIowPct = iowPct,
            clockSkewSec = clockSkew,
            temps = temps,
            emmc = emmc,
            storageInfo = storageInfo,
            topCpu = top.take(15),
            thirdPartyPackages = thirdParty,
            bootReceiverPackages = receivers,
            bgRestricted = restricted,
            animScales = anim,
            raw = raw,
        )
    }

    /** Загрузка процессора по процессам за ~1 секунду. */
    suspend fun topSample(raw: MutableMap<String, String>? = null): List<ProcCpu> {
        // Формат по умолчанию: он сортирует по %CPU. С -o некоторые прошивки сортируют по имени
        // (заголовок «RES[NAME]»), и в выборку попадают не те процессы.
        val out = safe("top -b -n 2 -d 1 -m 20")
        val parsed = Parsers.parseTop(out)
        raw?.put("top", out)
        return parsed
    }

    // ---------------------------------------------------------------- Анализ загрузки

    suspend fun bootReport(): BootReport {
        val raw = LinkedHashMap<String, String>()
        suspend fun run(key: String, cmd: String): String = safe(cmd).also { raw[key] = it }

        val uptime = run("uptime", "cat /proc/uptime").trim().split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0
        val props = Parsers.parseProps(run("getprop", "getprop"))
        val bootCompleted = props["sys.boot_completed"] == "1"

        val filter = "grep -E 'boot_progress|bootanim|boot_animation|am_proc_start|boot_completed'"
        var eventsText = run("events", "logcat -b events -d -v monotonic | $filter")
        var events = Parsers.parseEvents(eventsText)
        if (events.isEmpty()) {
            eventsText = run("events_plain", "logcat -b events -d | $filter")
            events = Parsers.parseEvents(eventsText)
        }

        val uptimeMs = (uptime * 1000).toLong()
        val stages = LinkedHashMap<String, BootStage>()
        val procStarts = ArrayList<ProcStart>()
        for (e in events) {
            val title = STAGE_TITLES[e.tag]
            if (title != null) {
                if (stages.containsKey(e.tag)) continue
                val v = e.msg.trim().toLongOrNull()
                val at = when {
                    v != null && v in 0..(uptimeMs + 5_000) -> v
                    e.timeSec != null -> (e.timeSec * 1000).toLong()
                    else -> continue
                }
                stages[e.tag] = BootStage(e.tag, title, at)
            } else if (e.tag == "am_proc_start") {
                val body = e.msg.trim().removePrefix("[").removeSuffix("]")
                val parts = body.split(",", limit = 6)
                if (parts.size >= 5) {
                    procStarts += ProcStart(
                        atMs = e.timeSec?.let { (it * 1000).toLong() },
                        process = parts[3].trim(),
                        type = parts[4].trim(),
                        component = parts.getOrNull(5)?.trim()?.removePrefix("{")?.removeSuffix("}").orEmpty(),
                    )
                }
            }
        }

        val (initStart, initServices) = Parsers.parseInitServices(props)
        val cpuTimes = Parsers.parsePsTimes(run("ps", "ps -A -o PID,TIME,RSS,NAME"))

        val dmesg = run("dmesg", "dmesg | tail -n 3000")
        val dmesgAvailable = dmesg.isNotBlank() &&
            !dmesg.contains("Permission denied", ignoreCase = true) &&
            !dmesg.contains("not permitted", ignoreCase = true) &&
            !dmesg.startsWith("ОШИБКА")
        val dmesgIssues = if (!dmesgAvailable) emptyList() else dmesg.lines().filter { line ->
            DMESG_PATTERNS.any { it.containsMatchIn(line) }
        }.takeLast(40)
        raw["dmesg"] = dmesg.takeLast(60_000)

        val sortedStages = stages.values.sortedBy { it.atMs }
        val screenReady = sortedStages.filter { it.key in BootReport.END_STAGE_KEYS }.maxOfOrNull { it.atMs }
        val window = (screenReady ?: uptimeMs) + 120_000
        val bootStarts = procStarts.filter { it.atMs == null || it.atMs <= window }.take(400)

        return BootReport(
            uptimeSec = uptime,
            bootCompleted = bootCompleted,
            logsCoverBoot = stages.containsKey("boot_progress_start"),
            initStartMs = initStart,
            stages = sortedStages,
            initServices = initServices,
            procStarts = bootStarts,
            cpuTimes = cpuTimes.take(30),
            dmesgAvailable = dmesgAvailable,
            dmesgIssues = dmesgIssues,
            raw = raw,
        )
    }

    /** Несколько замеров top подряд → средняя загрузка по процессам. */
    suspend fun loadAfterBoot(samples: Int, onProgress: (Int) -> Unit): List<ProcCpu> {
        val sum = HashMap<String, Double>()
        val res = HashMap<String, Long>()
        repeat(samples) { i ->
            onProgress(i + 1)
            for (p in topSample()) {
                sum[p.name] = (sum[p.name] ?: 0.0) + p.cpu
                res[p.name] = maxOf(res[p.name] ?: 0, p.resKb)
            }
            delay(3_000)
        }
        return sum.map { (name, total) -> ProcCpu(0, name, total / samples, res[name] ?: 0) }
            .sortedByDescending { it.cpu }
            .take(15)
    }

    /**
     * Тест скорости встроенной памяти: последовательная запись 64 МБ и 100 мелких записей
     * с принудительным сбросом на диск. Изношенная eMMC особенно медленна на мелких записях.
     * Время меряется на телефоне — сетевые задержки на фоне секунд пренебрежимо малы.
     */
    suspend fun storageTest(onProgress: (String) -> Unit): StorageTest {
        val raw = StringBuilder()
        val dir = "/data/local/tmp"
        safe("rm -f $dir/tvd_seq $dir/tvd_small")

        onProgress("Тест памяти: запись 64 МБ…")
        val t1 = System.nanoTime()
        val seqOut = safe("dd if=/dev/zero of=$dir/tvd_seq bs=1048576 count=64 conv=fsync")
        val seqSec = (System.nanoTime() - t1) / 1e9
        raw.appendLine("seq: $seqOut (${"%.2f".format(seqSec)} с)")
        val seqOk = !seqOut.contains("ОШИБКА") && !seqOut.contains("No space", true) && !seqOut.contains("denied", true)
        safe("rm -f $dir/tvd_seq")

        onProgress("Тест памяти: 100 мелких записей…")
        val t2 = System.nanoTime()
        val smallOut = safe(
            "i=0; while [ \$i -lt 100 ]; do dd if=/dev/zero of=$dir/tvd_small bs=4096 count=1 conv=fsync 2>/dev/null; " +
                "i=\$((i+1)); done; echo done",
        )
        val smallSec = (System.nanoTime() - t2) / 1e9
        raw.appendLine("small: ${smallOut.trim()} (${"%.2f".format(smallSec)} с)")
        val smallOk = smallOut.contains("done")
        safe("rm -f $dir/tvd_small")

        return StorageTest(
            seqWriteMBs = if (seqOk && seqSec > 0) 64 / seqSec else null,
            smallSyncMs = if (smallOk) smallSec * 1000 / 100 else null,
            raw = raw.toString(),
        )
    }

    suspend fun prepareBootLogging(): String =
        safe("logcat -G 16M; logcat -g | head -n 3")

    // ---------------------------------------------------------------- Приложения

    suspend fun apps(): List<AppInfo> {
        val all = Parsers.parsePackages(safe("pm list packages"))
        val third = Parsers.parsePackages(safe("pm list packages -3"))
        val disabled = Parsers.parsePackages(safe("pm list packages -d"))
        val receivers = (
            Parsers.parseReceiverPackages(safe("cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED")) +
                Parsers.parseReceiverPackages(safe("cmd package query-receivers --brief -a android.intent.action.LOCKED_BOOT_COMPLETED"))
            ).toSet()

        val disk = safe("dumpsys diskstats")
        val names = Parsers.parseStringArray(disk, "Package Names")
        val appSizes = Parsers.parseLongArray(disk, "App Sizes")
        val dataSizes = Parsers.parseLongArray(disk, "App Data Sizes")
        val cacheSizes = Parsers.parseLongArray(disk, "Cache Sizes")
        val sizeIdx = names.withIndex().associate { it.value to it.index }

        val pss = Parsers.parseMeminfoByPackage(safe("dumpsys meminfo"))
        val restricted = bgRestricted(third)
        val lastUsed = lastUsedTimes()

        return all.map { pkg ->
            val i = sizeIdx[pkg]
            AppInfo(
                pkg = pkg,
                thirdParty = pkg in third,
                disabled = pkg in disabled,
                bootReceiver = pkg in receivers,
                appBytes = i?.let { appSizes.getOrNull(it) },
                dataBytes = i?.let { dataSizes.getOrNull(it) },
                cacheBytes = i?.let { cacheSizes.getOrNull(it) },
                pssKb = pss[pkg] ?: 0,
                bgRestricted = pkg in restricted,
                lastUsedMs = lastUsed[pkg],
            )
        }.sortedWith(compareByDescending<AppInfo> { it.pssKb }.thenByDescending { it.totalBytes })
    }

    /** Время последнего использования приложений из статистики Android (usagestats). */
    suspend fun lastUsedTimes(): Map<String, Long> {
        val out = safe(
            "dumpsys usagestats | grep -oE 'package=[^ ]+ totalTimeUsed=\"[^\"]*\" lastTimeUsed=\"[^\"]*\"' | sort -u",
        )
        val re = Regex("package=(\\S+) totalTimeUsed=\"([^\"]*)\" lastTimeUsed=\"([^\"]*)\"")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val map = HashMap<String, Long>()
        for (line in out.lines()) {
            val m = re.find(line) ?: continue
            val total = m.groupValues[2]
            if (total == "00:00" || total.isEmpty()) continue // только отметился, но не использовался
            val t = runCatching { fmt.parse(m.groupValues[3])?.time }.getOrNull() ?: continue
            if (t < 946_684_800_000L) continue // «1970» и прочий мусор
            val pkg = m.groupValues[1]
            map[pkg] = maxOf(map[pkg] ?: 0L, t)
        }
        return map
    }

    /** Какие из пакетов ограничены в фоне (appops RUN_ANY_IN_BACKGROUND = ignore). */
    suspend fun bgRestricted(pkgs: Collection<String>): Set<String> {
        if (pkgs.isEmpty()) return emptySet()
        val out = safe(
            "for p in ${pkgs.joinToString(" ")}; do " +
                "echo \"\$p=\$(cmd appops get \$p RUN_ANY_IN_BACKGROUND)\"; done",
        )
        return out.lines().filter { it.contains("ignore") }.map { it.substringBefore('=').trim() }.toSet()
    }

    /** Холодный запуск приложения: остановить, запустить, замерить, вернуться домой. */
    suspend fun measureLaunch(pkg: String): Pair<Long?, String> {
        var comp = safe(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LEANBACK_LAUNCHER $pkg | tail -n 1",
        ).trim()
        if (!comp.contains('/')) {
            comp = safe(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER $pkg | tail -n 1",
            ).trim()
        }
        if (!comp.contains('/')) return null to "У приложения нет экрана запуска"
        safe("am force-stop $pkg")
        delay(1_500)
        val out = safe("am start -W -n $comp")
        delay(1_000)
        safe("input keyevent KEYCODE_HOME")
        val ms = Regex("TotalTime:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("WaitTime:\\s*(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
        return ms to out
    }

    companion object {
        val STAGE_TITLES = linkedMapOf(
            "boot_progress_start" to "Запуск Android (zygote)",
            "boot_progress_preload_start" to "Предзагрузка классов: начало",
            "boot_progress_preload_end" to "Предзагрузка классов: конец",
            "boot_progress_system_run" to "Запуск system_server",
            "boot_progress_pms_start" to "Проверка приложений: начало",
            "boot_progress_pms_system_scan_start" to "Сканирование системных приложений",
            "boot_progress_pms_data_scan_start" to "Сканирование установленных приложений",
            "boot_progress_pms_scan_end" to "Сканирование приложений завершено",
            "boot_progress_pms_ready" to "Менеджер приложений готов",
            "boot_progress_ams_ready" to "Системные службы готовы",
            "boot_progress_enable_screen" to "Экран включён",
            "sf_stop_bootanim" to "Анимация загрузки остановлена",
            "wm_boot_animation_done" to "Анимация загрузки завершена",
        )

        val DMESG_PATTERNS = listOf(
            Regex("mmc\\d.*(timeout|timed out|error|retry|CRC)", RegexOption.IGNORE_CASE),
            Regex("(I/O error|blk_update_request|Buffer I/O)", RegexOption.IGNORE_CASE),
            Regex("(EXT4-fs error|F2FS-fs.*error|fsck)", RegexOption.IGNORE_CASE),
            Regex("(thermal|throttl).*(trip|critical|limit)", RegexOption.IGNORE_CASE),
            Regex("lowmemorykiller|lmkd.*kill", RegexOption.IGNORE_CASE),
        )
    }
}
