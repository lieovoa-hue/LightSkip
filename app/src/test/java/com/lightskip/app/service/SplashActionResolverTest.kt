package com.lightskip.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplashActionResolverTest {

    @Test
    fun relativePointScalesWithScreen() {
        val point = SplashActionResolver.resolve("rel:0.091,0.724", 1080, 2340)

        assertEquals(98.28f, point?.x ?: -1f, 0.01f)
        assertEquals(1694.16f, point?.y ?: -1f, 0.01f)
    }

    @Test
    fun legacyBoundsStillResolveToCenter() {
        val point = SplashActionResolver.resolve("590,140,660,215", 1080, 2340)

        assertEquals(625f, point?.x ?: -1f, 0.01f)
        assertEquals(177.5f, point?.y ?: -1f, 0.01f)
    }

    @Test
    fun relativeRectangleResolvesToItsCenter() {
        val point = SplashActionResolver.resolve("rel:0.1,0.2,0.3,0.4", 1000, 2000)

        assertEquals(200f, point?.x ?: -1f, 0.01f)
        assertEquals(600f, point?.y ?: -1f, 0.01f)
    }

    @Test
    fun invalidRelativeCoordinatesAreRejected() {
        assertNull(SplashActionResolver.resolve("rel:1.1,0.7", 1080, 2340))
        assertNull(SplashActionResolver.resolve("rel:0.2", 1080, 2340))
    }
}
