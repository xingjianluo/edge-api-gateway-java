package com.github.xingjianluo.edgeapigatewayjava.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HopByHopTest {

    @Test
    void hopByHopHeadersRecognizedCaseInsensitively() {
        assertTrue(HopByHop.shouldStrip("Connection"));
        assertTrue(HopByHop.shouldStrip("TRANSFER-ENCODING"));
        assertTrue(HopByHop.shouldStrip("keep-alive"));
        assertTrue(HopByHop.shouldStrip("x-forwarded-for"));
        assertFalse(HopByHop.shouldStrip("x-business"));
        assertFalse(HopByHop.shouldStrip(null));
    }
}
