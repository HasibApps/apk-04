package com.example.ytdl

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ───────────────────────── Formats ─────────────────────────

enum class Fmt(val short: String, val height: Int = 0, val audio: Boolean = false) {
    V1080("1080p", 1080), V720("720p", 720), V480("480p", 480), V360("360p", 360),
    MP4("MP4"), MP3("MP3", 0, true), M4A("M4A", 0, true);

    val label: String get() = when (this) { MP4 -> "🎬 MP4 (best)"; MP3 -> "🎵 MP3"; M4A -> "🎧 M4A"; else -> "🎬 $short" }
}

enum class EngineMode(val title: String) {
    HYBRID("Hybrid / Auto Fallback"),
    FLASK("Flask API (pytubefix)"),
    NATIVE("Native yt-dlp + FFmpeg")
}

enum class ThemeMode(val title: String) {
    SYSTEM("System Default"), LIGHT("Light"), DARK("Dark"), AMOLED("AMOLED Black")
}

enum class Status { QUEUED, RUNNING, PAUSED, FAILED }

/** Video information (from yt-dlp --dump-json or the Flask API). sizes = estimated bytes per format. */
class Info(
    val title: String,
    val thumb: String?,
    val author: String?,
    val duration: Int,
    val sizes: Map<Fmt, Long> = emptyMap()
)

/** One item in the Active Queue. All fields are Compose state so the UI updates live. */
class DlTask(val id: Long, val url: String, val fmt: Fmt) {
    var title by mutableStateOf("")
    var thumb by mutableStateOf<String?>(null)
    var channel by mutableStateOf("")
    var status by mutableStateOf(Status.QUEUED)
    var progress by mutableFloatStateOf(0f)
    var speed by mutableStateOf("")
    var eta by mutableStateOf("")
    var stage by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var autoPaused by mutableStateOf(false)

    @Volatile var gen = 0
    @Volatile var stop: String? = null   // "pause" | "cancel" | null
    var job: Job? = null
}

data class HItem(
    val id: Long, val title: String, val channel: String, val thumb: String?, val url: String,
    val quality: String, val uri: String, val mime: String, val size: Long, val date: Long
)

// ───────────────────────── Settings (Jetpack DataStore) ─────────────────────────

data class Cfg(
    val engine: EngineMode = EngineMode.HYBRID,
    val apiUrl: String = "",
    val fmt: Fmt = Fmt.V1080,
    val concurrent: Int = 2,
    val wifiOnly: Boolean = false,
    val embedThumb: Boolean = true,
    val embedMeta: Boolean = true,
    val subs: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK
)

private val Context.settingsDs by preferencesDataStore("settings")

object SettingsStore {
    private val kEngine = stringPreferencesKey("engine")
    private val kApi = stringPreferencesKey("api_url")
    private val kFmt = stringPreferencesKey("fmt")
    private val kConc = intPreferencesKey("concurrent")
    private val kWifi = booleanPreferencesKey("wifi_only")
    private val kThumb = booleanPreferencesKey("embed_thumb")
    private val kMeta = booleanPreferencesKey("embed_meta")
    private val kSubs = booleanPreferencesKey("subs")
    private val kTheme = stringPreferencesKey("theme")

    fun flow(c: Context): Flow<Cfg> = c.settingsDs.data.map { p ->
        val d = Cfg()
        Cfg(
            engine = runCatching { EngineMode.valueOf(p[kEngine] ?: "") }.getOrDefault(d.engine),
            apiUrl = p[kApi] ?: "",
            fmt = runCatching { Fmt.valueOf(p[kFmt] ?: "") }.getOrDefault(d.fmt),
            concurrent = (p[kConc] ?: d.concurrent).coerceIn(1, 3),
            wifiOnly = p[kWifi] ?: d.wifiOnly,
            embedThumb = p[kThumb] ?: d.embedThumb,
            embedMeta = p[kMeta] ?: d.embedMeta,
            subs = p[kSubs] ?: d.subs,
            theme = runCatching { ThemeMode.valueOf(p[kTheme] ?: "") }.getOrDefault(d.theme)
        )
    }

    private suspend fun edit(c: Context, block: (MutablePreferences) -> Unit) { c.settingsDs.edit { block(it) } }

    suspend fun setEngine(c: Context, v: EngineMode) = edit(c) { it[kEngine] = v.name }
    suspend fun setApi(c: Context, v: String) = edit(c) { it[kApi] = v.trim() }
    suspend fun setFmt(c: Context, v: Fmt) = edit(c) { it[kFmt] = v.name }
    suspend fun setConcurrent(c: Context, v: Int) = edit(c) { it[kConc] = v.coerceIn(1, 3) }
    suspend fun setWifi(c: Context, v: Boolean) = edit(c) { it[kWifi] = v }
    suspend fun setThumb(c: Context, v: Boolean) = edit(c) { it[kThumb] = v }
    suspend fun setMeta(c: Context, v: Boolean) = edit(c) { it[kMeta] = v }
    suspend fun setSubs(c: Context, v: Boolean) = edit(c) { it[kSubs] = v }
    suspend fun setTheme(c: Context, v: ThemeMode) = edit(c) { it[kTheme] = v.name }
}

// ───────────────────────── History database (SQLite) ─────────────────────────

class HistoryDb(c: Context) : SQLiteOpenHelper(c, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, channel TEXT, thumb TEXT, " +
                "url TEXT, quality TEXT, uri TEXT, mime TEXT, size INTEGER, date INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun add(h: HItem): Long {
        val cv = ContentValues().apply {
            put("title", h.title); put("channel", h.channel); put("thumb", h.thumb); put("url", h.url)
            put("quality", h.quality); put("uri", h.uri); put("mime", h.mime); put("size", h.size); put("date", h.date)
        }
        return writableDatabase.insert("history", null, cv)
    }

    fun all(): List<HItem> {
        val out = ArrayList<HItem>()
        readableDatabase.rawQuery(
            "SELECT id,title,channel,thumb,url,quality,uri,mime,size,date FROM history ORDER BY date DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HItem(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3), c.getString(4) ?: "",
                        c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: "video/*", c.getLong(8), c.getLong(9)
                    )
                )
            }
        }
        return out
    }

    fun delete(id: Long) { writableDatabase.delete("history", "id=?", arrayOf(id.toString())) }
}
