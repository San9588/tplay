package dev.tplay.data.lyrics

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.tplay.data.model.Song
import dev.tplay.data.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

data class Lyrics(
    val source: String,
    val lines: List<LyricLine>,
) {
    val isSynced: Boolean get() = lines.any { it.timeMs >= 0 }

    fun currentIndex(ms: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs >= 0 && lines[i].timeMs <= ms) idx = i
        }
        return idx
    }
}

class LyricsRepository(private val context: Context) {

    suspend fun load(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        runCatching {
            when (song.source) {
                SongSource.LOCAL -> loadLocal(song)
                SongSource.YOUTUBE -> loadExternalFiles(song)
            }
        }.getOrNull()
    }

    private fun loadLocal(song: Song): Lyrics? {
        loadExternalFiles(song)?.let { return it }
        return embeddedFromSong(song)
    }

    // ---- external .lrc / .lyric / .txt files ----

    private fun loadExternalFiles(song: Song): Lyrics? {
        val names = buildSet {
            add(normalize(song.title))
            add(normalize(song.videoId ?: ""))
            val base = normalize(song.title)
            if (base.isNotEmpty()) add(base)
        }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        for (dir in candidateDirs(song)) {
            val files = dir.listFiles() ?: continue
            for (ext in LYRICS_EXTS) {
                for (name in names) {
                    val f = File(dir, "$name.$ext")
                    if (f.isFile) return parseLyricsFile(f)
                }
            }
            for (f in files) {
                val lower = f.name.lowercase(Locale.ROOT)
                if (!lower.endsWith(".lrc") && !lower.endsWith(".lyric")) continue
                val base = normalize(f.nameWithoutExtension)
                if (base in names) return parseLyricsFile(f)
            }
        }
        return null
    }

