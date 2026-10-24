# Сборка и выпуск

APK собирается в облаке GitHub Actions — на компьютере ничего ставить не нужно.

## Как это работает

Файл: `.github/workflows/build.yml` («Сборка APK»).

Запускается автоматически при каждом `push` в ветки `main` или `master`, а также вручную
(вкладка **Actions** → «Сборка APK» → **Run workflow**).

| Шаг | Что делает |
|---|---|
| Получить код | `actions/checkout@v5` |
| Установить Java | Temurin 21 (`actions/setup-java@v5`) |
| Установить Gradle | 9.6.0 (`gradle/actions/setup-gradle@v4`) |
| Собрать APK | `gradle assembleDebug --no-daemon`, лог пишется в `build.log` |
| Показать ошибки сборки | Только при неудаче: вытаскивает из лога строки ошибок и публикует их аннотациями по 8 строк |
| Переименовать APK | `app-debug.apk` → `TV-Doctor-<номер сборки>.apk` |
| Сохранить как артефакт | Файл доступен в карточке запуска (Artifacts) |
| Опубликовать в Releases | Тег `build-<номер>`, выпуск «ТВ-Доктор, сборка N», помечается последним |

## Версии

Номер сборки GitHub (`GITHUB_RUN_NUMBER`) становится версией приложения:
`versionCode = N`, `versionName = "0.1.N"`. Поэтому номер в шапке приложения совпадает с номером выпуска.

Неудачные сборки тоже занимают номер — например, сборки 23 нет в Releases, есть 22 и 24.

## Инструменты и библиотеки

| Что | Версия | Где задано |
|---|---|---|
| Android Gradle Plugin | 9.4.0 | `build.gradle.kts` |
| Kotlin Compose plugin | 2.4.10 | `build.gradle.kts` |
| compileSdk / targetSdk / minSdk | 37 / 35 / 26 | `app/build.gradle.kts` |
| Java (байткод) | 17 | `app/build.gradle.kts` |
| Compose BOM | 2025.08.00 | `app/build.gradle.kts` |
| Kadb (ADB-клиент) | 2.1.5 | `app/build.gradle.kts` |
| Coroutines | 1.10.2 | `app/build.gradle.kts` |

Репозитории зависимостей (`settings.gradle.kts`): Google, Maven Central и **JitPack** —
он нужен Kadb для библиотеки сопряжения по коду (spake2-java).

AGP 9 содержит встроенную поддержку Kotlin, отдельный плагин `kotlin-android` не подключается.

**Подпись.** Используется постоянный ключ `app/debug.keystore` (пароль `android`). Если его поменять,
новая версия не установится поверх старой — придётся удалить приложение (с потерей сохранённого адреса и истории).

## Где взять APK

- С телефона: репозиторий → **Releases** → последний выпуск → `TV-Doctor-N.apk`.
- Или: **Actions** → нужный запуск → **Artifacts**.

## Как посмотреть ошибку сборки

1. **Actions** → красный запуск → шаг «Показать ошибки сборки».
2. Ошибки также видны аннотациями в сводке запуска.

Через командную строку (если установлен `gh`):

```
gh api repos/burevestnik-fc/tv-doctor/actions/runs?per_page=1 --jq '.workflow_runs[0] | "\(.run_number) \(.conclusion) \(.id)"'
gh api repos/burevestnik-fc/tv-doctor/check-runs/<job_id>/annotations --jq '.[].message'
```

`job_id` — из `gh api repos/burevestnik-fc/tv-doctor/actions/runs/<run_id>/jobs --jq '.jobs[0].id'`.

## Частые ошибки

| Ошибка | Причина | Решение |
|---|---|---|
| `Could not find …` для зависимостей Kadb | Нет репозитория JitPack | `maven("https://jitpack.io")` в `settings.gradle.kts` (сборки 1–5) |
| Ошибки проверки метаданных AAR (нужен более новый compileSdk или AGP) | Kadb 2.1.5 требует новые инструменты | AGP 9.4.0, compileSdk 37, Compose plugin 2.4.10 (исправлено в сборке 6) |
| `Unresolved reference '<русское слово>'`, `Expecting '"'` | В строке Kotlin настоящий перенос строки вместо `\n` | Заменить перенос на `\n` (сборка 23) |
| `Unresolved reference` после переименования | Функция переименована не везде | `grep` по старому имени |
| `'when' expression must be exhaustive` | Добавлена вкладка или действие, но не обработано в `when` | Добавить ветку |

## Локальная сборка (необязательно)

Нужны JDK 21 и Android SDK с платформой 37:

```
gradle assembleDebug
```

Готовый файл: `app/build/outputs/apk/debug/app-debug.apk`.
