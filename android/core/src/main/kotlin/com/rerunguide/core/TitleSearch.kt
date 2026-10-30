package com.rerunguide.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId

/**
 * Options › Search: films by title and shows by series title (one entry per show; episode titles aren't searched), and
 * when each airs next. Matching ignores case, accents and punctuation, and a leading "The", "A" or "An" needn't be typed.
 */
object TitleSearch {
    /** The most results a search returns. */
    const val limit = 50
    /** How far ahead "airs next" looks, in seconds. */
    const val window = 24 * 3600.0

    /** One searchable title: a film (copies with the same title and year are one) or a show. */
    data class Entry(
        /** "movie:<full key>|<year>" or "show:<series title>". */
        val id: String,
        val title: String,
        /** A show's: its earliest episode's. */
        val year: Int?,
        val isShow: Boolean,
        /** The film (its first copy), or the show's first episode in the library. */
        val item: MediaItem,
        /** The title's letters and digits ("theoffice"), and the same without a leading article ("office"). */
        internal val full: String,
        internal val bare: String,
    )

    /** Every entry with its keys, sorted the way equal matches are listed (by `bare`, then year with none as 0, films
     *  before shows), and the entry each item airs as. */
    class Index(val entries: List<Entry>, internal val entryOf: Map<String, String>)

    fun index(library: List<MediaItem>): Index {
        val entries = ArrayList<Entry>(); val at = HashMap<String, Int>(); val entryOf = HashMap<String, String>()
        for (item in library) {
            val show = item.kind == MediaItem.Kind.episode
            val title = (if (show) item.seriesTitle else item.title) ?: continue
            var key: Keys? = null; var id = "show:$title"
            if (!show) { val k = keys(title); key = k; id = "movie:${k.full}|${item.year ?: 0}" }
            entryOf[item.id] = id
            val i = at[id]
            if (i != null) {
                val e = entries[i]; val y = item.year
                if (show && y != null && (e.year == null || y < e.year)) entries[i] = e.copy(year = y)
                continue
            }
            val k = key ?: keys(title)
            at[id] = entries.size
            entries += Entry(id, title, item.year, show, item, k.full, k.bare)
        }
        entries.sortWith { a, b ->
            swiftCompare(a.bare, b.bare).takeIf { it != 0 } ?: compareValues(a.year ?: 0, b.year ?: 0).takeIf { it != 0 }
                ?: compareValues(a.isShow, b.isShow).takeIf { it != 0 } ?: swiftCompare(a.full, b.full).takeIf { it != 0 }
                ?: swiftCompare(a.title, b.title)
        }
        return Index(entries, entryOf)
    }

    /** Up to `limit` entries: exact titles first, then titles that start with the query, then any other match; ties by
     *  title (its `bare` key), then year. A query without letters or digits finds nothing. */
    fun search(query: String, index: Index, limit: Int = TitleSearch.limit): List<Entry> {
        val q = keys(query).full
        if (q.isEmpty()) return emptyList()
        val exact = ArrayList<Entry>(); val prefix = ArrayList<Entry>(); val other = ArrayList<Entry>()
        for (e in index.entries) {   // already in tie order, so each list stays sorted
            if (e.full == q || e.bare == q) exact += e
            else if (e.full.startsWith(q) || e.bare.startsWith(q)) { if (prefix.size < limit) prefix += e }
            else if (other.size < limit && e.full.contains(q)) other += e   // a native indexOf, like Swift's byte compare
        }
        return (exact + prefix + other).take(limit)
    }

    internal data class Keys(val full: String, val bare: String)

    /** "The Schindler’s List" → ("theschindlerslist", "schindlerslist"): decomposed, lowercased, combining marks dropped,
     *  split into words at anything but a letter or a digit, joined without spaces; `bare` drops a leading article when
     *  more words follow. Lowercased per code point, as Swift's `lowercased()` is (no final-sigma rule), with ς read as σ. */
    internal fun keys(s: String): Keys {
        val words = ArrayList<String>(); val word = StringBuilder()
        val d = if (s.all { it < '\u00C0' }) s else Normalizer.normalize(s, Normalizer.Form.NFD)   // nothing below À decomposes
        var i = 0
        while (i < d.length) {
            val u = Character.toLowerCase(d.codePointAt(i)).let { if (it == 0x3C2) 0x3C3 else it }   // ς as σ, as Swift's
            i += Character.charCount(d.codePointAt(i))
            val t = Character.getType(u).toByte()
            if (t == Character.NON_SPACING_MARK) continue
            if (Character.isLetterOrDigit(u) || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER) word.appendCodePoint(u)
            else if (word.isNotEmpty()) { words += word.toString(); word.setLength(0) }
        }
        if (word.isNotEmpty()) words += word.toString()
        val full = words.joinToString("")
        return Keys(full, if (words.size > 1 && words[0] in setOf("the", "a", "an")) words.drop(1).joinToString("") else full)
    }

    /** Where an entry airs next. */
    data class Airing(val channelID: String, val number: Int, val name: String, val start: Instant)

    /** The earliest airing of each entry across `lineups` (each channel with its slots) from `now` to `window` on, but not
     *  past tomorrow. A program counts while it plays and when it starts inside the window, never in its break (it's over
     *  by then). The earliest start wins, ties to the lower channel number. */
    fun nextAirings(lineups: List<Pair<Channel, List<Slot>>>, index: Index, now: Instant, window: Double = TitleSearch.window,
                    calendar: ZoneId = ZoneId.systemDefault()): Map<String, Airing> {
        // No further than tomorrow, so "Tomorrow" stays true: a spring-forward day is 23 hours.
        val end = minOf(now.plusSeconds(window), now.atZone(calendar).toLocalDate().plusDays(2).atStartOfDay(calendar).toInstant())
        val best = HashMap<String, Airing>()
        for ((ch, slots) in lineups) for (s in slots) {
            if (s.start >= end || s.itemEnd <= now) continue
            val id = index.entryOf[s.item.id] ?: continue
            val b = best[id]
            if (b != null && (b.start < s.start || (b.start == s.start && b.number <= ch.number))) continue
            best[id] = Airing(ch.id, ch.number, ch.name, s.start)
        }
        return best
    }

    /** "On now · Ch 5 Fright Night, 40 min in", "Today 3:00 PM · Ch 5 Fright Night", "Tonight 10:00 PM · …" (later today
     *  from 6 PM), "Tomorrow 2:00 PM · Ch 22 Gumshoe", or "Not scheduled in the next 24 hours". `time` is the app's clock
     *  ("10:00 PM"). */
    fun airsText(a: Airing?, now: Instant, calendar: ZoneId = ZoneId.systemDefault(), time: (Instant) -> String): String {
        if (a == null) return "Not scheduled in the next 24 hours"
        val ch = "Ch ${a.number} ${a.name}"
        if (a.start <= now) return "On now · $ch, ${(now.secondsSince(a.start) / 60).toInt()} min in"
        val start = a.start.atZone(calendar)
        val day = if (start.toLocalDate() != now.atZone(calendar).toLocalDate()) "Tomorrow" else if (start.hour >= 18) "Tonight" else "Today"
        return "$day ${time(a.start)} · $ch"
    }
}
