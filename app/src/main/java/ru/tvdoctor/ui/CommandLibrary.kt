package ru.tvdoctor.ui

/** Готовая команда для терминала. changes = true — команда что-то меняет на ТВ (нужно подтверждение). */
data class LibCmd(
    val title: String,
    val command: String,
    val description: String,
    val changes: Boolean = false,
)

data class LibCategory(val title: String, val commands: List<LibCmd>)

/** Справочник частых команд. Все работают без root. */
val COMMAND_LIBRARY = listOf(
    LibCategory(
        "Устройство",
        listOf(
            LibCmd(
                "Модель и прошивка",
                "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; " +
                    "getprop ro.build.display.id; getprop ro.board.platform",
                "Производитель, модель, версия Android, прошивка, чип.",
            ),
            LibCmd("Время работы", "uptime", "Сколько ТВ работает с последнего включения и средняя нагрузка."),
            LibCmd("Экран", "wm size; wm density", "Разрешение и плотность интерфейса."),
            LibCmd("Процессор", "cat /proc/cpuinfo | grep -iE 'hardware|processor|model name|features' | head -n 12", "Ядра и модель процессора."),
            LibCmd("Частоты ядер", "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq", "Текущая частота каждого ядра, кГц. Низкие под нагрузкой — перегрев или энергосбережение."),
            LibCmd("Температура", "for z in /sys/class/thermal/thermal_zone*; do echo \$(cat \$z/type)=\$(cat \$z/temp); done", "Датчики температуры (обычно в тысячных градуса)."),
        ),
    ),
    LibCategory(
        "Память",
        listOf(
            LibCmd("Свободное место", "df -h /data", "Сколько занято и свободно во встроенной памяти."),
            LibCmd("Оперативная память", "head -n 8 /proc/meminfo", "Всего, свободно, доступно, сжатая память (Swap)."),
            LibCmd(
                "Кто занимает ОЗУ",
                "dumpsys meminfo | grep -A 30 'Total PSS by process'",
                "Процессы по занятой памяти, сверху самые тяжёлые.",
            ),
            LibCmd("Самые большие папки данных", "du -sh /sdcard/* 2>/dev/null | sort -rh | head -n 15", "Что занимает место в общей памяти (загрузки, фильмы и т.п.)."),
            LibCmd("Очистить кэш всех приложений", "pm trim-caches 999G", "Удаляет временные файлы приложений. Безопасно.", changes = true),
        ),
    ),
    LibCategory(
        "Нагрузка",
        listOf(
            LibCmd("Топ процессов", "top -b -n 1 -m 15", "Кто сейчас занимает процессор и память."),
            LibCmd("Процессорное время", "ps -A -o TIME,RSS,NAME | sort -r | head -n 20", "Кто больше всех нагрузил процессор с момента включения."),
            LibCmd("Средняя нагрузка", "cat /proc/loadavg", "За 1, 5 и 15 минут. Норма — не выше числа ядер."),
            LibCmd("Убийства из-за нехватки ОЗУ", "logcat -d -b events | grep -iE 'am_kill|lowmem' | tail -n 20", "Как часто система закрывает приложения, потому что не хватает памяти."),
        ),
    ),
    LibCategory(
        "Приложения",
        listOf(
            LibCmd("Сторонние приложения", "pm list packages -3", "Всё, что установлено поверх прошивки."),
            LibCmd("Отключённые приложения", "pm list packages -d", "Что сейчас отключено (их можно включить на вкладке «Приложения»)."),
            LibCmd("Автозапуск", "cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED", "Кто просыпается при включении ТВ."),
            LibCmd("Что сейчас на экране", "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 3", "Какое приложение открыто прямо сейчас."),
            LibCmd("Домашний экран (лаунчер)", "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME", "Какое приложение система считает главным экраном."),
            LibCmd("Запрещённые в фоне", "cmd appops query-op RUN_ANY_IN_BACKGROUND ignore", "Кому запрещена фоновая работа."),
        ),
    ),
    LibCategory(
        "Загрузка",
        listOf(
            LibCmd("Этапы загрузки", "logcat -b events -d | grep -E 'boot_progress|bootanim'", "Метки времени этапов загрузки (мс от включения)."),
            LibCmd("Экраны после включения", "logcat -d -b all | grep -E 'START u0|Displayed '", "Какие экраны открывались с момента включения и в каком порядке."),
            LibCmd("Запуск системных служб", "getprop | grep boottime", "Когда стартовала каждая служба init (нс от включения)."),
            LibCmd("Ошибки в журнале", "logcat -d -t 60 '*:E'", "Последние 60 ошибок системы и приложений."),
            LibCmd("Сбои приложений", "logcat -d -b crash | tail -n 40", "Последние падения приложений."),
            LibCmd("Размер журнала", "logcat -g", "Сколько хранит журнал. Для анализа загрузки лучше 4–16 МБ."),
            LibCmd("Увеличить журнал до 16 МБ", "logcat -G 16M; logcat -g", "Чтобы начало загрузки не терялось.", changes = true),
        ),
    ),
    LibCategory(
        "Сеть",
        listOf(
            LibCmd("IP-адреса ТВ", "ip -4 addr | grep inet", "Адреса ТВ во всех сетях (Wi-Fi, кабель, VPN)."),
            LibCmd("Интернет есть?", "ping -c 3 -W 2 8.8.8.8", "Проверка связи с интернетом по IP."),
            LibCmd("DNS работает?", "ping -c 3 -W 2 ya.ru", "Если по IP есть связь, а по имени нет — проблема с DNS."),
            LibCmd("Wi-Fi: сигнал и скорость", "dumpsys wifi | grep -E 'mWifiInfo' | head -n 2", "Уровень сигнала (RSSI) и скорость соединения."),
            LibCmd("Активные VPN", "dumpsys connectivity | grep -iE 'VPN' | head -n 10", "Работает ли VPN прямо сейчас."),
        ),
    ),
    LibCategory(
        "Настройки",
        listOf(
            LibCmd("Скорость анимаций", "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale", "Текущие масштабы анимаций (null = 1x)."),
            LibCmd(
                "Анимации 0.5x (быстрее)",
                "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5",
                "Меню и переходы станут отзывчивее.", changes = true,
            ),
            LibCmd(
                "Анимации 1x (как было)",
                "settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1",
                "Вернуть стандартную скорость.", changes = true,
            ),
            LibCmd("Заставка: выключить", "settings put secure screensaver_enabled 0", "ТВ не будет запускать заставку при простое.", changes = true),
            LibCmd("Заставка: включить", "settings put secure screensaver_enabled 1", "Вернуть заставку.", changes = true),
        ),
    ),
    LibCategory(
        "Пульт и экран",
        listOf(
            LibCmd("Кнопка «Домой»", "input keyevent KEYCODE_HOME", "Как нажать «Домой» на пульте.", changes = true),
            LibCmd("Кнопка «Назад»", "input keyevent KEYCODE_BACK", "Как нажать «Назад» на пульте.", changes = true),
            LibCmd("Открыть настройки", "am start -a android.settings.SETTINGS", "Открывает настройки на экране ТВ.", changes = true),
            LibCmd("Открыть «Для разработчиков»", "am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "Сразу в меню разработчика на ТВ.", changes = true),
            LibCmd("Скриншот экрана ТВ", "screencap -p /sdcard/Download/tv_screen.png; ls -l /sdcard/Download/tv_screen.png", "Сохраняется на ТВ в папку Download.", changes = true),
            LibCmd("Перезагрузить ТВ", "svc power reboot", "Обычная перезагрузка.", changes = true),
        ),
    ),
)
