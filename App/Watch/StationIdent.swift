import SwiftUI
import AVFoundation

/// The station ident: a full-screen channel card WatchScreen shows for 1.5 s on tune-in ("YOU'RE WATCHING") and
/// before a trailer break ("WE'LL BE RIGHT BACK"). WatchScreen decides when; this is the picture and the sting.
struct StationIdentCard: View {
    @Environment(\.theme) private var t
    let number: Int, name: String, tag: String, kicker: String, sub: String

    var body: some View {
        ZStack {
            t.bg
            // The onboarding Stage's radial-gradient(1100px 700px …) glow, centered behind the card.
            RadialGradient(colors: [t.glow, .clear], center: .center, startRadius: 0, endRadius: 770)
                .frame(width: 2200, height: 2200).scaleEffect(x: 1, y: 700 / 1100).position(x: 960, y: 540)
            HStack(spacing: 64) {
                (t.id == .midnight ? AnyShape(Rectangle()) : AnyShape(Circle())).fill(t.accent).frame(width: 340, height: 340)
                    .overlay(Text("\(number)").font(t.heading(160)).minimumScaleFactor(0.6).lineLimit(1).padding(24).foregroundStyle(t.accentInk))
                VStack(alignment: .leading, spacing: 12) {
                    Text(kicker).font(t.body(28, weight: .bold)).tracking(em: 0.18, size: 28).foregroundStyle(t.accent)
                    Text(t.heading(name)).font(t.heading(72)).tracking(em: t.headingTracking, size: 72).foregroundStyle(t.ink).lineLimit(1)
                    Text(tag).font(t.body(30)).foregroundStyle(t.dim).lineLimit(1)
                    Text(sub).font(t.body(32)).foregroundStyle(t.mute).lineLimit(1).padding(.top, 12)
                }
                .frame(maxWidth: 1100, alignment: .leading)
            }
            .fixedSize()
        }
        .frame(width: 1920, height: 1080).clipped()
    }
}

/// The sting under the card: a two-note "bing-bong" on tune-in, a softer three-note descending figure before a break.
/// Sines with a 30 ms attack and an exponential release, rendered once into small in-memory WAVs and played with
/// AVAudioPlayer over the program audio. Not AVAudioEngine: its player node raises an Objective-C exception ("player did
/// not see an IO cycle") when the engine's output stops under it, for example after an HDMI or AirPlay route change on
/// an Apple TV, and Swift can't catch that. AVAudioPlayer just returns false; the card still shows either way.
/// Everything AVAudio runs on `queue`, never the main thread: on a real Apple TV the session calls are synchronous trips
/// to the audio server that can stall while the program's own audio is starting up, and a stalled main thread stops the
/// remote (and, under Xcode's debugger, the TV button) from doing anything.
/// ponytail: synthesized stand-in for a real sting asset; load an audio file into the players when one exists.
final class StationSting: @unchecked Sendable {   // all mutable state is touched on `queue` only
    static let shared = StationSting()
    enum Kind: Sendable { case tune, brk }

    private let queue = DispatchQueue(label: "rerunguide.sting", qos: .userInitiated)
    private var players: [Kind: AVAudioPlayer] = [:]

    private init() {}

    func play(_ kind: Kind) {
        queue.async { [self] in
            if players.isEmpty {
                try? AVAudioSession.sharedInstance().setCategory(.playback, options: .mixWithOthers)
                players[.tune] = try? AVAudioPlayer(data: Self.wav([(880, 0.12), (660, 0.22)], gain: 0.2))                // −14 dBFS
                players[.brk] = try? AVAudioPlayer(data: Self.wav([(660, 0.14), (554, 0.14), (440, 0.28)], gain: 0.11))   // −19 dBFS
            }
            guard let p = players[kind] else { return }
            p.currentTime = 0
            p.play()
        }
    }

    /// (frequency Hz, seconds) notes back to back, each with a 30 ms attack and a release that lands at −43 dB, as a
    /// 16-bit mono 44.1 kHz WAV.
    static func wav(_ notes: [(Double, Double)], gain: Double) -> Data {
        let sr = 44100.0
        var pcm = Data()
        for (hz, dur) in notes {
            for k in 0..<Int(dur * sr) {
                let t = Double(k) / sr
                let v = sin(2 * .pi * hz * t) * min(1, t / 0.03) * exp(-5 * t / dur) * gain
                withUnsafeBytes(of: Int16(max(-1, min(1, v)) * 32767).littleEndian) { pcm.append(contentsOf: $0) }
            }
        }
        var wav = Data()
        func put(_ s: String) { wav.append(contentsOf: Array(s.utf8)) }
        func put32(_ n: Int) { withUnsafeBytes(of: UInt32(n).littleEndian) { wav.append(contentsOf: $0) } }
        func put16(_ n: Int) { withUnsafeBytes(of: UInt16(n).littleEndian) { wav.append(contentsOf: $0) } }
        put("RIFF"); put32(36 + pcm.count); put("WAVE")
        put("fmt "); put32(16); put16(1); put16(1); put32(Int(sr)); put32(Int(sr) * 2); put16(2); put16(16)   // PCM, mono, 16-bit
        put("data"); put32(pcm.count)
        return wav + pcm
    }
}
