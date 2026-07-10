package com.stepcast.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Minimal OPML 2.0 read/write — the migration path in and out of the app. */
object Opml {

    /** Returns every feed URL found in outline/@xmlUrl, at any nesting depth. */
    fun parse(stream: InputStream): List<String> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)
        val urls = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG &&
                parser.name.equals("outline", ignoreCase = true)
            ) {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i).equals("xmlUrl", ignoreCase = true)) {
                        val url = parser.getAttributeValue(i).trim()
                        if (url.startsWith("http")) urls += url
                    }
                }
            }
            event = parser.next()
        }
        return urls.distinct()
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
