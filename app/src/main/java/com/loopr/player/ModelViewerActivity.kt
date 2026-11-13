package com.loopr.player

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.loopr.player.databinding.ActivityModelBinding
import com.loopr.player.model.ControllableModel
import com.loopr.player.model.ModelItem
import com.loopr.player.model.ModelLibrary
import com.loopr.player.model.ModelMeta
import com.loopr.player.model.ModelScene
import com.loopr.player.model.ModelStatus
import com.loopr.player.model.Models
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.hypot

/**
 * A 3D model full screen — VRM 0.x / 1.0, GLB or glTF — with its folder as a queue, as a video has.
 *
 * One finger turns it, a pinch zooms, two fingers pan, a double tap puts it back; a tap shows or
 * hides the controls. Everything here is also `loopr.model` in the local API, which runs these same
 * methods.
 */
class ModelViewerActivity : AppCompatActivity(), ControllableModel {

    companion object {
        const val EXTRA_INDEX = "model_index"
        private const val TAG = "LooprModel"

        /** The queue a library launch hands over (an intent can't carry hundreds of items cheaply). */
        @Volatile var pendingQueue: List<ModelItem>? = null

        private val BACKGROUND_ORDER = listOf("dark", "grey", "light")
    }

    override val playerId: Int = Players.newId()

