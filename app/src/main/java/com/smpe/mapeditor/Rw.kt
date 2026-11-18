package com.smpe.mapeditor

import android.content.Context
import android.net.Uri
import android.opengl.Matrix
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

class ImgEntry(val offset: Long, val size: Int)
class TexData(val key: String, val w: Int, val h: Int, val rgba: ByteArray)
class PartData(val idx: ShortArray, val col: FloatArray, val tex: TexData?)
/** verts: interleaved pos3 nrm3 uv2 col4 (12 floats) */
class ModelData(val verts: FloatArray, val parts: List<PartData>, val min: FloatArray, val max: FloatArray)
class ObjDef(val id: Int, val model: String, val txd: String) { val lc = model.lowercase() }

// ---------- RenderWare chunk helpers ----------
private class Ck(val type: Int, val size: Int, val ver: Int, val s: Int) { val e get() = s + size }

private fun ByteBuffer.ck(p: Int): Ck? {
    if (p < 0 || p + 12 > limit()) return null
    val sz = getInt(p + 4)
    if (sz < 0 || p + 12 + sz > limit()) return null
    return Ck(getInt(p), sz, getInt(p + 8), p + 12)
}

private inline fun ByteBuffer.kids(c: Ck, f: (Ck) -> Unit) {
    var p = c.s
    while (p + 12 <= c.e) { val k = ck(p) ?: break; f(k); p = k.e }
}

private fun rwVer(v: Int): Int =
    if ((v and 0xFFFF0000.toInt()) != 0) ((((v ushr 14) and 0x3FF00) + 0x30000) or ((v ushr 16) and 0x3F)) else (v shl 8)

private fun ByteBuffer.str(p: Int, maxLen: Int): String {
    val sb = StringBuilder()
    for (i in 0 until maxLen) { val b = get(p + i).toInt(); if (b == 0) break; sb.append((b and 0xFF).toChar()) }
    return sb.toString()
}

// ---------- DFF ----------
private class MatRaw(val col: FloatArray, val tex: String?)
private class GeoRaw(val n: Int, val pos: FloatArray, val uv: FloatArray?, val col: FloatArray?, val tri: IntArray, val mats: List<MatRaw>)

private fun parseMaterial(bb: ByteBuffer, mc: Ck): MatRaw {
    val ms = bb.ck(mc.s)
    var col = floatArrayOf(1f, 1f, 1f, 1f)
    var name: String? = null
    if (ms != null) {
        val q = ms.s
        col = floatArrayOf((bb.get(q + 4).toInt() and 0xFF) / 255f, (bb.get(q + 5).toInt() and 0xFF) / 255f,
            (bb.get(q + 6).toInt() and 0xFF) / 255f, (bb.get(q + 7).toInt() and 0xFF) / 255f)
        if (bb.getInt(q + 12) != 0) {
            bb.kids(mc) { t ->
                if (t.type == 0x06) {
                    val ts = bb.ck(t.s)
                    if (ts != null) { val n = bb.ck(ts.e); if (n != null && n.type == 2) name = bb.str(n.s, n.size) }
                }
            }
        }
    }
    return MatRaw(col, name)
}

private fun parseGeo(bb: ByteBuffer, g: Ck): GeoRaw? {
    val st = bb.ck(g.s) ?: return null
    var p = st.s
    val flags = bb.getInt(p); val nt = bb.getInt(p + 4); val nv = bb.getInt(p + 8)
    p += 16
    if (rwVer(g.ver) < 0x34000) p += 12
    if (nv <= 0 || nv > 65535) return null
    if ((flags and 0x01000000) != 0) return parseNativeGeo(bb, g, nv)
    var col: FloatArray? = null
    if ((flags and 0x08) != 0) { val c0 = p; col = FloatArray(nv * 4) { (bb.get(c0 + it).toInt() and 0xFF) / 255f }; p += nv * 4 }
    var nTex = (flags ushr 16) and 0xFF
    if (nTex == 0) nTex = if ((flags and 0x04) != 0) 1 else if ((flags and 0x80) != 0) 2 else 0
    var uv: FloatArray? = null
    for (t in 0 until nTex) { if (t == 0) { val u0 = p; uv = FloatArray(nv * 2) { bb.getFloat(u0 + it * 4) } }; p += nv * 8 }
    var tri = IntArray(nt * 4)
    for (i in 0 until nt) {
        val b = p + i * 8
        tri[i * 4] = bb.getShort(b + 2).toInt() and 0xFFFF
        tri[i * 4 + 1] = bb.getShort(b).toInt() and 0xFFFF
        tri[i * 4 + 2] = bb.getShort(b + 6).toInt() and 0xFFFF
        tri[i * 4 + 3] = bb.getShort(b + 4).toInt() and 0xFFFF
    }
    p += nt * 8 + 16
    val hasV = bb.getInt(p)
    p += 8
    if (hasV == 0) return null
    val p0 = p
    val pos = FloatArray(nv * 3) { bb.getFloat(p0 + it * 4) }
    val mats = ArrayList<MatRaw>()
    bb.kids(g) { k ->
        if (k.type == 0x08) {
            val s2 = bb.ck(k.s)
            if (s2 != null) {
                val num = bb.getInt(s2.s)
                var q = s2.e
                for (i in 0 until num) {
                    val ix = bb.getInt(s2.s + 4 + i * 4)
                    if (ix < 0 || ix >= mats.size) { val mc = bb.ck(q); if (mc != null) { mats.add(parseMaterial(bb, mc)); q = mc.e } else mats.add(MatRaw(floatArrayOf(1f, 1f, 1f, 1f), null)) }
                    else mats.add(mats[ix])
                }
            }
        }
    }
    if (nt == 0) {
        var bm: Ck? = null
        bb.kids(g) { k -> if (k.type == 0x03) bb.kids(k) { m -> if (m.type == 0x50E) bm = m } }
        val b2 = bm
        if (b2 != null) tri = readBinMesh(bb, b2)
    }
    return GeoRaw(nv, pos, uv, col, tri, mats)
}

