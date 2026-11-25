package com.example.myvideoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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
    private var player: ExoPlayer? = null

    private lateinit var tvCustomSubtitle: TextView
    private lateinit var btnOpenVideo: Button
    private lateinit var btnAspect: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnAddSub: Button
    private lateinit var btnSubSyncMinus: Button
    private lateinit var btnSubSyncPlus: Button
    private lateinit var btnSubSpeed: Button

    private val aspectModes = listOf(
        Pair("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
        Pair("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
        Pair("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
        Pair("Fixed W", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
        Pair("Fixed H", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT)
    )
    private var aspectIndex = 0

    private val videoSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2

    private val subSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var subSpeedIndex = 2

    private var subSyncOffsetMs: Long = 0L
    private var subtitleCues: List<SubtitleCue> = emptyList()

    private val subtitleHandler = Handler(Looper.getMainLooper())
    private val subtitleRunnable = object : Runnable {
        override fun run() {
            updateCustomSubtitle()
            subtitleHandler.postDelayed(this, 100L)
        }
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { playVideo(it) }
    }

    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadSubtitleFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        tvCustomSubtitle = findViewById(R.id.tvCustomSubtitle)
        btnOpenVideo = findViewById(R.id.btnOpenVideo)
        btnAspect = findViewById(R.id.btnAspect)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnAddSub = findViewById(R.id.btnAddSub)
        btnSubSyncMinus = findViewById(R.id.btnSubSyncMinus)
        btnSubSyncPlus = findViewById(R.id.btnSubSyncPlus)
        btnSubSpeed = findViewById(R.id.btnSubSpeed)

        initPlayer()
        setupControls()

        val incomingUri: Uri? = if (intent?.action == Intent.ACTION_VIEW) intent.data else null
        incomingUri?.let { playVideo(it) }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.playbackParameters = PlaybackParameters(videoSpeeds[speedIndex])
        }
    }

    private fun playVideo(uri: Uri) {
        player?.let { exo ->
            val mediaItem = MediaItem.fromUri(uri)
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun setupControls() {
        btnOpenVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        btnAspect.setOnClickListener {
            aspectIndex = (aspectIndex + 1) % aspectModes.size
            val (label, mode) = aspectModes[aspectIndex]
            playerView.resizeMode = mode
            btnAspect.text = "Aspect: $label"
        }

        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % videoSpeeds.size
            val spd = videoSpeeds[speedIndex]
            player?.playbackParameters = PlaybackParameters(spd)
            btnSpeed.text = "Speed: ${spd}x"
        }

        btnAddSub.setOnClickListener {
            pickSubtitleLauncher.launch(arrayOf("*/*"))
        }

        btnSubSyncMinus.setOnClickListener {
            subSyncOffsetMs -= 500L
            Toast.makeText(this, "Sub Sync: ${subSyncOffsetMs / 1000.0}s", Toast.LENGTH_SHORT).show()
        }

        btnSubSyncPlus.setOnClickListener {
            subSyncOffsetMs += 500L
            Toast.makeText(this, "Sub Sync: ${subSyncOffsetMs / 1000.0}s", Toast.LENGTH_SHORT).show()
        }

        btnSubSpeed.setOnClickListener {
            subSpeedIndex = (subSpeedIndex + 1) % subSpeeds.size
            val subSpd = subSpeeds[subSpeedIndex]
            btnSubSpeed.text = "SubSpd: ${subSpd}x"
        }
    }

    private fun loadSubtitleFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val allText = reader.readText()
                    subtitleCues = parseSrt(allText)
                    subSyncOffsetMs = 0L
                    subSpeedIndex = 2
                    btnSubSpeed.text = "SubSpd: 1.0x"
                    Toast.makeText(
                        this,
                        "Loaded ${subtitleCues.size} subtitle lines",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Subtitle load error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseSrt(srtContent: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = srtContent.replace("\r\n", "\n").split("\n\n")
        val timeRegex = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")

        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size < 2) continue

            val timeLine = lines.firstOrNull { timeRegex.containsMatchIn(it) } ?: continue
            val match = timeRegex.find(timeLine) ?: continue

            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured
            val startMs = toMillis(h1.toLong(), m1.toLong(), s1.toLong(), ms1.toLong())
            val endMs = toMillis(h2.toLong(), m2.toLong(), s2.toLong(), ms2.toLong())

            val timeLineIndex = lines.indexOf(timeLine)
            val textLines = lines.drop(timeLineIndex + 1)
            if (textLines.isNotEmpty()) {
                cues.add(SubtitleCue(startMs, endMs, textLines.joinToString("\n")))
            }
        }
        return cues
    }

    private fun toMillis(h: Long, m: Long, s: Long, ms: Long): Long {
        return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
    }

    private fun updateCustomSubtitle() {
        val exo = player ?: return
        if (subtitleCues.isEmpty() || !exo.isPlaying) {
            if (subtitleCues.isEmpty()) tvCustomSubtitle.visibility = View.GONE
            return
        }

        val subSpeed = subSpeeds[subSpeedIndex].toDouble()
        val effectivePosMs = ((exo.currentPosition * subSpeed) + subSyncOffsetMs).toLong()

        val activeCue = subtitleCues.firstOrNull { cue ->
            effectivePosMs in cue.startMs..cue.endMs
        }

        if (activeCue != null) {
            tvCustomSubtitle.text = activeCue.text
            tvCustomSubtitle.visibility = View.VISIBLE
        } else {
            tvCustomSubtitle.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        subtitleHandler.post(subtitleRunnable)
    }

    override fun onStop() {
        super.onStop()
        subtitleHandler.removeCallbacks(subtitleRunnable)
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        subtitleHandler.removeCallbacks(subtitleRunnable)
        player?.release()
        player = null
    }
}
