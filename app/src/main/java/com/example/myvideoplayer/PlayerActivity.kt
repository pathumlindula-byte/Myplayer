package com.example.myvideoplayer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var centerSeekPanel: LinearLayout
    private lateinit var seekBarPanel: LinearLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var tvCustomSubtitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var videoSeekBar: SeekBar

    private lateinit var btnOpenVideo: Button
    private lateinit var btnRewind10: Button
    private lateinit var btnPlayPause: Button
    private lateinit var btnForward10: Button
    private lateinit var btnZoom: Button
    private lateinit var btnAspect: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnRotate: Button
    private lateinit var btnAddSub: Button
    private lateinit var btnSubSyncMinus: Button
    private lateinit var btnSubSyncPlus: Button
    private lateinit var btnSubSpeed: Button

    private var player: ExoPlayer? = null
    private var controlsVisible = true
    private var manualLandscape = false
    private var zoomToFill = false
    private var isUserSeeking = false

    private var downX = 0f
    private var downY = 0f
    private var touchSlop = 24

    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 1

    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
    )
    private var aspectIndex = 0

    private val subSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f)
    private var subSpeedIndex = 1
    private var subSyncOffsetMs: Long = 0L
    private var subtitles: List<SubtitleCue> = emptyList()

    private val subHandler = Handler(Looper.getMainLooper())

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { playVideo(it) }
    }

    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadSrtSubtitles(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        playerView = findViewById(R.id.playerView)
        centerSeekPanel = findViewById(R.id.centerSeekPanel)
        seekBarPanel = findViewById(R.id.seekBarPanel)
        controlsPanel = findViewById(R.id.controlsPanel)
        tvCustomSubtitle = findViewById(R.id.tvCustomSubtitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        videoSeekBar = findViewById(R.id.videoSeekBar)

        btnOpenVideo = findViewById(R.id.btnOpenVideo)
        btnRewind10 = findViewById(R.id.btnRewind10)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward10 = findViewById(R.id.btnForward10)
        btnZoom = findViewById(R.id.btnZoom)
        btnAspect = findViewById(R.id.btnAspect)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnRotate = findViewById(R.id.btnRotate)
        btnAddSub = findViewById(R.id.btnAddSub)
        btnSubSyncMinus = findViewById(R.id.btnSubSyncMinus)
        btnSubSyncPlus = findViewById(R.id.btnSubSyncPlus)
        btnSubSpeed = findViewById(R.id.btnSubSpeed)

        initPlayer()
        applyImmersiveMode()
        setupListeners()
        startProgressAndSubtitleLoop()
    }

    private fun updatePlayPauseLabel() {
        val playing = player?.isPlaying == true
        btnPlayPause.text = if (playing) "Pause" else "Play"
    }

    private fun isPointInsideView(view: View, rawX: Int, rawY: Int): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        return rect.contains(rawX, rawY)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val movedX = abs(ev.x - downX)
                val movedY = abs(ev.y - downY)
                val isTap = movedX <= touchSlop && movedY <= touchSlop

                val x = ev.rawX.toInt()
                val y = ev.rawY.toInt()

                val hitInteractiveControl =
                    isPointInsideView(controlsPanel, x, y) ||
                        isPointInsideView(seekBarPanel, x, y) ||
                        isPointInsideView(btnRewind10, x, y) ||
                        isPointInsideView(btnPlayPause, x, y) ||
                        isPointInsideView(btnForward10, x, y)

                if (isTap && !hitInteractiveControl) {
                    toggleControls()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun initPlayer() {
        if (player != null) return
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseLabel()
                    }
                })
            }
        updatePlayPauseLabel()
    }

    private fun setZoomToFill(enabled: Boolean) {
        zoomToFill = enabled
        if (enabled) {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            btnZoom.text = "Zoom: Fill"
            btnAspect.text = "Aspect: Zoom"
            aspectIndex = 2
            Toast.makeText(this, "Zoom to Fill", Toast.LENGTH_SHORT).show()
        } else {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            btnZoom.text = "Zoom: Fit"
            btnAspect.text = "Aspect: Fit"
            aspectIndex = 0
            Toast.makeText(this, "Zoom: Fit", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playVideo(uri: Uri) {
        val exo = player ?: return
        setZoomToFill(false)
        videoSeekBar.progress = 0
        tvCurrentTime.text = "00:00"
        tvTotalTime.text = "00:00"
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
        manualLandscape = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        applyImmersiveMode()
        updatePlayPauseLabel()
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        val target = if (duration > 0L) {
            (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        } else {
            (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        }
        exo.seekTo(target)
        updateSeekBarUi(target, duration)
        val secText = if (deltaMs < 0) "${deltaMs / 1000L}s" else "+${deltaMs / 1000L}s"
        Toast.makeText(this, secText, Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun updateSeekBarUi(currentPos: Long, duration: Long) {
        if (duration > 0L) {
            tvTotalTime.text = formatTime(duration)
            if (!isUserSeeking) {
                val progress = ((currentPos.toDouble() / duration.toDouble()) * 1000.0)
                    .toInt()
                    .coerceIn(0, 1000)
                videoSeekBar.progress = progress
                tvCurrentTime.text = formatTime(currentPos)
            }
        } else {
            tvTotalTime.text = "00:00"
            if (!isUserSeeking) {
                tvCurrentTime.text = formatTime(currentPos)
                videoSeekBar.progress = 0
            }
        }
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        val vis = if (controlsVisible) View.VISIBLE else View.GONE
        controlsPanel.visibility = vis
        seekBarPanel.visibility = vis
        centerSeekPanel.visibility = vis
        if (!controlsVisible) {
            applyImmersiveMode()
        }
    }

    private fun setupListeners() {
        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = player?.duration ?: 0L
                if (fromUser && duration > 0L) {
                    val previewMs = ((progress / 1000.0) * duration).toLong()
                    tvCurrentTime.text = formatTime(previewMs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val exo = player
                val duration = exo?.duration ?: 0L
                val progress = seekBar?.progress ?: 0
                if (exo != null && duration > 0L) {
                    val targetMs = ((progress / 1000.0) * duration).toLong()
                    exo.seekTo(targetMs)
                    tvCurrentTime.text = formatTime(targetMs)
                }
                isUserSeeking = false
            }
        })

        btnOpenVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        btnRewind10.setOnClickListener {
            seekBy(-10_000L)
        }

        btnPlayPause.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            if (exo.isPlaying) {
                exo.pause()
            } else {
                exo.play()
            }
            updatePlayPauseLabel()
        }

        btnForward10.setOnClickListener {
            seekBy(10_000L)
        }

        btnZoom.setOnClickListener {
            setZoomToFill(!zoomToFill)
        }

        btnAspect.setOnClickListener {
            aspectIndex = (aspectIndex + 1) % aspectModes.size
            val (mode, label) = aspectModes[aspectIndex]
            playerView.resizeMode = mode
            zoomToFill = (mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            btnZoom.text = if (zoomToFill) "Zoom: Fill" else "Zoom: Fit"
            btnAspect.text = "Aspect: $label"
        }

        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val spd = speeds[speedIndex]
            player?.playbackParameters = PlaybackParameters(spd)
            btnSpeed.text = "Speed: ${spd}x"
        }

        btnRotate.setOnClickListener {
            val currentlyLandscape = manualLandscape ||
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (currentlyLandscape) {
                manualLandscape = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Toast.makeText(this, "Portrait", Toast.LENGTH_SHORT).show()
            } else {
                manualLandscape = true
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Toast.makeText(this, "Landscape", Toast.LENGTH_SHORT).show()
            }
            applyImmersiveMode()
        }

        btnAddSub.setOnClickListener {
            pickSubtitleLauncher.launch("*/*")
        }

        btnSubSyncMinus.setOnClickListener {
            subSyncOffsetMs -= 500L
            Toast.makeText(this, "Sub Sync: ${subSyncOffsetMs}ms", Toast.LENGTH_SHORT).show()
        }

        btnSubSyncPlus.setOnClickListener {
            subSyncOffsetMs += 500L
            Toast.makeText(this, "Sub Sync: ${subSyncOffsetMs}ms", Toast.LENGTH_SHORT).show()
        }

        btnSubSpeed.setOnClickListener {
            subSpeedIndex = (subSpeedIndex + 1) % subSpeeds.size
            val subSpd = subSpeeds[subSpeedIndex]
            btnSubSpeed.text = "SubSpd: ${subSpd}x"
        }
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun loadSrtSubtitles(uri: Uri) {
        try {
            val lines = mutableListOf<String>()
            contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        lines.add(line)
                        line = reader.readLine()
                    }
                }
            }
            subtitles = parseSrt(lines)
            subSyncOffsetMs = 0L
            Toast.makeText(this, "Loaded ${subtitles.size} subs", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load SRT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseSrt(lines: List<String>): List<SubtitleCue> {
        val result = mutableListOf<SubtitleCue>()
        var i = 0
        val timeRegex = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")

        while (i < lines.size) {
            val line = lines[i].trim()
            val match = timeRegex.find(line)
            if (match != null) {
                val startMs = toMs(
                    match.groupValues[1].toLong(),
                    match.groupValues[2].toLong(),
                    match.groupValues[3].toLong(),
                    match.groupValues[4].toLong()
                )
                val endMs = toMs(
                    match.groupValues[5].toLong(),
                    match.groupValues[6].toLong(),
                    match.groupValues[7].toLong(),
                    match.groupValues[8].toLong()
                )
                i++
                val textBuilder = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(lines[i].trim())
                    i++
                }
                val cleanText = textBuilder.toString().replace(Regex("<[^>]*>"), "")
                if (cleanText.isNotEmpty()) {
                    result.add(SubtitleCue(startMs, endMs, cleanText))
                }
            }
            i++
        }
        return result
    }

    private fun toMs(h: Long, m: Long, s: Long, ms: Long): Long =
        ((h * 3600L) + (m * 60L) + s) * 1000L + ms

    private fun startProgressAndSubtitleLoop() {
        subHandler.post(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val exo = player
                if (exo != null) {
                    updateSeekBarUi(exo.currentPosition, exo.duration)
                    updatePlayPauseLabel()
                    if (subtitles.isNotEmpty() && exo.isPlaying) {
                        val subSpd = subSpeeds[subSpeedIndex].toDouble()
                        val effectivePos = ((exo.currentPosition * subSpd) + subSyncOffsetMs).toLong()
                        val activeCue = subtitles.firstOrNull { effectivePos in it.startMs..it.endMs }
                        if (activeCue != null) {
                            tvCustomSubtitle.text = activeCue.text
                            tvCustomSubtitle.visibility = View.VISIBLE
                        } else {
                            tvCustomSubtitle.visibility = View.GONE
                        }
                    } else if (subtitles.isEmpty()) {
                        tvCustomSubtitle.visibility = View.GONE
                    }
                }
                subHandler.postDelayed(this, 200L)
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        subHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
