package com.rerunguide.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

enum class ServerKind {
    plex, emby, jellyfin, demo;

    val rawValue: String get() = name
    val displayName: String
        get() = when (this) { plex -> "Plex"; emby -> "Emby"; jellyfin -> "Jellyfin"; demo -> "Demo" }

    companion object { fun from(rawValue: String?): ServerKind? = entries.firstOrNull { it.rawValue == rawValue } }
}

/** A library/section on the server (Plex section, Jellyfin/Emby view). Users pick which ones go on the air. */
data class LibrarySection(val id: String, val title: String, val kind: Kind, val itemCount: Int? = null) {
    enum class Kind {
        movies, shows, other;

        val rawValue: String get() = name
        companion object { fun from(rawValue: String?): Kind? = entries.firstOrNull { it.rawValue == rawValue } }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("kind", kind.rawValue).putOpt("itemCount", itemCount)

    companion object {
        fun fromJson(o: JSONObject) = LibrarySection(o.getString("id"), o.getString("title"),
            Kind.from(o.getString("kind")) ?: throw org.json.JSONException("kind"), o.intOrNull("itemCount"))
    }
}

/** One playable thing from the library. Server-agnostic. */
data class MediaItem(
    val id: String,
    val kind: Kind,
    val title: String,
    val seriesTitle: String? = null,
    val seriesID: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    /** Seconds. */
    val runtime: Double,
    /** Server-relative poster/thumb path. Resolve with `MediaServer.artworkURL(path)`. */
    val artworkPath: String? = null,
    /** Server-relative wide art (backdrop/fanart) path, used by the guide's preview window. Resolve with `MediaServer.artworkURL(path)`. */
    val backdropPath: String? = null,
    val overview: String? = null,
    /** "PG", "R", "TV-14"… as the server reports it. */
    val contentRating: String? = null,
    val directors: List<String> = emptyList(),
    /** Top-billed cast, in billing order (first ~6 is plenty). */
    val actors: List<String> = emptyList(),
    /** Which `LibrarySection` it came from, when known. */
    val libraryID: String? = null,
    /** When the server added it to the library (Plex addedAt, Emby/Jellyfin DateCreated). Drives "Just Added". */
    val addedAt: Instant? = null,
    /**
     * The signed-in user has watched it, as the server says (Plex viewCount, Emby/Jellyfin UserData.Played). Drives
     * "Unwatched". `fromJson` requires the key, so a library cache written before it is unreadable and refetched.
     */
    val watched: Boolean = false,
) {
    enum class Kind {
        movie, episode;

        val rawValue: String get() = name
        companion object { fun from(rawValue: String?): Kind? = entries.firstOrNull { it.rawValue == rawValue } }
    }

    /** Guide-cell headline: series name for episodes, title for movies. */
    val displayTitle: String get() = if (kind == Kind.episode) (seriesTitle ?: title) else title


    /** "S5 E9" or "" . */
    val seasonEpisode: String get() = listOfNotNull(season?.let { "S$it" }, episode?.let { "E$it" }).joinToString(" ")

    /** Guide-cell second line: episode title for episodes, year for movies. */
    val subtitle: String
        get() = when (kind) {
            Kind.episode -> title
            Kind.movie -> year?.toString() ?: ""
        }

    /** Info-panel sub line. Episodes: “Episode” · S5 E9 · 1986. Movies: 1981 · PG · 115 min. */
    val infoLine: String
        get() = when (kind) {
            Kind.episode -> listOfNotNull("“$title”", seasonEpisode.ifEmpty { null }, year?.toString()).joinToString(" · ")
            Kind.movie -> listOfNotNull(year?.toString(), contentRating, "${(runtime / 60).toInt()} min").joinToString(" · ")
        }

    /** Keys as Swift's synthesized Codable; dates as epoch seconds. Missing optional keys keep their defaults. */
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.rawValue).put("title", title)
        .putOpt("seriesTitle", seriesTitle).putOpt("seriesID", seriesID).putOpt("season", season).putOpt("episode", episode)
        .putOpt("year", year).put("genres", JSONArray(genres)).put("runtime", runtime).putOpt("artworkPath", artworkPath)
        .putOpt("backdropPath", backdropPath).putOpt("overview", overview).putOpt("contentRating", contentRating)
        .put("directors", JSONArray(directors)).put("actors", JSONArray(actors)).putOpt("libraryID", libraryID)
        .putOpt("addedAt", addedAt?.epochSeconds).put("watched", watched)

