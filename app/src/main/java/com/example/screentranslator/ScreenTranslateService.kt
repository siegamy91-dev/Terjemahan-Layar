package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenTranslateService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var button: TextView? = null
    private var overlay: FrameLayout? = null
    private var busy = false

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val langId = LanguageIdentification.getClient()
    private val translators = HashMap<String, Translator>()
    private val target = TranslateLanguage.INDONESIAN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || projection != null) return START_NOT_STICKY
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }

        @Suppress("DEPRECATION")
        val dm = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "screen-translate", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )
        showButton()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("st", "Screen Translator", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "st")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Screen Translator aktif")
            .setContentText("Tap tombol T untuk menerjemahkan. Tekan lama untuk berhenti.")
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ---------- Tombol melayang ----------
    private fun showButton() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }

        button = TextView(this).apply {
            text = "T"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1A73E8.toInt())
            setOnClickListener { translateScreen() }
            setOnLongClickListener { stopSelf(); true }
        }
        wm.addView(button, lp)
    }

    // ---------- Tangkap -> OCR -> Terjemah ----------
    private fun translateScreen() {
        if (busy) return
        if (overlay != null) { removeOverlay(); return } // tap lagi = tutup hasil
        busy = true
        button?.visibility = View.INVISIBLE
        handler.postDelayed({ grabFrame() }, 300) // beri waktu tombol hilang dari layar
    }

    private fun grabFrame() {
        val img = reader?.acquireLatestImage()
        button?.visibility = View.VISIBLE
        if (img == null) { busy = false; toast("Gagal menangkap layar, coba lagi"); return }

        val plane = img.planes[0]
        val w = plane.rowStride / plane.pixelStride
        val bmp = Bitmap.createBitmap(w, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        val screen = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        img.close()

        recognizer.process(InputImage.fromBitmap(screen, 0))
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.filter { it.boundingBox != null }
                if (blocks.isEmpty()) { busy = false; toast("Teks tidak ditemukan"); return@addOnSuccessListener }
                prepareOverlay()
                var pending = blocks.size
                blocks.forEach { b ->
                    translate(b.text) { translated ->
                        addLabel(translated, b.boundingBox!!, b.lines.size)
                        if (--pending == 0) busy = false
                    }
                }
            }
            .addOnFailureListener { busy = false; toast("OCR gagal") }
    }

    private fun translate(text: String, done: (String) -> Unit) {
        langId.identifyLanguage(text)
            .addOnSuccessListener { code ->
                val src = if (code == "und") null else TranslateLanguage.fromLanguageTag(code)
                if (src == null || src == target) { done(text); return@addOnSuccessListener }
                val tr = translators.getOrPut(src) {
                    Translation.getClient(
                        TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build()
                    )
                }
                tr.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        tr.translate(text)
                            .addOnSuccessListener { done(it) }
                            .addOnFailureListener { done(text) }
                    }
                    .addOnFailureListener { toast("Perlu internet untuk unduh model bahasa"); done(text) }
            }
            .addOnFailureListener { done(text) }
    }

    // ---------- Overlay hasil ----------
    private fun prepareOverlay() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        overlay = FrameLayout(this).apply { setOnClickListener { removeOverlay() } }
        wm.addView(overlay, lp)
        // tombol tetap di atas overlay
        button?.let { wm.removeView(it); showButton() }
    }

    private fun addLabel(text: String, box: android.graphics.Rect, lines: Int) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            setPadding(6, 2, 6, 2)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (box.height().toFloat() / lines * 0.6f).coerceAtLeast(24f))
        }
        overlay?.addView(tv, FrameLayout.LayoutParams(box.width(), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = box.left; topMargin = box.top
        })
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        removeOverlay()
        button?.let { runCatching { wm.removeView(it) } }
        display?.release(); reader?.close(); projection?.stop()
        translators.values.forEach { it.close() }
        super.onDestroy()
    }
}
