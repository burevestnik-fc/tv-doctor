package ru.tvdoctor.diag

enum class Severity { CRIT, WARN, INFO, OK }

/** Что можно сделать одной кнопкой прямо из вывода. */
enum class ActionKind {
    CLEAR_CACHE, RESTRICT_AUTOSTART, FAST_ANIMATIONS, STORAGE_TEST, REBOOT_MEASURE, LOAD_APPS, DISABLE_PACKAGES,
}

data class FixAction(val kind: ActionKind, val label: String, val packages: List<String> = emptyList())

/** Пункт, который можно отметить галочкой в карточке вывода. */
data class Choice(
    val pkg: String,
    val label: String,
    /** Что за приложение и можно ли его трогать (заполняет Analyzer). */
    val note: AppNote? = null,
    /** «последний раз 12.09.2026», «ни разу» или null, если данных нет. */
    val lastUsed: String? = null,
)

/**
 * Вывод. Если есть choices — карточка показывает их галочками, а кнопки choiceActions
 * применяются только к отмеченным пунктам (packages подставляются при нажатии).
 */
data class Finding(
    val severity: Severity,
    val title: String,
    val details: String,
    val action: FixAction? = null,
    val choices: List<Choice> = emptyList(),
    val choiceActions: List<FixAction> = emptyList(),
)

/** Одна точка истории — для сравнения «до/после». */
data class HistoryEntry(
    val timeMs: Long,
    val score: Int,
    val homeSec: Double?,
    val load1: Double?,
    val ramAvailPct: Double?,
    val dataFreePct: Double?,
    val autostart: Int?,
    val seqMBs: Double?,
    val smallMs: Double?,
)

/** Процесс и его загрузка процессора в момент замера. */
data class ProcCpu(val pid: Int, val name: String, val cpu: Double, val resKb: Long)

/** Процесс и сколько процессорного времени он набрал с момента запуска. */
data class ProcTime(val pid: Int, val name: String, val cpuSec: Long, val rssKb: Long)

data class SystemSnapshot(
    val manufacturer: String,
    val model: String,
    val android: String,
    val build: String,
    val platform: String,
    val uptimeSec: Double,
    val memTotalKb: Long,
    val memAvailKb: Long,
    val swapTotalKb: Long,
    val swapFreeKb: Long,
    val dataTotalKb: Long,
    val dataUsedKb: Long,
    val dataAvailKb: Long,
    val load1: Double,
    val load5: Double,
    val cpuCount: Int,
    /**
     * Процессов в состоянии D (непрерываемое ожидание). На ТВ-чипах MStar/MediaTek это десятки
     * драйверов, которые всегда ждут событий железа: они раздувают среднюю нагрузку, не тратя процессор.
     */
    val dStateCount: Int = 0,
    /** Простой процессора по top, % от всех ядер (null — не удалось определить). */
    val cpuIdlePct: Double? = null,
    /** Ожидание диска по top (iowait), % от всех ядер: процессор простаивает, ожидая память. */
    val cpuIowPct: Double? = null,
    /** На сколько секунд часы ТВ впереди (+) или позади (−) телефона; null — не удалось узнать. */
    val clockSkewSec: Long? = null,
    val temps: List<Pair<String, Double>>,
    val emmc: List<Pair<String, String>>,
    /** Доп. сведения о памяти, доступные без root (Health HAL, задержка записи). */
    val storageInfo: List<String>,
    val topCpu: List<ProcCpu>,
    val thirdPartyPackages: Set<String>,
    val bootReceiverPackages: List<String>,
    /** Приложения, которым запрещена фоновая работа. */
    val bgRestricted: Set<String> = emptySet(),
    /** Масштабы анимаций: окно, переходы, аниматор (null — по умолчанию 1x). */
    val animScales: List<Double?> = emptyList(),
    val raw: Map<String, String>,
) {
    /** Нагрузка без «вечно ждущих» драйверов — то, что реально конкурирует за процессор и диск. */
    val realLoad: Double get() = (load1 - dStateCount).coerceAtLeast(0.0)

    /** Занятость процессора по top, % от всех ядер. */
    val cpuBusyPct: Double? get() = cpuIdlePct?.let { (100.0 - it - (cpuIowPct ?: 0.0)).coerceIn(0.0, 100.0) }
}

data class BootStage(val key: String, val title: String, val atMs: Long)

data class ProcStart(val atMs: Long?, val process: String, val type: String, val component: String)

data class InitService(val name: String, val atMs: Long)

/** Время, замеренное самим приложением при перезагрузке. */
data class MeasuredBoot(val toAdbSec: Double, val toCompletedSec: Double?)

data class BootReport(
    val uptimeSec: Double,
    val bootCompleted: Boolean,
    val logsCoverBoot: Boolean,
    val initStartMs: Long?,
    val stages: List<BootStage>,
    val initServices: List<InitService>,
    val procStarts: List<ProcStart>,
    val cpuTimes: List<ProcTime>,
    val dmesgAvailable: Boolean,
    val dmesgIssues: List<String>,
    val measured: MeasuredBoot? = null,
    val afterBootLoad: List<ProcCpu> = emptyList(),
    val raw: Map<String, String>,
) {
    /** Момент, когда система показала домашний экран (мс от включения). */
    val screenReadyMs: Long?
        get() = stages.filter { it.key in END_STAGE_KEYS }.maxOfOrNull { it.atMs }

    companion object {
        val END_STAGE_KEYS = setOf(
            "boot_progress_enable_screen", "sf_stop_bootanim", "wm_boot_animation_done",
        )
    }
}

data class AppInfo(
    val pkg: String,
    val thirdParty: Boolean,
    val disabled: Boolean,
    val bootReceiver: Boolean,
    val appBytes: Long?,
    val dataBytes: Long?,
    val cacheBytes: Long?,
    val pssKb: Long,
    val bgRestricted: Boolean = false,
    /** Когда приложение последний раз открывали (null — нет данных или никогда). */
    val lastUsedMs: Long? = null,
    val launchMs: Long? = null,
) {
    val totalBytes: Long get() = (appBytes ?: 0) + (dataBytes ?: 0) + (cacheBytes ?: 0)
}

/** Результат теста скорости встроенной памяти. */
data class StorageTest(
    val seqWriteMBs: Double?,
    val smallSyncMs: Double?,
    val raw: String,
)
