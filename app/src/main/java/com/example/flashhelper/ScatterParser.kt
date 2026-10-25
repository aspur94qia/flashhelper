package com.example.flashhelper

data class Partition(
    val name: String,
    val fileName: String?,
    val startAddr: Long,
    val physicalSize: Long,
    val isDownload: Boolean
)

object ScatterParser {
    /** Parser ringan untuk MT6765_Android_scatter.txt (format YAML sederhana MTK). */
    fun parse(text: String): List<Partition> {
        val parts = mutableListOf<Partition>()
        var name: String? = null
        var fileName: String? = null
        var startAddr = 0L
        var physSize = 0L
        var isDownload = false

        fun flush() {
            if (name != null) {
                parts.add(Partition(name!!, fileName, startAddr, physSize, isDownload))
            }
            name = null; fileName = null; startAddr = 0; physSize = 0; isDownload = false
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("- partition_index:")) flush()
            when {
                line.startsWith("partition_name:") -> name = line.substringAfter(":").trim()
                line.startsWith("file_name:") -> {
                    val v = line.substringAfter(":").trim()
                    fileName = if (v.isEmpty() || v == "NONE") null else v
                }
                line.startsWith("linear_start_addr:") ->
                    startAddr = line.substringAfter(":").trim().removePrefix("0x")
                        .toLongOrNull(16) ?: 0
                line.startsWith("physical_start_addr:") -> {
                    // beberapa format pakai physical_start_addr, bukan linear
                    if (startAddr == 0L) {
                        startAddr = line.substringAfter(":").trim().removePrefix("0x")
                            .toLongOrNull(16) ?: 0
                    }
                }
                line.startsWith("partition_size:") ->
                    physSize = line.substringAfter(":").trim().removePrefix("0x")
                        .toLongOrNull(16) ?: 0
                line.startsWith("is_download:") ->
                    isDownload = line.substringAfter(":").trim().equals("true", true)
            }
        }
        flush()
        return parts.filter { it.isDownload }
    }
}
