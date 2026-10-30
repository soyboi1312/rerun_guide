package com.rerunguide.app

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A wrapping paragraph with SwiftUI's line breaking (TextKit's standard strategy pushes out orphans): when the last line
 *  would be one short lone word, the word before it moves down with it ("…says yes, we / move on."). A long one stays
 *  ("…computer, open / emby.media/pin" on tvOS). Re-laid out once.
 *  ponytail: "short" = under a quarter of the line, which fits every tvOS case seen; TextKit's exact rule is unpublished. */
@Composable
internal fun Para(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Para(AnnotatedString(text), style, modifier, maxLines)

@Composable
internal fun Para(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    var shown by remember(text) { mutableStateOf(text) }
    BasicText(shown, modifier, style, overflow = TextOverflow.Ellipsis, maxLines = maxLines, onTextLayout = { r ->
        val n = r.lineCount
        // once (the fixed copy is one character longer); text cut off by `maxLines` (SwiftUI .lineLimit) has no orphan
        if (shown.length != text.length || n < 2 || r.hasVisualOverflow) return@BasicText
        if (r.layoutInput.constraints.maxWidth == Constraints.Infinity || text.text.getOrNull(r.getLineStart(n - 1) - 1) == '\n') return@BasicText   // unbounded, or a paragraph of its own
        val s = text.text
        if (s.substring(r.getLineStart(n - 1), r.getLineEnd(n - 1)).trim().contains(' ')) return@BasicText
        if (r.getLineRight(n - 1) - r.getLineLeft(n - 1) > r.layoutInput.constraints.maxWidth / 4f) return@BasicText   // only a short one
        val prevStart = r.getLineStart(n - 2)
        val gap = s.substring(prevStart, r.getLineEnd(n - 2)).trimEnd().lastIndexOf(' ')
        if (gap < 0) return@BasicText
        val k = prevStart + gap + 1
        shown = text.subSequence(0, k) + AnnotatedString("\n") + text.subSequence(k, text.length)
    })
}

/** Natural line height in em, from the bundled TTFs' hhea metrics (no line gap): Hanken Grotesk 1.303, Bricolage
 *  Grotesque and Barlow Condensed 1.2. */
internal fun TextStyle.lineEm() = if (fontFamily == Fonts.hanken) 1.303f else 1.2f

/** SwiftUI `.lineSpacing(n)`: n points between wrapped lines on top of the face's natural line, none above the first or
 *  below the last. One line keeps its natural height. */
internal fun TextStyle.lineSpacing(n: Float) = copy(
    lineHeight = (fontSize.value * lineEm() + n).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** 12-hour clock text in the user's locale (the prototype's fmt and range), shared by the guide, the player and the info sheet. */
internal object Clock {
    private val timeF = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
    private val apF = DateTimeFormatter.ofPattern("a", Locale.getDefault())
    private val dateF = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())
    fun time(d: Instant): String = timeF.format(d.atZone(ZoneId.systemDefault()))
    fun ap(d: Instant): String = apF.format(d.atZone(ZoneId.systemDefault()))
    /** "WED OCT 22" (the Midnight Movie time bar). */
    fun date(d: Instant): String = dateF.format(d.atZone(ZoneId.systemDefault())).uppercase()
    /** "Wed Oct 22" (the portrait lineup's header). */
    fun day(d: Instant): String = dateF.format(d.atZone(ZoneId.systemDefault()))
    /** The first AM/PM only when it differs from the second. */
    fun range(a: Instant, b: Instant): String {
        val x = ap(a); val y = ap(b)
        return time(a) + (if (x != y) " $x" else "") + " – " + time(b) + " " + y
    }
}
