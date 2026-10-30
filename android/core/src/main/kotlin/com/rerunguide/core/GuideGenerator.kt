package com.rerunguide.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Turns a flat library into channel definitions and resolves definitions back into items. */
object ChannelBuilder {
    /**
     * The automatic channel lineup. Up front, when the library has enough for them: New Releases (films from this year and last),
     * New This Season (TV from this year and last), Just Added (anything added in the last 60 days), Unwatched (films the
     * signed-in user hasn't seen), then the classic channels (TV and films from before 1970). After that every genre, theme,
     * marathon, director, actor and year channel, scattered with a fixed seed so the lineup feels like cable, not an
     * alphabetical list, yet is identical on every device.
     * Genre channels get retro names ("Studio Audience", "Gumshoe", "Toon Block"); unknown genres keep their own name.
     * No channel mixes films and TV: a genre gets a films channel with ≥ `minItems` films and a TV channel with
     * ≥ `minItems` episodes, and director, actor and year channels are films. Only the biggest groups make the lineup
     * (`maxSeries` marathons, `maxPeople` directors and actors, 3 years).
     */
    fun automaticChannels(from: List<MediaItem>, minItems: Int = 4, maxSeries: Int = 24, maxPeople: Int = 12,
                          now: Instant = Instant.now()): List<ChannelDefinition> {
        val library = from
        fun def(id: String, name: String, tag: String, rule: List<RuleChip>, order: PlayOrder = PlayOrder.shuffle) =
            ChannelDefinition(id = id, number = 0, name = name, tag = tag, rule = rule, order = order)
        fun span(items: List<MediaItem>): List<RuleChip> {
            val years = items.mapNotNull { it.year }
            return if (years.isEmpty()) emptyList() else listOf(RuleChip.years(years.min() / 10 * 10, years.max()))
        }
        fun count(rule: List<RuleChip>): Int = library.count { i -> rule.all { matches(it, i, now) } }

        // Lead channels.
        val year = now.atZone(ZoneId.systemDefault()).year
        val lead = mutableListOf<ChannelDefinition>()
        for ((id, name, tag, rule, order) in listOf(
            Lead("lead:new-movies", "New Releases", "Films from ${year - 1}–$year", listOf(RuleChip.years(year - 1, year), RuleChip.movies), PlayOrder.newestFirst),
            Lead("lead:new-tv", "New This Season", "TV from ${year - 1}–$year", listOf(RuleChip.years(year - 1, year), RuleChip.tv), PlayOrder.newestFirst),
            Lead("lead:just-added", "Just Added", "Films new on the server", listOf(RuleChip.addedWithin(days = 60), RuleChip.movies), PlayOrder.shuffle),
            Lead("lead:unwatched", "Unwatched", "Films you haven’t seen", listOf(RuleChip.unwatched, RuleChip.movies), PlayOrder.shuffle),
            Lead("lead:just-added-tv", "Fresh Episodes", "TV new on the server", listOf(RuleChip.addedWithin(days = 60), RuleChip.tv), PlayOrder.shuffle),
            Lead("lead:classic-tv", "Front Porch", "Classic TV, before 1970", listOf(RuleChip.years(1900, 1969), RuleChip.tv), PlayOrder.shuffle),
            Lead("lead:classic-movies", "The Vault", "Classic films, before 1970", listOf(RuleChip.years(1900, 1969), RuleChip.movies), PlayOrder.shuffle),
        )) if (count(rule) >= minItems) lead.add(def(id, name, tag, rule, order = order))

        val byGenre = LinkedHashMap<String, MutableList<MediaItem>>()
        val bySeries = LinkedHashMap<String, Pair<String, MutableList<MediaItem>>>()
        val byDirector = LinkedHashMap<String, Int>()
        val byActor = LinkedHashMap<String, Int>()
        val byYear = LinkedHashMap<Int, Int>()
        for (item in library) {
            for (g in item.genres) byGenre.getOrPut(g) { mutableListOf() }.add(item)
            when (item.kind) {
                MediaItem.Kind.episode -> {
                    val key = item.seriesID ?: item.seriesTitle ?: continue
                    bySeries.getOrPut(key) { (item.seriesTitle ?: key) to mutableListOf() }.second.add(item)
                }
                MediaItem.Kind.movie -> {
                    for (d in item.directors) byDirector[d] = (byDirector[d] ?: 0) + 1
                    for (a in item.actors) byActor[a] = (byActor[a] ?: 0) + 1
                    item.year?.let { y -> byYear[y] = (byYear[y] ?: 0) + 1 }
                }
            }
        }

        var genres = mutableListOf<ChannelDefinition>()
        for ((g, all) in byGenre) {
            if (all.size < minItems) continue
            val items = all.filter { matches(RuleChip.genre(g), it, now) }   // what the channel will really air (kids' titles leave horror)
            val movies = items.filter { it.kind == MediaItem.Kind.movie }
            val eps = items.filter { it.kind == MediaItem.Kind.episode }
            if (movies.size >= minItems) {
                val (mn, md) = retroName(g, ShelfKind.movies)
                genres.add(def("genre:$g:movies", mn, decadeTag(md, movies.mapNotNull { it.year }), listOf(RuleChip.genre(g)) + span(movies) + RuleChip.movies))
            }
            if (eps.size >= minItems) {
                val (tn, td) = retroName(g, ShelfKind.tv)
                genres.add(def("genre:$g:tv", tn, decadeTag(td, eps.mapNotNull { it.year }), listOf(RuleChip.genre(g)) + span(eps) + RuleChip.tv))
            }
        }
        // Two channels with the same retro name (two genres mapping to one, or a genre missing from the table) get told apart by what's on them.
        val dupes = genres.groupBy { it.name }.filter { it.value.size > 1 }.keys
        genres = genres.map { gd ->
            if (gd.name !in dupes) return@map gd
            val kind = if (RuleChip.tv in gd.rule) "TV" else if (RuleChip.movies in gd.rule) "Films"
                else gd.id.split(":").filter { it.isNotEmpty() }.drop(1).firstOrNull() ?: ""
            gd.copy(name = gd.name + " ($kind)")
        }.toMutableList()
        // Themed channels: a keyword search within a few related genres, for what no genre tag covers.
        val themes = listOf(
            def("theme:monsters", "Creature Feature", "Monster movies",
                listOf(RuleChip.movies, RuleChip.anyGenre(monsterGenres), RuleChip.keywords(monsterWords))),
        ).filter { count(it.rule) >= minItems }
        val series = bySeries.entries.filter { it.value.second.size >= minItems }
            .sortedWith { a, b -> if (a.value.second.size != b.value.second.size) b.value.second.size - a.value.second.size else swiftCompare(a.key, b.key) }
            .take(maxSeries).map {
                def("series:${it.key}", it.value.first, "Marathon", listOf(RuleChip.series(id = it.key, title = it.value.first)), order = PlayOrder.inOrder)
            }
        fun top(counts: Map<String, Int>): List<String> =
            counts.entries.filter { it.value >= minItems }
                .sortedWith { a, b -> if (a.value != b.value) b.value - a.value else swiftCompare(a.key, b.key) }
                .take(maxPeople).map { it.key }
        val directors = top(byDirector).map { def("director:$it", it, "Director", listOf(RuleChip.director(it), RuleChip.movies)) }
        val actors = top(byActor).map { def("actor:$it", it, "Actor", listOf(RuleChip.actor(it), RuleChip.movies)) }
        val years = byYear.entries.filter { it.value >= minItems }
            .sortedWith { a, b -> if (a.value != b.value) b.value - a.value else a.key.compareTo(b.key) }.take(3)
            .map { def("year:${it.key}", "Class of ’" + String.format(Locale.ROOT, "%02d", it.key % 100), "Films from ${it.key}", listOf(RuleChip.year(it.key), RuleChip.movies)) }

        // Scatter the rest with a fixed seed: same channels → same order on every device and launch.
        val rng = SeededGenerator(seed = "retro-binge-dial")
        val rest = (genres + themes + series + directors + actors + years).sortedWith { a, b -> swiftCompare(a.id, b.id) }.shuffled(using = rng)
        val all = (lead + rest).toMutableList()
        var n = 0
        for (i in all.indices) {
            n = if (i == 0) 2 else n + 1 + (StableHash.fnv1a(all[i].id) % 3u).toInt()
            if (n == Channel.scrambledNumber) n += 1   // 99 belongs to the scrambled channel
            all[i] = all[i].copy(number = n)
        }
        return all
    }

