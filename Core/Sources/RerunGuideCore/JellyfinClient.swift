import Foundation
import os

/// Emby and Jellyfin share one client; `kind` selects the few places they differ.
public final class JellyfinClient: MediaServer, Sendable {
    public let kind: ServerKind
    public let baseURL: URL
    public let token: String
    public let userID: String
    public let deviceID: String

    public let serverName: String

    /// Streaming cap in kbit/s, 0 = original. Sent as MaxStreamingBitrate in PlaybackInfo and its DeviceProfile.
    public let maxBitrateKbps: Int

    /// `session` label → PlaySessionId of the transcode last started for it, so the next load (or stopPlayback) can kill it.
    private let sessions = OSAllocatedUnfairLock<[String: String]>(initialState: [:])
    /// MediaSources[0].Id per item, filled by `tracks(for:)`. Emby (verified on 4.10) only honours AudioStreamIndex /
    /// SubtitleStreamIndex when the PlaybackInfo query also names the MediaSourceId; Jellyfin doesn't mind it.
    private let sourceIDs = OSAllocatedUnfairLock<[String: String]>(initialState: [:])

    public init(baseURL: URL, token: String, userID: String, deviceID: String, kind: ServerKind, serverName: String = "", maxBitrateKbps: Int = 0) {
        self.maxBitrateKbps = maxBitrateKbps
        self.serverName = serverName.isEmpty ? (baseURL.host ?? kind.displayName) : serverName
        precondition(kind == .emby || kind == .jellyfin)
        let s = baseURL.absoluteString
        self.baseURL = s.hasSuffix("/") ? URL(string: String(s.dropLast())) ?? baseURL : baseURL
        self.token = token; self.userID = userID; self.deviceID = deviceID; self.kind = kind
    }

    // MARK: - Sign-in

    private struct Auth: Decodable { struct User: Decodable { let Id: String }; let AccessToken: String; let User: User }

    /// Username/password sign-in. Returns a ready client; persist `token` and `userID`.
    public static func login(baseURL: URL, username: String, password: String, deviceID: String, kind: ServerKind) async throws -> JellyfinClient {
        let anon = JellyfinClient(baseURL: baseURL, token: "", userID: "", deviceID: deviceID, kind: kind)
        let body = try JSONSerialization.data(withJSONObject: ["Username": username, "Pw": password])
        return await anon.signedIn(try await HTTP.json(anon.request("/Users/AuthenticateByName", method: "POST", body: body)))
    }

    /// Jellyfin Quick Connect (Jellyfin only): show `code`, user approves it in Settings › Quick Connect, poll, then exchange.
    /// Every call is unauthenticated but carries the MediaBrowser header so the server records the device.
    public struct QuickConnectSession: Sendable { public let code: String; public let secret: String
        public init(code: String, secret: String) { self.code = code; self.secret = secret } }

    public static func quickConnectStart(baseURL: URL, deviceID: String) async throws -> QuickConnectSession {
        struct Start: Decodable { let Code: String, Secret: String }
        let anon = anonymous(baseURL: baseURL, deviceID: deviceID)
        guard try await HTTP.json(anon.request("/QuickConnect/Enabled"), as: Bool.self) else { throw MediaServerError.notFound("Quick Connect") }
        let s: Start
        do { s = try await HTTP.json(anon.request("/QuickConnect/Initiate", method: "POST")) }
        catch MediaServerError.http(405, _) { s = try await HTTP.json(anon.request("/QuickConnect/Initiate")) } // 10.8 only routes GET
        return QuickConnectSession(code: s.Code, secret: s.Secret)
    }

    /// true once the user has approved the code. Throws `notFound` when the code has expired.
    public static func quickConnectIsApproved(baseURL: URL, secret: String, deviceID: String) async throws -> Bool {
        struct State: Decodable { let Authenticated: Bool }
        let anon = anonymous(baseURL: baseURL, deviceID: deviceID)
        do { return try await HTTP.json(anon.request("/QuickConnect/Connect", query: ["secret": secret]), as: State.self).Authenticated }
        catch MediaServerError.http(404, _) { throw MediaServerError.notFound("Quick Connect code (expired)") }
    }

