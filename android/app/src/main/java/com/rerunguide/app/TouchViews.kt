package com.rerunguide.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The guide's menu button, Back, and Play/Pause on the player: the remote's buttons have no on-screen equivalent in
 *  the TV design. Window space, 44 dp, in the top-left corner. Phones and tablets only (RootView). */
@Composable
fun TouchChrome(modifier: Modifier = Modifier) {
    val model = LocalModel.current
    val paused = model.playback?.paused == true
    val fade = tween<Float>(200, easing = EaseOut)
    // Menu (guide only) and Back never show together; stacked, so one fading out never holds the other's place
    // (SwiftUI lays the new HStack out without the removed button).
    Box(modifier) {
        AnimatedVisibility(model.showsMenuButton, enter = fadeIn(fade), exit = fadeOut(fade)) {
            TouchButton(menuGlyph, "Menu") { model.openOptions() }
        }
        AnimatedVisibility(model.showsTouchBack, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TouchButton(backGlyph, "Back") { model.touchBack() }
                if (model.screen == Screen.watch) {
                    TouchButton(if (paused) playGlyph else pauseGlyph, if (paused) "Play" else "Pause") { model.press(RemoteCommand.play) }
                }
            }
        }
    }
}

@Composable
private fun TouchButton(glyph: DrawScope.(Color) -> Unit, label: String, action: () -> Unit) {
    val t = LocalModel.current.theme
    Canvas(Modifier.size(44.dp).background(t.panel.copy(alpha = 0.9f), if (t.radius == 0f) RectangleShape else CircleShape)
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = action)
        .semantics { contentDescription = label }) { glyph(t.ink) }
}

// SF Symbols at 18 pt bold, drawn around the button's center (offsets in dp).
private val menuGlyph: DrawScope.(Color) -> Unit = { ink ->   // line.3.horizontal
    for (dy in listOf(-6f, 0f, 6f)) drawLine(ink, at(-8f, dy), at(8f, dy), 2.4.dp.toPx(), StrokeCap.Round)
}
private val backGlyph: DrawScope.(Color) -> Unit = { ink ->   // chevron.backward
    drawPath(path(at(3f, -8f), at(-5f, 0f), at(3f, 8f)), ink, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
private val playGlyph: DrawScope.(Color) -> Unit = { ink ->   // play.fill
    val p = path(at(-5f, -8f), at(8.5f, 0f), at(-5f, 8f)).apply { close() }
    drawPath(p, ink)
    drawPath(p, ink, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))   // the symbol's rounded corners
}
private val pauseGlyph: DrawScope.(Color) -> Unit = { ink ->   // pause.fill
    for (x in listOf(-6f, 1.5f)) drawRoundRect(ink, at(x, -8f), Size(4.5.dp.toPx(), 16.dp.toPx()), CornerRadius(1.2.dp.toPx()))
}

/** The point (x, y) dp from the center. */
private fun DrawScope.at(x: Float, y: Float) = center + Offset(x.dp.toPx(), y.dp.toPx())

private fun path(a: Offset, b: Offset, c: Offset) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y) }

/** The first three launches, the menu's edge peeks in from the left three times on the guide, then goes away: the
 *  menu slides in from there (a swipe in from the edge, the menu button, or the profile chip). */
@Composable
fun MenuHint(modifier: Modifier = Modifier) {
    val model = LocalModel.current
    var finished by remember { mutableStateOf(false) }
    if (MenuHintLaunches.due && !MenuHintLaunches.played && !finished && model.showsMenuButton) {
        val peek = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(1200)
            repeat(3) {
                launch { peek.animateTo(34f, spring(dampingRatio = 0.5f, stiffness = 438.65f)) }   // .spring(response: 0.3, dampingFraction: 0.5)
                delay(450)
                launch { peek.animateTo(0f, tween(250, easing = EaseIn)) }
                delay(500)
            }
            MenuHintLaunches.played = true; finished = true
            MenuHintLaunches.count()
        }
        // Hidden off the left edge (60 + 8 + 5 = 73) until it peeks.
        Row(modifier.fillMaxHeight().offset { IntOffset((-73 + peek.value).dp.roundToPx(), 0) }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(60.dp).fillMaxHeight().fillWithShadow(model.theme.panel, 0f, Color.Black.copy(alpha = 0.5f), blur = 16f, x = 6f))
            Box(Modifier.padding(start = 8.dp).size(5.dp, 56.dp).background(model.theme.ink.copy(alpha = 0.4f), RoundedCornerShape(50)))
        }
    }
}

private object MenuHintLaunches {
    private const val KEY = "menuHintLaunches"
    /** Read once per launch; counted when this launch's hint has played. */
    val due by lazy { AppModel.defaults.getInt(KEY, 0) < 3 }
    var played = false
    fun count() = AppModel.defaults.edit().putInt(KEY, AppModel.defaults.getInt(KEY, 0) + 1).apply()
}

