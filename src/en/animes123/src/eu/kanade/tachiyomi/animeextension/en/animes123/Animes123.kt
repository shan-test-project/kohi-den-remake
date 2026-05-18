package eu.kanade.tachiyomi.animeextension.en.animes123

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
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Animes123 : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "123Animes"
    override val baseUrl = "https://123animes.ru"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "en-US,en;q=0.9")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/home?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("a[href*=/anime/]").filter { el ->
            el.selectFirst("img") != null && !el.attr("href").contains("episode")
        }.distinctBy { it.attr("href") }.map { el ->
            SAnime.create().apply {
                title = el.selectFirst("img")?.attr("alt")?.trim()
                    ?: el.selectFirst("[class*=title], [class*=name]")?.text()?.trim()
                    ?: el.text().trim()
                setUrlWithoutDomain(el.attr("href"))
                thumbnail_url = el.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }.filter { it.title?.isNotBlank() == true }

        val hasNextPage = doc.selectFirst("a.next, [class*=next-page], li.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/recent-release?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.item, [class*=item], li[class*=episode]").mapNotNull { el ->
            val link = el.selectFirst("a[href*=/anime/]") ?: return@mapNotNull null
            SAnime.create().apply {
                title = el.selectFirst("img")?.attr("alt")?.trim()
                    ?: el.selectFirst("[class*=title], [class*=name], .name")?.text()?.trim()
                    ?: link.text().trim()
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = el.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }.filter { it.title?.isNotBlank() == true }

        val hasNextPage = doc.selectFirst("a.next, [class*=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()

        return when {
            query.isNotBlank() ->
                GET("$baseUrl/filter?keyword=${query.trim()}&page=$page", headers)
            genreFilter?.state != 0 ->
                GET("$baseUrl/genre/${GENRE_LIST[genreFilter.state].second}?page=$page", headers)
            typeFilter?.state != 0 ->
                GET("$baseUrl/${TYPE_LIST[typeFilter.state].second}?page=$page", headers)
            else ->
                GET("$baseUrl/all-anime-shows?page=$page", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(
            "div.item, [class*=item], div.mse, [class*=card], " +
                "div[class*=anime], li[class*=anime], a[href*=/anime/]",
        ).mapNotNull { el ->
            val link = if (el.tagName() == "a") el
            else el.selectFirst("a[href*=/anime/]") ?: return@mapNotNull null
            SAnime.create().apply {
                title = el.selectFirst("img")?.attr("alt")?.trim()
                    ?: el.selectFirst("h2, h3, [class*=title], [class*=name], .name")?.text()?.trim()
                    ?: link.attr("title").ifBlank { link.text().trim() }
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = el.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }.distinctBy { it.url }.filter { it.title?.isNotBlank() == true }

        val hasNextPage = doc.selectFirst("a.next, [class*=next-page], li.next a") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1, [class*=anime-title], [class*=title]")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("[class*=poster] img, [class*=cover] img, .thumb img")?.attr("abs:src")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("[class*=description], [class*=overview], [class*=synopsis], .ptext")?.text()
            genre = doc.select("a[href*=/genre/]").joinToString(", ") { it.text() }
            author = doc.selectFirst("a[href*=/studio/], [class*=studio] a")?.text()
            status = parseStatus(
                doc.selectFirst("[class*=status], span:contains(Status)")?.text() ?: "",
            )
        }
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
        text.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val animeSlug = response.request.url.pathSegments.last()

        // Find episode links from the page
        val epElements = doc.select(
            "a[href*=/watch/], " +
                "[class*=ep-item] a, [class*=episode-item] a, " +
                "li[class*=ep] a, a.ep, " +
                ".thumb.tooltipstered",
        ).filter {
            it.attr("href").contains("/watch/") || it.attr("class").contains("tooltipstered")
        }

        if (epElements.isNotEmpty()) {
            return epElements.mapIndexed { idx, el ->
                val href = el.attr("abs:href").ifBlank {
                    val dataId = el.attr("data-id").ifBlank { animeSlug }
                    val ts = el.attr("data-ts").ifBlank { (idx + 1).toString().padStart(3, '0') }
                    "$baseUrl/watch/$dataId/$ts"
                }
                val epNum = Regex("/(\\d+)$").find(href.trimEnd('/'))
                    ?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (idx + 1).toFloat()
                SEpisode.create().apply {
                    name = el.attr("data-jtitle").ifBlank {
                        el.text().ifBlank { "Episode ${epNum.toInt()}" }
                    }
                    episode_number = epNum
                    setUrlWithoutDomain(href.removePrefix(baseUrl))
                }
            }.reversed()
        }

        // Fallback using data-ts attributes
        val tsElements = doc.select("[data-ts]")
        if (tsElements.isNotEmpty()) {
            return tsElements.mapIndexed { idx, el ->
                val ts = el.attr("data-ts")
                val epNum = ts.trimStart('0').toFloatOrNull() ?: (idx + 1).toFloat()
                SEpisode.create().apply {
                    name = "Episode ${ts.trimStart('0').ifBlank { "1" }}"
                    episode_number = epNum
                    setUrlWithoutDomain("/watch/$animeSlug/$ts")
                }
            }.reversed()
        }

        return emptyList()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request =
        GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val embedUrls = mutableListOf<Pair<String, String>>()

        val adDomains = listOf("adtng", "exoclick", "trafficjunky", "adnium", "adskeeper", "ad-plus")

        // 1. Find server tabs/buttons
        val servers = doc.select(
            "[class*=server] li, [class*=server-item], " +
                "[class*=host] li, ul[class*=server] li, " +
                "#servers li, .server-tab li",
        )

        if (servers.isNotEmpty()) {
            servers.forEachIndexed { idx, server ->
                val name = server.text().trim().ifBlank { "Server ${idx + 1}" }
                val dataId = server.attr("data-id").ifBlank {
                    server.attr("data-server").ifBlank { server.attr("id") }
                }
                if (dataId.isNotBlank()) {
                    // Try internal player AJAX
                    runCatching {
                        val ajaxResp = client.newCall(
                            GET("$baseUrl/ajax/server/$dataId", headers),
                        ).execute().asJsoup()
                        ajaxResp.select("iframe[src]").forEach { iframe ->
                            val src = iframe.attr("abs:src")
                            if (src.isNotBlank() && adDomains.none { src.contains(it) }) {
                                embedUrls.add(Pair(src, name))
                            }
                        }
                    }
                }
            }
        }

        // 2. Direct iframes on page
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank() && adDomains.none { src.contains(it) }) {
                embedUrls.add(Pair(src, "Server 1"))
            }
        }

        // 3. Parse scripts
        val scriptText = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val urlRegex = Regex("""["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']""")
        urlRegex.findAll(scriptText).forEach { match ->
            val url = match.groupValues[1]
            if (url.isNotBlank()) embedUrls.add(Pair(url, "Direct"))
        }

        val embedRegex = Regex(
            """["'](https?://(?:streamtape|dood|streamwish|vidhide|mp4upload|filemoon|luluvdo)[^\s"']+)["']""",
        )
        embedRegex.findAll(scriptText).forEach { match ->
            embedUrls.add(Pair(match.groupValues[1], "Stream"))
        }

        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

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
            url.contains("streamwish") || url.contains("swdyu") ->
                StreamWishExtractor(client, headers).videosFromUrl(url, prefix)
            url.contains("vidhide") || url.contains("luluvdo") ->
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
                            vids.add(Video(videoUrl, "${prefix}${src.attr("label").ifBlank { "Default" }}", videoUrl))
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
        GenreFilter(),
    )

    private class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        TYPE_LIST.map { it.first }.toTypedArray(),
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        GENRE_LIST.map { it.first }.toTypedArray(),
    )

    companion object {
        private val TYPE_LIST = listOf(
            Pair("All", ""),
            Pair("Japanese Anime", "japanese-anime"),
            Pair("Chinese Anime", "chinese-anime"),
            Pair("Subbed", "subbed-anime"),
            Pair("Dubbed", "dubbed-anime"),
        )
        private val GENRE_LIST = listOf(
            Pair("All", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Mecha", "mecha"),
            Pair("Mystery", "mystery"),
            Pair("Romance", "romance"),
            Pair("School", "school"),
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