    private data class Lead(val id: String, val name: String, val tag: String, val rule: List<RuleChip>, val order: PlayOrder)

    internal enum class ShelfKind { movies, tv, mixed }

    private data class Names(val movies: Pair<String, String>, val tv: Pair<String, String>, val mixed: Pair<String, String>)
    private fun n(mn: String, md: String, tn: String, td: String, xn: String, xd: String) = Names(mn to md, tn to td, xn to xd)

    private val retroTable: Map<String, Names> = mapOf(
        "action" to n("Adrenaline", "Action films", "Stunt Hour", "Action TV", "Adrenaline", "Action"),
        "action & adventure" to n("Thrill Ride", "Action & adventure films", "Thrill Ride", "Action & adventure TV", "Thrill Ride", "Action & adventure"),
        "adventure" to n("The Matinee", "Adventure films", "Wide World", "Adventure TV", "The Matinee", "Adventure"),
        "animation" to n("The Big Cartoon", "Animated films", "Toon Block", "Cartoons", "Toon Block", "Animation"),
        "anime" to n("Anime Features", "Anime films", "Anime Block", "Anime", "Anime Block", "Anime"),
        "biography" to n("Life Stories", "Biopics", "Portraits", "Biographies", "Life Stories", "Biographies"),
        "children" to n("Kids’ Matinee", "Kids’ films", "After School", "Kids’ TV", "After School", "Kids"),
        "kids" to n("Kids’ Matinee", "Kids’ films", "After School", "Kids’ TV", "After School", "Kids"),
        "comedy" to n("Funny Business", "Comedy films", "Studio Audience", "Sitcoms", "Studio Audience", "Comedy"),
        "crime" to n("Mean Streets", "Crime films", "Gumshoe", "Detectives", "Gumshoe", "Crime"),
        "documentary" to n("True Stories", "Documentaries", "Field Notes", "Documentary TV", "True Stories", "Documentaries"),
        "drama" to n("The Picture Show", "Drama films", "Prime Time", "Drama series", "Prime Time", "Drama"),
        "family" to n("Family Hour", "Family films", "Family Room", "Family TV", "Family Hour", "Family"),
        "fantasy" to n("Once Upon", "Fantasy films", "The Realm", "Fantasy TV", "Once Upon", "Fantasy"),
        "food" to n("Dinner Bell", "Food", "Dinner Bell", "Food TV", "Dinner Bell", "Food"),
        "game show" to n("Game Night", "Game shows", "Game Night", "Game shows", "Game Night", "Game shows"),
        "history" to n("Days Gone By", "History films", "The Archive", "History TV", "Days Gone By", "History"),
        "holiday" to n("Yule Log", "Holiday films", "Tinsel Time", "Holiday TV", "Yule Log", "Holiday"),
        "home and garden" to n("Open House", "Home & garden", "Open House", "Home & garden", "Open House", "Home & garden"),
        "horror" to n("Fright Night", "Horror films", "Chiller", "Horror TV", "Fright Night", "Horror"),
        "martial arts" to n("Kung Fu Theater", "Martial arts", "Kung Fu Theater", "Martial arts", "Kung Fu Theater", "Martial arts"),
        "mini-series" to n("The Miniseries", "Mini-series", "The Miniseries", "Mini-series", "The Miniseries", "Mini-series"),
        "music" to n("The Jukebox", "Music films", "Video Hour", "Music TV", "The Jukebox", "Music"),
        "musical" to n("Showtunes", "Musicals", "Showtunes", "Musicals", "Showtunes", "Musicals"),
        "mystery" to n("Whodunit", "Mystery films", "Case Files", "Mystery TV", "Whodunit", "Mysteries"),
        "news" to n("Newsroom", "News", "Newsroom", "News", "Newsroom", "News"),
        "reality" to n("Real Life", "Reality TV", "Real Life", "Reality TV", "Real Life", "Reality TV"),
        "romance" to n("Date Night", "Romance films", "Love Lines", "Romance TV", "Date Night", "Romance"),
        "sci-fi" to n("Final Frontier", "Sci-fi films", "Space Age", "Sci-fi TV", "Space Age", "Sci-fi"),
        "science fiction" to n("Final Frontier", "Sci-fi films", "Space Age", "Sci-fi TV", "Space Age", "Sci-fi"),
        "soap" to n("Daytime", "Soaps", "Daytime", "Soaps", "Daytime", "Soaps"),
        "sport" to n("Game Day", "Sports films", "Sports Desk", "Sports TV", "Game Day", "Sports"),
        "sports" to n("Game Day", "Sports films", "Sports Desk", "Sports TV", "Game Day", "Sports"),
        "superhero" to n("Capes & Cowls", "Superhero films", "Secret Identity", "Superhero TV", "Capes & Cowls", "Superheroes"),
        "suspense" to n("Nail Biter", "Suspense films", "Held Breath", "Suspense TV", "Nail Biter", "Suspense"),
        "talk show" to n("Late Night", "Talk shows", "Late Night", "Talk shows", "Late Night", "Talk shows"),
        "thriller" to n("Edge of Your Seat", "Thrillers", "Cliffhanger", "Thriller TV", "Edge of Your Seat", "Thrillers"),
        "travel" to n("Passport", "Travel films", "Road Trip", "Travel TV", "Passport", "Travel"),
        "war" to n("The Front", "War films", "War Room", "War TV", "The Front", "War"),
        "western" to n("Dusty Trails", "Westerns", "The Frontier", "Western TV", "Dusty Trails", "Westerns"),
    )

