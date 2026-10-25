package com.example.flashhelper

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
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

    private class Session(
        val conn: UsbDeviceConnection,
        val inEp: UsbEndpoint,
        val outEp: UsbEndpoint
    ) {
        fun write(b: ByteArray): Boolean =
            conn.bulkTransfer(outEp, b, b.size, 1000) == b.size

        fun read(n: Int, timeout: Int = 1000): ByteArray? {
            val res = ByteArray(n)
            val tmp = ByteArray(512)
            var got = 0
            while (got < n) {
                val r = conn.bulkTransfer(inEp, tmp, tmp.size, timeout)
                if (r <= 0) return null
                val c = minOf(r, n - got)
                System.arraycopy(tmp, 0, res, got, c)
                got += c
            }
            return res
        }

        fun txrx(tx: Int): Int {
            if (!write(byteArrayOf(tx.toByte()))) return -1
            val r = read(1, 500) ?: return -1
            return r[0].toInt() and 0xFF
        }

        fun echo(cmd: Int): Boolean = txrx(cmd) == cmd

        fun rword(): Int? {
            val b = read(2) ?: return null
            return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        }

        fun rdword(): Long? {
            val b = read(4) ?: return null
            var v = 0L
            for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
            return v
        }

        fun handshake(): Boolean {
            var ok = false
            for (i in 1..30) {
                if (txrx(0xA0) == 0x5F) { ok = true; break }
                Thread.sleep(100)
            }
            if (!ok) return false
            return txrx(0x0A) == 0xF5 && txrx(0x50) == 0xAF && txrx(0x05) == 0xFA
        }

        fun getHwCode(): Int? {
            if (!echo(0xFD)) return null
            val hw = rword() ?: return null
            val st = rword() ?: return null
            return if (st == 0) hw else null
        }

        fun getHwSwVer(): List<Int>? {
            if (!echo(0xFC)) return null
            val sub = rword() ?: return null
            val ver = rword() ?: return null
            val sw = rword() ?: return null
            val st = rword() ?: return null
            return if (st == 0) listOf(sub, ver, sw) else null
        }

        fun sendDa(loadAddr: Long, da: ByteArray, sigLen: Int, log: StringBuilder): Boolean {
            // CMD 0xD7 = SEND_DA
            if (!echo(0xD7)) { log.appendLine("SEND_DA ditolak"); return false }
            fun w32(v: Long) {
                write(byteArrayOf(
                    ((v shr 24) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    (v and 0xFF).toByte()
                ))
            }
            w32(loadAddr)
            w32(da.size.toLong())
            w32(sigLen.toLong())
            val st1 = rword()
            if (st1 != 0) { log.appendLine("SEND_DA setup gagal, status=$st1"); return false }

            var sent = 0
            var checksum = 0
            val chunk = 1024
            while (sent < da.size) {
                val n = minOf(chunk, da.size - sent)
                val part = da.copyOfRange(sent, sent + n)
                if (!write(part)) { log.appendLine("Gagal kirim data di offset $sent"); return false }
                for (b in part) checksum = checksum xor (b.toInt() and 0xFF)
                sent += n
                if (sent % (64 * 1024) < chunk) log.appendLine("Terkirim: $sent / ${da.size} byte")
            }
            val devChecksum = rword()
            log.appendLine("Checksum lokal=0x%02X, perangkat=0x%04X".format(checksum, devChecksum ?: -1))
            val st2 = rword()
            if (st2 != 0) { log.appendLine("SEND_DA selesai dengan status error=$st2"); return false }
            log.appendLine("SEND_DA berhasil")
            return true
        }

        fun jumpDa(addr: Long, log: StringBuilder): Boolean {
            // CMD 0xD5 = JUMP_DA
            if (!echo(0xD5)) { log.appendLine("JUMP_DA ditolak"); return false }
            fun w32(v: Long) {
                write(byteArrayOf(
                    ((v shr 24) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    (v and 0xFF).toByte()
                ))
            }
            w32(addr)
            val st = rword()
            return if (st == 0) { log.appendLine("JUMP_DA berhasil, DA sekarang aktif"); true }
            else { log.appendLine("JUMP_DA gagal, status=$st"); false }
        }

        fun getTargetConfig(): Long? {
            if (!echo(0xD8)) return null
            val cfg = rdword() ?: return null
            val st = rword() ?: return null
            return if (st == 0) cfg else null
        }
    }

    fun runSendDa(
        ctx: Context, dev: UsbDevice, loadAddr: Long, da: ByteArray, sigLen: Int, log: StringBuilder
    ): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(dev) ?: run { log.appendLine("Gagal buka USB"); return false }
        try {
            var s: Session? = null
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
                    if (!conn.claimInterface(itf, true)) { log.appendLine("Gagal claim interface"); return false }
                    s = Session(conn, tIn, tOut)
                    break
                }
            }
            if (s == null) { log.appendLine("Endpoint bulk tidak ditemukan"); return false }
            if (!s.handshake()) { log.appendLine("Handshake gagal"); return false }
            log.appendLine("Handshake OK")
            if (!s.sendDa(loadAddr, da, sigLen, log)) return false
            return s.jumpDa(loadAddr, log)
        } finally {
            conn.close()
        }
    }

        /** Jalankan di background thread. */
    fun inspect(ctx: Context, dev: UsbDevice): String {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(dev) ?: return "Gagal membuka perangkat USB"
        val log = StringBuilder()
        try {
            var s: Session? = null
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
                    s = Session(conn, tIn, tOut)
                    break
                }
            }
            if (s == null) return "Endpoint bulk tidak ditemukan"

            if (!s.handshake()) {
                return "Handshake gagal (tidak ada balasan 0x5F). " +
                    "Pastikan HP target mati & masuk mode BROM."
            }
            log.appendLine("Handshake BROM BERHASIL")

            val hw = s.getHwCode()
            log.appendLine(
                if (hw != null) "HW code      : 0x%04X".format(hw) else "HW code      : gagal dibaca"
            )

            val v = s.getHwSwVer()
            if (v != null) {
                log.appendLine("HW subcode   : 0x%04X".format(v[0]))
                log.appendLine("HW version   : 0x%04X".format(v[1]))
                log.appendLine("SW version   : 0x%04X".format(v[2]))
            } else log.appendLine("HW/SW ver    : gagal dibaca")

            val cfg = s.getTargetConfig()
            if (cfg != null) {
                val sbc = (cfg and 1L) != 0L
                val sla = (cfg and 2L) != 0L
                val daa = (cfg and 4L) != 0L
                log.appendLine("Target config: 0x%08X".format(cfg))
                log.appendLine("Secure boot   : " + if (sbc) "AKTIF" else "tidak")
                log.appendLine("SLA           : " + if (sla) "AKTIF" else "tidak")
                log.appendLine("DAA           : " + if (daa) "AKTIF" else "tidak")
                if (sla || daa) {
                    log.appendLine("=> Flash butuh bypass autentikasi (tahap 3).")
                } else {
                    log.appendLine("=> Tanpa autentikasi, Download Agent bisa dimuat langsung.")
                }
            } else log.appendLine("Target config: gagal dibaca")
        } finally {
            conn.close()
        }
        return log.toString()
    }
}