    companion object {
        /**
         * A film's title with its cut, "Watchmen (Directors Cut)": two cuts are two films, on screen and to the scheduler
         * (`GuideGenerator.repeatKey`), while two plain copies stay one.
         */
        fun title(title: String, edition: String?): String {
            val e = edition?.trim()
            return if (e.isNullOrEmpty()) title else "$title ($e)"
        }

        /**
         * The cut named in a file path, Plex style: "Watchmen (2009) {edition-Directors Cut}.mkv". Emby and Jellyfin have
         * no edition field; Plex sends `editionTitle`.
         */
        fun edition(inPath: String?): String? = inPath?.let { editionRegex.find(it)?.groupValues?.get(1) }
        private val editionRegex = Regex("""\{edition-([^}]+)\}""")

        fun fromJson(o: JSONObject) = MediaItem(
            id = o.getString("id"), kind = Kind.from(o.getString("kind")) ?: throw org.json.JSONException("kind"),
            title = o.getString("title"), seriesTitle = o.stringOrNull("seriesTitle"), seriesID = o.stringOrNull("seriesID"),
            season = o.intOrNull("season"), episode = o.intOrNull("episode"), year = o.intOrNull("year"),
            genres = o.strings("genres"), runtime = o.getDouble("runtime"), artworkPath = o.stringOrNull("artworkPath"),
            backdropPath = o.stringOrNull("backdropPath"), overview = o.stringOrNull("overview"),
            contentRating = o.stringOrNull("contentRating"), directors = o.strings("directors"), actors = o.strings("actors"),
            libraryID = o.stringOrNull("libraryID"), addedAt = o.doubleOrNull("addedAt")?.let(::instantOf),
            watched = o.getBoolean("watched"),
        )
    }
}

/** What "tune in" means: join the program in progress (real TV) or start it from the beginning. */
enum class TuneMode {
    live, start;

    val rawValue: String get() = name
    companion object { fun from(rawValue: String?): TuneMode? = entries.firstOrNull { it.rawValue == rawValue } }
}

enum class PlayOrder(val rawValue: String) {
    shuffle("Shuffle"), inOrder("In order"), newestFirst("Newest first");

    val next: PlayOrder get() = when (this) { shuffle -> inOrder; inOrder -> newestFirst; newestFirst -> shuffle }

    companion object { fun from(rawValue: String?): PlayOrder? = entries.firstOrNull { it.rawValue == rawValue } }
}

/** One criterion of a channel's rule. A channel's items are the library items matching EVERY chip. */
sealed interface RuleChip {
    data class genre(val value: String) : RuleChip
    data class years(val from: Int, val to: Int) : RuleChip
    data class director(val name: String) : RuleChip
    data class actor(val name: String) : RuleChip
    data class series(val id: String, val title: String) : RuleChip
    data class year(val value: Int) : RuleChip
    data object movies : RuleChip
    data object tv : RuleChip
    /** Added to the server within the last N days (relative to when the channels are rebuilt). */
    data class addedWithin(val days: Int) : RuleChip
    /** Any of these genres. One chip, so it ANDs with the rest of the rule like every other chip. */
    data class anyGenre(val genres: List<String>) : RuleChip
    /** Title or overview mentions any of these words: case-insensitive, matched at a word start ("zombie" takes "zombies"). */
    data class keywords(val words: List<String>) : RuleChip
    /** Not watched by the signed-in user (`MediaItem.watched`). */
    data object unwatched : RuleChip