    /**
     * Retro channel names for the common genres ("Studio Audience", "Gumshoe"). Returns (name, descriptor);
     * the descriptor becomes the tag with the decade span appended. Unknown genres fall back to the genre itself.
     */
    internal fun retroName(genre: String, kind: ShelfKind): Pair<String, String> {
        val e = retroTable[genre.lowercase()]
        if (e != null) return when (kind) { ShelfKind.movies -> e.movies; ShelfKind.tv -> e.tv; ShelfKind.mixed -> e.mixed }
        return when (kind) {
            ShelfKind.movies -> "$genre Movies" to "$genre films"
            ShelfKind.tv -> "$genre TV" to "$genre TV"
            ShelfKind.mixed -> genre to genre
        }
    }

    /**
     * Items matching every chip of the rule, in the sequence the channel's `order` wants
     * (`inOrder`: season/episode then year; `newestFirst`: year descending; `shuffle`: library order, shuffled per day by the scheduler).
     */
    fun resolve(definition: ChannelDefinition, library: List<MediaItem>, now: Instant = Instant.now()): List<MediaItem> {
        val items = library.filter { item -> definition.rule.all { matches(it, item, now) } }
        return when (definition.order) {
            PlayOrder.shuffle -> items
            PlayOrder.inOrder -> items.sortedWith { a, b ->
                compareValues(a.season ?: 0, b.season ?: 0).takeIf { it != 0 } ?: compareValues(a.episode ?: 0, b.episode ?: 0).takeIf { it != 0 }
                    ?: compareValues(a.year ?: 0, b.year ?: 0).takeIf { it != 0 } ?: swiftCompare(a.title, b.title)
            }
            PlayOrder.newestFirst -> items.sortedWith { a, b ->
                compareValues(b.year ?: 0, a.year ?: 0).takeIf { it != 0 } ?: swiftCompare(b.title, a.title)
            }
        }
    }

