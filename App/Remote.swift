import SwiftUI
import UIKit

/// The seven things a Siri Remote can say. Swipes and clickpad arrows both become up/down/left/right.
enum RemoteCommand: String { case up, down, left, right, select, play, back }

/// A focusable UIKit view that owns the remote while it is active. The app drives its own focus model (the guide drifts,
/// loops and pages), so no SwiftUI control may be focusable while this is active. The only exceptions are the text fields
/// a screen shows while `AppModel.editingField != nil`, which deactivates this view so the keyboard can take over.
/// It is also the app's one accessibility element: VoiceOver reads `focusDescription` and its gestures become commands.
/// `passBack` hands a Menu press to UIKit (which returns to the Home screen) at the app's top level, as the HIG asks.
/// Off tvOS the same view is the first responder instead, so hardware-keyboard presses (iPad, Mac) arrive here too, and
/// it turns drags, the scroll wheel and the trackpad anywhere in the window into presses.
struct RemoteCaptureView: UIViewRepresentable {
    var isActive: Bool
    var passBack: Bool
    var focusDescription: String
    var onCommand: (RemoteCommand) -> Void
    /// A keyboard's number keys, 0–9: channel numbers on the guide and the player. The Siri Remote has none.
    var onDigit: (Int) -> Void = { _ in }
    /// Up / Down from drags and scrolls (about one per row): lists follow the finger.
    var onScroll: (RemoteCommand) -> Void = { _ in }
    /// On the player a drag is one remote swipe instead (up = channel up, right = 10 s ahead), sent to `onCommand`.
    var playerSwipes = false
    /// A swipe in from the left edge of the screen.
    var onEdgeSwipe: () -> Void = {}

    func makeUIView(context: Context) -> RemoteCaptureUIView { RemoteCaptureUIView() }
    func updateUIView(_ v: RemoteCaptureUIView, context: Context) {
        v.onCommand = onCommand
        v.onDigit = onDigit
        v.onScroll = onScroll
        #if !os(tvOS)
        v.playerSwipes = playerSwipes
        v.onEdgeSwipe = onEdgeSwipe
        #endif
        v.passBack = passBack
        v.focusDescription = focusDescription
        if v.isActive != isActive { v.isActive = isActive }
    }
}

final class RemoteCaptureUIView: UIView {
    var onCommand: ((RemoteCommand) -> Void)?
    var onDigit: ((Int) -> Void)?
    var onScroll: ((RemoteCommand) -> Void)?
    var passBack = false
    var isActive = true { didSet { takeInput() } }
    /// What the focus is on, for VoiceOver. A change moves the VoiceOver cursor here so the new text is read.
    var focusDescription = "" {
        didSet {
            guard focusDescription != oldValue else { return }
            accessibilityLabel = focusDescription
            UIAccessibility.post(notification: .layoutChanged, argument: self)
        }
    }
    /// Presses handed to UIKit in pressesBegan; their ended/cancelled must follow, or UIKit never sees a whole Menu press.
    private var passed = Set<UIPress>()
    #if os(tvOS)
    override var canBecomeFocused: Bool { isActive }
    #else
    override var canBecomeFirstResponder: Bool { isActive }
    #endif