    /** Chip text as shown in the channel editor: "Genre: Sitcom", "1975–1999", "Director: Steven Spielberg", "TV". */
    val label: String
        get() = when (this) {
            is genre -> "Genre: $value"
            is years -> if (from <= 1900) "Before ${to + 1}" else "$from–$to"
            is director -> "Director: $name"
            is actor -> "Actor: $name"
            is series -> "Show: $title"
            is year -> "Year: $value"
            movies -> "Movies"
            tv -> "TV"
            is addedWithin -> "Added in the last $days days"
            is anyGenre -> "Genre: " + genres.joinToString(" / ")
            is keywords -> "Mentions: " + words.take(4).joinToString(", ") + (if (words.size > 4) "…" else "")
            unwatched -> "Unwatched"
        }

    /** Swift's synthesized enum Codable shape: {"genre":{"_0":"Comedy"}}, {"series":{"id":…,"title":…}}, {"movies":{}}. */
    fun toJson(): JSONObject = when (this) {
        is genre -> wrap("genre", JSONObject().put("_0", value))
        is years -> wrap("years", JSONObject().put("_0", from).put("_1", to))
        is director -> wrap("director", JSONObject().put("_0", name))
        is actor -> wrap("actor", JSONObject().put("_0", name))
        is series -> wrap("series", JSONObject().put("id", id).put("title", title))
        is year -> wrap("year", JSONObject().put("_0", value))
        movies -> wrap("movies", JSONObject())
        tv -> wrap("tv", JSONObject())
        is addedWithin -> wrap("addedWithin", JSONObject().put("days", days))
        is anyGenre -> wrap("anyGenre", JSONObject().put("_0", JSONArray(genres)))
        is keywords -> wrap("keywords", JSONObject().put("_0", JSONArray(words)))
        unwatched -> wrap("unwatched", JSONObject())
    }

    companion object {
        private fun wrap(case: String, v: JSONObject) = JSONObject().put(case, v)

        fun fromJson(o: JSONObject): RuleChip {
            val case = o.keys().asSequence().singleOrNull() ?: throw org.json.JSONException("RuleChip: one key expected")
            val v = o.getJSONObject(case)
            return when (case) {
                "genre" -> genre(v.getString("_0"))
                "years" -> years(v.getInt("_0"), v.getInt("_1"))
                "director" -> director(v.getString("_0"))
                "actor" -> actor(v.getString("_0"))
                "series" -> series(v.getString("id"), v.getString("title"))
                "year" -> year(v.getInt("_0"))
                "movies" -> movies
                "tv" -> tv
                "addedWithin" -> addedWithin(v.getInt("days"))
                "anyGenre" -> anyGenre(v.strings("_0"))
                "keywords" -> keywords(v.strings("_0"))
                "unwatched" -> unwatched
                else -> throw org.json.JSONException("RuleChip: unknown case $case")
            }
        }
    }
}

/**
 * What a channel IS, independent of the library contents. Automatic ones are rebuilt from the library each launch;
 * custom ones and per-channel edits are persisted by the app.
 */