    public static func loginWithQuickConnect(baseURL: URL, secret: String, deviceID: String) async throws -> JellyfinClient {
        let anon = anonymous(baseURL: baseURL, deviceID: deviceID)
        let body = try JSONSerialization.data(withJSONObject: ["Secret": secret])
        return await anon.signedIn(try await HTTP.json(anon.request("/Users/AuthenticateWithQuickConnect", method: "POST", body: body)))
    }

    /// Emby Connect (Emby only): trade a server's Connect access key (`EmbyConnect.servers`) for a local sign-in, trying its
    /// addresses in order, LAN first, with short timeouts so a viewer away from home doesn't sit on the LAN one.
    public static func connectExchange(_ server: PlexServer, connectUserID: String, deviceID: String) async throws -> JellyfinClient {
        struct Exchange: Decodable { let LocalUserId: String; let AccessToken: String }
        var last: Error = MediaServerError.notFound("an address for \(server.name)")
        var answered: Error? = nil   // a server's own refusal says more than another address timing out
        for a in server.addresses.isEmpty ? [PlexAddress(url: server.baseURL, local: false)] : server.addresses {
            if a.local {   // on another network a LAN address can be any device: send the key only if it answers as this server
                struct Info: Decodable { let Id: String? }
                var p = JellyfinClient(baseURL: a.url, token: "", userID: "", deviceID: deviceID, kind: .emby).request("/System/Info/Public")
                p.timeoutInterval = 4
                guard (try? await HTTP.json(p, as: Info.self))?.Id == server.id else { continue }
            }
            let keyed = JellyfinClient(baseURL: a.url, token: server.accessToken, userID: "", deviceID: deviceID, kind: .emby, serverName: server.name)
            var r = keyed.request("/Connect/Exchange", query: ["format": "json", "ConnectUserId": connectUserID])
            r.timeoutInterval = a.local ? 4 : 10
            do {
                let e: Exchange = try await HTTP.json(r)
                return JellyfinClient(baseURL: a.url, token: e.AccessToken, userID: e.LocalUserId, deviceID: deviceID, kind: .emby, serverName: server.name)
            } catch let e as MediaServerError {
                answered = answered ?? e
            } catch { last = error }
        }
        throw answered ?? last
    }

    private static func anonymous(baseURL: URL, deviceID: String) -> JellyfinClient {
        JellyfinClient(baseURL: baseURL, token: "", userID: "", deviceID: deviceID, kind: .jellyfin)
    }

    /// Auth result → ready client, named after the server when it will say (best-effort; host otherwise).
    private func signedIn(_ auth: Auth) async -> JellyfinClient {
        struct Info: Decodable { let ServerName: String? }
        let name = (try? await HTTP.json(request("/System/Info/Public"), as: Info.self))?.ServerName ?? ""
        return JellyfinClient(baseURL: baseURL, token: auth.AccessToken, userID: auth.User.Id, deviceID: deviceID, kind: kind, serverName: name)
    }

    // MARK: - Library

