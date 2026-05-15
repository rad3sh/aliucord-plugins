package com.github.rad3sh

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
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

        addItem(
            "Discord Default",
            "Comportamento padrão sem intervenção — SCO e roteamento normais do Android.",
            storedProfile == "default"
        ) { pluginSettings.setString(PREF_PROFILE, "default") }

        addItem(
            "Media + Modo Normal (sem SCO)",
            if (scoActive)
                "USAGE_MEDIA + SCO bloqueado + MODE_NORMAL — tenta rotear output pelo A2DP. ⚠ Mic BT pode parar."
            else
                "Só disponível durante chamada SCO ativa. Inicie uma chamada com headset BT primeiro.",
            storedProfile == "media_normal_nosco"
        ) { pluginSettings.setString(PREF_PROFILE, "media_normal_nosco") }
    }
}

// ─── Plugin ───────────────────────────────────────────────────────────────────
@AliucordPlugin
class BtScoFixer : Plugin() {

    // Utilitário para traduzir o valor int do modo de áudio para o nome simbólico
    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        else -> "MODE_$mode"
    }

    override fun start(context: Context) {
        BtScoFixerSettingsSheet.pluginSettings = settings

        // Sync initial state: if plugin loads mid-call with SCO already on
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        scoActive = am.isBluetoothScoOn

        settingsTab = SettingsTab(BtScoFixerSettingsSheet::class.java, SettingsTab.Type.BOTTOM_SHEET)

        // ── Hook 0: WebRtcAudioTrack.initPlayout — override audioAttributes per call ──
        // createAudioDeviceModule() is called once at WebRTC engine startup (before the
        // plugin loads), so hooking it misses all calls. initPlayout(int,int,double) is
        // called per-call and uses this.audioAttributes to create the AudioTrack.
        // We set the instance field right before AudioTrack creation so our usage applies.
        val hook1Method = try {
            Class.forName("org.webrtc.audio.WebRtcAudioTrack")
                .getDeclaredMethod("initPlayout", Int::class.java, Int::class.java, Double::class.java)
                .also { it.isAccessible = true; logger.info("[Hook0] initPlayout found — registering patch") }
        } catch (e: Throwable) {
            logger.warn("[Hook0] initPlayout NOT found — hook skipped", e); null
        }
        if (hook1Method != null) {
            
            patcher.patch(
                hook1Method,
                
                PreHook { param ->
                    logger.info("[Hook0] initPlayout called (profile=${settings.getString(PREF_PROFILE, "default")}, scoActive=$scoActive)")
                    /* 
                    val usageMode = settings.getString(PREF_USAGE, "VOICE_COMM")
                    if (usageMode == "VOICE_COMM") {
                        logger.info("[Hook0] initPlayout — usage=VOICE_COMM (no override)")
                        return@PreHook
                    }
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    try {
                        hook1Method.declaringClass
                            .getDeclaredField("audioAttributes")
                            .also { it.isAccessible = true }
                            .set(param.thisObject, attrs)
                        logger.info("[Hook0] initPlayout — audioAttributes overridden → USAGE_MEDIA")
                    } catch (e: Throwable) {
                        logger.warn("[Hook0] initPlayout — failed to set audioAttributes field", e)
                    }
                        */
                }
                    
            )
                
        }

        // ── Hook 1: startBluetoothSco — mark SCO active ──────────────────────────
        // Block SCO only when profile=media_normal_nosco AND a TYPE_BLUETOOTH_SCO (type=7) device
        // is connected (i.e. the Baseus headset). Other outputs pass through normally.
        patcher.patch(
            AudioManager::class.java.getMethod("startBluetoothSco"),
            PreHook { param ->

                val amInst = param.thisObject as AudioManager
                val profile = settings.getString(PREF_PROFILE, "default")
                logger.info("[Hook1] startBluetoothSco called (profile=$profile, mode=${modeName(amInst.mode)}, scoActive=$scoActive)")
                scoActive = amInst.isBluetoothScoOn()
                if (profile == "media_normal_nosco") {
                    var hasBtSco = false
                    for (d in am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                        if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { hasBtSco = true; break }
                    }
                    if (hasBtSco) {
                        param.result = null
                        // Discord calls setMode(IN_COMMUNICATION) ~40ms BEFORE startBluetoothSco,
                        // so Hook4 misses it (scoActive was false). Force NORMAL retroativamente.
                        amInst.setMode(AudioManager.MODE_NORMAL)
                        logger.info("[Hook1] startBluetoothSco → BLOCKED (profile=$profile, mode=${modeName(amInst.mode)}, scoActive=$scoActive) TYPE_BLUETOOTH_SCO device detected")
                    } else {
                        logger.info("[Hook1] startBluetoothSco → ALLOWED (profile=$profile, mode=${modeName(amInst.mode)}, scoActive=$scoActive) no TYPE_BLUETOOTH_SCO device")
                    }
                } else {

                /* TODO
                if (Build.VERSION.SDK_INT >= 31) {
                    // Android 12 ou superior: DEVE usar setCommunicationDevice() / clearCommunicationDevice()
                    // Isso gerencia o áudio de chamadas modernos e fones HFP normais
                } else {
                    // Android 11 ou inferior: DEVE usar startBluetoothSco() / stopBluetoothSco()
                }
                */
                // Perfil default: não bloqueia startBluetoothSco, mas marca scoActive=true para o caso de o usuário mudar para o perfil "media_normal_nosco" durante a chamada
                logger.info("[Hook1] startBluetoothSco → ALLOWED (profile=$profile, mode=${modeName(amInst.mode)}, scoActive=$scoActive)")
                }
            }
        )

        // ── Hook 2: stopBluetoothSco — mark SCO inactive ────────────────────────────
        patcher.patch(
            AudioManager::class.java.getMethod("stopBluetoothSco"),
            PreHook { param ->
                val profile = settings.getString(PREF_PROFILE, "default")
                logger.info("[Hook2] stopBluetoothSco called (profile=$profile, mode=${modeName((param.thisObject as AudioManager).mode)} , scoActive=$scoActive)")
                val amInst = param.thisObject as AudioManager
                amInst.setBluetoothScoOn(false) // chama o método original para garantir que o estado do Android seja atualizado (ex: isBluetoothScoOn = false
                scoActive = amInst.isBluetoothScoOn() // atualiza nosso estado interno de scoActive
                logger.info("[Hook2] stopBluetoothSco → (profile=$profile, mode=${modeName((param.thisObject as AudioManager).mode)}, scoActive=$scoActive)")

                //TODO
                /* 
                if (Build.VERSION.SDK_INT >= 31) {
                    amInst.clearCommunicationDevice()
                    logger.info("[Hook2] clearCommunicationDevice() called (Android 12+) (profile=$profile, mode=${modeName((param.thisObject as AudioManager).mode)}, scoActive=$scoActive)")
                    return@PreHook
                } else {

                }
                */
            }
        )

        // ── Hook 4: AudioManager.setMode — force NORMAL ──────────────────────────
        // Guard on scoActive: Discord calls stopBluetoothSco() before switching to
        // speakerphone/earpiece/wired, which sets scoActive=false — so the override
        // is automatically skipped for those outputs without any extra device scanning.
        patcher.patch(
            AudioManager::class.java.getMethod("setMode", Int::class.java),
            PreHook { param ->
                val profile = settings.getString(PREF_PROFILE, "default")
                logger.info("[Hook4] setMode called (profile=$profile, mode=${modeName(param.args[0] as Int)} , scoActive=$scoActive)")
                val original = param.args[0] as Int

                // Só aplica o override se:
                // - Perfil ativo é "media_normal_nosco"
                // - SCO está ativo (scoActive=true)
                // - Discord está tentando setar MODE_IN_COMMUNICATION
                if (profile != "media_normal_nosco" || !scoActive || original != AudioManager.MODE_IN_COMMUNICATION) {
                    // EARPIECE, WIRED_HEADSET, SPEAKER_OUTPUT:
                    // Discord chama stopBluetoothSco antes de trocar para esses outputs,
                    // então scoActive=false e o override é ignorado (setMode(IN_COMMUNICATION) segue normal)
                    logger.info("[Hook4] setMode=${modeName(original)} - Skip (profile=$profile, scoActive=$scoActive)")
                    return@PreHook
                }

                // BLUETOOTH_HEADSET:
                // Override: força MODE_NORMAL para tentar rotear áudio via A2DP
                param.args[0] = AudioManager.MODE_NORMAL
                logger.info("[Hook4] setMode: mode=${modeName(original)} → NORMAL (profile=$profile, mode=${modeName(param.args[0] as Int)}, scoActive=$scoActive)")
            }
        )

    }

    override fun stop(context: Context) = patcher.unpatchAll()
}
