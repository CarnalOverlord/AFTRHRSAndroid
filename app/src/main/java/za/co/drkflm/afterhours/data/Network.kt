package za.co.drkflm.afterhours.data

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

object Network {
    private lateinit var cookieJar: PersistentCookieJar
    lateinit var http: OkHttpClient
        private set

    fun initialize(context: Context) {
        if (::http.isInitialized) return
        cookieJar = PersistentCookieJar(context.getSharedPreferences("aftrhrs_session", Context.MODE_PRIVATE))
        http = OkHttpClient.Builder().cookieJar(cookieJar).build()
    }

    fun cookieHeader(): String = cookieJar.loadForRequest(ApiClient.ORIGIN.toHttpUrl())
        .joinToString("; ") { "${it.name}=${it.value}" }
}

private class PersistentCookieJar(private val preferences: SharedPreferences) : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    init {
        preferences.getStringSet(KEY, emptySet()).orEmpty().forEach { encoded ->
            Cookie.parse(ApiClient.ORIGIN.toHttpUrl(), encoded)?.let(cookies::add)
        }
        removeExpired()
    }

    @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
        incoming.forEach { fresh ->
            cookies.removeAll { it.name == fresh.name && it.domain == fresh.domain && it.path == fresh.path }
            if (fresh.expiresAt > System.currentTimeMillis()) cookies += fresh
        }
        persist()
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (removeExpired()) persist()
        return cookies.filter { it.matches(url) }
    }

    private fun removeExpired(): Boolean = cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
    private fun persist() = preferences.edit().putStringSet(KEY, cookies.map { it.toString() }.toSet()).apply()

    private companion object { const val KEY = "cookies" }
}