    fun matches(chip: RuleChip, item: MediaItem, now: Instant = Instant.now()): Boolean = when (chip) {
        is RuleChip.addedWithin -> item.addedAt?.let { it >= now.plusSeconds(-chip.days.toDouble() * 86_400) } ?: false
        is RuleChip.genre -> chip.value in item.genres && !(chip.value.lowercase() in grownUpGenres && isForKids(item))
        is RuleChip.years -> item.year?.let { it in chip.from..chip.to } ?: false
        is RuleChip.director -> chip.name in item.directors
        is RuleChip.actor -> chip.name in item.actors
        is RuleChip.series -> item.seriesID == chip.id || (item.seriesID == null && item.seriesTitle == chip.title)
        is RuleChip.year -> item.year == chip.value
        RuleChip.movies -> item.kind == MediaItem.Kind.movie
        RuleChip.tv -> item.kind == MediaItem.Kind.episode
        is RuleChip.anyGenre -> item.genres.any { it in chip.genres }
        is RuleChip.keywords -> mentions(item, chip.words)
        RuleChip.unwatched -> !item.watched
    }

    /** Genres whose channels stay grown-up: a kids' title tagged with one (the animated Gremlins series is "Horror") is left off. */
    internal val grownUpGenres = setOf("horror", "thriller")

