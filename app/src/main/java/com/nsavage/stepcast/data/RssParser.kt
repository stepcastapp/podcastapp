package com.nsavage.stepcast.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val description: String,
    val imageUrl: String?,
    val author: String,
    val episodes: List<ParsedEpisode>
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val imageUrl: String?,
    val pubDateMs: Long,
    val durationMs: Long,
    /** See [Chapters] for the storage format; null when the feed has none. */
    val chapters: String? = null,
    /** Podcasting 2.0 <podcast:transcript>; best-supported format wins. */
    val transcriptUrl: String? = null,
    val transcriptType: String? = null
)

/**
 * Minimal RSS 2.0 + iTunes namespace parser. Original implementation over
 * XmlPullParser; tolerant of missing tags, skips non-audio enclosures.
 */
object RssParser {

    /** The HTML entities that actually show up in podcast feeds. */
    private val HTML_ENTITIES = mapOf(
        "nbsp" to " ", "iexcl" to "¡", "cent" to "¢", "pound" to "£",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "plusmn" to "±", "sup2" to "²", "sup3" to "³", "micro" to "µ",
        "para" to "¶", "middot" to "·", "laquo" to "«", "raquo" to "»",
        "frac14" to "¼", "frac12" to "½", "frac34" to "¾",
        "iquest" to "¿", "times" to "×", "divide" to "÷",
        "ndash" to "–", "mdash" to "—", "lsquo" to "‘",
        "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“",
        "rdquo" to "”", "bdquo" to "„", "dagger" to "†",
        "Dagger" to "‡", "bull" to "•", "hellip" to "…",
        "permil" to "‰", "prime" to "′", "Prime" to "″",
        "lsaquo" to "‹", "rsaquo" to "›", "euro" to "€",
        "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
        "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç",
        "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
        "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï",
        "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô",
        "otilde" to "õ", "ouml" to "ö", "oslash" to "ø", "ugrave" to "ù",
        "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý",
        "yuml" to "ÿ", "szlig" to "ß",
        "Agrave" to "À", "Aacute" to "Á", "Auml" to "Ä", "Aring" to "Å",
        "Ccedil" to "Ç", "Eacute" to "É", "Egrave" to "È",
        "Ntilde" to "Ñ", "Oacute" to "Ó", "Ouml" to "Ö", "Oslash" to "Ø",
        "Uacute" to "Ú", "Uuml" to "Ü"
    )