    /// Wire shape of a BaseItemDto; property names mirror the JSON so no CodingKeys are needed.
    private struct Item: Decodable {
        struct Person: Decodable { let Name: String?, `Type`: String? }
        let Id: String, Name: String?, `Type`: String
        let SeriesName: String?, SeriesId: String?, ParentIndexNumber: Int?, IndexNumber: Int?
        let ProductionYear: Int?, RunTimeTicks: Int64?, Genres: [String]?
        let ImageTags: [String: String]?, SeriesPrimaryImageTag: String?
        let BackdropImageTags: [String]?, ParentBackdropImageTags: [String]?, ParentBackdropItemId: String?
        let Overview: String?, OfficialRating: String?, People: [Person]?
        let DateCreated: String?
        /// The file, for a film's cut ({edition-…}); movies only.
        let Path: String?
        /// This user's state. Both servers send it on /Users/{id}/Items unless EnableUserData=false (verified on Emby 4.10).
        struct UserData: Decodable { let Played: Bool? }
        let UserData: UserData?
        /// Emby writes 7 fractional digits ("2026-02-12T08:00:00.0000000Z"); ISO8601DateFormatter wants at most 3, so cut them.
        var added: Date? { DateCreated.flatMap { try? Date(String($0.prefix(19)) + "Z", strategy: .iso8601) } }

        var hasBackdrop: Bool { !(BackdropImageTags ?? []).isEmpty }
        func people(_ type: String) -> [String] { (People ?? []).filter { $0.`Type` == type }.compactMap(\.Name) }
    }
    private struct Page: Decodable { let Items: [Item]; let TotalRecordCount: Int }

    public func libraries() async throws -> [LibrarySection] {
        struct View: Decodable { let Id: String, Name: String?, CollectionType: String? }
        struct Views: Decodable { let Items: [View] }
        let views: Views = try await HTTP.json(request("/Users/\(userID)/Views"))
        // Counts are a nicety: one request per view, in parallel, nil when a view won't count.
        let counts = await withTaskGroup(of: (Int, Int?).self, returning: [Int?].self) { group in
            for (i, v) in views.Items.enumerated() {
                let id = v.Id
                group.addTask { (i, try? await self.count(in: id)) }
            }
            var counts = [Int?](repeating: nil, count: views.Items.count)
            for await (i, n) in group { counts[i] = n }
            return counts
        }
        return zip(views.Items, counts).map { v, n in
            let kind: LibrarySection.Kind = switch v.CollectionType { case "movies": .movies; case "tvshows": .shows; default: .other }
            return LibrarySection(id: v.Id, title: v.Name ?? "", kind: kind, itemCount: n)
        }
    }

    private func count(in viewID: String) async throws -> Int {
        let page: Page = try await HTTP.json(request("/Users/\(userID)/Items", query: [
            "ParentId": viewID, "Recursive": "true", "IncludeItemTypes": "Movie,Series", "IsMissing": "false", "Limit": "1"]))
        return page.TotalRecordCount
    }

    public func libraryItems(in sections: [LibrarySection]?) async throws -> [MediaItem] {
        // Episodes usually carry no Genres/People/rating of their own; inherit from the series. Episodes are also not asked
        // for Genres/People: a 10k-episode library's per-episode cast list is tens of MB of JSON on every full load.
        let series = Dictionary(try await items(types: "Series", fields: Self.seriesFields).map { ($0.Id, $0) }, uniquingKeysWith: { a, _ in a })
        var out: [MediaItem] = []
        for parent in sections?.map(\.id) ?? [nil] { // ponytail: sections fetched one after another; a task group if many large views make launch slow
            for (types, fields) in Self.fetches {
                out += try await items(types: types, fields: fields, parent: parent).compactMap { mediaItem($0, series: series, parent: parent) }
            }
        }
        return out
    }

    /// The newest `limit` movies and `limit` episodes of each library, by date added, plus their series: a few small requests,
    /// so the app can run it every time it opens.
    public func recentlyAdded(in sections: [LibrarySection]?, limit: Int) async throws -> [MediaItem] {
        var found: [(item: Item, parent: String?)] = []
        for parent in sections?.map(\.id) ?? [nil] {
            for (types, fields) in Self.fetches {
                found += try await items(types: types, fields: fields, parent: parent, newest: limit).map { ($0, parent) }
            }
        }
        let ids = Array(Set(found.compactMap(\.item.SeriesId))).sorted()
        let series = ids.isEmpty ? [:] : Dictionary(try await items(types: "Series", fields: Self.seriesFields, ids: ids)
            .map { ($0.Id, $0) }, uniquingKeysWith: { a, _ in a })
        return found.compactMap { mediaItem($0.item, series: series, parent: $0.parent) }
    }

