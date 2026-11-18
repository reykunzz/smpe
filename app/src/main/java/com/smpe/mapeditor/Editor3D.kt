@file:OptIn(ExperimentalMaterial3Api::class)

package com.smpe.mapeditor

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
                Panel(vm, gl, Modifier.width(320.dp).fillMaxHeight())
                Viewport(vm, gl, panMode, { panMode = it }, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Viewport(vm, gl, panMode, { panMode = it }, Modifier.weight(1f).fillMaxWidth())
                Panel(vm, gl, Modifier.fillMaxWidth().heightIn(max = 300.dp))
            }
        }
    }
}

@Composable
fun Viewport(vm: EditorVM, gl: GlScene, panMode: Boolean, setPan: (Boolean) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current.density
    val pm = rememberUpdatedState(panMode)
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
                gl.scene = vm.entries.mapIndexedNotNull { i, e -> if (e is Obj) SceneObj(i, e) else null }
                gl.sel = vm.sel
                v.requestRender()
            }
        )
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        gl.dist = (gl.dist / zoom).coerceIn(5f, 6000f)
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
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap -> vm.sel = gl.pick(tap.x, tap.y, 40f * density); gl.requestRender() }
                }
        )
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !panMode, onClick = { setPan(false) }, label = { Text("Putar") })
            FilterChip(selected = panMode, onClick = { setPan(true) }, label = { Text("Geser") })
            AssistChip(onClick = {
                val o = vm.entries.getOrNull(vm.sel) as? Obj
                if (o != null) { gl.tx = o.x; gl.ty = o.y; gl.tz = o.z; gl.dist = minOf(gl.dist, 60f); gl.requestRender() }
            }, label = { Text("Fokus") })
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
fun Panel(vm: EditorVM, gl: GlScene, modifier: Modifier) {
    val i = vm.sel
    val o = vm.entries.getOrNull(i) as? Obj
    var step by remember { mutableFloatStateOf(1f) }
    var viewDist by remember { mutableFloatStateOf(gl.viewDist) }
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Properti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (o == null) {
            Text("Ketuk objek di tampilan 3D untuk memilihnya.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("${o.model}  (ID ${o.id})", fontWeight = FontWeight.SemiBold)
            Text(String.format(Locale.US, "X %.2f   Y %.2f   Z %.2f", o.x, o.y, o.z), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in listOf(0.1f, 1f, 5f, 20f)) FilterChip(selected = step == s, onClick = { step = s }, label = { Text(s.toString()) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Nudge("X-", Modifier.weight(1f)) { vm.update(i, o.copy(x = o.x - step)) }
                Nudge("X+", Modifier.weight(1f)) { vm.update(i, o.copy(x = o.x + step)) }
                Nudge("Y-", Modifier.weight(1f)) { vm.update(i, o.copy(y = o.y - step)) }
                Nudge("Y+", Modifier.weight(1f)) { vm.update(i, o.copy(y = o.y + step)) }
                Nudge("Z-", Modifier.weight(1f)) { vm.update(i, o.copy(z = o.z - step)) }
                Nudge("Z+", Modifier.weight(1f)) { vm.update(i, o.copy(z = o.z + step)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Nudge("Putar -15", Modifier.weight(1f)) { vm.update(i, rotZ(o, -15f)) }
                Nudge("Putar +15", Modifier.weight(1f)) { vm.update(i, rotZ(o, 15f)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Nudge("Ke tengah", Modifier.weight(1f)) { vm.update(i, o.copy(x = gl.tx, y = gl.ty, z = gl.tz)) }
                Nudge("Salin", Modifier.weight(1f)) {
                    vm.entries.add(i + 1, o.copy(x = o.x + 3f, src = null)); vm.dirty = true; vm.sel = i + 1
                }
                Nudge("Hapus", Modifier.weight(1f)) { vm.remove(i) }
            }
        }
        HorizontalDivider()
        Button(onClick = { vm.picker = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("Tambah objek") }
        Text("Jarak pandang: ${viewDist.toInt()} m", style = MaterialTheme.typography.labelMedium)
        Slider(value = viewDist, onValueChange = { viewDist = it; gl.viewDist = it; gl.requestRender() }, valueRange = 150f..2500f)
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
                if (key.isEmpty()) Text("Ketik nama model, misalnya lamppost.", Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun BoxScope.GamePrompt(vm: EditorVM, onPick: () -> Unit) {
    Card(
        Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tampilkan model GTA asli", fontWeight = FontWeight.Bold)
            Text("Pilih folder utama GTA SA PC (berisi folder models dan data). Model dan tekstur dibaca dari gta3.img.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.gameStatus.isNotEmpty()) Text(vm.gameStatus, style = MaterialTheme.typography.labelSmall)
            Button(onClick = onPick, shape = RoundedCornerShape(14.dp)) { Text("Pilih folder GTA SA") }
        }
    }
}
