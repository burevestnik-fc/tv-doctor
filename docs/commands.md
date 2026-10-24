# Справочник команд

Все команды, которые ТВ-Доктор выполняет на телевизоре. Root ни одной не нужен: всё работает
с правами пользователя `shell`, которые даёт ADB.

Обозначения в колонке «Меняет»:
- **—** только читает;
- **✎** что-то меняет на ТВ (рядом — как отменить).

Каждая команда отправляется в обёртке `( команда ) 2>&1; echo __TVD_END_…__` — так ошибки попадают
в вывод, а по метке в конце приложение понимает, что ответ получен целиком
(подробнее — [architecture.md](architecture.md#adbclient--связь-с-тв)).

---

## Подключение

| Команда | Зачем | Меняет |
|---|---|---|
| `echo tvdoctor_ok` | Проверка, что соединение живое и ТВ отвечает | — |
| сервис `reboot:` или `svc power reboot` | Перезагрузка ТВ («Перезагрузить и измерить») | ✎ перезагрузка |
| `getprop sys.boot_completed` | Раз в секунду после перезагрузки: `1` — система загрузилась | — |

Сопряжение по коду и поиск ТВ в сети (mDNS) идут не через команды, а средствами библиотеки Kadb
и Android на телефоне.

---

## Обзор («Обновить»)

| Команда | Зачем | Меняет |
|---|---|---|
| `getprop` | Все свойства системы: модель, прошивка, платформа, времена старта служб | — |
| `cat /proc/meminfo` | Оперативная память и swap | — |
| `df -k /data` | Встроенная память: всего, занято, свободно | — |
| `cat /proc/loadavg` | Средняя нагрузка (системная) | — |
| `nproc` (запасной: `grep -c ^processor /proc/cpuinfo`) | Число ядер | — |
| `cat /proc/uptime` | Сколько ТВ работает с включения | — |
| `for z in /sys/class/thermal/thermal_zone*; do …` | Датчики температуры | — |
| чтение `life_time` и `pre_eol_info` в `/sys/class/mmc_host`, `/sys/block/mmcblk0`, `/sys/bus/mmc` | Показатели износа eMMC (на многих прошивках закрыты) | — |
| `dumpsys android.hardware.health.IHealth/default \| grep -iE 'eol\|life\|storage'` | Износ памяти через системную службу здоровья | — |
| `dumpsys diskstats \| head -n 4` | Задержка записи по замеру Android | — |
| `pm list packages -3` | Сторонние приложения | — |
| `cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED` (и `LOCKED_BOOT_COMPLETED`) | Кто подписан на автозапуск | — |
| `top -b -n 2 -d 1 -m 20 -o PID,%CPU,RES,NAME` (запасной: без `-o`) | Кто занимает процессор и общий простой процессора | — |
| `ps -A -o STAT \| grep -c '^D'` | Сколько процессов в состоянии D — вычитается из нагрузки | — |
| `cmd appops get <пакет> RUN_ANY_IN_BACKGROUND` (циклом) | Кому запрещён фон | — |
| `settings get global window_animation_scale` (и `transition_…`, `animator_duration_…`) | Скорость анимаций | — |

### Тест скорости памяти

| Команда | Зачем | Меняет |
|---|---|---|
| `dd if=/dev/zero of=/data/local/tmp/tvd_seq bs=1048576 count=64 conv=fsync` | Последовательная запись 64 МБ, время меряет телефон | ✎ временный файл, сразу удаляется |
| цикл из 100 × `dd … of=/data/local/tmp/tvd_small bs=4096 count=1 conv=fsync` | Мелкая запись с принудительным сохранением — самая чувствительная к состоянию eMMC | ✎ временный файл, сразу удаляется |
| `rm -f /data/local/tmp/tvd_seq /data/local/tmp/tvd_small` | Уборка | ✎ |

---

## Загрузка

| Команда | Зачем | Меняет |
|---|---|---|
| `logcat -G 16M; logcat -g` | Увеличить журнал до 16 МБ, чтобы начало загрузки не вытеснилось | ✎ размер журнала (безвредно; вернуть: «Для разработчиков» → «Размер буфера журнала») |
| `logcat -b events -d -v monotonic \| grep -E 'boot_progress\|bootanim\|boot_animation\|am_proc_start\|boot_completed'` | Метки этапов загрузки и запуски процессов | — |
| `getprop` (`ro.boottime.*`) | Когда стартовала каждая служба init | — |
| `ps -A -o PID,TIME,RSS,NAME` | Процессорное время с включения | — |
| `dmesg \| tail -n 3000` | Журнал ядра: ошибки памяти, ФС, перегрев (часто закрыт без root) | — |
| `top …` × 8 с паузой 3 с | Нагрузка в первую минуту после загрузки | — |

---

## Приложения

| Команда | Зачем | Меняет |
|---|---|---|
| `pm list packages`, `-3`, `-d` | Все / сторонние / отключённые | — |
| `pm list packages -u` | Включая удалённые для пользователя | — |
| `dumpsys diskstats` | Размер приложения, данных и кэша | — |
| `dumpsys meminfo` | Сколько ОЗУ занимает каждое приложение | — |
| `dumpsys usagestats \| grep -oE 'package=… lastTimeUsed=…'` | Когда приложение последний раз открывали | — |
| `cmd appops set <пакет> RUN_ANY_IN_BACKGROUND ignore; cmd appops set <пакет> RUN_IN_BACKGROUND ignore; am set-standby-bucket <пакет> restricted` | «Запретить фон» | ✎ вернуть: то же с `allow` и `active` («Разрешить фон») |
| `am force-stop <пакет>` | «Остановить» | ✎ приложение просто закроется |
| `pm disable-user --user 0 <пакет>` | «Отключить» | ✎ вернуть: `pm enable <пакет>` («Включить») |
| `pm enable <пакет>` | «Включить» | ✎ |
| `cmd package install-existing <пакет>` | «Вернуть» удалённое для пользователя | ✎ |
| `pm trim-caches 999G` | «Очистить кэш всех» | ✎ удаляет кэш (безвредно) |
| `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER <пакет>` (запасной: `LAUNCHER`) | Найти экран запуска для замера | — |
| `am start -W -n <экран>`, затем `input keyevent KEYCODE_HOME` | «Замер запуска»: время холодного запуска | ✎ на экране ТВ откроется приложение, затем главный экран |
| `settings put global window_animation_scale 0.5` (и два других) | «Ускорить анимации (0.5x)» | ✎ вернуть: «Для разработчиков» → «Масштаб анимации» → 1x |

---

## Обслуживание

| Команда | Зачем | Меняет |
|---|---|---|
| `dumpsys mount \| grep -i 'last maint'` | Когда Android сам последний раз делал обслуживание памяти | — |
| `sm idle-maint run` (запасной: `sm fstrim`) | TRIM и плановое обслуживание памяти | ✎ необратимо, но безвредно: данные не трогает |
| `setsid sh -c 'cmd package bg-dexopt-job > /data/local/tmp/tvd_dexopt.log 2>&1; echo $? > /data/local/tmp/tvd_dexopt.done' &` | Оптимизация приложений в фоне на ТВ; переживает обрыв связи | ✎ перекомпиляция приложений (безвредно) |
| `cat /data/local/tmp/tvd_dexopt.done`, `tail -n 5 …log` | Закончилась ли оптимизация и чем | — |
| `pm trim-caches 999G` | Очистка кэша | ✎ |
| `am kill-all` | Закрыть фоновые приложения | ✎ приложения откроются заново как обычно |
| `grep MemAvailable /proc/meminfo`, `df -k /data` | Сколько освободилось | — |
| `find /sdcard/ -type f \( -size +40960 -o -iname '*.apk' \) -exec stat -c '%s\|%Y\|%n' {} +` | Поиск файлов от 20 МБ и APK | — |
| `find /data/local/tmp/ -type f -size +2048 -exec stat …` | Забытые временные файлы отладки от 1 МБ | — |
| `rm -f '<путь>' && [ ! -e '<путь>' ] && echo deleted` | Удаление выбранных файлов. Только в `/sdcard/`, `/storage/emulated/`, `/data/local/tmp/` и никогда в `Android/` | ✎ **необратимо** |
| `getprop \| grep -E 'ro.build.display.id\|ro.build.date]\|ro.build.version.security_patch\|ro.build.version.release]'` | Версия прошивки и патча безопасности | — |
| `pm list packages` (фильтр `upgrade\|update\|ota\|fota`) | Найти программу обновления производителя | — |
| `am start -a android.settings.SYSTEM_UPDATE_SETTINGS` (запасной: экран запуска программы обновления) | Открыть проверку обновлений на ТВ | ✎ открывает экран на ТВ |

---

## Терминал: «Что открывается при включении»

| Команда | Зачем |
|---|---|
| `cat /proc/uptime` | Сколько ТВ работает с перезагрузки |
| `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` | Какое приложение — главный экран |
| `dumpsys activity activities \| grep -E 'topResumedActivity\|mResumedActivity' \| head -n 2` | Что сейчас на экране |
| `logcat -d -b all \| grep -E 'START u0\|Displayed '` | Какие экраны открывались после включения и кто перекрыл главный экран |
| `cmd package query-receivers --components -a android.intent.action.BOOT_COMPLETED` | Автозапуск с точностью до компонента |

Все только читают.

---

## Справочник в терминале

Готовые команды вкладки «Терминал» → «Справочник команд». ✎ — перед запуском приложение спросит подтверждение.

**Устройство**
- Модель и прошивка — `getprop ro.product.manufacturer; getprop ro.product.model; …`
- Время работы — `uptime`
- Экран — `wm size; wm density`
- Процессор — `cat /proc/cpuinfo | grep -iE 'hardware|processor|model name|features' | head -n 12`
- Частоты ядер — `cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq`
- Температура — цикл по `thermal_zone*`

**Память**
- Свободное место — `df -h /data`
- Оперативная память — `head -n 8 /proc/meminfo`
- Кто занимает ОЗУ — `dumpsys meminfo | grep -A 30 'Total PSS by process'`
- Самые большие папки данных — `du -sh /sdcard/* 2>/dev/null | sort -rh | head -n 15`
- ✎ Очистить кэш всех приложений — `pm trim-caches 999G`

**Нагрузка**
- Топ процессов — `top -b -n 1 -m 15`
- Процессорное время — `ps -A -o TIME,RSS,NAME | sort -r | head -n 20`
- Средняя нагрузка — `cat /proc/loadavg`
- Убийства из-за нехватки ОЗУ — `logcat -d -b events | grep -iE 'am_kill|lowmem' | tail -n 20`

**Приложения**
- Сторонние — `pm list packages -3`
- Отключённые — `pm list packages -d`
- Автозапуск — `cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED`
- Что сейчас на экране — `dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 3`
- Домашний экран — `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
- Запрещённые в фоне — `cmd appops query-op RUN_ANY_IN_BACKGROUND ignore`

**Загрузка**
- Этапы загрузки — `logcat -b events -d | grep -E 'boot_progress|bootanim'`
- Экраны после включения — `logcat -d -b all | grep -E 'START u0|Displayed '`
- Запуск системных служб — `getprop | grep boottime`
- Ошибки в журнале — `logcat -d -t 60 '*:E'`
- Сбои приложений — `logcat -d -b crash | tail -n 40`
- Размер журнала — `logcat -g`
- ✎ Увеличить журнал до 16 МБ — `logcat -G 16M; logcat -g`

**Сеть**
- IP-адреса ТВ — `ip -4 addr | grep inet`
- Интернет есть? — `ping -c 3 -W 2 8.8.8.8`
- DNS работает? — `ping -c 3 -W 2 ya.ru`
- Wi-Fi: сигнал и скорость — `dumpsys wifi | grep -E 'mWifiInfo' | head -n 2`
- Активные VPN — `dumpsys connectivity | grep -iE 'VPN' | head -n 10`

**Настройки**
- Скорость анимаций — `settings get global …_scale` × 3
- ✎ Анимации 0.5x / ✎ Анимации 1x — `settings put global …_scale 0.5` / `1`
- ✎ Заставка: выключить / включить — `settings put secure screensaver_enabled 0` / `1`

**Пульт и экран**
- ✎ Кнопка «Домой» — `input keyevent KEYCODE_HOME`
- ✎ Кнопка «Назад» — `input keyevent KEYCODE_BACK`
- ✎ Открыть настройки — `am start -a android.settings.SETTINGS`
- ✎ Открыть «Для разработчиков» — `am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS`
- ✎ Скриншот экрана ТВ — `screencap -p /sdcard/Download/tv_screen.png`
- ✎ Перезагрузить ТВ — `svc power reboot`

---

## Полезные команды для ручной диагностики

Не встроены в приложение, но пригодились при разборе ТВ Hyundai AI Point (см. [findings.md](findings.md)):

| Команда | Зачем |
|---|---|
| `cat /proc/loadavg; echo "Потоков в D: $(ps -A -o STAT \| grep -c '^D')"; top -b -n 1 -m 8 \| tail -n 10` | Проверить, настоящая ли высокая нагрузка |
| `ps -A -o STAT,NAME \| grep "^D"` | Кто именно в состоянии D |
| `dumpsys package <пакет> \| grep -oE "<пакет>/[A-Za-z0-9_.]+"` | Экраны, службы и приёмники внутри пакета |
| `cmd package query-receivers --components -a android.intent.action.BOOT_COMPLETED \| grep -iE "mediatek\|homwee\|gdch"` | Системный автозапуск производителя |
| `logcat -d -b all \| grep -iE "tvcenter\|hotel" \| grep -iE "START\|broadcast\|Start proc"` | Кто открывает экран тюнера при старте |
| `pm list packages \| grep -i anoki \| cut -d: -f2` + `pm disable-user --user 0 $p` | Найти и отключить трекер просмотров |
