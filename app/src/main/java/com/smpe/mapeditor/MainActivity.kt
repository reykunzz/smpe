package com.smpe.mapeditor

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import androidx.compose.foundation.text.selection.SelectionContainer
import java.io.File
import android.content.Context
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.Dispatchers
import java.util.Locale

// ---------- Model ----------
sealed interface Entry
data class Raw(val t: String) : Entry
data class Obj(
    val id: Int, val model: String, val interior: Int,
    val x: Float, val y: Float, val z: Float,
    val rx: Float, val ry: Float, val rz: Float, val rw: Float,
    val lod: Int?, val src: String? = null
) : Entry {
    private fun f(v: Float) = String.format(Locale.US, "%.6f", v)
    fun line(): String = src ?: buildString {
        append("$id, $model, $interior, ${f(x)}, ${f(y)}, ${f(z)}, ${f(rx)}, ${f(ry)}, ${f(rz)}, ${f(rw)}")
        if (lod != null) append(", $lod")
    }
}

fun parseObj(l: String): Obj? = try {
    val p = l.split(",").map { it.trim() }
    if (p.size < 10) null else Obj(
        p[0].toInt(), p[1], p[2].toInt(),
        p[3].toFloat(), p[4].toFloat(), p[5].toFloat(),
        p[6].toFloat(), p[7].toFloat(), p[8].toFloat(), p[9].toFloat(),
        p.getOrNull(10)?.toIntOrNull(), l
    )
} catch (e: Exception) { null }

fun parseIpl(text: String): List<Entry> {
    var inInst = false
    return text.lines().map { l ->
        val t = l.trim()
        when {
            t.equals("inst", true) -> { inInst = true; Raw(l) }
            t.equals("end", true) -> { inInst = false; Raw(l) }
            inInst && t.isNotEmpty() && !t.startsWith("#") -> parseObj(l) ?: Raw(l)
            else -> Raw(l)
        }
    }
}

class EditorVM : ViewModel() {
    val entries = mutableStateListOf<Entry>()
    var fileName by mutableStateOf("Belum ada file")
    var uri by mutableStateOf<Uri?>(null)
    var sel by mutableIntStateOf(-1)
    var dirty by mutableStateOf(false)
    var game by mutableStateOf<GameData?>(null)
    var gameStatus by mutableStateOf("")
    var picker by mutableStateOf(false)
    var gl: GlScene? = null
    var diag by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    private val undoS = ArrayList<List<Entry>>()
    private val redoS = ArrayList<List<Entry>>()
    private fun snapshot() {
        undoS.add(entries.toList()); if (undoS.size > 80) undoS.removeAt(0)
        redoS.clear(); canUndo = true; canRedo = false
    }
    fun undo() {
        if (undoS.isEmpty()) return
        redoS.add(entries.toList())
        val s = undoS.removeAt(undoS.size - 1)
        entries.clear(); entries.addAll(s); sel = -1; dirty = true
        canUndo = undoS.isNotEmpty(); canRedo = true
    }
    fun redo() {
        if (redoS.isEmpty()) return
        undoS.add(entries.toList())
        val s = redoS.removeAt(redoS.size - 1)
        entries.clear(); entries.addAll(s); sel = -1; dirty = true
        canRedo = redoS.isNotEmpty(); canUndo = true
    }
    val count: Int get() = entries.count { it is Obj }

    fun load(text: String, name: String, u: Uri?): Boolean {
        if (text.startsWith("bnry")) return false
        entries.clear(); entries.addAll(parseIpl(text))
        fileName = name; uri = u; sel = -1; dirty = false
        undoS.clear(); redoS.clear(); canUndo = false; canRedo = false
        return true
    }
    fun text(): String = entries.joinToString("\n") { if (it is Obj) it.line() else (it as Raw).t }
    fun update(i: Int, o: Obj) { snapshot(); entries[i] = o.copy(src = null); dirty = true }
    fun remove(i: Int) { snapshot(); entries.removeAt(i); sel = -1; dirty = true }
    fun duplicate(i: Int) {
        val o = entries.getOrNull(i) as? Obj ?: return
        snapshot(); entries.add(i + 1, o.copy(x = o.x + 3f, src = null)); dirty = true; sel = i + 1
    }
    fun add(model: String = "new_object", id: Int = 1337, x: Float = 0f, y: Float = 0f, z: Float = 10f): Int {
        val o = Obj(id, model, 0, x, y, z, 0f, 0f, 0f, 1f, -1)
        snapshot()
        val last = entries.indexOfLast { it is Obj }
        dirty = true
        return if (last >= 0) { entries.add(last + 1, o); last + 1 }
        else { entries.add(Raw("inst")); entries.add(o); entries.add(Raw("end")); entries.size - 2 }
    }
}