/** Text input: the field the remote would have opened inline (`editingField`) as a system alert with a text field,
 *  on TV and phone alike (an EditText brings the system keyboard on Google TV and stays legible however small the
 *  stage is drawn; OK and Cancel are D-pad focusable). OK, or the phone keyboard's Done, commits the way the tvOS fields'
 *  submit does; Cancel or Back leaves the value as it was. Search's results follow the typing behind the dialog. */
@Composable
fun TextEntry() {
    val model = LocalModel.current
    val field = model.editingField ?: return
    val context = LocalContext.current
    DisposableEffect(field) {
        val dialog = textEntryDialog(context, model, field)
        dialog.show()
        onDispose { dialog.dismiss() }   // OK / Cancel cleared editingField, or the model closed the field itself
    }
}

private fun textEntryDialog(context: Context, model: AppModel, field: String): AlertDialog {
    val original = initial(model, field)
    val edit = EditText(context).apply {
        hint = title(field)
        inputType = when (field) {
            "pass" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            "addr" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            "chNum" -> InputType.TYPE_CLASS_NUMBER
            "user" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        // No fullscreen extract editor (a phone in landscape): the alert stays visible above the keyboard, as on iOS.
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setText(original)
        setSelection(text.length)
        nextFocusDownId = android.R.id.button1   // Down from the field: OK (the nearest button is Cancel)
        if (field == "search") doAfterTextChanged { model.searchQuery = text.toString() }
    }
    val pad = (24 * context.resources.displayMetrics.density).toInt()
    return AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)   // the iOS app is dark-only
        .setTitle(title(field))
        .setView(FrameLayout(context).apply { setPadding(pad, pad / 3, pad, 0); addView(edit) })
        .setNegativeButton("Cancel") { _, _ -> cancel(model, original) }
        .setPositiveButton("OK") { _, _ -> commit(model, field, edit.text.toString()) }
        .setOnCancelListener { cancel(model, original) }   // Back
        .create().apply {
            setCanceledOnTouchOutside(false)   // an iOS alert ignores taps around it
            window?.let { w ->
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                // .statusBarHidden(), except in portrait, which shows the bars (a dialog window shows them by default)
                if (!model.portrait) WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
            // Phone: the keyboard's Done commits. Google TV's keyboard sends Done also when Up leaves its top row, so
            // there Done only hides the keyboard and focuses OK; one more OK commits.
            edit.setOnEditorActionListener { v, id, e ->
                val done = id == EditorInfo.IME_ACTION_DONE || (e?.keyCode == KeyEvent.KEYCODE_ENTER && e.action == KeyEvent.ACTION_DOWN)
                if (done && Platform.isTV) {
                    window?.let { w -> WindowCompat.getInsetsController(w, v).hide(WindowInsetsCompat.Type.ime()) }
                    getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                } else if (done) commit(model, field, v.text.toString())   // editingField = null → TextEntry dismisses the dialog
                done
            }
            setOnShowListener {
                edit.requestFocus()
                window?.let { w -> edit.post { WindowCompat.getInsetsController(w, edit).show(WindowInsetsCompat.Type.ime()) } }
            }
        }
}

private fun title(f: String): String = when (f) {
    "addr" -> "Server address"
    "user" -> "Username"
    "pass" -> "Password"
    "profileName" -> "Profile name"
    "chName" -> "Channel name"
    "chNum" -> "Channel number"
    "search" -> "Search"
    else -> ""
}

private fun initial(model: AppModel, f: String): String {
    val profiles = model.persisted.profiles
    return when (f) {
        "addr" -> model.address
        "user" -> model.username
        "pass" -> model.password
        "profileName" -> profiles.getOrNull(model.focusIndex)?.name ?: ""
        "chName" -> model.selectedChannel?.name ?: ""
        "chNum" -> model.numberDraft ?: ""
        "search" -> model.searchQuery
        else -> ""
    }
}

/** Never reads `editingField`: the field is the one this dialog was opened for. */
private fun commit(model: AppModel, f: String, draft: String) {
    when (f) {
        "addr" -> model.address = AppModel.normalized(draft)
        "user" -> model.username = draft
        "pass" -> model.password = draft
        "profileName" -> model.commitProfileName(model.focusIndex, draft)
        "chName" -> model.channelSelection?.let { id -> model.updateChannel(id) { it.copy(name = draft) } }
        "chNum" -> { model.numberDraft = draft.filter { it.isDigit() }.take(3); model.commitNumber() }
        "search" -> model.searchQuery = draft
    }
    model.editingField = null
}

/** `original`: the field's value when the dialog opened. */
private fun cancel(model: AppModel, original: String) {
    if (model.editingField == null) return
    if (model.editingField == "chNum") model.numberDraft = null
    if (model.editingField == "search") model.searchQuery = original
    model.editingField = null
}
