package com.example.teksgambar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

private fun toast(c: Context, m: String) = Toast.makeText(c, m, Toast.LENGTH_LONG).show()

private fun saveToGallery(context: Context, bmp: Bitmap): Boolean {
    return try {
        val name = "TeksKeGambar_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TeksKeGambar")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) {
        false
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tkg", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var prompt by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFull by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) photo = loadScaled(context, uri, 1024)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bmp = result
        if (granted && bmp != null) {
            toast(context, if (saveToGallery(context, bmp)) "Tersimpan di galeri (Pictures/TeksKeGambar)" else "Gagal menyimpan")
        } else {
            toast(context, "Izin penyimpanan ditolak")
        }
    }

    val doSave: (Bitmap) -> Unit = { bmp ->
        val allowed = Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            toast(context, if (saveToGallery(context, bmp)) "Tersimpan di galeri (Pictures/TeksKeGambar)" else "Gagal menyimpan")
        } else {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Teks ke Gambar", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Deskripsi / perintah") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3
        )
        Text(
            "${prompt.length} karakter" + if (prompt.length > 800) " (terlalu panjang, coba dipersingkat)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (prompt.length > 800) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                prefs.edit().putString("key", it.trim()).apply()
            },
            label = { Text("API key Pollinations (wajib untuk mode foto)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !loading) {
                Text(if (photo == null) "Pilih Foto (opsional)" else "Ganti Foto")
            }
            if (photo != null) {
                TextButton(onClick = { photo = null }, enabled = !loading) { Text("Hapus Foto") }
            }
        }

        photo?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Foto referensi",
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
            )
            Text(
                "Foto akan diunggah sementara (1 jam) ke Litterbox agar bisa diproses.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        result = generate(prompt.trim(), photo, apiKey.trim())
                    } catch (e: Exception) {
                        error = "Gagal: ${e.message}"
                    }
                    loading = false
                }
            },
            enabled = prompt.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "Memproses..." else "Buat Gambar") }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        result?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Hasil gambar (ketuk untuk layar penuh)",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFull = true }
            )
            Text("Ketuk gambar untuk layar penuh dan zoom.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { doSave(bmp) }) { Text("Simpan ke Galeri") }
                OutlinedButton(onClick = { showFull = true }) { Text("Layar Penuh") }
            }
        }
    }

    val shown = result
    if (showFull && shown != null) {
        FullImageDialog(bmp = shown, onSave = { doSave(it) }, onClose = { showFull = false })
    }
}

@Composable
fun FullImageDialog(bmp: Bitmap, onSave: (Bitmap) -> Unit, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Gambar layar penuh",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = { onSave(bmp) }) { Text("Simpan") }
                OutlinedButton(
                    onClick = onClose,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Tutup") }
            }
        }
    }
}

// Ganti alamat ini jika ingin memakai layanan lain.
private const val BASE_URL = "https://image.pollinations.ai/prompt/"
private const val GEN_URL = "https://gen.pollinations.ai/image/"
private const val UA = "Mozilla/5.0 (Linux; Android 13) TeksKeGambar/1.0"
private const val UPLOAD_URL = "https://litterbox.catbox.moe/resources/internals/api.php"

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private fun loadScaled(context: Context, uri: Uri, maxSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun uploadTemp(photo: Bitmap): String {
    val out = ByteArrayOutputStream()
    photo.compress(Bitmap.CompressFormat.JPEG, 85, out)
    val bytes = out.toByteArray()
    val boundary = "----tkg${System.currentTimeMillis()}"
    val conn = URL(UPLOAD_URL).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = 30_000
    conn.readTimeout = 60_000
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    conn.setRequestProperty("User-Agent", UA)
    try {
        conn.outputStream.use { os ->
            fun field(name: String, value: String) {
                os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("reqtype", "fileupload")
            field("time", "1h")
            os.write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"foto.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n").toByteArray()
            )
            os.write(bytes)
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        if (conn.responseCode != 200) error("Upload gagal (HTTP ${conn.responseCode})")
        val link = conn.inputStream.bufferedReader().readText().trim()
        if (!link.startsWith("http")) error("Upload gagal: $link")
        return link
    } finally {
        conn.disconnect()
    }
}

suspend fun generate(prompt: String, photo: Bitmap?, key: String): Bitmap = withContext(Dispatchers.IO) {
    val seed = Random.nextInt(1, 999999)
    var extra = ""
    if (photo != null && key.isBlank()) {
        error("Mode foto butuh API key Pollinations (gratis daftar di enter.pollinations.ai). Isi kolom API key dulu.")
    }
    if (photo != null) {
        val link = uploadTemp(photo)
        extra = "&model=kontext&image=${enc(link)}"
    }
    val base = if (key.isNotBlank()) GEN_URL else BASE_URL
    val url = URL("$base${enc(prompt)}?width=768&height=768&nologo=true&seed=$seed$extra")
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = 30_000
    conn.readTimeout = 180_000
    conn.setRequestProperty("User-Agent", UA)
    if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
    try {
        if (conn.responseCode != 200) {
            val body = try {
                conn.errorStream?.bufferedReader()?.readText()?.take(200)
            } catch (e: Exception) {
                null
            }
            error("HTTP ${conn.responseCode} ${body ?: ""}".trim())
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) ?: error("Gambar tidak valid") }
    } finally {
        conn.disconnect()
    }
}
