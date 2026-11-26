package com.taujanskas.axclaude

import org.jsoup.Jsoup
import java.net.URL

/**
 * The accessibility tree tells us which images are on screen (and their alt text) but not
 * their web addresses. So we download the page's HTML and match on-screen images to <img>
 * tags by their alt text.
 */
object ImageResolver {

    data class PageImage(val url: String, val alt: String)

    data class Result(
        /** On-screen images, each with its web address if it could be matched. */
        val matched: List<Pair<VisibleImage, PageImage?>>
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Android 16; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0"

    fun resolve(pageUrl: String, visible: List<VisibleImage>): Result {
        val pageImages = try {
            fetchPageImages(pageUrl)
        } catch (e: Exception) {
            emptyList()
        }
        return Result(match(visible, pageImages))
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
            src?.let { PageImage(it, img.attr("alt").trim()) }
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
        return visible.map { v ->
            val d = norm(v.description)
            val found = if (d.isEmpty()) null else
                unused.firstOrNull { norm(it.alt) == d }
                    ?: unused.firstOrNull {
                        val a = norm(it.alt)
                        a.length >= 4 && d.length >= 4 && (a.contains(d) || d.contains(a))
                    }
            if (found != null) unused.remove(found)
            v to found
        }
    }
}
