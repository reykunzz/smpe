package com.smpe.mapeditor

import android.content.Context
import android.opengl.ETC1
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

private val FFB: Byte = 0xFF.toByte()

/** Name hash stored in the mobile .dat listings (see GTAMods wiki, "Mobile textures (SA/VC)"). */
fun nameHash16(name: String): Int {
    var h = 0
    for (b in name.toByteArray(Charsets.ISO_8859_1)) h += (h shl 5) + (b.toInt() and 0xFF)
    h += (h ushr 5)
    return h and 0xFFFF
}

class DbEntry(val name: String, val w: Int, val h: Int, val alias: String?)

/**
 * One mobile texture database: <base>.txt (names, order), <base>.<fmt>.toc (offsets), <base>.<fmt>.dat (data).
 * Header layout is validated against the .txt (size) and the format is inferred from the decoded length,
 * so unknown variations fail gracefully and are visible in the diagnostics.
 */
class TexDb(ctx: Context, val base: String, val fmt: String, dat: DocumentFile, toc: DocumentFile?, txt: DocumentFile) {
    private val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(dat.uri, "r")
        ?: throw IllegalStateException("tidak bisa membuka " + dat.name)
    private val fis = FileInputStream(pfd.fileDescriptor)
    private val ch: FileChannel = fis.channel
    val datSize: Long = ch.size()
    val entries = ArrayList<DbEntry>()
    private val index = HashMap<String, Int>()
    private var offsets: IntArray? = null
    private var seqTried = false
    var tocOk = false
    val ok = AtomicInteger(0)
    val fail = AtomicInteger(0)
    @Volatile var note = ""

    init {
        val text = ctx.contentResolver.openInputStream(txt.uri)!!.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
        parseTxt(text)
        if (toc != null) readToc(ctx, toc)
    }

