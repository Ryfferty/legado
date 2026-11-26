package io.legado.pack.diagnostics

import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackId
import io.legado.pack.api.PackLogLevel
import io.legado.pack.api.PackResourceLimits
import io.legado.pack.api.PackStorageLayout
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque

/** Immutable log line returned by read-only diagnostics snapshots. */
data class PackLogEntry(
    val timestampEpochMs: Long,
    val level: PackLogLevel,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Per-pack in-memory ring buffer plus a bounded on-disk log.
 *
 * Both budgets come from [PackResourceLimits]. There is no periodic task: the
 * in-memory ring is trimmed on append and the global disk budget is enforced by
 * truncating the least recently written pack logs when a new line would cross
 * the hard cap.
 */
class PackLogStore(
    private val layout: PackStorageLayout,
    private val limits: PackResourceLimits,
    private val onStorageFailure: (PackDiagnostic) -> Unit = {},
) {

    private val lock = Any()
    private val channels = LinkedHashMap<PackId, Channel>()
    private val globalBudget = minOf(limits.maxLogBytesTotal, PackResourceLimits.HARD_LOG_BUDGET_BYTES)
    private var totalBytes: Long = scanExistingBytes()

    fun log(
        id: PackId,
        level: PackLogLevel,
        message: String,
        details: Map<String, String> = emptyMap(),
    ): Long {
        val sanitizedMessage = sanitizeMessage(message)
        val sanitizedDetails = sanitizeDetails(details)
        val entry = PackLogEntry(
            timestampEpochMs = System.currentTimeMillis(),
            level = level,
            message = sanitizedMessage,
            details = sanitizedDetails,
        )
        var line = encodeLine(entry)
        var lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong().toLong()
        if (lineBytes > limits.maxLogBytesPerPack) {
            val shortened = sanitizeMessage(sanitizedMessage.take(ShortMessageChars))
            line = encodeLine(entry.copy(message = shortened))
            lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
        }
        synchronized(lock) {
            val channel = channelFor(id)
            channel.entries.addLast(entry)
            channel.memoryBytes += estimateMemory(entry)
            while (channel.entries.size > MaxEntriesPerPack ||
                channel.memoryBytes > limits.maxLogBytesPerPack
            ) {
                val removed = channel.entries.removeFirst()
                channel.memoryBytes -= estimateMemory(removed)
                if (channel.entries.isEmpty()) break
            }
            if (lineBytes > limits.maxLogBytesPerPack || lineBytes > globalBudget) {
                return lineBytes
            }
            try {
                writeLine(channel, line, lineBytes)
            } catch (e: Exception) {
                onStorageFailure(
                    PackDiagnostic(
                        code = PackErrorCode.IO_FAILED,
                        message = "cannot persist pack log: ${e.message}",
                        path = "logs/${id.value}",
                    ),
                )
            }
            return lineBytes
        }
    }

    fun snapshot(id: PackId, limit: Int = 200): List<PackLogEntry> {
        if (limit <= 0) return emptyList()
        synchronized(lock) {
            val channel = channels[id] ?: return emptyList()
            val size = channel.entries.size
            val skip = (size - limit).coerceAtLeast(0)
            return channel.entries.drop(skip).map { it.copy(details = it.details.toMap()) }
        }
    }

    fun diskBytes(id: PackId): Long {
        synchronized(lock) {
            val channel = channels[id]
            return channel?.fileBytes ?: logFile(id).length()
        }
    }

    fun totalDiskBytes(): Long = synchronized(lock) { totalBytes }

    fun logFile(id: PackId): File = File(layout.logsDir(id), LOG_FILE_NAME)

    fun onPackRemoved(id: PackId) {
        synchronized(lock) {
            val removed = channels.remove(id)
            if (removed != null) {
                totalBytes -= removed.fileBytes
                if (totalBytes < 0L) totalBytes = scanExistingBytes()
            }
        }
    }

    fun close() {
        // Every append is flushed before returning; there is no background writer.
    }

    private class Channel(val file: File) {
        val entries: ArrayDeque<PackLogEntry> = ArrayDeque()
        var memoryBytes: Long = 0L
        var fileBytes: Long = if (file.isFile) file.length() else 0L
    }

    private fun channelFor(id: PackId): Channel =
        channels.getOrPut(id) { Channel(logFile(id)) }

    private fun writeLine(channel: Channel, line: String, lineBytes: Long) {
        val directory = channel.file.parentFile
        if (directory != null && !directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("cannot create log directory ${directory.path}")
        }
        if (channel.fileBytes + lineBytes > limits.maxLogBytesPerPack) {
            if (!resetChannelFile(channel)) return
        }
        if (totalBytes + lineBytes > globalBudget) {
            freeOtherLogs(channel.file, totalBytes + lineBytes - globalBudget)
            if (totalBytes + lineBytes > globalBudget) {
                if (!resetChannelFile(channel) || totalBytes + lineBytes > globalBudget) return
            }
        }
        FileOutputStream(channel.file, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.flush()
        }
        channel.fileBytes += lineBytes
        totalBytes += lineBytes
        if (totalBytes > globalBudget) {
            totalBytes = globalBudget
        }
    }

    private fun resetChannelFile(channel: Channel): Boolean {
        val before = channel.fileBytes
        val reclaimed = truncate(channel.file)
        channel.fileBytes = before - reclaimed
        totalBytes -= reclaimed
        if (totalBytes < 0L) totalBytes = 0L
        return channel.fileBytes == 0L
    }

    private fun freeOtherLogs(current: File, needed: Long) {
        if (needed <= 0L) return
        val candidates = ArrayList<File>()
        layout.packsRoot.listFiles()?.forEach { packDir ->
            if (!packDir.isDirectory) return@forEach
            val logs = File(packDir, "logs")
            logs.listFiles()?.forEach { file ->
                if (file.isFile && file.absoluteFile != current.absoluteFile) {
                    candidates += file
                }
            }
        }
        candidates.sortBy { it.lastModified() }
        var freed = 0L
        var index = 0
        while (freed < needed && index < candidates.size) {
            val file = candidates[index++]
            val reclaimed = truncate(file)
            freed += reclaimed
            totalBytes -= reclaimed
            if (totalBytes < 0L) totalBytes = 0L
            // Keep the evicted pack's cached byte count in sync with the file.
            // A stale value makes diskBytes lie, and the evicted pack's next
            // write trips the per-pack limit on a phantom balance so its
            // lines are silently dropped instead of written.
            channelForFile(file)?.let { channel ->
                channel.fileBytes -= reclaimed
                if (channel.fileBytes < 0L) channel.fileBytes = 0L
            }
        }
    }

    private fun channelForFile(file: File): Channel? =
        channels.values.firstOrNull { it.file.absoluteFile == file.absoluteFile }

    /**
     * Empties the file and returns the number of bytes reclaimed
     * (`file.length()` before the call, or 0 when the file does not exist
     * or cannot be truncated). Callers treat the result as bytes freed.
     */
    private fun truncate(file: File): Long {
        if (!file.exists()) return 0L
        val before = file.length()
        return try {
            file.setWritable(true, false)
            FileOutputStream(file, false).use { }
            before
        } catch (_: Exception) {
            0L
        }
    }

    private fun scanExistingBytes(): Long {
        var total = 0L
        layout.packsRoot.listFiles()?.forEach { packDir ->
            if (!packDir.isDirectory) return@forEach
            val logs = File(packDir, "logs")
            logs.listFiles()?.forEach { file ->
                if (file.isFile) total += file.length()
            }
        }
        return total
    }

    private fun sanitizeMessage(message: String): String {
        val singleLine = message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
        val redacted = Redaction.redactText(singleLine)
        return if (redacted.length > MaxMessageChars) redacted.take(MaxMessageChars) else redacted
    }

    private fun sanitizeDetails(details: Map<String, String>): Map<String, String> {
        if (details.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        details.entries.take(MaxDetails).forEach { (key, value) ->
            val safeKey = key.take(MaxDetailKeyChars)
            val safeValue = if (Redaction.isSensitiveKey(key)) {
                Redaction.MASK
            } else {
                Redaction.redactText(value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '))
                    .take(MaxDetailValueChars)
            }
            result[safeKey] = safeValue
        }
        return result.toMap()
    }

    private fun encodeLine(entry: PackLogEntry): String = buildString {
        append(entry.timestampEpochMs)
        append('\t')
        append(entry.level.name)
        append('\t')
        append(entry.message)
        if (entry.details.isNotEmpty()) {
            append('\t')
            entry.details.entries.sortedBy { it.key }.forEachIndexed { index, detail ->
                if (index > 0) append('&')
                append(detail.key.replace('\t', ' '))
                append('=')
                append(detail.value.replace('\t', ' '))
            }
        }
        append('\n')
    }

    private fun estimateMemory(entry: PackLogEntry): Long {
        var bytes = entry.message.toByteArray(Charsets.UTF_8).size.toLong() + 64L
        entry.details.forEach { (key, value) ->
            bytes += key.length + value.toByteArray(Charsets.UTF_8).size + 8L
        }
        return bytes
    }

    companion object {
        private const val LOG_FILE_NAME = "pack.log"
        private const val MaxEntriesPerPack = 2_000
        private const val MaxMessageChars = 8_192
        private const val ShortMessageChars = 512
        private const val MaxDetails = 32
        private const val MaxDetailKeyChars = 128
        private const val MaxDetailValueChars = 1_024
    }
}