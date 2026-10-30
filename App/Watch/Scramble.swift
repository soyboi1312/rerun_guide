import SwiftUI
import AVFoundation
#if targetEnvironment(macCatalyst)
import CoreAudio   // UnsafeMutableAudioBufferListPointer: AVFoundation brings it on tvOS and iOS, not on the Mac
#endif
import RerunGuideCore

/// Channel 99's picture: a mystery backdrop from the library rolling and tearing under the scramble shaders, the
/// way premium cable channels looked without a descrambler. `seed` picks the backdrop (the callers pass the slot start, so
/// each "program" gets its own). The full-screen one also plays the warble.
struct ScrambleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase
    let seed: Int
    var audio = false
    @State private var art: URL?

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            TimelineView(.animation) { ctx in
                let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 3600)
                let roll = CGFloat(t.truncatingRemainder(dividingBy: 7) / 7) * size.height   // one roll every 7 s
                VStack(spacing: 0) { picture(size); picture(size) }
                    .offset(y: -roll)
                    .frame(width: size.width, height: size.height, alignment: .top).clipped()
                    .distortionEffect(ShaderLibrary.scrambleWarp(.float(Float(t)), .float2(size)), maxSampleOffset: CGSize(width: size.width * 0.4, height: 2))
                    .colorEffect(ShaderLibrary.scrambleColor(.float(Float(t)), .float2(size)))
            }
        }
        .background(.black)
        .task(id: seed) {
            let candidates = model.library.filter { $0.backdropPath != nil }
            art = candidates.isEmpty ? nil : model.server?.backdropURL(for: candidates[abs(seed) % candidates.count])
        }
        .onAppear { if audio { ScrambleAudio.shared.start() } }
        .onDisappear { if audio { ScrambleAudio.shared.stop() } }
        .onChange(of: phase) { _, p in   // the warble follows the app out to the Home screen and back
            guard audio else { return }
            if p == .background { ScrambleAudio.shared.stop() } else if p == .active { ScrambleAudio.shared.start() }
        }
    }

    @ViewBuilder private func picture(_ size: CGSize) -> some View {
        ZStack {
            // Colour bars stand in until the backdrop arrives, and are what shows when the library has no art.
            HStack(spacing: 0) {
                ForEach([Color.white, .yellow, .cyan, .green, .purple, .red, .blue], id: \.self) { $0 }
            }
            if let art { AsyncImage(url: art) { $0.resizable().scaledToFill() } placeholder: { Color.clear } }
        }
        .frame(width: size.width, height: size.height).clipped()
    }
}

/// The sound of a scrambled channel: a tone that never settles, buried in hiss. Quiet, and it stops with the picture.
/// Like `StationSting`, every AVAudio call runs on its own queue so a slow audio server can't freeze the remote.
final class ScrambleAudio: @unchecked Sendable {   // the engine is touched on `queue` only
    static let shared = ScrambleAudio()
    private let queue = DispatchQueue(label: "rerunguide.scramble-audio", qos: .userInitiated)
    private let engine = AVAudioEngine()
    private var wired = false

    private init() {}

    /// The render block runs on the audio thread; built in a static func so it carries no actor isolation.
    private static func warble(sampleRate sr: Double) -> AVAudioSourceNodeRenderBlock {
        var phase = 0.0, lfo = 0.0, hiss: UInt32 = 2463534242
        return { _, _, frameCount, abl in
            let buffers = UnsafeMutableAudioBufferListPointer(abl)
            guard let out = buffers[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            for i in 0..<Int(frameCount) {
                lfo += 0.9 / sr
                let hz = 260 + 140 * sin(lfo * 2 * .pi) + 40 * sin(lfo * 13.7)
                phase += hz / sr
                hiss ^= hiss << 13; hiss ^= hiss >> 17; hiss ^= hiss << 5             // xorshift noise
                let noise = (Double(hiss) / Double(UInt32.max) - 0.5) * 0.5
                let tone = sin(phase * 2 * .pi) * 0.35 + sin(phase * 4 * .pi) * 0.12
                out[i] = Float((tone + noise) * 0.18)
            }
            return noErr
        }
    }

    func start() {
        queue.async { [self] in
            if !wired {
                wired = true
                let sr = 44100.0, format = AVAudioFormat(standardFormatWithSampleRate: sr, channels: 1)!
                let node = AVAudioSourceNode(format: format, renderBlock: Self.warble(sampleRate: sr))
                engine.attach(node)
                engine.connect(node, to: engine.mainMixerNode, format: format)
                engine.mainMixerNode.outputVolume = 0.6
            }
            guard !engine.isRunning else { return }
            try? AVAudioSession.sharedInstance().setCategory(.playback, options: .mixWithOthers)
            try? AVAudioSession.sharedInstance().setActive(true)
            try? engine.start()
        }
    }

    func stop() { queue.async { [self] in engine.stop() } }
}
