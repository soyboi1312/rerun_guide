import SwiftUI
import AVFoundation
import AVKit
import Combine
import RerunGuideCore

/// Now playing: full-screen AVPlayer, break trailers / station card, channel banner, static flash.
/// The clock is the source of truth (prototype `wInfo`): the player loads the current program at the clock's offset
/// and is reloaded when the (channel, slot, tune, break) key changes; banner/focus/pause changes never touch it.
struct WatchScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @State private var loaded: Loaded?
    /// The station card on screen (tune-in / break), cleared 1.5 s after it was set.
    @State private var ident: Ident?
    /// What the player last said about its load: first frame shown, stalled (the loader's cue).
    @State private var status = PlayerStatus()
    /// The break's hand-off (the spot over or failed, a trailer failed): a new key, or leaving, cancels it and its requests.
    @State private var handOff: Task<Void, Never>?

    var body: some View {
        TimelineView(.periodic(from: .timelineOrigin, by: 1)) { ctx in
            let now = ctx.date
            let info = model.watchInfo(at: now)
            let key = info.map { LoadKey(channelID: $0.channel.id, slot: $0.slot, tune: model.tunes, isBreak: $0.isBreak) }
            let current = loaded?.key == key ? loaded : nil
            ZStack(alignment: .topLeading) {
                Color.black
                if let info {
                    stage(info, current: current, now: now)
                }
            }
            .backgroundTap { model.watchTap() }
            .task(id: key) { await load(key) }
            .task(id: ident) {
                guard ident != nil, (try? await Task.sleep(for: .seconds(1.5))) != nil else { return }
                withAnimation(.easeOut(duration: 0.4)) { ident = nil }
            }
            .onChange(of: model.identSerial, initial: true) {   // initial: tune() from the guide bumps it before this view exists
                guard let info, model.rewind == nil else { return }   // a start-over from the guide rewinds instead
                showIdent("YOU’RE WATCHING", sub: info.isBreak ? "Back at \(Clock.time(info.slot.end))" : info.slot.item.displayTitle, .tune)
            }
            .onChange(of: key) { old, new in   // the program rolled into its break under the same tune
                handOff?.cancel()
                if loaded?.key != new { model.spotChanged(end: nil) }   // a channel change mid-spot: the spot is gone now
                guard let old, let new, let info, new.isBreak, !old.isBreak, new.tune == old.tune,
                      model.settings.trailerBreaks && info.channel.trailerBreaks, !info.channel.isScrambled else { return }
                showIdent("WE’LL BE RIGHT BACK", sub: "\(info.channel.name) · back at \(Clock.time(new.slot.end))", .brk)
            }
            .onChange(of: now) { model.watchTick(at: now) }
            .onChange(of: loaded) { _, l in   // the spot on screen or gone: the remote can skip it, VoiceOver says it's an ad
                guard let l, l.spot else { model.spotChanged(end: nil); return }
                // Ended or skipped: off the air now (station / title card), not frozen on its last frame while the next one loads.
                model.spotChanged {
                    guard loaded?.key == l.key else { return }   // a stale end after a channel change must not clear the new one
                    loaded = Loaded(key: l.key, url: nil, startsAtOffset: false, offset: 0, trailer: nil, loop: false)
                    handOff = Task { await load(l.key, skipSpot: true) }
                }
            }
        }
        .frame(width: 1920, height: 1080)
        .onDisappear { handOff?.cancel(); model.cancelTimer("banner"); model.spotChanged(end: nil); loaded = nil; Task { await model.server?.stopPlayback(session: "watch") } }
    }

    @ViewBuilder private func stage(_ info: WatchInfo, current: Loaded?, now: Date) -> some View {
        let ch = info.channel, item = info.slot.item
        let trailerOn = current?.trailer != nil || (current == nil && model.settings.trailerBreaks && ch.trailerBreaks)
        let tv = item.kind == .episode && !info.isBreak
        let station = info.isBreak && current != nil && current?.url == nil
        let next = info.next
        let tr = current?.trailer ?? (info.isBreak && trailerOn ? model.breakTrailerCandidate(ch, slot: info.slot) : nil)
        let fx: CGFloat = tv ? 240 : 0, fw: CGFloat = tv ? 1440 : 1920

        if ch.isScrambled {
            ScrambleView(seed: Int(info.slot.start.timeIntervalSince1970 / 60), audio: true).at(0, 0, w: 1920, h: 1080)
        } else {
        Picture(hue: ch.hue, art: model.server?.backdropURL(for: tr ?? item)).at(fx, 0, w: fw, h: 1080)
        // Held while a plain-http link is dropped (its return's pick pending): the old item would play on behind the clock.
        PlayerView(loaded: current, paused: (model.playback?.paused ?? false) || (model.server == nil && !model.pip)) { err, started, key, url in
            // Same key, url nil: no reload; the title card / station card returns and `sync` stops calling play().
            // Only for what is loaded now: a failure of the last item can land after `loaded` moved on, before `sync`. Not
            // while away or while a plain-http link is dropped (a stopped session failing): the way back reloads anyway.
            guard !model.backgrounded, model.server != nil, let l = loaded, l.url != nil, l.key == key, l.url == url else { return }
            // A program with a subtitle that never showed a first frame: once more without (the retry asks for .off, so it
            // can't come back here). Once the picture has started, or with no subtitle to blame, it's a plain failure.
            if !started, l.subtitleRisk, l.trailer == nil, !l.spot {
                loaded = Loaded(key: l.key, url: nil, startsAtOffset: false, offset: 0, trailer: nil, loop: false)
                model.dropSubtitles(itemID: l.key.slot.item.id)
                return
            }
            if l.spot { model.spotChanged(end: nil); handOff = Task { await load(l.key, skipSpot: true) }; return }   // the trailers take the break
            // A trailer that failed or never started: quietly the next one, or the station card. Never a toast for a trailer.
            if l.trailer != nil {
                loaded = Loaded(key: l.key, url: nil, startsAtOffset: false, offset: 0, trailer: nil, loop: false)
                handOff = Task { await load(l.key, skipSpot: true, tries: started ? l.tries.restarted() : l.tries) }
                return
            }
            let msg = "Couldn’t play this stream: \(err)"
            loaded = Loaded(key: l.key, url: nil, startsAtOffset: false, offset: 0, trailer: nil, loop: false, error: msg)
            model.toast(msg)
            // The player never says whether the server was unreachable: look for it anyway (at most once a minute), and
            // reload there if it moved, as `serverFailed` does for the app's own requests.
            Task { if await model.recoverPlexAddress() { model.tunes += 1; model.foregroundSerial += 1 } }
        } onEnd: {
            model.skipSpot()   // the spot is over (skippable by then): as a skip, the trailers (or the program) take the rest
        } onStatus: { status = $0 } onPiP: { model.pipChanged($0, paused: $1) }
        .at(0, 0, w: 1920, h: 1080)
        // The mockup's stand-in for the picture: only while no stream is attached, never over live video.
        if current?.url == nil && (!info.isBreak || trailerOn) {
            VStack(spacing: 14) {
                Text((info.isBreak ? "Trailer" : "Playing from \(model.server?.serverName ?? "")").uppercased())
                    .font(t.body(24, weight: .semibold)).tracking(em: 0.2, size: 24).foregroundStyle(.white.opacity(0.78))
                Text(info.isBreak ? "\(tr?.title ?? "") (\(tr?.year.map(String.init) ?? ""))"
                                  : item.displayTitle + (item.kind == .movie ? " (\(item.year.map(String.init) ?? ""))" : ": “\(item.title)”"))
                    .font(t.heading(56, weight: .semibold)).lineSpacing(4).foregroundStyle(.white).multilineTextAlignment(.center).frame(maxWidth: 1200)
                if let e = current?.error {
                    Text(e).font(t.body(28)).foregroundStyle(t.accent).multilineTextAlignment(.center).frame(maxWidth: 1200)
                }
            }
            .frame(width: fw - 160).position(x: fx + fw / 2, y: 540)
        }
        if station {
            VStack(spacing: 20) {
                Text("\(ch.number)").font(t.heading(220)).frame(height: 220).foregroundStyle(t.accent)   // CSS line-height: 1
                Text(t.heading(ch.name)).font(t.heading(80)).tracking(em: t.headingTracking, size: 80).frame(height: 80)
                // What just ended and what's next, so a channel between programs doesn't look broken.
                VStack(spacing: 8) {
                    if !model.portrait {   // portrait: the card is drawn small, so it keeps the number, name and what's next
                        Text(item.displayTitle + (item.kind == .movie ? "" : ": “\(item.title)”")
                             + (info.live ? " ended at \(Clock.time(info.slot.start.addingTimeInterval(item.runtime)))" : " just ended"))
                            .font(t.body(36)).multilineTextAlignment(.center).frame(maxWidth: 1400)
                    }
                    Text(next.map { "Next: \($0.item.displayTitle) at \(Clock.time($0.start))" } ?? "More after this").font(t.body(36)).foregroundStyle(t.mute).multilineTextAlignment(.center).frame(maxWidth: model.portrait ? 1150 : nil)
                }
                .padding(.top, 16)
                if !model.portrait {   // portrait: Now playing's buttons do these
                    HStack(spacing: 48) {
                        if let next {
                            KeyHint(symbol: "button.programmable", touch: "play.fill", label: "Watch \(next.item.displayTitle) now") { model.startNextFromBreak() }
                        }
                        KeyHint(symbol: "playpause.fill", touch: "backward.end.fill", label: "Watch \(item.displayTitle) again") { model.restartFromBreak() }
                    }
                    .padding(.top, 24)
                }
            }
            .scaleEffect(model.portrait ? 1.6 : 1)
            .foregroundStyle(t.ink).frame(maxWidth: .infinity, maxHeight: .infinity).background(t.bg)
        }
        if info.isBreak, current?.trailer != nil, let next {   // trailers only: the spot draws its own UI in that corner
            VStack(alignment: .trailing, spacing: 6) {
                Text("Coming attractions".uppercased()).font(t.body(24, weight: .bold)).tracking(em: 0.16, size: 24).foregroundStyle(t.accent)
                Text("Back at \(Clock.time(next.start)) with \(next.item.displayTitle)").font(t.body(30)).foregroundStyle(.white)
            }
            .multilineTextAlignment(model.portrait ? .trailing : .leading)
            .padding(.vertical, 18).padding(.horizontal, 26).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: t.radius))
            .scaleEffect(model.portrait ? 2 : 1, anchor: .topTrailing)   // portrait draws the stage a fifth of its size
            .padding(.leading, model.portrait ? 924 : 0)                 // so doubled, it still fits the picture
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(.trailing, 72).padding(.top, 64)
        }
        // Portrait: Now playing's status chip says Ad and its first button skips (App/Portrait/PortraitWatch).
        if current?.spot == true, !model.portrait, let started = SpotOnAir.shared.started { spotChips(started: started, now: now) }
        // A stream attached with no first frame yet (the title card covers the fetch before it), or one that stalled.
        // A reload of the program that was on (a skip, Start over, a track change) is buffering, not tuning in. Never while
        // paused (the Paused chip says it), nor under the station ident.
        let s = status.key == current?.key && status.url == current?.url ? status : PlayerStatus()
        let tuning = current?.url != nil && !s.started
        if ident == nil && model.playback?.paused != true && (tuning || s.stalled) {
            let same = status.key?.channelID == current?.key.channelID && status.key?.slot == current?.key.slot
            RerunLoader(width: 320, captions: [tuning && !same ? "Tuning in…" : "Buffering…"], delay: 1).position(x: 960, y: 400)   // clear of the banner
        }
        }
        if let r = model.rewind { RewindEffect(rewind: r).id(r.started) }
        if model.playback?.paused == true && !model.portrait {   // portrait: the status chip says Paused
            HStack(spacing: 22) {
                HStack(spacing: 8) { Rectangle().frame(width: 10, height: 36); Rectangle().frame(width: 10, height: 36) }
                Text("Paused")
            }
            .font(t.body(32, weight: .semibold)).foregroundStyle(.white)
            .padding(.vertical, 18).padding(.horizontal, 28).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: t.radius))
            .padding(.leading, 80).padding(.top, 72)
        }
        ZStack {
            if model.bannerVisible && !model.portrait {   // portrait: Now playing's panel under the picture
                Banner(d: BannerData(info: info, model: model, t: t, now: now, trailerOn: trailerOn))
                    .transition(.opacity.combined(with: .offset(y: 14)))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom).padding(.horizontal, 72).padding(.bottom, 56)
        .animation(.easeOut(duration: t.id == .midnight ? 0.2 : 0.25), value: model.bannerVisible)
        if let ident {
            StationIdentCard(number: ch.number, name: ch.name, tag: ch.tag, kicker: ident.kicker, sub: ident.sub)
                .transition(.asymmetric(insertion: UIAccessibility.isReduceMotionEnabled ? .opacity : .opacity.combined(with: .scale(scale: 0.96)), removal: .opacity))
        }
        if model.flips > 0 { StaticFlash().id(model.flips) }
    }

    /// The house spot is an ad (App Store 2.5.18): an "Ad" label top left (below a Paused chip), and a Skip chip bottom
    /// right (above the banner) counting down 5 s, then naming the press that skips it; on touch the chip is the button.
    @ViewBuilder private func spotChips(started: Date, now: Date) -> some View {
        let mm = t.id == .midnight, paused = model.playback?.paused == true
        HStack(spacing: 14) {
            Text("Ad".uppercased()).font(mm ? t.heading(24) : t.body(24, weight: .bold)).tracking(em: 0.1, size: 24).foregroundStyle(t.accentInk)
                .padding(.top, 3).padding(.bottom, 2).padding(.horizontal, 12).background(t.accent, in: RoundedRectangle(cornerRadius: mm ? 0 : 8))
            Text("Message from Soyboi")
        }
        .font(t.body(32, weight: .semibold)).foregroundStyle(.white)
        .padding(.vertical, 18).padding(.horizontal, 28).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: t.radius))
        .padding(.leading, 80).padding(.top, paused ? 160 : 72)
        let skippable = SpotOnAir.shared.skippable
        let left = min(Int(AppModel.spotSkipDelay), max(1, Int((AppModel.spotSkipDelay - now.timeIntervalSince(started)).rounded(.up))))
        let label: Text = !skippable ? Text("Skip in \(left)")
            : Platform.isTV ? Text("Press ") + Text(Image(systemName: "button.programmable")) + Text(" to skip  ›") : Text("Skip  ›")
        label.font(t.body(32, weight: .semibold)).foregroundStyle(.white.opacity(skippable ? 1 : 0.72))
            .padding(.vertical, 18).padding(.horizontal, 28).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: t.radius))
            .onTouch { model.skipSpot() }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .padding(.trailing, 72).padding(.bottom, model.bannerVisible ? 480 : 64)
    }

    private func showIdent(_ kicker: String, sub: String, _ sting: StationSting.Kind) {
        guard model.settings.stationIdents else { return }
        withAnimation(.easeOut(duration: 0.25)) { ident = Ident(kicker: kicker, sub: sub, n: model.identSerial) }
        StationSting.shared.play(sting)
    }

    /// Fetch the stream for `key`: the program at the clock's offset, or for a break the first trailer the server has
    /// (deterministic candidate, then up to 3 other movies on the channel); none → station card (url nil). A trailer
    /// that fails carries on here with its `tries`: the ones already tried are skipped, and the budget ends them.
    private func load(_ key: LoadKey?, skipSpot: Bool = false, tries: TrailerTries = TrailerTries()) async {
        guard let key, let ch = model.channel(key.channelID) else { return }
        var result = Loaded(key: key, url: nil, startsAtOffset: false, offset: 0, trailer: nil, loop: false)
        if ch.isScrambled { model.playbackTracks = []; loaded = result; return }   // channel 99 draws itself
        guard let server = model.server else {   // dropped: no address of a plain-http link checked out on this network
            result.error = "Could not connect to the server."
            loaded = result
            return
        }
        if !skipSpot, key.isBreak || model.spotOverride, let spot = model.dueSpot(ch, slot: key.slot) {
            model.playbackTracks = []
            loaded = Loaded(key: key, url: spot, startsAtOffset: false, offset: 0, trailer: nil, loop: false, spot: true)
            return
        }
        if key.isBreak {
            model.playbackTracks = []
            if model.settings.trailerBreaks && ch.trailerBreaks {
                let first = model.breakTrailerCandidate(ch, slot: key.slot)
                let others = ch.items.filter { $0.kind == .movie && $0.id != key.slot.item.id && $0.id != first?.id }.prefix(3)
                let candidates = [first].compactMap { $0 } + others
                var tries = tries
                while let m = tries.next(candidates) {
                    tries = tries.adding(m)
                    do {
                        if let src = try await server.trailer(for: m, session: "watch") { result = Loaded(key: key, url: src.url, startsAtOffset: false, offset: 0, trailer: m, loop: true, tries: tries); break }
                    } catch { _ = model.serverFailed(error) }   // a sign-out or a server to look for, then the others: no toast for a trailer
                    if Task.isCancelled || model.server == nil { return }
                }
            }
        } else {
            let offset = max(0, min(model.watchInfo()?.position ?? 0, key.slot.item.runtime))
            // Another item: the banner's Audio/Subtitles must not build its pick from the last one's stream indices meanwhile.
            if loaded?.key.slot.item.id != key.slot.item.id { model.playbackTracks = [] }
            let tracks = (try? await server.tracks(for: key.slot.item)) ?? []
            // Away meanwhile, or the link dropped: the next request would carry the token, maybe to an unchecked address.
            guard !Task.isCancelled, !model.backgrounded, !model.pip, model.server != nil else { return }
            model.playbackTracks = tracks
            let sel = model.trackSelection(for: tracks, itemID: key.slot.item.id)
            do {
                let src = try await server.playbackSource(for: key.slot.item, startingAt: offset, session: "watch", tracks: sel)
                // The demo's fixed sample is shorter than any slot, so it loops instead of seeking.
                result = Loaded(key: key, url: src.url, startsAtOffset: src.startsAtOffset, offset: offset, trailer: nil, loop: server.kind == .demo,
                                subtitleInPlaylist: src.subtitleInPlaylist, tracks: sel,
                                subtitleRisk: { if case .track = sel.subtitle { true } else { false } }(),
                                subtitle: { if case .track(let id) = sel.subtitle { tracks.first { $0.id == id } } else { nil } }())
            } catch { result.error = model.serverFailed(error) }
        }
        // Away meanwhile (Home, picture in picture), or a plain-http link dropped: the way back reloads. Attached now, it
        // would start a transcode that nothing plays, and a never-started subtitle load would take the drop path below.
        guard !Task.isCancelled, !model.backgrounded, !model.pip, model.server != nil else { return }
        if skipSpot, loaded?.key != key { return }   // the hand-off finished after the viewer had already moved on
        loaded = result
        if let e = result.error, model.server != nil { model.toast(e) }   // a 401 already toasted on its way to Welcome
    }
}

