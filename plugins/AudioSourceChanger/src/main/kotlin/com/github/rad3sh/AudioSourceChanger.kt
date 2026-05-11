package com.rad3sh.audiosourcechanger

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaRecorder
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.*
import androidx.fragment.app.Fragment
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.DimenUtils
import com.aliucord.widgets.BottomSheet
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.webrtc.audio.JavaAudioDeviceModule

private val SOURCES = linkedMapOf(
    "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    "MIC"                 to MediaRecorder.AudioSource.MIC,
    "VOICE_RECOGNITION"   to MediaRecorder.AudioSource.VOICE_RECOGNITION,
    "UNPROCESSED"         to MediaRecorder.AudioSource.UNPROCESSED,
    "CAMCORDER"           to MediaRecorder.AudioSource.CAMCORDER,
    "DEFAULT"             to MediaRecorder.AudioSource.DEFAULT,
)

private const val PREF_KEY = "audio_source"
private const val ROW_TAG  = "audio_source_row"

private val SOURCE_DESCRIPTIONS = mapOf(
    "VOICE_COMMUNICATION" to "Tuned for VoIP. Uses echo cancellation and automatic gain control if available. (default)",
    "MIC"                 to "Standard microphone source with no additional processing.",
    "VOICE_RECOGNITION"   to "Tuned for voice recognition. Disables AGC and noise suppression.",
    "UNPROCESSED"         to "Raw microphone audio with minimal system processing if available.",
    "CAMCORDER"           to "Tuned for video recording, oriented with the camera if available.",
    "DEFAULT"             to "System default audio source.",
)

private val SOURCE_DISPLAY_NAMES = mapOf(
    "VOICE_COMMUNICATION" to "Voice communication",
    "MIC"                 to "Mic",
    "VOICE_RECOGNITION"   to "Voice recognition",
    "UNPROCESSED"         to "Unprocessed",
    "CAMCORDER"           to "Camcorder",
    "DEFAULT"             to "Default",
)

// Clones textColors, textSize and typeface from a native TextView to another
private fun cloneText(src: TextView, dst: TextView) {
    dst.setTextColor(src.textColors)
    dst.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize)
    dst.typeface = src.typeface
}

private fun resId(res: android.content.res.Resources, name: String, pkg: String): Int {
    var id = res.getIdentifier(name, "id", "com.discord")
    if (id == 0) id = res.getIdentifier(name, "id", pkg)
    return id
}

// ─── Selector sheet ───────────────────────────────────────────────────────────
class AudioSourceSelectorSheet : BottomSheet() {

    companion object {
        lateinit var pluginSettings: com.aliucord.api.SettingsAPI
        var onSourceChanged: ((Int) -> Unit)? = null
        var onRestartSensitivity: (() -> Unit)? = null
    }

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx  = requireContext()
        val dp16 = DimenUtils.dpToPx(16)

        // ── Resolve colors/sizes from Discord theme (UiKit.Settings.Item.Label / .Addition) ──
        fun resolveAttrColor(name: String): Int? {
            val id = ctx.resources.getIdentifier(name, "attr", "com.discord")
            if (id == 0) return null
            val tv = TypedValue()
            if (!ctx.theme.resolveAttribute(id, tv, true)) return null
            // If the attr resolves to a direct color int, return it directly.
            // If it resolves to an @color/ reference (ColorStateList or named color), load the resource.
            return when {
                tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT -> tv.data
                tv.resourceId != 0 -> runCatching {
                    ContextCompat.getColorStateList(ctx, tv.resourceId)?.defaultColor
                }.getOrNull()
                else -> null
            }
        }
        fun resolveDimenPx(name: String): Float? {
            val id = ctx.resources.getIdentifier(name, "dimen", "com.discord")
            return if (id != 0) ctx.resources.getDimensionPixelSize(id).toFloat() else null
        }
        // UiKit.Settings → ?attr/settings_text_color ; UiKit.Settings.Item → @dimen/uikit_settings_item_text_size
        val labelColor   = resolveAttrColor("settings_text_color")
        val labelSizePx  = resolveDimenPx("uikit_settings_item_text_size")
        // UiKit.Settings.Item.Addition → ?attr/primary_300, @dimen/uikit_textsize_medium
        val descColor    = resolveAttrColor("primary_300")
        val descSizePx   = resolveDimenPx("uikit_textsize_medium")
        // UiKit.TextAppearance (base of all UiKit.Settings.*) → ?attr/font_primary_normal (Whitney Medium)
        val primaryNormalTypeface: Typeface? = run {
            val attrId = ctx.resources.getIdentifier("font_primary_normal", "attr", "com.discord")
            if (attrId == 0) return@run null
            val tv = TypedValue()
            if (!ctx.theme.resolveAttribute(attrId, tv, true) || tv.resourceId == 0) return@run null
            runCatching { ResourcesCompat.getFont(ctx, tv.resourceId) }.getOrNull()
        }