private fun readMats(bb: ByteBuffer, g: Ck): ArrayList<MatRaw> {
    val mats = ArrayList<MatRaw>()
    bb.kids(g) { k ->
        if (k.type == 0x08) {
            val s2 = bb.ck(k.s)
            if (s2 != null) {
                val num = bb.getInt(s2.s)
                var q = s2.e
                for (i in 0 until num) {
                    val ix = bb.getInt(s2.s + 4 + i * 4)
                    if (ix < 0 || ix >= mats.size) {
                        val mc = bb.ck(q)
                        if (mc != null) { mats.add(parseMaterial(bb, mc)); q = mc.e } else mats.add(MatRaw(floatArrayOf(1f, 1f, 1f, 1f), null))
                    } else mats.add(mats[ix])
                }
            }
        }
    }
    return mats
}

/** Bin Mesh PLG: triangle list/strip split by material. Mobile files use uint16 indices, PC uint32. */
private fun readBinMesh(bb: ByteBuffer, m: Ck): IntArray {
    val q = m.s
    val strip = (bb.getInt(q) and 1) != 0
    val nm = bb.getInt(q + 4)
    val total = bb.getInt(q + 8)
    val u16 = m.size < 12 + nm * 8 + total * 4
    val acc = ArrayList<Int>()
    var pp = q + 12
    for (mi in 0 until nm) {
        val cnt = bb.getInt(pp); val mat = bb.getInt(pp + 4)
        pp += 8
        if (cnt < 0 || cnt > 400000) break
        val ix = IntArray(cnt)
        for (t in 0 until cnt) ix[t] = if (u16) (bb.getShort(pp + t * 2).toInt() and 0xFFFF) else bb.getInt(pp + t * 4)
        pp += cnt * (if (u16) 2 else 4)
        if (strip) {
            for (t in 2 until cnt) {
                val a = ix[t - 2]; val b = ix[t - 1]; val c = ix[t]
                if (a == b || b == c || a == c) continue
                if (t % 2 == 0) { acc.add(a); acc.add(b); acc.add(c) } else { acc.add(b); acc.add(a); acc.add(c) }
                acc.add(mat)
            }
        } else {
            var t = 0
            while (t + 2 < cnt) { acc.add(ix[t]); acc.add(ix[t + 1]); acc.add(ix[t + 2]); acc.add(mat); t += 3 }
        }
    }
    return acc.toIntArray()
}

private fun compSize(type: Int): Int = when (type) { 0 -> 4; 1, 2 -> 1; 3, 4 -> 2; else -> 0 }

private fun nativeComp(bb: ByteBuffer, p: Int, type: Int, norm: Boolean): Float = when (type) {
    0 -> bb.getFloat(p)
    1 -> { val v = bb.get(p).toInt(); if (norm) max(v / 127f, -1f) else v.toFloat() }
    2 -> { val v = bb.get(p).toInt() and 0xFF; if (norm) v / 255f else v.toFloat() }
    3 -> { val v = bb.getShort(p).toInt(); if (norm) max(v / 32767f, -1f) else v.toFloat() }
    else -> { val v = bb.getShort(p).toInt() and 0xFFFF; if (norm) v / 65535f else v.toFloat() }
}

/** Mobile (OpenGL "War Drum") pre-instanced geometry: Native Data PLG 0x510 + Bin Mesh 0x50E. */
private fun parseNativeGeo(bb: ByteBuffer, g: Ck, nv: Int): GeoRaw? {
    var nat: Ck? = null
    var bin: Ck? = null
    bb.kids(g) { k -> if (k.type == 0x03) bb.kids(k) { m -> if (m.type == 0x510) nat = m else if (m.type == 0x50E) bin = m } }
    val n = nat ?: return null
    val b = bin ?: return null
    val q = n.s
    val na = bb.getInt(q)
    if (na <= 0 || na > 12) return null
    val stride = bb.getInt(q + 4 + 16)
    val ds = q + 4 + na * 24
    if (stride <= 0 || ds.toLong() + stride.toLong() * nv > n.e.toLong()) return null
    var pos: FloatArray? = null
    var uv: FloatArray? = null
    var col: FloatArray? = null
    for (a in 0 until na) {
        val ab = q + 4 + a * 24
        val idx = bb.getInt(ab); val type = bb.getInt(ab + 4); val norm = bb.getInt(ab + 8) != 0
        val size = bb.getInt(ab + 12); val off = bb.getInt(ab + 20)
        val cs = compSize(type)
        if (cs == 0 || size < 1 || size > 4) continue
        when (idx) {
            0 -> if (size >= 3) pos = FloatArray(nv * 3) { nativeComp(bb, ds + (it / 3) * stride + off + (it % 3) * cs, type, norm) }
            1 -> if (size >= 2) uv = FloatArray(nv * 2) { nativeComp(bb, ds + (it / 2) * stride + off + (it % 2) * cs, type, norm) }
            3 -> if (size >= 3) col = FloatArray(nv * 4) {
                val c = it % 4
                if (c < size) nativeComp(bb, ds + (it / 4) * stride + off + c * cs, type, norm || type == 1 || type == 2) else 1f
            }
        }
    }
    val p = pos ?: return null
    val tri = readBinMesh(bb, b)
    if (tri.isEmpty()) return null
    return GeoRaw(nv, p, uv, col, tri, readMats(bb, g))
}

