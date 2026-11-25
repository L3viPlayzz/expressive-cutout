package com.ekoehler.expressivecutout.nexora

import org.junit.Assert.*
import org.junit.Test

class NexoraPolicyTest {
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
