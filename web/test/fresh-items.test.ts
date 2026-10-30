/// <reference types="node" />
// FreshItemsTests.kt.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ChannelBuilder } from '../src/core/GuideGenerator'
import { Channel, MediaItem } from '../src/core/Models'
import * as Time from '../src/core/Time'

/** A newly added film passed as `fresh` reaches Just Added and New Releases, but not the genre channel it also matches. */
test('fresh items only reach the new channels', () => {
  const now = 1_800_000_000
  const year = Time.localYear(now)
  const film = (id: string, days: number) => new MediaItem({
    id, kind: 'movie', title: id, year, genres: ['Horror'], runtime: 5400, addedAt: now - days * 86_400,
  })
  const library = Array.from({ length: 6 }, (_, i) => film(`old${i}`, 10))
  const fresh = [film('new', 0)]
  const defs = ChannelBuilder.automaticChannels(library, undefined, undefined, undefined, now)
  const ids = (chs: Channel[], id: string) => chs.find(it => it.id === id)?.items.map(it => it.id) ?? []
  const chs = ChannelBuilder.channels(defs, library, fresh, now)
  assert.ok(ids(chs, 'lead:just-added').indexOf('new') >= 0)
  assert.ok(ids(chs, 'lead:new-movies').indexOf('new') >= 0)
  const shelf = chs.find(it => !ChannelBuilder.isFresh(it.definition) && it.items.some(i => i.genres.indexOf('Horror') >= 0))
  assert.ok(shelf)
  assert.ok(!shelf.items.some(it => it.id === 'new'))
  assert.deepEqual(ChannelBuilder.channels(defs, library, [], now).map(it => it.id), ChannelBuilder.channelsFrom(defs, library, now).map(it => it.id))
})
