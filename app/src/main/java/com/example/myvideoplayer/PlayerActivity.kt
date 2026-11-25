package com.example.myvideoplayer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.InputStreamReader

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controlsPanel: LinearLayout
    private lateinit var tvCustomSubtitle: TextView

    private lateinit var btnOpenVideo: Button
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

        playerView = findViewById(R.id.playerView)
        controlsPanel = findViewById(R.id.controlsPanel)
        tvCustomSubtitle = findViewById(R.id.tvCustomSubtitle)

        btnOpenVideo = findViewById(R.id.btnOpenVideo)
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
        startSubtitleLoop()
    }

    private fun initPlayer() {
        if (player != null) return
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory)
            .build()
            .also { exo ->
                playerView.player = exo
            }
    }

    private fun playVideo(uri: Uri) {
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
        manualLandscape = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        applyImmersiveMode()
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        controlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        if (!controlsVisible) {
            applyImmersiveMode()
        }
    }

    private fun setupListeners() {
        val touchListener = View.OnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                toggleControls()
                v.performClick()
            }
            true
        }

        playerView.setOnTouchListener(touchListener)
        playerView.videoSurfaceView?.setOnTouchListener(touchListener)
        findViewById<View>(R.id.rootLayout).setOnTouchListener(touchListener)

        btnOpenVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        btnAspect.setOnClickListener {
            aspectIndex = (aspectIndex + 1) % aspectModes.size
            val (mode, label) = aspectModes[aspectIndex]
            playerView.resizeMode = mode
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

    private fun startSubtitleLoop() {
        subHandler.post(object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val exo = player
                if (exo != null && subtitles.isNotEmpty() && exo.isPlaying) {
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
