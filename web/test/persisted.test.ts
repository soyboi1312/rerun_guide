/// <reference types="node" />
// Port of android/app PersistedTest.kt: the saved state survives a round trip, and an older build's file keeps the
// defaults for keys it lacks (a throw there would wipe every profile and the server link).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ChannelDefinition, RuleChip } from '../src/core/Models'
import { PlexAddress } from '../src/core/PlexClient'
import { ChannelEdit, Persisted, Profile, ServerConfig, Settings } from '../src/app/AppModel'

const decode = (p: Persisted) => Persisted.fromJson(JSON.parse(JSON.stringify(Persisted.toJson(p))))

test('round trip and missing keys', () => {
  const p = Persisted.make({
    profiles: [new Profile('p1', 'You', '#f0a33a', 'MIDNIGHT', new Set(['genre:Sitcom']))],
    settings: Settings.make({ crtLines: false, subtitleLanguage: 'spa', tuneMode: 'start' }),
    channelEdits: new Map([['genre:Horror', ChannelEdit.make({ name: 'Shock', order: 'inOrder', trailerBreaks: false })]]),
    customChannels: [new ChannelDefinition({ id: 'custom:1', number: 50, name: 'Columbo', tag: 'Marathon',
      rule: [RuleChip.series('s1', 'Columbo')], order: 'inOrder', isCustom: true })],
    selectedLibraryIDs: ['1', '2'],
    serverConfig: new ServerConfig('plex', 'http://10.0.0.2:32400', 'Den', null,
      [PlexAddress.fromJson({ url: 'http://10.0.0.2:32400', local: true })], 'm'),
    lastChannelID: 'genre:Horror',
    guideFilter: 'movies',
  })
  assert.deepEqual(decode(p), p)

  const old = Persisted.fromJson(JSON.parse('{"currentProfileID":"p1","settings":{"crtLines":false,"subtitleMode":"auto"}}'))
  assert.deepEqual(old.settings, Settings.make({ crtLines: false }))   // "auto" was the old name of "forced"
  assert.deepEqual(old, Persisted.make({ settings: Settings.make({ crtLines: false }) }))
})

test('the JSON is the Kotlin toJson shape: same keys in the same order, nulls left out', () => {
  assert.equal(JSON.stringify(Persisted.toJson(Persisted.make())),
    '{"profiles":[{"id":"p1","name":"You","colorHex":"#f0a33a","theme":"late","hiddenChannelIDs":[]}],"currentProfileID":"p1",' +
    '"settings":{"crtLines":true,"autoScroll":true,"idleSeconds":8,"tuneMode":"live","trailerBreaks":true,"maxBitrateKbps":0,' +
    '"stationIdents":true,"attractMinutes":3,"subtitleMode":"forced","screensaver":"wall","houseSpots":true},' +
    '"channelEdits":{},"customChannels":[],"guideFilter":"all"}')
  assert.equal(new ServerConfig('jellyfin', 'http://h:8096', 'Den', 'u1').link, 'jellyfin|http://h:8096|u1')
})

test('a corrupt profile or an unknown enum name starts over, as Kotlin does', () => {
  assert.throws(() => Persisted.fromJson({ profiles: [{ id: 'p1' }] }))
  assert.throws(() => Persisted.fromJson({ guideFilter: 'sports' }))
  assert.throws(() => Persisted.fromJson({ settings: { tuneMode: 'later' } }))
})
