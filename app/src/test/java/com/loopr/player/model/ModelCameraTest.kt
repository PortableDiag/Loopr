package com.loopr.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCameraTest {

    private val eps = 1e-6

    @Test
    fun `yaw 0 looks at the front from plus Z, 180 from behind`() {
        val c = ModelCamera().apply { pitch = 0.0 }
        assertTrue(c.eye(1.0)[2] > 0)
        assertEquals(0.0, c.eye(1.0)[0], eps)
        c.yaw = 180.0
        assertTrue(c.eye(1.0)[2] < 0)
    }

    @Test
    fun `yaw wraps and pitch stops short of the poles`() {
        val c = ModelCamera()
        c.yaw = 370.0
        assertEquals(10.0, c.yaw, eps)
        c.yaw = -190.0
        assertEquals(170.0, c.yaw, eps)
        c.pitch = 120.0
        assertEquals(ModelCamera.MAX_PITCH, c.pitch, eps)
    }

    @Test
    fun `zoom one fits the sphere and a half is twice as close`() {
        val c = ModelCamera().apply { radius = 2.0 }
        val d1 = c.distance(1.0)
        c.zoom = 0.5
        assertEquals(d1 / 2, c.distance(1.0), eps)
        c.zoom = 100.0
        assertEquals(ModelCamera.MAX_ZOOM, c.zoom, eps)
    }

    @Test
    fun `a portrait screen backs off so the width still fits`() {
        val c = ModelCamera()
        assertTrue(c.distance(0.5) > c.distance(1.0))
    }

    @Test
    fun `pan moves the target right and up in model radii`() {
        val c = ModelCamera().apply { radius = 2.0; pitch = 0.0; panX = 1.0 }
        assertEquals(2.0, c.target()[0], eps)
        c.panX = 0.0; c.panY = 1.0
        assertEquals(2.0, c.target()[1], eps)
        c.panX = 9.0
        assertEquals(ModelCamera.MAX_PAN, c.panX, eps)
    }

    @Test
    fun `views are the desktop's presets and reset goes home`() {
        val c = ModelCamera()
        assertTrue(c.view("back")); assertEquals(180.0, c.yaw, eps)
        assertTrue(c.view("right")); assertEquals(90.0, c.yaw, eps)
        assertTrue(c.view("top")); assertEquals(ModelCamera.MAX_PITCH, c.pitch, eps)
        assertTrue(!c.view("sideways"))
        c.zoom = 3.0; c.reset()
        assertEquals(0.0, c.yaw, eps); assertEquals(1.0, c.zoom, eps); assertEquals(ModelCamera.DEFAULT_PITCH, c.pitch, eps)
    }
}
