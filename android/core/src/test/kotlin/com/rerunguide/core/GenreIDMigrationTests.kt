package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreIDMigrationTests {
    /**
     * A mixed genre channel splits: the Kids profile's hide covers both halves, the rename goes to the bigger half, the stale
     * id is gone, and a second run changes nothing.
     */
    @Test fun testSplitCarriesHideAndEdit() {
        val hidden = mutableListOf(setOf("genre:Horror", "genre:Comedy"), setOf())
        val edits = mutableMapOf("genre:Horror" to "Creep Show")
        val ids = listOf("genre:Horror:movies", "genre:Horror:tv", "genre:Comedy", "lead:new-movies")
        val r = GenreIDMigration.migrate(currentIDs = ids, sizes = mapOf("genre:Horror:movies" to 40, "genre:Horror:tv" to 12),
                                         hidden = hidden, edits = edits)
        assertTrue(r.changed); assertTrue(r.editMoved)
        assertEquals(setOf("genre:Horror:movies", "genre:Horror:tv", "genre:Comedy"), hidden[0])
        assertEquals(setOf<String>(), hidden[1])
        assertEquals(mapOf("genre:Horror:movies" to "Creep Show"), edits)
        val again = GenreIDMigration.migrate(currentIDs = ids, sizes = mapOf(), hidden = hidden, edits = edits)
        assertFalse(again.changed); assertFalse(again.editMoved)
    }

    /**
     * Halves merge back: hidden if either half was, the first stale edit wins, and an edit already on the merged
     * channel is kept rather than overwritten.
     */
    @Test fun testMergeKeepsHideAndExistingEdit() {
        val hidden = mutableListOf(setOf("genre:Drama:tv"))
        val edits = mutableMapOf("genre:Drama:movies" to "Old", "genre:Drama" to "Kept")
        val r = GenreIDMigration.migrate(currentIDs = listOf("genre:Drama"), sizes = mapOf("genre:Drama" to 9), hidden = hidden, edits = edits)
        assertTrue(r.changed); assertFalse(r.editMoved)
        assertEquals(setOf("genre:Drama"), hidden[0])
        assertEquals(mapOf("genre:Drama" to "Kept"), edits)
    }
}
