package ru.tvdoctor.ui

/** Подготовка команд и очистка вывода для вкладки «Терминал». */
object TerminalText {

    /** Сколько символов держим в памяти (полный вывод для копирования и файла). */
    const val MAX_CHARS = 3_000_000

    /** Сколько строк показываем на экране — больше телефон рисует медленно. */
    const val MAX_SCREEN_LINES = 5_000

    private val CSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val OSC = Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
    private val ESC_OTHER = Regex("\u001B[@-Z\\\\-_]?")
    private val CONTROL = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    /**
     * Убирает цветовые и управляющие коды терминала (их выдаёт, например, top без -b),
     * приводит переводы строк к одному виду.
     */
    fun clean(text: String): String = text
        .replace(OSC, "")
        .replace(CSI, "")
        .replace(ESC_OTHER, "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(CONTROL, "")

    /**
     * top без -b рисует «живую» таблицу с управляющими кодами, а без -n работает бесконечно.
     * Добавляем недостающие ключи, остальную команду не трогаем.
     */
    fun prepare(cmd: String): String {
        val m = Regex("""^(\s*)top(?=\s|$)""").find(cmd) ?: return cmd
        val rest = cmd.substring(m.range.last + 1)
        val firstPart = rest.split('|', ';', '&').first()
        val hasB = Regex("""(^|\s)-b(\s|$)""").containsMatchIn(firstPart)
        val hasN = Regex("""(^|\s)-n(\s|\d|$)""").containsMatchIn(firstPart)
        if (hasB && hasN) return cmd
        val add = buildString {
            if (!hasB) append(" -b")
            if (!hasN) append(" -n 1")
        }
        return m.groupValues[1] + "top" + add + rest
    }

    /** Ограничение по памяти: огромный вывод обрезаем с явной пометкой. */
    fun cap(text: String): String =
        if (text.length <= MAX_CHARS) text
        else text.take(MAX_CHARS) + "\n… вывод больше ${MAX_CHARS / 1_000_000} млн символов, дальше обрезано. " +
            "Сузьте команду через grep или head.\n"
}

/** Разбор журнала: какие экраны открывались (строки «START u0 {… cmp=…}»). */
object StartupLog {

    data class Start(val time: String, val component: String) {
        val pkg: String get() = component.substringBefore('/')
    }

    private val TIME = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d)""")
    private val CMP = Regex("""START u0 \{[^}]*cmp=([^\s}]+)""")

    fun parse(log: String): List<Start> {
        val out = ArrayList<Start>()
        for (line in log.lines()) {
            val cmp = CMP.find(line)?.groupValues?.get(1) ?: continue
            val time = TIME.find(line)?.groupValues?.get(1) ?: "--:--:--"
            // Подряд идущие повторы одного экрана (приложение «долбит» запуском) — одной строкой
            if (out.lastOrNull()?.component == cmp) continue
            out += Start(time, cmp)
        }
        return out
    }
}
