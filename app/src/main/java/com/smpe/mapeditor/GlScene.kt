package com.smpe.mapeditor

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class SceneObj(val idx: Int, val o: Obj) {
    val key = o.model.lowercase()
    val col = modelColor(o.model).let { floatArrayOf(it.red, it.green, it.blue) }
}

class GpuPart(val ibo: Int, val count: Int, val tex: Int, val col: FloatArray)
class GpuModel(val vbo: Int, val parts: List<GpuPart>)

/** Column-major model matrix. IPL quaternions in SA are stored conjugated. */
fun objMatrix(m: FloatArray, o: Obj) {
    var qx = -o.rx; var qy = -o.ry; var qz = -o.rz; var qw = o.rw
    val l = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
    if (l < 1e-6f) { qx = 0f; qy = 0f; qz = 0f; qw = 1f } else { qx /= l; qy /= l; qz /= l; qw /= l }
    val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
    val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
    val wx = qw * qx; val wy = qw * qy; val wz = qw * qz
    m[0] = 1 - 2 * (yy + zz); m[1] = 2 * (xy + wz); m[2] = 2 * (xz - wy); m[3] = 0f
    m[4] = 2 * (xy - wz); m[5] = 1 - 2 * (xx + zz); m[6] = 2 * (yz + wx); m[7] = 0f
    m[8] = 2 * (xz + wy); m[9] = 2 * (yz - wx); m[10] = 1 - 2 * (xx + yy); m[11] = 0f
    m[12] = o.x; m[13] = o.y; m[14] = o.z; m[15] = 1f
}

private const val VS = """
uniform mat4 uVP; uniform mat4 uM;
attribute vec3 aPos; attribute vec3 aNrm; attribute vec2 aUV; attribute vec4 aCol;
varying vec3 vN; varying vec2 vUV; varying vec4 vC;
void main() {
  gl_Position = uVP * (uM * vec4(aPos, 1.0));
  vN = (uM * vec4(aNrm, 0.0)).xyz; vUV = aUV; vC = aCol;
}"""
private const val FS = """
precision mediump float;
uniform sampler2D uTex; uniform vec4 uCol; uniform float uSel;
varying vec3 vN; varying vec2 vUV; varying vec4 vC;
void main() {
  vec4 t = texture2D(uTex, vUV);
  if (t.a < 0.3) discard;
  float l = 0.55 + 0.45 * abs(dot(normalize(vN), normalize(vec3(0.4, -0.3, 0.85))));
  gl_FragColor = vec4(t.rgb * vC.rgb * uCol.rgb * l + uSel * vec3(0.30, 0.16, 0.0), 1.0);
}"""
private const val LVS = "uniform mat4 uVP; attribute vec3 aPos; void main() { gl_Position = uVP * vec4(aPos, 1.0); }"
private const val LFS = "precision mediump float; uniform vec4 uCol; void main() { gl_FragColor = uCol; }"

class GlScene(private val game: GameData) : GLSurfaceView.Renderer {
    @Volatile var scene: List<SceneObj> = emptyList()
    @Volatile var sel = -1
    @Volatile var tx = 0f
    @Volatile var ty = 0f
    @Volatile var tz = 0f
    @Volatile var yaw = 0.5f
    @Volatile var pitch = 0.7f
    @Volatile var dist = 150f
    @Volatile var viewDist = 500f
    @Volatile var hideLod = false
    @Volatile var view: GLSurfaceView? = null
    @Volatile private var vpOut = FloatArray(16)
    @Volatile private var vis: List<SceneObj> = emptyList()
    @Volatile private var vw = 1
    @Volatile private var vh = 1

