package com.example.teksgambar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@Composable
fun App() {
    val context = LocalContext.current
    var prompt by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) photo = loadScaled(context, uri, 1024)
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
                        result = generate(prompt.trim(), photo)
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
        result?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Hasil gambar",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// Ganti alamat ini jika ingin memakai layanan lain.
private const val BASE_URL = "https://image.pollinations.ai/prompt/"
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

suspend fun generate(prompt: String, photo: Bitmap?): Bitmap = withContext(Dispatchers.IO) {
    val seed = Random.nextInt(1, 999999)
    var extra = ""
    if (photo != null) {
        val link = uploadTemp(photo)
        extra = "&model=kontext&image=${enc(link)}"
    }
    val url = URL("$BASE_URL${enc(prompt)}?width=768&height=768&nologo=true&seed=$seed$extra")
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = 30_000
    conn.readTimeout = 180_000
    try {
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        conn.inputStream.use { BitmapFactory.decodeStream(it) ?: error("Gambar tidak valid") }
    } finally {
        conn.disconnect()
    }
}
