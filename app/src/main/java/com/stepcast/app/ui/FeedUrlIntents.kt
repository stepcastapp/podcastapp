package com.stepcast.app.ui

/**
 * Pure string transforms behind the share-target / podcast-scheme intents,
 * kept free of android.content so plain JVM tests can pin them down.
 */
object FeedUrlIntents {

    /** First http(s) URL inside shared text, if any. */
    fun firstUrlIn(text: String?): String? =
        text?.let { Regex("https?://\\S+").find(it)?.value }

    /** Classic podcast link schemes → a fetchable https/http URL. */
    fun normalizeScheme(raw: String): String = when {
        raw.startsWith("pcast://") -> "https://" + raw.removePrefix("pcast://")
        raw.startsWith("podcast://") -> "https://" + raw.removePrefix("podcast://")
        raw.startsWith("itpc://") -> "https://" + raw.removePrefix("itpc://")
        raw.startsWith("feed://") -> "https://" + raw.removePrefix("feed://")
        raw.startsWith("pcast:") -> raw.removePrefix("pcast:")
        raw.startsWith("feed:") -> raw.removePrefix("feed:")
        else -> raw
    }
}
