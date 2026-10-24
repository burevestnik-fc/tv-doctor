package ru.tvdoctor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import ru.tvdoctor.ui.App
import ru.tvdoctor.ui.AppTheme
import ru.tvdoctor.ui.Exporter
import ru.tvdoctor.ui.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), Exporter {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App(vm = vm, exporter = this)
            }
        }
    }

    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        return "tv_doctor_$stamp.txt"
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Только выводы — текстом в любой мессенджер. */
    override fun shareFindings() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ТВ-Доктор: выводы")
            putExtra(Intent.EXTRA_TEXT, vm.buildFindings())
        }
        startActivity(Intent.createChooser(send, "Поделиться выводами"))
    }

    /** Полный отчёт с сырыми данными — файлом (в Telegram, почту и т.п.). */
    override fun shareReport() {
        try {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val file = File(dir, fileName())
            file.writeText(vm.buildReport(includeRaw = true))
            val uri = FileProvider.getUriForFile(this, "ru.tvdoctor.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Отчёт ТВ-Доктор")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Отправить отчёт"))
        } catch (e: Exception) {
            toast("Не удалось создать отчёт: ${e.message}")
        }
    }

    /** Полный отчёт — файлом в папку «Загрузки» телефона. */
    override fun saveReport() {
        val name = fileName()
        val text = vm.buildReport(includeRaw = true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TV-Doctor")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("система не дала создать файл")
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    ?: error("не удалось открыть файл для записи")
                toast("Сохранено: Загрузки/TV-Doctor/$name")
            } else {
                val dir = File(getExternalFilesDir(null), "reports").apply { mkdirs() }
                val file = File(dir, name).apply { writeText(text) }
                toast("Сохранено: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            toast("Не удалось сохранить: ${e.message}")
        }
    }

    /** Краткий отчёт без сырых данных — в буфер обмена. */
    override fun copySummary() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ТВ-Доктор", vm.buildReport(includeRaw = false)))
        toast("Краткий отчёт скопирован")
    }

    /** Весь вывод терминала — в буфер обмена. */
    override fun copyTerminal() {
        val text = vm.terminalText()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ТВ-Доктор: терминал", text))
        toast("Скопировано строк: ${text.lines().size}")
    }

    /** Весь вывод терминала — файлом (не обрезается ни экраном, ни мессенджером). */
    override fun shareTerminal() {
        try {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val file = File(dir, "tv_doctor_terminal_$stamp.txt")
            file.writeText(vm.terminalText())
            val uri = FileProvider.getUriForFile(this, "ru.tvdoctor.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ТВ-Доктор: вывод терминала")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Отправить вывод"))
        } catch (e: Exception) {
            toast("Не удалось создать файл: ${e.message}")
        }
    }
}
