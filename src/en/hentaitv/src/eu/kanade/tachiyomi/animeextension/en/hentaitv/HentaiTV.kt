package eu.kanade.tachiyomi.animeextension.en.hentaitv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiTV : AnimeHttpSource() {

    override val name = "Hentai.tv"
    override val baseUrl = "https://hentai.tv"
    override val lang = "en"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiBase = "$baseUrl/wp-json/wp/v2"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiBase/hentai?per_page=20&page=$page&orderby=modified&order=desc&_fields=id,slug,link,title,_links", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiBase/hentai?per_page=20&page=$page&orderby=date&order=desc&_fields=id,slug,link,title,_links", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseAnimePage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val selectedGenre = genreFilter?.let { GENRE_LIST[it.state].slug }.takeIf { it?.isNotBlank() == true }

        val url = "$apiBase/hentai".toHttpUrl().newBuilder().apply {
            addQueryParameter("per_page", "20")
            addQueryParameter("page", page.toString())
            addQueryParameter("orderby", "date")
            addQueryParameter("order", "desc")
            addQueryParameter("_fields", "id,slug,link,title,_links")
            if (query.isNotBlank()) addQueryParameter("search", query)
            if (selectedGenre != null) addQueryParameter("genre_name", selectedGenre)
        }.build()

        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    private fun parseAnimePage(response: Response): AnimesPage {
        val items = json.decodeFromString<List<WpPost>>(response.body.string())
        val totalPages = response.headers["X-WP-TotalPages"]?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        val animes = items.map { post ->
            SAnime.create().apply {
                title = post.title.rendered.unescapeHtml()
                setUrlWithoutDomain("$baseUrl/wp-json/wp/v2/hentai-ep?hentai_id=${post.id}&slug=${post.slug}")
                thumbnail_url = "$baseUrl/wp-content/themes/hentai/resources/assets/img/placeholder.png"
            }
        }

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfter("slug=").substringBefore("&")
        return GET("$baseUrl/$slug/", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("img.rounded")?.attr("abs:src")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = doc.select("a[href*=/genre/]").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val hentaiId = anime.url.substringAfter("hentai_id=").substringBefore("&")
        return GET("$apiBase/episodes?parent=$hentaiId&per_page=100&orderby=date&order=asc&_fields=id,slug,link,title,date", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val items = json.decodeFromString<List<WpPost>>(response.body.string())

        if (items.isEmpty()) return emptyList()

        return items.mapIndexed { index, post ->
            val titleText = post.title.rendered.unescapeHtml()
            val epNum = Regex("(?i)episode\\s*(\\d+\\.?\\d*)").find(titleText)
                ?.groupValues?.get(1)?.toFloatOrNull()
                ?: (index + 1).toFloat()

            SEpisode.create().apply {
                name = titleText
                episode_number = epNum
                setUrlWithoutDomain(post.link.removePrefix(baseUrl))
                date_upload = runCatching { dateFormat.parse(post.date)?.time }.getOrNull() ?: 0L
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // 1. Look for direct iframes (excluding ads)
        val adDomains = listOf("adtng.com", "exoclick.com", "trafficjunky", "adnium")
        val iframes = doc.select("iframe[src]").filter { iframe ->
            val src = iframe.attr("abs:src")
            src.isNotBlank() && adDomains.none { src.contains(it) }
        }

        // 2. Look for scripts with video URLs
        val scriptText = doc.select("script:not([src])").joinToString("\n") { it.data() }

        // 3. Try to get iframes from known video hosts
        val embedUrls = iframes.map { it.attr("abs:src") }.toMutableList()

        // 4. Extract URLs from scripts
        val urlRegex = Regex("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']""")
        urlRegex.findAll(scriptText).forEach { match ->
            val url = match.groupValues[1]
            if (url.isNotBlank()) embedUrls.add(url)
        }

        // 5. Also look for embed URLs in scripts
        val embedRegex = Regex("""["'](https?://(?:streamtape|dood|streamwish|vidhide|mp4upload|filemoon)[^\s"']+)["']""")
        embedRegex.findAll(scriptText).forEach { match ->
            embedUrls.add(match.groupValues[1])
        }

        // Process all found embed URLs
        return embedUrls.distinct().parallelCatchingFlatMapBlocking { url ->
            extractVideosFromUrl(url)
        }.ifEmpty {
            // Fall back: try data-src or og:video
            val ogVideo = doc.selectFirst("meta[property=og:video]")?.attr("content")
            if (!ogVideo.isNullOrBlank()) {
                listOf(Video(ogVideo, "Default", ogVideo))
            } else emptyList()
        }
    }

    private fun extractVideosFromUrl(url: String): List<Video> {
        return when {
            url.contains("streamtape") || url.contains("streamta.pe") ->
                StreamTapeExtractor(client).videoFromUrl(url)?.let { listOf(it) } ?: emptyList()
            url.contains("dood") || url.contains("ds2play") ->
                DoodExtractor(client).videosFromUrl(url)
            url.contains("streamwish") || url.contains("swish") ->
                StreamWishExtractor(client, headers).videosFromUrl(url)
            url.contains("vidhide") || url.contains("vid.hide") ->
                VidHideExtractor(client, headers).videosFromUrl(url)
            url.contains("mp4upload") ->
                Mp4uploadExtractor(client).videosFromUrl(url, headers)
            url.contains(".m3u8") || url.contains(".mp4") ->
                listOf(Video(url, "Default", url, headers))
            else -> {
                // Try fetching the URL and looking for video sources
                runCatching {
                    val doc = client.newCall(GET(url, headers)).execute().asJsoup()
                    val srcList = mutableListOf<Video>()
                    doc.select("source[src]").forEach { src ->
                        val videoUrl = src.attr("abs:src")
                        if (videoUrl.isNotBlank()) {
                            srcList.add(Video(videoUrl, src.attr("label").ifBlank { "Default" }, videoUrl))
                        }
                    }
                    srcList
                }.getOrDefault(emptyList())
            }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Genre filter (ignored with text search)"),
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
            Genre("Futanari", "futanari"),
            Genre("Gangbang", "gangbang"),
            Genre("Harem", "harem"),
            Genre("HD", "hd"),
            Genre("Incest", "incest"),
            Genre("MILF", "milf"),
            Genre("Monster", "monster"),
            Genre("NTR", "ntr"),
            Genre("Oral", "oral"),
            Genre("Romance", "romance"),
            Genre("School Girl", "school-girl"),
            Genre("Tentacles", "tentacles"),
            Genre("Uncensored", "uncensored"),
            Genre("Vanilla", "vanilla"),
            Genre("Yuri", "yuri"),
        )
    }

    // ============================== DTOs =================================

    @Serializable
    data class WpPost(
        val id: Int,
        val slug: String,
        val link: String,
        val title: WpTitle,
        val date: String = "",
    )

    @Serializable
    data class WpTitle(val rendered: String)

    private fun String.unescapeHtml(): String =
        this.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#8217;", "'")
            .replace("&#8216;", "'")
            .replace("&#8211;", "–")
}