private fun parseFrames(bb: ByteBuffer, c: Ck, worlds: ArrayList<FloatArray>, names: ArrayList<String>) {
    val st = bb.ck(c.s) ?: return
    val n = bb.getInt(st.s)
    val loc = ArrayList<FloatArray>(); val par = IntArray(n)
    for (i in 0 until n) {
        val b = st.s + 4 + i * 56
        val m = FloatArray(16)
        for (k in 0 until 3) {
            m[k] = bb.getFloat(b + k * 4); m[4 + k] = bb.getFloat(b + 12 + k * 4)
            m[8 + k] = bb.getFloat(b + 24 + k * 4); m[12 + k] = bb.getFloat(b + 36 + k * 4)
        }
        m[15] = 1f; loc.add(m); par[i] = bb.getInt(b + 48)
    }
    for (i in 0 until n) {
        val pi = par[i]
        if (pi in 0 until i) { val r = FloatArray(16); Matrix.multiplyMM(r, 0, worlds[pi], 0, loc[i], 0); worlds.add(r) } else worlds.add(loc[i])
    }
    var p = st.e
    for (i in 0 until n) {
        val ext = bb.ck(p) ?: break
        var nm = ""
        bb.kids(ext) { k -> if (k.type == 0x253F2FE) nm = bb.str(k.s, k.size) }
        names.add(nm); p = ext.e
    }
}

fun parseDff(data: ByteArray, tex: (String) -> TexData?): ModelData? {
    try {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val root = bb.ck(0) ?: return null
        if (root.type != 0x10) return null
        val worlds = ArrayList<FloatArray>(); val names = ArrayList<String>()
        val geos = ArrayList<GeoRaw?>(); val atoms = ArrayList<IntArray>()
        bb.kids(root) { c ->
            when (c.type) {
                0x0E -> parseFrames(bb, c, worlds, names)
                0x1A -> bb.kids(c) { g -> if (g.type == 0x0F) geos.add(try { parseGeo(bb, g) } catch (e: Exception) { null }) }
                0x14 -> { val s = bb.ck(c.s); if (s != null) atoms.add(intArrayOf(bb.getInt(s.s), bb.getInt(s.s + 4))) }
            }
        }
        class Inc(val g: GeoRaw, val w: FloatArray?)
        val inc = ArrayList<Inc>()
        var total = 0
        for (a in atoms) {
            val g = geos.getOrNull(a[1]) ?: continue
            val nm = names.getOrNull(a[0])?.lowercase() ?: ""
            if (nm.endsWith("_dam") || nm.endsWith("_vlo") || nm.endsWith("_dummy")) continue
            if (total + g.n > 65535) continue
            inc.add(Inc(g, worlds.getOrNull(a[0]))); total += g.n
        }
        if (inc.isEmpty()) return null
        val v = FloatArray(total * 12)
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f); val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        val parts = ArrayList<PartData>()
        var off = 0
        for (c in inc) {
            val g = c.g; val w = c.w
            val px = FloatArray(g.n * 3)
            for (i in 0 until g.n) {
                val x = g.pos[i * 3]; val y = g.pos[i * 3 + 1]; val z = g.pos[i * 3 + 2]
                if (w != null) {
                    px[i * 3] = w[0] * x + w[4] * y + w[8] * z + w[12]
                    px[i * 3 + 1] = w[1] * x + w[5] * y + w[9] * z + w[13]
                    px[i * 3 + 2] = w[2] * x + w[6] * y + w[10] * z + w[14]
                } else { px[i * 3] = x; px[i * 3 + 1] = y; px[i * 3 + 2] = z }
            }
            val nrm = FloatArray(g.n * 3)
            val nTri = g.tri.size / 4
            for (t in 0 until nTri) {
                val a = g.tri[t * 4]; val b = g.tri[t * 4 + 1]; val d = g.tri[t * 4 + 2]
                if (a >= g.n || b >= g.n || d >= g.n) continue
                val e1x = px[b * 3] - px[a * 3]; val e1y = px[b * 3 + 1] - px[a * 3 + 1]; val e1z = px[b * 3 + 2] - px[a * 3 + 2]
                val e2x = px[d * 3] - px[a * 3]; val e2y = px[d * 3 + 1] - px[a * 3 + 1]; val e2z = px[d * 3 + 2] - px[a * 3 + 2]
                val nx = e1y * e2z - e1z * e2y; val ny = e1z * e2x - e1x * e2z; val nz = e1x * e2y - e1y * e2x
                for (k in intArrayOf(a, b, d)) { nrm[k * 3] += nx; nrm[k * 3 + 1] += ny; nrm[k * 3 + 2] += nz }
            }
            for (i in 0 until g.n) {
                val o = (off + i) * 12
                val x = px[i * 3]; val y = px[i * 3 + 1]; val z = px[i * 3 + 2]
                if (x < mn[0]) mn[0] = x; if (y < mn[1]) mn[1] = y; if (z < mn[2]) mn[2] = z
                if (x > mx[0]) mx[0] = x; if (y > mx[1]) mx[1] = y; if (z > mx[2]) mx[2] = z
                v[o] = x; v[o + 1] = y; v[o + 2] = z
                val l = sqrt(nrm[i * 3] * nrm[i * 3] + nrm[i * 3 + 1] * nrm[i * 3 + 1] + nrm[i * 3 + 2] * nrm[i * 3 + 2])
                if (l > 1e-8f) { v[o + 3] = nrm[i * 3] / l; v[o + 4] = nrm[i * 3 + 1] / l; v[o + 5] = nrm[i * 3 + 2] / l } else v[o + 5] = 1f
                if (g.uv != null) { v[o + 6] = g.uv[i * 2]; v[o + 7] = g.uv[i * 2 + 1] }
                if (g.col != null) { v[o + 8] = g.col[i * 4]; v[o + 9] = g.col[i * 4 + 1]; v[o + 10] = g.col[i * 4 + 2]; v[o + 11] = 1f }
                else { v[o + 8] = 1f; v[o + 9] = 1f; v[o + 10] = 1f; v[o + 11] = 1f }
            }
            for (mi in 0 until g.mats.size) {
                var cnt = 0
                for (t in 0 until nTri) if (g.tri[t * 4 + 3] == mi && g.tri[t * 4] < g.n && g.tri[t * 4 + 1] < g.n && g.tri[t * 4 + 2] < g.n) cnt++
                if (cnt == 0) continue
                val idx = ShortArray(cnt * 3); var k = 0
                for (t in 0 until nTri) if (g.tri[t * 4 + 3] == mi && g.tri[t * 4] < g.n && g.tri[t * 4 + 1] < g.n && g.tri[t * 4 + 2] < g.n) {
                    idx[k++] = (off + g.tri[t * 4]).toShort(); idx[k++] = (off + g.tri[t * 4 + 1]).toShort(); idx[k++] = (off + g.tri[t * 4 + 2]).toShort()
                }
                val m = g.mats[mi]
                parts.add(PartData(idx, m.col, m.tex?.let { tex(it.lowercase()) }))
            }
            off += g.n
        }
        if (parts.isEmpty()) return null
        return ModelData(v, parts, mn, mx)
    } catch (e: Exception) { return null }
}

