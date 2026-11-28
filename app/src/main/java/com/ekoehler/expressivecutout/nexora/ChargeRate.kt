package com.ekoehler.expressivecutout.nexora

/** Net battery input, not adapter output. Thresholds are deliberately conservative. */
internal enum class ChargeRate(val title:String,val badge:String) {
    UNKNOWN("Charging",""), SLOW("Slow charging","Slow"), NORMAL("Charging",""), FAST("Fast charging","Fast");
    companion object {
        fun watts(currentMicroamps:Int,voltageMillivolts:Int):Float? {
            if(currentMicroamps<=0 || voltageMillivolts !in 2500..10000)return null
            return (currentMicroamps.toDouble()*voltageMillivolts/1_000_000_000.0).toFloat().takeIf{it in .1f..100f}
        }
        fun classify(samples:List<Float>,level:Int):ChargeRate {
            // Near full, normal tapering must not be mistaken for a slow charger.
            if(samples.size<3 || level !in 0..79)return UNKNOWN
            val v=samples.sorted()
            if(v.last()-v.first()>maxOf(2f,v[v.size/2]*.45f))return UNKNOWN
            return when {v[v.size/2]<3.5f->SLOW;v[v.size/2]>=10f->FAST;else->NORMAL}
        }
    }
}
