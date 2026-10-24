package ru.tvdoctor.diag

/** Разбор текстового вывода команд Android. Всё максимально терпимо к разным прошивкам. */
object Parsers {

    private val WS = Regex("\\s+")

    fun parseProps(text: String): Map<String, String> {
        val re = Regex("^\\[(.+?)]: \\[(.*)]\\s*$")
        val map = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val m = re.find(line.trim()) ?: continue
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }

    fun parseMeminfo(text: String): Map<String, Long> {
        val map = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val num = line.substring(idx + 1).trim().split(WS).firstOrNull()?.toLongOrNull() ?: continue
            map[key] = num
        }
        return map
    }

    /** "123M", "1.5G", "512K", "2048" (КБ) → килобайты. */
    fun parseSizeKb(s: String): Long {
        val t = s.trim().uppercase()
        if (t.isEmpty()) return 0
        val unit = t.last()
        val num = t.dropLast(1).replace(',', '.').toDoubleOrNull()
        return when (unit) {
            'K' -> num?.toLong() ?: 0
            'M' -> ((num ?: 0.0) * 1024).toLong()
            'G' -> ((num ?: 0.0) * 1024 * 1024).toLong()
            else -> t.replace(',', '.').toDoubleOrNull()?.toLong() ?: 0
        }
    }

    /** Вывод df: Filesystem 1K-blocks Used Available Use% Mounted on. Возвращает (всего, занято, свободно) в КБ. */
    fun parseDf(text: String): Triple<Long, Long, Long>? {
        val line = text.lines().lastOrNull { it.isNotBlank() && !it.startsWith("Filesystem") } ?: return null
        val t = line.trim().split(WS)
        if (t.size < 4) return null
        val total = t[1].toLongOrNull() ?: return null
        val used = t[2].toLongOrNull() ?: return null
        val avail = t[3].toLongOrNull() ?: return null
        return Triple(total, used, avail)
    }

    /** Вывод top (toybox). Берём последний кадр — в нём загрузка за интервал замера. */
    fun parseTop(text: String): List<ProcCpu> {
        val lines = text.lines()
        val hdrIdx = lines.indexOfLast { it.contains("PID") && it.contains("CPU") }
        if (hdrIdx < 0) return emptyList()
        val header = lines[hdrIdx].replace("[%CPU]", " %CPU").trim().split(WS)
        val iPid = header.indexOf("PID")
        val iCpu = header.indexOfFirst { it.contains("CPU") }
        val iRes = header.indexOf("RES")
        val iName = header.lastIndex
        if (iPid < 0 || iCpu < 0) return emptyList()
        val result = ArrayList<ProcCpu>()
        for (line in lines.drop(hdrIdx + 1)) {
            val t = line.trim().split(WS)
            if (t.size <= iName) continue
            val pid = t[iPid].toIntOrNull() ?: continue
            val cpu = t[iCpu].trimEnd('%').replace(',', '.').toDoubleOrNull() ?: continue
            val res = if (iRes >= 0) parseSizeKb(t[iRes]) else 0
            val name = t.drop(iName).joinToString(" ")
            if (name.startsWith("top ") || name == "top") continue
            result += ProcCpu(pid, name, cpu, res)
        }
        return result.sortedByDescending { it.cpu }
    }

    /** ps -A -o PID,TIME,RSS,NAME → процессорное время каждого процесса. */
    fun parsePsTimes(text: String): List<ProcTime> {
        val result = ArrayList<ProcTime>()
        for (line in text.lines()) {
            val t = line.trim().split(WS)
            if (t.size < 4) continue
            val pid = t[0].toIntOrNull() ?: continue
            val sec = parseCpuTime(t[1]) ?: continue
            val rss = t[2].toLongOrNull() ?: parseSizeKb(t[2])
            result += ProcTime(pid, t.drop(3).joinToString(" "), sec, rss)
        }
        return result.sortedByDescending { it.cpuSec }
    }

    /** "mm:ss", "hh:mm:ss", "d-hh:mm:ss" → секунды. */
    fun parseCpuTime(s: String): Long? {
        var days = 0L
        var rest = s
        val dash = s.indexOf('-')
        if (dash > 0) {
            days = s.substring(0, dash).toLongOrNull() ?: return null
            rest = s.substring(dash + 1)
        }
        val parts = rest.split(':').map { it.substringBefore('.').toLongOrNull() ?: return null }
        var sec = 0L
        for (p in parts) sec = sec * 60 + p
        return days * 86_400 + sec
    }

    /** Список пакетов из вывода "pm list packages". */
    fun parsePackages(text: String): Set<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").substringBefore(' ').substringAfterLast('=') }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Вывод "cmd package query-receivers --brief": строки вида com.foo/.BootReceiver */
    fun parseReceiverPackages(text: String): List<String> {
        val re = Regex("^\\s*([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
        return text.lineSequence().mapNotNull { re.find(it)?.groupValues?.get(1) }.distinct().toList()
    }

    fun parseStringArray(text: String, label: String): List<String> {
        val m = Regex(Regex.escape(label) + ": \\[(.*?)]").find(text) ?: return emptyList()
        return m.groupValues[1].split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    fun parseLongArray(text: String, label: String): List<Long> {
        val m = Regex(Regex.escape(label) + ": \\[(.*?)]").find(text) ?: return emptyList()
        return m.groupValues[1].split(',').map { it.trim().toLongOrNull() ?: 0L }
    }

    /** dumpsys meminfo → PSS (КБ) по каждому пакету (процессы "pkg:remote" суммируются в pkg). */
    fun parseMeminfoByPackage(text: String): Map<String, Long> {
        var idx = text.indexOf("Total PSS by process")
        if (idx < 0) idx = text.indexOf("Total RSS by process")
        if (idx < 0) return emptyMap()
        val re = Regex("^\\s*([\\d,]+)K: ([^\\s(]+)")
        val map = HashMap<String, Long>()
        for (line in text.substring(idx).lines().drop(1)) {
            if (line.isBlank()) break
            val m = re.find(line) ?: continue
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val pkg = m.groupValues[2].substringBefore(':')
            map[pkg] = (map[pkg] ?: 0) + kb
        }
        return map
    }

    /** Строка журнала событий. timeSec = секунды от включения (если формат monotonic). */
    data class EventLine(val timeSec: Double?, val tag: String, val msg: String)

    private val EV_MONO = Regex("^\\s*(\\d+\\.\\d+)\\s+(?:\\S+\\s+)*?[VDIWEF]\\s+([A-Za-z_][\\w.]*)\\s*:\\s?(.*)$")
    private val EV_DATE = Regex("^\\s*\\d\\d-\\d\\d\\s+[\\d:.]+\\s+(?:\\S+\\s+)*?[VDIWEF]\\s+([A-Za-z_][\\w.]*)\\s*:\\s?(.*)$")

    fun parseEvents(text: String): List<EventLine> {
        val result = ArrayList<EventLine>()
        for (line in text.lineSequence()) {
            val m = EV_MONO.find(line)
            if (m != null) {
                result += EventLine(m.groupValues[1].toDoubleOrNull(), m.groupValues[2], m.groupValues[3].trim())
                continue
            }
            val d = EV_DATE.find(line) ?: continue
            result += EventLine(null, d.groupValues[1], d.groupValues[2].trim())
        }
        return result
    }

    /** Свойства ro.boottime.* — когда init запустил каждую системную службу. */
    fun parseInitServices(props: Map<String, String>): Pair<Long?, List<InitService>> {
        var initStart: Long? = null
        val services = ArrayList<InitService>()
        for ((k, v) in props) {
            if (!k.startsWith("ro.boottime.")) continue
            val name = k.removePrefix("ro.boottime.")
            val num = v.toLongOrNull() ?: continue
            if (name == "init") {
                initStart = if (num > 100_000_000) num / 1_000_000 else num
                continue
            }
            if (name.startsWith("init.")) continue // длительности этапов init, не время старта
            val ms = if (num > 100_000_000) num / 1_000_000 else num
            services += InitService(name, ms)
        }
        return initStart to services.sortedBy { it.atMs }
    }
}
