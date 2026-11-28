package com.ekoehler.expressivecutout.nexora

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import com.ekoehler.expressivecutout.permissions.Permissions
import com.ekoehler.expressivecutout.data.StatusBarPreferences
import com.ekoehler.expressivecutout.data.StatusBarHideMode
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import com.ekoehler.expressivecutout.core.*
import com.ekoehler.expressivecutout.service.CutoutAccessibilityService
import com.ekoehler.expressivecutout.service.CutoutNotificationListenerService
import com.ekoehler.expressivecutout.system.ShizukuState
import com.ekoehler.expressivecutout.system.ShizukuStatus

class NexoraActivity : Activity() {
    private val blue = Color.rgb(99,202,255)
    private val violet = Color.rgb(181,151,255)
    private val white = Color.WHITE
    private val muted = Color.rgb(166,173,190)
    private val prefs by lazy { getSharedPreferences("nexora", Context.MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private var calibrating = false
    private var calibrationMode = 1
    private var page = "home"
    private var granting=false
    private var grantAfterConsent=false
    private var grantSummary:String?=null
    private var statusJob:kotlinx.coroutines.Job?=null
    private var pageEpoch=0
    private val mainScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
    private fun dp(v:Int) = (v*resources.displayMetrics.density).toInt()
    private fun surface(c:Int,r:Int=22)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK;window.decorView.systemUiVisibility=0
        page=savedInstanceState?.getString("page") ?: "home"
        calibrating=savedInstanceState?.getBoolean("calibrating") ?: false
        calibrationMode=savedInstanceState?.getInt("calibrationMode",1) ?: 1
        if(calibrating)drawCalibration() else drawPage()
        statusJob=mainScope.launch{
            ShizukuState.status.collect{state->
                if(state==ShizukuStatus.READY && grantAfterConsent){grantAfterConsent=false;grantAll()}
                else if(page=="access" && !granting && !isFinishing && !isDestroyed)drawPage()
            }
        }
    }
    override fun onSaveInstanceState(outState:Bundle){
        outState.putString("page",page);outState.putBoolean("calibrating",calibrating);outState.putInt("calibrationMode",calibrationMode)
        super.onSaveInstanceState(outState)
    }
    override fun onResume(){
        super.onResume()
        ShizukuState.refresh()
        if(calibrating) drawCalibration() else drawPage()
    }
    override fun onPause(){
        NexoraCalibration.mode.value=0
        super.onPause()
    }
    override fun onDestroy(){
        NexoraCalibration.mode.value=0
        grantAfterConsent=false
        statusJob?.cancel()
        mainScope.cancel()
        super.onDestroy()
    }
    @Deprecated("Use system back navigation")
    override fun onBackPressed(){
        if(calibrating) exitCalibration() else if(page!="home"){page="home";drawPage()} else super.onBackPressed()
    }
    private fun label(value:String,size:Float=14f,color:Int=white,bold:Boolean=false)=TextView(this).apply{
        text=value;textSize=size;setTextColor(color);if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private fun add(v:View, gap:Int=10){content.addView(v,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(gap)})}
    private fun heading(s:String){add(label(s,12f,violet,true),8)}
    private fun button(title:String,sub:String,action:()->Unit){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(16));background=surface(Color.rgb(19,21,30));isClickable=true;isFocusable=true;setOnClickListener{action()}}
        box.addView(label(title,17f,white,true));box.addView(label(sub,13f,muted));add(box)
    }
    private fun toggle(title:String,sub:String,key:String,default:Boolean=true){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(14));background=surface(Color.rgb(19,21,30))}
        val text=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(label(title,16f,white,true));addView(label(sub,12f,muted))}
        row.addView(text,LinearLayout.LayoutParams(0,-2,1f));row.addView(Switch(this).apply{isChecked=prefs.getBoolean(key,default);contentDescription=title;thumbTintList=android.content.res.ColorStateList.valueOf(white);trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(0xFF437DB7.toInt(),0xFF444752.toInt()));setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean(key,checked).apply()}});add(row)
    }
    private fun open(action:String){runCatching{startActivity(Intent(action))}.onFailure{Toast.makeText(this,"Settings unavailable",Toast.LENGTH_SHORT).show()}}
    private fun preview(type:SystemEventType,level:Int?=null){
        if(!CutoutAccessibilityService.bound.value){Toast.makeText(this,"Enable NEXORA accessibility first",Toast.LENGTH_LONG).show();return}
        IslandEventBus.emit(CutoutSignal.System(type,level))
    }
    private fun exitCalibration(){
        NexoraCalibration.mode.value=0
        calibrating=false
        page="appearance"
        drawPage()
    }
    private fun adjustment(title:String,key:String,low:Int,high:Int,default:Int,unit:String){
        val section=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(12),dp(16),dp(10))
            background=surface(Color.rgb(19,21,30))
        }
        val initial=prefs.getInt(key,default).coerceIn(low,high)
        val caption=label("$title  •  $initial$unit",15f,white,true)
        section.addView(caption)
        val slider=SeekBar(this).apply{
            this.max=high-low
            progress=initial-low
            contentDescription=title
            progressTintList=android.content.res.ColorStateList.valueOf(blue)
            thumbTintList=android.content.res.ColorStateList.valueOf(violet)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean){
                    val value=progress+low
                    caption.text="$title  •  $value$unit"
                    if(fromUser)prefs.edit().putInt(key,value).apply()
                }
                override fun onStartTrackingTouch(bar:SeekBar?)=Unit
                override fun onStopTrackingTouch(bar:SeekBar?)=Unit
            })
        }
        section.addView(slider,LinearLayout.LayoutParams(-1,dp(44)))
        add(section)
    }
    private fun drawCalibration(){
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(39,40,46));isFillViewport=true;clipToPadding=false}
        content=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(178),dp(20),dp(32))
        }
        scroll.addView(content);setContentView(scroll)
        NexoraCalibration.mode.value=calibrationMode
        add(label("Size & position",27f,white,true),8)
        add(label("LIVE PREVIEW",12f,violet,true),8)
        add(label("Adjust the actual island above. Changes are saved automatically.",14f,muted),14)
        if(!CutoutAccessibilityService.bound.value){
            button("Enable accessibility","Required to see the real island preview"){open(Settings.ACTION_ACCESSIBILITY_SETTINGS)}
        }
        val modes=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("Compact","Expanded","Charging").forEachIndexed{i,name->
            val b=Button(this).apply{
                text=name;isAllCaps=false;textSize=12f
                setTextColor(if(calibrationMode==i+1)Color.BLACK else white)
                background=surface(if(calibrationMode==i+1)blue else Color.rgb(29,32,42),16)
                setOnClickListener{calibrationMode=i+1;drawCalibration()}
            }
            modes.addView(b,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(4)})
        }
        add(modes,14)
        adjustment("Width","island_width",85,120,100,"%")
        val cameraBounds=CutoutMetrics.displayCutoutBoundsPx(this)
        val cameraMin=kotlin.math.ceil((cameraBounds?.height() ?: dp(12).toFloat()) / resources.displayMetrics.density + 3f).toInt().coerceAtLeast(20)
        if(cameraMin<=44) adjustment("Height","island_height_v2",cameraMin,44,28," dp")
        else add(label("Minimum safe height: ${cameraMin} dp (device camera clearance)",14f,muted))
        add(label("Minimum safe height on this display: ${cameraMin} dp",12f,muted),8)
        adjustment("Horizontal offset","island_x",-24,24,0," dp")
        adjustment("Vertical offset","island_y_v2",-10,10,0," dp")
        add(label("Height now adjusts the capsule itself. Camera clearance can limit the smallest height and offsets.",12f,muted))
        button("Reset to default","Restore the approved proportions"){
            prefs.edit().remove("island_width").remove("island_height").remove("island_height_v2").remove("island_x").remove("island_y").remove("island_y_v2").apply()
            drawCalibration()
        }
        button("Done","Close preview and return to invisible standby when idle"){exitCalibration()}
    }
    private fun go(target:String){page=target;drawPage()}
    private fun pageFrame(title:String,subtitle:String){
        pageEpoch++
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.BLACK);isFillViewport=true;clipToPadding=false}
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(24),dp(20),dp(32))}
        scroll.addView(content);setContentView(scroll)
        if(page!="home"){
            val back=label("‹  NEXORA",15f,blue,true).apply{setPadding(0,dp(8),0,dp(12));isClickable=true;setOnClickListener{go("home")}}
            add(back,6)
        }
        add(label(title,30f,white,true),5)
        add(label(subtitle,14f,muted),22)
    }
    private fun requestIsland(kind:String){
        if(!CutoutAccessibilityService.bound.value){Toast.makeText(this,"Enable accessibility first",Toast.LENGTH_LONG).show();return}
        NexoraCalibration.requests.tryEmit(kind)
    }
    private fun choice(title:String,key:String,choices:List<Pair<String,Int>>,default:Int){
        add(label(title,15f,white,true),7)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        choices.forEach{(name,value)->
            val selected=prefs.getInt(key,default)==value
            val b=Button(this).apply{
                text=name;isAllCaps=false;textSize=12f
                setTextColor(if(selected)Color.BLACK else white)
                background=surface(if(selected)blue else Color.rgb(29,32,42),16)
                setOnClickListener{prefs.edit().putInt(key,value).apply();drawPage()}
            }
            row.addView(b,LinearLayout.LayoutParams(0,dp(46),1f).apply{marginEnd=dp(5)})
        }
        add(row,15)
    }
    private fun confirmGrantAll(){
        if(ShizukuState.status.value==ShizukuStatus.NOT_INSTALLED || ShizukuState.status.value==ShizukuStatus.NOT_RUNNING){
            Permissions.openShizuku(this);return
        }
        android.app.AlertDialog.Builder(this).setTitle("Grant NEXORA permissions?")
            .setMessage("Allow Shizuku to enable NEXORA accessibility, notification access, battery exemption, phone state and optional test notifications. Other apps and accessibility services will be kept. You can revoke grants in Android settings.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Grant all"){_,_->
                if(ShizukuState.status.value==ShizukuStatus.READY)grantAll()
                else {grantAfterConsent=true;ShizukuState.requestPermission()}
            }.show()
    }
    private fun grantAll(){
        if(granting)return
        granting=true;grantSummary=null
        if(page=="access")drawPage()
        mainScope.launch{
            val commandsOk=withContext(Dispatchers.IO){runCatching{Permissions.grantMissingWithShizuku(applicationContext)}.getOrDefault(false)}
            delay(800)
            val missing=buildList{
                if(!Permissions.isAccessibilityGranted(this@NexoraActivity))add("Accessibility")
                if(!Permissions.isNotificationAccessGranted(this@NexoraActivity))add("Notification access")
                if(!Permissions.isBatteryOptimizationIgnored(this@NexoraActivity))add("Battery exemption")
                if(!Permissions.isPhoneStateGranted(this@NexoraActivity))add("Phone state")
                if(!Permissions.isPostNotificationGranted(this@NexoraActivity))add("Test notifications")
            }
            grantSummary=if(missing.isEmpty())"All five permissions verified." else "Still missing: "+missing.joinToString(", ")+". Use the individual settings below."
            if(!commandsOk && missing.isEmpty())grantSummary="All requested permissions verified."
            granting=false
            if(page=="access" && !isFinishing && !isDestroyed)drawPage()
        }
    }
    private fun statusMode(title:String,current:StatusBarHideMode,write:suspend(StatusBarHideMode)->Unit){
        add(label(title,15f,white,true),8)
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        StatusBarHideMode.entries.forEach{mode->
            val b=Button(this).apply{
                text=mode.name.lowercase().replaceFirstChar{it.uppercase()};isAllCaps=false
                setTextColor(if(mode==current)Color.BLACK else white)
                background=surface(if(mode==current)blue else Color.rgb(29,32,42),16)
                setOnClickListener{mainScope.launch{write(mode);drawPage()}}
            }
            row.addView(b,LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(5)})
        }
        add(row,14)
    }
    private fun drawMenu(){page="home";drawPage()}
    private fun drawPage(){
        when(page){
            "home" -> {
                pageFrame("NEXORA","BEYOND THE ISLAND")
                val status=LinearLayout(this).apply{
                    orientation=LinearLayout.VERTICAL
                    background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF152438.toInt(),0xFF201A35.toInt())).apply{cornerRadius=dp(25).toFloat()}
                    setPadding(dp(20),dp(20),dp(20),dp(20))
                    addView(label(if(CutoutAccessibilityService.bound.value && CutoutNotificationListenerService.bound.value)"Island ready" else "Finish setup",23f,white,true))
                    addView(label("Invisible at rest. Alive when it matters.",13f,muted))
                    addView(label(if(CutoutAccessibilityService.bound.value)"●  Overlay connected" else "○  Overlay not connected",13f,if(CutoutAccessibilityService.bound.value)0xFF59EBA4.toInt() else white))
                    addView(label(if(CutoutNotificationListenerService.bound.value)"●  Notifications connected" else "○  Notifications not connected",13f,if(CutoutNotificationListenerService.bound.value)0xFF59EBA4.toInt() else white))
                    setOnClickListener{go("access")}
                }
                add(status,20)
                toggle("NEXORA Island","Master switch","enabled")
                toggle("Focus mode","Pause non-call activities without dismissing Android notifications","focus_mode",false)
                heading("PERSONALIZE")
                button("Appearance","Size, position and live preview"){go("appearance")}
                button("Motion","Motion Lab and accessibility"){go("motion")}
                button("Live activities","Music, timers, charging and connections"){go("activities")}
                button("Notifications","Privacy, queue and inbox"){go("notifications")}
                button("Notch Touch","Camera gestures and sensitivity"){go("notch")}
                button("Gestures","Island touch preferences"){go("gestures")}
                heading("SYSTEM")
                button("Setup & Shizuku","Permissions in one place"){go("access")}
                button("Status bar","Optional Shizuku status bar controls"){go("statusbar")}
                button("About","Version, credits and source code"){go("about")}
            }
            "appearance"->{
                pageFrame("Appearance","The original NEXORA silhouette. Pure black, no border.")
                button("Size & position","Adjust the real overlay with three live preview modes"){calibrating=true;calibrationMode=1;drawCalibration()}
                heading("PROPORTIONS")
                button("Slim preset","136 dp music base • 24 dp height • centered"){
                    prefs.edit().putInt("island_width",95).putInt("island_height_v2",24).putInt("island_x",0).putInt("island_y_v2",0).apply()
                    calibrating=true;calibrationMode=1;drawCalibration()
                }
                button("Original preset","Restore the approved default layout"){
                    prefs.edit().putInt("island_width",100).putInt("island_height_v2",28).putInt("island_x",0).putInt("island_y_v2",0).apply()
                    calibrating=true;calibrationMode=1;drawCalibration()
                }
            }
            "motion"->{
                pageFrame("Motion","Beyond the Island • Momentum motion, camera-safe shared elements and signature effects.")
                add(label("Native motion • 3 seconds expanded, 3 seconds compact. Incoming calls stay open.",14f,muted),14)
                toggle("Haptic touch","Subtle system feedback on island touches","haptics")
                toggle("Reduced motion","Short transitions and static waveform","reduce_motion",false)
                heading("MOTION LAB")
                button("Notification Morph","Try a sample notification without sending one"){requestIsland("demo_message")}
                button("Energy Flow","Preview normal charging"){requestIsland("demo_charge")}
                button("Fast charging","Preview the faster emerald current"){requestIsland("demo_fast")}
                button("Slow charging","Preview the softer charging pulse"){requestIsland("demo_slow")}
                button("Connect Pulse","Preview a Wi-Fi connection"){preview(SystemEventType.WIFI_CONNECTED)}
                button("Bluetooth Flow","Preview an accessory connection"){preview(SystemEventType.BLUETOOTH_CONNECTED)}
                button("Wake Signature","Preview the locked-state capsule"){requestIsland("demo_lock")}
                button("Unlock Signature","Preview the orbit, opening lock and traced green check"){requestIsland("demo_unlock")}
                button("Sound Flow","Live music layout with sample artwork"){calibrating=true;calibrationMode=2;drawCalibration()}
            }
            "activities"->{
                pageFrame("Live activities","Choose what can appear on the Island.")
                toggle("Sound Flow","Album art, transport and playback progress","music")
                toggle("Hide in music app","Avoid duplicate controls while the player is open","hide_in_player")
                toggle("Timers","Supported clock app timers and stopwatches","timers")
                toggle("Signature events","Master switch for system events","events")
                heading("SYSTEM EVENTS")
                toggle("Charging & battery","Power connected, disconnected, full and low battery","event_energy")
                toggle("Live charging","Keep the real battery level compact while plugged in","charge_live",false)
                toggle("Connectivity","Wi-Fi, Bluetooth, VPN, headphones and hotspot","event_connect")
                toggle("Unlock Signature","Confirmed device unlock, not biometric scanning","event_unlock")
                toggle("Wake island","Show the compact lock while the lock screen is awake, never on AOD","lock_wake")
                toggle("Other system events","USB, debugging and ringer mode","event_system")
                heading("CALLS")
                toggle("Hide active call in dialer","Keep the full phone app unobstructed; incoming calls still get priority","hide_in_dialer")
                add(label("Incoming calls always get priority. Supported answer and decline actions stay available until the dialer confirms the call state.",14f,muted))
            }
            "notifications"->{
                pageFrame("Notifications","Your messages stay in the Android notification shade.")
                toggle("Notification morph","Show new notifications in the island","notifications")
                toggle("Silent notifications","Also include notifications marked silent by Android","silent_notifications")
                toggle("Message previews","Show message content while the phone is unlocked","message_text")
                toggle("Smart queue","Optional • let new events wait rather than interrupt each other","queue_events",false)
                toggle("Live progress","Downloads and uploads stay visible while Android reports progress","live_progress")
                toggle("Notification inbox","View active notifications in a paged island list","inbox")
                button("Notch Touch","Set up camera gestures and shortcuts"){go("notch")}
                button("Open inbox","Browse active notifications on the real island"){requestIsland("inbox")}
                button("Notification list","View and open active notifications in NEXORA"){go("inbox")}
                button("Apps & exclusions","Choose which apps may appear"){go("apps")}
                add(label("Lock screen message contents stay private. Android heads-up alerts remain enabled unless you explicitly change the Status bar settings.",13f,muted))
            }
            "apps"->{
                pageFrame("Apps & exclusions","Turn off notifications and music for individual apps.")
                val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                val search=EditText(this).apply{
                    hint="Search apps";setHintTextColor(muted);setTextColor(white);setSingleLine(true)
                    background=surface(Color.rgb(24,26,34),16);setPadding(dp(14),dp(10),dp(14),dp(10))
                }
                add(search);add(container)
                val pm=packageManager
                val store=com.ekoehler.expressivecutout.data.AppPreferences(this)
                val epoch=pageEpoch
                mainScope.launch {
                    val apps=withContext(Dispatchers.IO){
                        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                            .map{it.activityInfo.packageName to it.loadLabel(pm).toString()}.distinctBy{it.first}.filter{it.first!=packageName}.sortedBy{it.second.lowercase()}
                    }
                    val disabled=store.disabledPackages.first().toMutableSet()
                    fun fill(q:String){
                        container.removeAllViews()
                        apps.filter{it.second.contains(q,true)||it.first.contains(q,true)}.forEach{(pkg,name)->
                            val sw=Switch(this@NexoraActivity).apply{
                                text=name;setTextColor(white);isChecked=pkg !in disabled;setPadding(dp(8),dp(9),dp(8),dp(9))
                                setOnCheckedChangeListener{_,checked->
                                    if(checked)disabled.remove(pkg) else disabled.add(pkg)
                                    mainScope.launch{store.setEnabled(pkg,checked)}
                                }
                            }
                            container.addView(sw,LinearLayout.LayoutParams(-1,-2))
                        }
                    }
                    if(page=="apps" && pageEpoch==epoch){
                        fill(search.text.toString())
                        search.addTextChangedListener(object:android.text.TextWatcher{
                            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){fill(s?.toString().orEmpty())}
                            override fun afterTextChanged(s:android.text.Editable?)=Unit
                        })
                    }
                }
            }
            "inbox"->{
                pageFrame("Inbox","Active notifications on your device. Nothing is stored.")
                val items=NotificationInboxBus.items.value
                items.forEach{item->
                    button(if(prefs.getBoolean("message_text",true))item.title.ifBlank{item.appName}else item.appName,item.appName){
                        CutoutNotificationListenerService.openFromInbox(item.key)
                    }
                }
                button("Refresh","Read the current notification snapshot"){drawPage()}
            }
            "notch"->{
                pageFrame("Notch Touch","An invisible touch target around your camera. No separate app or root needed.")
                toggle("Camera gestures","Active while your phone is unlocked and awake","notch_inbox")
                heading("GESTURES")
                NotchGesture.entries.forEach{gesture->
                    val action=NotchAction.read(prefs.getString(gesture.key,null),gesture.defaultAction)
                    button(gesture.title,action.label){
                        val values=NotchAction.entries
                        android.app.AlertDialog.Builder(this).setTitle(gesture.title)
                            .setSingleChoiceItems(values.map{it.label}.toTypedArray(),values.indexOf(action)){dialog,which->
                                prefs.edit().putString(gesture.key,values[which].name).apply()
                                dialog.dismiss();drawPage()
                            }.setNegativeButton("Cancel",null).show()
                    }
                }
                heading("TOUCH AREA")
                adjustment("Extra touch padding","notch_padding",0,12,6," dp")
                adjustment("Swipe distance","notch_swipe_distance",18,56,28," dp")
                add(label("Double tap is off by default for instant single taps. If enabled, a single tap waits briefly for the second tap. Incoming calls, AOD and the lock screen take priority.",13f,muted))
                button("Reset camera gestures","Restore default actions and sensitivity"){
                    val edit=prefs.edit();NotchGesture.entries.forEach{edit.remove(it.key)}
                    edit.remove("notch_padding").remove("notch_swipe_distance").apply();drawPage()
                }
            }
            "gestures"->{
                pageFrame("Gestures & controls","Stay in control without opening another app.")
                button("Notch Touch","Configure the camera opening separately"){go("notch")}
                toggle("Swipe horizontally","Switch between live activities","swipe_switch")
                toggle("Swipe up","Dismiss the temporary island view","swipe_close")
                toggle("Swipe down","Open Android notifications from the compact island","swipe_shade")
                heading("TOUCH")
                add(label("Tap the camera opening for the notification inbox. Swipe between pages; tap a message to open it. Tap outside the camera area to expand a live activity. Long press opens Android quick settings by default. Incoming calls override navigation gestures until answered or declined.",14f,muted))
            }
            "access"->{
                pageFrame("Setup & Shizuku","Grant the permissions needed for NEXORA.")
                button("Shizuku",ShizukuState.status.value.name.replace('_',' ')){
                    if(ShizukuState.status.value==ShizukuStatus.PERMISSION_REQUIRED)ShizukuState.requestPermission()
                    else if(ShizukuState.status.value!=ShizukuStatus.READY)Permissions.openShizuku(this)
                    else Toast.makeText(this,"Shizuku connected",Toast.LENGTH_SHORT).show()
                }
                button(if(granting)"Applying permissions…" else "Grant all with Shizuku",if(granting)"Checking Android permissions" else "Accessibility, notifications, battery exemption, phone state and test alerts"){
                    if(!granting)confirmGrantAll()
                }
                grantSummary?.let{add(label(it,14f,muted),12)}
                heading("INDIVIDUAL PERMISSIONS")
                button("Accessibility overlay",if(Permissions.isAccessibilityGranted(this))"Granted"+if(CutoutAccessibilityService.bound.value)" • connected" else " • waiting for service" else "Not granted"){Permissions.openAccessibilitySettings(this)}
                button("Notification access",if(Permissions.isNotificationAccessGranted(this))"Granted"+if(CutoutNotificationListenerService.bound.value)" • connected" else " • waiting for service" else "Not granted"){Permissions.openNotificationAccessSettings(this)}
                button("Battery optimization",if(Permissions.isBatteryOptimizationIgnored(this))"Exempt" else "Allow reliable background operation"){Permissions.requestIgnoreBatteryOptimization(this)}
                button("Phone permission",if(Permissions.isPhoneStateGranted(this))"Granted" else "Optional • improve call-end detection"){
                    requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE),601)
                }
                if(android.os.Build.VERSION.SDK_INT>=33)button("Test notifications",if(Permissions.isPostNotificationGranted(this))"Granted" else "Optional • allow NEXORA test notifications"){
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),602)
                }
                heading("OPTIONAL CONNECTION DETAILS")
                if(android.os.Build.VERSION.SDK_INT>=31)button("Bluetooth device names",if(checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==android.content.pm.PackageManager.PERMISSION_GRANTED)"Granted" else "Nearby devices permission • show connected accessory names"){
                    requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),603)
                }
                button("Wi-Fi network name",if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)"Precise location granted • Android may still restrict background SSID access" else "Optional • Android requires location access to reveal the network name"){
                    requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION),604)
                }
                add(label("Only connection labels are used. No location tracking or background location permission. Names are shown when Android makes them available.",13f,muted))
                button("Refresh status","Recheck actual Android grants and Shizuku connection"){ShizukuState.refresh();drawPage()}
                add(label("Shizuku must be running and authorized. Android or device restrictions may block individual grants; these remain available above.",13f,muted))
            }
            "statusbar"->{
                pageFrame("Status bar","Optional Shizuku controls. System alerts are kept by default.")
                if(ShizukuState.status.value!=ShizukuStatus.READY){
                    button("Connect Shizuku","Required for these controls"){go("access")}
                } else {
                    val store=StatusBarPreferences(this)
                    val epoch=pageEpoch
                    mainScope.launch{
                        val icons=store.notificationIconsMode.first()
                        val system=store.systemInfoMode.first()
                        val clock=store.clockMode.first()
                        val silent=store.silenceAlerts.first()
                        if(page=="statusbar" && pageEpoch==epoch && !isFinishing && !isDestroyed){
                            statusMode("Notification icons",icons){store.setNotificationIconsMode(it)}
                            statusMode("System information",system){store.setSystemInfoMode(it)}
                            statusMode("Clock",clock){store.setClockMode(it)}
                            val sw=Switch(this@NexoraActivity).apply{
                                text="Silence Android system alerts";setTextColor(white);isChecked=silent;setPadding(dp(8),dp(12),dp(8),dp(12))
                                setOnCheckedChangeListener{_,checked->
                                    if(checked)android.app.AlertDialog.Builder(this@NexoraActivity)
                                        .setTitle("Silence system alerts?")
                                        .setMessage("Android may suppress sounds or heads-up alerts globally, including apps excluded from NEXORA. Notifications remain in the shade. Enable only if you accept this.")
                                        .setNegativeButton("Cancel"){_,_->drawPage()}
                                        .setPositiveButton("Enable"){_,_->mainScope.launch{store.setSilenceAlerts(true);drawPage()}}.show()
                                    else mainScope.launch{store.setSilenceAlerts(false)}
                                }
                            }
                            add(sw)
                            add(label("Auto hides selected status icons only when the island expands. Behavior varies by Android version.",13f,muted))
                        }
                    }
                }
            }
            "about"->{
                pageFrame("About NEXORA","Studio • Reborn")
                add(label("Independent Canvas interface, powered by Android media sessions, notification access and accessibility. No network permission.",15f,muted))
                add(label("GPL-3.0 • Modified from EvanKoe/expressive-cutout. No warranty. Source: github.com/L3viPlayzz/expressive-cutout/tree/nexora-reborn",13f,muted))
                button("Reset NEXORA preferences","Restore renderer settings; permissions are not removed"){
                    android.app.AlertDialog.Builder(this).setTitle("Reset NEXORA?").setMessage("This restores the new renderer settings.").setNegativeButton("Cancel",null).setPositiveButton("Reset"){_,_->prefs.edit().clear().apply();go("home")}.show()
                }
            }
        }
    }
}