        // ── Title — uses font_display_bold + colorHeaderPrimary same as native UiKit.Sheet.Header.Title ──
        val title = TextView(ctx)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        // Color: ?attr/colorHeaderPrimary (inherited by UiKit.TextView.H1 → UiKit.Sheet.Header.Title)
        val headerColorAttrId = ctx.resources.getIdentifier("colorHeaderPrimary", "attr", "com.discord")
        if (headerColorAttrId != 0) {
            val colorTv = TypedValue()
            if (ctx.theme.resolveAttribute(headerColorAttrId, colorTv, true))
                title.setTextColor(colorTv.data)
        }
        // Font: ?attr/font_display_bold (Ginto Nord Bold)
        val displayBoldAttrId = ctx.resources.getIdentifier("font_display_bold", "attr", "com.discord")
        if (displayBoldAttrId != 0) {
            val tv = android.util.TypedValue()
            if (ctx.theme.resolveAttribute(displayBoldAttrId, tv, true) && tv.resourceId != 0) {
                try {
                    ResourcesCompat.getFont(ctx, tv.resourceId)?.let { title.typeface = it }
                } catch (_: Throwable) {
                    title.setTypeface(title.typeface, Typeface.BOLD)
                }
            } else {
                title.setTypeface(title.typeface, Typeface.BOLD)
            }
        } else {
            title.setTypeface(title.typeface, Typeface.BOLD)
        }
        title.text = "Audio source"
        title.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(dp16, dp16, 0, dp16) }
        addView(title)

        // ── Divider — uses colorPrimaryDivider + marginTop 8dp same as native UiKit.Settings.Divider ──
        val divider = View(ctx)
        val divColorAttrId = ctx.resources.getIdentifier("colorPrimaryDivider", "attr", "com.discord")
        val divColorTv = TypedValue()
        val divColor = if (divColorAttrId != 0 && ctx.theme.resolveAttribute(divColorAttrId, divColorTv, true))
            divColorTv.data else Color.argb(40, 0, 0, 0)
        divider.setBackgroundColor(divColor)
        val divParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) // 0.25dp → 1px
        divParams.topMargin = DimenUtils.dpToPx(8)
        divider.layoutParams = divParams
        addView(divider)

        // ── Items — label + description of what each source does ──
        for ((name, value) in SOURCES) {
            val item = LinearLayout(ctx)
            item.orientation = LinearLayout.VERTICAL
            item.setPadding(dp16, dp16, dp16, dp16)
            item.isClickable = true
            item.isFocusable = true
            val bta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            item.background = bta.getDrawable(0)
            bta.recycle()

            val label = TextView(ctx)
            labelColor?.let { label.setTextColor(it) }
            labelSizePx?.let { label.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) } ?: label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            primaryNormalTypeface?.let { label.typeface = it }
            label.text = SOURCE_DISPLAY_NAMES[name] ?: name
            item.addView(label)

            val desc = TextView(ctx)
            descColor?.let { desc.setTextColor(it) }
            descSizePx?.let { desc.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) } ?: desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            primaryNormalTypeface?.let { desc.typeface = it }
            desc.text = SOURCE_DESCRIPTIONS[name] ?: ""
            item.addView(desc)

            item.setOnClickListener {
                pluginSettings.setInt(PREF_KEY, value)
                onSourceChanged?.invoke(value)
                onRestartSensitivity?.invoke()
                dismiss()
            }
            addView(item)
        }
    }
}

