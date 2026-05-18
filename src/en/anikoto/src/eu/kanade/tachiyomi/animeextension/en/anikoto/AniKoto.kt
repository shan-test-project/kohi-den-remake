package eu.kanade.tachiyomi.animeextension.en.anikoto

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AniKoto : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AniKoto"
    override val baseUrl = "https://anikototv.to"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("X-Requested-With", "XMLHttpRequest")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/filter?sort=most_watched&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/filter?sort=recently_updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseAnimePage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()

        val url = StringBuilder("$baseUrl/filter?page=$page")
        if (query.isNotBlank()) url.append("&keyword=${query.trim()}")
        typeFilter?.let { if (it.state != 0) url.append("&type=${TYPE_LIST[it.state].second}") }
        statusFilter?.let { if (it.state != 0) url.append("&status=${STATUS_LIST[it.state].second}") }
        genreFilter?.selectedGenres()?.forEach { url.append("&genre[]=$it") }

        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseAnimePage(response)

    private fun parseAnimePage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div.item, article.item, div.film-poster, div.flw-item, div.item-film")
            .ifEmpty { doc.select("div[class*=item]").filter { it.selectFirst("a[href*=/watch/]") != null } }

        val animes = items.mapNotNull { el ->
            val link = el.selectFirst("a[href*=/watch/]") ?: return@mapNotNull null
            SAnime.create().apply {
                title = el.selectFirst("h2, h3, .title, .film-name, [class*=title]")?.text()?.trim()
                    ?: link.attr("title").ifBlank { link.text() }
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = el.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }

        val hasNextPage = doc.selectFirst("a[href*=page][class*=next], li.next a, .pagination a[rel=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.title, h1, [class*=anime-title]")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".film-poster img, [class*=poster] img")?.attr("src")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("[class*=description], [class*=overview], [class*=synopsis]")?.text()
            genre = doc.select("a[href*=/genre/], a[href*=/tag/]").joinToString(", ") { it.text() }
            author = doc.selectFirst("[class*=studio] a, [class*=producer] a")?.text()
            status = when {
                doc.text().contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
                doc.text().contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers.newBuilder().set("X-Requested-With", "").build())

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val slug = response.request.url.pathSegments.dropLast(1).last()

        val episodeLinks = doc.select(".episodes a, [class*=episode] a, ul.episodes li a")
            .filter { it.attr("href").contains("/ep-") || it.attr("data-num") != null }

        if (episodeLinks.isNotEmpty()) {
            return episodeLinks.mapIndexed { idx, el ->
                val href = el.attr("abs:href").ifBlank { "$baseUrl/watch/$slug/ep-${idx + 1}" }
                val num = el.attr("data-num").toFloatOrNull()
                    ?: Regex("ep-(\\d+)").find(href)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (idx + 1).toFloat()
                SEpisode.create().apply {
                    name = el.text().ifBlank { "Episode ${num.toInt()}" }
                    episode_number = num
                    setUrlWithoutDomain(href.removePrefix(baseUrl))
                }
            }.reversed()
        }

        // Fallback: try to find episode count and generate list
        val epCountText = doc.select("[class*=episode-count], [class*=ep-count]").text()
        val epCount = Regex("(\\d+)").find(epCountText)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        return (1..epCount).map { num ->
            SEpisode.create().apply {
                name = "Episode $num"
                episode_number = num.toFloat()
                setUrlWithoutDomain("/watch/$slug/ep-$num")
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", headers.newBuilder().set("X-Requested-With", "").build())

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        // 1. Find server list
        val servers = doc.select("#w-servers li, [class*=server] li, ul[class*=server] li, ul.server li")

        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

        val embedUrls = mutableListOf<Pair<String, String>>() // (url, serverName)

        // 2. Try to extract iframe sources for each server
        if (servers.isEmpty()) {
            // Single player page - look for iframes directly
            val adDomains = listOf("adtng.com", "exoclick", "trafficjunky", "adnium", "adskeeper")
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotBlank() && adDomains.none { src.contains(it) }) {
                    embedUrls.add(Pair(src, "Server 1"))
                }
            }
        } else {
            servers.forEachIndexed { idx, server ->
                val serverName = server.text().trim().ifBlank { "Server ${idx + 1}" }
                val dataId = server.attr("data-id").ifBlank { server.attr("data-server") }
                if (dataId.isNotBlank()) {
                    // Try to get embed URL via form POST
                    runCatching {
                        val formBody = FormBody.Builder()
                            .add("action", "player_ajax")
                            .add("post", dataId)
                            .add("tipo", server.attr("data-type").ifBlank { "movie" })
                            .build()
                        val ajaxResponse = client.newCall(
                            POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody),
                        ).execute().asJsoup()
                        ajaxResponse.select("iframe[src]").forEach { iframe ->
                            val src = iframe.attr("abs:src")
                            if (src.isNotBlank()) embedUrls.add(Pair(src, serverName))
                        }
                    }
                }
            }
        }

        // 3. Parse scripts for video URLs
        val scriptText = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val urlRegex = Regex("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']""")
        urlRegex.findAll(scriptText).forEach { match ->
            val url = match.groupValues[1]
            if (url.isNotBlank()) embedUrls.add(Pair(url, "Direct"))
        }

        // 4. Extract videos from all found URLs
        val videos = embedUrls.distinct().parallelCatchingFlatMapBlocking { (url, serverName) ->
            extractVideosFromUrl(url, serverName)
        }

        return videos.sortedWith(
            compareBy(
                { it.quality.contains(preferredServer ?: "", ignoreCase = true).not() },
                { Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull()?.unaryMinus() ?: 0 },
            ),
        )
    }

    private fun extractVideosFromUrl(url: String, serverName: String = ""): List<Video> {
        val prefix = if (serverName.isNotBlank()) "$serverName - " else ""
        return when {
            url.contains("streamtape") || url.contains("streamta.pe") ->
                StreamTapeExtractor(client).videoFromUrl(url, "${prefix}StreamTape")?.let { listOf(it) } ?: emptyList()
            url.contains("dood") || url.contains("ds2play") || url.contains("doodstream") ->
                DoodExtractor(client).videosFromUrl(url, prefix)
            url.contains("streamwish") || url.contains("swdyu") || url.contains("swish") ->
                StreamWishExtractor(client, headers).videosFromUrl(url, prefix)
            url.contains("vidhide") || url.contains("vid.hide") || url.contains("luluvdo") ->
                VidHideExtractor(client, headers).videosFromUrl(url, videoNameGen = { "${prefix}VidHide:$it" })
            url.contains("mp4upload") ->
                Mp4uploadExtractor(client).videosFromUrl(url, headers)
            url.contains("filemoon") ->
                FilemoonExtractor(client).videosFromUrl(url, prefix)
            url.contains(".m3u8") || url.contains(".mp4") ->
                listOf(Video(url, "${prefix}Direct", url, headers))
            else -> {
                runCatching {
                    val doc = client.newCall(GET(url, headers)).execute().asJsoup()
                    val vids = mutableListOf<Video>()
                    doc.select("source[src]").forEach { src ->
                        val videoUrl = src.attr("abs:src")
                        if (videoUrl.isNotBlank()) {
                            val quality = src.attr("label").ifBlank { src.attr("size").ifBlank { "Default" } }
                            vids.add(Video(videoUrl, "$prefix$quality", videoUrl))
                        }
                    }
                    vids
                }.getOrDefault(emptyList())
            }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: filters are ignored with text search"),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private class TypeFilter : AnimeFilter.Select<String>("Type", TYPE_LIST.map { it.first }.toTypedArray())
    private class StatusFilter : AnimeFilter.Select<String>("Status", STATUS_LIST.map { it.first }.toTypedArray())

    private class GenreCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class GenreFilter : AnimeFilter.Group<GenreCheckBox>(
        "Genre",
        GENRE_LIST.map { GenreCheckBox(it.first, it.second) },
    ) {
        fun selectedGenres() = state.filter { it.state }.map { it.value }
    }

    companion object {
        private val TYPE_LIST = listOf(
            Pair("All", ""),
            Pair("Movie", "movie"),
            Pair("TV", "tv"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
        )
        private val STATUS_LIST = listOf(
            Pair("All", ""),
            Pair("Airing", "airing"),
            Pair("Completed", "completed"),
            Pair("Upcoming", "upcoming"),
        )
        private val GENRE_LIST = listOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Mecha", "mecha"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
        )

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "StreamTape",
            "DoodStream",
            "VidHide",
            "Mp4Upload",
            "Filemoon",
        )
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }
    }
}