    private static let seriesFields = "Genres,People,OfficialRating"
    private static let fetches = [("Movie", "Genres,People,Overview,ProductionYear,OfficialRating,DateCreated,Path"),
                                  ("Episode", "Overview,SeriesPrimaryImage,ProductionYear,OfficialRating,DateCreated")]

    private func mediaItem(_ i: Item, series: [String: Item], parent: String?) -> MediaItem? {
        func or<C: Collection>(_ a: C?, _ b: C?) -> C? { a?.isEmpty == false ? a : b }
        func backdrop(_ id: String) -> String { "/Items/\(id)/Images/Backdrop/0" }
        guard let ticks = i.RunTimeTicks, ticks > 0 else { return nil }
        let isEpisode = i.`Type` == "Episode"
        let show = isEpisode ? i.SeriesId.flatMap { series[$0] } : nil
        let own = i.ImageTags?["Primary"].map { _ in "/Items/\(i.Id)/Images/Primary" }
        let art = isEpisode && i.SeriesPrimaryImageTag != nil && i.SeriesId != nil ? "/Items/\(i.SeriesId!)/Images/Primary" : own
        let wide: String? = if i.hasBackdrop { backdrop(i.Id) }
            else if !(i.ParentBackdropImageTags ?? []).isEmpty, let p = i.ParentBackdropItemId ?? i.SeriesId { backdrop(p) }
            else if let show, show.hasBackdrop { backdrop(show.Id) }
            else { nil }
        return MediaItem(id: i.Id, kind: isEpisode ? .episode : .movie,
                         title: isEpisode ? i.Name ?? "" : MediaItem.title(i.Name ?? "", edition: MediaItem.edition(inPath: i.Path)),
                         seriesTitle: i.SeriesName, seriesID: i.SeriesId, season: i.ParentIndexNumber, episode: i.IndexNumber,
                         year: i.ProductionYear, genres: or(show?.Genres, i.Genres) ?? [],
                         runtime: TimeInterval(ticks) / 10_000_000, artworkPath: art, backdropPath: wide,
                         overview: or(i.Overview, nil), contentRating: or(i.OfficialRating, show?.OfficialRating),
                         directors: or(i.people("Director"), show?.people("Director")) ?? [],
                         actors: Array((or(i.people("Actor"), show?.people("Actor")) ?? []).prefix(6)),
                         libraryID: parent, addedAt: i.added, watched: i.UserData?.Played == true)
    }

    /// Pages through /Users/{id}/Items, optionally under one view. Jellyfin always sends OfficialRating/ProductionYear;
    /// Emby (verified on 4.10) omits them unless they are listed in Fields, and both ignore Fields values they don't know.
    /// `newest`: one page of that many, newest first by date added. `ids`: only those items.
    private func items(types: String, fields: String, parent: String? = nil, newest: Int? = nil, ids: [String]? = nil) async throws -> [Item] {
        var all: [Item] = []
        while true {
            var query = ["Recursive": "true", "IncludeItemTypes": types, "IsMissing": "false", "SortBy": "SortName",
                         "Fields": fields, "StartIndex": "\(all.count)", "Limit": "500"]
            query["ParentId"] = parent
            if let newest { query["SortBy"] = "DateCreated"; query["SortOrder"] = "Descending"; query["Limit"] = "\(newest)" }
            if let ids { query["Ids"] = ids.joined(separator: ",") }
            let page: Page = try await HTTP.json(request("/Users/\(userID)/Items", query: query))
            all += page.Items
            if newest != nil || page.Items.isEmpty || all.count >= page.TotalRecordCount { return all }
        }
    }

    // MARK: - Playback

