package app.starter

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.starter.theme.AppTheme
import app.starter.ui.common.revokeUnpickedShare
import app.starter.ui.wipe.SchemaMismatchScreen

/**
 * AppCompatActivity rather than ComponentActivity, and for one reason only: it is where
 * `AppCompatDelegate` lives, and `AppCompatDelegate` is what applies a per-app language on the
 * pre-13 half of the supported range (ADR-0004) and what moves the night mode (ADR-0006). Nothing
 * else here uses AppCompat — no views, no action bar, no AppCompat widgets — and AppCompatActivity
 * is a ComponentActivity subclass, so `setContent`, the window itself and the activity-result APIs
 * all still work unchanged.
 */
class MainActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        revokeUnpickedShare()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as MainApplication

        // Edge-to-edge, and **not** `enableEdgeToEdge()`. Every path in androidx.activity's version
        // of that call — `EdgeToEdgeApi35` included — reaches `Window.setStatusBarColor` and
        // `setNavigationBarColor`, both deprecated in Android 15, and Play flags apps that call
        // them. There is no version of the call that avoids it, so what it did is split in two:
        // this line, which is all Compose actually depends on, and the bar colours, which live in
        // `themes.xml` where an attribute is not a deprecated method. `values/colors.xml` carries
        // the reasoning and `AppTheme` writes the icon appearance at runtime.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // **Nobody pans this window; the content pads itself.**
        //
        // The manifest asks for `adjustResize`, and on API 26–29 that is the only thing that works:
        // `WindowInsets.ime` is not reported before API 30, so the older half of the supported range
        // depends on the window actually being resized. From API 30 the same request is inert — the
        // line above sets `decorFitsSystemWindows = false`, and the window manager downgrades the
        // resize to a *pan*. Panning is worse than doing nothing: with the keyboard open on a long
        // form it slides the top of the form under the status bar and carries the top app bar, Save
        // button and all, off the top of the screen.
        //
        // So on API 30+ the system is told to do neither, and `Modifier.imePadding()` in
        // `Navigation.kt` handles the keyboard as the inset it now is. Set here rather than in the
        // manifest because the manifest cannot say "only on new enough Android", and the old
        // behaviour is still load-bearing below 30.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }

        setContent {
            // Read from the application, never from `app.container` — that property is the `lazy`
            // that *is* ADR-0001's wipe guard, and the theme below wraps the schema-mismatch screen
            // as well as the app, so forcing it here would open the gate from inside the thing
            // standing in front of it.
            //
            // Kotlin note: a plain `Flow` has no current value the way a `StateFlow` does, so
            // collecting one as state needs an initial. `false` is also the stored default, which is
            // what keeps the first frame from being the wrong palette and then repainting.
            val materialYou by app.preferences.materialYou.collectAsStateWithLifecycle(initialValue = false)
            // The initial value is the one `onCreate` already read off disk, not `SYSTEM`: this
            // flow's first emission arrives after the first composition, so a stored `DARK` would
            // otherwise light-flash on every cold start. `MainApplication` paid for that read before
            // any Activity existed; spending it again here is free.
            val themeMode by
                app.preferences.themeMode.collectAsStateWithLifecycle(initialValue = app.startupThemeMode)

            AppTheme(themeMode = themeMode, dynamicColor = materialYou) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val schemaMismatch by app.schemaMismatch.collectAsStateWithLifecycle()

                    // Kotlin note: assigned to a local first because smart-casting a `var` read from
                    // another object is not allowed — the compiler cannot prove it has not changed
                    // between the null check and the use. A local `val` it can.
                    val mismatch = schemaMismatch
                    if (mismatch != null) {
                        // The guard is structural: `MainNavigation` is what first reads
                        // `AppContainer`, so not composing it is what keeps Room out of existence.
                        SchemaMismatchScreen(mismatch = mismatch, onContinue = app::consentToWipe)
                    } else {
                        MainNavigation()
                    }
                }
            }
        }
    }
}
