package com.loopr.player.model

import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One model on one [SurfaceView], drawn by Filament. Main thread only, and frames are drawn only
 * when something changed (or while it spins), so a still model costs nothing.
 *
 * Draws the rest pose — skinned meshes posed by their joints — with the model's own materials:
 * textures, alpha, unlit (which is what most VRM exporters give MToon as a fallback) and PBR.
 * Not drawn, as on the desktop: animation, spring bones, expressions, outlines.
 */
class ModelScene(private val surfaceView: SurfaceView, private val onFrame: () -> Unit) {

    companion object {
        /** Null once Filament's native code has loaded; otherwise why it did not. */
        val unavailable: String? by lazy {
            runCatching { Filament.init(); Gltfio.init() }.exceptionOrNull()?.let {
                "3D models need Filament's native code, which this phone's processor type does not have (${it.javaClass.simpleName})"
            }
        }

        val BACKGROUNDS = linkedMapOf(
            "dark" to floatArrayOf(0.07f, 0.08f, 0.10f),
            "grey" to floatArrayOf(0.32f, 0.33f, 0.36f),
            "light" to floatArrayOf(0.90f, 0.91f, 0.93f)
        )

        private fun srgbToLinear(c: Float): Float =
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    val camera = ModelCamera()
    var light = 1f
        set(v) { field = v.coerceIn(0f, 3f); gradingDirty = true; applyBackground(); dirty = true }
    var background = "dark"
        set(v) { field = v; applyBackground(); dirty = true }
    var spin = false
        set(v) { field = v; lastFrameNanos = 0; dirty = true }
    var spinSpeed = 30.0

    /** True from [setModel] until every texture has been decoded and uploaded. */
    var loading = false
        private set
    /** The model's size in metres, x by y by z, once loaded. */
    var size: DoubleArray? = null
        private set

    private val engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val filamentCamera: Camera = engine.createCamera(cameraEntity)
    private val sun = EntityManager.get().create()
    private val indirect: IndirectLight
    private var grading: ColorGrading? = null
    private var gradingDirty = true

    private val materials = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine, true)
    private var asset: FilamentAsset? = null
    /** Filament reads it asynchronously while loading; it must stay reachable until then. */
    private var source: ByteBuffer? = null
    private var turnRound = false

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private var width = 1
    private var height = 1
    private var dirty = true
    private var running = false
    private var destroyed = false
    private var lastFrameNanos = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || destroyed) return
            Choreographer.getInstance().postFrameCallback(this)
            tick(frameTimeNanos)
        }
    }

    init {
        view.scene = scene
        view.camera = filamentCamera
        view.antiAliasing = View.AntiAliasing.FXAA
        // Exposure 1: unlit materials (most VRMs) show their colours exactly; the light slider is a
        // grading exposure on top, so it brightens lit and unlit alike.
        filamentCamera.setExposure(1f)

        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.98f, 0.95f)
            .intensity(2.4f)
            .direction(0f, -0.5f, -1f)
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)
        indirect = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(0.55f)
            .build(engine)
        scene.indirectLight = indirect

        applyBackground()
        uiHelper.isOpaque = true
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                dirty = true
            }

            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
            }

            override fun onResized(w: Int, h: Int) {
                width = max(1, w); height = max(1, h)
                view.viewport = Viewport(0, 0, width, height)
                dirty = true
            }
        }
        uiHelper.attachTo(surfaceView)
    }

    fun resume() {
        if (running || destroyed) return
        running = true
        dirty = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    fun invalidate() { dirty = true }

    /**
     * Shows [data] (a whole GLB/VRM, or a `.gltf` with its side files in [resources]). Returns an
     * error a person can read, or null. Textures load in the background; [loading] says when done.
     */
    fun setModel(data: ByteBuffer, meta: ModelMeta, resources: Map<String, ByteBuffer>): String? {
        clearModel()
        val a = assetLoader.createAsset(data) ?: return "Filament could not read this model"
        for ((uri, buf) in resources) resourceLoader.addResourceData(uri, buf)
        val missing = a.resourceUris.filter { !resourceLoader.hasResourceData(it) }
        if (missing.isNotEmpty()) {
            assetLoader.destroyAsset(a)
            resourceLoader.evictResourceData()
            return "This .gltf needs ${missing.joinToString()} beside it, which Loopr can't reach. Open the .glb, or add its folder in 3D models."
        }
        if (!resourceLoader.asyncBeginLoad(a)) {
            assetLoader.destroyAsset(a)
            return "Filament could not load this model's buffers"
        }
        asset = a
        source = data
        loading = true
        turnRound = meta.facesMinusZ
        val tm = engine.transformManager
        if (turnRound) tm.setTransform(tm.getInstance(a.root), floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
        scene.addEntities(a.entities)

        val box = a.boundingBox
        val c = box.center
        val h = box.halfExtent
        camera.center = doubleArrayOf(if (turnRound) -c[0].toDouble() else c[0].toDouble(), c[1].toDouble(), if (turnRound) -c[2].toDouble() else c[2].toDouble())
        camera.radius = sqrt((h[0] * h[0] + h[1] * h[1] + h[2] * h[2]).toDouble())
        size = doubleArrayOf(h[0] * 2.0, h[1] * 2.0, h[2] * 2.0).map { Math.round(it * 1000) / 1000.0 }.toDoubleArray()
        poseSkins()
        dirty = true
        return null
    }

    fun clearModel() {
        asset?.let {
            if (loading) resourceLoader.asyncCancelLoad()
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        resourceLoader.evictResourceData()
        asset = null
        source = null
        loading = false
        size = null
        dirty = true
    }

    val hasModel: Boolean get() = asset != null

    private fun poseSkins() {
        asset?.instance?.animator?.updateBoneMatrices()
    }

    private fun applyBackground() {
        val c = BACKGROUNDS[background] ?: BACKGROUNDS.getValue("dark")
        // The light is a grading exposure, which the cleared background passes through too; divide
        // it back out so the slider lights the model and leaves the backdrop as chosen.
        val k = 1f / exposureOf(light)
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clearColor = floatArrayOf(srgbToLinear(c[0]) * k, srgbToLinear(c[1]) * k, srgbToLinear(c[2]) * k, 1f)
            clear = true
        }
    }

    private fun tick(frameTimeNanos: Long) {
        if (loading) {
            resourceLoader.asyncUpdateLoad()
            dirty = true
            if (resourceLoader.asyncGetLoadProgress() >= 1f) {
                loading = false
                asset?.releaseSourceData()
                source = null
                poseSkins()
                onFrame()
            }
        }
        if (spin) {
            if (lastFrameNanos != 0L) camera.yaw += spinSpeed * (frameTimeNanos - lastFrameNanos) / 1e9
            lastFrameNanos = frameTimeNanos
            dirty = true
        }
        if (!dirty || destroyed) return
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (gradingDirty) {
            grading?.let { engine.destroyColorGrading(it) }
            grading = ColorGrading.Builder()
                .toneMapper(ToneMapper.Linear())
                .exposure((ln(exposureOf(light).toDouble()) / ln(2.0)).toFloat())
                .build(engine)
            view.colorGrading = grading
            gradingDirty = false
        }
        placeCamera()
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            dirty = loading || spin
            onFrame()
        }
    }

    private fun placeCamera() {
        val aspect = width.toDouble() / height
        val e = camera.eye(aspect)
        val t = camera.target()
        filamentCamera.setProjection(camera.fovDeg, aspect, camera.near(aspect), camera.far(aspect), Camera.Fov.VERTICAL)
        filamentCamera.lookAt(e[0], e[1], e[2], t[0], t[1], t[2], 0.0, 1.0, 0.0)
        // A light that comes from over the viewer's shoulder, wherever they stand, so no side of
        // the model is ever left in the dark.
        val dx = t[0] - e[0]; val dy = t[1] - e[1]; val dz = t[2] - e[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz).takeIf { it > 0 } ?: 1.0
        val lm = engine.lightManager
        lm.setDirection(lm.getInstance(sun), (dx / len + 0.25 * dz / len).toFloat(), (dy / len - 0.6).toFloat(), (dz / len - 0.25 * dx / len).toFloat())
    }

    private fun exposureOf(light: Float) = max(light, 0.02f)

    fun destroy() {
        if (destroyed) return
        pause()
        clearModel()
        destroyed = true
        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = null
        resourceLoader.destroy()
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        grading?.let { engine.destroyColorGrading(it) }
        engine.destroyEntity(sun)
        engine.destroyIndirectLight(indirect)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        EntityManager.get().destroy(sun)
        engine.destroy()
    }
}
