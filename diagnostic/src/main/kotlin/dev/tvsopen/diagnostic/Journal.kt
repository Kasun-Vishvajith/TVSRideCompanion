package dev.tvsopen.diagnostic

import android.os.SystemClock
import dev.tvsopen.transport.BleFrame
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant

/** Keep the full capture on disk and only a bounded tail in the live views. */
internal class Journal(private val file: File) : Closeable {
    private val all = ArrayDeque<String>()
    private val incoming = ArrayDeque<String>()
    private val review = ArrayDeque<String>()
    private val writer = file.writerAppend()
    private var sequence = 0L
    private var pending = 0
    private var lastRx = "none this session"
    private var closed = false
    @Volatile var error: String? = null
        private set

    init {
        RandomAccessFile(file, "r").use { previous ->
            val start = (previous.length() - 65536).coerceAtLeast(0L)
            previous.seek(start)
            if (start > 0) previous.readLine()
            val bytes = ByteArray((previous.length() - previous.filePointer).toInt())
            previous.readFully(bytes)
            bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach {
                keep(all, it)
                if (" [RX] " in it) keep(incoming, it)
                if (" [RX] REVIEW " in it) keep(review, it)
            }
        }
        add("SESSION", "Diagnostic capture started")
    }

    @Synchronized
    fun frame(frame: BleFrame) {
        val rx = frame.direction == BleFrame.Direction.INBOUND
        val hex = frame.hex
        val id = frame.bytes.getOrNull(1)?.toInt()?.and(255)
        val priority = if (rx && id in listOf(0x10, 0x11, 0x16, 0x18, 0x19) ||
            !rx && id == 0x4A) "ROUTINE" else "REVIEW"
        val entry = add(if (rx) "RX" else "TX", "$priority ${frame.bytes.size}B $hex", frame.atMillis)
        if (rx) {
            lastRx = "#$sequence $hex"
            keep(incoming, entry)
            if (priority == "REVIEW") keep(review, entry)
        }
    }

    @Synchronized
    fun note(note: String, important: Boolean, context: String) {
        add(if (important) "IMPORTANT" else "NOTE", "$context | $note | last RX $lastRx")
    }

    @Synchronized
    fun add(tag: String, message: String, timestamp: Long = System.currentTimeMillis()): String {
        val entry = "${Instant.ofEpochMilli(timestamp)} +${SystemClock.elapsedRealtime()}ms " +
            "#${++sequence} [$tag] ${message.replace('\n', ' ').replace('\r', ' ')}"
        keep(all, entry)
        if (!closed && error == null) {
            runCatching {
                writer.appendLine(entry)
                if (++pending >= 32 || tag !in setOf("RX", "TX", "GATT")) flush()
            }.onFailure { error = "Capture write failed: ${it.message}" }
        }
        return entry
    }

    @Synchronized
    fun tail(receivedOnly: Boolean, reviewOnly: Boolean): String =
        (if (reviewOnly) review else if (receivedOnly) incoming else all).joinToString("\n")

    @Synchronized
    fun flush() {
        if (closed) return
        runCatching { writer.flush(); pending = 0 }
            .onFailure { error = "Capture flush failed: ${it.message}" }
    }

    data class Page(val text: String, val start: Long, val end: Long)

    fun history(before: Long?): Page = RandomAccessFile(file, "r").use { input ->
        val end = before ?: synchronized(this) { flush(); file.length() }
        val offset = (end - 32768).coerceAtLeast(0L)
        input.seek(offset)
        // Seek to a complete UTF-8 record, checking the previous byte in case
        // the window starts exactly at the beginning of a line.
        if (offset > 0) {
            input.seek(offset - 1)
            if (input.read() != '\n'.code) input.readLine()
        }
        val start = input.filePointer
        check(start < end || end == 0L) { "Capture record is too long to display" }
        val bytes = ByteArray((end - start).toInt())
        input.readFully(bytes)
        Page(bytes.toString(Charsets.UTF_8), start, end)
    }

    fun export(output: OutputStream) {
        val size = synchronized(this) { flush(); file.length() }
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32768)
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(n > 0) { "Capture ended during export" }
                output.write(buffer, 0, n)
                remaining -= n
            }
        }
    }

    @Synchronized
    override fun close() {
        flush()
        closed = true
        runCatching { writer.close() }
    }

    private fun keep(lines: ArrayDeque<String>, entry: String) {
        if (lines.size == 180) lines.removeFirst()
        lines.addLast(entry)
    }

    private fun File.writerAppend() =
        java.io.FileOutputStream(this, true).bufferedWriter(Charsets.UTF_8)
}
