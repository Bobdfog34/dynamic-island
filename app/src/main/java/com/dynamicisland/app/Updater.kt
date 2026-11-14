package com.dynamicisland.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String
)

class Updater(private val repo: String = "Bobdfog34/dynamic-island") {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun check(currentVersionName: String, callback: (UpdateInfo?) -> Unit) {
        executor.execute {
            var result: UpdateInfo? = null
            try {
                val url = URL("https://api.github.com/repos/$repo/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "DynamicIsland")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name", "")
                    val notes = json.optString("body", "")
                    val pageUrl = json.optString("html_url", "")
                    var apkUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name", "").endsWith(".apk")) {
                                val u = a.optString("browser_download_url", "")
                                if (u.isNotEmpty()) apkUrl = u
                                break
                            }
                        }
                    }
                    val ver = tag.trimStart('v', 'V')
                    if (isNewer(ver, currentVersionName)) {
                        result = UpdateInfo(tag, ver, notes, apkUrl, pageUrl)
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
            main.post { callback(result) }
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        if (latest.isBlank()) return false
        val a = latest.split(".").mapNotNull { it.toIntOrNull() }
        val b = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
