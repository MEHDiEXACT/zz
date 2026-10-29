package app.lyrics

import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val exts = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("p", 0)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen(prefs) } } }
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private fun save(dir: DocumentFile, name: String, text: String): Boolean = try {
        val f = dir.findFile(name) ?: dir.createFile("application/octet-stream", name) ?: return false
        contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(text.toByteArray()) }
        true
    } catch (e: Exception) { false }

    private fun scan(d: DocumentFile, out: MutableList<Pair<DocumentFile, DocumentFile>>) {
        for (f in d.listFiles()) {
            if (f.isDirectory) scan(f, out)
            else if ((f.name?.substringAfterLast('.', "")?.lowercase()) in exts) out += d to f
        }
    }

    private fun meta(f: DocumentFile): Pair<String, String> {
        var a = ""; var t = ""
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, f.uri)
            a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            r.release()
        } catch (_: Exception) {}
        if (t.isBlank()) {
            val base = f.name!!.substringBeforeLast('.')
            if (" - " in base) { a = base.substringBefore(" - ").trim(); t = base.substringAfter(" - ").trim() } else t = base
        }
        return a to t
    }

    private fun saveHit(dir: DocumentFile, base: String, h: Hit): Boolean =
        if (h.synced != null) save(dir, "$base.lrc", h.synced) else save(dir, "$base.txt", h.plain ?: "")

    @Composable
    fun Screen(prefs: SharedPreferences) {
        val scope = rememberCoroutineScope()
        var folder by remember { mutableStateOf(prefs.getString("folder", null)?.let { Uri.parse(it) }) }
        var q by remember { mutableStateOf("") }
        var hits by remember { mutableStateOf(listOf<Hit>()) }
        var status by remember { mutableStateOf("Choose your music folder to start.") }
        var busy by remember { mutableStateOf(false) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
            if (u != null) {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString("folder", u.toString()).apply()
                folder = u
                status = "Folder set."
            }
        }
        val dir = folder?.let { DocumentFile.fromTreeUri(this, it) }

        Column(Modifier.padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lyrics", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (dir == null) "Choose music folder" else "Folder: ${dir.name}  (change)")
            }
            OutlinedTextField(q, { q = it }, label = { Text("Artist / song") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && q.isNotBlank(), onClick = {
                    busy = true; status = "Searching…"
                    scope.launch {
                        hits = withContext(Dispatchers.IO) { Lyrics.search(q) }
                        status = "${hits.size} result(s)"; busy = false
                    }
                }) { Text("Search") }
                Button(enabled = !busy && dir != null, onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val songs = mutableListOf<Pair<DocumentFile, DocumentFile>>()
                            scan(dir!!, songs)
                            var ok = 0; var miss = 0; var skip = 0
                            songs.forEachIndexed { i, (d, f) ->
                                val base = f.name!!.substringBeforeLast('.')
                                if (d.findFile("$base.lrc") != null || d.findFile("$base.txt") != null) { skip++; return@forEachIndexed }
                                status = "Fetching ${i + 1}/${songs.size}: $base"
                                val (a, t) = meta(f)
                                val h = Lyrics.find(a, t)
                                if (h != null && saveHit(d, clean(base), h)) ok++ else miss++
                            }
                            status = "Done: $ok saved, $miss not found, $skip already had lyrics."
                        }
                        busy = false
                    }
                }) { Text("Auto-fetch all") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(hits) { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.title, style = MaterialTheme.typography.titleSmall)
                                Text("${h.artist} • ${if (h.synced != null) "synced" else "plain"}", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(enabled = dir != null, onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { saveHit(dir!!, clean("${h.artist} - ${h.title}"), h) }
                                    status = if (ok) "Saved: ${h.artist} - ${h.title}" else "Save failed"
                                }
                            }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}
