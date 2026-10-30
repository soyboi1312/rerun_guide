package com.rerunguide.app

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import java.io.File
import org.json.JSONObject
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rerunguide.core.ClientDevice
import kotlinx.coroutines.launch

/** The process's one model (the Swift `@State private var model = AppModel()` on the App): it outlives an Activity
 *  that the system recreates, like the app's state outlives a scene. */
private var sharedModel: AppModel? = null

/** RerunGuideApp + the RemoteCaptureView wiring: the remote (or a keyboard) drives `model.press`, Back at the top
 *  level goes to the system (Home), and the app's background/foreground transitions reach the model. */
class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private lateinit var remote: RemoteCapture
    /** The TV switched input or turned off under a Fire TV / Google TV box (its HDMI audio output went away): pause, as
     *  Play/Pause does, so live becomes time-shifted and it stays paused until the viewer presses Play (Amazon's 2a.19). */
    private val hdmi = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.none { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC }) return
            if (model.screen == Screen.watch && model.playback?.paused == false) model.togglePause()
        }
    }
    /** Phones and tablets: Wi-Fi to cellular or another Wi-Fi while the app is open (the Swift NWPathMonitor). The
     *  default network's onAvailable comes first for the one in use, then for each new one; onLost when it goes. */
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { model.pathChanged(network.toString()) }
        override fun onLost(network: Network) { model.pathChanged(null) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Once per process, at its first (full-screen) launch: a relaunch inside the picture-in-picture window (a font or
        // language change) would measure the small window and call a tablet a phone.
        if (sharedModel == null) Platform.init(this, forceTV = BuildConfig.DEBUG && intent.getBooleanExtra("forceTV", false),
            forceFireTV = BuildConfig.DEBUG && intent.getBooleanExtra("forceFireTV", false))
        Keychain.init(applicationContext)
        if (BuildConfig.DEBUG) importTestSeed()
        ClientDevice.device = if (Platform.isTV) Platform.device else "Android ${Platform.device}"
        decoderCodecs()
        model = sharedModel ?: AppModel(applicationContext, if (BuildConfig.DEBUG) intent.extras else null).also { sharedModel = it }
        if (Platform.isTV) getSystemService(AudioManager::class.java).registerAudioDeviceCallback(hdmi, null)   // null: main thread
        else getSystemService(ConnectivityManager::class.java).run {
            // With no network at all the callback says nothing, so the first one to arrive must not pass as the start's.
            if (activeNetwork == null) model.pathChanged(null)
            registerDefaultNetworkCallback(network, Handler(Looper.getMainLooper()))
        }

        // The stage owns the whole screen, like tvOS: no status or navigation bar (a swipe shows them for a moment).
        // Portrait shows them, as the iOS handoff does.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        lifecycleScope.launch { snapshotFlow { model.portrait }.collect { hideSystemBars() } }
        // As on iPhone and iPad (project.yml, OrientationLock): tablets turn freely (the stage letterboxes in portrait where
        // a screen has no portrait layout); phones turn to portrait only where it has one. Set here, not in the manifest:
        // Play flags a manifest lock for large screens (Android 16 ignores it there anyway).
        // USER on phones: no upside-down portrait where the phone doesn't allow it, as iPhone's allButUpsideDown.
        if (!Platform.isTV) lifecycleScope.launch {
            snapshotFlow { model.portraitReady }.collect {
                requestedOrientation = when {
                    Platform.device == "tablet" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                    it -> ActivityInfo.SCREEN_ORIENTATION_USER
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }

        // The remote while no text field is up (the text-entry dialog takes the keys meanwhile); Back passes to the
        // system at the top level on TV, which leaves for the Home screen as tvOS does with Menu; on a phone or tablet
        // every Back is the app's (Back on the drifting guide opens Options, like the edge swipe).
        // Portrait's Lineup is the top of a phone app (no Options menu to open), so Back there leaves, as on TV.
        remote = RemoteCapture(isActive = { model.editingField == null }, passBack = { (Platform.isTV || model.portrait) && model.backExitsApp }, onCommand = { model.press(it) },
            onDigit = { model.pressDigit(it) }, playerPaused = { if (model.screen == Screen.watch && model.overlay == null) model.playback?.paused == true else null })
        remote.install(this)

        // Phones and tablets: going Home while a program plays keeps it playing in a picture-in-picture window, which shows
        // the picture alone. Android 12+ enters it itself, mid-swipe, while auto-enter is on (kept in step with the screen
        // here); older versions ask in onUserLeaveHint. The activity stays started in the window, so onStop (and the
        // stream's stop) comes only when the window is closed. Activity skips devices without the feature itself; the
        // runCatching is for those that declare it with picture-in-picture off (low-RAM builds), where the calls throw.
        addOnPictureInPictureModeChangedListener { model.pip = it.isInPictureInPictureMode }
        if (Build.VERSION.SDK_INT >= 31 && !Platform.isTV)
            lifecycleScope.launch { snapshotFlow { watching }.collect { runCatching { setPictureInPictureParams(pipParams().setAutoEnterEnabled(it).build()) } } }

        // tvOS keeps the screen awake only while video actually plays: the player and the guide's preview each hold it
        // themselves (View.keepScreenOn from ExoPlayer's isPlaying, in WatchScreen and GuideScreen).

        setContent {
            CompositionLocalProvider(LocalModel provides model, LocalTheme provides model.theme) { RootView() }
        }
        // RootView paints every pixel, so the window's own background is one full-screen fill per frame for nothing.
        window.setBackgroundDrawable(null)
        // The first D-pad key after a touch leaves touch mode, and when no view has focus ViewRootImpl spends that key
        // giving one focus (EarlyPostImeInputStage → checkForLeavingTouchModeAndConsume → leaveTouchMode →
        // restoreDefaultFocus, here a Compose clickable) before dispatchKeyEvent ever sees it. A focused plain container
        // that keeps focus in touch mode makes leaveTouchMode a no-op (a focused ViewGroup that isn't
        // FOCUS_AFTER_DESCENDANTS keeps it), so every press reaches the remote, as on tvOS/iPhone.
        findViewById<View>(android.R.id.content).apply { isFocusableInTouchMode = true; requestFocus() }
    }

    /** Debug builds only: `files/seed.json` ({"persisted": {…}, "token": "…"}, written with `adb shell run-as`) signs this
     *  install in to a test server without typing a password, as a prefs plist does for the Apple TV simulator. Read once,
     *  then deleted along with the library cache (it belongs to whatever server was there before). */
    private fun importTestSeed() {
        val seed = File(filesDir, "seed.json")
        if (!seed.exists()) return
        runCatching {
            val o = JSONObject(seed.readText())
            o.optString("token").takeIf { it.isNotEmpty() }?.let { Keychain.set(it, "token") }
            o.optJSONObject("persisted")?.let {
                getSharedPreferences("defaults", MODE_PRIVATE).edit().putString("persisted", it.toString()).commit()
            }
        }
        seed.delete()
        File(cacheDir, "library.json").delete()
    }

    /** Tell the servers which codecs this device decodes (an Apple TV decodes them all; Android devices vary). */
    private fun decoderCodecs() {
        val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
            .flatMap { it.supportedTypes.map(String::lowercase) }.toSet()
        fun pick(vararg codecs: Pair<String, String>) = codecs.filter { it.second in types }.map { it.first }
        val audio = listOf("aac") + pick("ac3" to "audio/ac3", "eac3" to "audio/eac3")
        ClientDevice.audioCodecs = audio.joinToString(",")
        ClientDevice.directPlayAudioCodecs = (audio + pick("alac" to "audio/alac", "flac" to "audio/flac")).joinToString(",")
        // HEVC only when a decoder does Main10: the servers direct-stream any HEVC we claim, and most HEVC libraries are
        // 10-bit (the emulator's 8-bit-only c2.android.hevc.decoder failed on them). ponytail: an 8-bit-only device
        // transcodes 8-bit HEVC too; add a bit-depth condition to the device profiles if such devices matter.
        val hevc10 = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { type ->
                type.equals("video/hevc", ignoreCase = true) &&
                    info.getCapabilitiesForType(type).profileLevels.any { it.profile == CodecProfileLevel.HEVCProfileMain10 }
            }
        }
        ClientDevice.videoCodecs = if (hevc10) "h264,hevc" else "h264"
        @Suppress("DEPRECATION")   // Activity.display is API 30; minSdk is 26
        ClientDevice.hdr = windowManager.defaultDisplay.isHdr
    }

    // Portrait's screens are touch only, as on iPhone: keys would move the hidden TV guide's focus. Back still arrives
    // through RemoteCapture.install's callback; a headset's Play/Pause still pauses Now playing, and elsewhere is
    // swallowed rather than starting another app's media.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = when {
        !model.portrait -> remote.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        event.keyCode in playKeys -> model.screen != Screen.watch || remote.dispatchKeyEvent(event)
        else -> super.dispatchKeyEvent(event)
    }
    private val playKeys = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)

    /** Re-hidden whenever the window gets focus back (a fresh launch, the text-entry dialog closing, a transient swipe),
     *  which can otherwise leave the navigation bar showing and inset the stage. */
    private fun hideSystemBars() = WindowInsetsControllerCompat(window, window.decorView).run {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false   // both themes are dark
        if (model.portrait) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars() }
    override fun onResume() { super.onResume(); hideSystemBars() }
    // The Swift scenePhase .active / .background.
    override fun onStart() { super.onStart(); model.returnedToForeground() }
    override fun onStop() { super.onStop(); model.enteredBackground() }

    /** A program playing on a phone or tablet (never TV): leaving keeps it playing in a picture-in-picture window. A paused
     *  one leaves as before: the window has no play button to bring it back. */
    private val watching get() = !Platform.isTV && model.screen == Screen.watch && model.playback?.paused == false
    private fun pipParams() = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))   // the stage's shape
    // Android 8 to 11 (no auto-enter).
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT < 31 && watching) runCatching { enterPictureInPictureMode(pipParams().build()) } }
    override fun onDestroy() {
        if (Platform.isTV) getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(hdmi)
        else getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        super.onDestroy()
    }
}