    /// AVPlayer-shaped profile: mp4/mov direct play only (no mkv); everything else becomes HLS, fMP4 first (HEVC is
    /// only legal in fMP4 per Apple HLS spec 1.5), ts h264-only as the fallback for servers without fMP4 HLS.
    /// The HLS playlist is full-length VOD starting at 0 (StartTimeTicks is ignored for hls); the player seeks to the offset.
    /// Text subtitles (subrip/srt, mov_text, vtt, external .srt) ride along as WebVTT HLS renditions, so a sub track no longer
    /// forces a burn-in transcode: Jellyfin picks that from the vtt/Hls SubtitleProfile alone; Emby (verified on 4.10) also
    /// needs "ManifestSubtitles":"vtt" on the HLS TranscodingProfiles (Jellyfin ignores the unknown field), and without the
    /// SubtitleProfile it burns in. ponytail: on Emby ASS and PGS/VobSub still burn in (full encode; ASS is never offered as
    /// a rendition), and an mp4 with a selected sub goes HLS instead of direct play.
    /// Emby (verified on 4.10) gets HLS audio as E-AC-3 only, always encoded (AllowAudioStreamCopy=false in the request): its
    /// fMP4 muxer breaks both other ways when the stream starts mid-file. An AC-3/E-AC-3 copy whose video keyframe sits a
    /// segment or more before the start gets a header-only first segment (moov with no dac3/dec3, no media) → AVPlayer fails
    /// with CoreMedia -16046 before the first frame (Challengers, Beavis and Butt-Head Do Christmas); an AAC encode restarts
    /// with tfdt back at 0 → frozen picture (Shrek). E-AC-3 encodes give clean segments with A/V aligned from 0.
    /// ponytail: this re-encodes AAC audio too, though an AAC copy plays fine; copying it needs a per-source request
    /// (the copy switch is per request), worth it only if E-AC-3 encoding costs the server noticeably.
    private static func deviceProfile(maxBitrate bps: Int, emby: Bool) -> String { """
    {"MaxStreamingBitrate":\(bps),
     "DirectPlayProfiles":[{"Type":"Video","Container":"mp4,m4v,mov","VideoCodec":"h264,hevc","AudioCodec":"aac,ac3,eac3,alac,flac"}],
     "TranscodingProfiles":[
       {"Type":"Video","Container":"mp4","Protocol":"hls","VideoCodec":"h264,hevc","AudioCodec":"\(emby ? "eac3" : "aac,ac3,eac3")",
        "Context":"Streaming","MinSegments":2,"BreakOnNonKeyFrames":true,"MaxAudioChannels":"6","ManifestSubtitles":"vtt"},
       {"Type":"Video","Container":"ts","Protocol":"hls","VideoCodec":"h264","AudioCodec":"aac,ac3,eac3",
        "Context":"Streaming","MinSegments":2,"BreakOnNonKeyFrames":true,"MaxAudioChannels":"6","ManifestSubtitles":"vtt"}],
     "CodecProfiles":[],"SubtitleProfiles":[{"Format":"vtt","Method":"Hls"}]}
    """ }

    public func playbackSource(for item: MediaItem, startingAt offset: TimeInterval, session: String, tracks: TrackSelection) async throws -> PlaybackSource {
        try await playbackSource(itemID: item.id, startingAt: offset, session: session, tracks: tracks)
    }

