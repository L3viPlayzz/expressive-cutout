package com.ekoehler.expressivecutout.nexora

/** Pure, testable rules used by the renderer and calibration screen. */
internal object NexoraPolicy {
    fun canDraw(interactive:Boolean, displayOn:Boolean):Boolean = interactive && displayOn
    fun expandedMillis(seconds:Int):Long = seconds.coerceIn(1,8)*1000L
    fun compactMillis(seconds:Int):Long = seconds.coerceIn(0,10)*1000L
    fun shouldCollapse(now:Long, deadline:Long, ringing:Boolean):Boolean =
        !ringing && deadline>0L && now>=deadline
    fun safeHeight(requested:Int, cameraMinimum:Int):Int = maxOf(requested,cameraMinimum,1)
    fun motionMillis(base:Long, tempo:Int, reduced:Boolean):Long =
        if(reduced)100L else base*tempo.coerceIn(60,150)/100L
    fun smoothstep(fraction:Float):Float {
        val t=fraction.coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
}