// ─── Plugin entry-point ───────────────────────────────────────────────────────
@AliucordPlugin
class AudioSourceChanger : Plugin() {

    override fun start(context: Context) {
        AudioSourceSelectorSheet.pluginSettings = settings

        patcher.patch(
            JavaAudioDeviceModule.Builder::class.java
                .getDeclaredMethod("createAudioDeviceModule"),
            PreHook { param ->
                val src = settings.getInt(PREF_KEY, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                JavaAudioDeviceModule.Builder::class.java
                    .getDeclaredField("audioSource")
                    .also { it.isAccessible = true }
                    .set(param.thisObject, src)
            }
        )

        // Hook initRecording on modern WebRTC audio class
        try {
            val audioRecordClass = Class.forName("org.webrtc.audio.WebRtcAudioRecord")
            val initRecMethod = audioRecordClass.getDeclaredMethod("initRecording", Int::class.java, Int::class.java)
                .also { it.isAccessible = true }
            patcher.patch(initRecMethod, PreHook { param ->
                val src = settings.getInt(PREF_KEY, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                audioRecordClass
                    .getDeclaredField("audioSource")
                    .also { it.isAccessible = true }
                    .set(param.thisObject, src)
            })
        } catch (e: Throwable) {
            logger.warn("hook-setup: modern initRecording not found")
        }

        // Hook initRecording on legacy voiceengine class (used by some Discord builds)
        try {
            val legacyClass = Class.forName("org.webrtc.voiceengine.WebRtcAudioRecord")
            val legacyMethod = legacyClass.getDeclaredMethod("initRecording", Int::class.java, Int::class.java)
                .also { it.isAccessible = true }
            patcher.patch(legacyMethod, PreHook { param ->
                val src = settings.getInt(PREF_KEY, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                // legacy audioSource is a static field
                legacyClass
                    .getDeclaredField("audioSource")
                    .also { it.isAccessible = true }
                    .set(null, src)
            })
        } catch (e: Throwable) {
            logger.warn("hook-setup: legacy initRecording not found")
        }

        val voiceClass = Class.forName("com.discord.widgets.settings.WidgetSettingsVoice")
        patcher.patch(
            voiceClass.getDeclaredMethod("onViewBound", View::class.java),
            Hook { param ->
                val fragment = param.thisObject as? Fragment ?: return@Hook
                val rootView = param.args[0] as? View       ?: return@Hook

                // Sets up callback to restart the sensitivity test with the new source
                try {
                    val subjectField = voiceClass.getDeclaredField("requestListenForSensitivitySubject")
                        .also { it.isAccessible = true }
                    AudioSourceSelectorSheet.onRestartSensitivity = restartFn@{
                        try {
                            val subject = subjectField.get(param.thisObject) ?: return@restartFn
                            // RxJava 1.x: getValue() may not be accessible via getMethod; try both
                            val isActive: Boolean = try {
                                val m = try {
                                    subject.javaClass.getMethod("getValue")
                                } catch (_: NoSuchMethodException) {
                                    subject.javaClass.getDeclaredMethod("getValue")
                                        .also { it.isAccessible = true }
                                }
                                m.invoke(subject) as? Boolean ?: true
                            } catch (_: Throwable) {
                                true // can't determine — assume active
                            }
                            if (!isActive) return@restartFn
                            val onNext = subject.javaClass.getMethod("onNext", Any::class.java)
                            onNext.invoke(subject, false)
                            onNext.invoke(subject, true)
                        } catch (e: Throwable) {
                            logger.warn("restart sensitivity invoke failed", e)
                        }
                    }
                } catch (e: Throwable) {
                    logger.warn("restart sensitivity setup failed", e)
                }

                val ctx = fragment.requireContext()
                val res = ctx.resources
                val pkg = ctx.packageName

                val modeRowId = resId(res, "settings_voice_mode", pkg)
                if (modeRowId == 0) { logger.warn("settings_voice_mode view id not found"); return@Hook }

                val inputModeRow = rootView.findViewById<View>(modeRowId) ?: return@Hook
                val linearParent = inputModeRow.parent as? LinearLayout   ?: return@Hook

                if (linearParent.findViewWithTag<View>(ROW_TAG) != null) return@Hook

                var insertIndex = -1
                for (i in 0 until linearParent.childCount) {
                    if (linearParent.getChildAt(i) === inputModeRow) { insertIndex = i + 1; break }
                }
                if (insertIndex < 0) return@Hook

                // ── Captures appearance from native TextViews (already styled by Discord) ──
                val headerId = resId(res, "settings_voice_mode_header", pkg)
                val valueId  = resId(res, "settings_voice_mode_value",  pkg)
                val nativeHeader = if (headerId != 0) inputModeRow.findViewById<TextView>(headerId) else null
                val nativeValue  = if (valueId  != 0) inputModeRow.findViewById<TextView>(valueId)  else null

                // ── Arrow drawable via attr ic_navigate_next ──
                val arrowAttrId = res.getIdentifier("ic_navigate_next", "attr", "com.discord")
                val arrowDrawable = if (arrowAttrId != 0) {
                    val ta = ctx.theme.obtainStyledAttributes(intArrayOf(arrowAttrId))
                    val d = ta.getDrawable(0); ta.recycle(); d
                } else null

                val cur = settings.getInt(PREF_KEY, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                var curName = "VOICE_COMMUNICATION"
                for (e in SOURCES.entries) { if (e.value == cur) { curName = e.key; break } }

                val headViewId = View.generateViewId()

                // ── Value label (updated on selection) ──
                val valueLabel = TextView(ctx)
                nativeValue?.let { cloneText(it, valueLabel) } ?: run { valueLabel.textSize = 13f }
                valueLabel.text = SOURCE_DISPLAY_NAMES[curName] ?: curName
                AudioSourceSelectorSheet.onSourceChanged = { newVal ->
                    var n = newVal.toString()
                    for (e in SOURCES.entries) { if (e.value == newVal) { n = e.key; break } }
                    valueLabel.text = SOURCE_DISPLAY_NAMES[n] ?: n
                }

                // ── Row container — clones padding/minHeight/background from the native row ──
                val newRow = RelativeLayout(ctx)
                newRow.tag = ROW_TAG
                newRow.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val dp16r = DimenUtils.dpToPx(16)
                val dp12r = DimenUtils.dpToPx(12)
                newRow.setPadding(dp16r, dp12r, dp16r, dp12r)
                newRow.minimumHeight = DimenUtils.dpToPx(56)
                newRow.isClickable = true
                newRow.isFocusable = true
                try {
                    val bg = inputModeRow.background?.constantState?.newDrawable()?.mutate()
                    if (bg != null) newRow.background = bg
                    else {
                        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                        newRow.background = ta.getDrawable(0); ta.recycle()
                    }
                } catch (_: Exception) {
                    val ta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                    newRow.background = ta.getDrawable(0); ta.recycle()
                }
                newRow.setOnClickListener {
                    AudioSourceSelectorSheet().show(fragment.childFragmentManager, "AudioSourceSelector")
                }

                // ── Label "Audio Source" ──
                val labelView = TextView(ctx)
                nativeHeader?.let { cloneText(it, labelView) } ?: run { labelView.textSize = 16f }
                labelView.id = headViewId
                labelView.text = "Audio source"
                newRow.addView(labelView, RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                })

                // ── Value label below the header ──
                newRow.addView(valueLabel, RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.BELOW, headViewId)
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                })

                // ── Arrow → ──
                if (arrowDrawable != null) {
                    newRow.addView(ImageView(ctx).apply {
                        setImageDrawable(arrowDrawable)
                    }, RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.WRAP_CONTENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_END)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                    })
                }

                linearParent.addView(newRow, insertIndex)
            }
        )
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}