private struct LoadKey: Equatable { let channelID: String; let slot: Slot; let tune: Int; let isBreak: Bool }
private struct PlayerStatus: Equatable { var key: LoadKey?; var url: URL?; var started = false; var stalled = false }
private struct Ident: Equatable { let kicker: String, sub: String; let n: Int }
private struct Loaded: Equatable {
    let key: LoadKey; let url: URL?; let startsAtOffset: Bool; let offset: TimeInterval; let trailer: MediaItem?; let loop: Bool
    /// What was asked of the server, so the player can finish the subtitle choice (see `PlayerUIView.selectLegible`).
    var subtitleInPlaylist = false; var tracks = TrackSelection()
    /// A subtitle track was picked: the one thing a failure before the first frame is blamed on. Not Plex's .auto: the
    /// fallback's .off would make PlexClient change the account-wide subtitle choice.
    var subtitleRisk = false
    var subtitle: MediaTrack? = nil
    var error: String? = nil
    /// The bundled Soyboi spot: plays once, then `onEnd` loads what the key really calls for.
    var spot = false
    /// In a break, the trailers tried up to this one: its failure carries on from them.
    var tries = TrailerTries()
}

// MARK: - Player

/// One AVQueuePlayer behind an AVPlayerLayer. Trailers and the demo sample loop (AVPlayerLooper); programs seek to the
/// offset unless the server already started the stream there.
private struct PlayerView: UIViewRepresentable {
    let loaded: Loaded?
    let paused: Bool
    /// A stream the player could not play (item status .failed / failed to play to end, or no first frame in time: see
    /// `watchdog`), with the error's text, whether its first frame had shown, and the key and url it was loaded for.
    let onFail: (String, Bool, LoadKey?, URL?) -> Void
    /// The spot reached its end.
    var onEnd: () -> Void = {}
    /// The first frame showed, or the stream stalled or got going again.
    var onStatus: (PlayerStatus) -> Void = { _ in }
    /// Picture in picture started (true) or stopped (iPhone, iPad), and whether it left the player paused.
    var onPiP: (Bool, Bool) -> Void = { _, _ in }
    func makeUIView(context: Context) -> PlayerUIView { PlayerUIView() }
    func updateUIView(_ v: PlayerUIView, context: Context) { v.onFail = onFail; v.onEnd = onEnd; v.onStatus = onStatus; v.onPiP = onPiP; v.sync(loaded, paused: paused) }
    static func dismantleUIView(_ v: PlayerUIView, coordinator: ()) { v.stop() }
}