    /**
     * Made for children: tagged Children, Kids or Family, rated for kids, or animated without a rating for older viewers
     * (an adult animated horror like Love, Death & Robots is TV-MA and stays).
     * ponytail: US ratings only; another country's rating on an animated title counts as not older.
     */
    internal fun isForKids(item: MediaItem): Boolean {
        val genres = item.genres.map { it.lowercase() }.toSet()
        val rating = item.contentRating?.uppercase()
        if (genres.any { it in setOf("children", "kids", "family") }) return true
        if (rating in setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "G")) return true
        return "animation" in genres && rating !in setOf("TV-14", "TV-MA", "PG-13", "R", "NC-17")
    }

    /** Word-start, case-insensitive search of the title and overview: "creature" takes "creatures", not "procreature". */
    internal fun mentions(item: MediaItem, any: List<String>): Boolean {
        val text = (item.title + " " + (item.overview ?: "")).lowercase()
        return any.any { w ->
            if (w.isEmpty()) return@any false   // Foundation finds no range for an empty needle
            var from = 0
            while (true) {
                val r = text.indexOf(w, from)
                if (r < 0) break
                if (r == 0 || !Character.isLetter(text.codePointBefore(r))) return@any true
                from = r + w.length
            }
            false
        }
    }

    /**
     * The keywords for "Creature Feature": lowercase, word-start prefixes ("werewol" takes werewolf and werewolves).
     * "shark" alone matches loan sharks and shark-infested waters; "great white" is the one that eats people.
     */
    internal val monsterWords = listOf("monster", "kaiju", "godzilla", "king kong", "kong:", "creature", "werewol", "vampire", "zombie",
        "frankenstein", "dracula", "mummy", "dinosaur", "great white", "megalodon", "gremlin", "xenomorph",
        "kraken", "leviathan", "cryptid", "sasquatch", "bigfoot", "swamp thing")
    /** The genres searched for those keywords: Fantasy would add Terabithia and Spirited Away; Action/Adventure adds Jurassic Park and Kong. */
    internal val monsterGenres = listOf("Horror", "Science Fiction", "Sci-Fi", "Action", "Adventure")

    /**
     * The "what's new" channels: New Releases, New This Season, Just Added, and any channel with an "Added in the last N
     * days" chip. They also see the items `MediaServer.recentlyAdded` found since the last full library load.
     */
    fun isFresh(d: ChannelDefinition): Boolean =
        d.id in setOf("lead:new-movies", "lead:new-tv", "lead:just-added", "lead:just-added-tv") || d.rule.any { it is RuleChip.addedWithin }

    /**
     * `channels(definitions, library, now)`, with `fresh` (newly added items not yet in `library`) given to the `isFresh`
     * channels only: every other channel keeps resolving from `library`, so its lineup for the day holds still until the next
     * full load.
     */
    fun channels(definitions: List<ChannelDefinition>, library: List<MediaItem>, fresh: List<MediaItem>, now: Instant = Instant.now()): List<Channel> {
        if (fresh.isEmpty()) return channels(definitions, library, now)
        return channels(definitions.filter { !isFresh(it) }, library, now) + channels(definitions.filter(::isFresh), library + fresh, now)
    }

    /** Resolve definitions against the library, dropping channels with nothing to air. */
    fun channels(definitions: List<ChannelDefinition>, library: List<MediaItem>, now: Instant = Instant.now()): List<Channel> =
        definitions.mapNotNull { d -> resolve(d, library, now).takeIf { it.isNotEmpty() }?.let { Channel(d, it) } }

    /** Convenience: automatic channels resolved. Used by tests. */
    fun channels(from: List<MediaItem>, minItems: Int = 4): List<Channel> =
        channels(automaticChannels(from, minItems = minItems), from)

    enum class CandidateKind(val rawValue: String) { director("Director"), actor("Actor"), series("Show marathon") }
    data class Candidate(val name: String, val id: String?, val count: Int)

    /** Names with enough in the library to fill an evening, for the "New channel" picker. Sorted by count desc, then name. */
    fun candidates(kind: CandidateKind, library: List<MediaItem>, minItems: Int = 3): List<Candidate> {
        val counts = LinkedHashMap<String, Pair<String?, Int>>()
        fun bump(key: String, id: String?) { val c = counts[key]; counts[key] = (c?.first ?: id) to ((c?.second ?: 0) + 1) }
        for (item in library) {
            when (kind) {
                CandidateKind.director -> if (item.kind == MediaItem.Kind.movie) for (d in item.directors) bump(d, null)
                CandidateKind.actor -> if (item.kind == MediaItem.Kind.movie) for (a in item.actors) bump(a, null)
                CandidateKind.series -> { val t = item.seriesTitle; if (item.kind == MediaItem.Kind.episode && t != null) bump(t, item.seriesID) }
            }
        }
        return counts.entries.filter { it.value.second >= minItems }
            .map { Candidate(name = it.key, id = it.value.first, count = it.value.second) }
            .sortedWith { a, b -> if (a.count != b.count) b.count - a.count else swiftCompare(a.name, b.name) }
    }

    internal fun decadeTag(genre: String, years: List<Int>): String {
        val lo = years.minOrNull() ?: return genre
        val hi = years.max()
        // "80s" for the 1900s, "2010s" from 2000 on, so "50s–2020s" can't be read as the 1920s.
        val d = { y: Int -> if (y < 2000) "${y % 100 / 10 * 10}s" else "${y / 10 * 10}s" }
        return "$genre, " + (if (lo / 10 == hi / 10) d(lo) else "${d(lo)}–${d(hi)}")
    }
}