    private var prog = 0; private var lprog = 0
    private var uVP = 0; private var uM = 0; private var uTex = 0; private var uCol = 0; private var uSel = 0
    private var luVP = 0; private var luCol = 0
    private var white = 0
    private var cube: GpuModel? = null
    private val cubeKey = "#cube"
    private val models = HashMap<String, GpuModel?>()
    private val texIds = HashMap<String, Int>()
    private val requested = HashSet<String>()
    private val bounds = ConcurrentHashMap<String, FloatArray>()
    private val ready = ConcurrentLinkedQueue<Pair<String, ModelData?>>()
    private val lineBuf: FloatBuffer = ByteBuffer.allocateDirect(512 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val proj = FloatArray(16); private val vm4 = FloatArray(16); private val vp = FloatArray(16); private val mm = FloatArray(16)

    fun requestRender() { view?.requestRender() }

    private fun program(vs: String, fs: String, attrs: List<String>): Int {
        fun sh(type: Int, src: String): Int { val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        attrs.forEachIndexed { i, n -> GLES20.glBindAttribLocation(p, i, n) }
        GLES20.glLinkProgram(p)
        return p
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        prog = program(VS, FS, listOf("aPos", "aNrm", "aUV", "aCol"))
        uVP = GLES20.glGetUniformLocation(prog, "uVP"); uM = GLES20.glGetUniformLocation(prog, "uM")
        uTex = GLES20.glGetUniformLocation(prog, "uTex"); uCol = GLES20.glGetUniformLocation(prog, "uCol"); uSel = GLES20.glGetUniformLocation(prog, "uSel")
        lprog = program(LVS, LFS, listOf("aPos"))
        luVP = GLES20.glGetUniformLocation(lprog, "uVP"); luCol = GLES20.glGetUniformLocation(lprog, "uCol")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); white = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, white)
        val px = ByteBuffer.allocateDirect(4); px.put(byteArrayOf(-1, -1, -1, -1)); px.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        models.clear(); texIds.clear(); requested.clear()
        cube = upload(cubeData(), cubeKey)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height); vw = max(1, width); vh = max(1, height)
    }

    private fun cubeData(): ModelData {
        val h = 2f
        val verts = FloatArray(24 * 12); val idx = ShortArray(36)
        var vi = 0; var ii = 0
        for (a in 0..2) for (s in intArrayOf(-1, 1)) {
            val nrm = FloatArray(3); nrm[a] = s.toFloat()
            val u = FloatArray(3); u[(a + 1) % 3] = 1f
            val v = FloatArray(3); v[(a + 2) % 3] = 1f
            val base = vi
            for ((cu, cv) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
                val o = vi * 12
                for (k in 0..2) { verts[o + k] = nrm[k] * h + u[k] * cu * h + v[k] * cv * h; verts[o + 3 + k] = nrm[k] }
                for (k in 8..11) verts[o + k] = 1f
                vi++
            }
            for (k in intArrayOf(0, 1, 2, 0, 2, 3)) idx[ii++] = (base + k).toShort()
        }
        return ModelData(verts, listOf(PartData(idx, floatArrayOf(1f, 1f, 1f, 1f), null)), floatArrayOf(-h, -h, -h), floatArrayOf(h, h, h))
    }

    private fun floatBuf(a: FloatArray): FloatBuffer { val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); b.put(a); b.position(0); return b }
    private fun shortBuf(a: ShortArray): ShortBuffer { val b = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer(); b.put(a); b.position(0); return b }

    private fun texId(t: TexData?): Int {
        if (t == null) return 0
        texIds[t.key]?.let { return it }
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val bb = ByteBuffer.allocateDirect(t.rgba.size); bb.put(t.rgba); bb.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, t.w, t.h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        val pot = (t.w and (t.w - 1)) == 0 && (t.h and (t.h - 1)) == 0
        if (pot) {
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        } else {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        texIds[t.key] = ids[0]
        return ids[0]
    }

    private fun upload(md: ModelData, key: String): GpuModel {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0); val vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, md.verts.size * 4, floatBuf(md.verts), GLES20.GL_STATIC_DRAW)
        val parts = md.parts.map { p ->
            GLES20.glGenBuffers(1, ids, 0); val ibo = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.idx.size * 2, shortBuf(p.idx), GLES20.GL_STATIC_DRAW)
            GpuPart(ibo, p.idx.size, texId(p.tex), p.col)
        }
        bounds[key] = floatArrayOf(md.min[0], md.min[1], md.min[2], md.max[0], md.max[1], md.max[2])
        return GpuModel(vbo, parts)
    }

    private fun purge() {
        val b = IntArray(1)
        for (gm in models.values) if (gm != null) {
            b[0] = gm.vbo; GLES20.glDeleteBuffers(1, b, 0)
            for (p in gm.parts) { b[0] = p.ibo; GLES20.glDeleteBuffers(1, b, 0) }
        }
        for (t in texIds.values) { b[0] = t; GLES20.glDeleteTextures(1, b, 0) }
        models.clear(); texIds.clear(); requested.clear()
    }

    private fun request(key: String) {
        if (!requested.add(key)) return
        if (game.pending.get() >= 10) { requested.remove(key); return }
        game.request(key) { k, md -> ready.add(k to md); view?.requestRender() }
    }

    private fun draw(gm: GpuModel, m: FloatArray, tint: FloatArray?, selected: Boolean, oc: FloatArray) {
        GLES20.glUniformMatrix4fv(uM, 1, false, m, 0)
        GLES20.glUniform1f(uSel, if (selected) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gm.vbo)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 48, 0)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 48, 12)
        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, 48, 24)
        GLES20.glVertexAttribPointer(3, 4, GLES20.GL_FLOAT, false, 48, 32)
        for (p in gm.parts) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (p.tex != 0) p.tex else white)
            if (tint != null) GLES20.glUniform4f(uCol, tint[0], tint[1], tint[2], 1f)
            else if (p.tex == 0) GLES20.glUniform4f(uCol, p.col[0] * (0.55f + 0.45f * oc[0]), p.col[1] * (0.55f + 0.45f * oc[1]), p.col[2] * (0.55f + 0.45f * oc[2]), 1f)
            else GLES20.glUniform4f(uCol, p.col[0], p.col[1], p.col[2], 1f)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, p.ibo)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.count, GLES20.GL_UNSIGNED_SHORT, 0)
        }
    }

    private fun lines(pts: FloatArray, r: Float, g: Float, b: Float) {
        lineBuf.clear(); lineBuf.put(pts); lineBuf.position(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 0, lineBuf)
        GLES20.glUniform4f(luCol, r, g, b, 1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, pts.size / 3)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClearColor(0.38f, 0.55f, 0.78f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (models.size > 900 || texIds.size > 1500) purge()
        var up = 0
        while (up < 3) { val r = ready.poll() ?: break; models[r.first] = r.second?.let { upload(it, r.first) }; up++ }

        val d = dist; val cp = cos(pitch); val sp = sin(pitch)
        val cx = tx + d * cp * sin(yaw); val cy = ty - d * cp * cos(yaw); val cz = tz + d * sp
        Matrix.perspectiveM(proj, 0, 60f, vw.toFloat() / vh, max(0.3f, d * 0.01f), max(viewDist * 3f + 500f, d * 4f))
        Matrix.setLookAtM(vm4, 0, cx, cy, cz, tx, ty, tz, 0f, 0f, 1f)
        Matrix.multiplyMM(vp, 0, proj, 0, vm4, 0)
        vpOut = vp.copyOf()
        val fx = (tx - cx) / d; val fy = (ty - cy) / d; val fz = (tz - cz) / d

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        for (a in 0..3) GLES20.glEnableVertexAttribArray(a)

        val list = ArrayList<SceneObj>()
        var selObj: SceneObj? = null
        val s = sel
        for (so in scene) {
            val o = so.o
            if (so.idx == s) selObj = so
            val dx = o.x - cx; val dy = o.y - cy; val dz = o.z - cz
            val dd = sqrt(dx * dx + dy * dy + dz * dz)
            if (so.key.startsWith("lod")) { if (hideLod || dd < 250f || dd > viewDist * 3f) continue } else if (dd > viewDist) continue
            if (dx * fx + dy * fy + dz * fz < -120f) continue
            list.add(so)
            objMatrix(mm, o)
            val gm = if (models.containsKey(so.key)) models[so.key] else { request(so.key); null }
            if (gm != null) draw(gm, mm, null, so.idx == s, so.col) else cube?.let { draw(it, mm, so.col, so.idx == s, so.col) }
        }
        vis = list

        val so = selObj
        if (so != null) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(lprog)
            GLES20.glUniformMatrix4fv(luVP, 1, false, vp, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            for (a in 1..3) GLES20.glDisableVertexAttribArray(a)
            GLES20.glLineWidth(3f)
            val o = so.o
            val b = bounds[so.key] ?: bounds[cubeKey] ?: floatArrayOf(-2f, -2f, -2f, 2f, 2f, 2f)
            objMatrix(mm, o)
            val c = FloatArray(24)
            for (i in 0..7) {
                val lx = if ((i and 1) == 0) b[0] else b[3]; val ly = if ((i and 2) == 0) b[1] else b[4]; val lz = if ((i and 4) == 0) b[2] else b[5]
                c[i * 3] = mm[0] * lx + mm[4] * ly + mm[8] * lz + mm[12]
                c[i * 3 + 1] = mm[1] * lx + mm[5] * ly + mm[9] * lz + mm[13]
                c[i * 3 + 2] = mm[2] * lx + mm[6] * ly + mm[10] * lz + mm[14]
            }
            val edges = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7, 0, 4, 1, 5, 2, 6, 3, 7)
            val pts = FloatArray(edges.size * 3)
            for ((k, e) in edges.withIndex()) { pts[k * 3] = c[e * 3]; pts[k * 3 + 1] = c[e * 3 + 1]; pts[k * 3 + 2] = c[e * 3 + 2] }
            lines(pts, 1f, 0.15f, 0.15f)
            val ddx = o.x - cx; val ddy = o.y - cy; val ddz = o.z - cz
            val len = max(1.5f, sqrt(ddx * ddx + ddy * ddy + ddz * ddz) * 0.15f)
            lines(floatArrayOf(o.x, o.y, o.z, o.x + len, o.y, o.z), 1f, 0.2f, 0.2f)
            lines(floatArrayOf(o.x, o.y, o.z, o.x, o.y + len, o.z), 0.2f, 1f, 0.2f)
            lines(floatArrayOf(o.x, o.y, o.z, o.x, o.y, o.z + len), 0.3f, 0.5f, 1f)
        }
        if (game.pending.get() > 0 || !ready.isEmpty()) view?.requestRender()
    }

    private fun slab(o: FloatArray, d: FloatArray, mn: FloatArray, mx: FloatArray): Float {
        var t0 = 0f; var t1 = Float.MAX_VALUE
        for (i in 0..2) {
            if (abs(d[i]) < 1e-9f) { if (o[i] < mn[i] || o[i] > mx[i]) return -1f }
            else {
                var a = (mn[i] - o[i]) / d[i]; var b = (mx[i] - o[i]) / d[i]
                if (a > b) { val t = a; a = b; b = t }
                if (a > t0) t0 = a
                if (b < t1) t1 = b
                if (t0 > t1) return -1f
            }
        }
        return t0
    }

    /** Returns the entry index of the object under the touch point, or -1. Call from the main thread. */
    fun pick(px: Float, py: Float, slop: Float): Int {
        val vpc = vpOut; val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, vpc, 0)) return -1
        val nx = px / vw * 2f - 1f; val ny = 1f - py / vh * 2f
        fun un(z: Float): FloatArray {
            val o = FloatArray(4); Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(nx, ny, z, 1f), 0)
            return floatArrayOf(o[0] / o[3], o[1] / o[3], o[2] / o[3])
        }
        val a = un(-1f); val b = un(1f)
        val dir = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        var best = -1; var bt = Float.MAX_VALUE
        val m = FloatArray(16)
        val list = vis
        for (so in list) {
            val bd = bounds[so.key] ?: bounds[cubeKey] ?: continue
            objMatrix(m, so.o)
            val dx = a[0] - m[12]; val dy = a[1] - m[13]; val dz = a[2] - m[14]
            val lo = floatArrayOf(m[0] * dx + m[1] * dy + m[2] * dz, m[4] * dx + m[5] * dy + m[6] * dz, m[8] * dx + m[9] * dy + m[10] * dz)
            val ld = floatArrayOf(m[0] * dir[0] + m[1] * dir[1] + m[2] * dir[2], m[4] * dir[0] + m[5] * dir[1] + m[6] * dir[2], m[8] * dir[0] + m[9] * dir[1] + m[10] * dir[2])
            val t = slab(lo, ld, floatArrayOf(bd[0], bd[1], bd[2]), floatArrayOf(bd[3], bd[4], bd[5]))
            if (t >= 0f && t < bt) { bt = t; best = so.idx }
        }
        if (best >= 0) return best
        var bd2 = slop * slop
        for (so in list) {
            val x = so.o.x; val y = so.o.y; val z = so.o.z
            val cw = vpc[3] * x + vpc[7] * y + vpc[11] * z + vpc[15]
            if (cw <= 0.1f) continue
            val sx = ((vpc[0] * x + vpc[4] * y + vpc[8] * z + vpc[12]) / cw * 0.5f + 0.5f) * vw
            val sy = (1f - ((vpc[1] * x + vpc[5] * y + vpc[9] * z + vpc[13]) / cw * 0.5f + 0.5f)) * vh
            val dd = (sx - px) * (sx - px) + (sy - py) * (sy - py)
            if (dd < bd2) { bd2 = dd; best = so.idx }
        }
        return best
    }
}