    /// Audio/subtitle streams of the item's first MediaSource (same DTO on Emby and Jellyfin); id = the stream Index.
    public func tracks(for item: MediaItem) async throws -> [MediaTrack] {
        struct Stream: Decodable { let Index: Int, `Type`: String, Codec: String?, Language: String?, Title: String?
            let DisplayTitle: String?, DisplayLanguage: String?, IsDefault: Bool?, IsForced: Bool? }
        struct Source: Decodable { let Id: String?; let MediaStreams: [Stream]? }
        struct Detail: Decodable { let MediaSources: [Source]? }
        let d: Detail = try await HTTP.json(request("/Users/\(userID)/Items/\(item.id)", query: ["Fields": "MediaStreams"]))
        if let sid = d.MediaSources?.first?.Id { sourceIDs.withLock { $0[item.id] = sid } }
        return (d.MediaSources?.first?.MediaStreams ?? []).compactMap { s in
            let kind: MediaTrack.Kind
            switch s.`Type` { case "Audio": kind = .audio; case "Subtitle": kind = .subtitle; default: return nil }
            return MediaTrack(id: String(s.Index), kind: kind, language: s.Language,
                              title: s.DisplayTitle ?? s.Title ?? s.DisplayLanguage ?? s.Codec ?? "",
                              isDefault: s.IsDefault ?? false, isForced: s.IsForced ?? false)
        }
    }

    /// Local trailer extra of the movie (or, for an episode, its series) via the same PlaybackInfo flow; nil when none.
    /// RemoteTrailers are YouTube links AVPlayer can't play, so they are ignored.
    public func trailer(for item: MediaItem, session: String) async throws -> PlaybackSource? {
        struct Extra: Decodable { let Id: String }
        let owner = item.kind == .episode ? (item.seriesID ?? item.id) : item.id
        let extras: [Extra] = try await HTTP.json(request("/Users/\(userID)/Items/\(owner)/LocalTrailers"))
        guard let id = extras.first?.Id else { return nil }
        return try await playbackSource(itemID: id, startingAt: 0, session: session)
    }

    /// Best-effort kill of the transcode last started for `session` (DELETE /Videos/ActiveEncodings; Jellyfin requires the
    /// PlaySessionId, Emby accepts it). A direct-play source stores an id too, which makes the DELETE a harmless no-op.
    public func stopPlayback(session: String) async {
        guard let id = sessions.withLock({ $0.removeValue(forKey: session) }) else { return }
        _ = try? await HTTP.data(request("/Videos/ActiveEncodings", query: ["DeviceId": deviceID, "PlaySessionId": id], method: "DELETE"))
    }

    /// Both servers' "mark played" for this user: POST /Users/{id}/PlayedItems/{item}.
    public func markWatched(_ item: MediaItem) async throws {
        _ = try await HTTP.data(request("/Users/\(userID)/PlayedItems/\(item.id)", method: "POST"))
    }

