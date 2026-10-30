/// <reference types="node" />
// GenreIDMigrationTests.kt.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { GenreIDMigration } from '../src/core/GenreIDMigration'

/**
 * A mixed genre channel splits: the Kids profile's hide covers both halves, the rename goes to the bigger half, the
 * stale id is gone, and a second run changes nothing.
 */
test('a split carries the hide and the edit', () => {
  const hidden = [new Set(['genre:Horror', 'genre:Comedy']), new Set<string>()]
  const edits = new Map([['genre:Horror', 'Creep Show']])
  const ids = ['genre:Horror:movies', 'genre:Horror:tv', 'genre:Comedy', 'lead:new-movies']
  const r = GenreIDMigration.migrate(ids, new Map([['genre:Horror:movies', 40], ['genre:Horror:tv', 12]]), hidden, edits)
  assert.ok(r.changed)
  assert.ok(r.editMoved)
  assert.deepEqual(hidden[0], new Set(['genre:Horror:movies', 'genre:Horror:tv', 'genre:Comedy']))
  assert.deepEqual(hidden[1], new Set())
  assert.deepEqual(edits, new Map([['genre:Horror:movies', 'Creep Show']]))
  const again = GenreIDMigration.migrate(ids, new Map(), hidden, edits)
  assert.ok(!again.changed)
  assert.ok(!again.editMoved)
})

/**
 * Halves merge back: hidden if either half was, the first stale edit wins, and an edit already on the merged channel is
 * kept rather than overwritten.
 */
test('a merge keeps the hide and an existing edit', () => {
  const hidden = [new Set(['genre:Drama:tv'])]
  const edits = new Map([['genre:Drama:movies', 'Old'], ['genre:Drama', 'Kept']])
  const r = GenreIDMigration.migrate(['genre:Drama'], new Map([['genre:Drama', 9]]), hidden, edits)
  assert.ok(r.changed)
  assert.ok(!r.editMoved)
  assert.deepEqual(hidden[0], new Set(['genre:Drama']))
  assert.deepEqual(edits, new Map([['genre:Drama', 'Kept']]))
})

/** Web only: on equal sizes the edit goes to the smaller id, as Kotlin's maxWith(size, then reversed id) picks. */
test('a tie goes to the smaller id', () => {
  const edits = new Map([['genre:War', 'Mine']])
  GenreIDMigration.migrate(['genre:War:tv', 'genre:War:movies'], new Map(), [], edits)
  assert.deepEqual(edits, new Map([['genre:War:movies', 'Mine']]))
})
