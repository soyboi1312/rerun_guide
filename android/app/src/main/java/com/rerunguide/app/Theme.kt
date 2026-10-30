package com.rerunguide.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Profiles persist this; an unknown value from an older or newer build maps to LATE (App/Theme.swift ThemeID). */
enum class ThemeID(val raw: String, val displayName: String) {
    LATE("late", "Late Show"), MIDNIGHT("midnight", "Midnight Movie");
    val theme: Theme get() = if (this == LATE) Theme.late else Theme.midnight
    companion object { fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: LATE }
}

fun hex(s: String): Color = Color(android.graphics.Color.parseColor(s))

/** The design tokens, one to one with App/Theme.swift. Every screen reads colors and fonts from here. */
data class Theme(
    val id: ThemeID,
    val bg: Color, val panel: Color, val panel2: Color, val line: Color,
    val ink: Color, val mute: Color, val dim: Color,
    val accent: Color, val accentInk: Color,
    val focusBg: Color, val focusInk: Color, val focusDim: Color,
    val scrim: Color, val glow: Color,
    val radius: Float, val cellRadius: Float, val pill: Float,
    val focusScale: Float, val focusShadow: Boolean, val uppercase: Boolean,
    val headingTracking: Float,   // em
    val headingWeight: FontWeight,
    val headingFamily: FontFamily, val bodyFamily: FontFamily,
) {
    /** Heading text style (Bricolage Grotesque / Barlow Condensed): the Swift `t.heading(size, weight:)`. Both styles lay
     *  text out with unrounded glyph advances (TextMotion.Animated: linear metrics, no hinting), as tvOS does; hinted
     *  text at the TV's 1 px per point ran ~1% wider and wrapped earlier than on the Apple TV. */
    fun heading(size: Number, weight: FontWeight? = null) =
        TextStyle(fontFamily = headingFamily, fontWeight = clamp(weight ?: headingWeight), fontSize = size.toFloat().sp, textMotion = TextMotion.Animated)

    /** Body text style (Hanken Grotesk / Barlow Condensed): the Swift `t.body(size, weight:)`. */
    fun body(size: Number, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = bodyFamily, fontWeight = clamp(weight), fontSize = size.toFloat().sp, textMotion = TextMotion.Animated)

    /** Headings in caps when the theme says so (Late Show doesn't). */
    fun heading(text: String) = if (uppercase) text.uppercase() else text

    /** Phone type (the iOS handoff's Type table, Portrait*.kt): Late Show's size, 10% more for Midnight Movie's condensed
     *  face. Outside the stage `sp` follows the system font size, as Dynamic Type does on iPhone. */
    fun pBody(size: Number, weight: FontWeight = FontWeight.Normal) = body(size.toFloat() * (if (id == ThemeID.MIDNIGHT) 1.1f else 1f), weight)
    fun pHeading(size: Number, weight: FontWeight? = null) = heading(size.toFloat() * (if (id == ThemeID.MIDNIGHT) 1.1f else 1f), weight)

    /** Letter-spacing in em, like the handoff's CSS: `tracking(em: -0.03, size: 88)` → (-0.03).em. */
    fun tracking(em: Number) = em.toFloat().em

    /** Barlow Condensed ships 500–800 only, so its "regular" is Medium. */
    private fun clamp(w: FontWeight) = if (bodyFamily == Fonts.barlow && headingFamily == Fonts.barlow && w == FontWeight.Normal) FontWeight.Medium else w

    companion object {
        val late = Theme(
            id = ThemeID.LATE,
            bg = hex("#14110f"), panel = hex("#1e1a17"), panel2 = hex("#2a231e"), line = hex("#3b3129"),
            ink = hex("#f3ebe0"), mute = hex("#c9bdae"), dim = hex("#a89c8e"),
            accent = hex("#f0a33a"), accentInk = hex("#14110f"),
            focusBg = hex("#f3ebe0"), focusInk = hex("#16120f"), focusDim = hex("#5a4e42"),
            scrim = Color(10 / 255f, 8 / 255f, 7 / 255f, 0.72f), glow = Color(240 / 255f, 163 / 255f, 58 / 255f, 0.10f),
            radius = 24f, cellRadius = 16f, pill = 999f, focusScale = 1.03f, focusShadow = true, uppercase = false,
            headingTracking = -0.03f, headingWeight = FontWeight.Bold,
            headingFamily = Fonts.bricolage, bodyFamily = Fonts.hanken,
        )
        /** Near-black and neon red, everything in Barlow Condensed, square corners, no focus lift. */
        val midnight = Theme(
            id = ThemeID.MIDNIGHT,
            bg = hex("#0c0909"), panel = hex("#241414"), panel2 = hex("#2e1919"), line = hex("#4a2424"),
            ink = hex("#ffffff"), mute = hex("#eadcd8"), dim = hex("#c4a9a4"),
            accent = hex("#ff4a3a"), accentInk = hex("#0c0909"),
            focusBg = hex("#ff4a3a"), focusInk = hex("#0c0909"), focusDim = hex("#3a0c09"),
            scrim = Color(8 / 255f, 4 / 255f, 4 / 255f, 0.82f), glow = Color(1f, 74 / 255f, 58 / 255f, 0.12f),
            radius = 0f, cellRadius = 0f, pill = 0f, focusScale = 1f, focusShadow = false, uppercase = true,
            headingTracking = 0.01f, headingWeight = FontWeight.ExtraBold,
            headingFamily = Fonts.barlow, bodyFamily = Fonts.barlow,
        )
    }
}

/** The handoff's oklch(L C H) gradients, approximated: HSB with hue H, used for channel-colored previews. */
fun channelColor(hue: Double, lightness: Double, chroma: Double): Color =
    Color.hsv((((hue % 360) + 360) % 360).toFloat(), minOf(1.0, chroma * 4).toFloat(), minOf(1.0, lightness * 1.15).toFloat())

/** `Color(hex: "#f0a33a")` for strings stored in profiles. */
fun colorHex(s: String): Color = runCatching { hex(if (s.startsWith("#")) s else "#$s") }.getOrDefault(Color.Black)

/** The three bundled Google Fonts families (OFL), static TTFs in res/font. */
object Fonts {
    val bricolage = FontFamily(
        Font(R.font.bricolage_grotesque_medium, FontWeight.Medium),
        Font(R.font.bricolage_grotesque_semi_bold, FontWeight.SemiBold),
        Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
    )
    val hanken = FontFamily(
        Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
        Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
        Font(R.font.hanken_grotesk_semi_bold, FontWeight.SemiBold),
        Font(R.font.hanken_grotesk_bold, FontWeight.Bold),
        Font(R.font.hanken_grotesk_extra_bold, FontWeight.ExtraBold),
    )
    val barlow = FontFamily(
        Font(R.font.barlow_condensed_medium, FontWeight.Normal),   // Barlow Condensed ships 500–800 only
        Font(R.font.barlow_condensed_medium, FontWeight.Medium),
        Font(R.font.barlow_condensed_semi_bold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
        Font(R.font.barlow_condensed_extra_bold, FontWeight.ExtraBold),
        Font(R.font.barlow_condensed_extra_bold_italic, FontWeight.ExtraBold, FontStyle.Italic),
    )
}

/** The current profile's theme for every composable (the Swift `@Environment(\.theme)`). */
val LocalTheme = staticCompositionLocalOf { Theme.late }
