package com.ekoehler.expressivecutout.nexora

import org.junit.Assert.*
import org.junit.Test

class NexoraPolicyTest {
    @Test fun batterySpeedUsesMeasuredNetPower(){
        assertEquals(12f,ChargeRate.watts(3_000_000,4000)!!,.001f)
        assertNull(ChargeRate.watts(Int.MIN_VALUE,4000))
        assertNull(ChargeRate.watts(0,4000))
        assertEquals(ChargeRate.SLOW,ChargeRate.classify(listOf(2f,2.1f,2.2f),45))
        assertEquals(ChargeRate.NORMAL,ChargeRate.classify(listOf(5f,6f,6f),45))
        assertEquals(ChargeRate.FAST,ChargeRate.classify(listOf(11f,12f,12f),45))
        assertEquals(ChargeRate.UNKNOWN,ChargeRate.classify(listOf(11f,12f,12f),95))
        assertEquals(ChargeRate.UNKNOWN,ChargeRate.classify(listOf(2f,12f,15f),45))
    }
    @Test fun nativeMotion(){
        assertEquals(0f,NexoraPolicy.pulse(0f,.1f,.9f),.0001f)
        assertEquals(1f,NexoraPolicy.pulse(.5f,.1f,.9f),.0001f)
        assertEquals(0f,NexoraPolicy.pulse(1f,.1f,.9f),.0001f)
        assertEquals(.85f,NexoraPolicy.entrySlope(100f,0f,325),.0001f)
        assertEquals(0f,NexoraPolicy.breath(0f,1.8f),.0001f)
        assertEquals(1f,NexoraPolicy.breath(.9f,1.8f),.0001f)
    }
    @Test fun notchDoubleTapRequiresTimingAndDistance(){
        assertTrue(NexoraPolicy.isDoubleTap(180,300,8f,24f,true))
        assertFalse(NexoraPolicy.isDoubleTap(301,300,8f,24f,true))
        assertFalse(NexoraPolicy.isDoubleTap(180,300,25f,24f,true))
        assertFalse(NexoraPolicy.isDoubleTap(180,300,8f,24f,false))
        assertEquals(NotchAction.INBOX,NotchGesture.TAP.defaultAction)
        assertEquals(NotchAction.NONE,NotchGesture.DOUBLE.defaultAction)
        assertEquals(NotchAction.QUICK_SETTINGS,NotchGesture.HOLD.defaultAction)
        assertEquals(NotchAction.QUICK_SETTINGS,NotchAction.read("HUB",NotchAction.NONE))
        assertEquals(NotchAction.NONE,NotchAction.read("invalid",NotchAction.NONE))
    }
    @Test fun inboxPagesNeverExposeEmptyPage() {
        assertEquals(0,NexoraPolicy.inboxPages(0))
        assertEquals(1,NexoraPolicy.inboxPages(3))
        assertEquals(2,NexoraPolicy.inboxPages(4))
        assertEquals(34,NexoraPolicy.inboxPages(100))
        assertEquals(0,NexoraPolicy.inboxPage(9,0))
        assertEquals(1,NexoraPolicy.inboxPage(9,4))
        assertEquals(0,NexoraPolicy.inboxPage(-1,4))
    }
    @Test fun foregroundIntegrationAndShadeGesture() {
        assertTrue(NexoraPolicy.hideDuplicate(true,"player","player"))
        assertFalse(NexoraPolicy.hideDuplicate(false,"player","player"))
        assertFalse(NexoraPolicy.hideDuplicate(true,"launcher","player"))
        assertFalse(NexoraPolicy.hideDuplicate(true,null,null))
        assertEquals(5,NexoraPolicy.gesture(1f,50f,30f,150))
    }
    @Test fun stableScreenGestures() {
        assertEquals(0,NexoraPolicy.gesture(0f,0f,30f,150))
        assertEquals(1,NexoraPolicy.gesture(-50f,3f,30f,150))
        assertEquals(-1,NexoraPolicy.gesture(50f,3f,30f,150))
        assertEquals(2,NexoraPolicy.gesture(1f,-50f,30f,150))
        assertEquals(3,NexoraPolicy.gesture(45f,45f,30f,150))
        assertEquals(4,NexoraPolicy.gesture(1f,2f,30f,600))
    }
    @Test fun retargetMotionIsBoundedAndLandsAtRest() {
        for(slope in listOf(0f,.4f,1f,2f,2.8f,3.5f)){
            var last=0f
            for(i in 0..100){
                val v=NexoraPolicy.motionPosition(i/100f,slope)
                assertTrue(v in 0f..1f)
                assertTrue(v>=last-.00001f)
                last=v
            }
            assertEquals(1f,last,.00001f)
            assertEquals(0f,NexoraPolicy.motionVelocity(1f,slope),.00001f)
        }
        assertEquals(1f,NexoraPolicy.initialSlope(100f,.25f,400),.0001f)
        assertEquals(0f,NexoraPolicy.initialSlope(-100f,.25f,400),.0001f)
        assertEquals(0f,NexoraPolicy.glyphSize(20f,-1f,24f),.0001f)
        assertEquals(12f,NexoraPolicy.glyphSize(22f,12f,20f),.0001f)
    }
    @Test fun fluidAxesStayInsideHostWindow() {
        for(vertical in listOf(false,true)) for(opening in listOf(false,true)) {
            assertEquals(0f,NexoraPolicy.morphAxis(0f,vertical,opening),.0001f)
            assertEquals(1f,NexoraPolicy.morphAxis(1f,vertical,opening),.0001f)
            var previous=0f
            for(i in 0..100) {
                val value=NexoraPolicy.morphAxis(i/100f,vertical,opening)
                assertTrue(value in 0f..1f)
                assertTrue(value>=previous)
                previous=value
            }
        }
        assertEquals(0f,NexoraPolicy.phase(.1f,.2f,.8f),.0001f)
        assertEquals(1f,NexoraPolicy.phase(.9f,.2f,.8f),.0001f)
    }
    @Test fun onlyInteractiveDisplayCanDraw() {
        assertTrue(NexoraPolicy.canDraw(true,true))
        assertFalse(NexoraPolicy.canDraw(false,true))
        assertFalse(NexoraPolicy.canDraw(true,false))
        assertFalse(NexoraPolicy.canDraw(false,false))
    }
    @Test fun timingDefaultsAndClamps() {
        assertEquals(3000L,NexoraPolicy.expandedMillis(3))
        assertEquals(1000L,NexoraPolicy.expandedMillis(-1))
        assertEquals(8000L,NexoraPolicy.expandedMillis(99))
        assertEquals(0L,NexoraPolicy.compactMillis(0))
        assertEquals(10000L,NexoraPolicy.compactMillis(99))
    }
    @Test fun ringingIsNeverAutoCollapsed() {
        assertFalse(NexoraPolicy.shouldCollapse(9000,3000,true))
        assertFalse(NexoraPolicy.shouldCollapse(2999,3000,false))
        assertTrue(NexoraPolicy.shouldCollapse(3000,3000,false))
        assertFalse(NexoraPolicy.shouldCollapse(9000,0,false))
    }
    @Test fun cameraClearanceAndSliders() {
        assertEquals(24,NexoraPolicy.safeHeight(24,20))
        assertEquals(25,NexoraPolicy.safeHeight(20,25))
        assertEquals(44,NexoraPolicy.safeHeight(44,25))
    }
    @Test fun motionProfilesAndMorphEndpoints() {
        assertEquals(340L,NexoraPolicy.motionMillis(340,100,false))
        assertEquals(255L,NexoraPolicy.motionMillis(340,75,false))
        assertEquals(100L,NexoraPolicy.motionMillis(340,100,true))
        assertEquals(0f,NexoraPolicy.smoothstep(-1f),0.0001f)
        assertEquals(.5f,NexoraPolicy.smoothstep(.5f),0.0001f)
        assertEquals(1f,NexoraPolicy.smoothstep(2f),0.0001f)
        assertTrue(NexoraPolicy.smoothstep(.25f)<NexoraPolicy.smoothstep(.75f))
    }
}
