package com.example.flashhelper

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private var pendingAutoDevice: UsbDevice? = null

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                val target = pendingAutoDevice
                pendingAutoDevice = null
                if (target != null) autoFlow(dev) else log("Izin diberikan, tapi tidak ada aksi tertunda.")
            } else log("Izin USB ditolak")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Flash Helper"
        ContextCompat.registerReceiver(
            this, permReceiver, IntentFilter(MtkUsb.ACTION_PERM),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(section("FLASH LEWAT OTG"))
        root.addView(TextView(this).apply {
            text = "Colok HP TARGET (yang MATI) ke HP ini lewat kabel OTG sambil menahan " +
                "tombol Volume sesuai model target, lalu tekan tombol di bawah. " +
                "Hasil deteksi & log akan muncul di bawah tombol."
            textSize = 13f
        })
        root.addView(Button(this).apply {
            text = "JALANKAN OTOMATIS (deteksi + handshake + upload DA)"
            setOnClickListener { runAuto() }
        })
        root.addView(Button(this).apply {
            text = "Tulis partisi (terkunci, lihat log)"
            setOnClickListener { writePartitionLocked() }
        })
        logView = TextView(this).apply { textSize = 13f; setTextIsSelectable(true) }
        root.addView(logView)

        root.addView(section("INFO HP INI (bukan HP target OTG)"))
        root.addView(TextView(this).apply {
            text = deviceInfo(); textSize = 14f; setTextIsSelectable(true)
        })
        root.addView(Button(this).apply {
            text = "Salin info HP ini"
            setOnClickListener { copy(deviceInfo()) }
        })

        root.addView(section("PERSIAPAN SEBELUM FLASH"))
        root.addView(TextView(this).apply { text = CHECKLIST; textSize = 14f })
        root.addView(section("MASUK MODE KHUSUS (beda tiap model)"))
        root.addView(TextView(this).apply { text = MODES; textSize = 14f })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        unregisterReceiver(permReceiver)
        super.onDestroy()
    }

    private fun runAuto() {
        logView.text = ""
        log("Mencari HP MediaTek lewat OTG...")
        thread {
            val dev = MtkUsb.find(this)
            if (dev == null) {
                runOnUiThread { log("Tidak ada HP MediaTek terdeteksi. Cek kabel OTG & pastikan HP target dalam mode BROM.") }
                return@thread
            }
            runOnUiThread { log(MtkUsb.describe(dev)) }

            val mgr = getSystemService(Context.USB_SERVICE) as UsbManager
            if (mgr.hasPermission(dev)) {
                autoFlow(dev)
            } else {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(MtkUsb.ACTION_PERM).setPackage(packageName), flags
                )
                pendingAutoDevice = dev
                mgr.requestPermission(dev, pi)
            }
        }
    }

    private fun autoFlow(dev: UsbDevice) {
        thread {
            val logSb = StringBuilder()
            try {
                val da = DaLoader.readAsset(this, "da.bin")
                logSb.appendLine("DA dimuat dari aset: ${da.size} byte")
                val regions = DaLoader.parseRegions(da)
                val addr = regions?.firstOrNull()?.loadAddr ?: DaLoader.DEFAULT_LOAD_ADDR
                val sigLen = regions?.firstOrNull()?.sigLength ?: 0
                logSb.appendLine("Load address dipakai: 0x%06X".format(addr))
                runOnUiThread { log(logSb.toString()) }
                val ok = MtkUsb.runSendDa(this, dev, addr, da, sigLen, logSb)
                runOnUiThread {
                    log(logSb.toString())
                    log(if (ok) "SELESAI: DA aktif di RAM." else "GAGAL di salah satu langkah, lihat log di atas.")
                }
            } catch (e: Exception) {
                logSb.appendLine("Error: ${e.message}")
                runOnUiThread { log(logSb.toString()) }
            }
        }
    }

    private fun writePartitionLocked() {
        log("Fitur tulis partisi belum diaktifkan. Buktikan dulu tombol OTOMATIS sampai " +
            "muncul 'DA aktif di RAM', lalu kirim log-nya supaya fitur ini diaktifkan.")
    }

    private fun deviceInfo(): String = """
        Merek    : ${Build.MANUFACTURER}
        Model    : ${Build.MODEL}
        Kode     : ${Build.DEVICE}
        Chipset  : ${Build.HARDWARE} / ${Build.BOARD}
        Android  : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        Bootloader: ${Build.BOOTLOADER}
        Build    : ${Build.DISPLAY}
    """.trimIndent()

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 16f; setPadding(0, 32, 0, 8)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun copy(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("info", s)); toast("Tersalin")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun log(s: String) { logView.text = "${logView.text}\n$s".trim() }

    companion object {
        const val CHECKLIST = """
1. Backup data penting (flash bisa menghapus semua data).
2. Baterai kedua HP minimal 50%.
3. Pakai firmware yang cocok persis dengan model target.
4. Pakai kabel OTG yang bagus; jangan cabut saat proses.
"""
        const val MODES = """
Recovery : matikan HP, tahan Power + Volume Atas.
Fastboot : matikan HP, tahan Power + Volume Bawah.
BROM (MediaTek): matikan HP, colok USB sambil menahan tombol Volume (kombinasi beda tiap model).
"""
    }
}
