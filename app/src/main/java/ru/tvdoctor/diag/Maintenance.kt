package ru.tvdoctor.diag

import kotlinx.coroutines.delay

/** Найденный большой файл. kind — понятное описание типа. */
data class BigFile(val path: String, val size: Long, val mtimeSec: Long, val kind: String) {
    val name: String get() = path.substringAfterLast('/')
}

/** Сведения о прошивке и о том, чем ТВ проверяет обновления. */
data class FirmwareInfo(
    val version: String,
    val buildDate: String,
    val securityPatch: String,
    val android: String,
    /** Пакеты, похожие на программы обновления прошивки. */
    val updaters: List<String>,
)

/** Пункт обслуживания: что делает, как часто, сколько длится. */
data class MaintTask(
    val id: String,
    val title: String,
    val what: String,
    val howOften: String,
    /** Рекомендуемый интервал, дней (для подсветки «пора»). */
    val everyDays: Int,
    val duration: String,
)

/**
 * Обслуживание ТВ без root. Всё безопасно: ничего не удаляет из данных пользователя
 * и не меняет настройки (кроме явно указанных).
 */
class Maintenance(private val sh: suspend (String) -> String) {

    companion object {
        const val KILL = "kill"
        const val CACHE = "cache"
        const val TRIM = "trim"
        const val DEXOPT = "dexopt"

        val TASKS = listOf(
            MaintTask(
                TRIM, "Обслуживание памяти (TRIM)",
                "Сообщает микросхеме памяти, какие блоки свободны, и запускает плановое обслуживание Android. " +
                    "Без этого память со временем замедляется в разы: перед каждой записью ей приходится стирать старые данные. " +
                    "Android делает это сам только в простое, а ТВ в настоящий простой почти не уходит.",
                "Раз в месяц", 30, "2–10 минут",
            ),
            MaintTask(
                DEXOPT, "Оптимизация приложений",
                "После обновлений Android запускает приложения в медленном режиме, пока не перекомпилирует их в простое. " +
                    "На ТВ простоя мало, поэтому приложения могут годами открываться медленнее, чем могли бы.",
                "Раз в месяц и после обновления приложений", 30, "10–40 минут, ТВ лучше не трогать",
            ),
            MaintTask(
                CACHE, "Очистка кэша приложений",
                "Удаляет временные файлы всех приложений: картинки, превью, временные видео. " +
                    "Входы в аккаунты и настройки не трогает. Освобождает место — eMMC быстрее, когда свободно больше.",
                "Раз в месяц или когда мало места", 30, "до минуты",
            ),
            MaintTask(
                KILL, "Закрыть фоновые приложения",
                "Выгружает из оперативной памяти всё, что висит в фоне. Помогает, если ТВ стал тормозить после долгой работы. " +
                    "Ничего не удаляет, приложения откроются заново как обычно.",
                "Когда ТВ начал подтормаживать", 0, "секунды",
            ),
        )

        /** Чаще этого повторять долгие процедуры нет смысла, дней. */
        const val MIN_GAP_DAYS = 7

        /** «2026-10-25 02:10:00» из dumpsys mount → время в мс (null, если не распознано). */
        fun parseSystemDate(v: String?): Long? {
            val m = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2})""").find(v ?: return null) ?: return null
            return runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(m.groupValues[1])?.time
            }.getOrNull()
        }

        private const val DEXOPT_DONE = "/data/local/tmp/tvd_dexopt.done"
        private const val DEXOPT_LOG = "/data/local/tmp/tvd_dexopt.log"
    }

    /** Когда система сама последний раз делала обслуживание памяти (строка из dumpsys mount). */
    suspend fun systemLastMaintenance(): String? {
        val out = sh("dumpsys mount | grep -i 'last maint'")
        val line = out.lines().firstOrNull { it.contains("maint", true) } ?: return null
        val v = line.substringAfter(':').trim()
        return when {
            v.isEmpty() -> null
            v.startsWith("1970") || v == "0" -> "ни разу"
            else -> v
        }
    }

    /** Доступная ОЗУ, МБ. */
    private suspend fun memAvailMb(): Long? =
        sh("grep MemAvailable /proc/meminfo").trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.div(1024)

    /** Свободно на /data, МБ. */
    private suspend fun dataFreeMb(): Long? {
        val line = sh("df -k /data").lines().lastOrNull { it.isNotBlank() && !it.startsWith("Filesystem") } ?: return null
        return line.trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()?.div(1024)
    }

    suspend fun killBackground(): String {
        val before = memAvailMb()
        sh("am kill-all")
        delay(1500)
        val after = memAvailMb()
        return if (before != null && after != null) {
            "Фоновые приложения закрыты. Доступно ОЗУ: $before → $after МБ (${signed(after - before)} МБ)."
        } else {
            "Фоновые приложения закрыты."
        }
    }

    suspend fun clearCache(): String {
        val before = dataFreeMb()
        sh("pm trim-caches 999G")
        val after = dataFreeMb()
        return if (before != null && after != null) {
            "Кэш очищен. Свободно места: $before → $after МБ (${signed(after - before)} МБ)."
        } else {
            "Кэш всех приложений очищен."
        }
    }

    /**
     * TRIM через плановое обслуживание Android. Ждём, пока система отметит его завершение
     * (меняется «last maintenance»), не дольше [maxWaitMin] минут.
     */
    suspend fun trim(onProgress: (String) -> Unit, maxWaitMin: Int = 15): String {
        val before = systemLastMaintenance()
        var out = sh("sm idle-maint run")
        var how = "sm idle-maint run"
        if (looksFailed(out)) {
            out = sh("sm fstrim")
            how = "sm fstrim"
        }
        if (looksFailed(out)) return "Не удалось запустить обслуживание памяти: ${out.trim().take(300)}"

        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMin * 60_000L) {
            delay(10_000)
            keepAwake()
            val sec = (System.currentTimeMillis() - start) / 1000
            onProgress("Обслуживание памяти идёт… ${sec / 60} мин ${sec % 60} с (не выключайте ТВ)")
            val now = systemLastMaintenance()
            if (now != null && now != before) {
                return "Обслуживание памяти завершено за ${sec / 60} мин ${sec % 60} с ($how).\n" +
                    "Было: ${before ?: "нет данных"} → стало: $now."
            }
        }
        return "Обслуживание запущено ($how), но система не подтвердила завершение за $maxWaitMin мин. " +
            "Обычно это значит, что оно ещё идёт — подождите несколько минут, прежде чем выключать ТВ."
    }

    /**
     * Оптимизация приложений. Задача долгая, поэтому запускается на ТВ в фоне (переживает обрыв связи),
     * а мы опрашиваем метку завершения. Отмена прекращает только ожидание.
     */
    suspend fun dexopt(onProgress: (String) -> Unit, maxWaitMin: Int = 60): String {
        sh("rm -f $DEXOPT_DONE $DEXOPT_LOG")
        sh(
            "setsid sh -c 'cmd package bg-dexopt-job > $DEXOPT_LOG 2>&1; echo $? > $DEXOPT_DONE' " +
                "< /dev/null > /dev/null 2>&1 &",
        )
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMin * 60_000L) {
            delay(15_000)
            keepAwake()
            val sec = (System.currentTimeMillis() - start) / 1000
            onProgress("Оптимизирую приложения… ${sec / 60} мин ${sec % 60} с. Можно отменить ожидание — работа продолжится на ТВ.")
            val done = sh("cat $DEXOPT_DONE 2>/dev/null").trim()
            if (done.isNotEmpty()) {
                val log = sh("tail -n 5 $DEXOPT_LOG 2>/dev/null").trim()
                val ok = done == "0" && !looksFailed(log)
                return (if (ok) "Оптимизация приложений завершена" else "Оптимизация завершилась с ошибкой") +
                    " за ${sec / 60} мин ${sec % 60} с." + if (log.isNotEmpty()) "\n$log" else ""
            }
        }
        return "Оптимизация идёт дольше $maxWaitMin мин и продолжается на ТВ. Проверьте позже: «Проверить оптимизацию»."
    }

    /** Состояние фоновой оптимизации, если её запускали раньше. */
    suspend fun dexoptStatus(): String {
        val done = sh("cat $DEXOPT_DONE 2>/dev/null").trim()
        val log = sh("tail -n 5 $DEXOPT_LOG 2>/dev/null").trim()
        return when {
            done.isNotEmpty() -> "Оптимизация завершена (код $done).${if (log.isNotEmpty()) "\n$log" else ""}"
            log.isNotEmpty() || sh("ps -A | grep -c bg-dexopt").trim().toIntOrNull()?.let { it > 0 } == true ->
                "Оптимизация ещё идёт на ТВ."
            else -> "Оптимизацию через ТВ-Доктор ещё не запускали."
        }
    }

    // ---------------------------------------------------------------- большие файлы

    /**
     * Ищет во внутренней памяти большие файлы (от 20 МБ) и любые APK.
     * Папку Android/ (данные и кэш игр) не показываем: удаление оттуда ломает приложения.
     */
    suspend fun findBigFiles(): List<BigFile> {
        val out = sh(
            "find /sdcard/ -type f \\( -size +40960 -o -iname '*.apk' \\) -exec stat -c '%s|%Y|%n' {} + 2>/dev/null; " +
                "find /data/local/tmp/ -type f -size +2048 -exec stat -c '%s|%Y|%n' {} + 2>/dev/null",
        )
        return out.lines().mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val size = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
            val path = parts[2].trim().replace("//", "/")
            if (!isDeletable(path)) return@mapNotNull null
            BigFile(path, size, parts[1].trim().toLongOrNull() ?: 0, kindOf(path))
        }.distinctBy { it.path }.sortedByDescending { it.size }
    }

    /** Удаляем только из общей памяти и временной папки ADB, и никогда — из Android/. */
    fun isDeletable(path: String): Boolean =
        (path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/") || path.startsWith("/data/local/tmp/")) &&
            !path.contains("/Android/") && !path.contains("..")

    private fun kindOf(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            ext == "apk" -> "Установщик приложения (APK). После установки не нужен"
            ext in setOf("mp4", "mkv", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "3gp") -> "Видео"
            ext in setOf("mp3", "flac", "wav", "aac", "ogg", "m4a") -> "Музыка"
            ext in setOf("zip", "rar", "7z", "gz", "tar", "img", "bin") -> "Архив или образ"
            ext in setOf("jpg", "jpeg", "png", "heic") -> "Изображение"
            path.startsWith("/data/local/tmp/") -> "Временный файл отладки"
            else -> "Файл"
        }
    }

    /** Удаляет выбранные файлы. Возвращает текст итога. */
    suspend fun deleteFiles(files: List<BigFile>): String {
        var ok = 0
        var freed = 0L
        val failed = ArrayList<String>()
        for (f in files) {
            if (!isDeletable(f.path)) { failed += f.name; continue }
            val q = "'" + f.path.replace("'", "'\\''") + "'"
            val res = sh("rm -f $q && [ ! -e $q ] && echo deleted").trim()
            if (res.endsWith("deleted")) { ok++; freed += f.size } else failed += f.name
        }
        return "Удалено файлов: $ok, освобождено ${freed / 1_048_576} МБ." +
            if (failed.isNotEmpty()) "\nНе удалось удалить: ${failed.joinToString(", ")}" else ""
    }

    // ---------------------------------------------------------------- прошивка

    suspend fun firmwareInfo(): FirmwareInfo {
        fun prop(out: String, key: String) =
            out.lines().firstOrNull { it.startsWith("[$key]") }?.substringAfter("]: [")?.removeSuffix("]").orEmpty()
        val props = sh("getprop | grep -E 'ro.build.display.id|ro.build.date]|ro.build.version.security_patch|ro.build.version.release]'")
        val updaters = sh("pm list packages").lines()
            .map { it.removePrefix("package:").trim() }
            .filter { Regex("upgrade|update|ota|fota|systemupdate", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .filter { !it.contains("google.android.gms") }
        return FirmwareInfo(
            version = prop(props, "ro.build.display.id"),
            buildDate = prop(props, "ro.build.date"),
            securityPatch = prop(props, "ro.build.version.security_patch"),
            android = prop(props, "ro.build.version.release"),
            updaters = updaters,
        )
    }

    /**
     * Открывает на экране ТВ проверку обновлений: стандартный экран Android,
     * а если его нет — программу обновления производителя.
     */
    suspend fun openUpdater(updaters: List<String>): String {
        val std = sh("am start -a android.settings.SYSTEM_UPDATE_SETTINGS")
        if (!looksFailed(std) && !std.contains("unable to resolve", true)) {
            return "На экране ТВ открыт раздел обновления системы. Нажмите там «Проверить обновления» пультом."
        }
        for (pkg in updaters) {
            val act = sh(
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg",
            ).lines().map { it.trim() }.lastOrNull { it.contains('/') }
            if (act != null) {
                val r = sh("am start -n $act")
                if (!looksFailed(r)) return "На экране ТВ открыта программа обновления ($pkg). Проверьте обновления пультом."
            }
        }
        return "Не нашла экран обновления. Проверьте вручную: Настройки → Система (или «Об устройстве») → Обновление ПО."
    }

    private var lastPoke = 0L

    /**
     * Не даёт ТВ уснуть во время долгих операций: без пульта Android TV через 15–20 минут
     * гасит экран и отключает сеть. KEYCODE_WAKEUP считается действием пользователя
     * и сбрасывает таймер, а на включённом ТВ ничего не нажимает.
     */
    suspend fun keepAwake() {
        val now = System.currentTimeMillis()
        if (now - lastPoke < 60_000) return
        lastPoke = now
        runCatching { sh("input keyevent KEYCODE_WAKEUP") }
    }

    private fun looksFailed(out: String): Boolean =
        Regex("Unknown command|Exception|Error:|not found|Permission denied|Failure", RegexOption.IGNORE_CASE).containsMatchIn(out)

    private fun signed(v: Long) = if (v >= 0) "+$v" else "$v"
}