/** FNV-1a 64 of a string seeding SplitMix64. Stable across processes and devices. */
class SeededGenerator(seed: String) {
    private var state: ULong = StableHash.fnv1a(seed)

    fun next(): ULong {
        state += 0x9e3779b97f4a7c15uL
        var z = state
        z = (z xor (z shr 30)) * 0xbf58476d1ce4e5b9uL
        z = (z xor (z shr 27)) * 0x94d049bb133111ebuL
        return z xor (z shr 31)
    }

    /** Swift's `RandomNumberGenerator.next(upperBound:)`: Lemire's nearly divisionless method, same draws, same result. */
    fun next(upperBound: ULong): ULong {
        require(upperBound != 0uL) { "upperBound cannot be zero." }
        var random = next()
        var low = random * upperBound
        if (low < upperBound) {
            val t = (0uL - upperBound) % upperBound
            while (low < t) {
                random = next()
                low = random * upperBound
            }
        }
        return multiplyHighUnsigned(random, upperBound)
    }

    /** Swift's `Int.random(in: 0..<until, using:)`. */
    fun nextInt(until: Int): Int {
        require(until > 0) { "Can't get random value with an empty range" }
        return next(until.toULong()).toInt()
    }

    private fun multiplyHighUnsigned(a: ULong, b: ULong): ULong {
        val aLo = a and 0xffffffffuL
        val aHi = a shr 32
        val bLo = b and 0xffffffffuL
        val bHi = b shr 32
        val lh = aLo * bHi
        val hl = aHi * bLo
        val mid = ((aLo * bLo) shr 32) + (lh and 0xffffffffuL) + (hl and 0xffffffffuL)
        return aHi * bHi + (lh shr 32) + (hl shr 32) + (mid shr 32)
    }
}

/** Swift's `shuffled(using:)`: Fisher–Yates front to back, each swap partner from `Int.random(in: 0..<remaining)`. */
fun <T> List<T>.shuffled(using: SeededGenerator): List<T> {
    val result = toMutableList()
    var amount = result.size
    var i = 0
    while (amount > 1) {
        val r = using.nextInt(amount)
        amount -= 1
        java.util.Collections.swap(result, i, i + r)
        i += 1
    }
    return result
}

/** Deterministic scheduler. Same (channel, day) → identical lineup on every device, every launch. No server state. */
object GuideGenerator {
    /** Guide grid resolution. Slots are (runtime + `pad`) rounded UP to a multiple of this. */
    const val blockLength: Double = 30.0 * 60
    /** Breathing room so a 28-minute episode isn't jammed into one block: ceil((runtime + 3 min) / 30 min) blocks. */
    const val pad: Double = 3.0 * 60

    private fun startOfDay(t: Instant, zone: ZoneId): ZonedDateTime = t.atZone(zone).toLocalDate().atStartOfDay(zone)