    private func playbackSource(itemID: String, startingAt offset: TimeInterval, session: String, tracks: TrackSelection = TrackSelection()) async throws -> PlaybackSource {
        struct Stream: Decodable { let `Type`: String?; let Codec: String? }
        struct Source: Decodable { let Id: String; let SupportsDirectPlay: Bool?; let SupportsDirectStream: Bool?; let TranscodingUrl: String?; let MediaStreams: [Stream]? }
        struct Info: Decodable { let MediaSources: [Source]; let PlaySessionId: String? }
        await stopPlayback(session: session)   // a reload replaces this purpose's transcode instead of adding one
        // 0 = original, sent as 120 Mbps (no real cap). ponytail: the muted 788-px guide preview is capped at 4 Mbps
        // regardless, so sources above that transcode instead of remuxing; drop the cap if a software-only server can't keep up.
        var kbps = maxBitrateKbps > 0 ? maxBitrateKbps : 120_000
        if session == "preview" { kbps = min(kbps, 4000) }
        // Chosen streams by index (PlaybackInfoDto.AudioStreamIndex / SubtitleStreamIndex, same names on Emby). -1 = no
        // subtitle: the server looks up stream index -1, finds none, and the manifest carries no sub. A chosen audio or
        // subtitle track turns direct play off so the server muxes that audio into HLS and lists text subs as renditions
        // (Jellyfin marks the chosen one DEFAULT=YES, Emby none; the player selects it); a PGS/VobSub pick is burned in.
        let direct: Bool = tracks.audioID == nil && { if case .track = tracks.subtitle { false } else { true } }()
        var picks = tracks.audioID.flatMap(Int.init).map { ",\"AudioStreamIndex\":\($0)" } ?? ""
        switch tracks.subtitle {
        case .track(let id): picks += Int(id).map { ",\"SubtitleStreamIndex\":\($0)" } ?? ""
        case .off: picks += ",\"SubtitleStreamIndex\":-1"
        case .auto: break
        }
        let body = """
        {"UserId":"\(userID)","DeviceProfile":\(Self.deviceProfile(maxBitrate: kbps * 1000, emby: kind == .emby)),"StartTimeTicks":\(Int64(offset * 10_000_000)),
         "MaxStreamingBitrate":\(kbps * 1000),"EnableDirectPlay":\(direct),"EnableDirectStream":true,"EnableTranscoding":true,
         "AutoOpenLiveStream":true,"AllowVideoStreamCopy":true,"AllowAudioStreamCopy":\(kind != .emby)\(picks)}
        """
        // Emby (verified on 4.10) reads the stream indices and the direct-play switch from the QUERY STRING only (its own web
        // client sends them there), while Jellyfin reads the body. Send both so one request shape works on either server.
        var query = ["UserId": userID, "StartTimeTicks": "\(Int64(offset * 10_000_000))", "MaxStreamingBitrate": "\(kbps * 1000)",
                     "EnableDirectPlay": direct ? "true" : "false", "EnableDirectStream": "true", "EnableTranscoding": "true",
                     "AllowAudioStreamCopy": kind == .emby ? "false" : "true"]
        if !tracks.isDefault {
            var sid = sourceIDs.withLock { $0[itemID] }
            if sid == nil {   // the picker always lists tracks first; this is the fallback for a direct call
                struct Src: Decodable { let Id: String? }; struct D: Decodable { let MediaSources: [Src]? }
                sid = (try? await HTTP.json(request("/Users/\(userID)/Items/\(itemID)", query: ["Fields": "MediaSources"]), as: D.self))?.MediaSources?.first?.Id
            }
            query["MediaSourceId"] = sid
        }
        if let a = tracks.audioID.flatMap(Int.init) { query["AudioStreamIndex"] = "\(a)" }
        switch tracks.subtitle {
        case .track(let id): if let i = Int(id) { query["SubtitleStreamIndex"] = "\(i)" }
        case .off: query["SubtitleStreamIndex"] = "-1"
        case .auto: break
        }
        let info: Info = try await HTTP.json(request("/Items/\(itemID)/PlaybackInfo", query: query, method: "POST", body: Data(body.utf8)))
        let play = info.PlaySessionId
        sessions.withLock { $0[session] = play }
        guard let src = info.MediaSources.first else { throw MediaServerError.invalidResponse("no MediaSources for \(itemID)") }
        // An audio pick turns direct play off, and for a file that needs nothing done (an mp4 with its one AAC track) Emby
        // then answers "direct stream" with no TranscodingUrl: play the file as it is.
        // ponytail: a non-default audio pick on such a file plays its default audio; send it to HLS if that ever matters.
        if src.SupportsDirectPlay == true || (src.TranscodingUrl == nil && src.SupportsDirectStream == true) {
            return PlaybackSource(url: url("/Videos/\(itemID)/stream", query: [
                "Static": "true", "MediaSourceId": src.Id, "DeviceId": deviceID, "api_key": token]), startsAtOffset: false)
        }
        guard var path = src.TranscodingUrl else { throw MediaServerError.invalidResponse("no playable source for \(itemID)") }
        if !path.hasPrefix("/") { path = "/" + path }
        let hevc = src.MediaStreams?.contains { $0.`Type` == "Video" && ["hevc", "h265"].contains($0.Codec?.lowercased()) } ?? false
        let hlsSubs: Bool
        (path, hlsSubs) = Self.segments(path, kind: kind, picked: { if case .track = tracks.subtitle { true } else { false } }(), hevc: hevc)
        // Emby omits the token from TranscodingUrl; Jellyfin includes it (api_key= or, 10.9+, ApiKey=).
        if !path.lowercased().contains("api_key=") && !path.lowercased().contains("apikey=") {
            path += (path.contains("?") ? "&" : "?") + "api_key=" + token
        }
        guard let url = URL(string: baseURL.absoluteString + path) else { throw MediaServerError.invalidResponse("bad TranscodingUrl \(path)") }
        return PlaybackSource(url: url, startsAtOffset: false, subtitleInPlaylist: hlsSubs)
    }