// ---------- TXD ----------
private val FF: Byte = 0xFF.toByte()

internal fun decodeDxt(bb: ByteBuffer, off: Int, w: Int, h: Int, kind: Int, out: ByteArray) {
    val bw = (w + 3) / 4; val bh = (h + 3) / 4
    val bs = if (kind == 1) 8 else 16
    val pal = IntArray(16); val al = IntArray(16); val tb = IntArray(8)
    for (by in 0 until bh) for (bx in 0 until bw) {
        val b = off + (by * bw + bx) * bs
        val cb = if (kind == 1) b else b + 8
        val c0 = bb.getShort(cb).toInt() and 0xFFFF; val c1 = bb.getShort(cb + 2).toInt() and 0xFFFF
        pal[0] = ((c0 shr 11) and 31) * 255 / 31; pal[1] = ((c0 shr 5) and 63) * 255 / 63; pal[2] = (c0 and 31) * 255 / 31; pal[3] = 255
        pal[4] = ((c1 shr 11) and 31) * 255 / 31; pal[5] = ((c1 shr 5) and 63) * 255 / 63; pal[6] = (c1 and 31) * 255 / 31; pal[7] = 255
        if (c0 > c1 || kind != 1) {
            for (k in 0..2) { pal[8 + k] = (2 * pal[k] + pal[4 + k]) / 3; pal[12 + k] = (pal[k] + 2 * pal[4 + k]) / 3 }
            pal[11] = 255; pal[15] = 255
        } else {
            for (k in 0..2) { pal[8 + k] = (pal[k] + pal[4 + k]) / 2; pal[12 + k] = 0 }
            pal[11] = 255; pal[15] = 0
        }
        if (kind == 3) {
            for (i in 0 until 16) { val by2 = bb.get(b + i / 2).toInt() and 0xFF; al[i] = (if ((i and 1) == 0) by2 and 15 else by2 shr 4) * 17 }
        } else if (kind == 5) {
            val a0 = bb.get(b).toInt() and 0xFF; val a1 = bb.get(b + 1).toInt() and 0xFF
            var bits = 0L
            for (k in 0 until 6) bits = bits or ((bb.get(b + 2 + k).toLong() and 0xFF) shl (8 * k))
            tb[0] = a0; tb[1] = a1
            if (a0 > a1) { for (k in 1..6) tb[1 + k] = ((7 - k) * a0 + k * a1) / 7 }
            else { for (k in 1..4) tb[1 + k] = ((5 - k) * a0 + k * a1) / 5; tb[6] = 0; tb[7] = 255 }
            for (i in 0 until 16) al[i] = tb[((bits shr (3 * i)) and 7L).toInt()]
        }
        val bits = bb.getInt(cb + 4)
        for (i in 0 until 16) {
            val x = bx * 4 + (i and 3); val y = by * 4 + (i shr 2)
            if (x < w && y < h) {
                val ci = (bits ushr (2 * i)) and 3
                val o = (y * w + x) * 4
                out[o] = pal[ci * 4].toByte(); out[o + 1] = pal[ci * 4 + 1].toByte(); out[o + 2] = pal[ci * 4 + 2].toByte()
                out[o + 3] = (if (kind == 1) pal[ci * 4 + 3] else al[i]).toByte()
            }
        }
    }
}

