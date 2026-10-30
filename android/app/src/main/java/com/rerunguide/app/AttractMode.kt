package com.rerunguide.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.rerunguide.core.Channel
import java.util.Date

/** Attract mode's right-hand panel: the storefront wall's big clock over the channel line and title of whatever the
 *  drift is passing. Stands in for InfoPanel while `model.attracting`; the hint pills and blurb are left out.
 *  `modifier` goes after the placement (the guide's crossfade alpha). */
@Composable
fun AttractPanel(ch: Channel, title: String, modifier: Modifier = Modifier) {
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    val context = LocalContext.current
    val mm = t.id == ThemeID.MIDNIGHT
    // ponytail: hard-coded beside PreviewWindow's attract tube (Late Show 640×480, Midnight Movie 824×464 ending at x 904)
    // and above the time header / bar; no handoff spec for this layout. Midnight Movie sits where its info panel's content does.
    Column(Modifier.at(if (mm) 940 else 904, if (mm) 84 else 90, (if (mm) 860 else 936) + sw - 1920, if (mm) 396 else 460).then(modifier)) {
        EverySecond { d ->
            // `.dateTime.hour().minute()`: the device's own 12/24-hour clock.
            BasicText(android.text.format.DateFormat.getTimeFormat(context).format(Date.from(d)), Modifier.fillMaxWidth(),
                t.heading(96).copy(color = t.accent, fontFeatureSettings = "tnum", textAlign = TextAlign.End))
        }
        Spacer(Modifier.weight(1f))
        ChannelLine(ch)
        // Mixed case in both themes, like the info panel's title it crossfades with.
        Para(title, t.heading(80).copy(color = t.ink, letterSpacing = (if (mm) 0f else -0.025f).em),
            Modifier.padding(top = 12.dp).lineHeightTrim(80f), maxLines = 2)
    }
}
