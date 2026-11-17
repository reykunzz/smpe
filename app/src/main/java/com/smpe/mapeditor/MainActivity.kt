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
    val count: Int get() = entries.count { it is Obj }

    fun load(text: String, name: String, u: Uri?): Boolean {
        if (text.startsWith("bnry")) return false
        entries.clear(); entries.addAll(parseIpl(text))
        fileName = name; uri = u; sel = -1; dirty = false
        return true
    }
    fun text(): String = entries.joinToString("\n") { if (it is Obj) it.line() else (it as Raw).t }
    fun update(i: Int, o: Obj) { entries[i] = o.copy(src = null); dirty = true }
    fun remove(i: Int) { entries.removeAt(i); sel = -1; dirty = true }
    fun add(): Int {
        val o = Obj(1337, "new_object", 0, 0f, 0f, 10f, 0f, 0f, 0f, 1f, -1)
        val last = entries.indexOfLast { it is Obj }
        dirty = true
        return if (last >= 0) { entries.add(last + 1, o); last + 1 }
        else { entries.add(Raw("inst")); entries.add(o); entries.add(Raw("end")); entries.size - 2 }
    }
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
        setContent { SmpeTheme { App() } }
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

    @OptIn(ExperimentalMaterial3Api::class)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text("SMPE", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text(vm.fileName + if (vm.dirty) "  (belum disimpan)" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                actions = {
                    TextButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Buka") }
                    if (vm.entries.isNotEmpty()) {
                        TextButton(onClick = {
                            val u = vm.uri
                            if (u != null) write(u) else saveAs.launch(vm.fileName)
                        }) { Text("Simpan") }
                        TextButton(onClick = { saveAs.launch(vm.fileName) }) { Text("Simpan sebagai") }
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
            }
        },
        floatingActionButton = {
            if (vm.entries.isNotEmpty()) ExtendedFloatingActionButton(
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
                else -> ListView(vm)
            }
        }
        if (vm.sel in vm.entries.indices && vm.entries[vm.sel] is Obj) EditSheet(vm)
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
    val cPrimary = MaterialTheme.colorScheme.primary
    val cInterior = MaterialTheme.colorScheme.secondary
    val cGrid = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surface
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
            val r = 2.5f * density
            vm.entries.forEachIndexed { i, e ->
                if (e is Obj) {
                    val p = Offset(c.x + e.x * base, c.y - e.y * base)
                    if (p.x in -20f..size.width + 20f && p.y in -20f..size.height + 20f) {
                        drawCircle(if (e.interior == 0) cPrimary else cInterior, r, p)
                        if (i == vm.sel) drawCircle(Color.White, r * 3.2f, p, style = Stroke(2f * density))
                    }
                }
            }
        }
        SmallFloatingActionButton(
            onClick = { scale = 1f; off = Offset.Zero },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) { Icon(Icons.Default.Refresh, "Reset tampilan") }
        Text("${vm.count} objek. Cubit untuk zoom, ketuk titik untuk edit.",
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
