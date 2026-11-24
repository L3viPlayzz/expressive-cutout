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
    private fun dp(v:Int) = (v*resources.displayMetrics.density).toInt()
    private fun surface(c:Int,r:Int=22)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);window.statusBarColor=Color.BLACK;window.navigationBarColor=Color.BLACK;window.decorView.systemUiVisibility=0;drawMenu()}
    override fun onResume(){
        super.onResume()
        ShizukuState.refresh()
        if(calibrating) drawCalibration() else drawMenu()
    }
    override fun onPause(){
        NexoraCalibration.mode.value=0
        super.onPause()
    }
    override fun onDestroy(){
        NexoraCalibration.mode.value=0
        super.onDestroy()
    }
    @Deprecated("Use system back navigation")
    override fun onBackPressed(){
        if(calibrating) exitCalibration() else super.onBackPressed()
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
    private fun toggle(title:String,sub:String,key:String){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(14));background=surface(Color.rgb(19,21,30))}
        val text=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(label(title,16f,white,true));addView(label(sub,12f,muted))}
        row.addView(text,LinearLayout.LayoutParams(0,-2,1f));row.addView(Switch(this).apply{isChecked=prefs.getBoolean(key,true);contentDescription=title;setOnCheckedChangeListener{_,checked->prefs.edit().putBoolean(key,checked).apply()}});add(row)
    }
    private fun open(action:String){runCatching{startActivity(Intent(action))}.onFailure{Toast.makeText(this,"Settings unavailable",Toast.LENGTH_SHORT).show()}}
    private fun preview(type:SystemEventType,level:Int?=null){
        if(!CutoutAccessibilityService.bound.value){Toast.makeText(this,"Enable NEXORA accessibility first",Toast.LENGTH_LONG).show();return}
        IslandEventBus.emit(CutoutSignal.System(type,level))
    }
    private fun exitCalibration(){
        NexoraCalibration.mode.value=0
        calibrating=false
        drawMenu()
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
        adjustment("Height","island_height",30,54,36," dp")
        adjustment("Horizontal offset","island_x",-24,24,0," dp")
        adjustment("Vertical offset","island_y",0,16,0," dp")
        add(label("Camera clearance limits some adjustments so the island stays around your camera.",12f,muted))
        button("Reset to default","Restore the approved proportions"){
            prefs.edit().remove("island_width").remove("island_height").remove("island_x").remove("island_y").apply()
            drawCalibration()
        }
        button("Done","Close preview and return to invisible standby when idle"){exitCalibration()}
    }
    private fun drawMenu(){
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.BLACK);isFillViewport=true;clipToPadding=false}
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(24),dp(20),dp(32))}
        scroll.addView(content);setContentView(scroll)
        add(label("N E X O R A",32f,blue,true),3);add(label("R E B O R N   /   1.1",13f,violet,true),8)
        add(label("Beyond the Island",18f,white,true));add(label("A living interface. Invisible when idle.",14f,muted),24)
        heading("01   /   CONNECTION")
        button("Accessibility overlay",if(CutoutAccessibilityService.bound.value)"Connected • ready" else "Required • enable the NEXORA service"){open(Settings.ACTION_ACCESSIBILITY_SETTINGS)}
        button("Notification access",if(CutoutNotificationListenerService.bound.value)"Connected • media and messages" else "Required for notifications and music"){open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)}
        button("Shizuku",ShizukuState.status.value.name.replace('_',' ')){
            when(ShizukuState.status.value){
                ShizukuStatus.PERMISSION_REQUIRED->ShizukuState.requestPermission()
                ShizukuStatus.READY->startActivity(Intent(this,MainActivity::class.java))
                else->ShizukuState.installedManagerPackage(this)?.let{packageManager.getLaunchIntentForPackage(it)}?.let{startActivity(it)} ?: Toast.makeText(this,"Install and start Shizuku first",Toast.LENGTH_LONG).show()
            }
        }
        heading("02   /   LIVE EXPERIENCE")
        toggle("NEXORA Island","No overlay while idle","enabled")
        toggle("Notification morph","New messages and progress","notifications")
        toggle("Sound Flow","Album art and playback controls","music")
        toggle("Signature events","Charging, connectivity and unlock","events")
        heading("03   /   APPEARANCE")
        button("Size & position","Live camera alignment • width, height and offsets"){
            calibrating=true
            calibrationMode=1
            drawCalibration()
        }
        heading("04   /   MOTION LAB")
        button("Energy Flow","Preview the charging animation"){preview(SystemEventType.CHARGING_STARTED,84)}
        button("Connect Pulse","Preview the Wi-Fi animation"){preview(SystemEventType.WIFI_CONNECTED)}
        button("Unlock Signature","Visual preview only — not biometric authentication"){preview(SystemEventType.DEVICE_UNLOCKED)}
        heading("05   /   ADVANCED")
        button("Advanced settings","Original Android integration and Shizuku settings. Legacy preview may not work."){startActivity(Intent(this,MainActivity::class.java))}
        add(label("Tap to expand • swipe sideways to switch • hold to browse messages • swipe up to close.",13f,muted),20)
        add(label("GPL-3.0 • Modified from EvanKoe/expressive-cutout. No warranty. Source: github.com/L3viPlayzz/expressive-cutout/tree/nexora-reborn",12f,muted))
    }
}
