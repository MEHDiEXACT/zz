package app.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Hit(val title: String, val artist: String, val source: String, val synced: String?, val plain: String?)

object Lyrics {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun get(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "LyricsApk/1.0")
        if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }

    private fun JSONObject.str(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

    private fun hit(o: JSONObject) = Hit(
        o.str("trackName") ?: "", o.str("artistName") ?: "", "LRCLIB",
        o.str("syncedLyrics"), o.str("plainLyrics")
    )

    /** Free-text search (LRCLIB). */
    fun search(q: String): List<Hit> {
        val r = get("https://lrclib.net/api/search?q=${enc(q)}") ?: return emptyList()
        return try {
            val a = JSONArray(r)
            (0 until minOf(a.length(), 20)).map { hit(a.getJSONObject(it)) }
                .filter { it.synced != null || it.plain != null }
        } catch (e: Exception) { emptyList() }
    }

    /** Best match for a known song: tries each source in turn. */
    fun find(artist: String, title: String): Hit? {
        if (artist.isNotBlank()) {
            get("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}")?.let {
                try { val h = hit(JSONObject(it)); if (h.synced != null || h.plain != null) return h } catch (_: Exception) {}
            }
        }
        search("$artist $title".trim()).firstOrNull()?.let { return it }
        if (artist.isNotBlank()) {
            get("https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}")?.let {
                try {
                    val t = JSONObject(it).str("lyrics")
                    if (t != null) return Hit(title, artist, "lyrics.ovh", null, t)
                } catch (_: Exception) {}
            }
        }
        return null
    }
}
