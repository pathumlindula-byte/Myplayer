package com.example.myvideoplayer

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    private lateinit var playerView: PlayerView
    private lateinit var tvSubtitleOverlay: TextView
    private lateinit var emptyVideoPrompt: View
    private lateinit var btnOpenVideo: ImageButton
    private lateinit var gestureOverlay: View
    private lateinit var tvCenterFeedback: TextView
    private lateinit var controlsOverlay: View
    private lateinit var controlsTopGroup: View
    private lateinit var controlsBottomGroup: View
    private lateinit var btnUnlock: ImageButton

    private lateinit var btnBack: ImageButton
    private lateinit var tvVideoTitle: TextView
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnSubtitle: ImageButton
    private lateinit var btnMoreOptions: ImageButton
    private lateinit var btnDecoder: TextView
    private lateinit var btnDecoderBadge: TextView

    private lateinit var btnEqualizer: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnNextTop: ImageButton
    private lateinit var btnQuickSkip: ImageButton

    private lateinit var tvCurrentTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTotalTime: TextView

    private lateinit var btnLock: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnZoom: ImageButton
    private lateinit var btnFullScale: ImageButton
    private lateinit var btnFullscreenResize: ImageButton

    private var player: ExoPlayer? = null
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isSeeking = false
    private var isControlsLocked = false
    private var areControlsVisible = true

    private var playlistUris: List<Uri> = emptyList()
    private var currentIndex: Int = -1

    private var speedIndex = 2 // 1.0x default
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private var resizeModeIndex = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )
    private val resizeModeNames = arrayOf("Fit", "Fill", "Zoom", "Fixed W", "Fixed H")

    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var subtitlesEnabled = true

    // Swipe gesture state
    private var isSwipeDragging = false
    private var swipeSide: Int = 0 // -1 left (brightness), +1 right (volume)
    private var swipeStartY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 0

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            playlistUris = listOf(uri)
            currentIndex = 0
            playAtIndex(0)
        }
    }

    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            loadSubtitleFromUri(uri)
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            updateSubtitleOverlay()
            mainHandler.postDelayed(this, 250L)
        }
    }

    private val hideControlsRunnable = Runnable {
        if (!isControlsLocked && player?.isPlaying == true) {
            setControlsVisible(false)
        }
    }

    private val hideFeedbackRunnable = Runnable {
        tvCenterFeedback.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startBrightness = getCurrentBrightness()

        bindViews()
        setupListeners()
        initPlayer()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isControlsLocked) {
                    showCenterFeedback("Locked")
                } else {
                    finish()
                }
            }
        })

        val initialUri = intent?.data
        if (initialUri != null) {
            buildFolderPlaylist(initialUri)
            if (currentIndex >= 0 && playlistUris.isNotEmpty()) {
                playAtIndex(currentIndex)
            } else {
                playlistUris = listOf(initialUri)
                currentIndex = 0
                playAtIndex(0)
            }
        } else {
            showEmptyPrompt(true)
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        tvSubtitleOverlay = findViewById(R.id.tvSubtitleOverlay)
        emptyVideoPrompt = findViewById(R.id.emptyVideoPrompt)
        btnOpenVideo = findViewById(R.id.btnOpenVideo)
        gestureOverlay = findViewById(R.id.gestureOverlay)
        tvCenterFeedback = findViewById(R.id.tvCenterFeedback)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        controlsTopGroup = findViewById(R.id.controlsTopGroup)
        controlsBottomGroup = findViewById(R.id.controlsBottomGroup)
        btnUnlock = findViewById(R.id.btnUnlock)

        btnBack = findViewById(R.id.btnBack)
        tvVideoTitle = findViewById(R.id.tvVideoTitle)
        tvVideoTitle.isSelected = true
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnSubtitle = findViewById(R.id.btnSubtitle)
        btnMoreOptions = findViewById(R.id.btnMoreOptions)
        btnDecoder = findViewById(R.id.btnDecoder)
        btnDecoderBadge = findViewById(R.id.btnDecoderBadge)

        btnEqualizer = findViewById(R.id.btnEqualizer)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnAspectRatio = findViewById(R.id.btnAspectRatio)
        btnRotate = findViewById(R.id.btnRotate)
        btnNextTop = findViewById(R.id.btnNextTop)
        btnQuickSkip = findViewById(R.id.btnQuickSkip)

        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        seekBar = findViewById(R.id.seekBar)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        btnLock = findViewById(R.id.btnLock)
        btnRewind = findViewById(R.id.btnRewind)
        btnPrev = findViewById(R.id.btnPrev)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNext = findViewById(R.id.btnNext)
        btnForward = findViewById(R.id.btnForward)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnZoom = findViewById(R.id.btnZoom)
        btnFullScale = findViewById(R.id.btnFullScale)
        btnFullscreenResize = findViewById(R.id.btnFullscreenResize)
    }

    private fun initPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                if (isPlaying) {
                    scheduleHideControls()
                } else {
                    mainHandler.removeCallbacks(hideControlsRunnable)
                    if (!isControlsLocked) setControlsVisible(true)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgress()
                if (playbackState == Player.STATE_ENDED && currentIndex + 1 < playlistUris.size) {
                    playAtIndex(currentIndex + 1)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Video play කරන්න බැරි වුණා: ${error.errorCodeName}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun buildFolderPlaylist(targetUri: Uri) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_ADDED
        )

        val entries = mutableListOf<Pair<String?, Uri>>()
        var targetFolder: String? = null

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val parentPath = if (!path.isNullOrEmpty()) File(path).parentFile?.absolutePath else null
                    entries.add(parentPath to uri)
                    if (uri == targetUri) {
                        targetFolder = parentPath
                    }
                }
            }
        } catch (_: Exception) {
        }

        val filtered = if (targetFolder != null) {
            entries.filter { it.first == targetFolder }.map { it.second }
        } else {
            emptyList()
        }

        if (filtered.isNotEmpty()) {
            playlistUris = filtered
            currentIndex = filtered.indexOf(targetUri).coerceAtLeast(0)
        } else {
            playlistUris = listOf(targetUri)
            currentIndex = 0
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnOpenVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isControlsLocked) {
                    btnUnlock.visibility = if (btnUnlock.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    setControlsVisible(!areControlsVisible)
                    if (areControlsVisible) scheduleHideControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isControlsLocked) return true
                val width = gestureOverlay.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                if (e.x < width * 0.4f) {
                    seekBy(-10_000L, "-10s")
                } else if (e.x > width * 0.6f) {
                    seekBy(10_000L, "+10s")
                } else {
                    togglePlayPause()
                }
                return true
            }
        })

        gestureOverlay.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            if (isControlsLocked) {
                if (event.action == MotionEvent.ACTION_UP) v.performClick()
                return@setOnTouchListener true
            }

            val width = v.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val height = v.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isSwipeDragging = false
                    swipeStartY = event.y
                    swipeSide = if (event.x < width * 0.5f) -1 else 1
                    startBrightness = getCurrentBrightness()
                    startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = swipeStartY - event.y
                    if (!isSwipeDragging && abs(dy) > 28f) {
                        isSwipeDragging = true
                        mainHandler.removeCallbacks(hideControlsRunnable)
                    }
                    if (isSwipeDragging) {
                        val deltaFraction = dy / (height * 0.65f)
                        if (swipeSide == -1) {
                            val newBright = (startBrightness + deltaFraction).coerceIn(0.05f, 1.0f)
                            setWindowBrightness(newBright)
                            showCenterFeedback("Brightness ${(newBright * 100).toInt()}%")
                        } else {
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val targetVol = (startVolume + (deltaFraction * maxVol).toInt()).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            val percent = ((targetVol * 100f) / maxVol).toInt()
                            showCenterFeedback("Volume $percent%")
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isSwipeDragging = false
                    if (areControlsVisible) scheduleHideControls()
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    isSwipeDragging = false
                }
            }
            true
        }

        btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleHideControls()
        }

        btnRewind.setOnClickListener {
            seekBy(-10_000L, "-10s")
            scheduleHideControls()
        }

        btnForward.setOnClickListener {
            seekBy(10_000L, "+10s")
            scheduleHideControls()
        }

        btnQuickSkip.setOnClickListener {
            seekBy(85_000L, "+85s")
            scheduleHideControls()
        }

        btnPrev.setOnClickListener {
            if (playlistUris.isNotEmpty() && currentIndex > 0) {
                playAtIndex(currentIndex - 1)
            } else {
                player?.seekTo(0L)
                showCenterFeedback("Restart")
            }
            scheduleHideControls()
        }

        val nextAction = View.OnClickListener {
            if (playlistUris.isNotEmpty() && currentIndex + 1 < playlistUris.size) {
                playAtIndex(currentIndex + 1)
            } else {
                showCenterFeedback("Last Video")
            }
            scheduleHideControls()
        }
        btnNext.setOnClickListener(nextAction)
        btnNextTop.setOnClickListener(nextAction)

        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val newSpeed = speeds[speedIndex]
            player?.setPlaybackSpeed(newSpeed)
            btnSpeed.text = if (newSpeed % 1f == 0f) "${newSpeed.toInt()}X" else "${newSpeed}X"
            showCenterFeedback("${newSpeed}x")
            scheduleHideControls()
        }

        btnRotate.setOnClickListener {
            val currentOrientation = resources.configuration.orientation
            requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            scheduleHideControls()
        }

        val cycleResizeMode = View.OnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeModeIndex]
            showCenterFeedback(resizeModeNames[resizeModeIndex])
            scheduleHideControls()
        }
        btnAspectRatio.setOnClickListener(cycleResizeMode)
        btnZoom.setOnClickListener(cycleResizeMode)
        btnFullScale.setOnClickListener(cycleResizeMode)
        btnFullscreenResize.setOnClickListener(cycleResizeMode)

        btnRepeat.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            val nextMode = when (exo.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            exo.repeatMode = nextMode
            val label = when (nextMode) {
                Player.REPEAT_MODE_ONE -> "Repeat One"
                Player.REPEAT_MODE_ALL -> "Repeat All"
                else -> "Repeat Off"
            }
            showCenterFeedback(label)
            scheduleHideControls()
        }

        btnLock.setOnClickListener {
            isControlsLocked = true
            controlsOverlay.visibility = View.GONE
            btnUnlock.visibility = View.VISIBLE
            showCenterFeedback("Controls Locked")
        }

        btnUnlock.setOnClickListener {
            isControlsLocked = false
            btnUnlock.visibility = View.GONE
            setControlsVisible(true)
            showCenterFeedback("Controls Unlocked")
            scheduleHideControls()
        }

        btnAudioTrack.setOnClickListener {
            showCenterFeedback("Audio Track")
            scheduleHideControls()
        }

        btnSubtitle.setOnClickListener {
            if (subtitleCues.isEmpty()) {
                pickSubtitleLauncher.launch(arrayOf("*/*", "application/x-subrip", "text/plain"))
            } else {
                subtitlesEnabled = !subtitlesEnabled
                if (!subtitlesEnabled) {
                    tvSubtitleOverlay.visibility = View.GONE
                }
                showCenterFeedback(if (subtitlesEnabled) "Subtitles On" else "Subtitles Off")
            }
            scheduleHideControls()
        }

        btnSubtitle.setOnLongClickListener {
            pickSubtitleLauncher.launch(arrayOf("*/*", "application/x-subrip", "text/plain"))
            true
        }

        btnMoreOptions.setOnClickListener {
            pickVideoLauncher.launch("video/*")
            scheduleHideControls()
        }

        btnEqualizer.setOnClickListener {
            try {
                startActivity(Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL"))
            } catch (_: Exception) {
                showCenterFeedback("Equalizer unavailable")
            }
            scheduleHideControls()
        }

        btnDecoder.setOnClickListener {
            showCenterFeedback("HW Decoder")
            scheduleHideControls()
        }

        btnDecoderBadge.setOnClickListener {
            showCenterFeedback("SW Badge")
            scheduleHideControls()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration?.takeIf { it > 0L } ?: 0L
                    val targetPos = (duration * progress) / 1000L
                    tvCurrentTime.text = formatTime(targetPos)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isSeeking = true
                mainHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val duration = player?.duration?.takeIf { it > 0L } ?: 0L
                val targetPos = (duration * (sb?.progress ?: 0)) / 1000L
                player?.seekTo(targetPos)
                isSeeking = false
                scheduleHideControls()
            }
        })
    }

    private fun playAtIndex(index: Int) {
        if (index !in playlistUris.indices) return
        currentIndex = index
        val uri = playlistUris[index]
        subtitleCues = emptyList()
        tvSubtitleOverlay.visibility = View.GONE

        showEmptyPrompt(false)
        tvVideoTitle.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Zeno Player"
        player?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
        setControlsVisible(true)
        scheduleHideControls()
    }

    private fun loadSubtitleFromUri(uri: Uri) {
        try {
            val cues = mutableListOf<SubtitleCue>()
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                var line: String?
                var currentStart: Long? = null
                var currentEnd: Long? = null
                val textLines = mutableListOf<String>()

                fun flushCue() {
                    val s = currentStart
                    val e = currentEnd
                    if (s != null && e != null && textLines.isNotEmpty()) {
                        cues.add(SubtitleCue(s, e, textLines.joinToString("\n").trim()))
                    }
                    currentStart = null
                    currentEnd = null
                    textLines.clear()
                }

                while (true) {
                    line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        flushCue()
                        continue
                    }
                    if (trimmed.contains("-->")) {
                        flushCue()
                        val parts = trimmed.split("-->")
                        if (parts.size == 2) {
                            currentStart = parseSrtTimestamp(parts[0].trim())
                            currentEnd = parseSrtTimestamp(parts[1].substringBefore(" ").trim())
                        }
                    } else if (currentStart != null && !trimmed.matches(Regex("^\\d+$"))) {
                        textLines.add(trimmed.replace(Regex("<[^>]*>"), ""))
                    }
                }
                flushCue()
            }

            subtitleCues = cues
            subtitlesEnabled = cues.isNotEmpty()
            if (cues.isNotEmpty()) {
                showCenterFeedback("Subtitle Loaded (${cues.size})")
            } else {
                showCenterFeedback("Invalid SRT File")
            }
        } catch (_: Exception) {
            showCenterFeedback("Subtitle Load Failed")
        }
    }

    private fun parseSrtTimestamp(raw: String): Long? {
        val clean = raw.replace(',', '.')
        val parts = clean.split(':')
        if (parts.size != 3) return null
        return try {
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val secParts = parts[2].split('.')
            val seconds = secParts[0].toLong()
            val millis = if (secParts.size > 1) {
                secParts[1].take(3).padEnd(3, '0').toLong()
            } else {
                0L
            }
            ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
        } catch (_: Exception) {
            null
        }
    }

    private fun updateSubtitleOverlay() {
        if (!subtitlesEnabled || subtitleCues.isEmpty()) {
            tvSubtitleOverlay.visibility = View.GONE
            return
        }
        val pos = player?.currentPosition ?: 0L
        val active = subtitleCues.firstOrNull { pos in it.startMs..it.endMs }
        if (active != null && active.text.isNotEmpty()) {
            tvSubtitleOverlay.text = active.text
            tvSubtitleOverlay.visibility = View.VISIBLE
        } else {
            tvSubtitleOverlay.visibility = View.GONE
        }
    }

    private fun getCurrentBrightness(): Float {
        val current = window.attributes.screenBrightness
        return if (current < 0f) 0.5f else current.coerceIn(0.05f, 1.0f)
    }

    private fun setWindowBrightness(value: Float) {
        val params: WindowManager.LayoutParams = window.attributes
        params.screenBrightness = value.coerceIn(0.05f, 1.0f)
        window.attributes = params
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
        } else {
            exo.play()
        }
    }

    private fun seekBy(deltaMs: Long, feedback: String) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        updateProgress()
        updateSubtitleOverlay()
        showCenterFeedback(feedback)
    }

    private fun setControlsVisible(visible: Boolean) {
        areControlsVisible = visible
        controlsOverlay.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 3500L)
    }

    private fun showCenterFeedback(text: String) {
        tvCenterFeedback.text = text
        tvCenterFeedback.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideFeedbackRunnable)
        mainHandler.postDelayed(hideFeedbackRunnable, 900L)
    }

    private fun showEmptyPrompt(show: Boolean) {
        emptyVideoPrompt.visibility = if (show) View.VISIBLE else View.GONE
        gestureOverlay.visibility = if (show) View.GONE else View.VISIBLE
        controlsOverlay.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun updateProgress() {
        val exo = player ?: return
        val current = exo.currentPosition.coerceAtLeast(0L)
        val total = exo.duration.takeIf { it > 0L } ?: 0L

        if (!isSeeking) {
            tvCurrentTime.text = formatTime(current)
            val progress = if (total > 0L) ((current * 1000L) / total).toInt().coerceIn(0, 1000) else 0
            seekBar.progress = progress
        }
        tvTotalTime.text = formatTime(total)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(progressRunnable)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.removeCallbacks(hideControlsRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
