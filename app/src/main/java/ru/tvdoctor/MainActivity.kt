package ru.tvdoctor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import ru.tvdoctor.ui.App
import ru.tvdoctor.ui.AppTheme
import ru.tvdoctor.ui.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App(
                    vm = vm,
                    onShareReport = { shareReport() },
                    onCopySummary = { copySummary() },
                )
            }
        }
    }

    /** Полный отчёт с сырыми данными — файлом (в Telegram, почту и т.п.). */
    private fun shareReport() {
        try {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val file = File(dir, "tv_doctor_$stamp.txt")
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
            Toast.makeText(this, "Не удалось создать отчёт: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Краткий отчёт без сырых данных — в буфер обмена. */
    private fun copySummary() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ТВ-Доктор", vm.buildReport(includeRaw = false)))
        Toast.makeText(this, "Краткий отчёт скопирован", Toast.LENGTH_SHORT).show()
    }
}