    private fun candidateDirs(song: Song): List<File> {
        val dirs = mutableListOf<File>()
        if (song.source == SongSource.LOCAL) {
            resolveParentDir(song)?.let { dirs.add(it) }
        }
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), ""))
        dirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ""))
        dirs.add(File(context.getExternalFilesDir(null), "lyrics"))
        dirs.add(File(context.filesDir, "lyrics"))
        return dirs
    }

    private fun resolveParentDir(song: Song): File? = runCatching {
        val uri = android.net.Uri.parse(song.uri)
        val projection = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DATA)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val rel = runCatching {
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                }.getOrNull()?.let { cursor.getString(it) }
                val data = runCatching {
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                }.getOrNull()?.let { cursor.getString(it) }
                val parent = data?.let { File(it).parentFile }
                if (parent != null) return parent
                rel?.let {
                    return File(Environment.getExternalStorageDirectory(), it)
                }
            }
        }
        null
    }.getOrNull()

    private fun parseLyricsFile(file: File): Lyrics? {
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { return null }
        return parseContent(content, source = file.name)
    }

    private fun parseContent(content: String, source: String): Lyrics? {
        val lrc = LrcParser.parse(content)
        if (lrc.isNotEmpty()) {
            return Lyrics(source = source, lines = lrc)
        }
        val plain = content.lines().map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { LyricLine(-1L, it) }
        if (plain.isEmpty()) return null
        return Lyrics(source = source, lines = plain)
    }

    // ---- embedded lyrics (ID3v2 USLT, Vorbis LYRICS, iTunes (c)lyr) ----

    private fun embeddedFromSong(song: Song): Lyrics? {
        val bytes = readHead(android.net.Uri.parse(song.uri), MAX_READ) ?: return null
        val text = when {
            bytes.startsWithAscii("ID3") -> Id3Lyrics.extract(bytes)
            bytes.startsWithAscii("fLaC") -> VorbisLyrics.scan(bytes)
            else -> VorbisLyrics.scan(bytes) ?: M4aLyrics.scan(bytes)
        }?.trim()
        if (text.isNullOrEmpty()) return null
        return parseContent(text, source = "embedded")
    }

    private fun readHead(uri: android.net.Uri, max: Int): ByteArray? = runCatching {
        val input: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < max) {
                val n = stream.read(buf, 0, minOf(buf.size, max - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun ByteArray.startsWithAscii(s: String): Boolean {
        if (size < s.length) return false
        for (i in s.indices) if (this[i].toInt().toChar() != s[i]) return false
        return true
    }

    companion object {
        private const val MAX_READ = 2 * 1024 * 1024
        private val LYRICS_EXTS = listOf("lrc", "lyric", "txt")

        private fun normalize(s: String): String = s.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

object LrcParser {
    private val TIME_RE = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(content: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val matches = TIME_RE.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.replace(TIME_RE, "").trim()
            if (text.isEmpty()) return@forEach
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val frac = m.groupValues[3]
                val fracMs = when (frac.length) {
                    1 -> frac.toLongOrNull()?.times(100) ?: 0L
                    2 -> frac.toLongOrNull()?.times(10) ?: 0L
                    3 -> frac.toLongOrNull() ?: 0L
                    else -> 0L
                }
                out += LyricLine(min * 60_000L + sec * 1000L + fracMs, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}

object Id3Lyrics {

    fun extract(data: ByteArray): String? {
        if (data.size < 10) return null
        if (data[0].toInt().toChar() != 'I' || data[1].toInt().toChar() != 'D' ||
            data[2].toInt().toChar() != '3'
        ) return null
        val version = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val tagSize = synchsafe32(data, 6)
        val end = minOf(10 + tagSize, data.size)
        var pos = 10
        if (flags and 0x40 != 0 && pos + 4 <= end) {
            val extSize = synchsafe32(data, pos)
            pos += 4 + extSize
        }
        val idLen = if (version < 3) 3 else 4
        val sizeLen = if (version < 3) 3 else 4
        val headerLen = idLen + sizeLen + if (version < 3) 0 else 2
        while (pos + headerLen <= end) {
            val id = String(data, pos, idLen, Charsets.US_ASCII)
            if (id.all { it == '\u0000' }) break
            val frameSize = if (version < 3) {
                readU24(data, pos + idLen)
            } else if (version >= 4) {
                synchsafe32(data, pos + idLen)
            } else {
                readU32(data, pos + idLen)
            }
            if (frameSize <= 0 || frameSize > end - pos) break
            val start = pos + headerLen
            if (id == "USLT") {
                val text = decodeUslt(data, start, frameSize)
                if (!text.isNullOrBlank()) return text
            }
            pos = start + frameSize
        }
        return null
    }

    private fun decodeUslt(data: ByteArray, start: Int, size: Int): String? {
        if (size < 4 || start + size > data.size) return null
        val encoding = data[start].toInt() and 0xFF
        // skip encoding(1) + language(3) + descriptor terminator
        var p = start + 4
        val textStart = when (encoding) {
            1, 2 -> {
                while (p + 1 < start + size && !(data[p] == 0.toByte() && data[p + 1] == 0.toByte())) p += 2
                p + 2
            }
            else -> {
                while (p < start + size && data[p] != 0.toByte()) p++
                p + 1
            }
        }
        if (textStart >= start + size) return null
        return when (encoding) {
            0 -> String(data, textStart, start + size - textStart, Charsets.ISO_8859_1)
            1 -> decodeUtf16(data, textStart, start + size, littleEndian = true)
            2 -> decodeUtf16(data, textStart, start + size, littleEndian = false)
            3 -> String(data, textStart, start + size - textStart, Charsets.UTF_8)
            else -> null
        }
    }

    private fun decodeUtf16(data: ByteArray, start: Int, end: Int, littleEndian: Boolean): String {
        var p = start
        val bom = if (end - p >= 2) ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF) else 0
        if (bom == 0xFFFE) { p += 2 } else if (bom == 0xFEFF) { p += 2 }
        val sb = StringBuilder()
        var le = littleEndian
        if (bom == 0xFFFE) le = true
        if (bom == 0xFEFF) le = false
        while (p + 1 < end) {
            if (data[p] == 0.toByte() && data[p + 1] == 0.toByte()) break
            val cp = if (le) (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
            else ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            sb.append(cp.toChar())
            p += 2
        }
        return sb.toString()
    }

    private fun readU32(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
            ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)

    private fun readU24(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or
            (d[o + 2].toInt() and 0xFF)

    private fun synchsafe32(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0x7F) shl 21) or ((d[o + 1].toInt() and 0x7F) shl 14) or
            ((d[o + 2].toInt() and 0x7F) shl 7) or (d[o + 3].toInt() and 0x7F)
}

object VorbisLyrics {
    fun scan(data: ByteArray): String? {
        val marker = "LYRICS=".toByteArray(Charsets.US_ASCII)
        val max = minOf(data.size, 1_000_000)
        var i = 0
        outer@ while (i + marker.size <= max) {
            if (data[i] != 'L'.code.toByte()) { i++; continue }
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) { i++; continue@outer }
            }
            var end = i + marker.size
            while (end < max && data[end] != 0.toByte()) end++
            if (end > i + marker.size) {
                return String(data, i + marker.size, end - i - marker.size, Charsets.UTF_8)
            }
            i++
        }
        return null
    }
}

object M4aLyrics {
    fun scan(data: ByteArray): String? {
        val marker = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val max = minOf(data.size, 2_000_000)
        var i = 0
        outer@ while (i + 20 <= max) {
            if (data[i] != marker[0]) { i++; continue }
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) { i++; continue@outer }
            }
            // after 'c)lyr': data-atom size(4) "data"(4) type(4) locale(4) then payload
            val payloadStart = i + 20
            var end = payloadStart
            while (end < max && data[end] != 0.toByte() && end - payloadStart < 64_000) end++
            if (end > payloadStart) {
                val raw = String(data, payloadStart, end - payloadStart, Charsets.UTF_8)
                if (raw.isNotBlank()) return raw
            }
            i++
        }
        return null
    }
}