    private val rfc822Formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss Z"
    )

    fun parse(stream: InputStream): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Xml.newPullParser() ships with FEATURE_PROCESS_DOCDECL enabled,
        // and KXmlParser hard-refuses defineEntityReplacementText in that
        // mode (IllegalStateException) — so the entity table below would
        // never register. Podcast feeds don't carry DTDs; turn it off.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(stream, null)
        // Real-world feeds routinely leak HTML entities (&nbsp; &rsquo; …)
        // into bare XML text instead of CDATA — invalid XML, and the strict
        // parser aborts with "unresolved: &nbsp;". Teach it the common HTML
        // set so those feeds parse instead of failing to subscribe. No
        // runCatching here: if registration ever fails again it must fail
        // loudly, not resurface as a cryptic per-feed parse error.
        for ((name, value) in HTML_ENTITIES) {
            parser.defineEntityReplacementText(name, value)
        }

        var channelTitle = ""
        var channelDescription = ""
        var channelImage: String? = null
        var channelAuthor = ""
        val episodes = mutableListOf<ParsedEpisode>()

        var inItem = false
        var itemTitle = ""
        var itemGuid = ""
        var itemDescription = ""
        var itemAudioUrl = ""
        var itemImage: String? = null
        var itemPubDate = 0L
        var itemDuration = 0L
        val itemChapters = mutableListOf<Chapter>()
        var itemChaptersUrl: String? = null
        var itemTranscriptUrl: String? = null
        var itemTranscriptType: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (inItem) {
                        when (name) {
                            "title" -> itemTitle = parser.nextTextSafe()
                            "guid" -> itemGuid = parser.nextTextSafe()
                            "description" -> if (itemDescription.isEmpty()) itemDescription = parser.nextTextSafe()
                            "itunes:summary" -> if (itemDescription.isEmpty()) itemDescription = parser.nextTextSafe()
                            "pubdate" -> itemPubDate = parseDate(parser.nextTextSafe())
                            "itunes:duration" -> itemDuration = parseDuration(parser.nextTextSafe())
                            "itunes:image" -> itemImage = parser.getAttributeValue(null, "href") ?: itemImage
                            // Podlove Simple Chapters, inline in the item
                            "psc:chapter" -> {
                                val startMs = Chapters.parseNptMs(
                                    parser.getAttributeValue(null, "start").orEmpty()
                                )
                                val title = parser.getAttributeValue(null, "title").orEmpty()
                                if (startMs != null) itemChapters += Chapter(startMs, title)
                            }
                            // Podcasting 2.0 external JSON chapters
                            "podcast:chapters" -> {
                                itemChaptersUrl =
                                    parser.getAttributeValue(null, "url") ?: itemChaptersUrl
                            }
                            // Podcasting 2.0 transcript; an item may offer
                            // several formats — keep the best-supported one
                            "podcast:transcript" -> {
                                val url = parser.getAttributeValue(null, "url")
                                val type = parser.getAttributeValue(null, "type").orEmpty()
                                if (url != null &&
                                    Transcripts.formatRank(type) <
                                    Transcripts.formatRank(itemTranscriptType.orEmpty())
                                ) {
                                    itemTranscriptUrl = url
                                    itemTranscriptType = type
                                }
                            }
                            "enclosure", "media:content" -> {
                                val url = parser.getAttributeValue(null, "url").orEmpty()
                                val type = parser.getAttributeValue(null, "type").orEmpty()
                                val looksAudio = type.startsWith("audio/") ||
                                    (type.isEmpty() && url.substringBefore('?').let {
                                        it.endsWith(".mp3") || it.endsWith(".m4a") ||
                                            it.endsWith(".ogg") || it.endsWith(".opus")
                                    })
                                if (looksAudio && url.isNotEmpty() && itemAudioUrl.isEmpty()) {
                                    itemAudioUrl = url
                                }
                            }
                        }
                    } else {
                        when (name) {
                            "item" -> {
                                inItem = true
                                itemTitle = ""; itemGuid = ""; itemDescription = ""
                                itemAudioUrl = ""; itemImage = null
                                itemPubDate = 0L; itemDuration = 0L
                                itemChapters.clear(); itemChaptersUrl = null
                                itemTranscriptUrl = null; itemTranscriptType = null
                            }
                            "title" -> if (channelTitle.isEmpty()) channelTitle = parser.nextTextSafe()
                            "description" -> if (channelDescription.isEmpty()) channelDescription = parser.nextTextSafe()
                            "itunes:author" -> if (channelAuthor.isEmpty()) channelAuthor = parser.nextTextSafe()
                            "itunes:image" -> channelImage = parser.getAttributeValue(null, "href") ?: channelImage
                            "url" -> if (channelImage == null) channelImage = parser.nextTextSafe().ifEmpty { null }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inItem && parser.name.equals("item", ignoreCase = true)) {
                        inItem = false
                        if (itemAudioUrl.isNotEmpty()) {
                            episodes += ParsedEpisode(
                                guid = itemGuid.ifEmpty { itemAudioUrl },
                                title = itemTitle.ifEmpty { "(untitled)" },
                                description = itemDescription,
                                audioUrl = itemAudioUrl,
                                imageUrl = itemImage,
                                pubDateMs = itemPubDate,
                                durationMs = itemDuration,
                                chapters = when {
                                    itemChapters.isNotEmpty() ->
                                        Chapters.serialize(itemChapters.sortedBy { it.startMs })
                                    itemChaptersUrl != null ->
                                        Chapters.JSON_PREFIX + itemChaptersUrl
                                    else -> null
                                },
                                transcriptUrl = itemTranscriptUrl,
                                transcriptType = itemTranscriptType
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        return ParsedFeed(
            title = channelTitle.ifEmpty { "(untitled feed)" },
            description = channelDescription,
            imageUrl = channelImage,
            author = channelAuthor,
            episodes = episodes
        )
    }

    private fun XmlPullParser.nextTextSafe(): String = try {
        nextText().trim()
    } catch (e: Exception) {
        ""
    }

    private fun parseDate(text: String): Long {
        if (text.isEmpty()) return 0
        for (fmt in rfc822Formats) {
            try {
                return SimpleDateFormat(fmt, Locale.US).parse(text)?.time ?: 0
            } catch (_: Exception) {
            }
        }
        return 0
    }

    /** Accepts "HH:MM:SS", "MM:SS" or plain seconds. */
    private fun parseDuration(text: String): Long {
        if (text.isEmpty()) return 0
        return try {
            val parts = text.split(":").map { it.trim().toLong() }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                1 -> parts[0] * 1000
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
