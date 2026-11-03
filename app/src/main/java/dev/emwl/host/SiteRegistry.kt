package dev.emwl.host

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** In-memory view of the sites currently hosted by this process. */
class SiteRegistry(private val siteCount: Int) {

    private val hits = ConcurrentHashMap<String, AtomicLong>()

    fun siteNames(): List<String> = (0 until siteCount.coerceIn(0, Ports.MAX_SITES)).map { "site-${it + 1}" }

    fun portOf(name: String): Int? {
        val index = siteNames().indexOf(name)
        return if (index < 0) null else Ports.sitePort(index)
    }

    fun hit(name: String) {
        hits.getOrPut(name) { AtomicLong() }.incrementAndGet()
    }

    fun hitsOf(name: String): Long = hits[name]?.get() ?: 0

    fun toJson(): JSONArray = JSONArray().apply {
        siteNames().forEach { name ->
            put(
                JSONObject()
                    .put("name", name)
                    .put("port", portOf(name))
                    .put("hits", hitsOf(name))
            )
        }
    }
}