    private fun parseTxt(text: String) {
        val aff = Regex("\"affiliate=([^\"]*)\"")
        val wRe = Regex("width=(\\d+)")
        val hRe = Regex("height=(\\d+)")
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (!l.startsWith("\"")) continue
            val end = l.indexOf('"', 1)
            if (end < 0) continue
            val name = l.substring(1, end)
            val rest = l.substring(end + 1)
            val alias = aff.find(rest)?.groupValues?.get(1)
            val w = wRe.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = hRe.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (!index.containsKey(name.lowercase())) index[name.lowercase()] = entries.size
            entries.add(DbEntry(name, w, h, alias))
        }
    }

    private fun readToc(ctx: Context, toc: DocumentFile) {
        try {
            val b = ctx.contentResolver.openInputStream(toc.uri)!!.use { it.readBytes() }
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            if (b.size >= 4 + entries.size * 4 && (bb.getInt(0).toLong() and 0xFFFFFFFFL) == datSize) {
                offsets = IntArray(entries.size) { bb.getInt(4 + it * 4) }
                tocOk = true
            }
        } catch (e: Exception) { }
    }

    private fun read(pos: Long, n: Int): ByteArray? {
        if (pos < 0 || n < 0 || pos + n > datSize) return null
        val bb = ByteBuffer.allocate(n)
        var p = pos
        while (bb.hasRemaining()) { val r = ch.read(bb, p); if (r < 0) break; p += r }
        return if (bb.position() == n) bb.array() else null
    }

    @Synchronized
    private fun offsetsOrBuild(): IntArray? {
        offsets?.let { return it }
        if (seqTried) return null
        seqTried = true
        val o = IntArray(entries.size)
        var pos = 0L
        for (i in entries.indices) {
            if (entries[i].alias != null) { o[i] = -1; continue }
            val hd = read(pos, 16) ?: return null
            val size = ByteBuffer.wrap(hd).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
            if (size < 0) return null
            o[i] = pos.toInt()
            pos += 16L + size
        }
        offsets = o
        return o
    }

    private fun levelSize(kind: String, w: Int, h: Int): Int = when (kind) {
        "ETC1", "DXT1" -> max(1, (w + 3) / 4) * max(1, (h + 3) / 4) * 8
        "DXT5" -> max(1, (w + 3) / 4) * max(1, (h + 3) / 4) * 16
        "RGBA32" -> w * h * 4
        "RGBA16" -> w * h * 2
        else -> w * h
    }

    private fun kinds(): List<String> = when (fmt) {
        "etc" -> listOf("ETC1", "RGBA32", "RGBA16", "L8")
        "dxt" -> listOf("DXT1", "DXT5", "RGBA32", "RGBA16", "L8")
        else -> listOf("RGBA32", "RGBA16", "L8")
    }

    private fun rleDecode(src: ByteArray, ind: Int, seg: Int): ByteArray? {
        val out = ByteArrayOutputStream(src.size * 2)
        var p = 0
        while (p < src.size) {
            if ((src[p].toInt() and 0xFF) == ind) {
                if (p + 2 + seg > src.size) return null
                val rep = src[p + 1].toInt() and 0xFF
                for (r in 0 until rep) out.write(src, p + 2, seg)
                p += 2 + seg
            } else {
                if (p + seg > src.size) return null
                out.write(src, p, seg)
                p += seg
            }
            if (out.size() > 64_000_000) return null
        }
        return out.toByteArray()
    }

    private fun convert(kind: String, d: ByteArray, w: Int, h: Int): ByteArray? {
        val out = ByteArray(w * h * 4)
        when (kind) {
            "ETC1" -> {
                val n = levelSize("ETC1", w, h)
                if (d.size < n) return null
                val enc = ByteBuffer.allocateDirect(n); enc.put(d, 0, n); enc.position(0)
                val rgb = ByteBuffer.allocateDirect(w * h * 3)
                ETC1.decodeImage(enc, rgb, w, h, 3, w * 3)
                val a = ByteArray(w * h * 3); rgb.position(0); rgb.get(a)
                for (i in 0 until w * h) { out[i * 4] = a[i * 3]; out[i * 4 + 1] = a[i * 3 + 1]; out[i * 4 + 2] = a[i * 3 + 2]; out[i * 4 + 3] = FFB }
            }
            "DXT1" -> decodeDxt(ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN), 0, w, h, 1, out)
            "DXT5" -> decodeDxt(ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN), 0, w, h, 5, out)
            "RGBA32" -> { if (d.size < w * h * 4) return null; System.arraycopy(d, 0, out, 0, w * h * 4) }
            "RGBA16" -> {
                if (d.size < w * h * 2) return null
                for (i in 0 until w * h) {
                    val v = (d[i * 2].toInt() and 0xFF) or ((d[i * 2 + 1].toInt() and 0xFF) shl 8)
                    out[i * 4] = (((v shr 12) and 15) * 17).toByte(); out[i * 4 + 1] = (((v shr 8) and 15) * 17).toByte()
                    out[i * 4 + 2] = (((v shr 4) and 15) * 17).toByte(); out[i * 4 + 3] = ((v and 15) * 17).toByte()
                }
            }
            else -> {
                if (d.size < w * h) return null
                for (i in 0 until w * h) { out[i * 4] = d[i]; out[i * 4 + 1] = d[i]; out[i * 4 + 2] = d[i]; out[i * 4 + 3] = FFB }
            }
        }
        return out
    }

    private fun fail(msg: String): TexData? { note = msg; return null }

    private fun decode(idx: Int, e: DbEntry): TexData? {
        val offs = offsets ?: offsetsOrBuild() ?: return fail("offset tidak tersedia (toc tidak cocok)")
        val off = offs.getOrNull(idx)?.toLong() ?: return fail("offset di luar daftar")
        if (off < 0) return null
        val head = read(off, 40) ?: return fail("gagal baca header")
        val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        var shift = -1
        for (s in intArrayOf(0, 8)) {
            if ((hb.getShort(s + 4).toInt() and 0xFFFF) == e.w && (hb.getShort(s + 6).toInt() and 0x7FFF) == e.h) { shift = s; break }
        }
        if (shift < 0) return fail("header tidak cocok dengan .txt")
        val hm = hb.getShort(shift + 6).toInt() and 0xFFFF
        val size = hb.getInt(shift + 8)
        val rle = hb.getInt(shift + 12)
        if (size <= 0 || size > 48_000_000) return fail("ukuran data aneh: $size")
        val raw = read(off + shift + 16, size) ?: return fail("gagal baca data")
        val noMips = (hm and 0x8000) != 0
        val mips = ArrayList<IntArray>()
        var mw = e.w; var mh = e.h
        while (true) { mips.add(intArrayOf(mw, mh)); if (noMips || (mw == 1 && mh == 1)) break; mw = max(1, mw / 2); mh = max(1, mh / 2) }

        val tries = ArrayList<Pair<String, ByteArray>>()
        if (rle == 0) tries.add("raw" to raw)
        else for (s in intArrayOf(4, 2, 1, 8, 3, 16)) rleDecode(raw, rle and 0xFF, s)?.let { tries.add("rle$s" to it) }
        val ks = kinds()
        for ((tn, data) in tries) for (k in ks) {
            if (data.size == mips.sumOf { levelSize(k, it[0], it[1]) }) {
                val px = convert(k, data, e.w, e.h)
                if (px != null) { note = "$k/$tn"; return TexData("db/$base/${e.name.lowercase()}", e.w, e.h, px) }
            }
        }
        // lenient pass: biggest format whose first level fits in the data
        for ((tn, data) in tries) for (k in ks.sortedByDescending { levelSize(it, e.w, e.h) }) {
            if (levelSize(k, e.w, e.h) <= data.size) {
                val px = convert(k, data, e.w, e.h)
                if (px != null) { note = "~$k/$tn"; return TexData("db/$base/${e.name.lowercase()}", e.w, e.h, px) }
            }
        }
        return fail("format tidak dikenali (enc=" + (hb.getShort(shift + 2).toInt() and 0xFFFF) + " rle=" + rle + " size=" + size + ")")
    }

    fun get(name: String): TexData? = get(name.lowercase(), 0)

    private fun get(key: String, depth: Int): TexData? {
        val idx = index[key] ?: return null
        val e = entries[idx]
        val al = e.alias
        if (al != null) return if (depth < 4) get(al.lowercase(), depth + 1) else null
        val t = try { decode(idx, e) } catch (th: Throwable) { fail("error: " + th.message) }
        if (t != null) ok.incrementAndGet() else fail.incrementAndGet()
        return t
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { String.format("%02x", it) }

    fun report(maxSamples: Int): String {
        val sb = StringBuilder("DB ").append(base).append('.').append(fmt).append(": ").append(entries.size).append(" entri, dat ")
            .append(datSize).append(" B, toc ").append(if (tocOk) "OK" else "TIDAK cocok").append('\n')
        var shown = 0
        var okN = 0; var failN = 0
        for (i in entries.indices) {
            val e = entries[i]
            if (e.alias != null) continue
            val t = try { decode(i, e) } catch (th: Throwable) { fail("error: " + th.message) }
            if (t != null) okN++ else failN++
            if (shown < maxSamples) {
                shown++
                val off = offsets?.getOrNull(i)
                sb.append("  #").append(i).append(' ').append(e.name).append(' ').append(e.w).append('x').append(e.h).append(" off=").append(off)
                val hd = if (off != null && off >= 0) read(off.toLong(), 24) else null
                if (hd != null) {
                    sb.append(" hdr=").append(hex(hd))
                    sb.append(" hashOK=").append((ByteBuffer.wrap(hd).order(ByteOrder.LITTLE_ENDIAN).getShort(0).toInt() and 0xFFFF) == nameHash16(e.name))
                }
                sb.append(" -> ").append(if (t != null) "OK " else "GAGAL ").append(note).append('\n')
            }
            if (i >= 80) break
        }
        sb.append("  uji ~80 entri awal: ok ").append(okN).append(", gagal ").append(failN).append('\n')
        return sb.toString()
    }
}