class TxdFile(val name: String, data: ByteArray) {
    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val size = data.size
    private class Raw(val name: String, val w: Int, val h: Int, val raster: Int, val d3d: Int, val depth: Int, val pal: Int, val data: Int)
    private val items = HashMap<String, Raw>()

    init { try { parse() } catch (e: Exception) { } }

    private fun parse() {
        val root = bb.ck(0) ?: return
        bb.kids(root) { c ->
            if (c.type == 0x15) {
                val s = bb.ck(c.s)
                if (s != null && bb.getInt(s.s) == 9) {
                    val p = s.s
                    val nm = bb.str(p + 8, 32).lowercase()
                    val raster = bb.getInt(p + 72); val d3d = bb.getInt(p + 76)
                    val w = bb.getShort(p + 80).toInt() and 0xFFFF; val h = bb.getShort(p + 82).toInt() and 0xFFFF
                    val depth = bb.get(p + 84).toInt() and 0xFF
                    var q = p + 88; var pal = -1
                    if ((raster and 0x2000) != 0) { pal = q; q += 1024 } else if ((raster and 0x4000) != 0) { pal = q; q += 128 }
                    items[nm] = Raw(nm, w, h, raster, d3d, depth, pal, q + 4)
                }
            }
        }
    }

    fun decode(texName: String): TexData? {
        val r = items[texName.lowercase()] ?: return null
        val w = r.w; val h = r.h
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null
        val out = ByteArray(w * h * 4)
        val dxt = when (r.d3d) { 0x31545844 -> 1; 0x33545844 -> 3; 0x35545844 -> 5; else -> 0 }
        val o = r.data
        try {
            when {
                dxt != 0 -> decodeDxt(bb, o, w, h, dxt, out)
                (r.raster and 0x2000) != 0 -> for (i in 0 until w * h) {
                    val pp = r.pal + (bb.get(o + i).toInt() and 0xFF) * 4
                    out[i * 4] = bb.get(pp); out[i * 4 + 1] = bb.get(pp + 1); out[i * 4 + 2] = bb.get(pp + 2); out[i * 4 + 3] = bb.get(pp + 3)
                }
                (r.raster and 0x4000) != 0 -> return null
                r.depth == 32 -> {
                    val opaque = (r.raster and 0xF00) == 0x600
                    for (i in 0 until w * h) {
                        val s = o + i * 4
                        out[i * 4] = bb.get(s + 2); out[i * 4 + 1] = bb.get(s + 1); out[i * 4 + 2] = bb.get(s)
                        out[i * 4 + 3] = if (opaque) FF else bb.get(s + 3)
                    }
                }
                r.depth == 16 -> for (i in 0 until w * h) {
                    val v = bb.getShort(o + i * 2).toInt() and 0xFFFF
                    var rr: Int; var gg: Int; var bl: Int; var aa = 255
                    when (r.raster and 0xF00) {
                        0x200 -> { rr = ((v shr 11) and 31) * 255 / 31; gg = ((v shr 5) and 63) * 255 / 63; bl = (v and 31) * 255 / 31 }
                        0x300 -> { aa = ((v shr 12) and 15) * 17; rr = ((v shr 8) and 15) * 17; gg = ((v shr 4) and 15) * 17; bl = (v and 15) * 17 }
                        else -> {
                            if ((r.raster and 0xF00) == 0x100 && (v and 0x8000) == 0) aa = 0
                            rr = ((v shr 10) and 31) * 255 / 31; gg = ((v shr 5) and 31) * 255 / 31; bl = (v and 31) * 255 / 31
                        }
                    }
                    out[i * 4] = rr.toByte(); out[i * 4 + 1] = gg.toByte(); out[i * 4 + 2] = bl.toByte(); out[i * 4 + 3] = aa.toByte()
                }
                else -> return null
            }
        } catch (e: Exception) { return null }
        return TexData(name + "/" + r.name, w, h, out)
    }
}

// ---------- Game data (gta3.img + IDE) ----------
class GameData private constructor(private val fis: FileInputStream, private val pfd: ParcelFileDescriptor) {
    private val ch: FileChannel = fis.channel
    private val imgIndex = HashMap<String, ImgEntry>()
    val defs = HashMap<String, ObjDef>()
    val defList = ArrayList<ObjDef>()
    val defById = HashMap<Int, ObjDef>()
    private val txdParent = HashMap<String, String>()
    private val txds = object : LruCache<String, TxdFile>(48 * 1024) {
        override fun sizeOf(key: String, value: TxdFile): Int = max(1, value.size / 1024)
    }
    private val texs = object : LruCache<String, TexData>(64 * 1024) {
        override fun sizeOf(key: String, value: TexData): Int = max(1, value.rgba.size / 1024)
    }
    private val models = LruCache<String, ModelData>(400)
    private val exec = Executors.newFixedThreadPool(2)
    val pending = AtomicInteger(0)
    var idsKnown = true
    val reasons = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    val failedNames = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private fun why(r: String) { reasons.getOrPut(r) { AtomicInteger() }.incrementAndGet() }

