package com.nousresearch.hermes.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge inset handling.
 *
 * Why this is needed: `targetSdk 35` (Android 15) enforces edge-to-edge. The window
 * extends behind the status bar / navigation bar and `fitsSystemWindows` no longer pads
 * content clear of them. On the test device the display cutout is 124px tall, so a
 * Toolbar at y=0 renders *under* the status bar.
 *
 * Two traps this helper exists to avoid:
 *  1. Do NOT apply the top inset to a Toolbar that was passed to `setSupportActionBar()`
 *     — AppCompat owns that view's padding and resets it, silently dropping the inset.
 *     Apply to a container the Activity owns instead.
 *  2. Do NOT leave `decorFitsSystemWindows` at its default; the framework would also pad
 *     the decor, double-counting the inset with ours.
 */
object Insets {

    /**
     * Opts the Activity into full edge-to-edge drawing *without* framework decor padding,
     * so the app is the single owner of inset handling. Call before `setContentView`.
     */
    fun enableEdgeToEdge(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    }

    /**
     * Pads [view] by the system-bar (incl. display cutout) insets on the requested edges.
     *
     * The view's padding at call time is captured as the baseline, so XML-declared
     * padding is preserved. The handled edges are then consumed so descendants do not
     * apply them a second time.
     */
    fun pad(
        view: View,
        left: Boolean = false,
        top: Boolean = false,
        right: Boolean = false,
        bottom: Boolean = false,
    ) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            v.updatePadding(
                left = baseLeft + if (left) bars.left else 0,
                top = baseTop + if (top) bars.top else 0,
                right = baseRight + if (right) bars.right else 0,
                bottom = baseBottom + if (bottom) bars.bottom else 0,
            )

            // Rebuild the inset set, clearing only the edges we consumed. Using
            // WindowInsetsCompat.CONSUMED here would swallow the IME inset too and break
            // adjustResize keyboard handling for the message input.
            var remaining = windowInsets
            if (left) remaining = remaining.inset(bars.left, 0, 0, 0)
            if (top) remaining = remaining.inset(0, bars.top, 0, 0)
            if (right) remaining = remaining.inset(0, 0, bars.right, 0)
            if (bottom) remaining = remaining.inset(0, 0, 0, bars.bottom)
            remaining
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** Pads only the top by the status-bar/cutout inset (header container). */
    fun padTop(view: View) = pad(view, top = true)

    /** Pads only the bottom by the navigation-bar inset (bottom-docked input row). */
    fun padBottom(view: View) = pad(view, bottom = true)

    /** Pads top and bottom — the usual case for a full-screen content root. */
    fun padVertical(view: View) = pad(view, top = true, bottom = true)
}