// ---------- Helpers ----------
fun modelColor(model: String): Color =
    Color.hsv(((model.lowercase().hashCode() and 0x7fffffff) % 360).toFloat(), 0.62f, 0.95f)

fun shade(c: Color, k: Float) =
    Color((c.red * k).coerceIn(0f, 1f), (c.green * k).coerceIn(0f, 1f), (c.blue * k).coerceIn(0f, 1f), c.alpha)

/** returns [centerX, centerY, centerZ, suggestedDistance] */
fun sceneStats(entries: List<Entry>): FloatArray {
    val o = entries.filterIsInstance<Obj>()
    if (o.isEmpty()) return floatArrayOf(0f, 0f, 0f, 300f)
    val ext = max(o.maxOf { it.x } - o.minOf { it.x }, o.maxOf { it.y } - o.minOf { it.y })
    return floatArrayOf(o.map { it.x }.average().toFloat(), o.map { it.y }.average().toFloat(),
        o.map { it.z }.average().toFloat(), (ext * 0.9f).coerceIn(80f, 6000f))
}

/** Orbit camera: z is up, yaw 0 looks north. */
class Cam(tx: Float, ty: Float, tz: Float, yaw: Float, pitch: Float, dist: Float, val w: Float, val h: Float) {
    private val cp = cos(pitch); private val sp = sin(pitch)
    private val cyw = cos(yaw); private val syw = sin(yaw)
    val camX = tx + dist * cp * syw
    val camY = ty - dist * cp * cyw
    val camZ = tz + dist * sp
    private val fx = -cp * syw; private val fy = cp * cyw; private val fz = -sp
    private val rx = cyw; private val ry = syw
    private val ux = ry * fz; private val uy = -rx * fz; private val uz = rx * fy - ry * fx
    val focal = minOf(w, h) * 1.1f
    fun zc(x: Float, y: Float, z: Float) = (x - camX) * fx + (y - camY) * fy + (z - camZ) * fz
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val vx = x - camX; val vy = y - camY; val vz = z - camZ
        val d = vx * fx + vy * fy + vz * fz
        if (d < 1f) return false
        out[0] = w / 2f + (vx * rx + vy * ry) / d * focal
        out[1] = h / 2f - (vx * ux + vy * uy + vz * uz) / d * focal
        out[2] = d
        return true
    }
}

fun DrawScope.line3(cam: Cam, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, c: Color) {
    val a = cam.zc(x1, y1, z1); val b = cam.zc(x2, y2, z2)
    if (a < 1f && b < 1f) return
    var ax = x1; var ay = y1; var az = z1; var bx = x2; var by = y2; var bz = z2
    if (a < 1f) { val t = (1f - a) / (b - a); ax = x1 + (x2 - x1) * t; ay = y1 + (y2 - y1) * t; az = z1 + (z2 - z1) * t }
    else if (b < 1f) { val t = (1f - b) / (a - b); bx = x2 + (x1 - x2) * t; by = y2 + (y1 - y2) * t; bz = z2 + (z1 - z2) * t }
    val p = FloatArray(3); val q = FloatArray(3)
    if (cam.project(ax, ay, az, p) && cam.project(bx, by, bz, q))
        drawLine(c, Offset(p[0], p[1]), Offset(q[0], q[1]), 1f)
}

fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float) {
    // drawText throws when the text origin is outside the canvas, so skip those
    if (x.isNaN() || y.isNaN() || x < 0f || y < 0f || x > size.width - 30f || y > size.height - 20f) return
    drawText(tm, text, Offset(x, y), TextStyle(color = Color.White, fontSize = 10.sp, background = Color(0xAA0D1117)))
}

// ---------- Theme ----------
private val Scheme = darkColorScheme(
    primary = Color(0xFFFF8A3D), onPrimary = Color(0xFF1B0F05),
    primaryContainer = Color(0xFF3A2314), onPrimaryContainer = Color(0xFFFFD9BF),
    secondary = Color(0xFF3FA7D6), tertiary = Color(0xFF7BCB5B),
    background = Color(0xFF0D1117), onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF141922), onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF1D2430), onSurfaceVariant = Color(0xFFA9B4C4),
    outline = Color(0xFF3A4556), error = Color(0xFFFF6B6B)
)