private final class PlayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    private var player: AVQueuePlayer? = AVQueuePlayer()
    private var looper: AVPlayerLooper?
    private var key: LoadKey?, url: URL?
    /// The spot's item while it plays; its end (and only its end) calls `onEnd`.
    private var spotItem: AVPlayerItem?
    /// No first frame by then counts as a failure: 20 s after a program with a subtitle risk loads (a burn-in that never
    /// starts), `TrailerTries.startTimeout` after a trailer does (the next one takes over).
    private var watchdog: Task<Void, Never>?
    /// The current item has shown its first frame (AVPlayerLayer.isReadyForDisplay since it was attached).
    private var started = false
    /// It has played since: only then is waiting a stall (a slow tune's first frame can show before playback starts).
    private var played = false
    /// The subtitle choice to apply once the first frame shows (see `selectLegible`).
    private var pendingLegible: Loaded?
    /// The items this load put in the queue (the looper's copies too). AVQueuePlayer drops a failed item at once, so by the
    /// time a failure reaches main `currentItem` is already nil: failures are matched against these instead.
    private var items: [AVPlayerItem] = []
    /// This load's failure was reported (once per load: the status KVO and the notification can both see it).
    private var reported = false
    /// The looper's own status: a template that never loads fails the looper without queueing any item to observe.
    private var looperStatus: AnyCancellable?
    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    /// The items to match a failure against: the looper's copies exist only once it is ready (new objects), so read them live.
    private var loadItems: [AVPlayerItem] { looper?.loopingPlayerItems ?? items }
    var onFail: ((String, Bool, LoadKey?, URL?) -> Void)?
    var onEnd: (() -> Void)?
    var onStatus: ((PlayerStatus) -> Void)?
    var onPiP: ((Bool, Bool) -> Void)?
    #if os(iOS) && !targetEnvironment(macCatalyst)
    private var pip: AVPictureInPictureController?
    #endif
    /// From picture in picture's will start to its did stop: the window's own play/pause rules the player meanwhile.
    private var inPiP = false
    private var observers = Set<AnyCancellable>()
    private func report() {
        onStatus?(PlayerStatus(key: key, url: url, started: started, stalled: played && player?.timeControlStatus == .waitingToPlayAtSpecifiedRate))
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false   // taps on the picture reach WatchScreen (the banner), never this layer
        playerLayer.player = player
        playerLayer.videoGravity = .resizeAspect
        #if os(iOS) && !targetEnvironment(macCatalyst)
        // Home while a stream plays carries it on in a picture-in-picture window (live TV: no skipping in there), and coming
        // back to the app by any way brings it back full screen.
        if AVPictureInPictureController.isPictureInPictureSupported(), let c = AVPictureInPictureController(playerLayer: playerLayer) {
            c.canStartPictureInPictureAutomaticallyFromInline = true; c.requiresLinearPlayback = true; c.delegate = self
            pip = c
            NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
                .sink { [weak self] _ in self?.pip?.stopPictureInPicture() }
                .store(in: &observers)
        }
        #endif
        // One KVO on the player covers every item, the looper's copies included; delivered on main like the guide preview's.
        player?.publisher(for: \.currentItem?.status).receive(on: DispatchQueue.main)
            .sink { [weak self] s in   // this load's items only: a late .failed of the last load finds nothing here
                guard s == .failed, let self, !reported, let item = loadItems.first(where: { $0.status == .failed }) else { return }
                reported = true
                onFail?(item.error?.localizedDescription ?? "unknown error", started, key, url)
            }
            .store(in: &observers)
        // App-wide too (the guide preview's items post it), so only this load's items.
        NotificationCenter.default.publisher(for: AVPlayerItem.failedToPlayToEndTimeNotification).receive(on: DispatchQueue.main)
            .sink { [weak self] n in
                guard let self, !reported, let obj = n.object as AnyObject?, loadItems.contains(where: { $0 === obj }) else { return }
                reported = true
                onFail?((n.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?.localizedDescription ?? "playback stopped", started, key, url)
            }
            .store(in: &observers)
        // Re-read live: a `true` queued for the last item must not count for the next one (`sync` detaches the layer).
        playerLayer.publisher(for: \.isReadyForDisplay).receive(on: DispatchQueue.main)
            .sink { [weak self] ready in
                guard ready, let self, url != nil, !started, playerLayer.isReadyForDisplay, player?.currentItem != nil else { return }
                started = true; watchdog?.cancel(); watchdog = nil
                if let l = pendingLegible, let item = player?.currentItem { pendingLegible = nil; selectLegible(item, l) }   // a looper's copy too
                report()
            }
            .store(in: &observers)
        player?.publisher(for: \.timeControlStatus).receive(on: DispatchQueue.main)
            .sink { [weak self] _ in   // a stall, or playing again
                guard let self else { return }
                if player?.timeControlStatus == .playing { played = true }   // a direct-play file often plays before its first frame shows
                if started { report() }
            }
            .store(in: &observers)
        // The notification is app-wide (the guide preview posts it too), so match the spot's own item.
        NotificationCenter.default.publisher(for: AVPlayerItem.didPlayToEndTimeNotification).receive(on: DispatchQueue.main)
            .sink { [weak self] n in
                guard let self, let s = spotItem, (n.object as AnyObject?) === s else { return }
                spotItem = nil; onEnd?()
            }
            .store(in: &observers)
    }
    required init?(coder: NSCoder) { fatalError() }

    func sync(_ l: Loaded?, paused: Bool) {
        guard let player else { return }
        if l?.key != key || l?.url != url {   // same key, new url: the spot handing over, or a failed stream cleared
            key = l?.key; url = l?.url; spotItem = nil; watchdog?.cancel(); watchdog = nil; started = false; played = false; pendingLegible = nil
            items = []; reported = false; looperStatus = nil
            looper?.disableLooping(); looper = nil; player.pause(); player.removeAllItems()
            playerLayer.player = nil; playerLayer.player = player   // isReadyForDisplay back to false for the next item
            if let l, let url = l.url {
                let item = AVPlayerItem(url: url)
                pendingLegible = l
                if l.loop {
                    let lp = AVPlayerLooper(player: player, templateItem: item)
                    looper = lp
                    looperStatus = lp.publisher(for: \.status).receive(on: DispatchQueue.main).sink { [weak self, weak lp] s in
                        guard s == .failed, let self, !reported, let lp, lp === looper else { return }
                        reported = true
                        onFail?(lp.error?.localizedDescription ?? "unknown error", started, key, self.url)
                    }
                }
                else {
                    player.insert(item, after: nil); items = [item]
                    if l.spot { spotItem = item }
                    if !l.startsAtOffset, l.offset > 0 { player.seek(to: CMTime(seconds: l.offset, preferredTimescale: 600)) }
                }
                if l.trailer != nil || (!l.loop && !l.spot && l.subtitleRisk) {
                    let k = l.key, wait = l.trailer != nil ? TrailerTries.startTimeout : 20
                    watchdog = Task { [weak self] in   // already awake when `sync` cancels it: the key says whose it was
                        guard (try? await Task.sleep(for: .seconds(wait))) != nil, let self, !started, key == k else { return }
                        onFail?("The stream didn’t start.", false, key, self.url)
                    }
                }
            }
        }
        // Black letterbox/pillarbox bars once a stream is attached; the Picture backdrop shows through until then.
        playerLayer.backgroundColor = l?.url != nil ? UIColor.black.cgColor : nil
        guard l?.url != nil else { return }
        // Never in picture in picture or from the background: the window's own pause, and its close, must hold (a play() as
        // the window goes keeps the sound on with no picture). The window shows one item: it closes when the player empties for the next.
        if paused { player.pause() } else if player.timeControlStatus == .paused, !inPiP, UIApplication.shared.applicationState != .background { player.play() }
        // ponytail: no drift correction after a stall; the picture may lag the banner's clock. Add a periodic-time-observer resync if it shows.
    }

    /// Jellyfin/Emby carry a chosen subtitle as an HLS text rendition (Emby marks none DEFAULT, so AVPlayer shows nothing
    /// unless told): pick it by its name (the server's DisplayTitle), else by its language (as forced as the pick first),
    /// else none. "Off" or a burned-in pick clears whatever AVPlayer picked on its own; Auto leaves AVPlayer's default alone.
    /// Runs only once the first frame shows: AVPlayer holds the picture for a selected forced rendition, and Emby holds a
    /// new session's first subtitle segment 4–12 s (30–88 s cold); selected after it, the picture starts in under a second.
    private func selectLegible(_ item: AVPlayerItem, _ l: Loaded) {
        guard l.tracks.subtitle != .auto else { return }
        Task { @MainActor in
            guard let g = try? await item.asset.loadMediaSelectionGroup(for: .legible) else { return }
            guard l.subtitleInPlaylist, let want = l.subtitle else { item.select(nil, in: g); return }
            let lang = g.options.filter { o in
                let c = o.locale?.language.languageCode
                return [o.extendedLanguageTag, c?.identifier, c?.identifier(.alpha3)].contains { MediaTrack.sameLanguage($0, want.language) }
            }
            let pick = g.options.first { $0.displayName == want.title }
                ?? lang.first { $0.hasMediaCharacteristic(.containsOnlyForcedSubtitles) == want.isForced } ?? lang.first
            item.select(pick, in: g)
        }
    }

    /// Picture in picture ends here too: a window still up (a sign-out while away) may close with this view unannounced.
    func stop() { observers = []; looperStatus = nil; watchdog?.cancel(); watchdog = nil; pendingLegible = nil; items = []; reported = true; looper?.disableLooping(); looper = nil; player?.pause(); player?.removeAllItems(); player = nil; playerLayer.player = nil; onPiP?(false, false) }
}