    private fun readFully(buf: ByteBuffer, pos: Long) {
        var p = pos
        while (buf.hasRemaining()) { val n = ch.read(buf, p); if (n < 0) break; p += n }
    }

    private fun readIndex() {
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN); readFully(h, 0); h.flip()
        val magic = h.getInt()
        if (magic != 0x32524556) throw IllegalStateException("gta3.img bukan format VER2 (magic 0x" + Integer.toHexString(magic) + ")")
        val n = h.getInt()
        if (n <= 0 || n > 200000) throw IllegalStateException("Index gta3.img tidak valid")
        val t = ByteBuffer.allocate(n * 32).order(ByteOrder.LITTLE_ENDIAN); readFully(t, 8); t.flip()
        repeat(n) {
            val off = t.getInt().toLong() and 0xFFFFFFFFL
            val a = t.getShort().toInt() and 0xFFFF; val b = t.getShort().toInt() and 0xFFFF
            val nb = ByteArray(24); t.get(nb)
            var len = 0
            while (len < 24 && nb[len].toInt() != 0) len++
            imgIndex[String(nb, 0, len, Charsets.ISO_8859_1).lowercase()] = ImgEntry(off * 2048, max(a, b) * 2048)
        }
    }

    private fun parseIde(text: String) {
        var sec = ""
        for (raw in text.lineSequence()) {
            val l = raw.substringBefore('#').trim()
            if (l.isEmpty()) continue
            if (sec.isEmpty()) { sec = l.lowercase(); continue }
            if (l.equals("end", true)) { sec = ""; continue }
            val p = l.split(',').map { it.trim() }
            when (sec) {
                "objs", "tobj", "anim" -> if (p.size >= 3) {
                    val id = p[0].toIntOrNull()
                    if (id != null) { val d = ObjDef(id, p[1], p[2]); defById[id] = d; if (defs.put(d.lc, d) == null) defList.add(d) }
                }
                "txdp" -> if (p.size >= 2) txdParent[p[0].lowercase()] = p[1].lowercase()
            }
        }
    }

    private fun readEntry(name: String): ByteArray? {
        val e = imgIndex[name.lowercase()] ?: return null
        val buf = ByteBuffer.allocate(e.size)
        readFully(buf, e.offset)
        return buf.array()
    }

    private fun txd(name: String): TxdFile? =
        txds.get(name) ?: readEntry("$name.txd")?.let { TxdFile(name, it) }?.also { txds.put(name, it) }

    private fun texture(x: TxdFile, tn: String): TexData? {
        val key = x.name + "/" + tn
        return texs.get(key) ?: x.decode(tn)?.also { texs.put(key, it) }
    }

    fun loadModel(key: String): ModelData? {
        models.get(key)?.let { return it }
        val bytes = readEntry("$key.dff") ?: run { why("tidak ada di gta3.img"); return null }
        val chain = ArrayList<TxdFile>()
        var t: String? = defs[key]?.txd?.lowercase()
        var guard = 0
        while (t != null && guard++ < 4) { txd(t)?.let { chain.add(it) }; t = txdParent[t] }
        if (chain.none { it.name == "generic" }) txd("generic")?.let { chain.add(it) }
        val md = parseDff(bytes) { tn ->
            var r: TexData? = dbTexture(tn)
            if (r == null) for (x in chain) { r = texture(x, tn); if (r != null) break }
            r
        } ?: run { why("parse DFF gagal"); return null }
        models.put(key, md)
        return md
    }

    fun request(key: String, done: (String, ModelData?) -> Unit) {
        pending.incrementAndGet()
        exec.execute {
            var md: ModelData? = null
            try { md = loadModel(key) } catch (t: Throwable) { }
            pending.decrementAndGet()
            if (md != null) okCount.incrementAndGet() else failCount.incrementAndGet()
            if (md == null && failedNames.size < 12) failedNames.add(key)
            done(key, md)
        }
    }

    val texdbs = ArrayList<TexDb>()
    val okCount = AtomicInteger(0)
    val failCount = AtomicInteger(0)
    private val dbTex = object : LruCache<String, TexData>(64 * 1024) {
        override fun sizeOf(key: String, value: TexData): Int = max(1, value.rgba.size / 1024)
    }
    private val dbMissing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun dbTexture(name: String): TexData? {
        if (texdbs.isEmpty()) return null
        val k = name.lowercase()
        dbTex.get(k)?.let { return it }
        if (dbMissing.contains(k)) return null
        for (db in texdbs) {
            val t = db.get(k)
            if (t != null) { dbTex.put(k, t); return t }
        }
        dbMissing.add(k)
        return null
    }

    /** Binary stream IPLs inside gta3.img (<base>_stream<N>.ipl). base == null loads every stream file. */
    fun streamObjects(base: String?, known: Map<Int, String>): Pair<List<Obj>, String> {
        val names = imgIndex.keys.filter { it.endsWith(".ipl") && it.contains("_stream") && (base == null || it.startsWith(base + "_stream")) }.sorted()
        if (names.isEmpty()) return Pair(emptyList(), "Tidak ada file " + (base ?: "*") + "_stream*.ipl di gta3.img")
        val out = ArrayList<Obj>()
        var noName = 0
        for (n in names) {
            val b = readEntry(n) ?: continue
            val r = parseBinaryIpl(b, defById, known)
            out.addAll(r.first); noName += r.second
        }
        return Pair(out, names.size.toString() + " file stream, " + out.size + " objek" + (if (noName > 0) ", " + noName + " tanpa nama (IDE tidak ada)" else ""))
    }

    fun summary(): String = defList.size.toString() + " model IDE, " + texdbs.size + " database tekstur"

    var report: String = ""

    fun diagnose(): String {
        val sb = StringBuilder(report).append("\n")
        for (db in texdbs) sb.append(db.report(6))
        sb.append("Alasan gagal model: ").append(reasons.entries.joinToString { it.key + "=" + it.value.get() }).append("\nContoh gagal: ").append(failedNames.joinToString(", ")).append("\n")
        val dffs = imgIndex.keys.filter { it.endsWith(".dff") && !it.startsWith("lod") }.sorted()
        sb.append("Uji parse DFF:\n")
        val stepN = max(1, dffs.size / 5)
        for (n in dffs.filterIndexed { i, _ -> i % stepN == 0 }.take(5)) {
            val b = readEntry(n) ?: continue
            sb.append(n).append(" (").append(b.size).append(" B) ").append(inspectDff(b)).append("\n")
            val md = try { parseDff(b) { _ -> null } } catch (e: Throwable) { null }
            sb.append("  -> ").append(if (md == null) "GAGAL parse" else "OK: " + (md.verts.size / 12) + " vertex, " + md.parts.size + " bagian").append("\n")
        }
        return sb.toString()
    }

    companion object {
        @Volatile var lastReport: String = ""

        fun open(ctx: Context, tree: Uri): GameData {
            val rep = StringBuilder()
            lastReport = ""
            try {
                val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Folder tidak valid")
                val top = root.listFiles()
                rep.append("Folder: ").append(root.name).append("\nIsi (").append(top.size).append("): ")
                rep.append(top.take(40).joinToString(", ") { (it.name ?: "?") + (if (it.isDirectory) "/" else "") }).append("\n")
                if (top.isEmpty()) throw IllegalStateException("Folder kosong atau tidak terbaca. Android 11+ memblokir Android/data; salin folder game ke penyimpanan internal (mis. Download/GTASA) lalu pilih dari sana.")
                var img: DocumentFile? = null
                var texdb: DocumentFile? = null
                val ides = ArrayList<DocumentFile>()
                val exts = HashMap<String, Int>()
                val dirs = ArrayList<String>()
                val skip = setOf("audio", "movies", "text", "anim", "sfx", "fonts")
                fun walk(d: DocumentFile, depth: Int) {
                    for (f in d.listFiles()) {
                        val n = f.name?.lowercase() ?: continue
                        if (f.isDirectory) {
                            if (dirs.size < 40) dirs.add(n)
                            if (n == "texdb") texdb = f else if (depth < 6 && n !in skip) walk(f, depth + 1)
                        } else {
                            val ext = n.substringAfterLast('.', "")
                            exts[ext] = (exts[ext] ?: 0) + 1
                            if (n == "gta3.img") { if (img == null) img = f } else if (ext == "ide") ides.add(f)
                        }
                    }
                }
                walk(root, 0)
                rep.append("Folder ditelusuri: ").append(dirs.joinToString(", ")).append("\n")
                rep.append("Ekstensi file: ").append(exts.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { it.key + "=" + it.value }).append("\n")
                rep.append("gta3.img: ").append(if (img != null) "ditemukan" else "TIDAK ditemukan").append(" | file .ide: ").append(ides.size).append("\n")

                val dbs = ArrayList<TexDb>()
                val tdb = texdb
                if (tdb != null) {
                    val files = ArrayList<DocumentFile>()
                    fun collect(d: DocumentFile, depth: Int) {
                        for (f in d.listFiles()) { if (f.isDirectory) { if (depth < 3) collect(f, depth + 1) } else files.add(f) }
                    }
                    collect(tdb, 0)
                    val byName = HashMap<String, DocumentFile>()
                    for (f in files) { val n = f.name?.lowercase() ?: continue; if (!byName.containsKey(n)) byName[n] = f }
                    rep.append("texdb: ").append(files.size).append(" file: ").append(files.take(16).joinToString(", ") { it.name ?: "?" }).append("\n")
                    val pri = listOf("etc", "dxt", "unc", "pvr")
                    val bases = HashMap<String, String>()
                    val re = Regex("^(.+)\\.(etc|dxt|pvr|unc)\\.dat$")
                    for (n in byName.keys) {
                        val m = re.find(n) ?: continue
                        val b = m.groupValues[1]; val fm = m.groupValues[2]
                        val cur = bases[b]
                        if (cur == null || pri.indexOf(fm) < pri.indexOf(cur)) bases[b] = fm
                    }
                    for ((b, fm) in bases) {
                        val dat = byName["$b.$fm.dat"]; val txt = byName["$b.txt"]
                        if (dat == null || txt == null) { rep.append("DB $b.$fm dilewati (txt/dat tidak lengkap)\n"); continue }
                        try { dbs.add(TexDb(ctx, b, fm, dat, byName["$b.$fm.toc"], txt)); rep.append("DB $b.$fm dimuat\n") }
                        catch (e: Exception) { rep.append("DB $b.$fm gagal: ").append(e.message).append("\n") }
                    }
                } else rep.append("texdb: tidak ada\n")

                val image = img ?: throw IllegalStateException("gta3.img tidak ditemukan. Pilih folder game yang berisi gta3.img (lihat Diagnosa file game).")
                val pfd = ctx.contentResolver.openFileDescriptor(image.uri, "r") ?: throw IllegalStateException("Gagal membuka gta3.img")
                val g = GameData(FileInputStream(pfd.fileDescriptor), pfd)
                g.readIndex()
                g.texdbs.addAll(dbs)
                val names = g.imgIndex.keys
                rep.append("Entri gta3.img: ").append(names.size).append(" (dff ").append(names.count { it.endsWith(".dff") })
                    .append(", txd ").append(names.count { it.endsWith(".txd") }).append(", col ").append(names.count { it.endsWith(".col") })
                    .append(", ide ").append(names.count { it.endsWith(".ide") }).append(", ipl ").append(names.count { it.endsWith(".ipl") }).append(")\n")
                rep.append("Contoh: ").append(names.take(6).joinToString(", ")).append("\n")
                for (f in ides) ctx.contentResolver.openInputStream(f.uri)?.use { g.parseIde(it.bufferedReader().readText()) }
                for (n in names.filter { it.endsWith(".ide") }) g.readEntry(n)?.let { g.parseIde(String(it, Charsets.ISO_8859_1).replace("\u0000", "")) }
                if (g.defList.isEmpty()) {
                    for (n in names.filter { it.endsWith(".dff") }) { val d = ObjDef(0, n.removeSuffix(".dff"), ""); if (g.defs.put(d.lc, d) == null) g.defList.add(d) }
                    g.idsKnown = false
                    rep.append("IDE tidak ditemukan: daftar model diambil dari nama DFF (ID = 0, isi manual)\n")
                }
                g.defList.sortBy { it.lc }
                rep.append("Model terdaftar: ").append(g.defList.size).append("\n")
                g.report = rep.toString()
                return g
            } catch (e: Exception) {
                rep.append("ERROR: ").append(e.message).append("\n")
                throw e
            } finally {
                lastReport = rep.toString()
            }
        }
    }
}

