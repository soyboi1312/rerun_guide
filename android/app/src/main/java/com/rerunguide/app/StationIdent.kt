package com.rerunguide.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The station ident: a full-screen channel card WatchScreen shows for 1.5 s on tune-in ("YOU'RE WATCHING") and
 *  before a trailer break ("WE'LL BE RIGHT BACK"). WatchScreen decides when; this is the picture and the sting.
 *  Fixed 1920×1080 stage points, like the Swift frame. */
@Composable
fun StationIdentCard(number: Int, name: String, tag: String, kicker: String, sub: String, modifier: Modifier = Modifier) {
    val t = LocalTheme.current
    Box(
        modifier.requiredSize(1920.dp, 1080.dp).clipToBounds().background(t.bg).drawBehind {
            // The onboarding Stage's radial-gradient(1100px 700px …) glow, centered behind the card.
            scale(1f, 700f / 1100f, pivot = center) {
                val r = 770.dp.toPx()   // transparent past its radius: the circle is the whole glow
                drawCircle(Brush.radialGradient(listOf(t.glow, t.glow.copy(alpha = 0f)), center, r), r, center)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(340.dp).background(t.accent, if (t.id == ThemeID.MIDNIGHT) RectangleShape else CircleShape).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                // minimumScaleFactor(0.6): shrink to fit the 292-pt inside, never below 60 %.
                val style = t.heading(160).copy(color = t.accentInk)
                val measurer = rememberTextMeasurer()
                val room = with(LocalDensity.current) { 292.dp.toPx() }
                val wide = remember(number, style, measurer) { measurer.measure("$number", style, softWrap = false).size.width.toFloat() }
                val fit = if (wide > room) style.copy(fontSize = style.fontSize * max(0.6f, room / wide)) else style
                BasicText("$number", style = fit, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText(kicker, style = t.body(28, FontWeight.Bold).copy(color = t.accent, letterSpacing = 0.18.em))
                line(t.heading(name), t.heading(72).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)))
                line(tag, t.body(30).copy(color = t.dim))
                line(sub, t.body(32).copy(color = t.mute), Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun line(s: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) =
    BasicText(s, modifier, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)

/** The sting under the card: a two-note "bing-bong" on tune-in, a softer three-note descending figure before a break.
 *  Sines with a 30 ms attack and an exponential release, rendered once into PCM and played as static AudioTracks that
 *  mix over the program audio (no audio focus is taken). Audio failures are ignored: the card still shows.
 *  Everything AudioTrack runs on `queue`, never the main thread, so a slow audio server can't stall the remote.
 *  ponytail: synthesized stand-in for a real sting asset; replace `render` with a decoded asset when one exists. */
class StationSting private constructor() {
    companion object { val shared = StationSting() }

    @Suppress("EnumEntryName")
    enum class Kind { tune, brk }

    private val sampleRate = 44100
    private val queue = Executors.newSingleThreadScheduledExecutor { Thread(it, "rerunguide.sting") }
    private val tracks = mutableMapOf<Kind, AudioTrack>()   // touched on `queue` only
    private val frames = mutableMapOf<Kind, Int>()
    private var playing: AudioTrack? = null
    private var gen = 0

    fun play(kind: Kind) {
        queue.execute {
            runCatching {
                if (tracks.size < Kind.entries.size) {   // built together, so a failure leaves none (and is retried)
                    tracks.clear(); frames.clear()
                    for ((k, pcm) in listOf(Kind.tune to render(listOf(880.0 to 0.12, 660.0 to 0.22), gain = 0.2),             // −14 dBFS
                                            Kind.brk to render(listOf(660.0 to 0.14, 554.0 to 0.14, 440.0 to 0.28), gain = 0.11))) { // −19 dBFS
                        tracks[k] = track(pcm); frames[k] = pcm.size
                    }
                }
                val track = tracks.getValue(kind)
                playing?.stop()
                track.stop(); track.reloadStaticData(); track.play()
                playing = track
                // A static track that has played to its end still counts as playing; stop it once the chime is over.
                val g = ++gen
                queue.schedule({ runCatching { if (g == gen) { track.stop(); playing = null } } },
                    frames.getValue(kind) * 1000L / sampleRate + 500, TimeUnit.MILLISECONDS)   // room for an output waking from standby
            }
        }
    }

    /** (frequency Hz, seconds) notes back to back, each with a 30 ms attack and a release that lands at −43 dB. */
    private fun render(notes: List<Pair<Double, Double>>, gain: Double): ShortArray {
        val sr = sampleRate.toDouble()
        val out = ShortArray(notes.sumOf { (it.second * sr).toInt() })
        var i = 0
        for ((hz, dur) in notes) {
            for (k in 0 until (dur * sr).toInt()) {
                val t = k / sr
                val env = min(1.0, t / 0.03) * exp(-5 * t / dur)
                out[i++] = (sin(2 * PI * hz * t) * env * gain * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private fun track(pcm: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
        .also { it.write(pcm, 0, pcm.size) }
}
