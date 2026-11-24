package com.ekoehler.expressivecutout.nexora

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.*
import android.view.animation.PathInterpolator
import androidx.compose.ui.graphics.asAndroidBitmap
import com.ekoehler.expressivecutout.core.*
import com.ekoehler.expressivecutout.service.CutoutNotificationListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.math.*

/** Process-local only: calibration must never remain visible after leaving the screen. */
internal object NexoraCalibration {
    val mode = kotlinx.coroutines.flow.MutableStateFlow(0) // 0 off, 1 compact, 2 expanded, 3 charging
}

/** NEXORA Reborn: independent native Canvas renderer; only the event producers are shared. */
class NexoraOverlay(private val service: AccessibilityService) {
    private val wm = requireNotNull(service.getSystemService(WindowManager::class.java))
    private val power = requireNotNull(service.getSystemService(PowerManager::class.java))
    private val keyguard = requireNotNull(service.getSystemService(KeyguardManager::class.java))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = service.getSharedPreferences("nexora", Context.MODE_PRIVATE)
    private val density = service.resources.displayMetrics.density
    private fun dp(x: Float) = x * density
    private var panel: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    private var flash: Card? = null
    private var flashUntil = 0L
    private var autoCollapseAt = 0L
    private var autoFocus: String? = null
    private var selection = 0
    private var expanded = false
    private var browsing = false
    private var mediaIntent: PendingIntent? = null
    private var callIntent: PendingIntent? = null
    private var timerIntent: PendingIntent? = null
    private var callActions: List<CutoutSignal.Notification.Action> = emptyList()
    private var wasRinging = false
    private fun ringing() = OnCallBus.state.value?.ongoing == false
    private var current: Card? = null
    private var leaving = false
    private var geometry: ValueAnimator? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var targetX = 0
    private var targetY = 0
    private var centerX = 0
    private var anchorX = 0
    private var physicalY = 0f
    private var anchorY = 0
    private var screenWidthPx = 0
    private val morphCurve = PathInterpolator(.20f, .82f, .22f, 1f)
    private val closeCurve = PathInterpolator(.32f, .0f, .20f, 1f)
    private val refresh = Runnable { render() }
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                handler.removeCallbacks(refresh)
                flash = null; expanded = false; browsing = false; current = null; autoCollapseAt = 0L; autoFocus = null
                remove()
            } else render()
        }
    }
    private data class Card(val kind: String, val title: String, val detail: String = "", val accent: Int = BLUE,
        val intent: PendingIntent? = null, val key: String? = null, val progress: Float? = null, val appIcon: android.graphics.drawable.Drawable? = null)
    companion object {
        private val BLUE = Color.rgb(99, 202, 255)
        private val VIOLET = Color.rgb(181, 151, 255)
        private val GREEN = Color.rgb(89, 235, 164)
        private val RED = Color.rgb(255, 119, 137)
        private const val EXPANDED_MS = 3000L
        private const val COMPACT_EVENT_MS = 3000L
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        screenReceiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else service.registerReceiver(screenReceiver, filter)
            true
        }.getOrDefault(false)
        scope.launch { IslandEventBus.signals.collect { signal ->
            when (signal) {
                is CutoutSignal.Notification -> {
                    // Never remove or snooze the user's actual notification in the new renderer.
                    signal.key?.let { CutoutNotificationListenerService.release(it) }
                    if (prefs.getBoolean("notifications", true) && !keyguard.isKeyguardLocked) {
                        val p = signal.progressData
                        show(Card("message", signal.title ?: signal.appName ?: "Notification",
                            signal.text ?: signal.appName.orEmpty(), BLUE, signal.contentIntent, signal.key,
                            p?.takeIf { it.max > 0 }?.let { it.current.toFloat() / it.max },
                            runCatching { service.packageManager.getApplicationIcon(signal.packageName) }.getOrNull()))
                    }
                }
                is CutoutSignal.System -> if (prefs.getBoolean("events", true)) {
                    val t = signal.type.name
                    val kind = when {
                        t.startsWith("CHARGING") || t == "BATTERY_LOW" -> "energy"
                        t == "DEVICE_UNLOCKED" -> "unlock"
                        t == "DEVICE_LOCKED" -> "lock"
                        t.contains("WIFI") || t.contains("BLUETOOTH") || t.contains("VPN") || t.contains("HEADPHONES") || t.contains("HOTSPOT") -> "connect"
                        else -> "system"
                    }
                    val accent = when {
                        t.contains("DISCONNECTED") || t == "BATTERY_LOW" || t == "CHARGING_STOPPED" -> RED
                        kind == "energy" || kind == "unlock" -> GREEN
                        else -> BLUE
                    }
                    show(Card(kind, signal.payload.title ?: service.getString(signal.type.labelRes),
                        signal.payload.collapsedBadgeText ?: signal.payload.subtitle.orEmpty(), accent))
                }
                is CutoutSignal.SystemEnded -> Unit
                is CutoutSignal.Music -> {
                    mediaIntent = signal.contentIntent
                    if(prefs.getBoolean("music",true) && !keyguard.isKeyguardLocked) openLive("music")
                    else render()
                }
                is CutoutSignal.Call -> {
                    callIntent = signal.contentIntent; callActions = signal.actions
                    if (!signal.ongoing) openLive("call") else render()
                }
                is CutoutSignal.Timer -> { timerIntent = signal.contentIntent; openLive("timer") }
            }
        } }
        scope.launch { NexoraCalibration.mode.collect { render() } }
        scope.launch { NowPlayingBus.state.collect { render() } }
        scope.launch { OnCallBus.state.collect { call ->
            val incoming = call?.ongoing == false
            if (incoming && !wasRinging) {
                flash=null; flashUntil=0L; browsing=false
                expanded=true; autoCollapseAt=0L; autoFocus="call"
            } else if (wasRinging && !incoming) {
                expanded=false; autoCollapseAt=0L; browsing=false
                autoFocus=if(call!=null)"call" else null
                selection=0
            }
            wasRinging=incoming
            if(call==null){callActions=emptyList();callIntent=null}
            render()
        } }
        scope.launch { RunningTimerBus.state.collect { render() } }
        scope.launch { DeviceLockBus.locked.collect { if (it) { flash = null; browsing = false; expanded = false; autoCollapseAt=0L; autoFocus=null }; render() } }
        scope.launch { NotificationInboxBus.items.collect { if (browsing) render() } }
        render()
    }
    private fun openLive(kind: String) {
        if(ringing() && kind!="call")return
        flash=null; flashUntil=0L
        autoFocus=kind
        browsing=false
        expanded=true
        autoCollapseAt=if(kind=="call" && ringing())0L else SystemClock.uptimeMillis()+EXPANDED_MS
        render()
    }
    private fun show(card: Card) {
        // Incoming calls keep priority until answered or declined.
        if(OnCallBus.state.value?.ongoing==false && card.kind!="call") return
        flash=card
        val now=SystemClock.uptimeMillis()
        autoCollapseAt=now+EXPANDED_MS
        flashUntil=autoCollapseAt+COMPACT_EVENT_MS
        autoFocus=card.kind
        selection=0
        browsing=false
        expanded=true
        render()
    }
    private fun cards(): List<Card> {
        val result = mutableListOf<Card>()
        val locked = keyguard.isKeyguardLocked
        val preview = NexoraCalibration.mode.value
        if (!locked && preview != 0) return listOf(
            if (preview == 3) Card("energy", "Charging", "84%", GREEN)
            else Card("music", "Midnight Drive", "Now playing", VIOLET, progress = .60f)
        )
        val now=SystemClock.uptimeMillis()
        if(!ringing() && autoCollapseAt!=0L && now>=autoCollapseAt){
            autoCollapseAt=0L; expanded=false
        }
        if(now>=flashUntil) flash=null
        flash?.takeIf { !locked || it.kind != "message" }?.let { result.add(it) }
        if (locked) {
            OnCallBus.state.value?.let { result.add(Card("call",if(it.ongoing)"On call" else "Incoming call",if(it.ongoing)"Call in progress" else "Unlock to view",GREEN)) }
            return result
        }
        if (prefs.getBoolean("music", true)) NowPlayingBus.state.value?.takeIf { it.isPlaying || (expanded && current?.kind == "music") }?.let {
            result.add(Card("music", it.title ?: "Now playing", it.artist.orEmpty(), VIOLET, mediaIntent))
        }
        OnCallBus.state.value?.let { result.add(Card("call", it.callerLabel, if (it.ongoing) "On call" else "Incoming call", GREEN, callIntent)) }
        RunningTimerBus.state.value?.let { result.add(Card("timer", it.label ?: "Timer", "", BLUE, timerIntent)) }
        if (browsing) NotificationInboxBus.items.value.take(20).forEach {
            if (it.key != flash?.key) result.add(Card("message", it.title, it.appName, BLUE, key = it.key))
        }
        return result
    }
    // Keep the window stable during animation. Only the Canvas geometry morphs per frame;
    // WindowManager gets one resize before the transition and one when it settles.
    private fun layout(v: IslandView, x: Int, y: Int, w: Int, h: Int) {
        val lp = params ?: return
        if(lp.x!=x || lp.y!=y || lp.width!=w || lp.height!=h) {
            lp.x=x; lp.y=y; lp.width=w; lp.height=h
            runCatching { wm.updateViewLayout(v,lp) }
        }
    }
    private fun place(v: IslandView, x: Float, y: Float, w: Float, h: Float) {
        val lp=params ?: return
        v.visualX=x; v.visualY=y; v.visualWidth=w; v.visualHeight=h
        v.drawX=x-lp.x; v.drawY=y-lp.y
        v.cameraX=centerX-x; v.cameraY=physicalY-y
        v.invalidate()
    }
    private fun morph(v: IslandView, w: Int, h: Int, dismiss: Boolean = false) {
        val lp = params ?: return
        val x=(anchorX-w/2).coerceIn(0,(screenWidthPx-w).coerceAtLeast(0))
        val y=anchorY
        if (!dismiss && !leaving && targetWidth==w && targetHeight==h && targetX==x && targetY==y) return
        geometry?.cancel()
        targetWidth=w; targetHeight=h; targetX=x; targetY=y
        val fromX=v.visualX; val fromY=v.visualY
        val fromW=v.visualWidth.takeIf{it>0f} ?: lp.width.toFloat()
        val fromH=v.visualHeight.takeIf{it>0f} ?: lp.height.toFloat()
        val hostX=min(floor(fromX).toInt(),x)
        val hostY=min(floor(fromY).toInt(),y)
        val hostW=max(ceil(fromX+fromW).toInt(),x+w)-hostX
        val hostH=max(ceil(fromY+fromH).toInt(),y+h)-hostY
        layout(v,hostX,hostY,hostW.coerceAtLeast(1),hostH.coerceAtLeast(1))
        place(v,fromX,fromY,fromW,fromH)
        val startAlpha=v.alpha
        geometry=ValueAnimator.ofFloat(0f,1f).apply {
            duration=if(dismiss) 220L else if(h>fromH+dp(10f)) 340L else if(h<fromH-dp(10f)) 280L else 270L
            interpolator=if(dismiss || h<fromH-dp(10f))closeCurve else morphCurve
            addUpdateListener { a ->
                val t=a.animatedValue as Float
                place(v,fromX+(x-fromX)*t,fromY+(y-fromY)*t,fromW+(w-fromW)*t,fromH+(h-fromH)*t)
                v.alpha=if(dismiss)startAlpha*(1f-t*t) else startAlpha+(1f-startAlpha)*t
            }
            addListener(object:android.animation.AnimatorListenerAdapter(){
                private var cancelled=false
                override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(animation:android.animation.Animator){
                    if(cancelled)return
                    if(dismiss && leaving){remove();return}
                    layout(v,x,y,w,h);place(v,x.toFloat(),y.toFloat(),w.toFloat(),h.toFloat())
                }
            })
            start()
        }
    }

    private fun render() {
        handler.removeCallbacks(refresh)
        if ((!prefs.getBoolean("enabled", true) && NexoraCalibration.mode.value == 0) || !power.isInteractive || service.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            flash = null; expanded = false; browsing = false; autoCollapseAt=0L; autoFocus=null; remove(); return
        }
        val all = cards()
        if (all.isEmpty()) { selection = 0; browsing = false; expanded = false; current = null; hide(); return }
        autoFocus?.let { kind ->
            val position=all.indexOfFirst{it.kind==kind}
            if(position>=0) { selection=position; autoFocus=null }
        }
        if(ringing() && NexoraCalibration.mode.value==0) {
            val callIndex=all.indexOfFirst{it.kind=="call"}
            if(callIndex>=0){selection=callIndex; expanded=true; autoCollapseAt=0L; autoFocus=null; browsing=false}
        }
        selection = selection.coerceIn(0, all.lastIndex)
        val next = all[selection]
        val changed = current?.kind != next.kind || current?.title != next.title
        current = next
        val v = panel ?: IslandView().also { panel = it }
        val metrics = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics else null
        val screenWidth = metrics?.bounds?.width() ?: service.resources.displayMetrics.widthPixels
        val cutout = CutoutMetrics.displayCutoutBoundsPx(service)?.takeIf {
            it.centerY() < dp(64f) && it.centerX() >= screenWidth/4f && it.centerX() <= screenWidth*3/4f
        }
        val cameraX = cutout?.centerX()?.roundToInt() ?: screenWidth / 2
        val preview = NexoraCalibration.mode.value.takeUnless { keyguard.isKeyguardLocked } ?: 0
        val isExpanded = if (preview != 0) preview == 2 else expanded
        val scale = prefs.getInt("island_width",100).coerceIn(85,120)/100f
        val requestedHeight = prefs.getInt("island_height_v2",28).coerceIn(20,44)
        val cameraRadius = max(dp(6f), (cutout?.width() ?: 0f) / 2f + dp(1.5f))
        physicalY = cutout?.centerY() ?: dp(17f)
        val cameraHalfHeight = (cutout?.height()?.div(2f) ?: dp(6f)) + dp(1.5f)
        val topHeight = max(dp(requestedHeight.toFloat()).roundToInt(),ceil(cameraHalfHeight*2).toInt())
        val minY=ceil(physicalY+cameraHalfHeight-topHeight).toInt().coerceAtLeast(0)
        val maxY=floor(physicalY-cameraHalfHeight).toInt().coerceAtLeast(minY)
        // Center the thin capsule on the camera, instead of filling the whole status bar.
        anchorY=(physicalY-topHeight/2f+dp(prefs.getInt("island_y_v2",0).coerceIn(-10,10).toFloat())).roundToInt().coerceIn(minY,maxY)
        val width = min(screenWidth - dp(12f).toInt(), dp((if (isExpanded) 324f else if (next.kind == "music") 136f else if (next.kind == "energy") 152f else 176f)*scale).toInt())
        val height = topHeight + if (isExpanded) dp(100f).toInt() else 0
        centerX = cameraX
        val maxOffset = (width/2f-cameraRadius-dp(44f)).coerceAtLeast(0f)
        anchorX = cameraX + dp(prefs.getInt("island_x",0).coerceIn(-24,24).toFloat()).coerceIn(-maxOffset,maxOffset).toInt()
        screenWidthPx = screenWidth
        val x = (anchorX - width / 2).coerceIn(0, (screenWidth-width).coerceAtLeast(0))
        v.cameraRadius = cameraRadius
        v.topHeight = topHeight.toFloat()
        v.preview = preview != 0
        v.card = next
        v.count = all.size
        v.index = selection
        v.open = isExpanded
        if (params == null) {
            val seed = min(width, max(dp(28f).toInt(), (v.cameraRadius * 2 + dp(5f)).toInt()))
            val lp = WindowManager.LayoutParams(seed, topHeight, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = (anchorX - seed / 2).coerceIn(0,(screenWidth-seed).coerceAtLeast(0))
            lp.y = anchorY
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            lp.title = "NEXORA"
            try { wm.addView(v, lp); params = lp; targetWidth = 0; targetHeight = 0; place(v,lp.x.toFloat(),lp.y.toFloat(),seed.toFloat(),topHeight.toFloat()); v.alpha=if(preview!=0)1f else .2f; v.enter(); morph(v,width,height) } catch (_: Exception) { panel = null; params = null; return }
        } else {
            if (leaving) { leaving = false; geometry?.cancel(); targetWidth = 0; targetHeight = 0; v.alpha = 1f }
            if (changed) v.enter()
            if (preview != 0) {
                geometry?.cancel(); geometry = null
                val lp = params ?: return
                layout(v,x,anchorY,width,height)
                place(v,x.toFloat(),anchorY.toFloat(),width.toFloat(),height.toFloat())
                targetWidth=width; targetHeight=height; targetX=x; targetY=anchorY; v.alpha=1f
            } else {
                morph(v,width,height)
            }
        }
        v.invalidate()
        if (preview != 0) return
        val now=SystemClock.uptimeMillis()
        val nextDeadline=listOf(autoCollapseAt,if(flash!=null)flashUntil else 0L).filter{it>now}.minOrNull()
        handler.postDelayed(refresh, min(1000L,(nextDeadline?.minus(now) ?: 1000L).coerceAtLeast(1L)))
    }
    private fun hide() {
        val v = panel ?: return
        if (leaving) return
        leaving = true
        morph(v, max(dp(24f).toInt(),(v.cameraRadius*2+dp(4f)).toInt()), v.topHeight.toInt(), dismiss = true)
    }
    private fun remove() {
        leaving = false
        geometry?.cancel(); geometry = null; targetWidth = 0; targetHeight = 0
        panel?.let { it.animate().cancel(); runCatching { wm.removeViewImmediate(it) } }
        panel = null; params = null
    }
    fun onOrientationChanged(orientation: Int) { remove(); render() }
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (screenReceiverRegistered) runCatching { service.unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
        scope.cancel()
        remove()
    }
    private fun swipe(delta: Int) {
        if(ringing())return
        autoCollapseAt=0L; autoFocus=null
        browsing = true
        val all = cards()
        if (all.isNotEmpty()) selection = (selection + delta + all.size) % all.size
        expanded = true
        render()
    }
    // Use the dialer's own action intents; never invent answer/decline actions.
    private fun actionRole(title:String):Int {
        val t=title.lowercase(java.util.Locale.ROOT)
        return when {
            listOf("decline","reject","hang up","end call","weigeren","afwijzen","ophangen","beëindigen").any{t.contains(it)} -> -1
            listOf("answer","accept","opnemen","accepteren","beantwoorden").any{t.contains(it)} -> 1
            else -> 0
        }
    }
    private fun callButtons():List<CutoutSignal.Notification.Action> {
        val actions=callActions.filter{it.reply==null}
        return if(ringing()) actions.sortedBy{actionRole(it.title)}.let { sorted ->
            val known=sorted.filter{actionRole(it.title)!=0}
            (if(known.isNotEmpty())known else sorted).take(2)
        } else actions.take(2)
    }
    private fun tap(x: Float, y: Float) {
        val c = current ?: return
        autoCollapseAt=0L; autoFocus=null
        if (!expanded) { expanded = true; render(); return }
        if (y < (panel?.topHeight ?: 0f)) { if(!ringing()){expanded = false; browsing = false; render()}; return }
        if (keyguard.isKeyguardLocked) return
        if (c.kind == "music" && y > (panel?.topHeight ?: 0f) + dp(58f)) {
            NowPlayingBus.state.value?.transport?.let {
                val fraction = x / (panel?.visualWidth?.coerceAtLeast(1f) ?: 1f)
                when { fraction < .415f -> it.previous(); fraction > .585f -> it.next(); else -> it.playPause() }
            }
        } else if (c.kind == "timer" && y > (panel?.topHeight ?: 0f) + dp(58f)) {
            RunningTimerBus.state.value?.actions?.firstOrNull()?.intent?.let { runCatching { it.send() } }
        } else if (c.kind == "call" && y > (panel?.topHeight ?: 0f) + dp(58f)) {
            val actions=callButtons()
            val w=panel?.visualWidth ?: return
            val left=dp(14f)
            val gap=dp(8f)
            if(actions.isNotEmpty() && x>=left && x<=w-left && y<=(panel?.topHeight ?: 0f)+dp(89f)){
                val bw=(w-2*left-gap*(actions.size-1))/actions.size
                val i=actions.indices.firstOrNull{ index ->
                    val start=left+index*(bw+gap)
                    x>=start && x<=start+bw
                }
                i?.let { index -> runCatching { actions[index].intent.send() } }
            }
        } else if (c.intent != null) runCatching { c.intent.send() }
        else if (c.key != null) CutoutNotificationListenerService.openFromInbox(c.key)
        else { expanded = false; render() }
    }
    private inner class IslandView : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private val silhouette = Path()
        private val normalTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private val mediumTypeface = Typeface.create("sans-serif", Typeface.BOLD)
        private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val signature = LinearGradient(0f,0f,1f,1f,0xFF75BFFF.toInt(),0xFFB59AFF.toInt(),Shader.TileMode.CLAMP)
        private val gradientMatrix = Matrix()
        private val waveHeights = floatArrayOf(8f,16f,23f,15f,7f)
        private val albumSource = Rect()
        private var began = SystemClock.uptimeMillis()
        private var downX = 0f; private var downY = 0f
        private var downTime = 0L
        var cameraX = 0f; var cameraY = 0f; var cameraRadius = 0f; var topHeight = 0f
        var visualX=0f; var visualY=0f; var visualWidth=0f; var visualHeight=0f
        var drawX=0f; var drawY=0f
        var card = Card("system", "")
        var count = 1; var index = 0; var open = false; var preview = false
        init { isClickable = true; contentDescription = "NEXORA live activity" }
        fun enter() {
            began = SystemClock.uptimeMillis()
            invalidate()
        }
        private fun color(c: Int, a: Int = 255) { paint.color = c; paint.alpha = a; paint.style = Paint.Style.FILL; paint.shader = null }
        private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, col: Int, maxWidth: Float, bold: Boolean = false) {
            if (maxWidth <= 0f) return
            color(col); paint.textSize = dp(size); paint.typeface = if (bold) mediumTypeface else normalTypeface
            val line = android.text.TextUtils.ellipsize(value.replace('\n', ' '), textPaint.apply { set(paint) }, maxWidth, android.text.TextUtils.TruncateAt.END)
            canvas.drawText(line.toString(), x, y, paint)
        }
        // Reference layout: 122:31 compact and 264:105 expanded. Camera stays in physical coordinates.
        private fun gradient(x: Float, y: Float, w: Float, h: Float) {
            color(Color.WHITE)
            gradientMatrix.reset()
            gradientMatrix.setScale(w.coerceAtLeast(1f),h.coerceAtLeast(1f))
            gradientMatrix.postTranslate(x,y)
            signature.setLocalMatrix(gradientMatrix)
            paint.shader = signature
        }
        private fun album(canvas: Canvas,x: Float,y: Float,s: Float,r: Float) {
            rect.set(x,y,x+s,y+s)
            val bitmap = if(preview) null else NowPlayingBus.state.value?.albumArt?.asAndroidBitmap()
            if(bitmap==null) {
                gradient(x,y,s,s)
                canvas.drawRoundRect(rect,r,r,paint)
                paint.shader=null
            } else {
                color(Color.WHITE)
                paint.isFilterBitmap=true
                canvas.save()
                path.reset(); path.addRoundRect(rect,r,r,Path.Direction.CW);canvas.clipPath(path)
                val side=min(bitmap.width,bitmap.height)
                albumSource.set((bitmap.width-side)/2,(bitmap.height-side)/2,(bitmap.width+side)/2,(bitmap.height+side)/2)
                canvas.drawBitmap(bitmap,albumSource,rect,paint)
                canvas.restore()
            }
        }
        private fun transport(canvas: Canvas,x: Float,y: Float,kind: Int,s: Float) {
            color(Color.WHITE)
            paint.strokeCap=Paint.Cap.ROUND
            paint.strokeWidth=s*.13f
            val paused=!preview && NowPlayingBus.state.value?.isPlaying!=true
            if(kind==0 && !paused) {
                canvas.drawLine(x-s*.22f,y-s*.43f,x-s*.22f,y+s*.43f,paint)
                canvas.drawLine(x+s*.22f,y-s*.43f,x+s*.22f,y+s*.43f,paint)
            } else {
                val dir=if(kind<0) -1f else 1f
                path.reset()
                path.moveTo(x-dir*s*.30f,y-s*.48f)
                path.lineTo(x+dir*s*.43f,y)
                path.lineTo(x-dir*s*.30f,y+s*.48f)
                path.close();canvas.drawPath(path,paint)
                if(kind!=0)canvas.drawLine(x+dir*s*.52f,y-s*.43f,x+dir*s*.52f,y+s*.43f,paint)
            }
        }
        private fun musicLayout(canvas: Canvas,w:Float,h:Float,expansion:Float,age:Float) {
            val compactAlpha=(1f-expansion*1.9f).coerceIn(0f,1f)*((w-dp(82f))/dp(40f)).coerceIn(0f,1f)
            if(compactAlpha>0f) {
                if(compactAlpha<.999f) canvas.saveLayerAlpha(0f,0f,w,h,(compactAlpha*255).toInt()) else canvas.save()
                val s=min(min(dp(20f),topHeight-dp(8f)),(cameraX-cameraRadius-dp(13f)).coerceAtLeast(0f)).coerceAtLeast(0f)
                if(s>0f) album(canvas,dp(9f),cameraY-s/2,s,s*.26f)
                val playing=preview || NowPlayingBus.state.value?.isPlaying==true
                val waveW=dp(23f)
                val start=max(cameraX+cameraRadius+dp(5f),w-dp(10f)-waveW)
                if(start+waveW<w-dp(4f)) {
                    for(i in 0..4) {
                        val bar=min(topHeight-dp(8f),dp(if(playing) waveHeights[i]*(.72f+.24f*sin(age*5.2f+i*1.3f)) else 5f)).coerceAtLeast(dp(2f))
                        val bx=start+i*dp(4.6f)
                        gradient(bx,cameraY-bar/2,dp(3f),bar)
                        canvas.drawRoundRect(bx,cameraY-bar/2,bx+dp(3f),cameraY+bar/2,dp(1.5f),dp(1.5f),paint)
                    }
                }
                canvas.restore()
            }
            val a=((expansion-.18f)/.72f).coerceIn(0f,1f)
            if(a<=0f)return
            if(a<.999f) canvas.saveLayerAlpha(0f,0f,w,h,(a*255).toInt()) else canvas.save()
            canvas.translate(0f,dp(8f)*(1f-a))
            val y=topHeight
            val margin=dp(21f)
            val art=dp(43f)
            val artY=y-dp(5f)
            album(canvas,margin,artY,art,dp(10f))
            val tx=margin+art+dp(14f)
            text(canvas,card.title,tx,artY+dp(16f),15f,Color.WHITE,w-tx-margin,true)
            text(canvas,card.detail.ifBlank{"Now playing"},tx,artY+dp(34f),12f,0xFF9DA1AA.toInt(),w-tx-margin)
            val barY=y+dp(50f)
            color(0xFF292929.toInt())
            canvas.drawRoundRect(margin,barY,w-margin,barY+dp(3.5f),dp(1.75f),dp(1.75f),paint)
            val progress=if(preview) .60f else NowPlayingBus.state.value?.progress?.fractionAt(SystemClock.elapsedRealtime())
            if(progress!=null && progress>0f) {
                val end=margin+(w-2*margin)*progress
                gradient(margin,barY,w-2*margin,0f)
                canvas.drawRoundRect(margin,barY,end,barY+dp(3.5f),dp(1.75f),dp(1.75f),paint)
            }
            val buttonsY=y+dp(73f)
            transport(canvas,w*.34f,buttonsY,-1,dp(18f))
            transport(canvas,w*.50f,buttonsY,0,dp(18f))
            transport(canvas,w*.66f,buttonsY,1,dp(18f))
            if(count>1) for(i in 0 until min(count,8)) {
                color(if(i==index) VIOLET else 0xFF333333.toInt())
                canvas.drawCircle(w/2+(i-(min(count,8)-1)/2f)*dp(8f),y+dp(92f),dp(1.7f),paint)
            }
            canvas.restore()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val age = (SystemClock.uptimeMillis() - began) / 1000f
            val w = visualWidth; val h = visualHeight
            if(w<=0f || h<=0f)return
            canvas.save()
            canvas.translate(drawX,drawY)
            try {
            val accent = card.accent
            val fade = (1f - age / 1.2f).coerceIn(0f,1f)
            val inset = dp(.5f)
            val expansion = ((h-topHeight)/dp(100f)).coerceIn(0f,1f)
            val radius = ((topHeight-2*inset)/2*(1f-expansion)+dp(31f)*expansion).coerceIn(0f, max(0f,(h-2*inset)/2))
            rect.set(inset, inset, w-inset, h-inset)
            color(Color.BLACK); canvas.drawRoundRect(rect, radius, radius, paint)
            silhouette.reset(); silhouette.addRoundRect(rect,radius,radius,Path.Direction.CW)
            canvas.clipPath(silhouette)
            // Reference has a pure black silhouette without a visible outline.
            if(card.kind=="music") {
                if(w>dp(105f)) musicLayout(canvas,w,h,expansion,age)
                if(preview || age<1.3f || NowPlayingBus.state.value?.isPlaying==true)postInvalidateOnAnimation()
                return
            }
            if (w < dp(82f)) { if(age<1.3f) postInvalidateOnAnimation(); return }
            val contentAlpha = ((w-dp(82f))/dp(38f)).coerceIn(0f,1f)
            if (contentAlpha < .999f) canvas.saveLayerAlpha(0f,0f,w,h,(contentAlpha*255).toInt()) else canvas.save()
            val left = dp(14f)
            val right = cameraX + cameraRadius + dp(9f)
            val cy = cameraY
            val maxRight = (w-right-dp(12f)).coerceAtLeast(0f)
            val iconX = if(card.kind=="energy") cameraX*.46f else left+dp(8f)
            color(accent); paint.strokeWidth = dp(2f); paint.strokeCap = Paint.Cap.ROUND
            when(card.kind) {
                "call" -> {
                    val photo=OnCallBus.state.value?.photo?.asAndroidBitmap()
                    if(photo!=null && !keyguard.isKeyguardLocked){
                        val s=dp(20f);rect.set(iconX-s/2,cy-s/2,iconX+s/2,cy+s/2)
                        canvas.save();path.reset();path.addRoundRect(rect,s/2,s/2,Path.Direction.CW);canvas.clipPath(path);color(Color.WHITE);canvas.drawBitmap(photo,null,rect,paint);canvas.restore()
                    } else {
                        color(GREEN);paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2f);canvas.drawArc(iconX-dp(7f),cy-dp(7f),iconX+dp(7f),cy+dp(7f),85f,170f,false,paint);paint.style=Paint.Style.FILL
                    }
                }
                "energy" -> {
                    val pulse=(age/.40f).coerceIn(0f,1f)
                    val settle=1f-(1f-pulse)*(1f-pulse)*(1f-pulse)
                    canvas.save(); canvas.scale(.72f+.28f*settle,.72f+.28f*settle,iconX,cy)
                    paint.alpha=(settle*255).toInt()
                    path.reset(); path.moveTo(iconX+dp(2f),cy-dp(10f));path.lineTo(iconX-dp(6f),cy+dp(1f));path.lineTo(iconX,cy+dp(1f));path.lineTo(iconX-dp(2f),cy+dp(10f));path.lineTo(iconX+dp(7f),cy-dp(2f));path.lineTo(iconX+dp(1f),cy-dp(2f));path.close();canvas.drawPath(path,paint);canvas.restore()
                    // Compact charge card: only the lightning glyph and true battery level.
                }
                "unlock","lock" -> {
                    paint.style=Paint.Style.STROKE
                    val ring=cameraRadius+dp(1.5f)
                    if(ring+dp(1f)<min(cy,topHeight-cy)) {
                        val t=(age/.55f).coerceIn(0f,1f)
                        canvas.drawArc(cameraX-ring,cy-ring,cameraX+ring,cy+ring,-90f,360f*(1f-(1f-t)*(1f-t)),false,paint)
                    }
                    if(card.kind=="unlock"){
                        val t=(age/.32f).coerceIn(0f,1f)
                        val first=(t*2f).coerceIn(0f,1f)
                        val second=(t*2f-1f).coerceIn(0f,1f)
                        canvas.drawLine(iconX-dp(6f),cy,iconX-dp(6f)+dp(5f)*first,cy+dp(5f)*first,paint)
                        if(second>0f)canvas.drawLine(iconX-dp(1f),cy+dp(5f),iconX-dp(1f)+dp(9f)*second,cy+dp(5f)-dp(10f)*second,paint)
                    }
                    else canvas.drawRoundRect(iconX-dp(6f),cy-dp(4f),iconX+dp(6f),cy+dp(6f),dp(2f),dp(2f),paint)
                    paint.style=Paint.Style.FILL
                }
                "call" -> {
                    color(GREEN); paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(2.6f)
                    path.reset();path.moveTo(iconX-dp(6f),cy-dp(7f));path.cubicTo(iconX-dp(7f),cy+dp(1f),iconX+dp(1f),cy+dp(8f),iconX+dp(7f),cy+dp(7f));canvas.drawPath(path,paint)
                    paint.style=Paint.Style.FILL;canvas.drawRoundRect(iconX-dp(9f),cy-dp(9f),iconX-dp(3f),cy-dp(3f),dp(2f),dp(2f),paint);canvas.drawRoundRect(iconX+dp(3f),cy+dp(3f),iconX+dp(9f),cy+dp(9f),dp(2f),dp(2f),paint)
                }
                "timer" -> {
                    paint.style=Paint.Style.STROKE;canvas.drawCircle(iconX,cy,dp(7f),paint);canvas.drawLine(iconX,cy,iconX,cy-dp(4f),paint);canvas.drawLine(iconX,cy,iconX+dp(3f),cy+dp(2f),paint);paint.style=Paint.Style.FILL
                }
                "connect" -> {
                    paint.style=Paint.Style.STROKE
                    for(i in 1..3){val reveal=((age-i*.075f)/.32f).coerceIn(0f,1f);paint.alpha=(reveal*255).toInt();val r=dp(i*3f+3f);canvas.drawArc(iconX-r,cy-r,iconX+r,cy+r,215f,110f,false,paint)}
                    paint.style=Paint.Style.FILL; paint.alpha=255;canvas.drawCircle(iconX,cy+dp(3f),dp(2f),paint)
                }
                else -> {
                    val icon = card.appIcon
                    if (icon != null) {
                        icon.setBounds((iconX-dp(11f)).toInt(),(cy-dp(11f)).toInt(),(iconX+dp(11f)).toInt(),(cy+dp(11f)).toInt())
                        val reveal=(age/.28f).coerceIn(0f,1f)
                    val eased=1f-(1f-reveal)*(1f-reveal)*(1f-reveal)
                    canvas.save();canvas.scale(.82f+.18f*eased,.82f+.18f*eased,iconX,cy);icon.draw(canvas);canvas.restore()
                    } else { canvas.drawCircle(iconX,cy,dp(5f+2f*fade),paint);color(accent,48);canvas.drawCircle(iconX,cy,dp(10f+2f*fade),paint) }
                }
            }
            val short = when(card.kind) {
                "call" -> {
                    val started=OnCallBus.state.value?.startTimeMs
                    if(started!=null && OnCallBus.state.value?.ongoing==true){
                        val sec=((System.currentTimeMillis()-started)/1000).coerceAtLeast(0L)
                        String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60)
                    } else "Call"
                }
                "energy" -> card.detail.ifBlank { "Power" }
                "music" -> if (NowPlayingBus.state.value?.isPlaying == true) "Playing" else "Paused"
                "unlock" -> "Unlocked"
                "connect" -> card.title
                "timer" -> RunningTimerBus.state.value?.let { timer ->
                    val now=SystemClock.elapsedRealtime()
                    val ms=timer.pausedRemainingMs ?: timer.endElapsedRealtimeMs?.let{if(timer.countsUp)now-it else it-now} ?: 0L
                    val sec=ms.coerceAtLeast(0L)/1000
                    String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60)
                } ?: "Timer"
                else -> card.title
            }
            if (card.kind == "energy") {
                val labelX=max(right,cameraX+(w-cameraX)*.28f)
                text(canvas,short,labelX,cy+dp(5f),14f,accent,w-labelX-dp(8f),true)
            } else text(canvas,short,right,cy+dp(4f),11f,if(card.kind=="message") Color.WHITE else accent,maxRight,true)
            if(expansion > .15f) {
                val reveal=((expansion-.15f)/.75f).coerceIn(0f,1f)
                if (expansion < .90f) canvas.saveLayerAlpha(0f,topHeight,w,h,(reveal*255).toInt()) else canvas.save()
                canvas.translate(0f,dp(8f)*(1f-reveal))
                val y=topHeight
                text(canvas,card.title,left,y+dp(22f),17f,Color.WHITE,w-left*2,true)
                var detail=card.detail
                if(card.kind=="call" && OnCallBus.state.value?.ongoing==true) detail="Call in progress  •  $short"
                if(card.kind=="call" && !keyguard.isKeyguardLocked) OnCallBus.state.value?.startTimeMs?.let { start ->
                    val seconds=((System.currentTimeMillis()-start)/1000).coerceAtLeast(0)
                    detail=String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60)
                }
                if(card.kind=="timer") RunningTimerBus.state.value?.let { t ->
                    val now=SystemClock.elapsedRealtime(); val ms=t.pausedRemainingMs ?: t.endElapsedRealtimeMs?.let { if(t.countsUp) now-it else it-now } ?: 0L
                    val secs=ms.coerceAtLeast(0)/1000;detail=String.format(java.util.Locale.ROOT,"%02d:%02d",secs/60,secs%60)
                }
                text(canvas,detail,left,y+dp(42f),12f,Color.LTGRAY,w-left*2)
                val progress=card.progress
                if(progress!=null){color(Color.DKGRAY);canvas.drawRoundRect(left,y+dp(49f),w-left,y+dp(52f),dp(2f),dp(2f),paint);color(accent);canvas.drawRoundRect(left,y+dp(49f),left+(w-2*left)*progress.coerceIn(0f,1f),y+dp(52f),dp(2f),dp(2f),paint)}
                val actions=if(card.kind=="call" && !keyguard.isKeyguardLocked) callButtons() else emptyList()
                if(actions.isNotEmpty()){
                    val gap=dp(8f)
                    val bw=(w-2*left-gap*(actions.size-1))/actions.size
                    actions.forEachIndexed{i,action->
                        val bx=left+i*(bw+gap)
                        val role=actionRole(action.title)
                        color(when(role){-1->0xFFCD354A.toInt();1->0xFF238C56.toInt();else->0xFF24262A.toInt()})
                        canvas.drawRoundRect(bx,y+dp(57f),bx+bw,y+dp(89f),dp(16f),dp(16f),paint)
                        text(canvas,action.title,bx+dp(10f),y+dp(77f),12f,Color.WHITE,bw-dp(20f),true)
                    }
                } else {
                    val action=if(card.kind=="timer")RunningTimerBus.state.value?.actions?.firstOrNull()?.title else null
                    text(canvas,action ?: if(card.intent!=null||card.key!=null) "Open  •  Swipe to switch" else if(keyguard.isKeyguardLocked && card.kind=="call") "Unlock to manage call" else "Swipe to switch",left,y+dp(74f),12f,accent,w-2*left)
                }
                if(count>1) for(i in 0 until min(count,8)){color(if(i==index) accent else Color.DKGRAY);canvas.drawCircle(w/2+(i-(min(count,8)-1)/2f)*dp(8f),h-dp(8f),dp(2f),paint)}
                canvas.restore()
            }
            canvas.restore()
            if(age<1.3f) postInvalidateOnAnimation()
            } finally { canvas.restore() }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(preview) return true
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {downX=event.x;downY=event.y;downTime=SystemClock.uptimeMillis();return true}
                MotionEvent.ACTION_UP -> {
                    val dx=event.x-downX;val dy=event.y-downY
                    when {
                        abs(dx)>dp(35f) && abs(dx)>abs(dy) -> swipe(if(dx<0) 1 else -1)
                        dy < -dp(25f) -> {if(!ringing()){flash=null;expanded=false;browsing=false;selection=0;autoCollapseAt=0L;autoFocus=null;render()}}
                        SystemClock.uptimeMillis()-downTime>500 -> {if(!ringing()){browsing=true;expanded=true;autoCollapseAt=0L;autoFocus=null;render()}}
                        else -> {performClick();tap(event.x-drawX,event.y-drawY)}
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
                else -> return true
            }
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
