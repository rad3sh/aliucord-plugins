package com.github.rad3sh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.voice.controls.VoiceControlsSheetView
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import java.lang.reflect.Field

@AliucordPlugin
class FullscreenLock : Plugin() {

    private companion object {
        const val LOCK_BTN_TAG = "fullscreen_lock_btn"
    }

    private var landscapeLocked = false

    // Cached reflection fields — populated lazily on first call
    private var bindingField: Field? = null
    private var swBtnField: Field? = null

    override fun start(context: Context) {
        // Hook 0 — Intercept Activity.setRequestedOrientation to prevent Discord from
        // resetting orientation back to fullUser while we have the landscape lock active.
        // Discord calls setRequestedOrientation in AppActivity.onCreate which fights our lock.
        val setOrientationMethod = Activity::class.java
            .getDeclaredMethod("setRequestedOrientation", Int::class.javaPrimitiveType!!)
            .also { it.isAccessible = true }
        patcher.patch(setOrientationMethod, PreHook { param ->
            if (!landscapeLocked) return@PreHook
            val act = param.thisObject as? Activity ?: return@PreHook
            // Only intercept the Call activity — leave all other activities untouched
            if (act.javaClass.name != "com.discord.app.AppActivity\$Call") return@PreHook
            val requested = param.args[0] as Int
            if (requested != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                logger.info("INTERCEPT setRequestedOrientation($requested) \u2192 SENSOR_LANDSCAPE (caller=${act.javaClass.simpleName})")
                param.args[0] = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        })

        // Hook A — VoiceControlsSheetView.configureUI-3jxq49Y (PostHook)
        // This method is called every time the voice controls UI state changes.
        // We read the resulting visibility of stopWatchingButton (binding.t) to
        // determine whether the user is currently watching a stream.
        val configureUiMethod = VoiceControlsSheetView::class.java.declaredMethods
            .firstOrNull { it.name == "configureUI-3jxq49Y" }
            ?.also { it.isAccessible = true }
            ?: return

        patcher.patch(configureUiMethod, Hook { param ->
            val sheetView = param.thisObject as VoiceControlsSheetView

            // Lazily cache the binding field
            if (bindingField == null) {
                bindingField = VoiceControlsSheetView::class.java
                    .getDeclaredField("binding")
                    .also { it.isAccessible = true }
            }
            val binding = bindingField!!.get(sheetView) ?: run {
                logger.warn("configureUI: binding is null")
                return@Hook
            }

            // Lazily cache VoiceControlsSheetViewBinding.t (stopWatchingButton)
            if (swBtnField == null) {
                swBtnField = binding.javaClass
                    .getDeclaredField("t")
                    .also { it.isAccessible = true }
            }
            val stopWatchingBtn = swBtnField!!.get(binding) as? View ?: run {
                logger.warn("configureUI: stopWatchingBtn is null")
                return@Hook
            }

            val isWatchingStream = stopWatchingBtn.visibility == View.VISIBLE
            val activity = findActivity(sheetView)
            val cfgOri = activity?.resources?.configuration?.orientation ?: -999

            // Auto-restore orientation when stream focus is lost while locked
            if (!isWatchingStream && landscapeLocked) {
                logger.info("configureUI: stream lost \u2192 restoring FULL_USER")
                landscapeLocked = false
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }

            // Re-apply landscape lock when the Activity was recreated.
            // Check ACTUAL display orientation via configuration, not requestedOrientation
            // (manifest declares screenOrientation="fullUser" value=13, every new Activity
            // returns 13 which is != LANDSCAPE=0, causing infinite recreation if we use that).
            if (landscapeLocked && isWatchingStream && activity != null) {
                if (cfgOri != Configuration.ORIENTATION_LANDSCAPE) {
                    logger.info("configureUI: portrait while locked \u2192 setRequestedOrientation(SENSOR_LANDSCAPE)")
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            // Guard against double-injection — tag is preserved across re-calls
            var lockBtn = sheetView.findViewWithTag<View?>(LOCK_BTN_TAG) as? ImageButton

            if (lockBtn == null) {
                logger.info("configureUI: injecting lock button")
                lockBtn = createLockButton(sheetView, stopWatchingBtn) ?: return@Hook
            }

            lockBtn.visibility = if (isWatchingStream) View.VISIBLE else View.GONE
            if (isWatchingStream) {
                updateButtonAppearance(lockBtn, landscapeLocked, sheetView.context)

                // Count how many buttons are currently visible in the row (excluding ours).
                // Only demote a button if we would otherwise exceed 4 visible top-row buttons.
                // When the video/camera button is hidden there is already a free slot —
                // in that case no demotion is needed.
                val buttonsRow = lockBtn.parent as? ViewGroup ?: (stopWatchingBtn.parent as? ViewGroup)
                var visibleTopCount = 0
                if (buttonsRow != null) {
                    for (i in 0 until buttonsRow.childCount) {
                        val child = buttonsRow.getChildAt(i)
                        if (child !== lockBtn && child.visibility == View.VISIBLE) visibleTopCount++
                    }
                }
                if (visibleTopCount >= 4) {
                    val res     = sheetView.context.resources
                    val ssTopId = res.getIdentifier("screen_share_button",           "id", "com.discord")
                    val ssSecId = res.getIdentifier("screen_share_secondary_button", "id", "com.discord")
                    val aoTopId = res.getIdentifier("audio_output_container",        "id", "com.discord")
                    val aoSecId = res.getIdentifier("audio_output_secondary_button", "id", "com.discord")
                    val ssTop   = sheetView.findViewById<View>(ssTopId)
                    val aoTop   = sheetView.findViewById<View>(aoTopId)
                    when {
                        ssTop?.visibility == View.VISIBLE -> {
                            ssTop.visibility = View.GONE
                            sheetView.findViewById<View>(ssSecId)?.visibility = View.VISIBLE
                        }
                        aoTop?.visibility == View.VISIBLE -> {
                            aoTop.visibility = View.GONE
                            sheetView.findViewById<View>(aoSecId)?.visibility = View.VISIBLE
                        }
                    }
                    // Do NOT set secondary_actions_card visibility here.
                    // handleSheetState() owns that: it shows the card when the sheet
                    // expands and hides it when the sheet collapses (state == 4).
                    // Forcing VISIBLE here would undo handleSheetState's INVISIBLE on collapse.
                }
            }
        })

        // Hook B — WidgetCallFullscreen.onDestroy (PostHook)
        // Restore free rotation when the call screen is exited.
        // Skip when the activity is only being recreated for a config change
        // (e.g., the orientation flip we triggered ourselves).
        patcher.patch(
            WidgetCallFullscreen::class.java.getDeclaredMethod("onDestroy"),
            Hook { param ->
                val fragment = param.thisObject as? Fragment ?: return@Hook
                val activity = fragment.activity ?: run {
                    logger.warn("onDestroy: activity is null  landscapeLocked=$landscapeLocked")
                    return@Hook
                }
                if (!activity.isChangingConfigurations) {
                    logger.info("onDestroy: real exit \u2192 restoring FULL_USER")
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                    landscapeLocked = false
                }
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        landscapeLocked = false
    }

    // ── Button creation ────────────────────────────────────────────────────────

    private fun createLockButton(
        sheetView: VoiceControlsSheetView,
        stopWatchingBtn: View,
    ): ImageButton? {
        val parent = stopWatchingBtn.parent as? ViewGroup ?: return null
        val ctx = sheetView.context

        val btn = ImageButton(ctx).apply {
            tag = LOCK_BTN_TAG
            contentDescription = "Lock to landscape"

            // Clone the circular background from the stopWatchingButton so our
            // button inherits the same shape without needing the style resource.
            val bgDrawable = stopWatchingBtn.background
                ?.constantState?.newDrawable()?.mutate()
            background = bgDrawable

            // Match size and margins of the existing button exactly.
            val sourceLp = stopWatchingBtn.layoutParams
            val lp = LinearLayout.LayoutParams(sourceLp.width, sourceLp.height)
            if (sourceLp is ViewGroup.MarginLayoutParams) {
                lp.leftMargin   = sourceLp.leftMargin
                lp.rightMargin  = sourceLp.rightMargin
                lp.topMargin    = sourceLp.topMargin
                lp.bottomMargin = sourceLp.bottomMargin
            }
            layoutParams = lp

            setOnClickListener {
                landscapeLocked = !landscapeLocked
                // LANDSCAPE (0) = fixed landscape regardless of sensor / system rotation lock.
                // SENSOR_LANDSCAPE would oscillate when the sensor shows portrait.
                val orientation = if (landscapeLocked)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else
                    ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                val activity = findActivity(this)
                logger.info("CLICK: locked=$landscapeLocked  orientation=$orientation")
                updateButtonAppearance(this, landscapeLocked, ctx)
                activity?.requestedOrientation = orientation
            }
        }

        updateButtonAppearance(btn, landscapeLocked, ctx)

        // Insert immediately after stopWatchingButton.
        // Use a plain for-loop (not IntRange HOF) per pitfall rules.
        var idx = -1
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i) === stopWatchingBtn) {
                idx = i
                break
            }
        }
        if (idx >= 0) parent.addView(btn, idx + 1) else parent.addView(btn)

        return btn
    }

    // ── Appearance helpers ─────────────────────────────────────────────────────

    private fun updateButtonAppearance(btn: ImageButton, isActive: Boolean, ctx: Context) {
        // Switch icon: fullscreen-enter (unlocked) ↔ fullscreen-exit (locked)
        val iconName = if (isActive) "exo_ic_fullscreen_exit" else "exo_ic_fullscreen_enter"
        val iconId = ctx.resources.getIdentifier(iconName, "drawable", "com.discord")
        if (iconId != 0) btn.setImageDrawable(ctx.resources.getDrawable(iconId, ctx.theme))

        val res = ctx.resources

        val whiteId = res.getIdentifier("white", "color", "com.discord")
        val whiteAlpha24Id = res.getIdentifier("white_alpha_24", "color", "com.discord")

        val bgColor = if (isActive && whiteId != 0) {
            ColorCompat.getColor(ctx, whiteId)
        } else if (!isActive && whiteAlpha24Id != 0) {
            ColorCompat.getColor(ctx, whiteAlpha24Id)
        } else {
            if (isActive) 0xFFFFFFFF.toInt() else 0x3DFFFFFF
        }
        btn.backgroundTintList = ColorStateList.valueOf(bgColor)

        val iconColor = if (isActive) {
            val tv = TypedValue()
            val attrId = res.getIdentifier(
                "call_controls_active_button_icon_color", "attr", "com.discord"
            )
            if (attrId != 0 && ctx.theme.resolveAttribute(attrId, tv, true) && tv.resourceId != 0) {
                ColorCompat.getColor(ctx, tv.resourceId)
            } else {
                0xFF1E1F22.toInt() // fallback dark colour
            }
        } else {
            if (whiteId != 0) ColorCompat.getColor(ctx, whiteId) else 0xFFFFFFFF.toInt()
        }
        btn.imageTintList = ColorStateList.valueOf(iconColor)
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private fun findActivity(view: View): Activity? {
        var ctx = view.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
