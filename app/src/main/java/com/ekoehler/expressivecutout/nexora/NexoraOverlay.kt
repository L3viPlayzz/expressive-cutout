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
import androidx.compose.ui.graphics.asAndroidBitmap
import com.ekoehler.expressivecutout.core.*
import com.ekoehler.expressivecutout.service.CutoutNotificationListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlin.math.*

/** Process-local only: calibration must never remain visible after leaving the screen. */
internal object NexoraCalibration {
    val mode = kotlinx.coroutines.flow.MutableStateFlow(0) // 0 off, 1 compact, 2 expanded, 3 charging
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity=4)
    val integrationEnabled=kotlinx.coroutines.flow.MutableStateFlow(false)
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
    private val pending = java.util.ArrayDeque<Pair<Long,Card>>()
    private var inboxPage=0
    private var inboxOpen=false
    private var notchTarget:View?=null
    private var notchBounds:Rect?=null
    private var notchOwner:View?=null
    private var notchStartX=0f
    private var notchStartY=0f
    private var notchStartTime=0L
    private var notchTravel=0f
    private var lastTapTime=0L
    private var lastTapX=0f
    private var lastTapY=0f
    private var queuedTap:Runnable?=null
    private var secondTap=false
    private fun notchAction(gesture:NotchGesture)=NotchAction.read(prefs.getString(gesture.key,null),gesture.defaultAction)
    private fun notchAllowed()=enabled("enabled") && enabled("notch_inbox") && displayAwake() && !keyguard.isKeyguardLocked && !ringing() && NexoraCalibration.mode.value==0 && service.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT
    private fun cancelNotchGesture(){
        queuedTap?.let{handler.removeCallbacks(it)}
        queuedTap=null;lastTapTime=0L;notchOwner=null;secondTap=false
    }
    private fun dispatchNotch(gesture:NotchGesture){
        if(!notchAllowed())return
        when(notchAction(gesture)){
            NotchAction.NONE->Unit
            NotchAction.INBOX->if(inboxOpen)closeInbox() else openInbox()
            NotchAction.SHADE->service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            NotchAction.QUICK_SETTINGS->service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            NotchAction.NEXT_ACTIVITY->swipe(1,true)
            NotchAction.PREVIOUS_ACTIVITY->swipe(-1,true)
            NotchAction.PLAY_PAUSE->runCatching{NowPlayingBus.state.value?.transport?.playPause()}
            NotchAction.NEXT_TRACK->runCatching{NowPlayingBus.state.value?.transport?.next()}
            NotchAction.PREVIOUS_TRACK->runCatching{NowPlayingBus.state.value?.transport?.previous()}
            NotchAction.SETTINGS->runCatching{service.startActivity(android.content.Intent(service,NexoraActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}
            NotchAction.DISMISS->{inboxOpen=false;browsing=false;expanded=false;flash=null;pending.clear();autoCollapseAt=0L;autoFocus=null;selectedId=null;render()}
        }
    }
    private fun notchTouch(owner:View,e:MotionEvent):Boolean{
        if(!notchAllowed()){cancelNotchGesture();return false}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                notchOwner=owner;notchStartX=e.rawX;notchStartY=e.rawY;notchStartTime=SystemClock.uptimeMillis();notchTravel=0f
                secondTap=NexoraPolicy.isDoubleTap(notchStartTime-lastTapTime,ViewConfiguration.getDoubleTapTimeout().toLong(),hypot(e.rawX-lastTapX,e.rawY-lastTapY),dp(24f),queuedTap!=null)
                if(secondTap){queuedTap?.let{handler.removeCallbacks(it)};queuedTap=null}
                return true
            }
            MotionEvent.ACTION_MOVE->{if(notchOwner===owner)notchTravel=max(notchTravel,hypot(e.rawX-notchStartX,e.rawY-notchStartY));return true}
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->{cancelNotchGesture();return true}
            MotionEvent.ACTION_UP->{
                if(notchOwner!==owner)return true
                notchOwner=null
                val dx=e.rawX-notchStartX;val dy=e.rawY-notchStartY
                val slop=dp(prefs.getInt("notch_swipe_distance",28).coerceIn(18,56).toFloat())
                val elapsed=SystemClock.uptimeMillis()-notchStartTime
                val code=NexoraPolicy.gesture(dx,dy,slop,elapsed)
                val tapSlop=min(dp(12f),slop*.55f)
                if(code==0 && max(notchTravel,hypot(dx,dy))<=tapSlop){
                    if(enabled("haptics"))owner.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    owner.performClick()
                    if(notchAction(NotchGesture.DOUBLE)==NotchAction.NONE){cancelNotchGesture();dispatchNotch(NotchGesture.TAP);return true}
                    val now=SystemClock.uptimeMillis()
                    val interval=ViewConfiguration.getDoubleTapTimeout().toLong()
                    val double=secondTap || NexoraPolicy.isDoubleTap(now-lastTapTime,interval,hypot(e.rawX-lastTapX,e.rawY-lastTapY),dp(24f),queuedTap!=null)
                    if(double){cancelNotchGesture();dispatchNotch(NotchGesture.DOUBLE)}
                    else{
                        queuedTap?.let{handler.removeCallbacks(it)}
                        lastTapTime=now;lastTapX=e.rawX;lastTapY=e.rawY
                        val job=Runnable{queuedTap=null;lastTapTime=0L;dispatchNotch(NotchGesture.TAP)}
                        queuedTap=job;handler.postDelayed(job,interval)
                    }
                } else {
                    cancelNotchGesture()
                    val g=when(code){1->NotchGesture.LEFT;-1->NotchGesture.RIGHT;2->NotchGesture.UP;5->NotchGesture.DOWN;4->if(notchTravel<=tapSlop)NotchGesture.HOLD else null;else->null}
                    if(g!=null){if(enabled("haptics"))owner.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);dispatchNotch(g)}
                }
                return true
            }
            else->return true
        }
    }
    private var inboxRows:List<InboxNotification> = emptyList()
    private fun eligibleInbox()=if(enabled("inbox") && enabled("notifications")) NotificationInboxBus.items.value.filter{it.packageName !in mutedApps && (enabled("silent_notifications") || !it.silent)} else emptyList()
    private fun openInbox(){
        if(ringing() || keyguard.isKeyguardLocked || !enabled("enabled"))return
        val rows=eligibleInbox()
        if(rows.isEmpty())return
        inboxOpen=true;inboxPage=0;browsing=false;expanded=true;autoCollapseAt=0L;autoFocus="inbox";selectedId=null
        render()
    }
    private fun closeInbox(){
        inboxOpen=false;inboxPage=0;expanded=false;browsing=false;autoFocus=null;selectedId=null
        render()
    }
    private fun removeNotch(){
        if(notchTarget!=null){
            cancelNotchGesture()
            notchTarget?.let{runCatching{wm.removeViewImmediate(it)}}
        }
        notchTarget=null;notchBounds=null
    }
    private fun syncNotch(){
        // A small transparent target even when idle if a configured shortcut is useful.
        // Never creates a screen-wide overlay or draws on AOD.
        if(panel!=null || !displayVisible || !notchAllowed()){removeNotch();return}
        val useful=NotchGesture.entries.any{g->
            when(notchAction(g)){
                NotchAction.NONE,NotchAction.DISMISS,NotchAction.NEXT_ACTIVITY,NotchAction.PREVIOUS_ACTIVITY->false
                NotchAction.INBOX->eligibleInbox().isNotEmpty()
                NotchAction.PLAY_PAUSE,NotchAction.NEXT_TRACK,NotchAction.PREVIOUS_TRACK->NowPlayingBus.state.value!=null
                else->true
            }
        }
        if(!useful){removeNotch();return}
        val bounds=CutoutMetrics.displayCutoutBoundsPx(service) ?: run{removeNotch();return}
        if(bounds.centerY()>dp(64f)){removeNotch();return}
        val pad=dp(prefs.getInt("notch_padding",6).coerceIn(0,12).toFloat())
        val width=max(dp(24f),bounds.width()+2*pad).roundToInt()
        val height=max(dp(22f),bounds.height()+2*pad).roundToInt()
        val x=(bounds.centerX()-width/2f).roundToInt().coerceAtLeast(0)
        val y=(bounds.centerY()-height/2f).roundToInt().coerceAtLeast(0)
        val area=Rect(x,y,x+width,y+height)
        if(notchTarget!=null && notchBounds==area)return
        removeNotch()
        val target=object:View(service){
            override fun performClick():Boolean{super.performClick();return true}
            override fun onTouchEvent(event:MotionEvent)=notchTouch(this,event)
        }.apply{isClickable=true;contentDescription="NEXORA camera shortcuts"}
        val lp=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.LEFT;lp.x=x;lp.y=y
        lp.layoutInDisplayCutoutMode=if(Build.VERSION.SDK_INT>=30)WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.title="NEXORA camera shortcuts"
        runCatching{wm.addView(target,lp);notchTarget=target;notchBounds=area}
    }
    private var mutedApps:Set<String> = emptySet()
    private var foregroundPackage:String?=null
    private fun hidePlayer(pkg:String?)=NexoraPolicy.hideDuplicate(enabled("hide_in_player"),foregroundPackage,pkg)
    private fun hideCall(pkg:String?)=NexoraPolicy.hideDuplicate(enabled("hide_in_dialer"),foregroundPackage,pkg)
    private fun focused()=enabled("focus_mode",false)
    private var batteryLevel = -1
    private val progressKeys=mutableSetOf<String>()
    private var plugged = false
    private fun enabled(key:String, fallback:Boolean=true)=prefs.getBoolean(key,fallback)
    private fun expandedMs()=NexoraPolicy.expandedMillis(3)
    private fun compactMs()=NexoraPolicy.compactMillis(3)
    private fun refreshBattery(intent:android.content.Intent?) {
        if(intent==null)return
        val level=intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1)
        val scale=intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100)
        batteryLevel=if(level>=0 && scale>0)(level*100/scale).coerceIn(0,100) else -1
        plugged=intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0)!=0
    }
    private var flashUntil = 0L
    private var autoCollapseAt = 0L
    private var autoFocus: String? = null
    private var selection = 0
    private var selectedId:String?=null
    private fun Card.identity():String=key?.let{"notification:$it"} ?: when(kind){"music","call","timer","energy","unlock","lock"->kind;else->"$kind:${eventType ?: title}"}
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
    // px/ms, retained across compatible retargets.
    private var vx=0f;private var vy=0f;private var vw=0f;private var vh=0f
    private var reducedMotion=prefs.getBoolean("reduce_motion",false)
    private val iconCache=android.util.LruCache<String,android.graphics.drawable.Drawable>(40)
    private fun appIcon(pkg:String):android.graphics.drawable.Drawable? {
        iconCache.get(pkg)?.let{return it}
        return runCatching{service.packageManager.getApplicationIcon(pkg)}.getOrNull()?.also{iconCache.put(pkg,it)}
    }
    private var targetWidth = 0
    private var targetHeight = 0
    private var targetX = 0
    private var targetY = 0
    private var centerX = 0
    private var anchorX = 0
    private var physicalY = 0f
    private var anchorY = 0
    private var screenWidthPx = 0
    private val refresh = Runnable { render() }
    private val queuedRender=Runnable{render()}
    private fun scheduleRender(){handler.removeCallbacks(queuedRender);handler.post(queuedRender)}
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reducedMotion=prefs.getBoolean("reduce_motion",false);NexoraCalibration.integrationEnabled.value=enabled("enabled");scheduleRender() }
    private val displayManager = service.getSystemService(android.hardware.display.DisplayManager::class.java)
    private var displayListenerRegistered = false
    private var screenReceiverRegistered = false
    private var lastUnlockAt = -10000L
    private var hadLockedScreen = false
    private var displayVisible=false
    private fun displayAwake():Boolean = NexoraPolicy.canDraw(power.isInteractive, displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON)
    private val displayListener = object:android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id:Int)=Unit
        override fun onDisplayRemoved(id:Int){if(id==Display.DEFAULT_DISPLAY){removeNotch();remove()}}
        override fun onDisplayChanged(id:Int){
            if(id==Display.DEFAULT_DISPLAY) {
                if(!displayAwake()) { displayVisible=false; handler.removeCallbacks(refresh); cancelNotchGesture();removeNotch();remove() }
                else render()
            }
        }
    }
    private fun confirmedUnlock(){
        if(!displayAwake() || keyguard.isDeviceLocked)return
        hadLockedScreen=false
        if(!enabled("enabled") || !enabled("events") || !enabled("event_unlock") || ringing())return
        val now=SystemClock.uptimeMillis()
        if(now-lastUnlockAt<1300L)return
        lastUnlockAt=now
        hadLockedScreen=false
        if(focused())return
        // A confirmed OS unlock, never an inferred face scan or a fake authentication prompt.
        activate(Card("unlock","Unlocked","Device unlocked",GREEN),now)
        render()
    }
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                handler.removeCallbacks(refresh)
                displayVisible=false
                pending.clear();  hadLockedScreen=keyguard.isDeviceLocked || keyguard.isKeyguardLocked
                flash = null; expanded = false; browsing = false; current = null; autoCollapseAt = 0L; autoFocus = null
                inboxOpen=false;cancelNotchGesture();removeNotch();remove()
            } else {
                when(intent?.action){
                    android.content.Intent.ACTION_BATTERY_CHANGED -> refreshBattery(intent)
                    android.content.Intent.ACTION_SCREEN_ON -> if(keyguard.isDeviceLocked)hadLockedScreen=true else if(hadLockedScreen)confirmedUnlock()
                    android.content.Intent.ACTION_USER_PRESENT -> if(hadLockedScreen)confirmedUnlock()
                }
                render()
            }
        }
    }
    private data class Card(val kind: String, val title: String, val detail: String = "", val accent: Int = BLUE,
        val intent: PendingIntent? = null, val key: String? = null, val progress: Float? = null, val appIcon: android.graphics.drawable.Drawable? = null, val actions: List<CutoutSignal.Notification.Action> = emptyList(), val settingsAction:String?=null, val packageName:String?=null, val eventType:String?=null)
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
        NexoraCalibration.integrationEnabled.value=enabled("enabled")
        hadLockedScreen=keyguard.isDeviceLocked
        displayListenerRegistered=runCatching{displayManager?.registerDisplayListener(displayListener,handler);displayManager!=null}.getOrDefault(false)
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
        }
        screenReceiverRegistered = runCatching {
            val sticky=if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else service.registerReceiver(screenReceiver, filter)
            refreshBattery(sticky)
            true
        }.getOrDefault(false)
        scope.launch { IslandEventBus.signals.collect { signal ->
            when (signal) {
                is CutoutSignal.Notification -> {
                    // Never remove or snooze the user's actual notification in the new renderer.
                    // The listener is configured not to hold notifications in Reborn.
                    if (!focused() && signal.packageName !in mutedApps && enabled("notifications") && !keyguard.isKeyguardLocked && (!signal.isSilent || enabled("silent_notifications"))) {
                        val p = signal.progressData
                        val live=p!=null && !p.isComplete && enabled("live_progress")
                        val duplicate=live && signal.key?.let{!progressKeys.add(it)}==true
                        if(duplicate && flash?.key!=signal.key) return@collect
                        if(p?.isComplete==true)signal.key?.let{progressKeys.remove(it)}
                        show(Card(if(p!=null && !p.isComplete && enabled("live_progress"))"progress" else "message", if(enabled("message_text"))signal.title ?: signal.appName ?: "Notification" else signal.appName ?: "Notification",
                            if(enabled("message_text")) signal.text ?: signal.appName.orEmpty() else signal.appName.orEmpty(), BLUE, signal.contentIntent, signal.key,
                            p?.takeIf { it.max > 0 }?.let { it.current.toFloat() / it.max },
                            appIcon(signal.packageName), if(enabled("message_text"))signal.actions.filter{it.reply==null}.take(2) else emptyList(), packageName=signal.packageName))
                    }
                }
                is CutoutSignal.System -> if (!focused() && prefs.getBoolean("events", true)) {
                    val t = signal.type.name
                    if(t=="DEVICE_LOCKED"){hadLockedScreen=true;return@collect}
                    if(t=="DEVICE_UNLOCKED"){confirmedUnlock();return@collect}
                    val kind = when {
                        t.startsWith("CHARGING") || t == "BATTERY_LOW" -> "energy"
                        t == "DEVICE_UNLOCKED" -> "unlock"
                        t == "DEVICE_LOCKED" -> "lock"
                        t.contains("WIFI") || t.contains("BLUETOOTH") || t.contains("VPN") || t.contains("HEADPHONES") || t.contains("HOTSPOT") -> "connect"
                        else -> "system"
                    }
                    if(!enabled("event_"+kind)) return@collect
                    val accent = when {
                        t.contains("DISCONNECTED") || t == "BATTERY_LOW" || t == "CHARGING_STOPPED" -> RED
                        kind == "energy" || kind == "unlock" -> GREEN
                        else -> BLUE
                    }
                    val charging=t=="CHARGING_STARTED" || t=="CHARGING_COMPLETE"
                    val pct=signal.payload.collapsedBadgeText?.trim()?.removeSuffix("%")?.toIntOrNull()?.coerceIn(0,100)
                        ?: batteryLevel.takeIf{it>=0}
                    show(Card(kind, signal.payload.title ?: service.getString(signal.type.labelRes),
                        if(charging && pct!=null)"$pct%" else signal.payload.collapsedBadgeText ?: signal.payload.subtitle.orEmpty(), accent,
                        progress=if(charging)pct?.div(100f) else null, settingsAction=signal.payload.actionIntentAction,eventType=t))
                }
                is CutoutSignal.SystemEnded -> Unit
                is CutoutSignal.Music -> {
                    mediaIntent = signal.contentIntent
                    if(!focused() && prefs.getBoolean("music",true) && signal.packageName !in mutedApps && !keyguard.isKeyguardLocked && !hidePlayer(signal.packageName) && current?.kind!="music") openLive("music")
                    else render()
                }
                is CutoutSignal.Call -> {
                    callIntent = signal.contentIntent; callActions = signal.actions
                    if (!signal.ongoing) openLive("call") else render()
                }
                is CutoutSignal.Timer -> { timerIntent = signal.contentIntent; if(enabled("timers") && !focused() && current?.kind!="timer")openLive("timer") else render() }
            }
        } }
        scope.launch { com.ekoehler.expressivecutout.data.AppPreferences(service).disabledPackages.collect { mutedApps=it; render() } }
        scope.launch { NexoraCalibration.mode.collect { render() } }
        scope.launch { NexoraCalibration.requests.collect { request ->
            if(!ringing() && !keyguard.isKeyguardLocked && enabled("enabled")) {
                if(request=="demo_unlock"){show(Card("unlock","Unlocked","Animation preview",GREEN));return@collect}
                if(request=="demo_charge"){show(Card("energy","Charging","84%",GREEN,progress=.84f));return@collect}
                if(request=="demo_lock"){show(Card("lock","Locked","Animation preview",VIOLET));return@collect}
                if(request=="demo_message") {
                    show(Card("message","NEXORA preview","Your notification animation",BLUE,appIcon=appIcon(service.packageName)))
                    return@collect
                }
                if(request=="inbox"){openInbox();return@collect}
                flash=null; pending.clear(); autoCollapseAt=0L; expanded=true; browsing=request=="inbox"; selection=0; selectedId=null
                autoFocus=if(NotificationInboxBus.items.value.any{it.progress==null})"message" else "progress"; render()
            }
        } }
        scope.launch { ForegroundAppBus.packageName.collect{pkg->foregroundPackage=pkg;scheduleRender()} }
        scope.launch { NowPlayingBus.state.collect { scheduleRender() } }
        scope.launch { OnCallBus.state.collect { call ->
            val incoming = call?.ongoing == false
            if (incoming && !wasRinging) {
                cancelNotchGesture()
                inboxOpen=false;flash=null; flashUntil=0L; browsing=false
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
        scope.launch { RunningTimerBus.state.collect { scheduleRender() } }
        scope.launch { DeviceLockBus.locked.collect { locked ->
            if(locked){inboxOpen=false;cancelNotchGesture();removeNotch();hadLockedScreen=true;flash=null;pending.clear();browsing=false;expanded=false;autoCollapseAt=0L;autoFocus=null}
            else if(hadLockedScreen)confirmedUnlock()
            render()
        } }
        scope.launch { NotificationInboxBus.items.collect { items ->
            val active=items.filter{it.progress!=null || it.progressIndeterminate}.mapTo(mutableSetOf()){it.key}
            progressKeys.retainAll(active)
            if (browsing || inboxOpen || enabled("notch_inbox") || enabled("live_progress")) scheduleRender()
        } }
        render()
    }
    private fun openLive(kind: String) {
        if((ringing() || inboxOpen) && kind!="call"){scheduleRender();return}
        inboxOpen=false;flash=null; flashUntil=0L
        autoFocus=kind
        browsing=false
        expanded=true
        autoCollapseAt=if(kind=="call" && ringing())0L else SystemClock.uptimeMillis()+expandedMs()
        render()
    }
    private fun show(card: Card) {
        if(!enabled("enabled") || !displayAwake())return
        // Incoming calls keep priority until answered or declined.
        val now=SystemClock.uptimeMillis()
        if(card.kind=="progress" && flash?.key!=card.key && NotificationInboxBus.items.value.any{it.key==card.key && (it.progress!=null || it.progressIndeterminate)} && current?.key==card.key){render();return}
        if(card.kind=="progress" && card.key!=null && card.key==flash?.key){
            flash=card; render(); return
        }
        if(ringing() || inboxOpen || (enabled("queue_events",false) && flash!=null && now<autoCollapseAt && (card.key==null || card.key!=flash?.key))){
            if(card.key!=null)pending.removeAll{it.second.key==card.key}
            if(pending.size>=8)pending.removeFirst()
            pending.addLast(now to card)
            return
        }
        activate(card,now)
        render()
    }
    private fun activate(card:Card, now:Long) {
        inboxOpen=false;flash=card
        autoCollapseAt=now+expandedMs()
        flashUntil=autoCollapseAt+compactMs()
        autoFocus=card.kind
        selection=0
        browsing=false
        expanded=true
    }
    private fun cards(): List<Card> {
        val result = mutableListOf<Card>()
        val locked = keyguard.isKeyguardLocked
        val preview = NexoraCalibration.mode.value
        if (!locked && preview != 0) return listOf(
            if (preview == 3) Card("energy", "Charging", "84%", GREEN, progress=.84f)
            else Card("music", "Midnight Drive", "Now playing", VIOLET, progress = .60f)
        )
        val now=SystemClock.uptimeMillis()
        if(NexoraPolicy.shouldCollapse(now,autoCollapseAt,ringing())){
            autoCollapseAt=0L; expanded=false
        }
        if(now>=flashUntil || flash?.packageName?.let{it in mutedApps}==true) flash=null
        if(flash?.kind in listOf("message","progress") && !enabled("notifications"))flash=null
        if(focused()){flash=null;pending.clear()}
        pending.removeAll{(_,card)->card.packageName?.let{it in mutedApps}==true || (card.kind in listOf("message","progress") && !enabled("notifications"))}
        if(flash?.kind=="progress" && !enabled("live_progress"))flash=null
        while(pending.isNotEmpty() && now-pending.peekFirst()!!.first>30000L)pending.removeFirst()
        if(!locked && !ringing() && !browsing && !inboxOpen && flash==null && pending.isNotEmpty()) activate(pending.removeFirst().second,now)
        flash?.takeIf { !locked || (it.kind != "message" && it.kind != "progress") }?.let {
            if(it.kind in setOf("message","progress") && !enabled("message_text")) result.add(it.copy(title=it.packageName?.let{pkg->runCatching{service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg,0)).toString()}.getOrNull()} ?: "Notification",detail="",actions=emptyList())) else result.add(it)
        }
        if (locked) {
            if(enabled("lock_wake") && enabled("events") && enabled("event_unlock") && keyguard.isDeviceLocked && result.none{it.kind=="lock" || it.kind=="unlock"} && !ringing())result.add(Card("lock","Locked","Unlock your device",VIOLET))
            OnCallBus.state.value?.let { result.add(Card("call",if(it.ongoing)"On call" else "Incoming call",if(it.ongoing)"Call in progress" else "Unlock to view",GREEN)) }
            return result
        }
        if(ringing())inboxOpen=false
        inboxRows=if(inboxOpen)eligibleInbox()else emptyList()
        if(inboxOpen && inboxRows.isNotEmpty()){
            inboxPage=NexoraPolicy.inboxPage(inboxPage,inboxRows.size)
            return listOf(Card("inbox","Notifications","",VIOLET))
        }
        if(inboxOpen){inboxOpen=false;expanded=false}
        if(!focused() && enabled("notifications") && enabled("live_progress")) NotificationInboxBus.items.value.filter{it.packageName !in mutedApps && (it.progress!=null || it.progressIndeterminate) && it.key!=flash?.key}.take(5).forEach{
            result.add(Card("progress",if(enabled("message_text"))it.title.ifBlank{it.appName}else it.appName,it.appName,BLUE,key=it.key,progress=it.progress,appIcon=appIcon(it.packageName)))
        }
        if(!focused() && enabled("charge_live",false) && enabled("events") && enabled("event_energy") && plugged && batteryLevel>=0 && flash?.kind!="energy") result.add(Card("energy","Charging","$batteryLevel%",GREEN,progress=batteryLevel/100f))
        if (!focused() && prefs.getBoolean("music", true)) NowPlayingBus.state.value?.takeIf { it.packageName !in mutedApps && !hidePlayer(it.packageName) && (it.isPlaying || (expanded && current?.kind == "music")) }?.let {
            result.add(Card("music", it.title ?: "Now playing", it.artist.orEmpty(), VIOLET, mediaIntent))
        }
        OnCallBus.state.value?.takeIf{!it.ongoing || !hideCall(it.packageName)}?.let { result.add(Card("call", it.callerLabel, if (it.ongoing) "On call" else "Incoming call", GREEN, callIntent)) }
        if(!focused() && enabled("timers"))RunningTimerBus.state.value?.let { result.add(Card("timer", it.label ?: "Timer", "", BLUE, timerIntent)) }
        if (browsing && enabled("inbox")) NotificationInboxBus.items.value.forEach {
            if (it.packageName !in mutedApps && it.key != flash?.key && result.none{card->card.key==it.key} && (enabled("silent_notifications") || !it.silent)) result.add(Card("message", if(enabled("message_text"))it.title else it.appName, it.appName, BLUE, key = it.key, appIcon=appIcon(it.packageName)))
        }
        if(browsing && result.none{it.kind=="message" || it.kind=="progress"})browsing=false
        return result
    }
    // Keep the window stable during animation. Only the Canvas geometry morphs per frame;
    // WindowManager gets one resize before the transition and one when it settles.
    private fun layout(v: IslandView, x: Int, y: Int, w: Int, h: Int, passive:Boolean=keyguard.isKeyguardLocked) {
        val lp = params ?: return
        val flags=if(passive)lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if(lp.x!=x || lp.y!=y || lp.width!=w || lp.height!=h || lp.flags!=flags) {
            lp.x=x; lp.y=y; lp.width=w; lp.height=h;lp.flags=flags
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
        layout(v,hostX,hostY,hostW.coerceAtLeast(1),hostH.coerceAtLeast(1),passive=true)
        place(v,fromX,fromY,fromW,fromH)
        val startAlpha=v.alpha
        geometry=ValueAnimator.ofFloat(0f,1f).apply {
            val base=if(dismiss)205L else if(h>fromH+dp(10f))325L else if(h<fromH-dp(10f))270L else 235L
            duration=NexoraPolicy.motionMillis(base,100,reducedMotion)
            interpolator=android.view.animation.LinearInterpolator()
            val dx=x-fromX; val dy=y-fromY;val dw=w-fromW;val dh=h-fromH
            val ms=duration.coerceAtLeast(1L)
            val sx=NexoraPolicy.entrySlope(dx,vx,ms)
            val sy=NexoraPolicy.entrySlope(dy,vy,ms)
            val sw=NexoraPolicy.entrySlope(dw,vw,ms)
            val sh=NexoraPolicy.entrySlope(dh,vh,ms)
            addUpdateListener { a ->
                val t=a.animatedValue as Float
                // X and width share a progress curve, keeping the capsule center stationary
                // during collapse. Independent velocities previously caused lateral drift.
                val horizontal=if(abs(dw)>.01f)sw else sx
                val px=NexoraPolicy.motionPosition(t,horizontal);val py=NexoraPolicy.motionPosition(t,sy)
                val ph=NexoraPolicy.motionPosition(t,sh)
                vx=dx*NexoraPolicy.motionVelocity(t,horizontal)/ms;vy=dy*NexoraPolicy.motionVelocity(t,sy)/ms
                vw=dw*NexoraPolicy.motionVelocity(t,horizontal)/ms;vh=dh*NexoraPolicy.motionVelocity(t,sh)/ms
                place(v,fromX+dx*px,fromY+dy*py,fromW+dw*px,fromH+dh*ph)
                v.alpha=if(dismiss)startAlpha*(1f-NexoraPolicy.smoothstep(t)) else NexoraPolicy.lerp(startAlpha,1f,NexoraPolicy.smoothstep(t))
            }
            addListener(object:android.animation.AnimatorListenerAdapter(){
                private var cancelled=false
                override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(animation:android.animation.Animator){
                    if(cancelled)return
                    vx=0f;vy=0f;vw=0f;vh=0f
                    if(dismiss && leaving){remove();syncNotch();return}
                    layout(v,x,y,w,h);place(v,x.toFloat(),y.toFloat(),w.toFloat(),h.toFloat())
                    CutoutWidthBus.update(if(v.preview || keyguard.isKeyguardLocked)null else if(v.open)CutoutKind.EXPANDED else CutoutKind.NORMAL,false,false)
                }
            })
            start()
        }
    }

    private fun render() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(queuedRender)
        displayVisible=displayAwake()
        if ((!prefs.getBoolean("enabled", true) && NexoraCalibration.mode.value == 0) || !displayVisible || service.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            inboxOpen=false;cancelNotchGesture();removeNotch();flash = null; pending.clear();  expanded = false; browsing = false; autoCollapseAt=0L; autoFocus=null; remove(); return
        }
        val all = cards()
        if (all.isEmpty()) { selection=0; selectedId=null; autoFocus=null; browsing=false; expanded=false; current=null; hide();syncNotch();return }
        removeNotch()
        val focus=autoFocus
        if(focus!=null){
            val position=all.indexOfFirst{it.kind==focus}
            if(position>=0)selection=position
            else if(focus=="message" || focus=="progress"){
                val fallback=all.indexOfFirst{it.kind=="message" || it.kind=="progress"}
                if(fallback>=0)selection=fallback else {expanded=false;browsing=false}
            }
            autoFocus=null
        } else {
            val previous=selectedId?.let{id->all.indexOfFirst{it.identity()==id}} ?: -1
            if(previous>=0)selection=previous
        }
        if(ringing() && NexoraCalibration.mode.value==0) {
            val callIndex=all.indexOfFirst{it.kind=="call"}
            if(callIndex>=0){selection=callIndex; expanded=true; autoCollapseAt=0L; autoFocus=null; browsing=false}
        }
        selection = selection.coerceIn(0, all.lastIndex)
        if(keyguard.isKeyguardLocked && !ringing()) {
            val lockIndex=all.indexOfFirst{it.kind=="unlock" || it.kind=="energy"}.takeIf{it>=0} ?: all.indexOfFirst{it.kind=="lock"}
            if(lockIndex>=0){selection=lockIndex;if(all[lockIndex].kind=="lock")expanded=false}
        }
        val next = all[selection]
        val changed = current?.identity()!=next.identity() || (next.kind=="music" && current?.title!=next.title)
        if(changed && autoCollapseAt==0L && !ringing() && !browsing && !inboxOpen && flash==null)expanded=false
        current = next
        selectedId=next.identity()
        val v = panel ?: IslandView().also { panel = it }
        val metrics = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics else null
        val screenWidth = metrics?.bounds?.width() ?: service.resources.displayMetrics.widthPixels
        val cutout = CutoutMetrics.displayCutoutBoundsPx(service)?.takeIf {
            it.centerY() < dp(64f) && it.centerX() >= screenWidth/4f && it.centerX() <= screenWidth*3/4f
        }
        val cameraX = cutout?.centerX()?.roundToInt() ?: screenWidth / 2
        val preview = NexoraCalibration.mode.value.takeUnless { keyguard.isKeyguardLocked } ?: 0
        val isExpanded = if (preview != 0) preview == 2 else expanded && !(next.kind=="lock" && keyguard.isKeyguardLocked)
        if(preview!=0 || keyguard.isKeyguardLocked)CutoutWidthBus.update(null,false,false)
        else if(isExpanded)CutoutWidthBus.update(CutoutKind.EXPANDED,false,false)
        else if(panel==null || panel?.open==false && geometry?.isRunning!=true)CutoutWidthBus.update(CutoutKind.NORMAL,false,false)
        val scale = prefs.getInt("island_width",100).coerceIn(85,120)/100f
        val requestedHeight = prefs.getInt("island_height_v2",28).coerceIn(20,44)
        val cameraRadius = max(dp(6f), (cutout?.width() ?: 0f) / 2f + dp(1.5f))
        physicalY = cutout?.centerY() ?: dp(17f)
        val cameraHalfHeight = (cutout?.height()?.div(2f) ?: dp(6f)) + dp(1.5f)
        val topHeight = NexoraPolicy.safeHeight(dp(requestedHeight.toFloat()).roundToInt(),ceil(cameraHalfHeight*2).toInt())
        val minY=ceil(physicalY+cameraHalfHeight-topHeight).toInt().coerceAtLeast(0)
        val maxY=floor(physicalY-cameraHalfHeight).toInt().coerceAtLeast(minY)
        // Center the thin capsule on the camera, instead of filling the whole status bar.
        anchorY=(physicalY-topHeight/2f+dp(prefs.getInt("island_y_v2",0).coerceIn(-10,10).toFloat())).roundToInt().coerceIn(minY,maxY)
        val compactWidth=min(screenWidth-dp(12f).toInt(),dp((if(next.kind=="music")136f else if(next.kind=="energy")152f else if(next.kind=="lock" || next.kind=="unlock")144f else 176f)*scale).toInt())
        val width=if(isExpanded)min(screenWidth-dp(12f).toInt(),dp(324f*scale).toInt())else compactWidth
        val height = topHeight + if (isExpanded) dp(if(next.kind=="inbox")152f else 100f).toInt() else 0
        centerX = cameraX
        val maxOffset = (compactWidth/2f-cameraRadius-dp(44f)).coerceAtLeast(0f)
        anchorX = cameraX + dp(prefs.getInt("island_x",0).coerceIn(-24,24).toFloat()).coerceIn(-maxOffset,maxOffset).toInt()
        screenWidthPx = screenWidth
        val x = (anchorX - width / 2).coerceIn(0, (screenWidth-width).coerceAtLeast(0))
        v.cameraRadius = cameraRadius
        v.topHeight = topHeight.toFloat()
        v.preview = preview != 0
        v.card = next
        v.updateAssets()
        v.count = all.size
        v.index = selection
        v.open = isExpanded
        val passive=keyguard.isKeyguardLocked || geometry?.isRunning==true || leaving
        if (params == null) {
            val seed = min(width, max(dp(28f).toInt(), (v.cameraRadius * 2 + dp(5f)).toInt()))
            val lp = WindowManager.LayoutParams(seed, topHeight, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if(passive)WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.x = (anchorX - seed / 2).coerceIn(0,(screenWidth-seed).coerceAtLeast(0))
            lp.y = anchorY
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            lp.title = "NEXORA"
            try { wm.addView(v, lp); params = lp; targetWidth = 0; targetHeight = 0; place(v,lp.x.toFloat(),lp.y.toFloat(),seed.toFloat(),topHeight.toFloat()); v.alpha=if(preview!=0)1f else .2f; v.enter(); morph(v,width,height) } catch (_: Exception) { panel = null; params = null; return }
        } else {
            params?.let{lp->
                val flags=if(passive)lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                if(flags!=lp.flags){lp.flags=flags;runCatching{wm.updateViewLayout(v,lp)}}
            }
            if (leaving) { leaving = false; geometry?.cancel(); targetWidth = 0; targetHeight = 0 }
            if (changed) v.enter()
            if (preview != 0) {
                geometry?.cancel(); geometry = null
                vx=0f;vy=0f;vw=0f;vh=0f
                val lp = params ?: return
                layout(v,x,anchorY,width,height)
                place(v,x.toFloat(),anchorY.toFloat(),width.toFloat(),height.toFloat())
                targetWidth=width; targetHeight=height; targetX=x; targetY=anchorY; v.alpha=1f
            } else {
                morph(v,width,height)
            }
        }
        v.invalidate()
        if (preview != 0 || inboxOpen) return
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
        CutoutWidthBus.update(null,false,false)
        geometry?.cancel(); geometry = null; targetWidth = 0; targetHeight = 0
        vx=0f;vy=0f;vw=0f;vh=0f
        panel?.let { it.animate().cancel(); runCatching { wm.removeViewImmediate(it) } }
        panel = null; params = null
    }
    fun onOrientationChanged(orientation: Int) { cancelNotchGesture();removeNotch();remove(); render() }
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        NexoraCalibration.integrationEnabled.value=false
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if(displayListenerRegistered)runCatching{displayManager?.unregisterDisplayListener(displayListener)}
        displayListenerRegistered=false
        if (screenReceiverRegistered) runCatching { service.unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
        scope.cancel()
        cancelNotchGesture();removeNotch();remove()
    }
    private fun swipe(delta: Int,fromNotch:Boolean=false) {
        if(ringing())return
        if(inboxOpen){val next=NexoraPolicy.inboxPage(inboxPage+delta,inboxRows.size);if(next!=inboxPage){inboxPage=next;panel?.pageTransition(delta)};return}
        if(!fromNotch && !enabled("swipe_switch"))return
        autoCollapseAt=0L; autoFocus=null
        browsing = enabled("inbox")
        val all = cards()
        if(all.size<=1){browsing=false;return}
        val idx=selectedId?.let{id->all.indexOfFirst{it.identity()==id}}?.takeIf{it>=0} ?: selection.coerceIn(0,all.lastIndex)
        selection=(idx+delta+all.size)%all.size
        selectedId=all[selection].identity()
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
        if(c.kind=="inbox"){
            val top=panel?.topHeight ?: return
            if(y<top){closeInbox();return}
            val row=((y-top-dp(25f))/dp(37f)).toInt()
            if(y>=top+dp(25f) && row in 0..2)inboxRows.getOrNull(inboxPage*3+row)?.let{CutoutNotificationListenerService.openFromInbox(it.key);closeInbox()}
            return
        }
        if (!expanded) { expanded = true; render(); return }
        if (y < (panel?.topHeight ?: 0f)) { if(!ringing()){expanded = false;  render()}; return }
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
        } else if(c.kind=="message" && enabled("message_text") && c.actions.isNotEmpty() && y in ((panel?.topHeight ?: 0f)+dp(57f))..((panel?.topHeight ?: 0f)+dp(89f))) {
            val w=panel?.visualWidth ?: return
            val left=dp(14f); val gap=dp(8f);val bw=(w-2*left-gap*(c.actions.size-1))/c.actions.size
            c.actions.forEachIndexed{i,action->if(x>=left+i*(bw+gap) && x<=left+i*(bw+gap)+bw)runCatching{action.intent.send()}}
        } else if (c.intent != null) runCatching { c.intent.send() }
        else if(c.settingsAction!=null)runCatching{service.startActivity(android.content.Intent(c.settingsAction).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}
        else if (c.key != null) CutoutNotificationListenerService.openFromInbox(c.key)
        else { expanded = false; render() }
    }
    private class TextEntry(var value:String="",var width:Int=-1,var size:Float=0f,var bold:Boolean=false,var result:String="")
    private inner class IslandView : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private val silhouette = Path()
        private val effectPath = Path()
        private val effectMeasure = PathMeasure()
        private val effectSegment = Path()
        private val securityPath=Path()
        private val cameraMask=Path()
        private val chargeSweep=LinearGradient(0f,0f,1f,0f,intArrayOf(0x00FFFFFF,0x8FFFFFFF.toInt(),0x00FFFFFF),null,Shader.TileMode.CLAMP)
        private val chargeGradient = LinearGradient(0f,0f,1f,0f,intArrayOf(0xFF239A70.toInt(),0xFF59EBA4.toInt(),0xFFB4FFE0.toInt()),null,Shader.TileMode.CLAMP)
        private val normalTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
        private val mediumTypeface = Typeface.create("sans-serif", Typeface.BOLD)
        private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val textCache=Array(12){TextEntry()}
        private var textSlot=0
        private val signature = LinearGradient(0f,0f,1f,1f,0xFF75BFFF.toInt(),0xFFB59AFF.toInt(),Shader.TileMode.CLAMP)
        private val gradientMatrix = Matrix()
        private val waveHeights = floatArrayOf(8f,16f,23f,15f,7f)
        private val albumSource = Rect()
        private var began = SystemClock.uptimeMillis()
        private var pageBegan=0L
        private var pageDirection=1
        fun pageTransition(direction:Int){pageDirection=direction;pageBegan=SystemClock.uptimeMillis();invalidate()}
        private var cachedAlbum:android.graphics.Bitmap?=null
        private var cachedPhoto:android.graphics.Bitmap?=null
        fun updateAssets(){cachedAlbum=if(preview)null else NowPlayingBus.state.value?.albumArt?.asAndroidBitmap();cachedPhoto=if(keyguard.isKeyguardLocked)null else OnCallBus.state.value?.photo?.asAndroidBitmap()}
        private var downX = 0f; private var downY = 0f
        private var downCard:String?=null
        private var downExpanded=false
        private var touchActive=false
        private var notchRoute=false
        private var maxTravel=0f
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
            val widthPx=maxWidth.toInt()
            var entry:TextEntry?=null
            for(e in textCache)if(e.width==widthPx && e.value==value && e.size==size && e.bold==bold){entry=e;break}
            if(entry==null){
                val slot=textCache[textSlot];textSlot=(textSlot+1)%textCache.size
                slot.value=value;slot.width=widthPx;slot.size=size;slot.bold=bold
                slot.result=android.text.TextUtils.ellipsize(value.replace('\n',' '),textPaint.apply{set(paint)},widthPx.toFloat(),android.text.TextUtils.TruncateAt.END).toString()
                entry=slot
            }
            canvas.drawText(entry.result,x,y,paint)
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
            val bitmap = cachedAlbum
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
            // A single album cover travels between compact and expanded geometry.
            // Its corners and size morph together, rather than fading between duplicate images.
            val t=NexoraPolicy.smoothstep(expansion)
            val small=NexoraPolicy.glyphSize(dp(20f),cameraX-cameraRadius-dp(13f),2f*min(cameraY,topHeight-cameraY)-dp(4f))
            val artSize=small+(dp(43f)-small)*t
            val artX=dp(9f)+dp(12f)*t
            val morphArtY=(cameraY-small/2)*(1f-t)+(topHeight-dp(5f))*t
            val artRadius=small*.26f+(dp(10f)-small*.26f)*t
            val artAlpha=((w-dp(82f))/dp(40f)).coerceIn(0f,1f)
            if(artSize>0f && artAlpha>0f) {
                if(artAlpha<.999f)canvas.saveLayerAlpha(0f,0f,w,h,(artAlpha*255).toInt()) else canvas.save()
                album(canvas,artX,morphArtY,artSize,artRadius)
                canvas.restore()
            }
            val compactAlpha=(1f-expansion*1.9f).coerceIn(0f,1f)*((w-dp(82f))/dp(40f)).coerceIn(0f,1f)
            if(compactAlpha>0f) {
                if(compactAlpha<.999f) canvas.saveLayerAlpha(0f,0f,w,h,(compactAlpha*255).toInt()) else canvas.save()
                val playing=!reducedMotion && (preview || NowPlayingBus.state.value?.isPlaying==true)
                val waveW=dp(23f)
                val start=max(cameraX+cameraRadius+dp(5f),w-dp(10f)-waveW)
                if(start+waveW<w-dp(4f)) {
                    for(i in 0..4) {
                        val envelope=if(playing) .66f+.20f*sin(age*4.6f+i*.94f)+.12f*sin(age*7.8f-i*.73f) else .22f
                        val bar=NexoraPolicy.glyphSize(dp(waveHeights[i]*envelope),dp(24f),2f*min(cameraY,topHeight-cameraY)-dp(4f))
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
            val controls=NexoraPolicy.phase(expansion,.38f,.95f)
            if(controls>0f){
                if(controls<.999f)canvas.saveLayerAlpha(0f,0f,w,h,(controls*255).toInt())else canvas.save()
                val gap=dp(5f)*(1f-controls)
                transport(canvas,w*.34f+gap,buttonsY+gap,-1,dp(18f)*(.9f+.1f*controls))
                transport(canvas,w*.50f,buttonsY+gap,0,dp(18f))
                transport(canvas,w*.66f-gap,buttonsY+gap,1,dp(18f)*(.9f+.1f*controls))
                canvas.restore()
            }
            if(count>1) for(i in 0 until min(count,8)) {
                color(if(i==index) VIOLET else 0xFF333333.toInt())
                canvas.drawCircle(w/2+(i-(min(count,8)-1)/2f)*dp(8f),y+dp(92f),dp(1.7f),paint)
            }
            canvas.restore()
        }
        private fun trace(canvas:Canvas,source:Path,fraction:Float){
            effectMeasure.setPath(source,false)
            effectSegment.reset()
            effectMeasure.getSegment(0f,effectMeasure.length*fraction.coerceIn(0f,1f),effectSegment,true)
            canvas.drawPath(effectSegment,paint)
        }
        private fun blend(a:Int,b:Int,t:Float):Int {
            val f=t.coerceIn(0f,1f)
            return Color.rgb(NexoraPolicy.lerp(Color.red(a).toFloat(),Color.red(b).toFloat(),f).roundToInt(),
                NexoraPolicy.lerp(Color.green(a).toFloat(),Color.green(b).toFloat(),f).roundToInt(),
                NexoraPolicy.lerp(Color.blue(a).toFloat(),Color.blue(b).toFloat(),f).roundToInt())
        }
        // A single glyph travels and scales with the capsule. Every coordinate is in local
        // drawing space and the outer silhouette is clipped before entering this renderer.
        private fun signatureLayout(canvas:Canvas,w:Float,h:Float,e:Float,rawAge:Float){
            val reduced=reducedMotion
            val age=if(reduced)2f else rawAge
            val t=NexoraPolicy.phase(e,.04f,.96f)
            val compactRoom=(cameraX-cameraRadius-dp(6f)).coerceAtLeast(0f)
            val compactX=compactRoom*.50f+dp(3f)
            val compactSize=NexoraPolicy.glyphSize(dp(21f),compactRoom-dp(2f),2f*min(cameraY,topHeight-cameraY)-dp(4f))
            val expandedSize=min(dp(46f),(w-dp(80f)).coerceAtLeast(0f)*.24f)
            val gx=NexoraPolicy.lerp(compactX,dp(44f),t)
            val gy=NexoraPolicy.lerp(cameraY,topHeight+dp(37f),t)
            val diameter=NexoraPolicy.lerp(compactSize,expandedSize,t)
            val visibility=NexoraPolicy.phase(w,dp(60f),dp(113f))
            val isEnergy=card.kind=="energy"
            val unlocked=card.kind=="unlock"
            val success=if(unlocked)NexoraPolicy.phase(age,.10f,.68f) else 0f
            val tone=if(isEnergy)card.accent else if(unlocked)blend(VIOLET,GREEN,success) else VIOLET
            if(diameter>0f && visibility>0f){
                if(visibility<.999f)canvas.saveLayerAlpha(0f,0f,w,h,(visibility*255).toInt()) else canvas.save()
                canvas.translate(gx,gy)
                canvas.scale(diameter/48f,diameter/48f)
                // An orbit resolves into a complete ring; no glow or particles outside the pill.
                val orbit=if(isEnergy)NexoraPolicy.phase(age,.04f,.80f) else if(unlocked)success else NexoraPolicy.phase(age,0f,.75f)
                color(tone,if(isEnergy)45 else 65);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.1f;paint.strokeCap=Paint.Cap.ROUND
                canvas.drawCircle(0f,0f,21f,paint)
                if(isEnergy){
                    val level=card.progress
                    val amount=if(level!=null)level.coerceIn(0f,1f)*orbit else orbit
                    // Two finite soft pulses, contained inside the existing glyph.
                    if(!reduced && age<1.15f && card.accent==GREEN){
                        for(n in 0..1){
                            val q=((age-.04f-n*.24f)/.74f).coerceIn(0f,1f)
                            if(q>0f && q<1f){
                                val rr=10f+11f*NexoraPolicy.smoothstep(q)
                                color(GREEN,(115*sin(PI.toFloat()*q)).toInt());paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f
                                canvas.drawCircle(0f,0f,rr,paint)
                            }
                        }
                    }
                    // The charge current travels in two overlapping bands. A bright leading
                    // segment settles into a quiet progress arc rather than flashing.
                    color(tone,230);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f
                    canvas.drawArc(-21f,-21f,21f,21f,-90f,360f*amount,false,paint)
                    if(!reduced && amount>0f && age<1.15f){
                        val sweep=min(360f*amount,55f)
                        color(0xFFBCFFE1.toInt(),(200*(1f-NexoraPolicy.phase(age,.7f,1.15f))).toInt())
                        paint.style=Paint.Style.STROKE;paint.strokeWidth=3.1f
                        canvas.drawArc(-21f,-21f,21f,21f,-90f+360f*amount-sweep,sweep,false,paint)
                    }
                    if(age<1.15f && age>.04f){
                        val angle=(-90f+360f*amount)*PI.toFloat()/180f
                        color(Color.WHITE,(180*(1f-NexoraPolicy.phase(age,.65f,1.15f))).toInt())
                        canvas.drawCircle(cos(angle)*21f,sin(angle)*21f,1.8f,paint)
                    }
                    val bolt=NexoraPolicy.phase(age,.06f,.48f)
                    effectPath.reset();effectPath.moveTo(3f,-14f);effectPath.lineTo(-9f,1f);effectPath.lineTo(-1f,1f);effectPath.lineTo(-4f,14f);effectPath.lineTo(10f,-3f);effectPath.lineTo(2f,-3f);effectPath.close()
                    color(tone);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.9f;paint.strokeJoin=Paint.Join.ROUND
                    trace(canvas,effectPath,bolt)
                    color(tone,(255*NexoraPolicy.phase(age,.25f,.58f)).toInt());canvas.drawPath(effectPath,paint)
                } else {
                    // Two orbit segments converge as the actual lock turns into a check.
                    val rotate=if(reduced)0f else if(unlocked)NexoraPolicy.phase(age,0f,.64f)*165f else NexoraPolicy.phase(age,0f,.85f)*90f
                    val meet=if(unlocked)NexoraPolicy.phase(age,.26f,.78f) else 0f
                    val arc=if(unlocked)94f+85.5f*meet else 104f
                    color(tone,235);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.2f;paint.strokeCap=Paint.Cap.ROUND
                    canvas.drawArc(-21f,-21f,21f,21f,-90f+rotate,arc,false,paint)
                    color(if(unlocked)blend(BLUE,GREEN,success)else BLUE,235);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.2f
                    canvas.drawArc(-21f,-21f,21f,21f,90f+rotate,arc,false,paint)
                    val lift=if(unlocked)NexoraPolicy.phase(age,.02f,.40f)else 0f
                    val morph=if(unlocked)NexoraPolicy.phase(age,.34f,.78f)else 0f
                    val lockAlpha=(255*(1f-morph)).toInt()
                    if(lockAlpha>0){
                        color(tone,lockAlpha);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.3f;paint.strokeJoin=Paint.Join.ROUND
                        canvas.save();canvas.rotate(-32f*lift,-6f,1f);canvas.translate(0f,-2.5f*lift)
                        securityPath.reset();securityPath.moveTo(-6f,0f);securityPath.lineTo(-6f,-5f);securityPath.cubicTo(-6f,-13f,6f,-13f,6f,-5f);securityPath.lineTo(6f,0f)
                        canvas.drawPath(securityPath,paint);canvas.restore()
                        val bw=9f*(1f-.22f*morph);val bh=6.5f*(1f-.30f*morph)
                        canvas.drawRoundRect(-bw,5.5f-bh,bw,5.5f+bh,3f,3f,paint)
                        canvas.drawLine(0f,4f,0f,7f,paint)
                    }
                    if(morph>0f){
                        val settle=if(reduced)1f else NexoraPolicy.phase(age,.70f,.96f)
                        val pulse=if(reduced)0f else sin(PI.toFloat()*settle)*.08f
                        canvas.save();canvas.scale(.94f+.06f*settle+pulse,.94f+.06f*settle+pulse)
                        color(GREEN);paint.style=Paint.Style.STROKE;paint.strokeWidth=3.2f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
                        securityPath.reset();securityPath.moveTo(-9f,0f);securityPath.lineTo(-2f,7f);securityPath.lineTo(10f,-7f)
                        trace(canvas,securityPath,morph);canvas.restore()
                    }
                }
                paint.style=Paint.Style.FILL;canvas.restore()
            }
            if(isEnergy && !reduced && card.accent==GREEN && age<.90f && w>dp(90f)){
                val phase=NexoraPolicy.phase(age,.06f,.87f)
                val start=cameraX+cameraRadius+dp(3f);val end=w-dp(10f)
                val lineY=NexoraPolicy.lerp(cameraY+dp(5f),topHeight+dp(62f),t)
                if(end>start && lineY<h-dp(3f)){
                    val head=NexoraPolicy.lerp(start,end,phase)
                    color(GREEN,(95*sin(PI.toFloat()*phase)).toInt());paint.strokeWidth=dp(1.4f);paint.strokeCap=Paint.Cap.ROUND
                    canvas.drawLine(max(start,head-dp(17f)),lineY,head,lineY,paint)
                }
            }
            val labelFade=(1f-NexoraPolicy.phase(e,.12f,.58f))*visibility
            val right=cameraX+cameraRadius+dp(5f)
            val rightWidth=(w-right-dp(7f)).coerceAtLeast(0f)
            val compactLabel=if(isEnergy)card.detail.ifBlank{"Power"} else if(unlocked)"Unlocked" else "Locked"
            if(labelFade>0f && rightWidth>0f){
                if(labelFade<.999f)canvas.saveLayerAlpha(0f,0f,w,h,(labelFade*255).toInt()) else canvas.save()
                val textSize=min(if(isEnergy)14f else 10.5f,(topHeight/density-6f).coerceAtLeast(8f))
                text(canvas,compactLabel,right,cameraY+dp(textSize*.35f),textSize,tone,rightWidth,true)
                canvas.restore()
            }
            val detailAlpha=NexoraPolicy.phase(e,.25f,.85f)
            if(detailAlpha<=0f)return
            if(detailAlpha<.999f)canvas.saveLayerAlpha(0f,0f,w,h,(detailAlpha*255).toInt()) else canvas.save()
            canvas.translate(0f,dp(5f)*(1f-detailAlpha))
            val textX=dp(78f)
            val y=topHeight
            val available=w-textX-dp(17f)
            text(canvas,card.title,textX,y+dp(29f),17f,Color.WHITE,available,true)
            text(canvas,if(isEnergy)card.detail.ifBlank{"Power connected"} else if(unlocked)"Device unlocked" else "Unlock your device",textX,y+dp(48f),12f,0xFF9DA1AA.toInt(),available)
            if(isEnergy && card.progress!=null){
                val level=card.progress ?: 0f
                val l=dp(22f);val r=w-l;val by=y+dp(71f);val bh=dp(7f)
                val fill=l+(r-l)*level.coerceIn(0f,1f)*NexoraPolicy.phase(age,.15f,.95f)
                if(r>l){
                    color(0xFF1B2924.toInt());canvas.drawRoundRect(l,by,r,by+bh,bh/2,bh/2,paint)
                    if(fill>l){
                        color(Color.WHITE);gradientMatrix.reset();gradientMatrix.setScale(r-l,bh);gradientMatrix.postTranslate(l,by);chargeGradient.setLocalMatrix(gradientMatrix);paint.shader=chargeGradient
                        canvas.drawRoundRect(l,by,fill,by+bh,bh/2,bh/2,paint);paint.shader=null
                        if(!reduced && age<1.25f && fill-l>dp(8f)){
                            canvas.save()
                            rect.set(l,by,fill,by+bh);path.reset();path.addRoundRect(rect,bh/2,bh/2,Path.Direction.CW);canvas.clipPath(path)
                            val sweepWidth=dp(34f)
                            val travel=NexoraPolicy.phase(age,.22f,1.15f)
                            val start=l-sweepWidth+(fill-l+sweepWidth)*travel
                            color(Color.WHITE,(255*(1f-NexoraPolicy.phase(age,1.05f,1.25f))).toInt())
                            gradientMatrix.reset();gradientMatrix.setScale(sweepWidth,bh);gradientMatrix.postTranslate(start,by)
                            chargeSweep.setLocalMatrix(gradientMatrix);paint.shader=chargeSweep
                            canvas.drawRect(start,by,start+sweepWidth,by+bh,paint);paint.shader=null
                            canvas.restore()
                        }
                    }
                }
            } else if(unlocked){
                val line=NexoraPolicy.phase(age,.48f,1.05f)
                color(GREEN,(line*200).toInt());paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.5f);paint.strokeCap=Paint.Cap.ROUND
                canvas.drawLine(textX,y+dp(68f),textX+min(dp(50f),available.coerceAtLeast(0f))*line,y+dp(68f),paint)
                paint.style=Paint.Style.FILL
            }
            canvas.restore()
        }
        private fun connectionGlyph(canvas:Canvas,rawAge:Float){
            val age=if(reducedMotion)2f else rawAge
            val kind=card.eventType.orEmpty()
            val t=NexoraPolicy.phase(age,.04f,.72f)
            val disconnect=kind.contains("DISCONNECTED") || kind.endsWith("DISABLED")
            color(card.accent);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.9f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
            when {
                card.kind=="timer" -> {
                    canvas.drawArc(-10f,-10f,10f,10f,-90f,360f*t,false,paint)
                    effectPath.reset();effectPath.moveTo(0f,-6f);effectPath.lineTo(0f,0f);effectPath.lineTo(5f,3f);trace(canvas,effectPath,NexoraPolicy.phase(age,.22f,.8f))
                }
                kind.contains("BLUETOOTH") || kind.contains("VPN") -> {
                    val f=if(disconnect)1f-t else t
                    val distance=5f+4f*(1f-f)
                    canvas.save();canvas.rotate(if(kind.contains("VPN"))0f else -35f)
                    color(card.accent,220);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.9f
                    canvas.drawRoundRect(-distance-2.8f,-5f,-distance+4f,5f,3.5f,3.5f,paint)
                    canvas.drawRoundRect(distance-4f,-5f,distance+2.8f,5f,3.5f,3.5f,paint)
                    color(card.accent,(255*f).toInt());paint.strokeWidth=2.1f
                    canvas.drawLine(-3f,0f,3f,0f,paint);canvas.restore()
                }
                kind.contains("HEADPHONES") -> {
                    val f=NexoraPolicy.phase(age,.08f,.68f)
                    val shift=2f*(1f-f)
                    color(card.accent);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.8f
                    canvas.drawArc(-8f,-9f,8f,7f,180f,180f*f,false,paint)
                    color(card.accent,(255*f).toInt())
                    canvas.drawRoundRect(-10f-shift,-2f,-5f-shift,7f,2.5f,2.5f,paint)
                    canvas.drawRoundRect(5f+shift,-2f,10f+shift,7f,2.5f,2.5f,paint)
                }
                else -> {
                    for(n in 0..2){
                        val f=NexoraPolicy.phase(age,.04f+n*.09f,.34f+n*.12f)
                        val r=4f+n*3.1f
                        color(card.accent,(255*f).toInt());paint.style=Paint.Style.STROKE;paint.strokeWidth=1.8f
                        canvas.drawArc(-r,5f-r,r,5f+r,270f-52f*f,104f*f,false,paint)
                    }
                    color(card.accent);canvas.drawCircle(0f,5f,1.5f,paint)
                }
            }
            if(card.kind=="connect" && !reducedMotion && age<.95f){
                val q=(age/.95f).coerceIn(0f,1f)
                val radius=6f+5f*NexoraPolicy.smoothstep(q)
                color(card.accent,(105*sin(PI.toFloat()*q)).toInt());paint.style=Paint.Style.STROKE;paint.strokeWidth=1.1f
                canvas.drawArc(-radius,-radius,radius,radius,-95f+130f*q,105f,false,paint)
            }
            paint.style=Paint.Style.FILL
        }
        private fun activityGlyph(canvas:Canvas,x:Float,y:Float,size:Float,age:Float){
            if(size<=0f)return
            val radius=size/2f
            val motion=if(reducedMotion)1f else NexoraPolicy.phase(age,.03f,.40f)
            val caller=card.kind=="call"
            val photo=if(caller)cachedPhoto else null
            val icon=card.appIcon
            canvas.save();canvas.translate(x,y);canvas.scale(.87f+.13f*motion,.87f+.13f*motion)
            if(caller && ringing()){
                val pulse=if(reducedMotion)0f else NexoraPolicy.breath(age,1.8f)
                color(GREEN,(65+100*pulse).toInt());paint.style=Paint.Style.STROKE;paint.strokeWidth=max(dp(.7f),size*.055f)
                canvas.drawCircle(0f,0f,radius-paint.strokeWidth/2,paint)
            }
            val r=if(caller)radius*.77f else radius*.85f
            val halo=1f-NexoraPolicy.phase(age,.18f,.58f)
            if(!reducedMotion && !caller && halo>0f){
                color(card.accent,(120*halo).toInt());paint.style=Paint.Style.STROKE;paint.strokeWidth=max(dp(.6f),size*.045f)
                val rr=radius*(.75f+.20f*NexoraPolicy.phase(age,.05f,.50f))
                canvas.drawArc(-rr,-rr,rr,rr,-90f+90f*(1f-halo),220f*(1f-halo),false,paint)
            }
            if(photo!=null){
                rect.set(-r,-r,r,r);path.reset();path.addRoundRect(rect,r,r,Path.Direction.CW)
                canvas.save();canvas.clipPath(path);color(Color.WHITE);paint.isFilterBitmap=true
                val side=min(photo.width,photo.height)
                albumSource.set((photo.width-side)/2,(photo.height-side)/2,(photo.width+side)/2,(photo.height+side)/2)
                canvas.drawBitmap(photo,albumSource,rect,paint);canvas.restore()
            } else if(icon!=null){
                icon.setBounds((-r).toInt(),(-r).toInt(),r.toInt(),r.toInt());icon.draw(canvas)
            } else if(card.kind=="connect" || card.kind=="timer"){
                canvas.save();canvas.scale(r/12f,r/12f);connectionGlyph(canvas,age);canvas.restore()
            } else if(caller){
                canvas.save();canvas.scale(r/10f,r/10f)
                color(GREEN);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.6f;paint.strokeCap=Paint.Cap.ROUND
                path.reset();path.moveTo(-6f,-7f);path.cubicTo(-7f,1f,1f,8f,7f,7f);canvas.drawPath(path,paint)
                color(GREEN);canvas.drawRoundRect(-9f,-9f,-3f,-3f,2f,2f,paint);canvas.drawRoundRect(3f,3f,9f,9f,2f,2f,paint);canvas.restore()
            } else {
                color(card.accent,50);canvas.drawCircle(0f,0f,r,paint)
                color(card.accent);canvas.drawCircle(0f,0f,r*.52f,paint)
            }
            canvas.restore()
        }
        private fun drawInbox(canvas:Canvas,w:Float,h:Float,e:Float){
            val a=NexoraPolicy.phase(e,.20f,.82f)
            if(a<=0f)return
            if(a<.999f)canvas.saveLayerAlpha(0f,topHeight,w,h,(a*255).toInt())else canvas.save()
            val y=topHeight;val l=dp(16f);val r=w-l
            text(canvas,"Notifications",l,y+dp(18f),15f,Color.WHITE,w-dp(95f),true)
            val pages=NexoraPolicy.inboxPages(inboxRows.size)
            text(canvas,"${inboxPage+1}/$pages",r-dp(38f),y+dp(18f),11f,VIOLET,dp(38f),true)
            val pageMotion=if(reducedMotion || pageBegan==0L)1f else NexoraPolicy.phase((SystemClock.uptimeMillis()-pageBegan)/1000f,0f,.18f)
            if(pageMotion<1f)postInvalidateOnAnimation()
            for(row in 0..2){
                val item=inboxRows.getOrNull(inboxPage*3+row) ?: break
                val rowMotion=NexoraPolicy.phase(e,.26f+row*.05f,.85f+row*.04f)
                if(rowMotion<=0f)continue
                if(pageMotion<.999f)canvas.saveLayerAlpha(l,y+dp(23f),r,h,(255*pageMotion).toInt())else canvas.save()
                canvas.translate(dp(8f)*pageDirection*(1f-pageMotion),dp(5f)*(1f-rowMotion))
                val yy=y+dp(25f+row*37f)
                val icon=appIcon(item.packageName)
                val size=dp(23f)
                if(icon!=null){icon.setBounds(l.toInt(),(yy+dp(5f)).toInt(),(l+size).toInt(),(yy+dp(5f)+size).toInt());icon.draw(canvas)}
                val tx=l+dp(33f)
                val title=if(enabled("message_text"))item.title.ifBlank{item.appName}else item.appName
                val detail=if(enabled("message_text"))item.body.ifBlank{item.appName}else "Notification"
                text(canvas,title,tx,yy+dp(13f),13f,Color.WHITE,r-tx,true)
                text(canvas,detail,tx,yy+dp(28f),11f,0xFF9DA1AA.toInt(),r-tx)
                if(row<2 && inboxRows.size>inboxPage*3+row+1){color(0xFF25262A.toInt());canvas.drawRect(tx,yy+dp(35f),r,yy+dp(35.5f),paint)}
                canvas.restore()
            }
            if(pages>1)text(canvas,"Swipe for more",l,y+dp(146f),10f,0xFF92979F.toInt(),w-l*2)
            canvas.restore()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if(!displayVisible)return
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
            // Reserve the physical camera area even while an icon travels between sizes.
            cameraMask.reset();cameraMask.addCircle(cameraX,cameraY,cameraRadius,Path.Direction.CW)
            canvas.clipOutPath(cameraMask)
            val reveal=if(reducedMotion)1f else NexoraPolicy.phase(age,0f,.11f)
            if(reveal<.999f)canvas.saveLayerAlpha(0f,0f,w,h,((1f-(1f-reveal)*(1f-reveal))*255).toInt()) else canvas.save()
            try {
            // Reference has a pure black silhouette without a visible outline.
            if(card.kind=="inbox") {drawInbox(canvas,w,h,((h-topHeight)/dp(152f)).coerceIn(0f,1f));if(!reducedMotion && age<.14f)postInvalidateOnAnimation();return}
            if(card.kind=="music") {
                if(w>dp(105f)) musicLayout(canvas,w,h,expansion,age)
                if(!reducedMotion && (age<1.3f || preview || NowPlayingBus.state.value?.isPlaying==true))postInvalidateOnAnimation()
                return
            }
            if(card.kind=="energy" || card.kind=="unlock" || card.kind=="lock") {
                signatureLayout(canvas,w,h,expansion,age)
                if(age<1.3f && !reducedMotion)postInvalidateOnAnimation()
                return
            }
            if (w < dp(82f)) { if(age<1.3f && !reducedMotion) postInvalidateOnAnimation(); return }
            val contentAlpha = ((w-dp(82f))/dp(38f)).coerceIn(0f,1f)
            if (contentAlpha < .999f) canvas.saveLayerAlpha(0f,0f,w,h,(contentAlpha*255).toInt()) else canvas.save()
            val left = dp(14f)
            val right = cameraX + cameraRadius + dp(9f)
            val cy = cameraY
            val maxRight = (w-right-dp(12f)).coerceAtLeast(0f)
            val leftRoom=(cameraX-cameraRadius-dp(6f)).coerceAtLeast(0f)
            val iconX=min(left+dp(8f),dp(3f)+leftRoom/2)
            val shared=card.kind=="message" || card.kind=="call" || card.kind=="progress" || card.kind=="connect" || card.kind=="timer"
            val move=NexoraPolicy.smoothstep(expansion)
            val small=NexoraPolicy.glyphSize(dp(22f),leftRoom-dp(2f),2f*min(cy,topHeight-cy)-dp(3f))
            val sharedX=NexoraPolicy.lerp(iconX,left+dp(17f),move)
            val sharedY=NexoraPolicy.lerp(cy,topHeight+dp(27f),move)
            val sharedSize=NexoraPolicy.lerp(small,dp(34f),move)
            color(accent); paint.strokeWidth = dp(2f); paint.strokeCap = Paint.Cap.ROUND
            if(shared)activityGlyph(canvas,sharedX,sharedY,sharedSize,age)
            else when(card.kind) {
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
                "progress" -> card.progress?.let{"${(it*100).roundToInt()}%"} ?: "Working"
                "energy" -> card.detail.ifBlank { "Power" }
                "music" -> if (NowPlayingBus.state.value?.isPlaying == true) "Playing" else "Paused"
                "unlock" -> "Unlocked"
                "lock" -> "Locked"
                "connect" -> card.title
                "timer" -> RunningTimerBus.state.value?.let { timer ->
                    val now=SystemClock.elapsedRealtime()
                    val ms=timer.pausedRemainingMs ?: timer.endElapsedRealtimeMs?.let{if(timer.countsUp)now-it else it-now} ?: 0L
                    val sec=ms.coerceAtLeast(0L)/1000
                    String.format(java.util.Locale.ROOT,"%02d:%02d",sec/60,sec%60)
                } ?: "Timer"
                else -> card.title
            }
            val compactProgress=card.progress
            if(card.kind=="progress" && compactProgress!=null){
                val leftBar=right; val rightBar=w-dp(12f);val yy=cy+dp(7f)
                if(rightBar>leftBar){color(0xFF292929.toInt());canvas.drawRoundRect(leftBar,yy,rightBar,yy+dp(2f),dp(1f),dp(1f),paint);gradient(leftBar,yy,rightBar-leftBar,dp(2f));canvas.drawRoundRect(leftBar,yy,leftBar+(rightBar-leftBar)*compactProgress.coerceIn(0f,1f),yy+dp(2f),dp(1f),dp(1f),paint)}
            }
            val compactTextAlpha=if(shared)1f-NexoraPolicy.phase(expansion,.12f,.60f) else 1f
            if(compactTextAlpha>0f){
                if(compactTextAlpha<.999f)canvas.saveLayerAlpha(0f,0f,w,h,(compactTextAlpha*255).toInt()) else canvas.save()
                text(canvas,short,right,cy+dp(4f),min(11f,(topHeight/density-5f).coerceAtLeast(8f)),if(card.kind=="message")Color.WHITE else accent,maxRight,true)
                canvas.restore()
            }
            if(expansion > .15f) {
                val reveal=NexoraPolicy.phase(expansion,.18f,.90f)
                if (expansion < .90f) canvas.saveLayerAlpha(0f,topHeight,w,h,(reveal*255).toInt()) else canvas.save()
                canvas.translate(0f,dp(4f)*(1f-reveal))
                val y=topHeight
                val titleX=if(shared)left+dp(44f) else left
                text(canvas,card.title,titleX,y+dp(22f),17f,Color.WHITE,w-titleX-left,true)
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
                text(canvas,detail,titleX,y+dp(42f),12f,Color.LTGRAY,w-titleX-left)
                val progress=card.progress
                if(progress!=null && card.kind!="energy"){color(Color.DKGRAY);canvas.drawRoundRect(left,y+dp(49f),w-left,y+dp(52f),dp(2f),dp(2f),paint);color(accent);canvas.drawRoundRect(left,y+dp(49f),left+(w-2*left)*progress.coerceIn(0f,1f),y+dp(52f),dp(2f),dp(2f),paint)}
                val actions=if(card.kind=="call" && !keyguard.isKeyguardLocked) callButtons() else if(card.kind=="message" && enabled("message_text"))card.actions else emptyList()
                if(actions.isNotEmpty()){
                    val gap=dp(8f)
                    val bw=(w-2*left-gap*(actions.size-1))/actions.size
                    actions.forEachIndexed{i,action->
                        val bx=left+i*(bw+gap)
                        val role=if(card.kind=="call")actionRole(action.title) else 0
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
            if((age<1.3f || (card.kind=="call" && ringing())) && !reducedMotion) postInvalidateOnAnimation()
            } finally { canvas.restore() }
            } finally { canvas.restore() }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(preview)return true
            if(keyguard.isKeyguardLocked || !displayAwake())return false
            val localX=event.x-drawX;val localY=event.y-drawY
            if(event.actionMasked==MotionEvent.ACTION_DOWN){
                val pad=dp(prefs.getInt("notch_padding",6).coerceIn(0,12).toFloat())
                touchActive=false
                notchRoute=notchAllowed() && geometry?.isRunning!=true && localY in 0f..topHeight &&
                    abs(localX-cameraX)<=cameraRadius+pad
            }
            if(notchRoute){
                if(geometry?.isRunning==true){cancelNotchGesture();notchRoute=false;return true}
                val handled=notchTouch(this,event)
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)notchRoute=false
                return handled
            }
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    if(localX !in 0f..visualWidth || localY !in 0f..visualHeight)return false
                    // Ignore a touch starting on moving geometry; never classify window motion
                    // as a user swipe, and never trigger a control that moved under a finger.
                    touchActive=geometry?.isRunning!=true
                    downX=event.rawX;downY=event.rawY;downTime=SystemClock.uptimeMillis()
                    downCard=card.identity();downExpanded=open;maxTravel=0f
                    if(touchActive && enabled("haptics"))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    return true
                }
                MotionEvent.ACTION_MOVE->{
                    maxTravel=max(maxTravel,hypot(event.rawX-downX,event.rawY-downY))
                    if(downCard!=card.identity() || downExpanded!=open || geometry?.isRunning==true){touchActive=false}
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN->{touchActive=false;return true}
                MotionEvent.ACTION_UP->{
                    val valid=touchActive && downCard==card.identity() && downExpanded==open && geometry?.isRunning!=true
                    touchActive=false
                    if(!valid)return true
                    val dx=event.rawX-downX;val dy=event.rawY-downY
                    val slop=max(dp(32f),ViewConfiguration.get(service).scaledTouchSlop*2f)
                    when(NexoraPolicy.gesture(dx,dy,slop,SystemClock.uptimeMillis()-downTime)){
                        1->swipe(1)
                        -1->swipe(-1)
                        2->if(!ringing() && enabled("swipe_close")){inboxOpen=false;flash=null;pending.clear();expanded=false;browsing=false;selection=0;selectedId=null;autoCollapseAt=0L;autoFocus=null;render()}
                        5->if(enabled("swipe_shade") && !ringing() && !open){service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)}
                        4->if(!ringing() && maxTravel<slop){
                            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                        }
                        0->if(maxTravel<slop && localX in 0f..visualWidth && localY in 0f..visualHeight){performClick();tap(localX,localY)}
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL->{touchActive=false;return true}
                else->return true
            }
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