data class ChannelDefinition(
    val id: String,
    val number: Int,
    val name: String,
    /** Short descriptor under the name: "Sitcoms, 70s–90s", "Director", "Marathon", "Year". */
    val tag: String,
    val rule: List<RuleChip>,
    val order: PlayOrder = PlayOrder.shuffle,
    val trailerBreaks: Boolean = true,
    val isCustom: Boolean = false,
    /** Per-channel tune-in behaviour; null = follow the app setting. */
    val tuneMode: TuneMode? = null,
) {
    /** Stable 0..<360 hue for gradients, derived from the id (never from a per-process hash). */
    val hue: Double get() = (StableHash.fnv1a(id) % 360u).toDouble()

    fun toJson(): JSONObject = JSONObject().put("id", id).put("number", number).put("name", name).put("tag", tag)
        .put("rule", JSONArray(rule.map { it.toJson() })).put("order", order.rawValue).put("trailerBreaks", trailerBreaks)
        .put("isCustom", isCustom).putOpt("tuneMode", tuneMode?.rawValue)

    companion object {
        fun fromJson(o: JSONObject): ChannelDefinition {
            val rule = o.optJSONArray("rule")
            return ChannelDefinition(
                id = o.getString("id"), number = o.optInt("number", 0), name = o.optString("name", ""), tag = o.optString("tag", ""),
                rule = if (rule == null) emptyList() else (0 until rule.length()).map { RuleChip.fromJson(rule.getJSONObject(it)) },
                order = PlayOrder.from(o.stringOrNull("order")) ?: PlayOrder.shuffle,
                trailerBreaks = o.optBoolean("trailerBreaks", true), isCustom = o.optBoolean("isCustom", false),
                tuneMode = TuneMode.from(o.stringOrNull("tuneMode")),
            )
        }
    }
}

/** A guide row: a definition plus the library items it resolved to (already in `order`-appropriate sequence). */
data class Channel(val definition: ChannelDefinition, val items: List<MediaItem>) {
    /** Mostly films (vs. episodes); decides whether the channel belongs to the Movies or the TV guide. */
    val isMovies: Boolean = items.count { it.kind == MediaItem.Kind.movie } * 2 >= items.size

    /** Convenience for tests and channel 99: a plain genre-style channel. */
    constructor(id: String, number: Int, name: String, items: List<MediaItem>, tag: String = "", order: PlayOrder = PlayOrder.shuffle) :
        this(ChannelDefinition(id = id, number = number, name = name, tag = tag, rule = emptyList(), order = order), items)

    val id: String get() = definition.id
    val number: Int get() = definition.number
    val name: String get() = definition.name
    val tag: String get() = definition.tag
    val order: PlayOrder get() = definition.order
    val trailerBreaks: Boolean get() = definition.trailerBreaks
    val isCustom: Boolean get() = definition.isCustom
    val hue: Double get() = definition.hue
    /** Channel 99 (Scrambled.kt). */
    val isScrambled: Boolean get() = id == scrambledID

    companion object {
        const val scrambledID = "scrambled"
        const val scrambledNumber = 99
        /** Channel 99, built in Scrambled.kt. */
        val scrambled: Channel by lazy { makeScrambled() }
    }
}

/**
 * One cell in the guide: an item airing on a channel between `start` and `end`.
 * `end - start` is the runtime (+3 min pad) rounded UP to the guide block (30 min), like a paper TV guide.
 * Time after the item ends but before `end` is the "break" (trailers / station card).
 */
data class Slot(val item: MediaItem, val start: Instant, val end: Instant) {
    val id: String get() = "${item.id}@${start.epochSeconds.toLong()}"
    val duration: Double get() = end.secondsSince(start)
    /** When the item itself stops playing and the break begins. */
    val itemEnd: Instant get() = minOf(end, start.plusSeconds(item.runtime))
}

enum class ProgramStatus { later, live, `break`, past }

