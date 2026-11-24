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
import kotlin.math.*

/** NEXORA Reborn: independent native Canvas renderer; only the event producers are shared. */
class NexoraOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = service.getSharedPreferences("nexora", Context.MODE_PRIVATE)
    private val density = service.resources.displayMetrics.density
    private fun dp(x: Float) = x * density
    private var panel: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    private var flash: Card? = null
    private var flashUntil = 0L
    private var selection = 0
    private var expanded = false
    private var browsing = false
    private var mediaIntent: PendingIntent? = null
    private var callIntent: PendingIntent? = null
    private var timerIntent: PendingIntent? = null
    private var callActions: List<CutoutSignal.Notification.Action> = emptyList()
    private var current: Card? = null
    private var leaving = false
    private val refresh = Runnable { render() }
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private data class Card(val kind: String, val title: String, val detail: String = "", val accent: Int = BLUE,
        val intent: PendingIntent? = null, val key: String? = null, val progress: Float? = null)
    companion object {
        private val BLUE = Color.rgb(99, 202, 255)
        private val VIOLET = Color.rgb(181, 151, 255)
        private val GREEN = Color.rgb(89, 235, 164)
        private val RED = Color.rgb(255, 119, 137)
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        scope.launch { IslandEventBus.signals.collect { signal ->
            when (signal) {
                is CutoutSignal.Notification -> {
                    // Never remove or snooze the user's actual notification in the new renderer.
                    signal.key?.let { CutoutNotificationListenerService.release(it) }
                    if (prefs.getBoolean("notifications", true) && !keyguard.isKeyguardLocked) {
                        val p = signal.progressData
                        show(Card("message", signal.title ?: signal.appName ?: "Notification",
                            signal.text ?: signal.appName.orEmpty(), BLUE, signal.contentIntent, signal.key,
                            p?.takeIf { it.max > 0 }?.let { it.current.toFloat() / it.max }))
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
                        t.contains("DISCONNECTED") || t == "BATTERY_LOW" -> RED
                        kind == "energy" || kind == "unlock" -> GREEN
                        else -> BLUE
                    }
                    show(Card(kind, signal.payload.title ?: service.getString(signal.type.labelRes),
                        signal.payload.collapsedBadgeText ?: signal.payload.subtitle.orEmpty(), accent))
                }
                is CutoutSignal.SystemEnded -> Unit
                is CutoutSignal.Music -> { mediaIntent = signal.contentIntent; render() }
                is CutoutSignal.Call -> { callIntent = signal.contentIntent; callActions = signal.actions; render() }
                is CutoutSignal.Timer -> { timerIntent = signal.contentIntent; render() }
            }
        } }
        scope.launch { NowPlayingBus.state.collect { render() } }
        scope.launch { OnCallBus.state.collect { render() } }
        scope.launch { RunningTimerBus.state.collect { render() } }
        scope.launch { DeviceLockBus.locked.collect { if (it) { flash = null; browsing = false; expanded = false }; render() } }
        scope.launch { NotificationInboxBus.items.collect { if (browsing) render() } }
        render()
    }
    private fun show(card: Card) {
        flash = card
        flashUntil = SystemClock.uptimeMillis() + 4000
        selection = 0
        browsing = false
        expanded = true
        render()
    }
    private fun cards(): List<Card> {
        val result = mutableListOf<Card>()
        val locked = keyguard.isKeyguardLocked
        if (SystemClock.uptimeMillis() >= flashUntil) {
            if (flash != null && !browsing) expanded = false
            flash = null
        }
        flash?.takeIf { !locked || it.kind != "message" }?.let { result.add(it) }
        if (locked) return result
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
    private fun render() {
        handler.removeCallbacks(refresh)
        if (!prefs.getBoolean("enabled", true) || !power.isInteractive || service.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            flash = null; expanded = false; browsing = false; remove(); return
        }
        val all = cards()
        if (all.isEmpty()) { selection = 0; browsing = false; expanded = false; current = null; hide(); return }
        selection = selection.coerceIn(0, all.lastIndex)
        val next = all[selection]
        val changed = current?.kind != next.kind || current?.title != next.title
        current = next
        val v = panel ?: IslandView().also { panel = it }
        val metrics = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics else null
        val screenWidth = metrics?.bounds?.width() ?: service.resources.displayMetrics.widthPixels
        val cutout = if (Build.VERSION.SDK_INT >= 30) metrics?.windowInsets?.displayCutout?.boundingRects?.firstOrNull { it.top <= dp(24f) && it.centerX() in (screenWidth / 4)..(screenWidth * 3 / 4) } else null
        val cameraX = cutout?.centerX() ?: screenWidth / 2
        val topHeight = max(dp(36f).toInt(), (cutout?.bottom ?: 0) + dp(6f).toInt())
        val width = min(screenWidth - dp(12f).toInt(), dp(if (expanded) 338f else 228f).toInt())
        val height = topHeight + if (expanded) dp(100f).toInt() else 0
        val x = (cameraX - width / 2).coerceIn(0, (screenWidth-width).coerceAtLeast(0))
        v.cameraX = (cameraX - x).toFloat()
        v.cameraY = cutout?.centerY()?.toFloat() ?: topHeight * .46f
        v.cameraRadius = max(dp(8f), (cutout?.width() ?: 0) / 2f + dp(3f))
        v.topHeight = topHeight.toFloat()
        v.card = next
        v.count = all.size
        v.index = selection
        v.open = expanded
        if (params == null) {
            val lp = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = x
            lp.y = 0
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            lp.title = "NEXORA"
            try { wm.addView(v, lp); params = lp; v.enter() } catch (_: Exception) { panel = null; return }
        } else {
            val lp = params ?: return
            if (lp.width != width || lp.height != height || lp.x != x) {
                lp.width = width; lp.height = height; lp.x = x
                runCatching { wm.updateViewLayout(v, lp) }
            }
            if (leaving) { leaving = false; v.animate().cancel(); v.enter() }
            else if (changed) v.enter()
        }
        v.invalidate()
        if (flash != null) handler.postDelayed(refresh, (flashUntil - SystemClock.uptimeMillis()).coerceAtLeast(30L))
        else handler.postDelayed(refresh, 1000)
    }
    private fun hide() {
        val v = panel ?: return
        if (leaving) return
        leaving = true
        v.animate().alpha(0f).scaleX(.25f).scaleY(.8f).setDuration(210).withEndAction { if (leaving) remove() }.start()
    }
    private fun remove() {
        leaving = false
        panel?.let { it.animate().cancel(); runCatching { wm.removeViewImmediate(it) } }
        panel = null; params = null
    }
    fun onOrientationChanged(orientation: Int) { remove(); render() }
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        scope.cancel()
        remove()
    }
    private fun swipe(delta: Int) {
        browsing = true
        val all = cards()
        if (all.isNotEmpty()) selection = (selection + delta + all.size) % all.size
        expanded = true
        render()
    }
    private fun tap(x: Float, y: Float) {
        val c = current ?: return
        if (!expanded) { expanded = true; render(); return }
        if (y < (panel?.topHeight ?: 0f)) { expanded = false; browsing = false; render(); return }
        if (keyguard.isKeyguardLocked) return
        if (c.kind == "music" && y > (panel?.topHeight ?: 0f) + dp(58f)) {
            NowPlayingBus.state.value?.transport?.let {
                val fraction = x / (panel?.width?.toFloat() ?: 1f)
                when { fraction < .34f -> it.previous(); fraction > .66f -> it.next(); else -> it.playPause() }
            }
        } else if (c.kind == "timer" && y > (panel?.topHeight ?: 0f) + dp(58f)) {
            RunningTimerBus.state.value?.actions?.firstOrNull()?.intent?.let { runCatching { it.send() } }
        } else if (c.kind == "call" && y > (panel?.topHeight ?: 0f) + dp(58f)) {
            callActions.firstOrNull()?.intent?.let { runCatching { it.send() } }
        } else if (c.intent != null) runCatching { c.intent.send() }
        else if (c.key != null) CutoutNotificationListenerService.openFromInbox(c.key)
        else { expanded = false; render() }
    }
    private inner class IslandView : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private var began = SystemClock.uptimeMillis()
        private var downX = 0f; private var downY = 0f
        private var downTime = 0L
        var cameraX = 0f; var cameraY = 0f; var cameraRadius = 0f; var topHeight = 0f
        var card = Card("system", "")
        var count = 1; var index = 0; var open = false
        init { isClickable = true; contentDescription = "NEXORA live activity" }
        fun enter() {
            began = SystemClock.uptimeMillis()
            animate().cancel()
            pivotX = cameraX; pivotY = cameraY
            alpha = 0f; scaleX = .32f; scaleY = .85f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(PathInterpolator(.18f, .9f, .22f, 1f)).start()
        }
        private fun color(c: Int, a: Int = 255) { paint.color = c; paint.alpha = a; paint.style = Paint.Style.FILL; paint.shader = null }
        private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, col: Int, maxWidth: Float, bold: Boolean = false) {
            if (maxWidth <= 0f) return
            color(col); paint.textSize = dp(size); paint.typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
            val line = android.text.TextUtils.ellipsize(value.replace('\n', ' '), android.text.TextPaint(paint), maxWidth, android.text.TextUtils.TruncateAt.END)
            canvas.drawText(line.toString(), x, y, paint)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val age = (SystemClock.uptimeMillis() - began) / 1000f
            val w = width.toFloat(); val h = height.toFloat()
            val accent = card.accent
            val fade = (1f - age / 1.2f).coerceIn(0f,1f)
            val inset = dp(2f)
            rect.set(inset, inset, w-inset, h-inset)
            color(Color.BLACK); canvas.drawRoundRect(rect, dp(if(open) 28f else 20f), dp(if(open) 28f else 20f), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f); paint.shader = LinearGradient(0f,0f,w,h, intArrayOf(accent,VIOLET,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            paint.alpha = (65 + 145*fade).toInt()
            canvas.drawRoundRect(rect, dp(if(open) 28f else 20f), dp(if(open) 28f else 20f), paint)
            paint.shader = null; paint.style = Paint.Style.FILL
            val left = dp(15f)
            val right = cameraX + cameraRadius + dp(9f)
            val cy = cameraY
            val maxRight = (w-right-dp(12f)).coerceAtLeast(0f)
            val iconX = left + dp(10f)
            color(accent); paint.strokeWidth = dp(2f); paint.strokeCap = Paint.Cap.ROUND
            when(card.kind) {
                "energy" -> {
                    path.reset(); path.moveTo(iconX+dp(2f),cy-dp(10f));path.lineTo(iconX-dp(6f),cy+dp(1f));path.lineTo(iconX,cy+dp(1f));path.lineTo(iconX-dp(2f),cy+dp(10f));path.lineTo(iconX+dp(7f),cy-dp(2f));path.lineTo(iconX+dp(1f),cy-dp(2f));path.close();canvas.drawPath(path,paint)
                    color(accent, (fade*180).toInt()); canvas.drawRoundRect(left, topHeight-dp(5f), left+(w-2*left)*(1f-fade),topHeight-dp(3f),dp(2f),dp(2f),paint)
                }
                "unlock","lock" -> {
                    paint.style=Paint.Style.STROKE
                    canvas.drawArc(cameraX-cameraRadius-dp(5f),cy-cameraRadius-dp(5f),cameraX+cameraRadius+dp(5f),cy+cameraRadius+dp(5f),-90f+age*160f, min(360f,90f+age*380f),false,paint)
                    if(card.kind=="unlock"){canvas.drawLine(iconX-dp(6f),cy,iconX-dp(1f),cy+dp(5f),paint);canvas.drawLine(iconX-dp(1f),cy+dp(5f),iconX+dp(8f),cy-dp(5f),paint)}
                    else canvas.drawRoundRect(iconX-dp(6f),cy-dp(4f),iconX+dp(6f),cy+dp(6f),dp(2f),dp(2f),paint)
                    paint.style=Paint.Style.FILL
                }
                "connect" -> {
                    paint.style=Paint.Style.STROKE
                    for(i in 1..3){paint.alpha=(80+175*((age*2f+i*.21f)%1f)).toInt();val r=dp(i*3f+3f);canvas.drawArc(iconX-r,cy-r,iconX+r,cy+r,215f,110f,false,paint)}
                    paint.style=Paint.Style.FILL; paint.alpha=255;canvas.drawCircle(iconX,cy+dp(3f),dp(2f),paint)
                }
                "music" -> {
                    val art=NowPlayingBus.state.value?.albumArt
                    if(art!=null) { val bitmap=art.asAndroidBitmap(); rect.set(left,cy-dp(11f),left+dp(22f),cy+dp(11f));canvas.save();path.reset();path.addRoundRect(rect,dp(6f),dp(6f),Path.Direction.CW);canvas.clipPath(path);color(Color.WHITE);canvas.drawBitmap(bitmap,null,rect,paint);canvas.restore() }
                    else for(i in 0..4){ val bar=dp(3f+13f*(.5f+.5f*sin(age*6+i*1.4f)));canvas.drawRoundRect(left+i*dp(5f),cy-bar/2,left+i*dp(5f)+dp(3f),cy+bar/2,dp(2f),dp(2f),paint) }
                }
                else -> { canvas.drawCircle(iconX,cy,dp(5f+3f*fade),paint);color(accent,70);canvas.drawCircle(iconX,cy,dp(11f+3f*fade),paint) }
            }
            val short = when(card.kind) {
                "energy" -> card.detail.ifBlank { "Power" }
                "music" -> "Playing"
                "unlock" -> "Unlocked"
                "connect" -> "Connected"
                "timer" -> "Timer"
                else -> card.title
            }
            text(canvas,short,right,cy+dp(4f),11f,accent,maxRight,true)
            if(open) {
                val y=topHeight
                text(canvas,card.title,left,y+dp(22f),17f,Color.WHITE,w-left*2,true)
                var detail=card.detail
                if(card.kind=="timer") RunningTimerBus.state.value?.let { t ->
                    val now=SystemClock.elapsedRealtime(); val ms=t.pausedRemainingMs ?: t.endElapsedRealtimeMs?.let { if(t.countsUp) now-it else it-now } ?: 0L
                    val secs=ms.coerceAtLeast(0)/1000;detail=String.format(java.util.Locale.ROOT,"%02d:%02d",secs/60,secs%60)
                }
                text(canvas,detail,left,y+dp(42f),12f,Color.LTGRAY,w-left*2)
                val progress=if(card.kind=="music") NowPlayingBus.state.value?.progress?.fractionAt(SystemClock.elapsedRealtime()) else card.progress
                if(progress!=null){color(Color.DKGRAY);canvas.drawRoundRect(left,y+dp(49f),w-left,y+dp(52f),dp(2f),dp(2f),paint);color(accent);canvas.drawRoundRect(left,y+dp(49f),left+(w-2*left)*progress.coerceIn(0f,1f),y+dp(52f),dp(2f),dp(2f),paint)}
                if(card.kind=="music") {
                    text(canvas,"|◀",w*.15f,y+dp(78f),21f,accent,w*.2f,true)
                    text(canvas,if(NowPlayingBus.state.value?.isPlaying==true) "Ⅱ" else "▶",w*.47f,y+dp(78f),22f,Color.WHITE,w*.2f,true)
                    text(canvas,"▶|",w*.78f,y+dp(78f),21f,accent,w*.2f,true)
                } else {
                    val action=when(card.kind){"timer"->RunningTimerBus.state.value?.actions?.firstOrNull()?.title;"call"->callActions.firstOrNull()?.title;else->null}
                    text(canvas,action ?: if(card.intent!=null||card.key!=null) "Open  •  Swipe to switch" else "Swipe to switch",left,y+dp(74f),12f,accent,w-2*left)
                }
                for(i in 0 until min(count,8)){color(if(i==index) accent else Color.DKGRAY);canvas.drawCircle(w/2+(i-(min(count,8)-1)/2f)*dp(8f),h-dp(8f),dp(2f),paint)}
            }
            if(age<1.3f || (card.kind=="music" && NowPlayingBus.state.value?.isPlaying==true)) postInvalidateOnAnimation()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {downX=event.x;downY=event.y;downTime=SystemClock.uptimeMillis();return true}
                MotionEvent.ACTION_UP -> {
                    val dx=event.x-downX;val dy=event.y-downY
                    when {
                        abs(dx)>dp(35f) && abs(dx)>abs(dy) -> swipe(if(dx<0) 1 else -1)
                        dy < -dp(25f) -> {flash=null;expanded=false;browsing=false;selection=0;render()}
                        SystemClock.uptimeMillis()-downTime>500 -> {browsing=true;expanded=true;render()}
                        else -> {performClick();tap(event.x,event.y)}
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