#if os(iOS) && !targetEnvironment(macCatalyst)
extension PlayerUIView: @preconcurrency AVPictureInPictureControllerDelegate {
    // Will start, the earliest word: `enteredBackground` must see it when the scene reaches the background (did start lands
    // only about 0.2 s before that).
    func pictureInPictureControllerWillStartPictureInPicture(_: AVPictureInPictureController) { inPiP = true; onPiP?(true, false) }
    // Paused: the window's own pause (`sync` left the player alone until now), which the model never heard about.
    func pictureInPictureControllerDidStopPictureInPicture(_: AVPictureInPictureController) { inPiP = false; onPiP?(false, player?.timeControlStatus == .paused) }
    func pictureInPictureController(_: AVPictureInPictureController, failedToStartPictureInPictureWithError _: Error) { inPiP = false; onPiP?(false, false) }
}
#endif

// MARK: - Picture backdrop

/// Hue gradient (the prototype's `wv.bg`) with the item's backdrop on top while the stream loads or is absent.
private struct Picture: View {
    let hue: Double
    let art: URL?
    var body: some View {
        ZStack {
            RadialGradient(stops: [.init(color: .channel(hue: hue, lightness: 0.42, chroma: 0.09), location: 0),
                                   .init(color: .channel(hue: hue, lightness: 0.2, chroma: 0.05), location: 0.55),
                                   .init(color: .channel(hue: hue, lightness: 0.1, chroma: 0.02), location: 1)],
                           center: UnitPoint(x: 0.3, y: 0.25), startRadius: 0, endRadius: 1700)
            AsyncImage(url: art) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
            RadialGradient(stops: [.init(color: .clear, location: 0.45), .init(color: .black.opacity(0.5), location: 1)],
                           center: .center, startRadius: 0, endRadius: 1100)
        }
        .clipped()
    }
}