@Composable
fun SmpeTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val crashFile = File(filesDir, "crash.txt")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile.writeText(e.stackTraceToString().take(3500)) } catch (_: Exception) {}
            old?.uncaughtException(t, e)
        }
        setContent {
            SmpeTheme {
                var crash by remember { mutableStateOf(if (crashFile.exists()) crashFile.readText() else null) }
                App()
                crash?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { crashFile.delete(); crash = null },
                        confirmButton = { TextButton(onClick = { crashFile.delete(); crash = null }) { Text("Tutup") } },
                        title = { Text("Laporan crash terakhir") },
                        text = {
                            SelectionContainer {
                                Text(msg, fontSize = 10.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                            }
                        }
                    )
                }
            }
        }
    }
}

fun nameOf(ctx: android.content.Context, u: Uri): String {
    ctx.contentResolver.query(u, null, null, null, null)?.use {
        if (it.moveToFirst()) {
            val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) return it.getString(i)
        }
    }
    return "file.ipl"
}

// ---------- App ----------
@Composable
fun App(vm: EditorVM = viewModel()) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val prefs = remember { ctx.getSharedPreferences("smpe", Context.MODE_PRIVATE) }
    fun say(m: String) { scope.launch { snack.showSnackbar(m) } }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) try {
            try { ctx.contentResolver.takePersistableUriPermission(u, 3) } catch (_: Exception) {}
            val txt = ctx.contentResolver.openInputStream(u)!!.bufferedReader().readText()
            if (vm.load(txt, nameOf(ctx, u), u)) say("${vm.count} objek dimuat")
            else say("IPL biner belum didukung. Gunakan IPL teks.")
        } catch (e: Exception) { say("Gagal membuka file: ${e.message}") }
    }
    fun write(u: Uri) {
        try {
            ctx.contentResolver.openOutputStream(u, "wt")!!.use { it.write(vm.text().toByteArray()) }
            vm.uri = u; vm.dirty = false; say("Tersimpan")
        } catch (e: Exception) { say("Gagal menyimpan: ${e.message}. Coba Simpan sebagai.") }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { u: Uri? ->
        if (u != null) write(u)
    }

    fun loadGame(u: Uri) {
        vm.gameStatus = "Memuat data GTA SA..."
        say("Memuat data GTA SA...")
        scope.launch(Dispatchers.IO) {
            try {
                val g = GameData.open(ctx, u)
                vm.game = g
                vm.gameStatus = "${g.defList.size} model GTA SA siap"
                say("Data GTA SA siap: ${g.defList.size} model")
            } catch (e: Exception) {
                vm.gameStatus = "Gagal: ${e.message}"
                say("Gagal memuat data GTA: ${e.message}")
            }
        }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) {
            try { ctx.contentResolver.takePersistableUriPermission(u, 1) } catch (_: Exception) {}
            prefs.edit().putString("tree", u.toString()).apply()
            loadGame(u)
        }
    }
    LaunchedEffect(Unit) {
        val saved = prefs.getString("tree", null)
        if (saved != null && vm.game == null) loadGame(Uri.parse(saved))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo), null, Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("SMPE", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text(vm.fileName + if (vm.dirty) "  (belum disimpan)" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Buka") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (vm.entries.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Simpan") }, onClick = {
                                menu = false
                                val u = vm.uri
                                if (u != null) write(u) else saveAs.launch(vm.fileName)
                            })
                            DropdownMenuItem(text = { Text("Simpan sebagai") }, onClick = { menu = false; saveAs.launch(vm.fileName) })
                        }
                        DropdownMenuItem(text = { Text("Urungkan") }, enabled = vm.canUndo, onClick = { menu = false; vm.undo() })
                        DropdownMenuItem(text = { Text("Ulangi") }, enabled = vm.canRedo, onClick = { menu = false; vm.redo() })
                        DropdownMenuItem(text = { Text("Diagnosa file game") }, onClick = {
                            menu = false
                            scope.launch(Dispatchers.IO) {
                                vm.diag = vm.game?.diagnose() ?: GameData.lastReport.ifEmpty { "Folder GTA SA belum dipilih." }
                            }
                        })
                        DropdownMenuItem(text = { Text("Pilih folder GTA SA") }, onClick = { menu = false; pickTree.launch(null) })
                    }
                }
            )
        },
        bottomBar = {
            if (vm.entries.isNotEmpty()) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Place, null) }, label = { Text("Peta") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.List, null) }, label = { Text("Objek") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("3D") })
            }
        },
        floatingActionButton = {
            if (vm.entries.isNotEmpty() && !(tab == 2 && vm.game != null)) ExtendedFloatingActionButton(
                onClick = { vm.sel = vm.add() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Objek baru") }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                vm.entries.isEmpty() -> Empty { open.launch(arrayOf("*/*")) }
                tab == 0 -> MapView(vm)
                tab == 1 -> ListView(vm)
                else -> {
                    val g = vm.game
                    if (g != null) GlEditor(vm, g)
                    else Box(Modifier.fillMaxSize()) { View3D(vm); GamePrompt(vm) { pickTree.launch(null) } }
                }
            }
        }
        if (vm.sel in vm.entries.indices && vm.entries[vm.sel] is Obj && !(tab == 2 && vm.game != null)) EditSheet(vm)
        if (vm.picker) ModelPicker(vm)
        vm.diag?.let { DiagDialog(vm, it) }
    }
}

@Composable
fun Empty(onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(180.dp))
        Spacer(Modifier.height(24.dp))
        Text("Buka file IPL untuk mulai", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Pilih file .ipl teks dari GTA San Andreas. Objek akan muncul di peta dan bisa kamu ubah, tambah, atau hapus.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpen, shape = RoundedCornerShape(16.dp)) { Text("Buka file IPL") }
    }
}

// ---------- Map ----------
@Composable
fun MapView(vm: EditorVM) {
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    val cGrid = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surface
    val tm = rememberTextMeasurer()
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 60f); off += pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val base = minOf(size.width, size.height) / 6000f * scale
                        val c = Offset(size.width / 2f, size.height / 2f) + off
                        var best = -1
                        var bd = (28f * density) * (28f * density)
                        vm.entries.forEachIndexed { i, e ->
                            if (e is Obj) {
                                val d = (Offset(c.x + e.x * base, c.y - e.y * base) - tap).getDistanceSquared()
                                if (d < bd) { bd = d; best = i }
                            }
                        }
                        if (best >= 0) vm.sel = best
                    }
                }
        ) {
            val base = size.minDimension / 6000f * scale
            val c = Offset(size.width / 2f, size.height / 2f) + off
            drawRect(Brush.verticalGradient(listOf(Color(0xFF101722), Color(0xFF0D1117))))
            drawRect(bg, topLeft = c - Offset(3000f * base, 3000f * base), size = Size(6000f * base, 6000f * base))
            var g = -3000
            while (g <= 3000) {
                val gx = c.x + g * base
                val gy = c.y + g * base
                val a = if (g % 1500 == 0) 0.9f else 0.35f
                drawLine(cGrid.copy(alpha = a), Offset(gx, c.y - 3000f * base), Offset(gx, c.y + 3000f * base), 1f)
                drawLine(cGrid.copy(alpha = a), Offset(c.x - 3000f * base, gy), Offset(c.x + 3000f * base, gy), 1f)
                g += 500
            }
            val r = 3f * density
            var labels = 0
            vm.entries.forEachIndexed { i, e ->
                if (e is Obj) {
                    val p = Offset(c.x + e.x * base, c.y - e.y * base)
                    if (p.x in -20f..size.width + 20f && p.y in -20f..size.height + 20f) {
                        val col = modelColor(e.model)
                        if (e.interior == 0) drawCircle(col, r, p)
                        else drawRect(col, p - Offset(r, r), Size(2 * r, 2 * r))
                        if (i == vm.sel) drawCircle(Color.White, r * 3.2f, p, style = Stroke(2f * density))
                        if ((scale >= 6f && labels < 150) || i == vm.sel) {
                            labels++
                            label(tm, e.model, p.x + r + 3f, p.y - r - 14f)
                        }
                    }
                }
            }
        }
        SmallFloatingActionButton(
            onClick = { scale = 1f; off = Offset.Zero },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) { Icon(Icons.Default.Refresh, "Reset tampilan") }
        Text("${vm.count} objek. Warna = jenis model, bulat = luar, kotak = interior.\nZoom lebih dekat untuk melihat nama model.",
            Modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(0.75f),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- 3D ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun View3D(vm: EditorVM) {
    val st = remember(vm.fileName) { sceneStats(vm.entries) }
    var yaw by remember(vm.fileName) { mutableFloatStateOf(0.5f) }
    var pitch by remember(vm.fileName) { mutableFloatStateOf(0.8f) }
    var dist by remember(vm.fileName) { mutableFloatStateOf(st[3]) }
    var tx by remember(vm.fileName) { mutableFloatStateOf(st[0]) }
    var ty by remember(vm.fileName) { mutableFloatStateOf(st[1]) }
    var tz by remember(vm.fileName) { mutableFloatStateOf(st[2]) }
    var panMode by remember { mutableStateOf(false) }
    val tm = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outline

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        dist = (dist / zoom).coerceIn(15f, 9000f)
                        if (!panMode) {
                            yaw -= pan.x * 0.008f
                            pitch = (pitch + pan.y * 0.008f).coerceIn(0.1f, 1.5f)
                        } else {
                            val k = dist / (minOf(size.width, size.height) * 1.1f)
                            val c = cos(yaw); val s = sin(yaw)
                            tx += -pan.x * k * c - pan.y * k * s
                            ty += -pan.x * k * s + pan.y * k * c
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val cam = Cam(tx, ty, tz, yaw, pitch, dist, size.width.toFloat(), size.height.toFloat())
                        val o = FloatArray(3)
                        var best = -1; var bd = (30f * density) * (30f * density)
                        vm.entries.forEachIndexed { i, e ->
                            if (e is Obj && cam.project(e.x, e.y, e.z, o)) {
                                val d = (Offset(o[0], o[1]) - tap).getDistanceSquared()
                                if (d < bd) { bd = d; best = i }
                            }
                        }
                        if (best >= 0) vm.sel = best
                    }
                }
        ) {
            val cam = Cam(tx, ty, tz, yaw, pitch, dist, size.width, size.height)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF24385A), Color(0xFF0D1117))))

            // ground grid
            val step = 10f.pow(floor(log10(dist / 5f)))
            val x0 = floor(tx / step) * step
            val y0 = floor(ty / step) * step
            for (k in -8..8) {
                val gc = grid.copy(alpha = if (k % 5 == 0) 0.9f else 0.45f)
                line3(cam, x0 + k * step, y0 - 8 * step, 0f, x0 + k * step, y0 + 8 * step, 0f, gc)
                line3(cam, x0 - 8 * step, y0 + k * step, 0f, x0 + 8 * step, y0 + k * step, 0f, gc)
            }

            // visible objects, far to near
            val dep = FloatArray(vm.entries.size)
            val vis = ArrayList<Int>()
            val maxD = dist * 6f + 800f
            vm.entries.forEachIndexed { i, e ->
                if (e is Obj) {
                    val d = cam.zc(e.x, e.y, e.z)
                    if (d in 1f..maxD) { dep[i] = d; vis.add(i) }
                }
            }
            vis.sortByDescending { dep[it] }

            val half = max(3f, dist * 0.006f)
            val o = FloatArray(3); val gp = FloatArray(3); val pts = FloatArray(16)
            val path = Path()
            fun face(a: Int, b: Int, c: Int, d: Int, col: Color, k: Float) {
                path.reset()
                path.moveTo(pts[a * 2], pts[a * 2 + 1]); path.lineTo(pts[b * 2], pts[b * 2 + 1])
                path.lineTo(pts[c * 2], pts[c * 2 + 1]); path.lineTo(pts[d * 2], pts[d * 2 + 1])
                path.close()
                drawPath(path, shade(col, k))
                drawPath(path, Color(0x66000000), style = Stroke(1f))
            }

            for (i in vis) {
                val e = vm.entries[i] as Obj
                val col = modelColor(e.model)
                if (!cam.project(e.x, e.y, e.z, o)) continue
                if (cam.project(e.x, e.y, 0f, gp))
                    drawLine(col.copy(alpha = 0.35f), Offset(gp[0], gp[1]), Offset(o[0], o[1]), 1f)
                val px = half * 2f / o[2] * cam.focal
                var cube = px >= 4f
                if (cube) {
                    for (b in 0..7) {
                        val ok = cam.project(
                            e.x + if (b and 1 != 0) half else -half,
                            e.y + if (b and 2 != 0) half else -half,
                            e.z + if (b and 4 != 0) half else -half, gp)
                        if (!ok) { cube = false; break }
                        pts[b * 2] = gp[0]; pts[b * 2 + 1] = gp[1]
                    }
                }
                if (cube) {
                    val dx = cam.camX - e.x; val dy = cam.camY - e.y; val dz = cam.camZ - e.z
                    if (dy < -half) face(0, 1, 5, 4, col, 0.8f)
                    if (dy > half) face(2, 3, 7, 6, col, 0.8f)
                    if (dx < -half) face(0, 2, 6, 4, col, 0.65f)
                    if (dx > half) face(1, 3, 7, 5, col, 0.65f)
                    if (dz < -half) face(0, 1, 3, 2, col, 0.5f)
                    if (dz > half) face(4, 5, 7, 6, col, 1.1f)
                } else {
                    drawCircle(col, 2.5f * density, Offset(o[0], o[1]))
                }
                if (i == vm.sel) drawCircle(Color.White, max(px, 10f * density), Offset(o[0], o[1]), style = Stroke(2f * density))
            }

            // labels: nearest 25 + selected
            var shown = 0
            val thr = max(250f, dist * 0.9f)
            for (idx in vis.indices.reversed()) {
                val i = vis[idx]
                val want = i == vm.sel || (shown < 25 && dep[i] <= thr)
                if (!want) continue
                val e = vm.entries[i] as Obj
                if (cam.project(e.x, e.y, e.z, o)) {
                    shown++
                    label(tm, e.model, o[0] + 8f, o[1] - 34f)
                }
            }
        }

        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !panMode, onClick = { panMode = false }, label = { Text("Putar") })
                FilterChip(selected = panMode, onClick = { panMode = true }, label = { Text("Geser") })
                AssistChip(onClick = {
                    val o = vm.entries.getOrNull(vm.sel)
                    if (o is Obj) { tx = o.x; ty = o.y; tz = o.z; dist = minOf(dist, 120f) }
                }, label = { Text("Fokus") })
            }
            Text("Seret untuk ${if (panMode) "menggeser" else "memutar"}, cubit untuk zoom.\nKubus = posisi tiap model, warna = jenis model.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SmallFloatingActionButton(
            onClick = { tx = st[0]; ty = st[1]; tz = st[2]; dist = st[3]; yaw = 0.5f; pitch = 0.8f },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) { Icon(Icons.Default.Refresh, "Reset kamera") }
    }
}

// ---------- List ----------
@Composable
fun ListView(vm: EditorVM) {
    var q by remember { mutableStateOf("") }
    val rows = vm.entries.withIndex().filter { (_, e) ->
        e is Obj && (q.isBlank() || e.model.contains(q.trim(), true) || e.id.toString() == q.trim())
    }
    Column {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth().padding(12.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Cari model atau ID") },
            singleLine = true, shape = RoundedCornerShape(16.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rows, key = { it.index }) { row ->
                val i = row.index
                val e = row.value as Obj
                Card(
                    onClick = { vm.sel = i }, shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(modelColor(e.model), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("${e.id}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(e.model, fontWeight = FontWeight.SemiBold)
                            Text(String.format(Locale.US, "%.1f, %.1f, %.1f", e.x, e.y, e.z),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Edit ----------
@Composable
fun Field(label: String, v: String, mod: Modifier = Modifier, num: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = v, onValueChange = onChange, modifier = mod, label = { Text(label) },
        singleLine = true, shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (num) KeyboardType.Decimal else KeyboardType.Text)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(vm: EditorVM) {
    val i = vm.sel
    val o = vm.entries[i] as Obj
    var model by remember(i) { mutableStateOf(o.model) }
    var id by remember(i) { mutableStateOf(o.id.toString()) }
    var x by remember(i) { mutableStateOf(o.x.toString()) }
    var y by remember(i) { mutableStateOf(o.y.toString()) }
    var z by remember(i) { mutableStateOf(o.z.toString()) }
    ModalBottomSheet(onDismissRequest = { vm.sel = -1 }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Edit objek", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Model", model, Modifier.weight(2f), num = false) { model = it }
                Field("ID", id, Modifier.weight(1f)) { id = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("X", x, Modifier.weight(1f)) { x = it }
                Field("Y", y, Modifier.weight(1f)) { y = it }
                Field("Z", z, Modifier.weight(1f)) { z = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        vm.update(i, o.copy(
                            model = model.trim().ifEmpty { o.model }, id = id.toIntOrNull() ?: o.id,
                            x = x.toFloatOrNull() ?: o.x, y = y.toFloatOrNull() ?: o.y, z = z.toFloatOrNull() ?: o.z))
                        vm.sel = -1
                    },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)
                ) { Text("Terapkan") }
                OutlinedButton(
                    onClick = { vm.remove(i) }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Hapus") }
            }
        }
    }
}
