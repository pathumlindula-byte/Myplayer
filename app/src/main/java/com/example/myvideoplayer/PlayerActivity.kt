package com.example.myvideoplayer

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var player: ExoPlayer
    private lateinit var videoSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvCustomSubtitle: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var logoOverlay: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var speedIndex = 0
    private val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { playVideo(it) }
        }

    private val pickSubtitleLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                tvCustomSubtitle.animateIosShow()
                tvCustomSubtitle.text = "Subtitle loaded"
            }
        }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && !isSeeking) {
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                val current = player.currentPosition.takeIf { it > 0 } ?: 0L

                tvCurrentTime.text = formatTime(current)
                tvTotalTime.text = formatTime(duration)

                if (duration > 0) {
                    val progress = ((current * 1000L) / duration).toInt().coerceIn(0, 1000)
                    videoSeekBar.progress = progress
                } else {
                    videoSeekBar.progress = 0
                }
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        videoSeekBar = findViewById(R.id.videoSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvCustomSubtitle = findViewById(R.id.tvCustomSubtitle)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        logoOverlay = findViewById(R.id.logoOverlay)

        setupPlayer()
        setupControls()
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.text = if (isPlaying) "⏸" else "▶"
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
        allButtons.forEach { it.applyIosSpringPress() }

        btnRewind10.setOnClickListener {
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
        }

        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }

        btnForward10.setOnClickListener {
            val maxPos = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(maxPos))
        }

        btnOpenVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        btnZoom.setOnClickListener {
            // Zoom / resize toggle
        }

        btnAspect.setOnClickListener {
            // Aspect ratio toggle
        }

        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            player.setPlaybackSpeed(speeds[speedIndex])
        }

        btnRotate.setOnClickListener {
            playerView.animate()
                .rotation((playerView.rotation + 90f) % 360f)
                .setDuration(240)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        btnAddSub.setOnClickListener {
            pickSubtitleLauncher.launch("*/*")
        }

        btnSubSyncMinus.setOnClickListener {
            // Subtitle delay minus
        }

        btnSubSyncPlus.setOnClickListener {
            // Subtitle delay plus
        }

        btnSubSpeed.setOnClickListener {
            // Subtitle speed toggle
        }

        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::player.isInitialized) {
                    val duration = player.duration.takeIf { it > 0 } ?: 0L
                    if (duration > 0) {
                        player.seekTo((duration * progress) / 1000L)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.applyIosSpringPress() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.90f)
                        .scaleY(0.90f)
                        .setDuration(90)
                        .setInterpolator(null)
                        .start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(260)
                        .setInterpolator(OvershootInterpolator(2.2f))
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
        scaleX = 0.94f
        scaleY = 0.94f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    private fun View.animateIosHide() {
        if (visibility != View.VISIBLE) return
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(180)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
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
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}
