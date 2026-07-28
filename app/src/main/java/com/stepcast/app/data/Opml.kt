package com.stepcast.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Minimal OPML 2.0 read/write — the migration path in and out of the app. */
object Opml {

    /** One imported subscription: url + the outline folder it sat under. */
    data class Entry(val url: String, val title: String?, val folder: String?)

    /**
     * Returns every feed found in outline/@xmlUrl, carrying the enclosing
     * outline's name as its folder — our own export writes nested category
     * outlines, and importing used to throw that structure away. Registers
     * the same leaked-HTML-entity table as the feed parser, so an OPML with
     * an &nbsp; in a title imports instead of "Imported 0 of 0 feeds".
     */
    fun parse(stream: InputStream): List<Entry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(stream, null)
        RssParser.registerHtmlEntities(parser)
        val entries = mutableListOf<Entry>()
        // one stack frame per open <outline>: the folder name for
        // containers, null for feed leaves
        val folderStack = ArrayDeque<String?>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when {
                event == XmlPullParser.START_TAG &&
                    parser.name.equals("outline", ignoreCase = true) -> {
                    var xmlUrl: String? = null
                    var text: String? = null
                    for (i in 0 until parser.attributeCount) {
                        when {
                            parser.getAttributeName(i)
                                .equals("xmlUrl", ignoreCase = true) ->
                                xmlUrl = parser.getAttributeValue(i).trim()
                            parser.getAttributeName(i)
                                .equals("title", ignoreCase = true) && text == null ->
                                text = parser.getAttributeValue(i).trim()
                            parser.getAttributeName(i)
                                .equals("text", ignoreCase = true) ->
                                text = parser.getAttributeValue(i).trim()
                        }
                    }
                    if (xmlUrl != null && xmlUrl.startsWith("http")) {
                        entries += Entry(
                            url = xmlUrl,
                            title = text?.takeIf { it.isNotEmpty() },
                            folder = folderStack.lastOrNull { it != null }
                        )
                        folderStack.addLast(null)
                    } else {
                        folderStack.addLast(text?.takeIf { it.isNotEmpty() })
                    }
                }
                event == XmlPullParser.END_TAG &&
                    parser.name.equals("outline", ignoreCase = true) -> {
                    folderStack.removeLastOrNull()
                }
            }
            event = parser.next()
        }
        return entries.distinctBy { it.url }
    }

    fun serialize(
        podcasts: List<Podcast>,
        membershipsById: Map<Long, List<String>> = emptyMap()
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<opml version=\"2.0\">\n")
        append("  <head><title>Stepcast subscriptions</title></head>\n")
        append("  <body>\n")
        // virtual feeds (local folders) have no meaning off-device;
        // categories become standard nested outlines
        val exportable = podcasts.filter { it.localFolderUri == null }
        // a podcast in several categories appears under EVERY outline
        fun categoriesOf(podcast: Podcast): List<String?> {
            val fromJunction = membershipsById[podcast.id]
                ?.filter { it.isNotEmpty() }.orEmpty()
            if (fromJunction.isNotEmpty()) return fromJunction
            return listOf(podcast.folder?.takeIf(String::isNotEmpty))
        }
        val byFolder = exportable
            .flatMap { podcast -> categoriesOf(podcast).map { it to podcast } }
            .groupBy({ it.first }, { it.second })
        for ((folder, members) in byFolder.entries.sortedBy { it.key ?: "\uFFFF" }) {
            val indent = if (folder != null) {
                append("    <outline text=\"")
                append(escape(folder))
                append("\" title=\"")
                append(escape(folder))
                append("\">\n")
                "      "
            } else {
                "    "
            }
            for (podcast in members) {
                append(indent)
                append("<outline type=\"rss\" text=\"")
                append(escape(podcast.title))
                append("\" title=\"")
                append(escape(podcast.title))
                append("\" xmlUrl=\"")
                append(escape(podcast.feedUrl))
                append("\"/>\n")
            }
            if (folder != null) append("    </outline>\n")
        }
        append("  </body>\n")
        append("</opml>\n")
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