    private lateinit var binding: ActivityModelBinding
    private var scene: ModelScene? = null
    private var queue: List<ModelItem> = emptyList()
    private var index = 0
    private var meta: ModelMeta? = null
    private var error: String? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityModelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = bars.top + dp(8), left = bars.left + dp(6), right = bars.right + dp(6))
            binding.bottomBar.updatePadding(bottom = bars.bottom + dp(12), left = bars.left + dp(12), right = bars.right + dp(12))
            insets
        }

        val why = ModelScene.unavailable
        if (why == null) {
            scene = ModelScene(binding.modelSurface) { refreshUi() }
        } else {
            error = why
        }
        Models.register(this)
        setUpControls()
        setUpGestures()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        scene?.resume()
    }

    override fun onPause() {
        scene?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        Models.unregister(this)
        loadJob?.cancel()
        scene?.destroy()
        scene = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- Opening ----------------

    private fun handle(intent: Intent) {
        val handed = pendingQueue
        pendingQueue = null
        val items: List<ModelItem>
        val start: Int
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            // A file manager's launch: the file, plus any siblings it attached (Sift's convention).
            val data = intent.data!!
            val siblings = clipModels(intent.clipData)
            if (siblings.size > 1 && siblings.any { it.uri == data }) {
                items = siblings
                start = siblings.indexOfFirst { it.uri == data }
            } else {
                items = listOf(ModelLibrary.fromUri(this, data))
                start = 0
            }
        } else if (handed != null && handed.isNotEmpty()) {
            items = handed
            start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, handed.lastIndex)
        } else if (queue.isNotEmpty()) {
            return // a relaunch with nothing new: keep what is showing
        } else {
            finish(); return
        }
        queue = items
        load(start)
    }

    private fun clipModels(clip: ClipData?): List<ModelItem> {
        if (clip == null) return emptyList()
        val out = ArrayList<ModelItem>()
        for (i in 0 until clip.itemCount) {
            val uri: Uri = clip.getItemAt(i).uri ?: continue
            val item = ModelLibrary.fromUri(this, uri)
            if (ModelMeta.isModelName(item.name)) out.add(item)
        }
        return out.sortedWith(compareBy(com.loopr.player.model.NaturalOrder) { it.name })
    }

    private fun load(i: Int) {
        val s = scene
        index = i.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        meta = null
        val item = queue.getOrNull(index) ?: return
        loadJob?.cancel()
        if (s == null) { refreshUi(); return }
        error = null
        s.clearModel()
        refreshUi()
        loadJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val data = ModelMeta.unescapeSlashes(ModelLibrary.readAll(this@ModelViewerActivity, item.uri))
                    val m = ModelMeta.parse(data)
                    val side = HashMap<String, ByteBuffer>()
                    for (u in m.externalUris) ModelLibrary.sideFile(this@ModelViewerActivity, item, u)?.let { side[u] = it }
                    Triple(data, m, side)
                }
            }
            // Superseded (another model asked for, or the screen closing): the newer load owns the
            // view, and this one's cancellation is not an error to show.
            if (!isActive || scene == null || queue.getOrNull(index) != item) return@launch
            result.fold(
                onSuccess = { (data, m, side) ->
                    meta = m
                    error = s.setModel(data, m, side)
                },
                onFailure = { e ->
                    Log.w(TAG, "model open failed ${item.name}", e)
                    error = e.message ?: e.javaClass.simpleName
                }
            )
            s.camera.reset()
            s.invalidate()
            refreshUi()
        }
    }

    // ---------------- Controls ----------------

    private fun setUpControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPrev.setOnClickListener { previous() }
        binding.btnNext.setOnClickListener { if (!next()) toast("Last model in this folder") }
        binding.chipFront.setOnClickListener { setView("front") }
        binding.chipBack.setOnClickListener { setView("back") }
        binding.chipSide.setOnClickListener { setView("right") }
        binding.chipTop.setOnClickListener { setView("top") }
        binding.chipSpin.setOnClickListener { setSpin(!(scene?.spin ?: false), null) }
        binding.chipBackground.setOnClickListener {
            val cur = scene?.background ?: "dark"
            setBackground(BACKGROUND_ORDER[(BACKGROUND_ORDER.indexOf(cur) + 1) % BACKGROUND_ORDER.size])
        }
        binding.chipReset.setOnClickListener { resetView() }
        binding.lightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) scene?.light = progress / 100f
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun refreshUi() {
        if (!::binding.isInitialized) return
        val item = queue.getOrNull(index)
        val s = scene
        binding.title.text = meta?.title ?: item?.name ?: ""
        binding.details.text = listOfNotNull(
            meta?.format,
            meta?.let { "${"%,d".format(it.triangles)} triangles" },
            meta?.authors?.takeIf { it.isNotEmpty() }?.joinToString(),
            if (queue.size > 1) "${index + 1} / ${queue.size}" else null
        ).joinToString(" · ")
        binding.btnPrev.isEnabled = queue.size > 1
        binding.btnNext.isEnabled = queue.size > 1
        binding.btnPrev.alpha = if (queue.size > 1) 1f else 0.35f
        binding.btnNext.alpha = binding.btnPrev.alpha
        val msg = when {
            error != null -> getString(R.string.model_unreadable, error)
            s != null && (s.loading || (!s.hasModel && item != null)) -> getString(R.string.model_loading)
            else -> null
        }
        binding.message.visibility = if (msg == null) View.GONE else View.VISIBLE
        binding.message.text = msg ?: ""
        if (s != null) {
            binding.chipSpin.isSelected = s.spin
            binding.chipSpin.alpha = if (s.spin) 1f else 0.7f
            binding.chipBackground.text = getString(when (s.background) {
                "grey" -> R.string.model_bg_grey
                "light" -> R.string.model_bg_light
                else -> R.string.model_bg_dark
            })
            val p = (s.light * 100).toInt()
            if (binding.lightBar.progress != p) binding.lightBar.progress = p
        }
    }

    // ---------------- Gestures ----------------

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var twoFinger = false

    private fun setUpGestures() {
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                binding.controls.visibility = if (binding.controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { resetView(); return true }
        })
        // Two pointers tracked by hand: ScaleGestureDetector's minimum span never lets a pinch start
        // on some phones (Loopr's floating windows learned this).
        binding.modelSurface.setOnTouchListener { _, e ->
            taps.onTouchEvent(e)
            val s = scene ?: return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; twoFinger = false }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                    centroid(e, skip).let { (x, y, span) -> lastX = x; lastY = y; lastSpan = span }
                    twoFinger = e.pointerCount - (if (skip >= 0) 1 else 0) >= 2
                }
                MotionEvent.ACTION_MOVE -> {
                    val (x, y, span) = centroid(e, -1)
                    if (e.pointerCount >= 2) {
                        if (lastSpan > 0 && span > 0) s.camera.zoom *= (lastSpan / span).toDouble()
                        s.camera.panByPixels(x - lastX, y - lastY, binding.modelSurface.height,
                            binding.modelSurface.width.toDouble() / binding.modelSurface.height.coerceAtLeast(1))
                        lastSpan = span
                    } else if (!twoFinger) {
                        s.camera.drag(x - lastX, y - lastY, resources.displayMetrics.density)
                    }
                    lastX = x; lastY = y
                    s.invalidate()
                }
            }
            true
        }
    }

    /** The fingers' centre and spread, leaving out [skip] (a finger that is lifting). */
    private fun centroid(e: MotionEvent, skip: Int): Triple<Float, Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        if (n == 0) return Triple(e.x, e.y, 0f)
        val cx = sx / n; val cy = sy / n
        var spread = 0f
        for (i in 0 until e.pointerCount) if (i != skip) spread += hypot(e.getX(i) - cx, e.getY(i) - cy)
        return Triple(cx, cy, if (n >= 2) spread / n else 0f)
    }

    // ---------------- ControllableModel (the local API) ----------------

    override fun modelStatus(): ModelStatus {
        val s = scene
        val item = queue.getOrNull(index)
        return ModelStatus(
            name = item?.name ?: "", uri = item?.uri?.toString() ?: "", path = item?.path,
            index = index, queueSize = queue.size,
            loading = s != null && (s.loading || (!s.hasModel && error == null && item != null)),
            error = error, meta = meta, size = s?.size,
            yaw = s?.camera?.yaw ?: 0.0, pitch = s?.camera?.pitch ?: 0.0, zoom = s?.camera?.zoom ?: 1.0,
            panX = s?.camera?.panX ?: 0.0, panY = s?.camera?.panY ?: 0.0,
            spin = s?.spin ?: false, spinSpeed = s?.spinSpeed ?: 30.0,
            light = s?.light ?: 1f, background = s?.background ?: "dark"
        )
    }

    override fun modelQueue(): List<ModelItem> = queue

    private inline fun cam(block: (ModelScene) -> Unit) {
        val s = scene ?: throw IllegalStateException(error ?: "No renderer")
        block(s)
        s.invalidate()
        refreshUi()
    }

    override fun rotateTo(yaw: Double?, pitch: Double?) = cam { s ->
        yaw?.let { s.camera.yaw = it }; pitch?.let { s.camera.pitch = it }
    }

    override fun rotateBy(yaw: Double, pitch: Double) = cam { s -> s.camera.yaw += yaw; s.camera.pitch += pitch }

    override fun setZoom(zoom: Double) = cam { s -> s.camera.zoom = zoom }

    override fun panTo(x: Double?, y: Double?) = cam { s -> x?.let { s.camera.panX = it }; y?.let { s.camera.panY = it } }

    override fun setView(name: String): Boolean {
        var ok = false
        cam { s -> ok = s.camera.view(name) }
        return ok
    }

    override fun resetView() = cam { s -> s.camera.reset() }

    override fun setSpin(on: Boolean, speed: Double?) = cam { s -> speed?.let { s.spinSpeed = it }; s.spin = on }

    override fun setLight(light: Float) = cam { s -> s.light = light }

    override fun setBackground(name: String) = cam { s -> s.background = name }

    override fun next(): Boolean {
        if (index >= queue.size - 1) return false
        load(index + 1)
        return true
    }

    override fun previous() { if (queue.size > 1) load(if (index > 0) index - 1 else queue.size - 1) }

    override fun jumpTo(index: Int) = load(index)

    override fun closeModel() = finish()

    override fun screenshotTarget(what: String): Screenshot.Target {
        scene?.invalidate()
        val shot = if (what == "window") Screenshot.window(window, binding.modelSurface)
        else Screenshot.surface(binding.modelSurface)
        // A change the caller made a moment ago is drawn on the next frame; let it land first.
        return Screenshot.Target { Thread.sleep(80); shot.capture() }
    }
}
