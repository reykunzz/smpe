@file:OptIn(ExperimentalMaterial3Api::class)

package com.smpe.mapeditor

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GlEditor(vm: EditorVM, game: GameData) {
    val st = remember(game, vm.fileName) { sceneStats(vm.entries) }
    val gl = remember(game, vm.fileName) {
        GlScene(game).also { s -> s.tx = st[0]; s.ty = st[1]; s.tz = st[2]; s.dist = 150f; vm.gl = s }
    }
    var panMode by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 700.dp) {
            Row(Modifier.fillMaxSize()) {
                Panel(vm, gl, st, Modifier.width(330.dp).fillMaxHeight())
                Viewport(vm, gl, st, panMode, { panMode = it }, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Viewport(vm, gl, st, panMode, { panMode = it }, Modifier.weight(1f).fillMaxWidth())
                Panel(vm, gl, st, Modifier.fillMaxWidth().heightIn(max = 300.dp))
            }
        }
    }
}

@Composable
fun Viewport(vm: EditorVM, gl: GlScene, st: FloatArray, panMode: Boolean, setPan: (Boolean) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current.density
    val pm = rememberUpdatedState(panMode)
    val exScene = remember(vm.extra) { vm.extra.mapIndexed { i, o -> SceneObj(-(i + 2), o) } }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(800); tick++ } }
    Box(modifier.clipToBounds()) {
        AndroidView(
            factory = { c ->
                GLSurfaceView(c).apply {
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(gl)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    gl.view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                val sc = vm.entries.mapIndexedNotNull { i, e -> if (e is Obj) SceneObj(i, e) else null }
                sc.forEachIndexed { k, so -> so.pos = k }
                for (so in sc) { val l = so.o.lod; if (l != null && l >= 0 && l < sc.size) sc[l].isLod = true }
                for (so in exScene) { val l = so.o.lod; if (l != null && l >= 0 && l < sc.size) sc[l].isLod = true }
                gl.scene = sc
                gl.extra = exScene
                gl.sel = vm.sel
                gl.selStream = vm.selStream
                v.requestRender()
            }
        )
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                fun camMove(pan: Offset) {
                    if (!pm.value) {
                        gl.yaw -= pan.x * 0.008f
                        gl.pitch = (gl.pitch + pan.y * 0.008f).coerceIn(0.05f, 1.5f)
                    } else {
                        val k = gl.dist / (minOf(size.width, size.height) * 1.1f)
                        val c = cos(gl.yaw); val s = sin(gl.yaw)
                        gl.tx += -pan.x * k * c - pan.y * k * s
                        gl.ty += -pan.x * k * s + pan.y * k * c
                    }
                    gl.requestRender()
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val axis = if (vm.sel >= 0) gl.hitGizmo(down.position.x, down.position.y, 44f * density) else -1
                    var moved = false
                    var started = false
                    var startVal = 0f
                    var acc = 0f
                    var last = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            moved = true
                            val a = pressed[0]; val b = pressed[1]
                            val span = (a.position - b.position).getDistance()
                            val prev = (a.previousPosition - b.previousPosition).getDistance()
                            if (prev > 1f && span > 1f) gl.dist = (gl.dist * prev / span).coerceIn(5f, 6000f)
                            camMove((a.position + b.position) / 2f - (a.previousPosition + b.previousPosition) / 2f)
                            a.consume(); b.consume()
                        } else {
                            val c = pressed[0]
                            if (!moved && (c.position - down.position).getDistance() > 8f * density) moved = true
                            if (axis >= 0) {
                                if (moved) {
                                    val o = vm.entries.getOrNull(vm.sel) as? Obj
                                    if (o != null) {
                                        if (!started) { started = true; vm.beginEdit(); startVal = if (axis == 0) o.x else if (axis == 1) o.y else o.z; acc = 0f }
                                        acc += gl.axisDelta(axis, last.x, last.y, c.position.x, c.position.y)
                                        var v = startVal + acc
                                        if (vm.snap > 0f) v = Math.round(v / vm.snap) * vm.snap
                                        vm.setAxis(vm.sel, axis, v)
                                    }
                                    c.consume()
                                }
                            } else if (moved) {
                                camMove(c.position - c.previousPosition)
                                c.consume()
                            }
                            last = c.position
                        }
                    }
                    if (!moved) {
                        val i = gl.pick(down.position.x, down.position.y, 40f * density)
                        if (i >= 0) { vm.sel = i; vm.selStream = -1 }
                        else if (i <= -2) { vm.selStream = -(i + 2); vm.sel = -1 }
                        else { vm.sel = -1; vm.selStream = -1 }
                        gl.requestRender()
                    }
                }
            }
        )
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = !panMode, onClick = { setPan(false) }, label = { Text("Putar") })
            FilterChip(selected = panMode, onClick = { setPan(true) }, label = { Text("Geser") })
            AssistChip(onClick = {
                val o = vm.entries.getOrNull(vm.sel) as? Obj
                if (o != null) { gl.tx = o.x; gl.ty = o.y; gl.tz = o.z; gl.dist = minOf(gl.dist, 60f); gl.requestRender() }
            }, label = { Text("Fokus") })
            AssistChip(onClick = { gl.tx = st[0]; gl.ty = st[1]; gl.tz = st[2]; gl.dist = st[3] * 1.2f; gl.requestRender() }, label = { Text("Semua") })
            AssistChip(onClick = { vm.undo(); gl.requestRender() }, enabled = vm.canUndo, label = { Text("Urung") })
            AssistChip(onClick = { vm.redo(); gl.requestRender() }, enabled = vm.canRedo, label = { Text("Ulang") })
        }
        val sel = vm.entries.getOrNull(vm.sel) as? Obj
        val g = vm.game
        val hud = remember(tick, vm.sel, vm.count) {
            val ss = vm.extra.getOrNull(vm.selStream)
            val head = if (sel != null) sel.model + "  (ID " + sel.id + ")" else if (ss != null) "stream: " + ss.model + "  (ID " + ss.id + ")" else vm.count.toString() + " objek"
            if (g != null) head + "  |  model " + g.okCount.get() + " ok, " + g.failCount.get() + " gagal  |  tekstur " +
                g.texdbs.sumOf { it.ok.get() } + " ok, " + g.texdbs.sumOf { it.fail.get() } + " gagal" +
                (if (g.failCount.get() > 0) "  [" + g.reasons.entries.joinToString { it.key + " " + it.value.get() } + "]" else "") else head
        }
        Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(10.dp), color = Color(0xCC0D1117)) {
            Text(hud, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, color = Color.White)
        }
    }
}

