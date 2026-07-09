package com.mappo

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.themestudio.core.ThemeStudioProvider
import com.themestudio.persistence.SharedPrefsThemeOverridesStorage
import dagger.hilt.android.AndroidEntryPoint
import com.mappo.service.input.InputDispatcher
import com.mappo.ui.screen.MainScreen
import com.mappo.ui.screen.home.HomeBackdrop
import com.mappo.ui.theme.MappoTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var inputDispatcher: InputDispatcher

    // Deep-route request from the toolbar overlay (OVERLAY_TOOLBAR_PLAN.md, Brick 2). The
    // overlay launches us with EXTRA_ROUTE naming a NavHost destination; MainScreen navigates
    // there off the nonce. The nonce (not the route string) keys the navigation so re-tapping
    // the same destination after backing out re-navigates — a plain String wouldn't re-fire.
    private var pendingRoute by mutableStateOf<String?>(null)
    private var routeNonce by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureBackdropIfStale()
        consumeRouteExtra(intent)
        // Fully immersive, GameNative-style: the system bars are HIDDEN by default and Mappo
        // uses the entire screen (incl. the display cutout). A swipe from the edge reveals the
        // bars transiently, OVER the content, without shifting layout — so the Edit Overlay
        // coordinate space matches a real fullscreen game.
        //
        // Why immersive and not plain edge-to-edge: the window is translucent (so the home
        // drawer reveals the app behind), and a translucent activity is laid out within the
        // content frame — it's exempt from edge-to-edge enforcement and can't draw under the
        // bars no matter the flags. Hiding the bars sidesteps that: there are no bar insets,
        // so the content frame becomes the whole display. FLAG_LAYOUT_NO_LIMITS pins the frame
        // to the full display (incl. cutout); enableEdgeToEdge keeps the bars transparent for
        // the moments they transiently appear. See `hideSystemBars` + onWindowFocusChanged.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hideSystemBars()

        // The home is a translucent window over the foregrounded app, so the default "scale up
        // from the launcher icon" open animation looks wrong. Fade the (mostly transparent) window
        // — and its bottom toolbar — in and out instead, while the app behind holds still (hold).
        // API 34+ only; older devices keep the platform default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, R.anim.hold)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.hold, android.R.anim.fade_out)
        }

        setContent {
            val themeStorage = remember { SharedPrefsThemeOverridesStorage(applicationContext) }
            ThemeStudioProvider(storage = themeStorage) {
                MappoTheme {
                    MainScreen(deepLinkRoute = pendingRoute, deepLinkNonce = routeNonce)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a re-launch (e.g. the overlay firing a new deep-route intent while we're
        // already alive) arrives here, not onCreate. Re-point getIntent() and consume the route.
        setIntent(intent)
        captureBackdropIfStale()
        consumeRouteExtra(intent)
    }

    /**
     * Launcher-icon / deep-link path for the home's blurred backdrop: fire a screenshot right
     * away, before our first frame lands (the window fades in from transparent, so an early
     * capture is near-clean). Skipped when the Select+A chord just captured — that shot was
     * taken BEFORE launch and must not be replaced by one that may include our own window.
     */
    private fun captureBackdropIfStale() {
        if (HomeBackdrop.isFresh()) return
        inputDispatcher.captureScreenshot { HomeBackdrop.setFrom(it) }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
    }

    private fun consumeRouteExtra(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_ROUTE) ?: return
        pendingRoute = route
        routeNonce++
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive isn't sticky across focus changes (the transient swipe, returning from
        // another activity, dialogs), so re-hide whenever we regain focus.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        /** Intent extra (a [com.mappo.ui.nav.MappoRoute] string) launching us straight to a deep screen. */
        const val EXTRA_ROUTE = "com.mappo.extra.ROUTE"

        /**
         * True while this activity is resumed. The accessibility service's Select+A home chord
         * reads it to decide between revealing the home (launching the activity) and toggling
         * the frame in place (emitting on [homeToggleRequests]).
         */
        @Volatile
        var inForeground: Boolean = false
            private set

        private val homeToggleFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** Chord-driven requests to toggle the home frame while foreground; MainScreen collects. */
        val homeToggleRequests: SharedFlow<Unit> = homeToggleFlow.asSharedFlow()

        fun requestHomeToggle() {
            homeToggleFlow.tryEmit(Unit)
        }
    }
}
