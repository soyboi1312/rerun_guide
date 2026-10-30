package com.rerunguide.core

/**
 * A genre is one channel (`genre:G`) until both its films and its episodes reach the minimum, then two
 * (`genre:G:movies`, `genre:G:tv`), and one again if a half shrinks. The ids change, and every per-channel choice is
 * keyed by id, so carry them across: a hidden flag goes to every new channel (a Kids profile must not suddenly see
 * them), an edit goes to the new channel with the most items, and the stale ids are dropped.
 */
object GenreIDMigration {
    /** Swift's `(changed: Bool, editMoved: Bool)`. */
    data class Result(val changed: Boolean, val editMoved: Boolean)

    /**
     * `currentIDs`: this rebuild's automatic channel ids. `sizes`: item counts by id. `hidden`: each profile's hidden
     * ids (Swift `inout`: updated in place). `edits`: per-channel edits (updated in place). Returns whether anything
     * changed and whether an edit moved (which means the lineup has to be resolved again for the edit to apply).
     */
    fun <Edit : Any> migrate(currentIDs: List<String>, sizes: Map<String, Int>, hidden: MutableList<Set<String>>,
                             edits: MutableMap<String, Edit>): Result {
        val groups = LinkedHashMap<String, MutableList<String>>()   // base id → its current ids
        for (id in currentIDs) {
            if (!id.startsWith("genre:")) continue
            val base = if (id.endsWith(":movies")) id.dropLast(7) else if (id.endsWith(":tv")) id.dropLast(3) else id
            groups.getOrPut(base) { mutableListOf() }.add(id)
        }
        var changed = false
        var editMoved = false
        for ((base, now) in groups.entries.sortedWith { a, b -> swiftCompare(a.key, b.key) }) {
            val stale = (if (now == listOf(base)) listOf("$base:movies", "$base:tv") else listOf(base)).filter { it !in now }
            for (i in hidden.indices) {
                if (stale.none { it in hidden[i] }) continue
                hidden[i] = hidden[i] - stale.toSet() + now; changed = true
            }
            val from = stale.firstOrNull { edits[it] != null } ?: continue
            if (now.all { edits[it] == null }) {
                val to = now.maxWithOrNull(compareBy<String> { sizes[it] ?: 0 }.thenComparator { a, b -> swiftCompare(b, a) })
                if (to != null) { edits[to] = edits.getValue(from); editMoved = true }
            }
            for (id in stale) edits.remove(id)
            changed = true
        }
        return Result(changed, editMoved)
    }
}
