package eu.kanade.tachiyomi.animeextension.en.hentaitv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiTV : ParsedAnimeHttpSource() {

    override val name = "Hentai.tv"

    override val baseUrl = "https://hentai.tv"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/trending/", headers)

    override fun popularAnimeSelector(): String = "div.crsl-slde"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.attr("href"))
        title = link.text().ifBlank {
            element.selectFirst("img")?.attr("alt") ?: ""
        }
        thumbnail_url = element.selectFirst("figure img")?.attr("src")
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = "link[rel=next]"

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val selectedGenre = genreFilter?.let { GENRE_LIST[it.state].slug }

        return when {
            query.isNotBlank() ->
                GET("$baseUrl/search/$query/page/$page/", headers)
            !selectedGenre.isNullOrBlank() ->
                GET("$baseUrl/genre/$selectedGenre/page/$page/", headers)
            else ->
                GET("$baseUrl/page/$page/", headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "link[rel=next]"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title()
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        genre = document.select("a[href*=/genre/]").joinToString(", ") { it.text() }
        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val epNumMatch = Regex("Episode\\s*(\\d+\\.?\\d*)", RegexOption.IGNORE_CASE).find(title)
        val epNum = epNumMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 1F

        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = title.ifBlank { "Episode" }
                episode_number = epNum
            },
        )
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("source[src]").forEach { source ->
            val src = source.attr("abs:src")
            if (src.isNotBlank()) {
                val label = source.attr("label").ifBlank { "Default" }
                videoList.add(Video(src, label, src))
            }
        }

        document.select("video[src]").forEach { video ->
            val src = video.attr("abs:src")
            if (src.isNotBlank() && videoList.none { it.url == src }) {
                videoList.add(Video(src, "Default", src))
            }
        }

        document.selectFirst("meta[property=og:video]")?.attr("content")?.let { ogVideo ->
            if (ogVideo.isNotBlank() && videoList.none { it.url == ogVideo }) {
                videoList.add(Video(ogVideo, "Default", ogVideo))
            }
        }

        val scriptText = document.select("script:not([src])").joinToString("\n") { it.data() }
        val urlRegex = Regex("""["']?(https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']?""")
        urlRegex.findAll(scriptText).forEach { match ->
            val src = match.groupValues[1]
            if (videoList.none { it.url == src }) {
                val label = when {
                    src.contains("1080") -> "1080p"
                    src.contains("720") -> "720p"
                    src.contains("480") -> "480p"
                    src.contains("360") -> "360p"
                    else -> "Default"
                }
                videoList.add(Video(src, label, src))
            }
        }

        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank()) {
                runCatching {
                    val iframeDoc = client.newCall(GET(src, headers)).execute().asJsoup()
                    iframeDoc.select("source[src]").forEach { source ->
                        val videoSrc = source.attr("abs:src")
                        if (videoSrc.isNotBlank() && videoList.none { it.url == videoSrc }) {
                            videoList.add(Video(videoSrc, source.attr("label").ifBlank { "Default" }, videoSrc))
                        }
                    }
                    val iframeScript = iframeDoc.select("script:not([src])").joinToString("\n") { it.data() }
                    urlRegex.findAll(iframeScript).forEach { match ->
                        val videoSrc = match.groupValues[1]
                        if (videoList.none { it.url == videoSrc }) {
                            videoList.add(Video(videoSrc, "Default", videoSrc))
                        }
                    }
                }
            }
        }

        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Genre filter is ignored when using text search"),
        GenreFilter(),
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        GENRE_LIST.map { it.name }.toTypedArray(),
    )

    private data class Genre(val name: String, val slug: String)

    companion object {
        private val GENRE_LIST = listOf(
            Genre("All", ""),
            Genre("3D", "3d"),
            Genre("Ahegao", "ahegao"),
            Genre("Anal", "anal"),
            Genre("Big Boobs", "big-boobs"),
            Genre("Blow Job", "blow-job"),
            Genre("Bondage", "bondage"),
            Genre("Censored", "censored"),
            Genre("Creampie", "creampie"),
            Genre("Dark Skin", "dark-skin"),
            Genre("Demon", "demon"),
            Genre("Double Penetration", "double-penetration"),
            Genre("Elf", "elf"),
            Genre("Fantasy", "fantasy"),
            Genre("Femdom", "femdom"),
            Genre("Footjob", "footjob"),
            Genre("Futanari", "futanari"),
            Genre("Gangbang", "gangbang"),
            Genre("Harem", "harem"),
            Genre("HD", "hd"),
            Genre("Historical", "historical"),
            Genre("Housewife", "housewife"),
            Genre("Humiliation", "humiliation"),
            Genre("Incest", "incest"),
            Genre("Lactation", "lactation"),
            Genre("Large Breasts", "large-breasts"),
            Genre("Maid", "maid"),
            Genre("MILF", "milf"),
            Genre("Mind Break", "mind-break"),
            Genre("Monster", "monster"),
            Genre("NTR", "ntr"),
            Genre("Nurse", "nurse"),
            Genre("Office Lady", "office-lady"),
            Genre("Oral", "oral"),
            Genre("Plot", "plot"),
            Genre("Rape", "rape"),
            Genre("Romance", "romance"),
            Genre("School Girl", "school-girl"),
            Genre("Sci-Fi", "sci-fi"),
            Genre("Slaves", "slaves"),
            Genre("Squirting", "squirting"),
            Genre("Succubus", "succubus"),
            Genre("Supernatural", "supernatural"),
            Genre("Swimsuit", "swimsuit"),
            Genre("Tentacles", "tentacles"),
            Genre("Threesome", "threesome"),
            Genre("Torture", "torture"),
            Genre("Toys", "toys"),
            Genre("Uncensored", "uncensored"),
            Genre("Vanilla", "vanilla"),
            Genre("Virgin", "virgin"),
            Genre("Yuri", "yuri"),
        )
    }
}