// MARK: - Static

/// ~0.5 s of noise fading out on channel change (the prototype's rbStatic), re-created per `flips`.
struct StaticFlash: View {
    @State private var opacity = 1.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// One 220×220 grey noise tile (the prototype's feTurbulence tile, grayscale, contrast 1.6), built once.
    fileprivate static let tile: Image = {
        let n = 220, bytes = Data((0..<n * n).map { _ in UInt8.random(in: 13...191) })
        let cg = CGImage(width: n, height: n, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: n,
                         space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0),
                         provider: CGDataProvider(data: bytes as CFData)!, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        return Image(decorative: cg, scale: 1).interpolation(.none)
    }()
    var body: some View {
        Group {
            if reduceMotion { Color.black }   // Reduce Motion: a 0.2 s crossfade instead of the noise (iOS handoff, Motion)
            else { Canvas { g, size in g.fill(Path(CGRect(origin: .zero, size: size)), with: .tiledImage(Self.tile)) } }
        }
        .opacity(opacity)
        .allowsHitTesting(false)
        // ponytail: one linear fade instead of the prototype's 5 stepped grain shifts; add a phaseAnimator if the steps matter.
        .onAppear { withAnimation(.linear(duration: reduceMotion ? 0.2 : 0.5)) { opacity = 0 } }
    }
}