fun inspectDff(data: ByteArray): String {
    try {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val root = bb.ck(0) ?: return "chunk root tidak valid"
        val sb = StringBuilder("root=0x" + Integer.toHexString(root.type) + " ver=0x" + Integer.toHexString(rwVer(root.ver)) + " anak=")
        var geo = ""
        bb.kids(root) { c ->
            sb.append("0x").append(Integer.toHexString(c.type)).append(' ')
            if (c.type == 0x1A) bb.kids(c) { g ->
                if (g.type == 0x0F && geo.isEmpty()) {
                    val st = bb.ck(g.s)
                    if (st != null) {
                        geo = "| geo flags=0x" + Integer.toHexString(bb.getInt(st.s)) + " tri=" + bb.getInt(st.s + 4) + " vert=" + bb.getInt(st.s + 8)
                        val ex = StringBuilder(" ext=")
                        bb.kids(g) { k -> if (k.type == 0x03) bb.kids(k) { m -> ex.append("0x").append(Integer.toHexString(m.type)).append(' ') } }
                        geo += ex.toString()
                    }
                }
            }
        }
        return sb.toString() + geo
    } catch (e: Exception) { return "error: " + e.message }
}

/** Binary IPL ("bnry"): 40-byte instance records (pos3, rot4, id, interior flags, lod index). */
fun parseBinaryIpl(data: ByteArray, defById: Map<Int, ObjDef>, known: Map<Int, String>): Pair<List<Obj>, Int> {
    try {
        if (data.size < 0x20) return Pair(emptyList(), 0)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != 0x79726E62) return Pair(emptyList(), 0)
        val n = bb.getInt(4); val off = bb.getInt(0x1C)
        if (n < 0 || n > 100000 || off < 0 || off.toLong() + n.toLong() * 40 > data.size) return Pair(emptyList(), 0)
        val out = ArrayList<Obj>(n)
        var noName = 0
        for (i in 0 until n) {
            val p = off + i * 40
            val x = bb.getFloat(p); val y = bb.getFloat(p + 4); val z = bb.getFloat(p + 8)
            if (x.isNaN() || y.isNaN() || z.isNaN() || Math.abs(x) > 20000f || Math.abs(y) > 20000f) continue
            val id = bb.getInt(p + 28)
            val name = defById[id]?.model ?: known[id]
            if (name == null) noName++
            out.add(Obj(id, name ?: ("id" + id), bb.getInt(p + 32) and 0xFF, x, y, z,
                bb.getFloat(p + 12), bb.getFloat(p + 16), bb.getFloat(p + 20), bb.getFloat(p + 24), bb.getInt(p + 36)))
        }
        return Pair(out, noName)
    } catch (e: Exception) { return Pair(emptyList(), 0) }
}
