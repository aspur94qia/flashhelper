package com.example.flashhelper

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager

object MtkUsb {
    const val VID_MTK = 0x0E8D
    const val PID_BROM = 0x0003
    const val PID_PRELOADER = 0x2000
    const val ACTION_PERM = "com.example.flashhelper.USB_PERM"

    fun find(ctx: Context): UsbDevice? {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values.firstOrNull { it.vendorId == VID_MTK }
    }

    fun describe(d: UsbDevice): String {
        val mode = when (d.productId) {
            PID_BROM -> "BROM"
            PID_PRELOADER -> "Preloader"
            else -> "tidak dikenal"
        }
        return "MediaTek VID=%04X PID=%04X (mode %s)".format(d.vendorId, d.productId, mode)
    }

    fun handshake(ctx: Context, dev: UsbDevice): String {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(dev) ?: return "Gagal membuka perangkat USB"
        val log = StringBuilder()
        try {
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until dev.interfaceCount) {
                val itf = dev.getInterface(i)
                var tIn: UsbEndpoint? = null
                var tOut: UsbEndpoint? = null
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) tIn = ep else tOut = ep
                    }
                }
                if (tIn != null && tOut != null) {
                    if (!conn.claimInterface(itf, true)) return "Gagal claim interface $i"
                    inEp = tIn; outEp = tOut
                    log.appendLine("Interface $i")
                    break
                }
            }
            if (inEp == null || outEp == null) return "Endpoint bulk tidak ditemukan"

            fun xfer(tx: Int): Int {
                val out = byteArrayOf(tx.toByte())
                if (conn.bulkTransfer(outEp, out, 1, 500) < 0) return -1
                val buf = ByteArray(64)
                val n = conn.bulkTransfer(inEp, buf, buf.size, 500)
                return if (n >= 1) buf[0].toInt() and 0xFF else -1
            }

            var ok = false
            for (attempt in 1..30) {
                if (xfer(0xA0) == 0x5F) { ok = true; break }
                Thread.sleep(100)
            }
            if (!ok) return log.append(
                "Handshake gagal (tidak ada balasan 0x5F). Pastikan HP target mati & masuk mode BROM."
            ).toString()
            for ((tx, expect) in listOf(0x0A to 0xF5, 0x50 to 0xAF, 0x05 to 0xFA)) {
                val r = xfer(tx)
                log.appendLine("TX %02X -> RX %02X".format(tx, r))
                if (r != expect) return log.append("Balasan tidak sesuai (harusnya %02X)".format(expect)).toString()
            }
            log.appendLine("Handshake BROM BERHASIL")
        } finally {
            conn.close()
        }
        return log.toString()
    }
}
