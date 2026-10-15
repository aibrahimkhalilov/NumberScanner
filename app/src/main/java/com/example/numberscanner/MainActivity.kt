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

/** Одна отсканированная марка. photo — ссылка на фото в галерее (может быть пустой). */
data class ScanItem(var code: String, val time: Long, val batch: String, var photo: String = "")

data class Snapshot(val current: String, val batches: List<String>, val items: List<ScanItem>)

class MainActivity : AppCompatActivity() {

    // ---- данные ----
    private val all = mutableListOf<ScanItem>()        // все марки, старые первыми
    private val batches = mutableListOf<String>()      // названия партий
    private var current = "Партия 1"                   // текущая партия
    private var shown = listOf<ScanItem>()             // то, что сейчас в списке
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

    // антиспам для сигнала «дубликат»
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

    /** На Android 9 и старше для записи фото в галерею нужно отдельное разрешение. */
    private fun requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ===================== Сохранение / загрузка =====================

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

    // ===================== Список, поиск, партии =====================

    private fun refresh() {
        val q = searchEdit.text.toString().trim().uppercase()
        if (q.isEmpty()) {
            shown = all.filter { it.batch == current }.reversed()
            display.clear()
            shown.forEach {
                display.add("${it.code}${if (it.photo.isNotEmpty()) "  📷" else ""}\n${dateTimeFmt.format(Date(it.time))}")
            }
            countText.text = "Партия «$current»: ${shown.size} шт.  \vert{}  Всего: ${all.size}"
        } else {
            shown = all.filter { it.code.contains(q) }.reversed()
            display.clear()
            shown.forEach {
                display.add("${it.code}${if (it.photo.isNotEmpty()) "  📷" else ""}\n${dateTimeFmt.format(Date(it.time))}  ·${it.batch}")
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

    // ===================== Меню элемента =====================

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
                // ИЗМЕНЕНО: разрешаем от 1 до 4 букв и любые цифры
                !Regex("[A-Z]{1,4}[0-9]+").matches(v) ->
                    toast("Неверный формат. Пример: AA12345")
                all.any { it !== item && it.code == v } ->
                    toast("Такой номер уже есть
