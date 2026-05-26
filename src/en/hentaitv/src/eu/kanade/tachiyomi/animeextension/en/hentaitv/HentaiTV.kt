package eu.kanade.tachiyomi.animeextension.en.hentaitv

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode  // <-- Fixed: Added missing import
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiTv : ParsedAnimeHttpSource() {

    override val name = "Hentai.tv"
    override val baseUrl = "https://hentai.tv"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular / Latest ==============================

    override fun popularAnimeSelector() = "article, .hentai-item, .video-item"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/page/$page/", headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val titleElement = element.selectFirst("h3 a, a[title]") ?: element.selectFirst("a")
        return SAnime.create().apply {
            title = titleElement?.text()?.trim() ?: "Unknown"
            thumbnail_url = element.selectFirst("img")?.attr("src")?.trim()
            setUrlWithoutDomain(titleElement?.attr("href") ?: "")
        }
    }

    override fun popularAnimeNextPageSelector() = "a.next, .pagination a:contains(Next)"

    // ============================== Latest ==============================

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Search ==============================

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=$query&page=$page", headers)
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Details ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
            thumbnail_url = document.selectFirst("img[src*='poster'], .poster img")?.attr("src")
            description = document.selectFirst(".description, .entry-content, .synopsis")?.text()
            author = document.selectFirst(".studio, .producer")?.text()
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "a[href*='-episode-'], .episode-list a"

    // Fixed: Properly overriding the list parse and satisfying the interface
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        // Fallback/Default for single-episode entries
        val defaultEpisode = SEpisode.create().apply {
            name = "Episode 1"
            episode_number = 1f
            setUrlWithoutDomain(document.location())
        }
        episodes.add(defaultEpisode)

        // Try to find more episodes if it's a multi-episode entry
        document.select(episodeListSelector()).forEachIndexed { index, el ->
            val href = el.attr("href")
            if (href.isNotBlank()) {
                val ep = SEpisode.create().apply {
                    name = el.text().trim().ifBlank { "Episode ${index + 1}" }
                    setUrlWithoutDomain(href)
                }
                episodes.add(ep)
            }
        }
        
        // Reverse order so Episode 1 is at the bottom of the Tachiyomi list view
        return episodes.distinctBy { it.url }.reversed()
    }

    // Fixed: Added dummy/empty overrides required by ParsedAnimeHttpSource
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException("Not used")
    override fun episodeNextPageSelector(): String? = null

    // ============================== Video ==============================

    override fun videoListSelector() = "video source, script"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Method 1: Direct <source> tag
        document.select("video source").forEach { source ->
            val url = source.attr("src")
            if (url.contains(".m3u8") || url.contains(".mp4")) {
                videos.add(Video(url, "Direct", url))
            }
        }

        // Method 2: Extract m3u8 from JavaScript
        val scripts = document.select("script")
        scripts.forEach { script ->
            val content = script.html()
            if (content.contains("m3u8") || content.contains("master")) {
                val m3u8Regex = """["']([^"']*\.m3u8[^"']*)["']""".toRegex()
                m3u8Regex.findAll(content).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.startsWith("http")) {
                        videos.add(Video(url, "HLS (Adaptive)", url))
                    }
                }
            }
        }

        // Fallback: Look for data attributes
        val allLinks = document.select("a[href*='.m3u8'], [data-video]")
        allLinks.forEach {
            val url = it.attr("href").ifBlank { it.attr("data-video") }
            if (url.contains(".m3u8")) {
                videos.add(Video(url, "HLS", url))
            }
        }

        return videos.ifEmpty {
            listOf(Video(document.location(), "Open in Browser", document.location()))
        }
    }

    override fun List<Video>.sort(): List<Video> {
        return this
    }
}