// MARK: - Rewind

/// Start over on the program on screen, as a VCR rewinding its tape: two bands of static rolling up the picture (none
/// with Reduce Motion), and the OSD's ◀◀ REW over a counter running back to 0:00:00 (eased, 1.1 s), then a fade.
private struct RewindEffect: View {
    let rewind: AppModel.Rewind
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation) { ctx in
            let t = ctx.date.timeIntervalSince(rewind.started)
            let left = 1 - pow(1 - min(1, t / 1.1), 3)   // ease-out
            let s = Int((rewind.from * (1 - left)).rounded())
            ZStack(alignment: .topTrailing) {
                if !reduceMotion {
                    Canvas { g, size in
                        for k in 0..<2 {   // 90 tall, up the screen every 0.7 s, shaking sideways
                            let y = (size.height + 90) * (1 - (t / 0.7 + Double(k) * 0.55).truncatingRemainder(dividingBy: 1)) - 90
                            let shade = GraphicsContext.Shading.tiledImage(StaticFlash.tile, origin: CGPoint(x: .random(in: 0..<220), y: .random(in: 0..<220)))
                            g.fill(Path(CGRect(x: .random(in: -60...0), y: y, width: size.width + 60, height: 90)), with: shade)
                        }
                    }
                    .opacity(0.85)
                }
                VStack(alignment: .trailing, spacing: 6) {
                    HStack(spacing: 16) { Image(systemName: "backward.fill"); Text("REW") }
                    Text(String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60))
                }
                .font(.system(size: 56, weight: .bold, design: .monospaced)).foregroundStyle(.white)
                .shadow(color: .black.opacity(0.7), radius: 0, x: 3, y: 3)
                .padding(.trailing, 96).padding(.top, 72)
            }
            .frame(width: 1920, height: 1080, alignment: .topTrailing)
            .opacity(max(0, min(1, (AppModel.rewindLength - t) / 0.25)))
        }
        .allowsHitTesting(false)
    }
}

// MARK: - Banner

/// Port of vWatch: every string/color the banner shows.
@MainActor private struct BannerData {
    let number: Int, name: String, tag: String, title: String, sub: String
    let startT: String, endT: String, timeRange: String, progress: CGFloat
    let status: String, statusColor: Color
    /// Midnight Movie's status badge (LIVE / BREAK / START OVER / PAUSED) and its color.
    let badge: String, badgeBg: Color
    let nextTime: String, nextTitle: String
    let actions: [String], focus: Int, skipCue: Int
    /// The progress bar has the remote's focus (no button does then).
    let bar: Bool
    /// The open Audio or Subtitles list: the button it's over, its rows, the one on and the one focused.
    struct Menu { let anchor: Int, rows: [String], on: Int, focus: Int }
    let menu: Menu?
    /// The lineup strip: this slot plus the next three, "9:30 PM" then "10:00" until the meridiem changes.
    let tonight: [(time: String, title: String, isNow: Bool)]

    init(info: WatchInfo, model: AppModel, t: Theme, now: Date, trailerOn: Bool) {
        let ch = info.channel, s = info.slot, item = s.item, pos = info.position, paused = model.playback?.paused == true
        let behind = !info.live && now >= s.start && now < s.end ? now.timeIntervalSince(s.start) - pos : 0
        let minsIn = Int(pos / 60)
        number = ch.number; name = ch.name; tag = ch.tag; title = item.displayTitle
        sub = item.kind == .movie ? [item.year.map(String.init), item.contentRating].compactMap { $0 }.joined(separator: " · ")
                                  : (["“\(item.title)”", item.seasonEpisode.isEmpty ? nil : item.seasonEpisode].compactMap { $0 }).joined(separator: " · ")
        startT = Clock.time(s.start); endT = Clock.time(s.end); timeRange = Clock.range(s.start, s.end)
        progress = max(0, min(1, pos / s.duration))
        badge = paused ? "PAUSED" : info.live ? (info.isBreak ? "BREAK" : "LIVE") : "START OVER"
        badgeBg = paused ? t.dim : info.live ? (info.isBreak ? t.breakBadge : t.accent) : t.startOverBadge
        status = paused ? "Paused"
            : info.live ? (info.isBreak ? "\(trailerOn ? "Trailers" : "Station break") · back at \(Clock.time(s.end))" : "Live · \(minsIn) min in")
            : behind >= 60 ? "\(Int((behind / 60).rounded())) min behind live" : behind >= 3 ? "\(Int(behind.rounded())) s behind live" : "From the beginning · \(minsIn) min in"
        statusColor = !paused && info.live && !info.isBreak ? t.accent : t.mute
        let next = info.next
        nextTitle = next?.item.displayTitle ?? "More after this"
        nextTime = next.map { "\(Clock.time($0.start)) \(Clock.ap($0.start))" } ?? ""
        actions = model.watchActions().map(\.label); focus = model.bannerFocus; skipCue = model.skipCue
        bar = model.barFocused && model.bannerFocus >= 0
        menu = model.trackMenu.flatMap { m in
            let c = model.trackChoices(m.kind)
            return c.isEmpty ? nil : Menu(anchor: model.bannerFocus, rows: c.map(\.label), on: c.firstIndex(where: \.on) ?? -1, focus: min(m.focus, c.count - 1))
        }
        var strip: [(time: String, title: String, isNow: Bool)] = [], prevAP = ""
        for x in [s] + model.slots(ch, from: s.start, to: s.start + 4 * 3600).filter({ $0.start >= s.end }).prefix(3) {
            let ap = Clock.ap(x.start)
            strip.append((Clock.time(x.start) + (ap == prevAP ? "" : " " + ap), x.item.displayTitle, x.start == s.start))
            prevAP = ap
        }
        tonight = strip
    }
}

private struct Banner: View {
    let d: BannerData
    @Environment(\.theme) private var t
    @Environment(AppModel.self) private var model

    /// " · Press ⓢ for options" while the banner shows only the program; the remote glyph is tvOS only.
    private var optionsHint: Text {
        guard d.focus < 0 else { return Text("") }
        return Platform.isTV ? Text(" · Press ") + Text(Image(systemName: "button.programmable")) + Text(" for options")
                             : Text(" · \(Platform.verb) for options")
    }