    /// Emby (verified on 4.10) restarts the encoder of an fMP4 HLS stream mid-play with segments whose tfdt starts over at 0
    /// and no EXT-X-DISCONTINUITY; AVPlayer freezes on the jump back (a burned-in subtitle restarts it within a minute).
    /// Its TS segments keep the source timestamps across restarts. A burn-in re-encodes to H.264 anyway, which TS carries.
    /// A WebVTT rendition goes TS too: both servers stamp every subtitle segment X-TIMESTAMP-MAP=MPEGTS:900000 (Emby 4.10
    /// ignores VttTimestampMap; Jellyfin releases before PR #17299), which matches TS video (PTS from 10 s) and puts cues 10 s late
    /// over fMP4 (tfdt from 0). AVPlayer decodes no HEVC in TS, so every switch to TS also narrows VideoCodec to H.264
    /// (an HEVC source is re-encoded; with VAAPI hardware encoding the first segment is ready in under a second). ponytail: on
    /// Jellyfin an HEVC source keeps fMP4 and late cues; extend the switch once it's checked on a Jellyfin server.
    /// An HEVC remux stays fMP4 elsewhere.
    /// Only a pick the server lists as an HLS rendition (SubtitleMethod=Hls) rides in the playlist; a burn-in (Encode) is in
    /// the picture. Returns the rewritten TranscodingUrl path and whether the pick is a playlist rendition.
    static func segments(_ path: String, kind: ServerKind, picked: Bool, hevc: Bool) -> (path: String, subtitleInPlaylist: Bool) {
        let hlsSubs = picked && path.contains("SubtitleMethod=Hls")
        guard (kind == .emby && path.contains("SubtitleMethod=Encode")) || (hlsSubs && (kind == .emby || !hevc)) else { return (path, hlsSubs) }
        return (path.replacingOccurrences(of: "SegmentContainer=mp4", with: "SegmentContainer=ts")
                    .replacingOccurrences(of: "VideoCodec=h264,hevc", with: "VideoCodec=h264"), hlsSubs)
    }

    public func artworkURL(path: String?) -> URL? {
        path.map { url($0, query: ["maxWidth": "600", "api_key": token]) }
    }

    // MARK: - HTTP

    private func url(_ path: String, query: [String: String]) -> URL {
        var c = URLComponents(string: baseURL.absoluteString + path)!
        c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return c.url!
    }

    /// MediaBrowser auth on every request. Jellyfin reads `Authorization`, Emby reads `X-Emby-Authorization` /
    /// `X-Emby-Token`; each ignores the other's header, so send all. Emby rejects the scheme without a DeviceId.
    private func request(_ path: String, query: [String: String] = [:], method: String = "GET", body: Data? = nil) -> URLRequest {
        var r = URLRequest(url: url(path, query: query), timeoutInterval: 30)
        r.httpMethod = method
        r.httpBody = body
        var auth = "MediaBrowser Client=\"Rerun Guide\", Device=\"\(clientDevice.device)\", DeviceId=\"\(deviceID)\", Version=\"1.0\""
        if !token.isEmpty { auth += ", Token=\"\(token)\""; r.setValue(token, forHTTPHeaderField: "X-Emby-Token") }
        r.setValue(auth, forHTTPHeaderField: "Authorization")
        r.setValue(auth, forHTTPHeaderField: "X-Emby-Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { r.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        return r
    }
}
