package com.intramap.vision

import org.junit.Assert.*
import org.junit.Test

class ArCoreReferenceAdapterGeometryTest {
    private val translation = floatArrayOf(1.25f,1.7f,3.5f)
    @Test fun imageUsesPositiveXAxis() {
        val pose = requireNotNull(referencePlanePose(translation,floatArrayOf(1f,0f,0f)))
        assertEquals(1.25,pose.x,1e-9); assertEquals(3.5,pose.y,1e-9); assertEquals(0.0,pose.headingDeg,1e-9)
    }
    @Test fun imageXAxisCanPointAlongWorldZ() {
        assertEquals(90.0,requireNotNull(referencePlanePose(translation,floatArrayOf(0f,0f,1f))).headingDeg,1e-9)
    }
    @Test fun cameraOpticalForwardIsNegativeZAxis() {
        assertEquals(270.0,requireNotNull(referencePlanePose(translation,floatArrayOf(0f,0f,1f),negateAxis = true)).headingDeg,1e-9)
    }
    @Test fun nonUnitAxesStillProduceTheCorrectHeading() {
        assertEquals(90.0,requireNotNull(referencePlanePose(translation,floatArrayOf(0f,0f,2f))).headingDeg,1e-9)
    }
    @Test fun verticalOrNearlyVerticalAxesAreRejected() {
        assertNull(referencePlanePose(translation,floatArrayOf(0f,1f,0f)))
        assertNull(referencePlanePose(translation,floatArrayOf(0.1f,1f,0f)))
    }
    @Test fun malformedOrNonfiniteInputsAreRejected() {
        assertNull(referencePlanePose(floatArrayOf(1f,2f),floatArrayOf(1f,0f,0f)))
        assertNull(referencePlanePose(translation,floatArrayOf(Float.NaN,0f,1f)))
        assertNull(referencePlanePose(floatArrayOf(Float.POSITIVE_INFINITY,0f,0f),floatArrayOf(1f,0f,0f)))
    }
    @Test fun zeroDirectionCannotInventAHeading() {
        assertNull(referencePlanePose(translation,floatArrayOf(0f,0f,0f)))
    }
}
