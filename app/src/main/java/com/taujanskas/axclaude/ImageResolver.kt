package com.taujanskas.axclaude

import org.jsoup.Jsoup
import java.net.URL
import kotlin.math.abs

/**
 * The accessibility tree tells us which images are on screen (and their alt text) but not
 * their web addresses. So we download the page's HTML and match on-screen images to <img>
 * tags: first by alt text, then by shape (width/height ratio) for images without alt text.
 */
object ImageResolver {

    data class PageImage(val url: String, val alt: String, val ratio: Double?)

    data class Result(
        /** On-screen images, each with its web address if it could be matched. */
        val matched: List<Pair<VisibleImage, PageImage?>>,
        val debug: String
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Android 16; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0"

    fun resolve(pageUrl: String, visible: List<VisibleImage>): Result {
        var debug: String
        val pageImages = try {
            val list = fetchPageImages(pageUrl)
            debug = "fetched ${list.size} <img> tags (${list.count { it.alt.isNotEmpty() }} with alt, " +
                "${list.count { it.ratio != null }} with size)"
            list
        } catch (e: Exception) {
            debug = "fetch failed: ${e.javaClass.simpleName}: ${e.message}"
            emptyList()
        }
        return Result(match(visible, pageImages), debug)
    }

    private fun fetchPageImages(pageUrl: String): List<PageImage> {
        val doc = Jsoup.connect(pageUrl)
            .userAgent(USER_AGENT)
            .timeout(6000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .maxBodySize(4_000_000)
            .get()
        val base = URL(doc.location().ifEmpty { pageUrl })

        return doc.select("img").mapNotNull { img ->
            val src = listOf("src", "data-src", "data-lazy-src", "data-original")
                .map { img.absUrl(it) }
                .firstOrNull { it.startsWith("http") }
                ?: firstSrcsetUrl(img.attr("srcset"), base)
            val w = img.attr("width").filter { it.isDigit() }.toDoubleOrNull()
            val h = img.attr("height").filter { it.isDigit() }.toDoubleOrNull()
            val ratio = if (w != null && h != null && w > 0 && h > 0) w / h else null
            src?.let { PageImage(it, img.attr("alt").trim(), ratio) }
        }.distinctBy { it.url }
    }

    private fun firstSrcsetUrl(srcset: String, base: URL): String? {
        val candidate = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        if (candidate.isNullOrBlank()) return null
        return try {
            URL(base, candidate).toString().takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun match(
        visible: List<VisibleImage>,
        pageImages: List<PageImage>
    ): List<Pair<VisibleImage, PageImage?>> {
        val unused = pageImages.toMutableList()
        val result = visible.map { v -> v to null as PageImage? }.toMutableList()

        // Pass 1: alt text.
        result.indices.forEach { i ->
            val d = norm(result[i].first.description)
            if (d.isEmpty()) return@forEach
            val found = unused.firstOrNull { norm(it.alt) == d }
                ?: unused.firstOrNull {
                    val a = norm(it.alt)
                    a.length >= 4 && d.length >= 4 && (a.contains(d) || d.contains(a))
                }
            if (found != null) {
                unused.remove(found)
                result[i] = result[i].first to found
            }
        }

        // Pass 2: shape, for images still unmatched (within 4%).
        result.indices.forEach { i ->
            if (result[i].second != null) return@forEach
            val b = result[i].first.bounds
            if (b.height() <= 0) return@forEach
            val r = b.width().toDouble() / b.height()
            val found = unused
                .filter { it.ratio != null && abs(it.ratio - r) / r < 0.04 }
                .minByOrNull { abs(it.ratio!! - r) }
            if (found != null) {
                unused.remove(found)
                result[i] = result[i].first to found
            }
        }
        return result
    }
}
