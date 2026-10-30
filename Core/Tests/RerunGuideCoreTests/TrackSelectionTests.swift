import XCTest
@testable import RerunGuideCore

final class TrackSelectionTests: XCTestCase {
    private let tracks = [
        MediaTrack(id: "1", kind: .audio, language: "eng", title: "English"),
        MediaTrack(id: "2", kind: .subtitle, language: "fre", title: "French (Forced SUBRIP)", isForced: true),
        MediaTrack(id: "3", kind: .subtitle, language: "eng", title: "English (Default Forced SUBRIP)", isDefault: true, isForced: true),
        MediaTrack(id: "4", kind: .subtitle, language: "eng", title: "English (SUBRIP)"),
    ]
    private func pick(_ t: [MediaTrack], _ server: ServerKind?, _ mode: String = "forced", _ lang: String = "eng") -> TrackSelection.Subtitle {
        TrackSelection.preferred(for: t, server: server, audioLanguage: nil, subtitleMode: mode, subtitleLanguage: lang).subtitle
    }

    func testForcedOnlyPicksTheForcedTrackOnEmbyAndJellyfin() {
        XCTAssertEqual(pick(tracks, .emby), .track("3"))
        XCTAssertEqual(pick(tracks, .jellyfin), .track("3"))
        // No forced track in the subtitle language: the device language, then the playing audio's; never a foreign one.
        XCTAssertEqual(pick(tracks, .emby, "forced", "spa"), .track("3"))   // English audio
        XCTAssertEqual(TrackSelection.preferred(for: tracks, server: .emby, audioLanguage: nil, subtitleMode: "forced",
                                                subtitleLanguage: "spa", deviceLanguage: "fra").subtitle, .track("2"))
        XCTAssertEqual(pick([tracks[1], tracks[2]], .emby, "forced", "spa"), .off)   // no audio track to go by
        XCTAssertEqual(pick([MediaTrack(id: "1", kind: .audio, language: "spa", title: "Spanish")] + tracks.dropFirst(), .emby, "forced", "spa"), .off)
        XCTAssertEqual(pick(Array(tracks.prefix(1)) + [tracks[3]], .emby), .off)   // no forced track: none
    }

    func testBibliographicAndTerminologyCodesMatch() {
        // Emby reports "fre"/"ger", the device locale gives "fra"/"deu".
        XCTAssertEqual(pick(tracks, .emby, "forced", "fra"), .track("2"))
        let ger = [MediaTrack(id: "1", kind: .audio, language: "ger", title: "Deutsch"),
                   MediaTrack(id: "2", kind: .subtitle, language: "ger", title: "Deutsch"),
                   MediaTrack(id: "3", kind: .subtitle, language: "eng", title: "English")]
        XCTAssertEqual(pick(ger, .emby, "on", "deu"), .track("2"))
        XCTAssertEqual(TrackSelection.preferred(for: ger, server: .emby, audioLanguage: "deu", subtitleMode: "off", subtitleLanguage: "eng").audioID, "1")
        XCTAssertTrue(MediaTrack.sameLanguage("CHI", "zho"))
        XCTAssertTrue(MediaTrack.sameLanguage("nob", "nor"))
        XCTAssertTrue(MediaTrack.sameLanguage("NNO", "nor"))
        XCTAssertFalse(MediaTrack.sameLanguage(nil, nil))
        XCTAssertFalse(MediaTrack.sameLanguage("und", "und"))
    }

    func testForcedTrackWithoutLanguageNeverCounts() {
        let t = [tracks[0], MediaTrack(id: "2", kind: .subtitle, language: nil, title: "Forced", isForced: true)]
        XCTAssertEqual(pick(t, .emby), .off)
    }

    func testForcedOnlyStaysWithTheServerOnPlex() {
        XCTAssertEqual(pick(tracks, .plex), .auto)
        XCTAssertEqual(pick(tracks, nil), .auto)
    }

    func testOnAndOff() {
        XCTAssertEqual(pick(tracks, .emby, "on"), .track("4"))
        XCTAssertEqual(pick(tracks, .plex, "on"), .track("4"))
        XCTAssertEqual(pick(tracks, .emby, "off"), .off)
        XCTAssertEqual(pick(Array(tracks.prefix(1)), .emby, "on"), .auto)
        XCTAssertEqual(TrackSelection.preferred(for: tracks, server: .emby, audioLanguage: "eng", subtitleMode: "off", subtitleLanguage: "eng").audioID, "1")
    }
}

final class SegmentsTests: XCTestCase {
    private func path(_ container: String, _ codec: String, _ method: String) -> String {
        "/videos/1/master.m3u8?VideoCodec=\(codec)&SegmentContainer=\(container)&SubtitleMethod=\(method)"
    }

    func testOnlyAnHlsRenditionRidesInThePlaylist() {
        XCTAssertTrue(JellyfinClient.segments(path("mp4", "h264,hevc", "Hls"), kind: .emby, picked: true, hevc: false).subtitleInPlaylist)
        XCTAssertFalse(JellyfinClient.segments(path("mp4", "h264,hevc", "Encode"), kind: .emby, picked: true, hevc: false).subtitleInPlaylist)   // burned in
        XCTAssertFalse(JellyfinClient.segments(path("mp4", "h264,hevc", "Hls"), kind: .emby, picked: false, hevc: false).subtitleInPlaylist)
    }

    func testEverySwitchToTSAlsoNarrowsToH264() {
        for (kind, method, hevc) in [(ServerKind.emby, "Hls", true), (.emby, "Hls", false), (.emby, "Encode", false), (.jellyfin, "Hls", false)] {
            XCTAssertEqual(JellyfinClient.segments(path("mp4", "h264,hevc", method), kind: kind, picked: true, hevc: hevc).path, path("ts", "h264", method))
        }
        // Jellyfin keeps fMP4 for an HEVC source and for a burn-in.
        XCTAssertEqual(JellyfinClient.segments(path("mp4", "h264,hevc", "Hls"), kind: .jellyfin, picked: true, hevc: true).path, path("mp4", "h264,hevc", "Hls"))
        XCTAssertEqual(JellyfinClient.segments(path("mp4", "h264,hevc", "Encode"), kind: .jellyfin, picked: true, hevc: false).path, path("mp4", "h264,hevc", "Encode"))
    }
}