/** One audio or subtitle track of an item, as the server reports it. */
data class MediaTrack(
    /** Plex stream id / Jellyfin-Emby stream index, as a string. */
    val id: String,
    val kind: Kind,
    /** ISO 639-2 code ("eng", "spa") when the server knows it. */
    val language: String?,
    /** "English (AAC Stereo)", "Spanish (SUBRIP)", "Commentary"… */
    val title: String,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
) {
    enum class Kind {
        audio, subtitle;

        val rawValue: String get() = name
    }

    companion object {
        private val bibliographic = mapOf("fre" to "fra", "ger" to "deu", "chi" to "zho", "dut" to "nld", "gre" to "ell", "cze" to "ces",
            "rum" to "ron", "may" to "msa", "baq" to "eus", "per" to "fas", "ice" to "isl", "mac" to "mkd", "slo" to "slk", "wel" to "cym",
            "arm" to "hye", "geo" to "kat", "bur" to "mya", "tib" to "bod", "alb" to "sqi", "mao" to "mri",
            "nob" to "nor", "nno" to "nor")   // a Norwegian device says Bokmål/Nynorsk, servers tag "nor"
        /** Java only names a few 3-letter codes; go through the 2-letter one it always knows. */
        private val twoLetter: Map<String, String> by lazy {
            Locale.getISOLanguages().associateBy { runCatching { Locale(it).isO3Language }.getOrDefault(it) }
        }

        /** "Spanish" for "spa"; falls back to the code. Handles the bibliographic ISO 639-2/B codes Emby and Plex emit. */
        fun languageName(code: String?): String? {
            if (code.isNullOrEmpty() || code == "und") return null
            val t = terminology(code) ?: code
            val lang = twoLetter[t] ?: t
            val name = Locale(lang).getDisplayLanguage(Locale.getDefault())
            return if (name.isEmpty() || name.equals(lang, ignoreCase = true)) code else name
        }

        /** The ISO 639-2/T code for `code` ("fre" → "fra", "ger" → "deu"; others lowercased as is). Emby and Plex emit /B
         *  codes, the device locale gives /T, so every language comparison goes through here. null for none / "und". */
        fun terminology(code: String?): String? {
            val c = code?.lowercase()
            if (c.isNullOrEmpty() || c == "und") return null
            return bibliographic[c] ?: c
        }

        /** Same language, /B and /T codes alike; an unknown language matches nothing. */
        fun sameLanguage(a: String?, b: String?): Boolean {
            val t = terminology(a) ?: return false
            return t == terminology(b)
        }
    }
}

/** What the viewer asked for. null / auto = the server's default choice. */
data class TrackSelection(val audioID: String? = null, val subtitle: Subtitle = Subtitle.auto) {
    sealed interface Subtitle {
        data object auto : Subtitle
        data object off : Subtitle
        data class track(val id: String) : Subtitle
    }

    val isDefault: Boolean get() = audioID == null && subtitle == Subtitle.auto

    /** {"audioID": "2", "subtitle": "auto" | "off" | {"track": "5"}}. */
    fun toJson(): JSONObject = JSONObject().putOpt("audioID", audioID).put("subtitle", when (subtitle) {
        Subtitle.auto -> "auto"
        Subtitle.off -> "off"
        is Subtitle.track -> JSONObject().put("track", subtitle.id)
    })