    /// tvOS: the focus engine routes the remote to the focused view. Elsewhere presses go to the first responder, which
    /// this is whenever no text is being edited (the text-entry alert's field takes it meanwhile).
    private func takeInput() {
        #if os(tvOS)
        setNeedsFocusUpdate(); updateFocusIfNeeded()
        #else
        if isActive { becomeFirstResponder() } else { resignFirstResponder() }
        #endif
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isAccessibilityElement = true
        accessibilityTraits = .adjustable   // VoiceOver swipe up/down → accessibilityIncrement/Decrement
        accessibilityHint = "Swipe up or down to move, double-tap to select, scrub to go back."
        accessibilityCustomActions = [("Previous program", RemoteCommand.left), ("Next program", RemoteCommand.right), ("Play/Pause", RemoteCommand.play)]
            .map { name, cmd in UIAccessibilityCustomAction(name: name) { [weak self] _ in self?.onCommand?(cmd); return true } }
        #if os(tvOS)   // the Siri Remote's touch surface; touchscreens and mice go through `dragPan` instead
        for dir: UISwipeGestureRecognizer.Direction in [.up, .down, .left, .right] {
            let g = UISwipeGestureRecognizer(target: self, action: #selector(swiped(_:)))
            g.direction = dir
            addGestureRecognizer(g)
        }
        #endif
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc private func swiped(_ g: UISwipeGestureRecognizer) {
        guard isActive else { return }
        switch g.direction {
        case .up: onCommand?(.up)
        case .down: onCommand?(.down)
        case .left: onCommand?(.left)
        case .right: onCommand?(.right)
        default: break
        }
    }

    // MARK: VoiceOver (its gestures replace the swipes above while it is on)

    override func accessibilityIncrement() { onCommand?(.down) }
    override func accessibilityDecrement() { onCommand?(.up) }
    override func accessibilityActivate() -> Bool { onCommand?(.select); return true }
    override func accessibilityPerformEscape() -> Bool {
        guard !passBack else { return false }   // top level: the system takes it, like a Menu press
        onCommand?(.back); return true
    }

    // MARK: Presses

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        guard isActive else { passed.formUnion(presses); return super.pressesBegan(presses, with: event) }
        var unhandled = Set<UIPress>()
        for p in presses {
            // A passed Menu press reaches UIKit, which returns to the Home screen; other keys keep their system meaning.
            if let d = digit(p) { onDigit?(d) }
            else if let cmd = command(p), !(cmd == .back && passBack) { onCommand?(cmd) } else { unhandled.insert(p) }
        }
        guard !unhandled.isEmpty else { return }
        passed.formUnion(unhandled)
        super.pressesBegan(unhandled, with: event)
    }

    /// The Siri Remote's buttons (the tvOS simulator maps the Mac keyboard onto them); off tvOS the keyboard: arrows,
    /// Return = Select, Space = Play/Pause, Esc or Delete = Back. Shortcuts with ⌘ ⌃ ⌥ stay the system's.
    private func command(_ p: UIPress) -> RemoteCommand? {
        #if !os(tvOS)
        if let key = p.key {
            guard key.modifierFlags.isDisjoint(with: [.command, .control, .alternate]) else { return nil }
            switch key.keyCode {
            case .keyboardUpArrow: return .up
            case .keyboardDownArrow: return .down
            case .keyboardLeftArrow: return .left
            case .keyboardRightArrow: return .right
            case .keyboardReturnOrEnter, .keypadEnter: return .select
            case .keyboardSpacebar: return .play
            case .keyboardEscape, .keyboardDeleteOrBackspace: return .back
            default: return nil
            }
        }
        #endif
        switch p.type {
        case .upArrow: return .up
        case .downArrow: return .down
        case .leftArrow: return .left
        case .rightArrow: return .right
        case .select: return .select
        case .playPause: return .play
        case .menu: return .back
        default: return nil
        }
    }

    /// A keyboard's number row or keypad, 0–9, by key position (so any layout, Shift or not); not with ⌘ ⌃ ⌥.
    private func digit(_ p: UIPress) -> Int? {
        guard let key = p.key, key.modifierFlags.isDisjoint(with: [.command, .control, .alternate]) else { return nil }
        let c = key.keyCode.rawValue
        for first in [UIKeyboardHIDUsage.keyboard1.rawValue, UIKeyboardHIDUsage.keypad1.rawValue] where (first...first + 9).contains(c) {
            return (c - first + 1) % 10   // 1…9, then 0
        }
        return nil
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let pass = presses.intersection(passed); passed.subtract(pass)
        if !pass.isEmpty { super.pressesEnded(pass, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let pass = presses.intersection(passed); passed.subtract(pass)
        if !pass.isEmpty { super.pressesCancelled(pass, with: event) }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        takeInput()
        #if !os(tvOS)
        for g in [scrollPan, dragPan, edgePan] { g.view?.removeGestureRecognizer(g); window?.addGestureRecognizer(g) }
        // A first responder set before the window is key can be dropped (first launch); a Mac window also loses it to others.
        NotificationCenter.default.removeObserver(self, name: UIWindow.didBecomeKeyNotification, object: nil)
        if let window { NotificationCenter.default.addObserver(self, selector: #selector(windowBecameKey), name: UIWindow.didBecomeKeyNotification, object: window) }
        #endif
    }

    #if !os(tvOS)
    // MARK: Drags, scroll wheel, trackpad

    @objc private func windowBecameKey() { takeInput() }

    var playerSwipes = false
    var onEdgeSwipe: (() -> Void)?
    private var scrollSent = 0, dragSent = 0

    /// Both live on the window, so they hear every drag and scroll over the stage whatever SwiftUI view or gesture is
    /// under the finger, and share the stage-point math.
    private lazy var scrollPan: UIPanGestureRecognizer = {
        let g = UIPanGestureRecognizer(target: self, action: #selector(scrolled(_:)))
        g.allowedScrollTypesMask = .all
        g.allowedTouchTypes = []   // scroll events only
        return g
    }()
    private lazy var dragPan: UIPanGestureRecognizer = {
        let g = UIPanGestureRecognizer(target: self, action: #selector(dragged(_:)))
        g.cancelsTouchesInView = false   // SwiftUI's taps keep every touch…
        g.delegate = self                // …and recognize alongside this
        g.require(toFail: edgePan)       // a swipe in from the left edge is the edge swipe, not a list drag
        return g
    }()
    private lazy var edgePan: UIScreenEdgePanGestureRecognizer = {
        let g = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(edgeSwiped(_:)))
        g.edges = .left
        return g
    }()

    @objc private func edgeSwiped(_ g: UIScreenEdgePanGestureRecognizer) {
        if g.state == .began, isActive { onEdgeSwipe?() }   // once, as soon as it is recognized
    }

    /// Stage points (the stage is always 1080 tall), whether SwiftUI scales this view with a transform or a frame.
    private func stage(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * 1080 / bounds.height, y: p.y * 1080 / bounds.height) }

    /// Content follows the fingers: scrolling the content up presses Down, one press per 80 pt of stage.
    @objc private func scrolled(_ g: UIPanGestureRecognizer) {
        if g.state == .began { scrollSent = 0 }
        guard isActive, bounds.height > 0 else { return }
        step(&scrollSent, to: Int(-stage(g.translation(in: self)).y / 80))
    }

    /// Lists and the guide follow the finger, one press per 100 pt (about a row); a flick adds the rows it would have
    /// coasted, and a sideways swipe presses Left / Right once. On the player the whole drag is one remote swipe.
    @objc private func dragged(_ g: UIPanGestureRecognizer) {
        if g.state == .began { dragSent = 0 }
        guard isActive, bounds.height > 0 else { return }
        let t = stage(g.translation(in: self)), dx = t.x, dy = t.y
        switch g.state {
        case .began, .changed:
            if !playerSwipes, abs(dy) > abs(dx) { step(&dragSent, to: Int(-dy / 100)) }
        case .ended:
            if playerSwipes {
                guard max(abs(dx), abs(dy)) > 60 else { return }
                onCommand?(abs(dy) > abs(dx) ? (dy < 0 ? .up : .down) : (dx > 0 ? .right : .left))
            } else if dragSent != 0 || abs(dy) > abs(dx) {
                let coast = Int(-(dy + stage(g.velocity(in: self)).y / 4) / 100)   // where a quarter second more would land
                step(&dragSent, to: max(dragSent - 6, min(dragSent + 6, coast)))
            } else if abs(dx) > 60 {
                onCommand?(dx < 0 ? .right : .left)
            }
        default: break
        }
    }

    private func step(_ sent: inout Int, to n: Int) {
        while sent < n { onScroll?(.down); sent += 1 }
        while sent > n { onScroll?(.up); sent -= 1 }
    }
    #endif
}

#if !os(tvOS)
extension RemoteCaptureUIView: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}
#endif
