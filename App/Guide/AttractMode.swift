import SwiftUI
import RerunGuideCore

/// Attract mode's right-hand panel: the storefront wall's big clock over the channel line and title of whatever the
/// drift is passing. Stands in for InfoPanel while `model.attracting`; the hint pills and blurb are gone.
struct AttractPanel: View {
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw
    let ch: Channel, title: String

    var body: some View {
        let mm = t.id == .midnight
        VStack(alignment: .leading, spacing: 0) {
            TimelineView(.periodic(from: .timelineOrigin, by: 1)) { ctx in
                Text(ctx.date, format: .dateTime.hour().minute()).font(t.heading(96)).monospacedDigit().foregroundStyle(t.accent)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            Spacer(minLength: 0)
            ChannelLine(ch: ch)
            // Mixed case in both themes, like the info panel's title it crossfades with.
            Text(title).font(t.heading(80)).tracking(em: mm ? 0 : -0.025, size: 80).lineLimit(2).padding(.vertical, t.lineHeightTrim(80)).padding(.top, 12)
        }
        // ponytail: hard-coded beside PreviewWindow's attract tube (Late Show 640×480, Midnight Movie 824×464 ending at x 904)
        // and above the time header / bar; no handoff spec for this layout. Midnight Movie sits where its info panel's content does.
        .at(mm ? 940 : 904, mm ? 84 : 90, w: (mm ? 860 : 936) + sw - 1920, h: mm ? 396 : 460)
    }
}