    /**
     * Lineup tiling `day` from local midnight to the next local midnight.
     * Pool order follows `channel.order`: shuffle = seeded shuffle (seed = channel.id + yyyy-MM-dd); inOrder/newestFirst =
     * the channel's item order rotated by a seeded offset so consecutive days don't all start at episode 1.
     * Repeat the pool if it runs short. If the next item doesn't fit before midnight, take the next one that does.
     * If none fits, no program starts that can't finish: the day's last slot runs on to midnight instead, re-picked to
     * the longest item that fits that window, and the time left over becomes its break. Only an item longer than the
     * whole day is still cut at midnight. A short program shares its half hour with the next ones that fit (`tile`).
     * Nothing airs twice in a row (`repeatKey`) unless the channel has nothing else; across midnight, unless nothing else
     * fits the last slot (or the channel plays in order).
     */
    fun schedule(channel: Channel, day: Instant, calendar: ZoneId = ZoneId.systemDefault()): List<Slot> {
        if (channel.items.isEmpty()) return emptyList()
        val start = startOfDay(day, calendar)
        // Tile by time: a DST day is 23 or 25 hours, never assume 48 blocks.
        // (Start of the next day, not 24h on: where a DST change skips midnight, a day starts at 1:00.)
        val next = start.toLocalDate().plusDays(1).atStartOfDay(calendar)
        // A day always opens with its pool's first item, so tonight's last slot can steer clear of tomorrow's opener
        // without looking at any other day's lineup. Not on a show played in order: skipping an episode at 11:30 PM
        // would break the run, and tomorrow starts somewhere else in it anyway.
        val opens = if (channel.order == PlayOrder.inOrder) null else repeatKey(pool(channel, next)[0])
        return tile(pool(channel, start), start.toInstant(), next.toInstant(), opens)
    }

    /**
     * What mustn't air twice in a row: the item, or for a film any copy of it (same title and year; a library often
     * holds a 4K copy as a second item). A cut is part of the title, so two cuts are two films. Episodes go by item, so a show's next episode, or the
     * next segment sharing its episode number, still follows.
     */
    internal fun repeatKey(item: MediaItem): String =
        if (item.kind == MediaItem.Kind.movie) "film:${item.title}|${item.year ?: 0}" else "item:${item.id}"

    /** The day's play order, seeded by channel and date. */
    internal fun pool(channel: Channel, day: ZonedDateTime): List<MediaItem> {
        val rng = SeededGenerator(seed = channel.id + String.format(Locale.ROOT, "|%04d-%02d-%02d", day.year, day.monthValue, day.dayOfMonth))
        return when (channel.order) {
            PlayOrder.shuffle -> channel.items.shuffled(using = rng)
            PlayOrder.inOrder, PlayOrder.newestFirst -> {
                val r = (rng.next() % channel.items.size.toULong()).toInt()
                channel.items.subList(r, channel.items.size) + channel.items.subList(0, r)
            }
        }
    }

