package com.example.flashhelper

import android.content.Context
import java.io.ByteArrayOutputStream

/**
 * Parser header DA legacy MTK ("all-in-one" style) + pengiriman ke RAM lewat BROM.
 * Format umum: offset 0x8 berisi jumlah region, tiap region 0x8C byte berisi
 * load_address(4), length(4), sig_length(4), nama(64), dst.
 * Kalau parsing region gagal, fallback: kirim seluruh file sebagai 1 blok ke
 * alamat default SRAM MT6765 (0x200000), umum dipakai legacy DA tahap 1.
 */
object DaLoader {

    data class Region(val loadAddr: Long, val length: Int, val sigLength: Int, val name: String)

    fun readAsset(ctx: Context, name: String): ByteArray =
        ctx.assets.open(name).use { it.readBytes() }

    private fun u32(b: ByteArray, off: Int): Long {
        return ((b[off].toLong() and 0xFF)) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    /** Best-effort: cari region table di header. Return null kalau tidak dikenali. */
    fun parseRegions(da: ByteArray): List<Region>? {
        return try {
            if (da.size < 0x20) return null
            val count = u32(da, 0x8).toInt()
            if (count <= 0 || count > 8) return null
            val regions = mutableListOf<Region>()
            var off = 0xC
            for (i in 0 until count) {
                if (off + 0x5C > da.size) return null
                val nameBytes = da.copyOfRange(off, off + 0x40)
                val name = String(nameBytes).substringBefore('\u0000')
                val loadAddr = u32(da, off + 0x40)
                val length = u32(da, off + 0x44).toInt()
                val sigLen = u32(da, off + 0x48).toInt()
                regions.add(Region(loadAddr, length, sigLen, name))
                off += 0x5C
            }
            regions
        } catch (e: Exception) {
            null
        }
    }

    /** Alamat SRAM default untuk upload DA1 di chip MT67xx lama. */
    const val DEFAULT_LOAD_ADDR = 0x200000L
}
