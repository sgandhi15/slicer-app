package com.example.slicer

import android.animation.ValueAnimator
import android.os.Bundle
import android.window.SplashScreenView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.fillMaxSize
import com.example.slicer.ui.theme.SlicerTheme
import com.example.slicer.home.SlicerHomeScreen

class MainActivity : ComponentActivity() {
    private var exitingSplash: SplashScreenView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restored activities do not get a new splash, so their content must already be visible.
        val welcomeEntranceReady = mutableStateOf(savedInstanceState != null)
        val welcomeLogoReady = mutableStateOf(savedInstanceState != null)
        val welcomeLogoStartBounds = mutableStateOf<Rect?>(null)
        var welcomeLogoBounds: Rect? = null
        splashScreen.setOnExitAnimationListener { splashView ->
            if (!ValueAnimator.areAnimatorsEnabled()) {
                welcomeLogoReady.value = true
                welcomeEntranceReady.value = true
                splashView.remove()
            } else {
                // Android may hand over the splash before its icon animation has finished.
                // Wait only for the remaining animation time, not another full second.
                val duration = splashView.iconAnimationDuration?.toMillis() ?: 0L
                val startedAt = splashView.iconAnimationStart?.toEpochMilli()
                val elapsed = startedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: duration
                val remaining = (duration - elapsed).coerceAtLeast(0L)
                splashView.postDelayed({
                    if (isFinishing || isDestroyed) {
                        splashView.remove()
                    } else {
                        playSplashHandoff(
                            splashView = splashView,
                            targetBounds = welcomeLogoBounds,
                            prepareLogoHandoff = { welcomeLogoStartBounds.value = it },
                            revealLogo = { welcomeLogoReady.value = true },
                            revealContent = { welcomeEntranceReady.value = true }
                        )
                    }
                }, remaining)
            }
        }
        setContent {
            SlicerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SlicerHomeScreen(
                        welcomeEntranceReady = welcomeEntranceReady.value,
                        welcomeLogoReady = welcomeLogoReady.value,
                        welcomeLogoStartBounds = welcomeLogoStartBounds.value,
                        onWelcomeLogoPositioned = { welcomeLogoBounds = it }
                    )
                }
            }
        }
    }

    private fun playSplashHandoff(
        splashView: SplashScreenView,
        targetBounds: Rect?,
        prepareLogoHandoff: (Rect) -> Unit,
        revealLogo: () -> Unit,
        revealContent: () -> Unit
    ) {
        val icon = splashView.iconView
        // Returning users go directly to setup, which has no welcome-logo destination.
        if (targetBounds == null || icon == null || icon.width == 0 || icon.height == 0) {
            revealLogo()
            revealContent()
            splashView.animate().alpha(0f).setDuration(180L)
                .withEndAction { splashView.remove() }.start()
            return
        }

        val iconLocation = IntArray(2)
        icon.getLocationInWindow(iconLocation)
        prepareLogoHandoff(
            Rect(
                iconLocation[0].toFloat(), iconLocation[1].toFloat(),
                (iconLocation[0] + icon.width).toFloat(), (iconLocation[1] + icon.height).toFloat()
            )
        )
        // Transfer at the stationary starting position. Compose moves the actual welcome logo,
        // so there is no second renderer, mask, or differently sized badge to swap in at the end.
        revealLogo()
        revealContent()
        exitingSplash = splashView
        splashView.animate().alpha(0f).setDuration(60L).withEndAction {
            splashView.remove()
            exitingSplash = null
        }.start()
    }

    override fun onDestroy() {
        exitingSplash?.let {
            it.animate().cancel()
            it.remove()
        }
        super.onDestroy()
    }
}
