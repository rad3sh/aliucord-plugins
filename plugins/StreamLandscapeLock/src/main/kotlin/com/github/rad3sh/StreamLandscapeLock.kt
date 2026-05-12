package com.github.rad3sh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
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
import com.aliucord.utils.DimenUtils
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.voice.controls.VoiceControlsSheetView
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import java.lang.reflect.Field

@AliucordPlugin
class StreamLandscapeLock : Plugin() {

    private companion object {
        const val LOCK_BTN_TAG = "stream_landscape_lock_btn"
    }

    private var landscapeLocked = false
    private var callCount = 0

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

            val seq = ++callCount
            val isWatchingStream = stopWatchingBtn.visibility == View.VISIBLE
            val activity = findActivity(sheetView)

            // Dump full state on every call
            val reqOri = activity?.requestedOrientation ?: -999
            val cfgOri = activity?.resources?.configuration?.orientation ?: -999
            val cfgOriName = when (cfgOri) {
                Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
                Configuration.ORIENTATION_PORTRAIT  -> "PORTRAIT"
                else -> "UNDEFINED($cfgOri)"
            }
            val isChanging = activity?.isChangingConfigurations ?: false
            logger.info("#$seq configureUI: locked=$landscapeLocked  watching=$isWatchingStream  reqOri=$reqOri  cfgOri=$cfgOriName  isChangingConfigs=$isChanging  activity=${activity?.javaClass?.simpleName}")

            // Auto-restore orientation when stream focus is lost while locked
            if (!isWatchingStream && landscapeLocked) {
                logger.info("#$seq configureUI: stream NOT visible & locked \u2192 restoring FULL_USER")
                landscapeLocked = false
                logger.info("#$seq configureUI: calling setRequestedOrientation(FULL_USER=13)")
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }

            // Re-apply landscape lock when the Activity was recreated.
            // Check ACTUAL display orientation via configuration, not requestedOrientation
            // (manifest declares screenOrientation="fullUser" value=13, every new Activity
            // returns 13 which is != LANDSCAPE=0, causing infinite recreation if we use that).
            if (landscapeLocked && isWatchingStream && activity != null) {
                val isLandscape = cfgOri == Configuration.ORIENTATION_LANDSCAPE
                logger.info("#$seq configureUI: lock reassert check: isLandscape=$isLandscape (cfgOri=$cfgOriName)")
                if (!isLandscape) {
                    logger.info("#$seq configureUI: screen is portrait \u2192 calling setRequestedOrientation(SENSOR_LANDSCAPE=6)")
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    logger.info("#$seq configureUI: already landscape, no setRequestedOrientation call")
                }
            }

            // Guard against double-injection — tag is preserved across re-calls
            var lockBtn = sheetView.findViewWithTag<View?>(LOCK_BTN_TAG) as? ImageButton

            if (lockBtn == null) {
                logger.info("#$seq configureUI: injecting lock button (isWatchingStream=$isWatchingStream)")
                lockBtn = createLockButton(sheetView, stopWatchingBtn) ?: return@Hook
            } else {
                logger.info("#$seq configureUI: lock button already present, skipping injection")
            }

            lockBtn.visibility = if (isWatchingStream) View.VISIBLE else View.GONE
            if (isWatchingStream) {
                updateButtonAppearance(lockBtn, landscapeLocked, sheetView.context)
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
                val changing = activity.isChangingConfigurations
                val reqOri = activity.requestedOrientation
                val cfgOri = activity.resources.configuration.orientation
                logger.info("onDestroy: isChangingConfigurations=$changing  landscapeLocked=$landscapeLocked  reqOri=$reqOri  cfgOri=$cfgOri")
                if (!changing) {
                    logger.info("onDestroy: real exit \u2192 calling setRequestedOrientation(FULL_USER=13)")
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                    landscapeLocked = false
                } else {
                    logger.info("onDestroy: config change recreation, preserving landscapeLocked=$landscapeLocked")
                }
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        landscapeLocked = false
        callCount = 0
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
            setImageDrawable(createLandscapeIconDrawable(ctx))
            contentDescription = "Lock to landscape"

            // Clone the circular background from the stopWatchingButton so our
            // button inherits the same shape without needing the style resource.
            val bgDrawable = stopWatchingBtn.background
                ?.constantState?.newDrawable()?.mutate()
            background = bgDrawable

            // Match the size of the existing button; add a small leading margin.
            val sourceLp = stopWatchingBtn.layoutParams
            val lp = LinearLayout.LayoutParams(sourceLp.width, sourceLp.height)
            lp.marginStart = DimenUtils.dpToPx(8)
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
                val cfgOriClick = activity?.resources?.configuration?.orientation
                logger.info("CLICK: landscapeLocked=$landscapeLocked  setting orientation=$orientation  cfgOri=$cfgOriClick  activity=${activity?.javaClass?.simpleName}")
                logger.info("CLICK: calling setRequestedOrientation($orientation)")
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

    /** Draws a landscape-screen + padlock icon using Canvas — no resource lookup needed. */
    private fun createLandscapeIconDrawable(ctx: Context): BitmapDrawable {
        val dp = DimenUtils.dpToPx(1).toFloat()
        val size = DimenUtils.dpToPx(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
        }

        // Landscape screen frame (stroke rectangle)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * dp
        canvas.drawRoundRect(RectF(1f * dp, 5f * dp, 23f * dp, 19f * dp), 1.5f * dp, 1.5f * dp, paint)

        // Lock body (filled rounded rect)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(7.75f * dp, 11f * dp, 15.25f * dp, 16f * dp), 0.75f * dp, 0.75f * dp, paint)

        // Lock shackle (top arc)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * dp
        canvas.drawArc(RectF(9f * dp, 8f * dp, 13f * dp, 12f * dp), 180f, 180f, false, paint)

        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun updateButtonAppearance(btn: ImageButton, isActive: Boolean, ctx: Context) {
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
