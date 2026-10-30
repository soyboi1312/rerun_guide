package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSelectionTests {
    private val tracks = listOf(
        MediaTrack("1", MediaTrack.Kind.audio, "eng", "English"),
        MediaTrack("2", MediaTrack.Kind.subtitle, "fre", "French (Forced SUBRIP)", isForced = true),
        MediaTrack("3", MediaTrack.Kind.subtitle, "eng", "English (Default Forced SUBRIP)", isDefault = true, isForced = true),
        MediaTrack("4", MediaTrack.Kind.subtitle, "eng", "English (SUBRIP)"),
    )
    private fun pick(t: List<MediaTrack>, server: ServerKind?, mode: String = "forced", lang: String = "eng") =
        TrackSelection.preferred(t, server, null, mode, lang).subtitle
    private fun track(id: String) = TrackSelection.Subtitle.track(id)

    @Test fun testForcedOnlyPicksTheForcedTrackOnEmbyAndJellyfin() {
        assertEquals(track("3"), pick(tracks, ServerKind.emby))
        assertEquals(track("3"), pick(tracks, ServerKind.jellyfin))
        // No forced track in the subtitle language: the device language, then the playing audio's; never a foreign one.
        assertEquals(track("3"), pick(tracks, ServerKind.emby, "forced", "spa"))   // English audio
        assertEquals(track("2"), TrackSelection.preferred(tracks, ServerKind.emby, null, "forced", "spa", deviceLanguage = "fra").subtitle)
        assertEquals(TrackSelection.Subtitle.off, pick(listOf(tracks[1], tracks[2]), ServerKind.emby, "forced", "spa"))   // no audio track to go by
        assertEquals(TrackSelection.Subtitle.off, pick(listOf(MediaTrack("1", MediaTrack.Kind.audio, "spa", "Spanish")) + tracks.drop(1), ServerKind.emby, "forced", "spa"))
        assertEquals(TrackSelection.Subtitle.off, pick(listOf(tracks[0], tracks[3]), ServerKind.emby))   // no forced track: none
    }

    @Test fun testBibliographicAndTerminologyCodesMatch() {
        // Emby reports "fre"/"ger", the device locale gives "fra"/"deu".
        assertEquals(track("2"), pick(tracks, ServerKind.emby, "forced", "fra"))
        val ger = listOf(MediaTrack("1", MediaTrack.Kind.audio, "ger", "Deutsch"), MediaTrack("2", MediaTrack.Kind.subtitle, "ger", "Deutsch"),
            MediaTrack("3", MediaTrack.Kind.subtitle, "eng", "English"))
        assertEquals(track("2"), pick(ger, ServerKind.emby, "on", "deu"))
        assertEquals("1", TrackSelection.preferred(ger, ServerKind.emby, "deu", "off", "eng").audioID)
        assertTrue(MediaTrack.sameLanguage("CHI", "zho"))
        assertTrue(MediaTrack.sameLanguage("nob", "nor"))
        assertTrue(MediaTrack.sameLanguage("NNO", "nor"))
        assertFalse(MediaTrack.sameLanguage(null, null))
        assertFalse(MediaTrack.sameLanguage("und", "und"))
    }

    @Test fun testForcedTrackWithoutLanguageNeverCounts() {
        val t = listOf(tracks[0], MediaTrack("2", MediaTrack.Kind.subtitle, null, "Forced", isForced = true))
        assertEquals(TrackSelection.Subtitle.off, pick(t, ServerKind.emby))
    }

    @Test fun testOnlyAnHlsRenditionRidesInThePlaylist() {
        fun path(c: String, m: String) = "/videos/1/master.m3u8?VideoCodec=h264,hevc&SegmentContainer=$c&SubtitleMethod=$m"
        assertTrue(JellyfinClient.segments(path("mp4", "Hls"), ServerKind.emby, true, false).second)
        assertFalse(JellyfinClient.segments(path("mp4", "Encode"), ServerKind.emby, true, false).second)   // burned in
        assertFalse(JellyfinClient.segments(path("mp4", "Hls"), ServerKind.emby, false, false).second)
        // Emby: always TS; ExoPlayer plays HEVC in TS, so VideoCodec stays. Jellyfin: TS for a rendition, unless HEVC.
        assertEquals(path("ts", "Hls"), JellyfinClient.segments(path("mp4", "Hls"), ServerKind.emby, true, true).first)
        assertEquals(path("ts", "Hls"), JellyfinClient.segments(path("mp4", "Hls"), ServerKind.jellyfin, true, false).first)
        assertEquals(path("mp4", "Hls"), JellyfinClient.segments(path("mp4", "Hls"), ServerKind.jellyfin, true, true).first)
        assertEquals(path("mp4", "Encode"), JellyfinClient.segments(path("mp4", "Encode"), ServerKind.jellyfin, true, false).first)
    }

    @Test fun testForcedOnlyStaysWithTheServerOnPlex() {
        assertEquals(TrackSelection.Subtitle.auto, pick(tracks, ServerKind.plex))
        assertEquals(TrackSelection.Subtitle.auto, pick(tracks, null))
    }

    @Test fun testOnAndOff() {
        assertEquals(track("4"), pick(tracks, ServerKind.emby, "on"))
        assertEquals(track("4"), pick(tracks, ServerKind.plex, "on"))
        assertEquals(TrackSelection.Subtitle.off, pick(tracks, ServerKind.emby, "off"))
        assertEquals(TrackSelection.Subtitle.auto, pick(tracks.take(1), ServerKind.emby, "on"))
        assertEquals("1", TrackSelection.preferred(tracks, ServerKind.emby, "eng", "off", "eng").audioID)
    }
}