fun rotZ(o: Obj, deg: Float): Obj {
    val h = Math.toRadians(-deg.toDouble()).toFloat() / 2f
    val pz = sin(h); val pw = cos(h)
    return o.copy(
        rx = o.rx * pw + o.ry * pz,
        ry = -o.rx * pz + o.ry * pw,
        rz = o.rw * pz + o.rz * pw,
        rw = o.rw * pw - o.rz * pz
    )
}

@Composable
fun Nudge(label: String, mod: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = mod, contentPadding = PaddingValues(0.dp)) { Text(label, fontSize = 12.sp) }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun Panel(vm: EditorVM, gl: GlScene, st: FloatArray, modifier: Modifier) {
    val i = vm.sel
    val o = vm.entries.getOrNull(i) as? Obj
    var step by remember { mutableFloatStateOf(1f) }
    var viewDist by remember { mutableFloatStateOf(gl.viewDist) }
    var hideLod by remember { mutableStateOf(gl.hideLod) }
    var flipV by remember { mutableStateOf(gl.flipV) }
    val scope = rememberCoroutineScope()
    fun loadStream(all: Boolean) {
        val g = vm.game ?: return
        vm.streamNote = "Memuat stream..."
        val base = if (all) null else vm.fileName.lowercase().removeSuffix(".ipl")
        val known = HashMap<Int, String>()
        for (e in vm.entries) if (e is Obj) known[e.id] = e.model
        scope.launch(Dispatchers.IO) {
            val r = g.streamObjects(base, known)
            vm.extra = r.first; vm.streamNote = r.second
            gl.requestRender()
        }
    }
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("OBJEK TERPILIH") {
            if (o == null) {
                Text("Ketuk objek di tampilan 3D untuk memilihnya.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(modelColor(o.model), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(o.model, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("ID ${o.id}", style = MaterialTheme.typography.labelMedium)
                }
                Text(String.format(Locale.US, "X %.2f   Y %.2f   Z %.2f", o.x, o.y, o.z), style = MaterialTheme.typography.bodySmall)
                Text("Seret panah merah (X), hijau (Y), biru (Z) di layar 3D untuk menggeser objek.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val sobj = vm.extra.getOrNull(vm.selStream)
        if (sobj != null) {
            SectionCard("OBJEK STREAM (DILIHAT SAJA)") {
                Text(sobj.model + "  (ID " + sobj.id + ")", fontWeight = FontWeight.Bold)
                Text(String.format(Locale.US, "X %.2f   Y %.2f   Z %.2f", sobj.x, sobj.y, sobj.z), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.adoptStream(); gl.requestRender() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Salin ke IPL agar bisa diedit")
                }
                Text("Objek aslinya tetap ada di stream, jadi hasilnya objek tambahan.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (o != null) {
            SectionCard("GERAKKAN") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(0.1f, 1f, 5f, 20f)) FilterChip(selected = step == s, onClick = { step = s }, label = { Text(s.toString()) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Snap seret:", style = MaterialTheme.typography.labelMedium)
                    for (s in listOf(0f, 0.5f, 1f, 5f)) FilterChip(selected = vm.snap == s, onClick = { vm.snap = s }, label = { Text(if (s == 0f) "Off" else s.toString()) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Nudge("X-", Modifier.weight(1f)) { vm.update(i, o.copy(x = o.x - step)) }
                    Nudge("X+", Modifier.weight(1f)) { vm.update(i, o.copy(x = o.x + step)) }
                    Nudge("Y-", Modifier.weight(1f)) { vm.update(i, o.copy(y = o.y - step)) }
                    Nudge("Y+", Modifier.weight(1f)) { vm.update(i, o.copy(y = o.y + step)) }
                    Nudge("Z-", Modifier.weight(1f)) { vm.update(i, o.copy(z = o.z - step)) }
                    Nudge("Z+", Modifier.weight(1f)) { vm.update(i, o.copy(z = o.z + step)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Nudge("-90", Modifier.weight(1f)) { vm.update(i, rotZ(o, -90f)) }
                    Nudge("-15", Modifier.weight(1f)) { vm.update(i, rotZ(o, -15f)) }
                    Nudge("+15", Modifier.weight(1f)) { vm.update(i, rotZ(o, 15f)) }
                    Nudge("+90", Modifier.weight(1f)) { vm.update(i, rotZ(o, 90f)) }
                }
            }
            SectionCard("EDIT NILAI") {
                val key = listOf(i, o.x, o.y, o.z, o.model, o.id)
                var fm by remember(key) { mutableStateOf(o.model) }
                var fid by remember(key) { mutableStateOf(o.id.toString()) }
                var fx by remember(key) { mutableStateOf(String.format(Locale.US, "%.3f", o.x)) }
                var fy by remember(key) { mutableStateOf(String.format(Locale.US, "%.3f", o.y)) }
                var fz by remember(key) { mutableStateOf(String.format(Locale.US, "%.3f", o.z)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Model", fm, Modifier.weight(2f), num = false) { fm = it }
                    Field("ID", fid, Modifier.weight(1f)) { fid = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("X", fx, Modifier.weight(1f)) { fx = it }
                    Field("Y", fy, Modifier.weight(1f)) { fy = it }
                    Field("Z", fz, Modifier.weight(1f)) { fz = it }
                }
                Button(onClick = {
                    vm.update(i, o.copy(
                        model = fm.trim().ifEmpty { o.model }, id = fid.toIntOrNull() ?: o.id,
                        x = fx.toFloatOrNull() ?: o.x, y = fy.toFloatOrNull() ?: o.y, z = fz.toFloatOrNull() ?: o.z))
                }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("Terapkan") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Nudge("Ke tengah", Modifier.weight(1f)) { vm.update(i, o.copy(x = gl.tx, y = gl.ty, z = gl.tz)) }
                Nudge("Salin", Modifier.weight(1f)) { vm.duplicate(i) }
                Nudge("Hapus", Modifier.weight(1f)) { vm.remove(i) }
            }
        }
        SectionCard("IPL STREAM (DETAIL PETA)") {
            Text(vm.streamNote.ifEmpty { "IPL teks hanya memuat sebagian objek (banyak LOD). Detail bangunan ada di file stream biner dalam gta3.img. Hanya untuk dilihat, tidak ikut disimpan." },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Nudge("Stream ini", Modifier.weight(1f)) { loadStream(false) }
                Nudge("Semua (berat)", Modifier.weight(1f)) { loadStream(true) }
                Nudge("Kosongkan", Modifier.weight(1f)) { vm.extra = emptyList(); vm.streamNote = ""; gl.requestRender() }
            }
        }
        SectionCard("ADEGAN") {
            Button(onClick = { vm.picker = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("Tambah objek") }
            Text("Jarak pandang: ${viewDist.toInt()} m", style = MaterialTheme.typography.labelMedium)
            Slider(value = viewDist, onValueChange = { viewDist = it; gl.viewDist = it; gl.requestRender() }, valueRange = 150f..2500f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Balik tekstur vertikal", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = flipV, onCheckedChange = { flipV = it; gl.flipV = it; gl.requestRender() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sembunyikan model LOD", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = hideLod, onCheckedChange = { hideLod = it; gl.hideLod = it; gl.requestRender() })
            }
        }
        Text(vm.gameStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ModelPicker(vm: EditorVM) {
    val g = vm.game
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.picker = false },
        confirmButton = { TextButton(onClick = { vm.picker = false }) { Text("Batal") } },
        title = { Text("Tambah objek") },
        text = {
            Column {
                OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Cari nama model atau ID") }, modifier = Modifier.fillMaxWidth())
                val key = q.trim().lowercase()
                val res = if (g == null || key.isEmpty()) emptyList() else g.defList.filter { it.lc.contains(key) || it.id.toString() == key }.take(60)
                if (key.isEmpty()) Text(
                    if (g != null && !g.idsKnown) "File .ide tidak ditemukan: daftar dari nama model, ID = 0. Ubah ID lewat Edit nilai." else "Ketik nama model, misalnya lamppost.",
                    Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(res) { d ->
                        Text("${d.model}   (${d.id})", Modifier.fillMaxWidth().clickable {
                            val s = vm.gl
                            vm.sel = vm.add(d.model, d.id, s?.tx ?: 0f, s?.ty ?: 0f, s?.tz ?: 10f)
                            vm.picker = false
                        }.padding(vertical = 12.dp))
                    }
                }
            }
        }
    )
}

@Composable
fun DiagDialog(vm: EditorVM, text: String) {
    val clip = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { vm.diag = null },
        confirmButton = { TextButton(onClick = { clip.setText(AnnotatedString(text)) }) { Text("Salin") } },
        dismissButton = { TextButton(onClick = { vm.diag = null }) { Text("Tutup") } },
        title = { Text("Diagnosa file game") },
        text = {
            SelectionContainer {
                Text(text, fontSize = 10.sp, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
            }
        }
    )
}

@Composable
fun BoxScope.GamePrompt(vm: EditorVM, onPick: () -> Unit) {
    Card(
        Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tampilkan model GTA asli", fontWeight = FontWeight.Bold)
            Text("Pilih folder game (berisi gta3.img, data, dan texdb). Mendukung GTA SA mobile (tekstur dari texdb) maupun PC. Di Android 11+ folder Android/data tidak bisa dipilih, salin dulu ke Download/GTASA.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.gameStatus.isNotEmpty()) Text(vm.gameStatus, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick, shape = RoundedCornerShape(14.dp)) { Text("Pilih folder GTA SA") }
                OutlinedButton(onClick = { vm.diag = GameData.lastReport.ifEmpty { "Belum ada laporan. Pilih folder GTA SA dulu." } }, shape = RoundedCornerShape(14.dp)) { Text("Diagnosa") }
            }
        }
    }
}