    /// The remote's way to run a banner button: focus it, then Select (not the number box's OK).
    private func run(_ i: Int) { model.closeEntry(); model.bannerFocus = i; model.barFocused = false; model.press(.select) }

    /// The open track list over button `i` (TrackList lifts itself above the button).
    @ViewBuilder private func trackList(over i: Int) -> some View {
        if let m = d.menu, m.anchor == i { TrackList(menu: m) }
    }
    var body: some View {
        if t.id == .midnight { midnight } else { late }
    }

    private var late: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack(alignment: .bottom, spacing: 48) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 14) {
                        Text("\(d.number)").font(t.body(28, weight: .bold)).foregroundStyle(t.accentInk).padding(.vertical, 3).padding(.horizontal, 16).background(t.accent, in: Capsule())
                        Text(d.name).font(t.body(28, weight: .semibold)).foregroundStyle(t.ink)
                        Text("· \(d.tag)").font(t.body(28))
                    }
                    .foregroundStyle(t.mute)
                    Text(d.title).font(t.heading(72, weight: .bold)).tracking(em: -0.025, size: 72).lineLimit(1).foregroundStyle(t.ink)
                    Text(d.sub).font(t.body(32)).foregroundStyle(t.mute)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .trailing, spacing: 8) {
                    Text("Up next · \(d.nextTime)".uppercased()).font(t.body(24, weight: .semibold)).tracking(em: 0.14, size: 24).foregroundStyle(t.dim)
                    Text(d.nextTitle).font(t.body(34, weight: .semibold)).lineLimit(1).foregroundStyle(t.ink)
                }
                .frame(width: 460, alignment: .trailing)
            }
            HStack(spacing: 24) {
                Text(d.startT)
                Bar(progress: d.progress, height: 8, radius: 4, track: t.line, fill: t.accent, cue: d.skipCue, focused: d.bar)
                Text(d.endT)
                (Text(d.status).foregroundStyle(d.statusColor) + optionsHint.font(t.body(24)).foregroundStyle(t.dim))
                    .frame(minWidth: 340, alignment: .trailing)
            }
            .font(t.body(26, weight: .semibold)).foregroundStyle(t.ink)
            HStack(spacing: 16) {
                if d.focus >= 0 {
                    ForEach(Array(d.actions.enumerated()), id: \.offset) { i, label in
                        let f = i == d.focus && !d.bar
                        Text(label).font(t.body(28, weight: .semibold)).foregroundStyle(f ? t.focusInk : t.ink)
                            .padding(.vertical, 14).padding(.horizontal, 30).background(f ? t.focusBg : t.panel2, in: Capsule())
                            .onTouch { run(i) }
                            .scaleEffect(f ? 1.05 : 1).animation(.easeOut(duration: 0.15), value: f)
                            .overlay(alignment: .topLeading) { trackList(over: i) }
                    }
                } else {
                    Text("Tonight on \(d.name)".uppercased()).font(t.body(22, weight: .semibold)).tracking(em: 0.14, size: 22).foregroundStyle(t.dim).fixedSize()
                    ForEach(Array(d.tonight.enumerated()), id: \.offset) { _, x in
                        HStack(spacing: 10) {
                            if x.isNow { Circle().fill(t.accent).frame(width: 8, height: 8) }
                            Text(x.time).font(t.body(24, weight: .semibold)).foregroundStyle(t.accent).fixedSize()
                            Text(x.title).font(t.body(26)).foregroundStyle(t.ink).lineLimit(1).truncationMode(.tail)
                        }
                        .padding(.vertical, 10).padding(.horizontal, x.isNow ? 20 : 0)
                        .background(x.isNow ? t.panel2 : .clear, in: Capsule())
                    }
                }
            }
            .frame(minHeight: 62)
        }
        .padding(.top, 36).padding(.horizontal, 44).padding(.bottom, 32)
        .background(t.bg.opacity(0.92), in: RoundedRectangle(cornerRadius: 32))
        .shadow(color: .black.opacity(0.5), radius: 40, y: 30)
    }

    /// The prototype's bCable block: a 280px channel-number block beside a bevelled info box, everything square and in caps.
    private var midnight: some View {
        HStack(alignment: .top, spacing: 6) {
            VStack(spacing: 8) {
                Text("\(d.number)").font(t.heading(132)).frame(height: 132 * 0.9)   // CSS line-height: .9
                Text(t.heading(d.name)).font(t.heading(30)).tracking(em: 0.06, size: 30).multilineTextAlignment(.center).lineLimit(2)
                    .padding(.vertical, t.lineHeightTrim(30))
            }
            .foregroundStyle(t.accentInk)
            .padding(.vertical, 20).padding(.horizontal, 14).frame(maxWidth: .infinity, maxHeight: .infinity)
            .bevelled(t.accent, light: t.numberBevelLight, dark: t.numberBevelDark).frame(width: 280)
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .firstTextBaseline, spacing: 32) {
                    Text(t.heading(d.title)).font(t.heading(68)).lineLimit(1).frame(height: 68).frame(maxWidth: .infinity, alignment: .leading)
                    Text(d.timeRange).font(t.body(32, weight: .bold)).foregroundStyle(t.accent).fixedSize()
                }
                HStack(spacing: 22) {
                    Text(d.sub).foregroundStyle(t.mute)
                    Text(d.badge).font(t.heading(24)).tracking(em: 0.1, size: 24).foregroundStyle(t.accentInk)
                        .padding(.top, 3).padding(.bottom, 2).padding(.horizontal, 12).background(d.badgeBg)
                    (Text(d.status) + optionsHint.font(t.body(24, weight: .semibold))).foregroundStyle(t.dim)
                }
                .font(t.body(30, weight: .semibold))
                Bar(progress: d.progress, height: 12, radius: 0, track: t.panel2, fill: t.accent, cue: d.skipCue, focused: d.bar)
                HStack(spacing: 24) {
                    if d.focus >= 0 {
                        // The chips take the whole row: five of them at this size leave no room for the Next line.
                        HStack(spacing: 8) {
                            ForEach(Array(d.actions.enumerated()), id: \.offset) { i, label in
                                let f = i == d.focus && !d.bar
                                Text(label.uppercased()).font(t.heading(26)).tracking(em: 0.04, size: 26).foregroundStyle(f ? t.focusInk : t.ink).lineLimit(1)
                                    .padding(.vertical, 8).padding(.horizontal, 18).background(f ? t.focusBg : t.panel2)
                                    .onTouch { run(i) }
                                    .overlay(alignment: .topLeading) { trackList(over: i) }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    } else if d.tonight.isEmpty {
                        // The prototype's Next line; the "Tonight on" strip says the same and more, so it takes the row when it has entries.
                        (Text("Next\(d.nextTime.isEmpty ? "" : " " + d.nextTime): ".uppercased()) + Text(d.nextTitle.uppercased()).foregroundStyle(t.ink))
                            .font(t.heading(28, weight: .bold)).tracking(em: 0.04, size: 28).foregroundStyle(t.dim).lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        HStack(spacing: 28) {
                            Text("Tonight on \(d.name)".uppercased()).foregroundStyle(t.dim).fixedSize()
                            ForEach(Array(d.tonight.enumerated()), id: \.offset) { _, x in
                                HStack(spacing: 10) {
                                    Text(x.time).foregroundStyle(t.accent).fixedSize()
                                    Text(x.title.uppercased()).foregroundStyle(t.ink).lineLimit(1)
                                }
                                .padding(.vertical, 6).padding(.horizontal, x.isNow ? 14 : 0).background(x.isNow ? t.panel2 : .clear)
                            }
                        }
                        .font(t.body(24, weight: .semibold)).tracking(em: 0.04, size: 24)
                    }
                }
                .frame(minHeight: 52)
            }
            .padding(.vertical, 24).padding(.horizontal, 36)
            .bevelled(t.bg, light: t.bevelLight, dark: t.bevelDark)
        }
        .fixedSize(horizontal: false, vertical: true)
        .foregroundStyle(t.ink)
    }
}

