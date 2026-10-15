package com.example.numberscanner

import android.Manifest
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Одна отсканированная марка. photo — ссылка на фото в галерее (может быть пустой). */
data class ScanItem(var code: String, val time: Long, val batch: String, var photo: String = "")

data class Snapshot(val current: String, val batches: List<String>, val items: List<ScanItem>)

class MainActivity : AppCompatActivity() {

    // ---- данные ----
    private val all = mutableListOf<ScanItem>()
    private val batches = mutableListOf<String>()
    private var current = "Партия 1"
    private var shown = listOf<ScanItem>()
    private val display = mutableListOf<String>()

    // ---- интерфейс / камера ----
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var countText: TextView
    private lateinit var searchEdit: EditText
    private lateinit var previewView: PreviewView
    private lateinit var scanFrame: View
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var tone: ToneGenerator? = null
    private var torchOn = false

    private var lastDupCode = ""
    private var lastDupTime = 0L

    private val dateTimeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dayLabelFmt = SimpleDateFormat("dd.MM.yyyy, EEE", Locale.getDefault())

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Без доступа к памяти фото сохраняться не будут")
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
                requestStorageIfNeeded()
            } else toast("Нужен доступ к камере")
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) restoreFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        scanFrame = findViewById(R.id.scanFrame)
        countText = findViewById(R.id.countText)
        searchEdit = findViewById(R.id.searchEdit)
        val listView = findViewById<ListView>(R.id.listView)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, display)
        listView.adapter = adapter
        cameraExecutor = Executors.newSingleThreadExecutor()
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (e: Exception) { null }

        load()
        refresh()

        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateFrame() }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < shown.size) showItemMenu(shown[position])
        }

        findViewById<SeekBar>(R.id.zoomBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    camera?.cameraControl?.setLinearZoom(progress / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        findViewById<Button>(R.id.torchButton).setOnClickListener { b ->
            val cam = camera
            if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
                toast("Фонарик недоступен")
            } else {
                torchOn = !torchOn
                cam.cameraControl.enableTorch(torchOn)
                (b as Button).text = if (torchOn) "🔦 Выкл" else "🔦 Вкл"
            }
        }

        findViewById<Button>(R.id.batchesButton).setOnClickListener { showBatches() }
        findViewById<Button>(R.id.newBatchButton).setOnClickListener { newBatchDialog() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportDialog() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { clearDialog() }
        findViewById<Button>(R.id.moreButton).setOnClickListener { moreDialog() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
            requestStorageIfNeeded()
        } else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun buildJson(): String {
        val root = JSONObject()
        root.put("app", "AKscan")
        root.put("version", 2)
        root.put("current", current)
        root.put("batches", JSONArray(batches as Collection<*>))
        val arr = JSONArray()
        all.forEach {
            arr.put(
                JSONObject().put("c", it.code).put("t", it.time)
                    .put("b", it.batch).put("p", it.photo)
            )
        }
        root.put("items", arr)
        return root.toString()
    }

    private fun parse(s: String): Snapshot? {
        return try {
            val root = JSONObject(s)
            val arr = root.getJSONArray("items")
            val items = mutableListOf<ScanItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(ScanItem(o.getString("c"), o.getLong("t"), o.getString("b"), o.optString("p", "")))
            }
            val bs = mutableListOf<String>()
            val ba = root.optJSONArray("batches")
            if (ba != null) for (i in 0 until ba.length()) bs.add(ba.getString(i))
            Snapshot(root.optString("current", "Партия 1"), bs, items)
        } catch (e: Exception) {
            null
        }
    }

    private fun save() {
        getSharedPreferences("scanner", MODE_PRIVATE).edit()
            .putString("data", buildJson()).apply()
    }

    private fun load() {
        val s = getSharedPreferences("scanner", MODE_PRIVATE).getString("data", null)
        val snap = if (s != null) parse(s) else null
        if (snap != null) {
            current = snap.current
            batches.addAll(snap.batches)
            all.addAll(snap.items)
        }
        normalizeBatches()
    }

    private fun normalizeBatches() {
        if (current !in batches) batches.add(current)
        all.forEach { if (it.batch !in batches) batches.add(it.batch) }
    }

    private fun refresh() {
        val q = searchEdit.text.toString().trim().uppercase()
        if (q.isEmpty()) {
            shown = all.filter { it.batch == current }.reversed()
            display.clear()
            shown.forEach {
                display.add("${it.code}${if (it.photo.isNotEmpty()) "  📷" else ""}\n${dateTimeFmt.format(Date(it.time))}")
            }
            countText.text = "Партия «$current»: ${shown.size} шт.  |  Всего: ${all.size}"
        } else {
            shown = all.filter { it.code.contains(q) }.reversed()
            display.clear()
            shown.forEach {
                display.add("${it.code}${if (it.photo.isNotEmpty()) "  📷" else ""}\n${dateTimeFmt.format(Date(it.time))}  ·  ${it.batch}")
            }
            countText.text = "Поиск «$q»: найдено ${shown.size}"
        }
        adapter.notifyDataSetChanged()
    }

    private fun showBatches() {
        val names = batches.map { b -> "$b  (${all.count { it.batch == b }} шт.)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите партию")
            .setItems(names) { _, which ->
                current = batches[which]
                searchEdit.setText("")
                save()
                refresh()
            }
            .show()
    }

    private fun newBatchDialog() {
        askText("Новая партия", "Партия ${batches.size + 1}") { name ->
            if (name.isNotEmpty()) {
                if (name !in batches) batches.add(name)
                current = name
                searchEdit.setText("")
                save()
                refresh()
            }
        }
    }

    private fun showItemMenu(item: ScanItem) {
        val labels = mutableListOf("Копировать", "Изменить номер", "Удалить")
        if (item.photo.isNotEmpty()) labels.add(0, "Показать фото")
        AlertDialog.Builder(this)
            .setTitle(item.code)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "Показать фото" -> openPhoto(item)
                    "Копировать" -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("number", item.code))
                        toast("Скопировано: ${item.code}")
                    }
                    "Изменить номер" -> editItem(item)
                    "Удалить" -> confirm("Удалить ${item.code}?\n(Фото в галерее останется.)") {
                        all.remove(item)
                        save()
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun openPhoto(item: ScanItem) {
        try {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.photo), "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("Не удалось открыть фото. Оно в галерее, папка AKscan")
        } catch (e: Exception) {
            toast("Фото недоступно (возможно, удалено из галереи)")
        }
    }

    private fun editItem(item: ScanItem) {
        askText("Исправить номер", item.code) { text ->
            val v = text.uppercase()
            when {
                !Regex("[A-Z]{1,4}[0-9]+").matches(v) ->
                    toast("Неверный формат. Пример: AA12345")
                all.any { it !== item && it.code == v } ->
                    toast("Такой номер уже есть в списке")
                else -> {
                    item.code = v
                    save()
                    refresh()
                }
            }
        }
    }

    private fun clearDialog() {
        AlertDialog.Builder(this)
            .setTitle("Очистить")
            .setItems(arrayOf("Текущую партию «$current»", "Всё (все партии)")) { _, which ->
                if (which == 0) {
                    confirm("Удалить все марки партии «$current»?\n(Фото в галерее останутся.)") {
                        all.removeAll { it.batch == current }
                        save()
                        refresh()
                    }
                } else {
                    confirm("Удалить ВСЕ марки во всех партиях?\n(Фото в галерее останутся.)") {
                        all.clear()
                        batches.clear()
                        current = "Партия 1"
                        batches.add(current)
                        save()
                        refresh()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun moreDialog() {
        AlertDialog.Builder(this)
            .setTitle("AKscan")
            .setItems(
                arrayOf(
                    "📊 Статистика",
                    "💾 Сохранить резервную копию",
                    "📥 Восстановить из копии"
                )
            ) { _, which ->
                when (which) {
                    0 -> showStats()
                    1 -> backup()
                    2 -> restoreLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun showStats() {
        val today = dayStart(System.currentTimeMillis())
        val sb = StringBuilder()
        sb.append("Сегодня: ${all.count { it.time >= today }}\n")
        sb.append("Всего: ${all.size}\n\n")
        sb.append("Последние 7 дней:\n")
        for (i in 0 until 7) {
            val from = addDays(today, -i)
            val to = addDays(from, 1)
            val c = all.count { it.time >= from && it.time < to }
            sb.append("${dayLabelFmt.format(Date(from))}: $c\n")
        }
        sb.append("\nПо партиям:\n")
        batches.forEach { b ->
            val total = all.count { it.batch == b }
            val t = all.count { it.batch == b && it.time >= today }
            sb.append("$b: $total  (сегодня $t)\n")
        }
        AlertDialog.Builder(this)
            .setTitle("Статистика")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun backup() {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "AKscan_backup_$stamp.json")
            file.writeText(buildJson(), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Сохранить резервную копию"))
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        }
    }

    private fun restoreFrom(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        val snap = if (text != null) parse(text.removePrefix("\uFEFF")) else null
        if (snap == null) {
            toast("Не удалось прочитать файл копии")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("В копии: ${snap.items.size} марок")
            .setItems(
                arrayOf(
                    "Объединить с текущими данными",
                    "Заменить все данные (текущие будут удалены)"
                )
            ) { _, which ->
                if (which == 0) {
                    var added = 0
                    snap.items.forEach { n ->
                        if (all.none { a -> a.code == n.code }) {
                            all.add(n)
                            added++
                        }
                    }
                    snap.batches.forEach { b -> if (b !in batches) batches.add(b) }
                    all.sortBy { it.time }
                    normalizeBatches()
                    toast("Добавлено марок: $added")
                } else {
                    all.clear()
                    batches.clear()
                    all.addAll(snap.items)
                    batches.addAll(snap.batches)
                    current = snap.current
                    normalizeBatches()
                    toast("Восстановлено марок: ${all.size}")
                }
                save()
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun exportDialog() {
        if (all.isEmpty()) {
            toast("Список пуст")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Экспорт: какие партии?")
            .setItems(arrayOf("Текущая партия «$current»", "Все партии")) { _, which ->
                val base = if (which == 0) all.filter { it.batch == current } else all.toList()
                pickPeriod(base) { list ->
                    if (list.isEmpty()) toast("За выбранный период марок нет") else exportCsv(list)
                }
            }
            .show()
    }

    private fun pickPeriod(base: List<ScanItem>, onResult: (List<ScanItem>) -> Unit) {
        val today = dayStart(System.currentTimeMillis())
        AlertDialog.Builder(this)
            .setTitle("За какой период?")
            .setItems(arrayOf("За всё время", "Сегодня", "Вчера", "Выбрать день", "Выбрать период")) { _, which ->
                when (which) {
                    0 -> onResult(base)
                    1 -> onResult(inRange(base, today, addDays(today, 1)))
                    2 -> onResult(inRange(base, addDays(today, -1), today))
                    3 -> pickDate("Выберите день") { d -> onResult(inRange(base, d, addDays(d, 1))) }
                    4 -> pickDate("Начало периода") { a ->
                        pickDate("Конец периода") { b ->
                            val from = minOf(a, b)
                            val to = addDays(maxOf(a, b), 1)
                            onResult(inRange(base, from, to))
                        }
                    }
                }
            }
            .show()
    }

    private fun inRange(list: List<ScanItem>, from: Long, toExclusive: Long) =
        list.filter { it.time >= from && it.time < toExclusive }

    private fun pickDate(title: String, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance()
        val dlg = DatePickerDialog(
            this,
            { _, y, m, d ->
                val cal = Calendar.getInstance()
                cal.set(y, m, d, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                onPicked(cal.timeInMillis)
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        )
        dlg.setTitle(title)
        dlg.show()
    }

    private fun dayStart(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun addDays(ms: Long, days: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.add(Calendar.DAY_OF_YEAR, days)
        return c.timeInMillis
    }

    private fun csvText(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun exportCsv(list: List<ScanItem>) {
        try {
            val sorted = list.sortedBy { it.time }
            val sb = StringBuilder("\uFEFF") // BOM для кириллицы
            sb.append("sep=,\n") // Инструкция для Excel разделять по запятым
            sb.append("№,Номер,Дата,Время,Партия\n")
            sorted.forEachIndexed { i, it ->
                val d = Date(it.time)
                sb.append("${i + 1},${it.code},${dateFmt.format(d)},${timeFmt.format(d)},${csvText(it.batch)}\n")
            }
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "AKscan_$stamp.csv")
            file.writeText(sb.toString(), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Экспорт CSV"))
        } catch (e: Exception) {
            toast("Ошибка экспорта: ${e.message}")
        }
    }

    private fun onCodeFound(code: String) {
        val now = System.currentTimeMillis()
        val existing = all.firstOrNull { it.code == code }

        if (existing != null) {
            if (code != lastDupCode || now - lastDupTime > 4000) {
                tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
                vibrate(500)
                Toast.makeText(
                    this,
                    "УЖЕ СКАНИРОВАЛСЯ!\n$code\n${dateTimeFmt.format(Date(existing.time))}, ${existing.batch}",
                    Toast.LENGTH_LONG
                ).show()
            }
            lastDupCode = code
            lastDupTime = now
            return
        }

        val item = ScanItem(code, now, current)
        all.add(item)
        lastDupCode = code
        lastDupTime = now
        save()
        refresh()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        vibrate(80)
        takePhoto(item)
    }

    private fun takePhoto(item: ScanItem) {
        val capture = imageCapture ?: return
        val name = "AKscan_${item.code}_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(item.time))
        val executor = ContextCompat.getMainExecutor(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AKscan")
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ).build()
            capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    item.photo = output.savedUri?.toString() ?: ""
                    save()
                    refresh()
                }
                override fun onError(exception: ImageCaptureException) {}
            })
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "AKscan"
            )
            dir.mkdirs()
            val file = File(dir, "$name.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    MediaScannerConnection.scanFile(
                        this@MainActivity, arrayOf(file.absolutePath), arrayOf("image/jpeg")
                    ) { _, uri ->
                        runOnUiThread {
                            item.photo = uri?.toString() ?: ""
                            save()
                            refresh()
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) {}
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(ms)
            }
        } catch (e: Exception) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun confirm(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Да") { _, _ -> onYes() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            if (title.contains("номер", ignoreCase = true)) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(InputFilter.AllCaps())
            }
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateFrame() {
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val scale = maxOf(vw / 9f, vh / 16f)
        val w = minOf(vw, ScanConfig.CROP_W * 9f * scale).toInt()
        val h = minOf(vh, ScanConfig.CROP_H * 16f * scale).toInt()
        val lp = scanFrame.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            scanFrame.layoutParams = lp
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val aspect = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .build()
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                ).build()
            val captureSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, CodeAnalyzer { onCodeFound(it) })

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(captureSelector)
                .build()
            imageCapture = capture

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture
            )

            previewView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                    true
                } else {
                    false
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
