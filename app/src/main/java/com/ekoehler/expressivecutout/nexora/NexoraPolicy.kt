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
    fun lerp(a:Float,b:Float,t:Float):Float=a+(b-a)*t.coerceIn(0f,1f)
    fun phase(time:Float,start:Float,end:Float):Float=smoothstep((time-start)/(end-start).coerceAtLeast(.001f))
    /** Bounded, independent axes; the host window never needs overshoot space. */
    fun morphAxis(time:Float,vertical:Boolean,opening:Boolean):Float {
        val u=time.coerceIn(0f,1f)
        val lead=if(vertical==opening) .10f else 0f
        val q=((u-lead)/(1f-lead)).coerceIn(0f,1f)
        return q*q*(3f-2f*q)
    }
    // Monotone Hermite motion: retain useful incoming velocity on a retarget,
    // land at zero velocity, and never overshoot the allocated window.
    fun initialSlope(delta:Float,velocity:Float,durationMs:Long):Float =
        if(kotlin.math.abs(delta)<.01f)0f else (velocity*durationMs/delta).coerceIn(0f,2.8f)
    fun motionPosition(t:Float,slope:Float):Float {
        val q=t.coerceIn(0f,1f)
        val s=slope.coerceIn(0f,2.8f)
        return (q*q*(3f-2f*q)+s*q*(1f-q)*(1f-q)).coerceIn(0f,1f)
    }
    fun motionVelocity(t:Float,slope:Float):Float {
        val q=t.coerceIn(0f,1f)
        return (6f*q*(1f-q)+slope.coerceIn(0f,2.8f)*(1f-q)*(1f-3f*q)).coerceAtLeast(0f)
    }
    fun glyphSize(requested:Float,room:Float,height:Float):Float =
        minOf(requested,room,height).coerceAtLeast(0f)
    fun smoothstep(fraction:Float):Float {
        val t=fraction.coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
}
