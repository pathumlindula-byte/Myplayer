package com.example.myvideoplayer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: View
    private lateinit var emptyVideoPrompt: View
    private lateinit var btnOpenVideo: TextView
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvSubtitleOverlay: TextView
    private lateinit var tvSubtitleStatus: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnSubtitle: TextView
    private lateinit var btnRotate: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnUnlock: ImageButton
    private lateinit var btnAspectRatio: TextView
    private lateinit var btnZoom: TextView

    private var player: ExoPlayer? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var isLocked = false
    private var hasVideoLoaded = false
    private var lastSubtitleText: String? = null

    private val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 1

    private val aspectModes = arrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "FIT",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "STRETCH",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "CROP"
    )
    private var aspectIndex = 0

    private val zoomScales = floatArrayOf(1.0f, 1.15f, 1.35f)
    private var zoomIndex = 0

    private val subtitles = mutableListOf<SubtitleCue>()

    private val autoHideRunnable = Runnable {
        if (!isLocked && hasVideoLoaded && controlsOverlay.visibility == View.VISIBLE && player?.isPlaying == true) {
            hideControlsOverlay()
        }
    }

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

    private val requestMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        launchVideoPickerSafe()
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val currentPlayer = player
            if (currentPlayer != null) {
                val duration = currentPlayer.duration.coerceAtLeast(0L)
                val position = currentPlayer.currentPosition.coerceAtLeast(0L)

                if (!isSeeking && duration > 0L) {
                    val progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
                    seekBar.progress = progress
                }

                tvCurrentTime.text = formatDuration(position)
                tvTotalTime.text = formatDuration(duration)
                updateSubtitleAt(position)
            }
            uiHandler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ZenoPlayer)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        bindViews()
        setupPlayer()
        setupListeners()

        val incomingUri = intent?.data
        if (incomingUri != null && intent?.action == Intent.ACTION_VIEW) {
            playVideo(incomingUri)
        } else {
            emptyVideoPrompt.visibility = View.VISIBLE
            controlsOverlay.visibility = View.GONE
        }
    }

    private fun checkPermissionsAndPickVideo() {
        val neededPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestMediaPermissionLauncher.launch(neededPermissions)
    }

    private fun launchVideoPickerSafe() {
        try {
            pickVideoLauncher.launch("video/*")
        } catch (_: Exception) {
            Toast.makeText(this, "Video picker open කරන්න බැරි වුණා", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        emptyVideoPrompt = findViewById(R.id.emptyVideoPrompt)
        btnOpenVideo = findViewById(R.id.btnOpenVideo)
        tvVideoTitle = findViewById(R.id.tvVideoTitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvSubtitleOverlay = findViewById(R.id.tvSubtitleOverlay)
        tvSubtitleStatus = findViewById(R.id.tvSubtitleStatus)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnRewind = findViewById(R.id.btnRewind)
        btnForward = findViewById(R.id.btnForward)
        btnBack = findViewById(R.id.btnBack)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnSubtitle = findViewById(R.id.btnSubtitle)
        btnRotate = findViewById(R.id.btnRotate)
        btnLock = findViewById(R.id.btnLock)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnAspectRatio = findViewById(R.id.btnAspectRatio)
        btnZoom = findViewById(R.id.btnZoom)
    }

    private fun setupPlayer() {
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val universalLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 6000, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(universalLoadControl)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        btnPlayPause.setImageResource(
                            if (isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                        btnPlayPause.animate()
                            .scaleX(1.12f)
                            .scaleY(1.12f)
                            .setDuration(110L)
                            .withEndAction {
                                btnPlayPause.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setInterpolator(OvershootInterpolator(2.2f))
                                    .setDuration(160L)
                                    .start()
                            }
                            .start()
                        if (isPlaying) {
                            resetAutoHideTimer()
                        } else {
                            cancelAutoHideTimer()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            this@PlayerActivity,
                            "මේ video format එක play කරන්න බැහැ",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
    }

    private fun resetAutoHideTimer() {
        cancelAutoHideTimer()
        uiHandler.postDelayed(autoHideRunnable, 4000L)
    }

    private fun cancelAutoHideTimer() {
        uiHandler.removeCallbacks(autoHideRunnable)
    }

    private fun animateGlassClick(view: View, action: () -> Unit) {
        resetAutoHideTimer()
        view.animate().cancel()
        view.alpha = 1f
        view.animate()
            .scaleX(0.86f)
            .scaleY(0.86f)
            .alpha(0.75f)
            .setDuration(75L)
            .withEndAction {
                view.alpha = 1f
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .setDuration(180L)
                    .start()
                action()
            }
            .start()
    }

    private fun showControlsOverlay() {
        if (!hasVideoLoaded) return
        controlsOverlay.animate().cancel()
        controlsOverlay.alpha = 0f
        controlsOverlay.scaleX = 0.98f
        controlsOverlay.scaleY = 0.98f
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(170L)
            .start()
        resetAutoHideTimer()
    }

    private fun hideControlsOverlay() {
        cancelAutoHideTimer()
        controlsOverlay.animate().cancel()
        controlsOverlay.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                controlsOverlay.visibility = View.GONE
                controlsOverlay.alpha = 1f
                controlsOverlay.scaleX = 1f
                controlsOverlay.scaleY = 1f
            }
            .start()
    }

    private fun toggleControlsOverlay() {
        if (!hasVideoLoaded) return
        if (controlsOverlay.visibility == View.VISIBLE) {
            hideControlsOverlay()
        } else {
            showControlsOverlay()
        }
    }

    private fun setupListeners() {
        btnOpenVideo.setOnClickListener {
            animateGlassClick(it) {
                checkPermissionsAndPickVideo()
            }
        }

        playerView.setOnClickListener {
            if (!isLocked) {
                toggleControlsOverlay()
            }
        }

        controlsOverlay.setOnClickListener {
            if (!isLocked) {
                hideControlsOverlay()
            }
        }

        btnBack.setOnClickListener {
            animateGlassClick(it) {
                checkPermissionsAndPickVideo()
            }
        }

        btnPlayPause.setOnClickListener {
            animateGlassClick(it) {
                player?.let { exo ->
                    if (exo.isPlaying) exo.pause() else exo.play()
                }
            }
        }

        btnRewind.setOnClickListener {
            animateGlassClick(it) {
                player?.let { exo ->
                    exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                }
            }
        }

        btnForward.setOnClickListener {
            animateGlassClick(it) {
                player?.let { exo ->
                    val maxPos = exo.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    exo.seekTo((exo.currentPosition + 10_000L).coerceAtMost(maxPos))
                }
            }
        }

        btnSpeed.setOnClickListener {
            animateGlassClick(it) {
                speedIndex = (speedIndex + 1) % speeds.size
                val newSpeed = speeds[speedIndex]
                player?.setPlaybackSpeed(newSpeed)
                btnSpeed.text = String.format(Locale.US, "%.2gx", newSpeed)
            }
        }

        btnRotate.setOnClickListener {
            animateGlassClick(it) {
                requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
        }

        btnLock.setOnClickListener {
            animateGlassClick(it) {
                isLocked = true
                cancelAutoHideTimer()
                controlsOverlay.visibility = View.GONE
                btnUnlock.alpha = 0f
                btnUnlock.scaleX = 0.7f
                btnUnlock.scaleY = 0.7f
                btnUnlock.visibility = View.VISIBLE
                btnUnlock.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .setDuration(180L)
                    .start()
            }
        }

        btnUnlock.setOnClickListener {
            animateGlassClick(it) {
                isLocked = false
                btnUnlock.visibility = View.GONE
                showControlsOverlay()
            }
        }

        btnAspectRatio.setOnClickListener {
            animateGlassClick(it) {
                aspectIndex = (aspectIndex + 1) % aspectModes.size
                val (mode, label) = aspectModes[aspectIndex]
                playerView.resizeMode = mode
                btnAspectRatio.text = label
            }
        }

        btnZoom.setOnClickListener {
            animateGlassClick(it) {
                zoomIndex = (zoomIndex + 1) % zoomScales.size
                val scale = zoomScales[zoomIndex]
                playerView.videoSurfaceView?.let { surface ->
                    surface.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(170L)
                        .start()
                }
                btnZoom.text = "ZOOM ${(scale * 100).toInt()}%"
            }
        }

        btnSubtitle.setOnClickListener {
            animateGlassClick(it) {
                try {
                    pickSubtitleLauncher.launch("*/*")
                } catch (_: Exception) {
                    Toast.makeText(this, "Subtitle picker open කරන්න බැරි වුණා", Toast.LENGTH_SHORT).show()
                }
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetAutoHideTimer()
                    val duration = player?.duration ?: 0L
                    if (duration > 0L) {
                        tvCurrentTime.text = formatDuration((duration * progress) / 1000L)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                cancelAutoHideTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = player?.duration ?: 0L
                val targetProgress = seekBar?.progress ?: 0
                if (duration > 0L) {
                    player?.seekTo((duration * targetProgress) / 1000L)
                }
                isSeeking = false
                resetAutoHideTimer()
            }
        })
    }

    private fun playVideo(uri: Uri) {
        try {
            hasVideoLoaded = true
            emptyVideoPrompt.visibility = View.GONE
            controlsOverlay.visibility = View.VISIBLE
            resetAutoHideTimer()
            tvVideoTitle.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Zeno Player"
            player?.apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Video එක load කරන්න බැරි වුණා", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSrtSubtitles(uri: Uri) {
        try {
            subtitles.clear()
            lastSubtitleText = null
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    var currentStart: Long? = null
                    var currentEnd: Long? = null
                    val textBuilder = StringBuilder()

                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (trimmed.contains("-->")) {
                            if (currentStart != null && currentEnd != null && textBuilder.isNotEmpty()) {
                                subtitles.add(SubtitleCue(currentStart!!, currentEnd!!, textBuilder.toString().trim()))
                                textBuilder.clear()
                            }
                            val parts = trimmed.split("-->")
                            if (parts.size == 2) {
                                currentStart = parseSrtTime(parts[0].trim())
                                currentEnd = parseSrtTime(parts[1].trim().substringBefore(" "))
                            }
                        } else if (trimmed.isEmpty()) {
                            if (currentStart != null && currentEnd != null && textBuilder.isNotEmpty()) {
                                subtitles.add(SubtitleCue(currentStart!!, currentEnd!!, textBuilder.toString().trim()))
                                textBuilder.clear()
                            }
                            currentStart = null
                            currentEnd = null
                        } else if (currentStart != null && trimmed.toIntOrNull() == null) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            textBuilder.append(trimmed)
                        }
                    }
                    if (currentStart != null && currentEnd != null && textBuilder.isNotEmpty()) {
                        subtitles.add(SubtitleCue(currentStart!!, currentEnd!!, textBuilder.toString().trim()))
                    }
                }
            }
            tvSubtitleStatus.text = "SRT ON (${subtitles.size})"
            Toast.makeText(this, "Subtitles loaded", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            tvSubtitleStatus.text = "SRT Err"
            Toast.makeText(this, "Invalid SRT file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseSrtTime(timeStr: String): Long {
        val clean = timeStr.replace(',', '.')
        val hms = clean.split(":")
        if (hms.size != 3) return 0L
        val hours = hms[0].toLongOrNull() ?: 0L
        val minutes = hms[1].toLongOrNull() ?: 0L
        val secParts = hms[2].split(".")
        val seconds = secParts[0].toLongOrNull() ?: 0L
        val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return TimeUnit.HOURS.toMillis(hours) +
            TimeUnit.MINUTES.toMillis(minutes) +
            TimeUnit.SECONDS.toMillis(seconds) +
            millis
    }

    private fun updateSubtitleAt(positionMs: Long) {
        if (subtitles.isEmpty()) {
            tvSubtitleOverlay.visibility = View.GONE
            lastSubtitleText = null
            return
        }
        val active = subtitles.firstOrNull { positionMs in it.startMs..it.endMs }
        if (active != null) {
            if (active.text != lastSubtitleText) {
                lastSubtitleText = active.text
                tvSubtitleOverlay.text = active.text
                tvSubtitleOverlay.alpha = 0.2f
                tvSubtitleOverlay.scaleY = 0.9f
                tvSubtitleOverlay.visibility = View.VISIBLE
                tvSubtitleOverlay.animate()
                    .alpha(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start()
            }
        } else if (tvSubtitleOverlay.visibility == View.VISIBLE) {
            lastSubtitleText = null
            tvSubtitleOverlay.visibility = View.GONE
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(progressRunnable)
    }

    override fun onStop() {
        super.onStop()
        cancelAutoHideTimer()
        uiHandler.removeCallbacks(progressRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoHideTimer()
        uiHandler.removeCallbacks(progressRunnable)
        player?.release()
        player = null
    }
}