    /**
     * Tiles [start, end) from `pool` as `schedule` describes, opening with `pool[0]`. `opens`: the `repeatKey` of what
     * airs right after `end`, which the last slot avoids when something else fits.
     */
    internal fun tile(pool: List<MediaItem>, start: Instant, end: Instant, opens: String?): List<Slot> {
        val slots = mutableListOf<Slot>()
        var cursor = start
        var next = 0
        while (cursor < end) {
            val previous = slots.lastOrNull()?.let { repeatKey(it.item) }
            var pick: Int? = null
            var again: Int? = null
            for (k in pool.indices) {
                val i = (next + k) % pool.size
                val slotEnd = cursor.plusSeconds(slotLength(pool[i]))
                if (slotEnd > end) continue
                val key = repeatKey(pool[i])
                if (slots.isEmpty() || key != previous && (slotEnd < end || key != opens)) { pick = i; break }
                if (again == null) again = i
            }
            // Only the same thing again fits: fine on a channel with nothing else that could air today (one film, or
            // copies of it). Otherwise it's the end of the day, below.
            val day = end.secondsSince(start)
            if (pick == null && again != null && pool.none { repeatKey(it) != previous && slotLength(it) <= day }) pick = again
            if (pick == null) {
                var last = slots.removeLastOrNull()
                // A half hour of short programs goes as a whole, so the re-picked slot starts on the half-hour grid.
                while (last != null && last.start.secondsSince(start) % blockLength != 0.0) last = slots.removeLastOrNull() ?: break
                if (last != null) {
                    val window = end.secondsSince(last.start)
                    var best = last.item   // fits by construction; a longer one that also fits leaves a shorter break
                    val before = slots.lastOrNull()?.let { repeatKey(it.item) }
                    if (before != null) {   // (the day's opener itself never changes)
                        // The longest that fits and repeats neither the slot before nor tomorrow's opener.
                        fun ok(it: MediaItem) = repeatKey(it) != before && repeatKey(it) != opens
                        var found: MediaItem? = if (ok(last.item)) last.item else null
                        for (k in pool.indices) {
                            val it = pool[(next + k) % pool.size]
                            if (slotLength(it) <= window && slotLength(it) > (found?.let { f -> slotLength(f) } ?: 0.0) && ok(it)) found = it
                        }
                        best = found ?: last.item
                    }
                    slots.add(Slot(best, last.start, end))
                } else {
                    slots.add(Slot(pool[next], cursor, end))   // longer than the whole day
                }
                break
            }
            val slotEnd = cursor.plusSeconds(slotLength(pool[pick]))
            // A short program (15 minutes or less: half its half hour, the pad not counted) doesn't leave the rest of it empty: the next ones in order that
            // fit play right after it, each in its own slot, the way cartoon blocks did. Nothing crosses the half hour.
            val parts = mutableListOf(pool[pick])
            var used = partLength(pool[pick])
            next = (pick + 1) % pool.size
            if (pool[pick].runtime <= slotEnd.secondsSince(cursor) / 2) {
                while (pool.size > 1) {
                    val it = pool[next]
                    if (used + partLength(it) > slotEnd.secondsSince(cursor) || parts.any { p -> p.id == it.id } ||
                        repeatKey(it) == repeatKey(parts.last()) || !(slotEnd < end || repeatKey(it) != opens)) break
                    parts.add(it); used += partLength(it); next = (next + 1) % pool.size
                }
            }
            for ((n, it) in parts.withIndex()) {   // the last one keeps whatever is left of the half hour as its break
                val partEnd = if (n == parts.size - 1) slotEnd else cursor.plusSeconds(partLength(it))
                slots.add(Slot(it, cursor, partEnd))
                cursor = partEnd
            }
        }
        return slots
    }

    /** A packed program's slot: its runtime in whole seconds, so the next one starts as it ends (no break in between). */
    internal fun partLength(item: MediaItem): Double = maxOf(1.0, floor(item.runtime))

    /** Slots on `channel` intersecting [from, to]. Spans days as needed. */
    fun schedule(channel: Channel, from: Instant, to: Instant, calendar: ZoneId = ZoneId.systemDefault()): List<Slot> {
        val slots = mutableListOf<Slot>()
        var day = startOfDay(from, calendar)
        while (day.toInstant() < to) {
            slots += schedule(channel, day.toInstant(), calendar)
            day = day.plusDays(1)
        }
        return slots.filter { it.end > from && it.start < to }
    }

    /** The slot containing `at` on `channel`. */
    fun slot(on: Channel, at: Instant, calendar: ZoneId = ZoneId.systemDefault()): Slot? =
        schedule(on, at, calendar).firstOrNull { it.start <= at && at < it.end }

    /**
     * Swift `slot(on:startingAt:)`. The slot by its exact start (what `ProgramKey` stores), if the generator still produces it.
     * Sub-second tolerance so a start that went through JSON (persisted key) still matches.
     */
    fun slotStartingAt(on: Channel, startingAt: Instant, calendar: ZoneId = ZoneId.systemDefault()): Slot? =
        schedule(on, startingAt, calendar).firstOrNull { abs(it.start.secondsSince(startingAt)) < 1 }

    /** later: before start · live: item playing · break: item over, slot not · past: slot over. */
    fun status(of: Slot, at: Instant): ProgramStatus {
        if (at < of.start) return ProgramStatus.later
        if (at >= of.end) return ProgramStatus.past
        return if (at >= of.itemEnd) ProgramStatus.`break` else ProgramStatus.live
    }

    fun slotLength(item: MediaItem): Double {
        // Channel 99's programs fill their slots exactly: no pad, so the scramble never drops into a station break.
        val padded = if (item.id.startsWith(Channel.scrambledID)) item.runtime else item.runtime + pad
        return maxOf(1.0, ceil(padded / blockLength)) * blockLength
    }
}
