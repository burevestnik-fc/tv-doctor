package ru.tvdoctor.diag

/** Насколько безопасно трогать приложение. */
enum class Verdict(val text: String) {
    DISABLE_OK("Можно отключить, если не пользуетесь"),
    BG_ONLY("Пользуетесь — запретите только фон, не отключайте"),
    KEEP("Не трогать: перестанет работать важная функция"),
}

data class AppNote(val name: String, val about: String, val verdict: Verdict)

/**
 * Справочник известных приложений: понятное название, что делает, можно ли отключать.
 * Для неизвестных — оценка по виду пакета.
 */
object KnownApps {

    private val MAP: Map<String, AppNote> = mapOf(
        // Видео и онлайн-кинотеатры
        "org.videolan.vlc" to AppNote("VLC", "Видеоплеер: файлы с флешки, из сети, IPTV-плейлисты. В фоне работать не нужно.", Verdict.BG_ONLY),
        "com.mxtech.videoplayer.ad" to AppNote("MX Player", "Видеоплеер для файлов. В фоне работать не нужно.", Verdict.BG_ONLY),
        "com.kinopub" to AppNote("Кинопаб", "Онлайн-кинотеатр (фильмы и сериалы). В фоне работать не нужно.", Verdict.BG_ONLY),
        "ru.kinopoisk.tv" to AppNote("Кинопоиск", "Онлайн-кинотеатр Яндекса. Заметно держит память в фоне — запрет фона особенно полезен.", Verdict.BG_ONLY),
        "com.vk.tv" to AppNote("VK Видео", "Видео ВКонтакте для ТВ. В фоне работать не нужно.", Verdict.BG_ONLY),
        "ru.ok.androidtv" to AppNote("ОК Видео", "Видео Одноклассников для ТВ. В фоне работать не нужно.", Verdict.BG_ONLY),
        "ru.rutube.app" to AppNote("RUTUBE", "Видеохостинг. В фоне работать не нужно.", Verdict.BG_ONLY),
        "watch.kino" to AppNote("Онлайн-кинотеатр", "Стороннее приложение для просмотра фильмов. В фоне работать не нужно.", Verdict.BG_ONLY),
        "ru.ivi.client" to AppNote("Иви", "Онлайн-кинотеатр. В фоне работать не нужно.", Verdict.BG_ONLY),
        "com.google.android.youtube.tv" to AppNote("YouTube", "YouTube для ТВ. Отключать не стоит, если смотрите; фон можно запретить.", Verdict.BG_ONLY),
        "com.liskovsoft.smarttubetv" to AppNote("SmartTube", "Сторонний клиент YouTube без рекламы. В фоне работать не нужно.", Verdict.BG_ONLY),
        "com.liskovsoft.smarttubetv.beta" to AppNote("SmartTube (бета)", "Сторонний клиент YouTube без рекламы. В фоне работать не нужно.", Verdict.BG_ONLY),
        "com.plexapp.android" to AppNote("Plex", "Медиасервер и плеер. В фоне работать не нужно.", Verdict.BG_ONLY),

        // Закрытые в РФ / ненужные сервисы
        "com.google.android.videos" to AppNote("Google Play Фильмы", "Магазин фильмов Google, в России не работает. После отключения на главном экране может опустеть раздел покупок.", Verdict.DISABLE_OK),
        "com.google.android.music" to AppNote("Google Play Музыка", "Сервис закрыт Google.", Verdict.DISABLE_OK),
        "com.google.android.play.games" to AppNote("Google Play Игры", "Профиль и достижения в играх. На ТВ почти не нужен.", Verdict.DISABLE_OK),
        "com.netflix.ninja" to AppNote("Netflix", "Не работает в России.", Verdict.DISABLE_OK),
        "com.amazon.amazonvideo.livingroom" to AppNote("Prime Video", "Не работает в России.", Verdict.DISABLE_OK),
        "com.disney.disneyplus" to AppNote("Disney+", "Не работает в России.", Verdict.DISABLE_OK),
        "com.spotify.tv.android" to AppNote("Spotify", "Не работает в России.", Verdict.DISABLE_OK),
        "com.hbo.hbonow" to AppNote("HBO", "Не работает в России.", Verdict.DISABLE_OK),

        // Игры и детское
        "com.FDGEntertainment.redball4.gp" to AppNote("Red Ball 4", "Игра-платформер. В фоне работать не нужно; если не играете — можно отключить.", Verdict.DISABLE_OK),
        "com.astepanov.mobile.mathforkids" to AppNote("Математика для детей", "Обучающая игра. В фоне работать не нужно; если не пользуетесь — можно отключить.", Verdict.DISABLE_OK),

        // Утилиты
        "com.cxinventor.file.explorer" to AppNote("File Explorer", "Файловый менеджер: флешки, папки, установка APK. В фоне работать не нужно.", Verdict.BG_ONLY),

        // VPN — ломается при запрете фона
        "com.wireguard.android" to AppNote("WireGuard", "VPN. Работает именно в фоне: запрет фона или отключение выключат VPN.", Verdict.KEEP),
    )

    fun note(pkg: String): AppNote = MAP[pkg] ?: guess(pkg)

    private fun guess(pkg: String): AppNote {
        val p = pkg.lowercase()
        return when {
            listOf("vpn", "wireguard", "proxy", "v2ray", "outline", "amnezia").any { it in p } ->
                AppNote("VPN", "Похоже на VPN или прокси. Работает в фоне: запрет фона выключит его.", Verdict.KEEP)
            "launcher" in p || "home" in p ->
                AppNote("Главный экран", "Похоже на сторонний главный экран (лаунчер). Отключение может оставить ТВ без меню.", Verdict.KEEP)
            "keyboard" in p || "inputmethod" in p || ".ime" in p ->
                AppNote("Клавиатура", "Похоже на экранную клавиатуру. Без неё не получится вводить текст.", Verdict.KEEP)
            "game" in p || "games" in p ->
                AppNote("Игра", "Похоже на игру. В фоне работать не нужно.", Verdict.DISABLE_OK)
            else ->
                AppNote("Стороннее приложение", "Установлено поверх прошивки, сама система от него не зависит. Решайте по тому, пользуетесь ли им.", Verdict.BG_ONLY)
        }
    }
}
