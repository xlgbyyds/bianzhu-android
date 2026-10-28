package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrSideClassifierTest {
    @Test fun obviousSidesAndLongAmbiguousText() {
        assertEquals("me", OcrSideClassifier.classify(860, 1100, 1200))
        assertEquals("other", OcrSideClassifier.classify(100, 390, 1200))
        assertEquals("unknown", OcrSideClassifier.classify(130, 1080, 1200))
    }

    @Test fun invalidBoundsAreNeverAssignedToTheOtherPerson() {
        assertEquals("unknown", OcrSideClassifier.classify(0, 100, 0))
        assertEquals("unknown", OcrSideClassifier.classify(300, 200, 1200))
    }
}
