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
import com.ekoehler.expressivecutout.MainActivity
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
    private val mainScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
    private fun dp(v:Int) = (v*resources.displayMetrics.density).toInt()
    private fun surface(c:Int,r:Int=22)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK;window.decorView.systemUiVisibility=0;drawMenu()}
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
    private fun drawMenu(){page="home";drawPage()}
    private fun drawPage(){
        when(page){
            "home" -> {
                pageFrame("N E X O R A","STUDIO  /  1.2")
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
                button("Motion","Timing, speed, haptics and motion lab"){go("motion")}
                button("Live activities","Music, timers, charging and connections"){go("activities")}
                button("Notifications","Privacy, queue and inbox"){go("notifications")}
                button("Gestures & controls","Swipe, long press and Quick Controls"){go("gestures")}
                heading("SYSTEM")
                button("Setup & Shizuku","Permissions and Android integration"){go("access")}
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
                pageFrame("Motion","Make the island feel natural, not busy.")
                choice("Animation tempo","motion_speed",listOf("Fast" to 75,"Fluid" to 100,"Gentle" to 130),100)
                adjustment("Expanded duration","expand_seconds",1,8,3," s")
                adjustment("Compact event duration","compact_seconds",0,10,3," s")
                toggle("Haptic touch","Subtle system feedback on island touches","haptics")
                toggle("Reduced motion","Short transitions and static waveform","reduce_motion",false)
                heading("MOTION LAB")
                button("Notification Morph","Try a sample notification without sending one"){requestIsland("demo_message")}
                button("Energy Flow","Preview a charge at 84%"){preview(SystemEventType.CHARGING_STARTED,84)}
                button("Connect Pulse","Preview a Wi-Fi connection"){preview(SystemEventType.WIFI_CONNECTED)}
                button("Unlock Signature","Preview confirmed-unlock effect only"){preview(SystemEventType.DEVICE_UNLOCKED)}
                button("Sound Flow","Live music layout with sample artwork"){calibrating=true;calibrationMode=2;drawCalibration()}
            }
            "activities"->{
                pageFrame("Live activities","Choose what can appear on the Island.")
                toggle("Sound Flow","Album art, transport and playback progress","music")
                toggle("Timers","Supported clock app timers and stopwatches","timers")
                toggle("Signature events","Master switch for system events","events")
                heading("SYSTEM EVENTS")
                toggle("Charging & battery","Power connected, disconnected, full and low battery","event_energy")
                toggle("Live charging","Keep the real battery level compact while plugged in","charge_live",false)
                toggle("Connectivity","Wi-Fi, Bluetooth, VPN, headphones and hotspot","event_connect")
                toggle("Unlock Signature","Confirmed device unlock, not biometric scanning","event_unlock")
                toggle("Device locked","Show lock events when the screen is on","event_lock")
                toggle("Other system events","USB, debugging and ringer mode","event_system")
                heading("CALLS")
                add(label("Incoming calls always get priority. Supported answer and decline actions stay available until the dialer confirms the call state.",14f,muted))
            }
            "notifications"->{
                pageFrame("Notifications","Your messages stay in the Android notification shade.")
                toggle("Notification morph","Show new notifications in the island","notifications")
                toggle("Silent notifications","Also include notifications marked silent by Android","silent_notifications")
                toggle("Message previews","Show message content while the phone is unlocked","message_text")
                toggle("Smart queue","Optional • let new events wait rather than interrupt each other","queue_events",false)
                toggle("Live progress","Downloads and uploads stay visible while Android reports progress","live_progress")
                toggle("Notification inbox","Include recent active notifications when browsing","inbox")
                button("Open inbox","Browse active notifications on the real island"){requestIsland("inbox")}
                button("Notification list","View and open active notifications in NEXORA"){go("inbox")}
                button("Apps & exclusions","Choose which apps may appear"){go("apps")}
                add(label("Lock screen message contents stay private. Android heads-up alerts remain enabled unless you explicitly change the advanced Shizuku setting.",13f,muted))
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
                val apps=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                    .map{it.activityInfo.packageName to it.loadLabel(pm).toString()}.distinctBy{it.first}.filter{it.first!=packageName}.sortedBy{it.second.lowercase()}
                val store=com.ekoehler.expressivecutout.data.AppPreferences(this)
                mainScope.launch {
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
                    if(page=="apps"){
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
                if(items.isEmpty())add(label("All caught up",20f,muted,true))
                else items.forEach{item->
                    button(if(prefs.getBoolean("message_text",true))item.title.ifBlank{item.appName}else item.appName,item.appName){
                        CutoutNotificationListenerService.openFromInbox(item.key)
                    }
                }
                button("Refresh","Read the current notification snapshot"){drawPage()}
            }
            "gestures"->{
                pageFrame("Gestures & controls","Stay in control without opening another app.")
                toggle("Swipe horizontally","Switch between live activities","swipe_switch")
                toggle("Swipe up","Dismiss the temporary island view","swipe_close")
                toggle("Hold for Quick Controls","Long press opens volume and shortcuts instead of inbox","hold_hub",false)
                button("Open Quick Controls","Media volume • Android quick settings • inbox"){requestIsland("hub")}
                heading("TOUCH")
                add(label("Tap compact to expand. Tap the camera row to collapse. Tap a notification to open it. Incoming calls override navigation gestures until answered or declined.",14f,muted))
            }
            "access"->{
                pageFrame("Setup & Shizuku","Grant only the access you want to use.")
                button("Accessibility overlay",if(CutoutAccessibilityService.bound.value)"Connected • ready" else "Required • enable NEXORA"){open(Settings.ACTION_ACCESSIBILITY_SETTINGS)}
                button("Notification access",if(CutoutNotificationListenerService.bound.value)"Connected • media and messages" else "Required for notifications and music"){open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)}
                button("Shizuku",ShizukuState.status.value.name.replace('_',' ')){
                    when(ShizukuState.status.value){
                        ShizukuStatus.PERMISSION_REQUIRED->ShizukuState.requestPermission()
                        ShizukuStatus.READY->startActivity(Intent(this,MainActivity::class.java))
                        else->ShizukuState.installedManagerPackage(this)?.let{packageManager.getLaunchIntentForPackage(it)}?.let{startActivity(it)} ?: Toast.makeText(this,"Install and start Shizuku first",Toast.LENGTH_LONG).show()
                    }
                }
                button("Phone permission","Optional • improve call-end detection"){
                    if(android.os.Build.VERSION.SDK_INT>=23)requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE),601)
                }
                button("Advanced Android settings","Original integration, per-app and status bar settings"){startActivity(Intent(this,MainActivity::class.java))}
                add(label("Shizuku cannot grant unrestricted system access. Some legacy appearance settings do not control the new renderer.",13f,muted))
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
