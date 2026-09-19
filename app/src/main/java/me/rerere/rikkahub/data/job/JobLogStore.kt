package me.rerere.rikkahub.data.job

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 任务日志的文件仓库。
 *
 * 设计要点:
 * - 不进 workspace 文件区（避免污染用户文件、也不受 RikkaHubApp 启动时清理 temp 的影响）;
 * - 每个流（stdout/stderr/screen）一个文件, 超过 [MAX_STREAM_BYTES] 轮转为 `.1` 并继续写,
 *   因此单流最多占 2x 空间, 且总量有上限;
 * - 读取用"逻辑偏移"（rotated + current 拼接后的字节位置）, 配合 job_logs 的增量游标。
 */
class JobLogStore(private val baseDir: File) {

    fun streamFile(root: String, jobId: String, stream: JobLogStream): File {
        val dir = File(baseDir, root).apply { mkdirs() }
        return File(dir, "${jobId}.${stream.name.lowercase()}.log")
    }

    fun openAppend(file: File): OutputStream {
        file.parentFile?.mkdirs()
        return RotatingOutputStream(file, MAX_STREAM_BYTES)
    }

    fun appendText(file: File, text: String) {
        if (text.isEmpty()) return
        runCatching { openAppend(file).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
    }

    fun logicalSize(file: File): Long = rotatedOf(file).length() + (if (file.isFile) file.length() else 0L)

    /** 从逻辑偏移 [since] 起最多读 [maxBytes]。 */
    fun read(file: File, since: Long, maxBytes: Int): ByteArray {
        val rotated = rotatedOf(file)
        val rotatedLength = rotated.length()
        val currentLength = if (file.isFile) file.length() else 0L
        val total = rotatedLength + currentLength
        val start = since.coerceIn(0L, total)
        val end = minOf(total, start + maxBytes.toLong())
        if (end <= start) return ByteArray(0)
        val result = ByteArray((end - start).toInt())
        var written = 0
        // 先读轮转文件（旧数据在前）
        if (start < rotatedLength) {
            val readEnd = minOf(end, rotatedLength)
            written += readRange(rotated, start, (readEnd - start).toInt(), result, written)
        }
        if (end > rotatedLength) {
            val currentStart = (start - rotatedLength).coerceAtLeast(0L)
            val length = (end - maxOf(start, rotatedLength)).toInt()
            written += readRange(file, currentStart, length, result, written)
        }
        return if (written == result.size) result else result.copyOf(written)
    }

    fun readChunk(file: File, since: Long, maxBytes: Int): JobLogChunk {
        val total = logicalSize(file)
        val start = since.coerceAtLeast(0L)
        if (start > total) {
            // 游标超出（例如日志被清理过）: 退化为返回尾部
            val tailStart = (total - maxBytes).coerceAtLeast(0L)
            val bytes = read(file, tailStart, maxBytes)
            return JobLogChunk(
                text = decode(bytes),
                from = tailStart,
                to = tailStart + bytes.size,
                totalBytes = total,
                cursorInvalid = true,
                binary = bytes.any { it == 0.toByte() },
            )
        }
        val bytes = read(file, start, maxBytes)
        return JobLogChunk(
            text = decode(bytes),
            from = start,
            to = start + bytes.size,
            totalBytes = total,
            binary = bytes.any { it == 0.toByte() },
        )
    }

    fun tailText(file: File, maxBytes: Int): String {
        val total = logicalSize(file)
        val start = (total - maxBytes).coerceAtLeast(0L)
        return decode(read(file, start, maxBytes))
    }

    fun delete(root: String, jobId: String) {
        JobLogStream.entries.forEach { stream ->
            val file = streamFile(root, jobId, stream)
            runCatching { file.delete() }
            runCatching { rotatedOf(file).delete() }
        }
    }

    private fun rotatedOf(file: File) = File(file.parentFile, file.name + ".1")

    private fun readRange(file: File, from: Long, length: Int, target: ByteArray, offset: Int): Int {
        if (length <= 0 || !file.isFile) return 0
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                var read = 0
                while (read < length) {
                    val count = raf.read(target, offset + read, length - read)
                    if (count < 0) break
                    read += count
                }
                read
            }
        }.getOrDefault(0)
    }

    private fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    companion object {
        const val MAX_STREAM_BYTES = 8L * 1024 * 1024
    }
}

/** 追加写, 到达上限后把当前文件轮转为 `.1`（覆盖旧的 `.1`）再继续写新文件。 */
private class RotatingOutputStream(
    private val file: File,
    private val maxBytes: Long,
) : OutputStream() {
    private var out = FileOutputStream(file, true)
    private var size = file.length()

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        size += len
        if (size >= maxBytes) rotate()
    }

    private fun rotate() {
        runCatching { out.flush() }
        runCatching { out.close() }
        val rotated = File(file.parentFile, file.name + ".1")
        runCatching { rotated.delete() }
        runCatching { file.renameTo(rotated) }
        out = FileOutputStream(file, false)
        size = 0
    }

    override fun flush() {
        runCatching { out.flush() }
    }

    override fun close() {
        runCatching { out.flush() }
        runCatching { out.close() }
    }
}
