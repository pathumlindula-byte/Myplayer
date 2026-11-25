package com.example.myvideoplayer

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

class PlayerActivity : AppCompatActivity() {

    private lateinit var rootLayout: View
    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var videoSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvCustomSubtitle: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var logoOverlay: LinearLayout
    private var centerSeekPanel: View? = null
    private var topActionRow: View? = null
    private var subActionRow: View? = null
    private var seekRow: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var controlsVisible = true
    private var isLandscape = false

    private var speedIndex = 0
    private val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)

    private var zoomIndex = 0
    private val zoomScales = listOf(1.0f, 1.25f, 1.5f, 1.75f)

    private var aspectIndex = 0
    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop"
    )

    private val subtitleCues = mutableListOf<SubtitleCue>()
    private var subtitleOffsetMs: Long = 0L
    private var subStepIndex = 0
    private val subStepsMs = listOf(500L, 1000L, 250L)

    private val hideControlsRunnable = Runnable {
        if (::player.isInitialized && player.isPlaying && !isSeeking) {
            setControlsVisibility(visible = false, animate = true)
        }
    }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { playVideo(it) }
        }

    private val pickSubtitleLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadSubtitleFile(it) }
        }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                val current = player.currentPosition.takeIf { it > 0 } ?: 0L

                if (!isSeeking) {
                    tvCurrentTime.text = formatTime(current)
                    tvTotalTime.text = formatTime(duration)

                    if (duration > 0) {
                        val progress = ((current * 1000L) / duration).toInt().coerceIn(0, 1000)
                        videoSeekBar.progress = progress
                    } else {
                        videoSeekBar.progress = 0
                    }
                }

                updateSubtitleAtPosition(current)
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        rootLayout = findViewById(R.id.rootLayout)
        playerView = findViewById(R.id.playerView)
        videoSeekBar = findViewById(R.id.videoSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvCustomSubtitle = findViewById(R.id.tvCustomSubtitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        logoOverlay = findViewById(R.id.logoOverlay)
        centerSeekPanel = findViewById(R.id.centerSeekPanel)
        topActionRow = findViewById(R.id.topActionRow)
        subActionRow = findViewById(R.id.subActionRow)
        seekRow = findViewById(R.id.seekRow)

        resetPlayerTransform()
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateSystemBarsForOrientation(isLandscape)

        setupPlayer()
        setupControls()
        setupTapToToggleControls()
    }

    private fun resetPlayerTransform() {
        playerView.rotation = 0f
        playerView.scaleX = zoomScales[zoomIndex]
        playerView.scaleY = zoomScales[zoomIndex]
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        playerView.useController = false
        playerView.resizeMode = aspectModes[aspectIndex].first

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.text = if (isPlaying) "⏸" else "▶"
                if (isPlaying) {
                    resetHideControlsTimer()
                } else {
                    handler.removeCallbacks(hideControlsRunnable)
                    setControlsVisibility(visible = true, animate = true)
                }
            }

            override fun onRenderedFirstFrame() {
                logoOverlay.animateIosHide()
            }
        })
    }

    private fun playVideo(uri: Uri) {
        logoOverlay.animateIosHide()
        val mediaItem = MediaItem.fromUri(uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        resetHideControlsTimer()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToToggleControls() {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }
            }
        )

        val touchToggleListener = View.OnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            true
        }

        rootLayout.setOnTouchListener(touchToggleListener)
        playerView.setOnTouchListener(touchToggleListener)
    }

    private fun toggleControls() {
        setControlsVisibility(visible = !controlsVisible, animate = true)
        if (controlsVisible && player.isPlaying) {
            resetHideControlsTimer()
        } else {
            handler.removeCallbacks(hideControlsRunnable)
        }
    }

    private fun setControlsVisibility(visible: Boolean, animate: Boolean) {
        controlsVisible = visible
        val targets = listOfNotNull<View>(
            centerSeekPanel,
            topActionRow,
            subActionRow,
            seekRow
        )

        targets.forEach { view ->
            if (visible) {
                if (animate) {
                    view.animateIosShow()
                } else {
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.visibility = View.VISIBLE
                }
            } else {
                if (animate) {
                    view.animateIosHide()
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3500L)
    }

    private fun toggleLandscapeOrientation() {
        isLandscape = !isLandscape
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        resetPlayerTransform()
        updateSystemBarsForOrientation(isLandscape)
        resetHideControlsTimer()
    }

    private fun updateSystemBarsForOrientation(landscape: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        resetPlayerTransform()
        updateSystemBarsForOrientation(isLandscape)
    }

    private fun setupControls() {
        val btnRewind10: Button = findViewById(R.id.btnRewind10)
        val btnForward10: Button = findViewById(R.id.btnForward10)
        val btnOpenVideo: Button = findViewById(R.id.btnOpenVideo)
        val btnZoom: Button = findViewById(R.id.btnZoom)
        val btnAspect: Button = findViewById(R.id.btnAspect)
        val btnSpeed: Button = findViewById(R.id.btnSpeed)
        val btnRotate: Button = findViewById(R.id.btnRotate)
        val btnAddSub: Button = findViewById(R.id.btnAddSub)
        val btnSubSyncMinus: Button = findViewById(R.id.btnSubSyncMinus)
        val btnSubSyncPlus: Button = findViewById(R.id.btnSubSyncPlus)
        val btnSubSpeed: Button = findViewById(R.id.btnSubSpeed)

        val allButtons = listOf<View>(
            btnRewind10,
            btnPlayPause,
            btnForward10,
            btnOpenVideo,
            btnZoom,
            btnAspect,
            btnSpeed,
            btnRotate,
            btnAddSub,
            btnSubSyncMinus,
            btnSubSyncPlus,
            btnSubSpeed
        )
        allButtons.forEach { it.applyGlassLightAnimation() }

        btnRewind10.setOnClickListener {
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
            resetHideControlsTimer()
        }

        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
                resetHideControlsTimer()
            }
        }

        btnForward10.setOnClickListener {
            val maxPos = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(maxPos))
            resetHideControlsTimer()
        }

        btnOpenVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        btnZoom.setOnClickListener {
            zoomIndex = (zoomIndex + 1) % zoomScales.size
            val scale = zoomScales[zoomIndex]
            playerView.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            Toast.makeText(this, "Zoom: ${scale}x", Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }

        btnAspect.setOnClickListener {
            aspectIndex = (aspectIndex + 1) % aspectModes.size
            val (mode, label) = aspectModes[aspectIndex]
            playerView.resizeMode = mode
            Toast.makeText(this, "Aspect: $label", Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }

        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val newSpeed = speeds[speedIndex]
            player.setPlaybackSpeed(newSpeed)
            Toast.makeText(this, "Speed: ${newSpeed}x", Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }

        btnRotate.setOnClickListener {
            toggleLandscapeOrientation()
            Toast.makeText(
                this,
                if (isLandscape) "Landscape" else "Portrait",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnAddSub.setOnClickListener {
            pickSubtitleLauncher.launch("*/*")
        }

        btnSubSyncMinus.setOnClickListener {
            val step = subStepsMs[subStepIndex]
            subtitleOffsetMs -= step
            updateSubtitleAtPosition(player.currentPosition.coerceAtLeast(0L))
            Toast.makeText(
                this,
                "Sub sync: ${formatOffset(subtitleOffsetMs)}",
                Toast.LENGTH_SHORT
            ).show()
            resetHideControlsTimer()
        }

        btnSubSyncPlus.setOnClickListener {
            val step = subStepsMs[subStepIndex]
            subtitleOffsetMs += step
            updateSubtitleAtPosition(player.currentPosition.coerceAtLeast(0L))
            Toast.makeText(
                this,
                "Sub sync: ${formatOffset(subtitleOffsetMs)}",
                Toast.LENGTH_SHORT
            ).show()
            resetHideControlsTimer()
        }

        btnSubSpeed.setOnClickListener {
            subStepIndex = (subStepIndex + 1) % subStepsMs.size
            val sec = subStepsMs[subStepIndex] / 1000.0
            Toast.makeText(this, "Sub step: ${sec}s", Toast.LENGTH_SHORT).show()
            resetHideControlsTimer()
        }

        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::player.isInitialized) {
                    val duration = player.duration.takeIf { it > 0 } ?: 0L
                    if (duration > 0) {
                        val targetMs = (duration * progress) / 1000L
                        player.seekTo(targetMs)
                        tvCurrentTime.text = formatTime(targetMs)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                if (player.isPlaying) {
                    resetHideControlsTimer()
                }
            }
        })
    }

    private fun loadSubtitleFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                val parsed = parseSrtLines(reader.readLines())
                subtitleCues.clear()
                subtitleCues.addAll(parsed)
                subtitleOffsetMs = 0L

                if (subtitleCues.isNotEmpty()) {
                    tvCustomSubtitle.text = "Subtitle loaded (${subtitleCues.size} lines)"
                    tvCustomSubtitle.animateIosShow()
                    Toast.makeText(
                        this,
                        "Loaded ${subtitleCues.size} subtitles",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    tvCustomSubtitle.text = "Subtitle file loaded"
                    tvCustomSubtitle.animateIosShow()
                    Toast.makeText(
                        this,
                        "Subtitle loaded (no SRT timestamps found)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to read subtitle file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseSrtLines(lines: List<String>): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        val timeRegex = Regex("""(\d{1,2}:\d{2}:\d{2}[,\.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,\.]\d{1,3})""")

        var i = 0
        while (i < lines.size) {
            val match = timeRegex.find(lines[i].trim())
            if (match != null) {
                val startMs = parseSrtTimestamp(match.groupValues[1])
                val endMs = parseSrtTimestamp(match.groupValues[2])
                val textLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    val cleanLine = lines[i].replace(Regex("<[^>]*>"), "").trim()
                    if (cleanLine.isNotEmpty()) {
                        textLines.add(cleanLine)
                    }
                    i++
                }
                if (startMs != null && endMs != null && textLines.isNotEmpty()) {
                    result.add(SubtitleCue(startMs, endMs, textLines.joinToString("\n")))
                }
            }
            i++
        }
        return result
    }

    private fun parseSrtTimestamp(raw: String): Long? {
        val normalized = raw.replace(',', '.').trim()
        val parts = normalized.split(":", ".")
        if (parts.size < 4) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        val millisStr = parts[3].padEnd(3, '0').take(3)
        val millis = millisStr.toLongOrNull() ?: return null
        return TimeUnit.HOURS.toMillis(hours) +
            TimeUnit.MINUTES.toMillis(minutes) +
            TimeUnit.SECONDS.toMillis(seconds) +
            millis
    }

    private fun updateSubtitleAtPosition(positionMs: Long) {
        if (subtitleCues.isEmpty()) return
        val adjustedMs = (positionMs + subtitleOffsetMs).coerceAtLeast(0L)
        val activeCue = subtitleCues.firstOrNull { adjustedMs in it.startMs..it.endMs }
        if (activeCue != null) {
            if (tvCustomSubtitle.visibility != View.VISIBLE) {
                tvCustomSubtitle.animateIosShow()
            }
            if (tvCustomSubtitle.text.toString() != activeCue.text) {
                tvCustomSubtitle.text = activeCue.text
            }
        } else if (tvCustomSubtitle.visibility == View.VISIBLE &&
            tvCustomSubtitle.text.toString() != "Subtitle loaded" &&
            !tvCustomSubtitle.text.toString().startsWith("Subtitle loaded (")
        ) {
            tvCustomSubtitle.animateIosHide()
        }
    }

    private fun formatOffset(offsetMs: Long): String {
        val sign = if (offsetMs >= 0) "+" else "-"
        val sec = abs(offsetMs) / 1000.0
        return String.format(Locale.US, "%s%.2fs", sign, sec)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.applyGlassLightAnimation() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.alpha = 1f
                    v.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .alpha(1f)
                        .setDuration(85)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.96f)
                        .setDuration(260)
                        .setInterpolator(OvershootInterpolator(2.0f))
                        .withEndAction { v.alpha = 1f }
                        .start()
                    false
                }
                else -> false
            }
        }
    }

    private fun View.animateIosShow() {
        animate().cancel()
        alpha = 0f
        scaleX = 0.92f
        scaleY = 0.92f
        translationY = 18f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.35f))
            .start()
    }

    private fun View.animateIosHide() {
        if (visibility != View.VISIBLE) return
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .translationY(12f)
            .setDuration(180)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                translationY = 0f
            }
            .start()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onStart() {
        super.onStart()
        handler.post(updateProgressRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}
