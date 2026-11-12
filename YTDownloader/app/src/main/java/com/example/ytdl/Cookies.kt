package com.example.ytdl

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cookie store for private / login-only videos (e.g. Facebook "only available for registered users").
 * Cookies are kept in the app's private storage as a Netscape cookies.txt file, which is what yt-dlp's --cookies expects.
 *
 * Accepted input formats: Netscape cookies.txt, JSON export (EditThisCookie / Cookie-Editor), or a raw
 * "Cookie:" header string (name=value; name2=value2) which is treated as Facebook cookies.
 */
object Cookies {
    private const val HEADER = "# Netscape HTTP Cookie File"
    private const val HTTP_ONLY = "#HttpOnly_"
    private const val YEAR = 31_536_000L

    var count by mutableIntStateOf(0)
        private set
    var hasFacebookLogin by mutableStateOf(false)
        private set

    private lateinit var file: File

    fun init(c: Context) {
        file = File(c.filesDir, "cookies.txt")
        refresh()
    }

    fun exists(): Boolean = ::file.isInitialized && file.exists() && count > 0

    // ───────────── storage ─────────────

    private fun readLines(): List<String> =
        if (::file.isInitialized && file.exists())
            file.readLines().filter { it.isNotBlank() && (!it.startsWith("#") || it.startsWith(HTTP_ONLY)) }
        else emptyList()

    private fun write(lines: List<String>) {
        file.writeText(HEADER + "\n" + lines.joinToString("\n") + "\n")
        refresh()
    }

    private fun domainOf(line: String) = line.removePrefix(HTTP_ONLY).substringBefore('\t')

    private fun refresh() {
        val ls = readLines()
        count = ls.size
        hasFacebookLogin = ls.any { domainOf(it).endsWith("facebook.com") && it.split('\t').getOrNull(5) == "xs" }
    }

    fun clear() {
        if (::file.isInitialized) file.delete()
        refresh()
    }

    /** Per-task private copy (yt-dlp rewrites the cookie file when it exits; parallel downloads must not share one). */
    fun use(r: YoutubeDLRequest, dir: File, tag: String): File? {
        if (!exists()) return null
        return try {
            val f = File(dir, "cookies_$tag.txt")
            file.copyTo(f, true)
            r.addOption("--cookies", f.absolutePath)
            f
        } catch (_: Throwable) { null }
    }

    // ───────────── saving ─────────────

    /** Saves pasted / imported text (replaces everything). Returns the number of cookies saved, 0 = invalid input. */
    fun saveText(text: String): Int {
        val lines = normalize(text)
        if (lines.isEmpty()) return 0
        write(lines)
        return lines.size
    }

    /** Saves the cookie string of a logged-in WebView (name=value; …) as Facebook cookies, keeping other sites' cookies. */
    fun mergeFacebook(header: String): Int {
        val fresh = fromHeader(header, ".facebook.com")
        if (fresh.isEmpty()) return 0
        write(readLines().filter { !domainOf(it).endsWith("facebook.com") } + fresh)
        return fresh.size
    }

    // ───────────── parsing ─────────────

    private fun normalize(text: String): List<String> {
        val t = text.trim().trimStart('﻿')
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("[") || t.startsWith("{")) return fromJson(t)
        val ns = fromNetscape(t)
        if (ns.isNotEmpty()) return ns
        return fromHeader(t, ".facebook.com")
    }

    private fun fromNetscape(t: String): List<String> {
        val out = ArrayList<String>()
        for (raw in t.lineSequence()) {
            var s = raw.trim('\r', '\n')
            if (s.isBlank()) continue
            var http = false
            if (s.startsWith(HTTP_ONLY)) { http = true; s = s.removePrefix(HTTP_ONLY) } else if (s.startsWith("#")) continue
            val p = if ('\t' in s) s.split('\t') else s.trim().split(Regex("\\s+"), 7)
            if (p.size < 7) continue
            val inc = p[1].trim().uppercase(); val sec = p[3].trim().uppercase()
            if ((inc != "TRUE" && inc != "FALSE") || (sec != "TRUE" && sec != "FALSE")) continue
            if (p[4].trim().toLongOrNull() == null || p[5].isBlank()) continue
            out.add((if (http) HTTP_ONLY else "") + listOf(p[0].trim(), inc, p[2].trim(), sec, p[4].trim(), p[5].trim(), p[6].trim()).joinToString("\t"))
        }
        return out
    }

    private fun fromJson(t: String): List<String> = try {
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("cookies") ?: JSONArray()
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val domain = o.optString("domain"); val name = o.optString("name")
            if (domain.isBlank() || name.isBlank()) continue
            val exp = o.optDouble("expirationDate", 0.0).toLong().let { if (it > 0) it else now + YEAR }
            out.add(
                (if (o.optBoolean("httpOnly")) HTTP_ONLY else "") + listOf(
                    domain, if (domain.startsWith(".")) "TRUE" else "FALSE", o.optString("path", "/").ifBlank { "/" },
                    if (o.optBoolean("secure")) "TRUE" else "FALSE", exp.toString(), name, o.optString("value")
                ).joinToString("\t")
            )
        }
        out
    } catch (_: Throwable) { emptyList() }

    private fun fromHeader(h: String, domain: String): List<String> {
        val exp = System.currentTimeMillis() / 1000 + YEAR
        return h.trim().removePrefix("Cookie:").removePrefix("cookie:").split(';').mapNotNull { part ->
            val s = part.trim(); val i = s.indexOf('=')
            if (i <= 0) null else listOf(domain, "TRUE", "/", "TRUE", exp.toString(), s.substring(0, i).trim(), s.substring(i + 1).trim()).joinToString("\t")
        }
    }
}
