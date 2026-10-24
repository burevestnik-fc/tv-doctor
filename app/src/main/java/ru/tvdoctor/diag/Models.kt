package ru.tvdoctor.diag

enum class Severity { CRIT, WARN, INFO, OK }

data class Finding(val severity: Severity, val title: String, val details: String)

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
    val temps: List<Pair<String, Double>>,
    val emmc: List<Pair<String, String>>,
    /** Доп. сведения о памяти, доступные без root (Health HAL, задержка записи). */
    val storageInfo: List<String>,
    val topCpu: List<ProcCpu>,
    val thirdPartyPackages: Set<String>,
    val bootReceiverPackages: List<String>,
    /** Приложения, которым запрещена фоновая работа. */
    val bgRestricted: Set<String> = emptySet(),
    val raw: Map<String, String>,
)

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