/// The banner's Audio or Subtitles list: a check by the choice that's on, the remote's focus, seven rows at most (the list
/// slides to keep the focus on the middle row, with a scroll bar when rows are out of view). It sits with its bottom 16
/// above its button's top. A tap plays a row, as the remote's Select does.
private struct TrackList: View {
    let menu: BannerData.Menu
    @Environment(\.theme) private var t
    @Environment(AppModel.self) private var model
    /// An odd count, so the middle row is whole and so are the rows around it.
    private static let rowH: CGFloat = 64, shown = 7, width: CGFloat = 560

    var body: some View {
        let n = menu.rows.count, h = CGFloat(min(n, Self.shown)) * Self.rowH, travel = CGFloat(n) * Self.rowH - h
        let ty = max(0, min(travel, CGFloat(menu.focus) * Self.rowH - (h - Self.rowH) / 2))
        let thumb = h * h / (CGFloat(n) * Self.rowH)
        VStack(spacing: 0) {
            ForEach(Array(menu.rows.enumerated()), id: \.offset) { i, label in
                let f = i == menu.focus
                HStack(spacing: 14) {
                    Image(systemName: "checkmark").font(.system(size: 22, weight: .bold)).opacity(i == menu.on ? 1 : 0)
                    Text(label).font(t.body(28, weight: .semibold)).lineLimit(1)
                }
                .foregroundStyle(f ? t.focusInk : t.ink)
                .padding(.horizontal, 22).frame(maxWidth: .infinity, minHeight: Self.rowH, maxHeight: Self.rowH, alignment: .leading)
                .background(f ? t.focusBg : .clear, in: RoundedRectangle(cornerRadius: t.radius == 0 ? 0 : 14))
                .onTouch { model.trackMenu?.focus = i; model.press(.select) }
            }
        }
        .frame(width: Self.width, height: CGFloat(n) * Self.rowH, alignment: .top)
        .offset(y: -ty)
        .frame(width: Self.width, height: h, alignment: .top).clipped().contentShape(Rectangle())   // rows out of view take no taps
        .padding(10)
        .overlay(alignment: .topTrailing) {   // the scroll bar, in the right padding
            if travel > 0 {
                Capsule().fill(t.mute).frame(width: 4, height: thumb).offset(x: -3, y: 10 + (h - thumb) * ty / travel)
            }
        }
        .animation(.easeInOut(duration: 0.22), value: ty)
        .background(t.panel, in: RoundedRectangle(cornerRadius: t.radius == 0 ? 0 : 24))
        .shadow(color: .black.opacity(0.5), radius: 30, y: 16)
        .offset(y: -(h + 20 + 16))   // the button's overlay puts this top on its top: lift it clear, with a 16 gap
    }
}

/// The progress bar, with a ⏪ / ⏩ tag over the playhead while `cue` is −1 / +1 (a skip back or ahead), and a 24-pt
/// knob on the playhead while it has the remote's focus.
private struct Bar: View {
    let progress: CGFloat, height: CGFloat, radius: CGFloat, track: Color, fill: Color
    var cue = 0, focused = false
    @Environment(\.theme) private var t
    var body: some View {
        GeometryReader { g in
            RoundedRectangle(cornerRadius: radius).fill(fill).frame(width: g.size.width * progress)
            if focused {
                RoundedRectangle(cornerRadius: radius > 0 ? 12 : 0).fill(t.ink).frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.5), radius: 6, y: 2)
                    .position(x: g.size.width * progress, y: height / 2)
            }
            if cue != 0 {
                Image(systemName: cue < 0 ? "backward.fill" : "forward.fill").font(.system(size: 22, weight: .bold)).foregroundStyle(t.accentInk)
                    .frame(width: 60, height: 36).background(fill, in: RoundedRectangle(cornerRadius: radius > 0 ? 18 : 0))
                    .position(x: min(max(g.size.width * progress, 30), g.size.width - 30), y: -24)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: cue)
        .frame(height: height).background(track, in: RoundedRectangle(cornerRadius: radius))
    }
}

/// Midnight Movie's one-off colors (the prototype's bCable block); Late Show never reads them.
private extension Theme {
    var breakBadge: Color { Color(hex: "#ffa94d") }
    var startOverBadge: Color { Color(hex: "#7fd39a") }
    var bevelLight: Color { Color(hex: "#6e3434") }
    var bevelDark: Color { Color(hex: "#050303") }
    var numberBevelLight: Color { Color(hex: "#ff9a8f") }
    var numberBevelDark: Color { Color(hex: "#a8231a") }
}

private extension View {
    /// CSS `border: 6px solid` with mitred light top/left and dark bottom/right edges around a solid fill. Drawn behind the
    /// content, as CSS borders are, so a banner track list rising over the edge stays on top.
    func bevelled(_ fill: Color, light: Color, dark: Color) -> some View {
        padding(6).background {   // back to front: the fill, then the edges
            fill
            GeometryReader { g in
                let w = g.size.width, h = g.size.height, b: CGFloat = 6
                Path { p in p.addLines([.zero, CGPoint(x: w, y: 0), CGPoint(x: w - b, y: b), CGPoint(x: b, y: b), CGPoint(x: b, y: h - b), CGPoint(x: 0, y: h)]); p.closeSubpath() }.fill(light)
                Path { p in p.addLines([CGPoint(x: w, y: h), CGPoint(x: 0, y: h), CGPoint(x: b, y: h - b), CGPoint(x: w - b, y: h - b), CGPoint(x: w - b, y: b), CGPoint(x: w, y: 0)]); p.closeSubpath() }.fill(dark)
            }
        }
    }
}