    companion object {
        /**
         * The Settings preferences matched against an item's tracks. `subtitleMode` "off" / "on" / anything else = "Forced
         * only": Plex leaves that to the server (an explicit Plex pick changes the account-wide choice), Emby/Jellyfin get
         * the forced track asked for by index, or none, so the server account's subtitle preference never decides. A forced
         * track counts only in `subtitleLanguage`, else `deviceLanguage`, else the language of the audio that will play
         * (picked, else the item's default); a foreign forced track is never shown. An unmatched audio language, or "on"
         * without subtitle tracks, = server default.
         */
        fun preferred(tracks: List<MediaTrack>, server: ServerKind?, audioLanguage: String?,
                      subtitleMode: String, subtitleLanguage: String, deviceLanguage: String? = null): TrackSelection {
            val audio = tracks.filter { it.kind == MediaTrack.Kind.audio }
            val audioID = audio.firstOrNull { MediaTrack.sameLanguage(it.language, audioLanguage) }?.id
            val subs = tracks.filter { it.kind == MediaTrack.Kind.subtitle }
            val subtitle: Subtitle = when (subtitleMode) {
                "off" -> Subtitle.off
                "on" -> {
                    // The chosen language if the program has it, else any full subtitle track; forced-only tracks last.
                    val pick = subs.firstOrNull { MediaTrack.sameLanguage(it.language, subtitleLanguage) && !it.isForced }
                        ?: subs.firstOrNull { !it.isForced } ?: subs.firstOrNull()
                    if (pick != null) Subtitle.track(pick.id) else Subtitle.auto
                }
                else -> if (server != ServerKind.emby && server != ServerKind.jellyfin) Subtitle.auto else {
                    val forced = subs.filter { it.isForced }
                    val playing = audio.firstOrNull { it.id == audioID } ?: audio.firstOrNull { it.isDefault } ?: audio.firstOrNull()
                    val langs: List<String?> = listOf(subtitleLanguage, deviceLanguage, playing?.language)
                    var pick: MediaTrack? = null
                    for (lang in langs) { pick = forced.firstOrNull { MediaTrack.sameLanguage(it.language, lang) }; if (pick != null) break }
                    if (pick != null) Subtitle.track(pick.id) else Subtitle.off
                }
            }
            return TrackSelection(audioID, subtitle)
        }

        fun fromJson(o: JSONObject): TrackSelection {
            val s = o.opt("subtitle")
            val sub = when {
                s == "off" -> Subtitle.off
                s is JSONObject && s.has("track") -> Subtitle.track(s.getString("track"))
                else -> Subtitle.auto
            }
            return TrackSelection(o.stringOrNull("audioID"), sub)
        }
    }
}

/** What the player should load. */
data class PlaybackSource(
    val url: String,
    /**
     * true: the server already started the stream at the requested offset (Plex HLS transcode), so do NOT seek.
     * false: the stream starts at 0 (direct play, Jellyfin/Emby HLS); the player must seek to the offset itself.
     */
    val startsAtOffset: Boolean,
    /**
     * true when a chosen subtitle rides in the HLS playlist as a text rendition the player must select (Jellyfin/Emby);
     * false when it is burned into the picture (Plex) or no subtitle was requested.
     */
    val subtitleInPlaylist: Boolean = false,
)

/** Process-independent hashing. Never feed a per-process `hashCode()` into anything deterministic. */
object StableHash {
    fun fnv1a(s: String): ULong {
        var h: ULong = 0xcbf29ce484222325uL
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor b.toUByte().toULong(); h *= 0x100000001b3uL }
        return h
    }
}

/**
 * Swift's `String <`: Unicode scalar order of the NFC forms. Kotlin's `compareTo` is UTF-16 order and not normalized;
 * every sort that decides the channel lineup or a day's schedule goes through this so both platforms order identically.
 */
fun swiftCompare(a: String, b: String): Int {
    val x = nfc(a)
    val y = nfc(b)
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val c = x.codePointAt(i)
        val d = y.codePointAt(j)
        if (c != d) return c.compareTo(d)
        i += Character.charCount(c); j += Character.charCount(d)
    }
    return (x.length - i).compareTo(y.length - j)
}

/** Everything below U+0300 is already NFC; skip the normalizer for the common case. */
private fun nfc(s: String) = if (s.all { it < '̀' }) s else Normalizer.normalize(s, Normalizer.Form.NFC)

// org.json helpers: null for a missing key or JSON null (Swift `decodeIfPresent`).
fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
internal fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
internal fun JSONObject.doubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
fun JSONObject.boolOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
internal fun JSONObject.objectOrNull(key: String): JSONObject? = if (has(key) && !isNull(key)) getJSONObject(key) else null
internal fun JSONObject.arrayOrNull(key: String): JSONArray? = if (has(key) && !isNull(key)) getJSONArray(key) else null
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.objectsOrNull(key: String): List<JSONObject>? = arrayOrNull(key)?.objects()
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
internal fun JSONObject.strings(key: String): List<String> = arrayOrNull(key)?.strings() ?: emptyList()
