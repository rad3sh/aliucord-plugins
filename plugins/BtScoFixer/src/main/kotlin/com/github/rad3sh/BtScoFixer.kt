package com.github.rad3sh

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.utils.DimenUtils
import com.aliucord.widgets.BottomSheet

// ─── Setting keys ─────────────────────────────────────────────────────────────
private const val PREF_PROFILE = "sco_profile"  // "default" | "media_normal_nosco"

// Tracks whether Discord has opened an SCO session this call (file-level so both classes see it)
@Volatile private var scoActive = false

// ─── Settings BottomSheet ─────────────────────────────────────────────────────
class BtScoFixerSettingsSheet : BottomSheet() {

    companion object {
        lateinit var pluginSettings: com.aliucord.api.SettingsAPI
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx  = requireContext()
        val dp16 = DimenUtils.dpToPx(16)
        val dp12 = DimenUtils.dpToPx(12)
        val dp8  = DimenUtils.dpToPx(8)

        // ── Resolve Discord theme attrs ──────────────────────────────────────────
        fun resolveAttrColor(name: String): Int? {
            val id = ctx.resources.getIdentifier(name, "attr", "com.discord")
            if (id == 0) return null
            val tv = TypedValue()
            if (!ctx.theme.resolveAttribute(id, tv, true)) return null
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

        val labelColor  = resolveAttrColor("settings_text_color")
        val descColor   = resolveAttrColor("primary_300")
        val labelSizePx = resolveDimenPx("uikit_settings_item_text_size")
        val descSizePx  = resolveDimenPx("uikit_textsize_medium")

        val primaryFont: Typeface? = run {
            val attrId = ctx.resources.getIdentifier("font_primary_normal", "attr", "com.discord")
            if (attrId == 0) return@run null
            val tv = TypedValue()
            if (!ctx.theme.resolveAttribute(attrId, tv, true) || tv.resourceId == 0) return@run null
            runCatching { ResourcesCompat.getFont(ctx, tv.resourceId) }.getOrNull()
        }

        val headerColor: Int? = run {
            val attrId = ctx.resources.getIdentifier("colorHeaderPrimary", "attr", "com.discord")
            if (attrId == 0) return@run null
            val tv = TypedValue()
            if (!ctx.theme.resolveAttribute(attrId, tv, true)) return@run null
            tv.data
        }

        val displayBoldFont: Typeface? = run {
            val attrId = ctx.resources.getIdentifier("font_display_bold", "attr", "com.discord")
            if (attrId == 0) return@run null
            val tv = TypedValue()
            if (!ctx.theme.resolveAttribute(attrId, tv, true) || tv.resourceId == 0) return@run null
            runCatching { ResourcesCompat.getFont(ctx, tv.resourceId) }.getOrNull()
        }

        // ── Helpers ──────────────────────────────────────────────────────────────
        fun addDivider() {
            val v = View(ctx)
            val divAttrId = ctx.resources.getIdentifier("colorPrimaryDivider", "attr", "com.discord")
            if (divAttrId != 0) {
                val tv = TypedValue()
                if (ctx.theme.resolveAttribute(divAttrId, tv, true)) v.setBackgroundColor(tv.data)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            lp.setMargins(0, dp8, 0, 0)
            v.layoutParams = lp
            addView(v)
        }

        fun addSectionTitle(text: String) {
            val tv = TextView(ctx)
            tv.text = text
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            displayBoldFont?.let { tv.typeface = it } ?: tv.setTypeface(tv.typeface, Typeface.BOLD)
            headerColor?.let { tv.setTextColor(it) }
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(dp16, dp12, 0, dp8) }
            addView(tv)
        }

        fun addItem(
            label: String,
            desc: String,
            selected: Boolean,
            enabled: Boolean = true,
            onClick: () -> Unit,
        ) {
            val item = LinearLayout(ctx)
            item.orientation = LinearLayout.VERTICAL
            item.setPadding(dp16, dp12, dp16, dp12)
            item.minimumHeight = DimenUtils.dpToPx(56)
            if (enabled) {
                item.isClickable = true
                item.isFocusable = true
                val bta = ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                item.background = bta.getDrawable(0)
                bta.recycle()
            } else {
                item.alpha = 0.38f
            }

            val labelView = TextView(ctx)
            labelView.text = if (selected) "● $label" else "  $label"
            labelColor?.let { labelView.setTextColor(it) }
            labelSizePx?.let { labelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
                ?: labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            primaryFont?.let { labelView.typeface = it }
            if (selected) labelView.setTypeface(labelView.typeface, Typeface.BOLD)
            item.addView(labelView)

            val descView = TextView(ctx)
            descView.text = desc
            descColor?.let { descView.setTextColor(it) }
            descSizePx?.let { descView.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
                ?: descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            primaryFont?.let { descView.typeface = it }
            item.addView(descView)

            if (enabled) item.setOnClickListener { onClick(); dismiss() }
            addView(item)
        }

        // ── Title ────────────────────────────────────────────────────────────────
        val title = TextView(ctx)
        title.text = "BtScoFixer"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        displayBoldFont?.let { title.typeface = it } ?: title.setTypeface(title.typeface, Typeface.BOLD)
        headerColor?.let { title.setTextColor(it) }
        title.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(dp16, dp16, 0, dp12) }
        addView(title)
        addDivider()

        // ── SCO Status ───────────────────────────────────────────────────────────
        val statusText = if (scoActive)
            "Bluetooth SCO: Ativo — Discord está usando SCO nesta chamada."
        else
            "Bluetooth SCO: Inativo — aguardando chamada com headset BT."

        val statusView = TextView(ctx)
        statusView.text = statusText
        descColor?.let { statusView.setTextColor(it) }
        descSizePx?.let { statusView.setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
            ?: statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        primaryFont?.let { statusView.typeface = it }
        statusView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(dp16, dp8, dp16, dp8) }
        addView(statusView)
        addDivider()

        // ── Profiles ─────────────────────────────────────────────────────────────
        // When SCO is inactive, effective profile is always default (no overrides applied)
        addSectionTitle("Perfil ao detectar SCO")

        val storedProfile = pluginSettings.getString(PREF_PROFILE, "default")
        val effectiveProfile = if (scoActive) storedProfile else "default"

        addItem(
            "Discord Default",
            "Comportamento padrão sem intervenção — SCO e roteamento normais do Android.",
            effectiveProfile == "default",
        ) { pluginSettings.setString(PREF_PROFILE, "default") }

        addItem(
            "Media + Modo Normal (sem SCO)",
            if (scoActive)
                "USAGE_MEDIA + SCO bloqueado + MODE_NORMAL — tenta rotear output pelo A2DP. ⚠ Mic BT pode parar."
            else
                "Só disponível durante chamada SCO ativa. Inicie uma chamada com headset BT primeiro.",
            effectiveProfile == "media_normal_nosco",
            enabled = scoActive,
        ) { pluginSettings.setString(PREF_PROFILE, "media_normal_nosco") }
    }
}

// ─── Plugin ───────────────────────────────────────────────────────────────────
@AliucordPlugin
class BtScoFixer : Plugin() {

    override fun start(context: Context) {
        BtScoFixerSettingsSheet.pluginSettings = settings

        // Sync initial state: if plugin loads mid-call with SCO already on
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        scoActive = am.isBluetoothScoOn

        settingsTab = SettingsTab(BtScoFixerSettingsSheet::class.java, SettingsTab.Type.BOTTOM_SHEET)

        // ── Hook 1: startBluetoothSco — mark SCO active ──────────────────────────
        // Discord calls this when a BT SCO headset is selected for the call.
        // We detect it here and optionally block it if "media_normal_nosco" is selected.
        patcher.patch(
            AudioManager::class.java.getMethod("startBluetoothSco"),
            PreHook { param ->
                scoActive = true
                val profile = settings.getString(PREF_PROFILE, "default")
                if (profile == "media_normal_nosco") {
                    param.result = null
                    logger.info("[Hook1] startBluetoothSco → BLOCKED (profile=media_normal_nosco)")
                } else {
                    logger.info("[Hook1] startBluetoothSco → ALLOWED (profile=default)")
                }
            }
        )

        // ── Hook 2: stopBluetoothSco — mark SCO inactive ─────────────────────────
        // SCO stopped = device disconnected or call ended → revert to default behavior.
        patcher.patch(
            AudioManager::class.java.getMethod("stopBluetoothSco"),
            PreHook { param ->
                scoActive = false
                logger.info("[Hook2] stopBluetoothSco → scoActive=false, effective profile → default")
            }
        )

        // ── Hook 3: WebRtcAudioTrack.initPlayout — override audioAttributes ──────
        // Called per-call. If SCO is active and profile is media_normal_nosco,
        // override audioAttributes to USAGE_MEDIA before AudioTrack is created.
        val hook3Method = try {
            Class.forName("org.webrtc.audio.WebRtcAudioTrack")
                .getDeclaredMethod("initPlayout", Int::class.java, Int::class.java, Double::class.java)
                .also { it.isAccessible = true; logger.info("[Hook3] initPlayout found") }
        } catch (e: Throwable) {
            logger.warn("[Hook3] initPlayout NOT found — hook skipped", e); null
        }
        if (hook3Method != null) {
            patcher.patch(
                hook3Method,
                PreHook { param ->
                    val profile = settings.getString(PREF_PROFILE, "default")
                    if (profile != "media_normal_nosco") {
                        // Restore audioAttributes if the field was previously overridden to USAGE_MEDIA.
                        // The WebRtcAudioTrack instance may be reused between calls, keeping the stale value.
                        try {
                            val field = hook3Method.declaringClass
                                .getDeclaredField("audioAttributes")
                                .also { it.isAccessible = true }
                            val current = field.get(param.thisObject) as? AudioAttributes
                            if (current != null && current.usage == AudioAttributes.USAGE_MEDIA) {
                                val restored = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                                field.set(param.thisObject, restored)
                                logger.info("[Hook3] initPlayout — profile=default, restored audioAttributes → USAGE_VOICE_COMMUNICATION")
                            } else {
                                logger.info("[Hook3] initPlayout — profile=default, no restore needed")
                            }
                        } catch (e: Throwable) {
                            logger.warn("[Hook3] initPlayout — failed to check/restore audioAttributes", e)
                        }
                        return@PreHook
                    }
                    // Dump all device types for diagnostics
                    val allDevices = am.getDevices(AudioManager.GET_DEVICES_ALL)
                    val deviceTypes = buildString {
                        for (d in allDevices) append("${d.productName}(type=${d.type}) ")
                    }
                    logger.info("[Hook3] devices: $deviceTypes")
                    // Verifica presença do dispositivo BT SCO diretamente — evita race com startBluetoothSco
                    var hasSco = false
                    for (d in allDevices) {
                        if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { hasSco = true; break }
                    }
                    if (!hasSco) {
                        logger.info("[Hook3] initPlayout — nenhum dispositivo SCO presente, no override")
                        return@PreHook
                    }
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    try {
                        hook3Method.declaringClass
                            .getDeclaredField("audioAttributes")
                            .also { it.isAccessible = true }
                            .set(param.thisObject, attrs)
                        logger.info("[Hook3] initPlayout → audioAttributes overridden to USAGE_MEDIA")
                    } catch (e: Throwable) {
                        logger.warn("[Hook3] initPlayout — failed to set audioAttributes", e)
                    }
                }
            )
        }

        // ── Hook 4: AudioManager.setMode — force NORMAL ──────────────────────────
        // When SCO is active and profile is media_normal_nosco, replace
        // IN_COMMUNICATION(3) with NORMAL(0) to disable HW AEC and change routing.
        patcher.patch(
            AudioManager::class.java.getMethod("setMode", Int::class.java),
            PreHook { param ->
                val profile = settings.getString(PREF_PROFILE, "default")
                val original = param.args[0] as Int
                if (profile != "media_normal_nosco") {
                    logger.info("[Hook4] setMode: $original — profile=$profile, no override")
                    return@PreHook
                }
                // Dump all device types for diagnostics
                val amHook = param.thisObject as AudioManager
                val allDevices = amHook.getDevices(AudioManager.GET_DEVICES_ALL)
                val deviceTypes = buildString {
                    for (d in allDevices) append("${d.productName}(type=${d.type}) ")
                }
                logger.info("[Hook4] setMode=$original devices: $deviceTypes")
                // Verifica presença do dispositivo BT SCO diretamente — evita race com startBluetoothSco
                var hasSco = false
                for (d in allDevices) {
                    if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { hasSco = true; break }
                }
                if (!hasSco) {
                    logger.info("[Hook4] setMode: $original — nenhum dispositivo SCO, no override")
                    return@PreHook
                }
                if (original == AudioManager.MODE_IN_COMMUNICATION) {
                    param.args[0] = AudioManager.MODE_NORMAL
                    logger.info("[Hook4] setMode: $original → ${AudioManager.MODE_NORMAL} (forced NORMAL)")
                }
            }
        )
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}
