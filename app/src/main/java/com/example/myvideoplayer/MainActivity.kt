package com.example.myvideoplayer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private data class VideoItem(
        val title: String,
        val uri: Uri,
        val durationMs: Long,
        val folderName: String
    )

    private data class FolderItem(
        val name: String,
        val videos: List<VideoItem>
    )

    private lateinit var homeRoot: View
    private lateinit var homeHeader: View
    private lateinit var btnHomeBack: ImageButton
    private lateinit var tvHomeTitle: TextView
    private lateinit var btnRefreshMedia: TextView
    private lateinit var tvEmptyHome: TextView
    private lateinit var rvMediaList: RecyclerView

    private val folders = mutableListOf<FolderItem>()
    private var selectedFolder: FolderItem? = null
    private var hasAnimatedInitialList = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadVideoFoldersAsync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ZenoPlayer)
        try {
            enableEdgeToEdge()
        } catch (_: Exception) {
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        bindViews()
        applySystemBarsPadding()
        setupListeners()
        animateHeaderFast()

        if (hasVideoPermission()) {
            loadVideoFoldersAsync()
        } else {
            requestMediaPermission()
        }
    }

    private fun bindViews() {
        homeRoot = findViewById(R.id.homeRoot)
        homeHeader = findViewById(R.id.homeHeader)
        btnHomeBack = findViewById(R.id.btnHomeBack)
        tvHomeTitle = findViewById(R.id.tvHomeTitle)
        btnRefreshMedia = findViewById(R.id.btnRefreshMedia)
        tvEmptyHome = findViewById(R.id.tvEmptyHome)
        rvMediaList = findViewById(R.id.rvMediaList)
        rvMediaList.layoutManager = LinearLayoutManager(this)
        rvMediaList.setHasFixedSize(true)
    }

    private fun applySystemBarsPadding() {
        val baseHeaderTop = homeHeader.paddingTop
        val baseListBottom = rvMediaList.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(homeRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            homeHeader.setPadding(
                homeHeader.paddingLeft,
                baseHeaderTop + bars.top,
                homeHeader.paddingRight,
                homeHeader.paddingBottom
            )
            rvMediaList.setPadding(
                rvMediaList.paddingLeft,
                rvMediaList.paddingTop,
                rvMediaList.paddingRight,
                baseListBottom + bars.bottom
            )
            insets
        }
    }

    private fun hasVideoPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMediaPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions)
    }

    private fun animateHeaderFast() {
        homeHeader.alpha = 0f
        homeHeader.translationY = -14f
        homeHeader.animate()
            .alpha(1f)
            .translationY(0f)
            .setInterpolator(OvershootInterpolator(1.2f))
            .setDuration(200L)
            .start()
    }

    private fun animateButtonPop(view: View, action: () -> Unit) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(65L)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .setDuration(140L)
                    .start()
                action()
            }
            .start()
    }

    private fun animateRowClick(view: View, action: () -> Unit) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .alpha(0.78f)
            .setDuration(65L)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(120L)
                    .start()
                action()
            }
            .start()
    }

    private fun setupListeners() {
        btnHomeBack.setOnClickListener {
            animateButtonPop(it) {
                showFoldersView(animate = true)
            }
        }

        btnRefreshMedia.setOnClickListener {
            animateButtonPop(it) {
                if (hasVideoPermission()) {
                    loadVideoFoldersAsync()
                } else {
                    requestMediaPermission()
                }
            }
        }
    }

    private fun loadVideoFoldersAsync() {
        if (!hasVideoPermission()) return

        ioExecutor.execute {
            val grouped = linkedMapOf<String, MutableList<VideoItem>>()
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )

            try {
                contentResolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} ASC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                            ?: cursor.getString(nameCol)
                            ?: "Video"
                        val duration = cursor.getLong(durationCol)
                        val folder = cursor.getString(bucketCol)?.takeIf { it.isNotBlank() }
                            ?: "Internal memory"
                        val contentUri = ContentUris.withAppendedId(collection, id)

                        grouped.getOrPut(folder) { mutableListOf() }
                            .add(VideoItem(title, contentUri, duration, folder))
                    }
                }
            } catch (_: Exception) {
            }

            val builtFolders = grouped.map { (name, list) -> FolderItem(name, list) }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                folders.clear()
                folders.addAll(builtFolders)

                val currentSelected = selectedFolder
                if (currentSelected != null) {
                    val updated = folders.firstOrNull { it.name == currentSelected.name }
                    if (updated != null) {
                        showVideosInFolder(updated, animate = false)
                        return@post
                    }
                }
                showFoldersView(animate = !hasAnimatedInitialList)
                hasAnimatedInitialList = true
            }
        }
    }

    private fun triggerListAnimation() {
        try {
            rvMediaList.layoutAnimation = AnimationUtils.loadLayoutAnimation(
                this,
                R.anim.layout_fall_down
            )
            rvMediaList.scheduleLayoutAnimation()
        } catch (_: Exception) {
        }
    }

    private fun showFoldersView(animate: Boolean) {
        selectedFolder = null
        btnHomeBack.visibility = View.GONE
        tvHomeTitle.text = "Zeno Player"

        if (folders.isEmpty()) {
            tvEmptyHome.text = "Videos හමු වුණේ නැහැ"
            tvEmptyHome.visibility = View.VISIBLE
            rvMediaList.visibility = View.GONE
        } else {
            tvEmptyHome.visibility = View.GONE
            rvMediaList.visibility = View.VISIBLE
            rvMediaList.adapter = FolderAdapter(folders) { folder ->
                showVideosInFolder(folder, animate = true)
            }
            if (animate) triggerListAnimation()
        }
    }

    private fun showVideosInFolder(folder: FolderItem, animate: Boolean) {
        selectedFolder = folder
        btnHomeBack.visibility = View.VISIBLE
        tvHomeTitle.text = folder.name

        if (folder.videos.isEmpty()) {
            tvEmptyHome.text = "මේ folder එකේ videos නැහැ"
            tvEmptyHome.visibility = View.VISIBLE
            rvMediaList.visibility = View.GONE
        } else {
            tvEmptyHome.visibility = View.GONE
            rvMediaList.visibility = View.VISIBLE
            rvMediaList.adapter = VideoAdapter(folder.videos) { video ->
                openPlayer(video)
            }
            if (animate) triggerListAnimation()
        }
    }

    private fun openPlayer(video: VideoItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(video.uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
    }

    private inner class FolderAdapter(
        private val items: List<FolderItem>,
        private val onClick: (FolderItem) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

        inner class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivFolderBadge: ImageView = view.findViewById(R.id.ivFolderBadge)
            val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
            val tvVideoCount: TextView = view.findViewById(R.id.tvVideoCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_folder_row, parent, false)
            return FolderViewHolder(view)
        }

        override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
            val item = items[position]
            holder.tvFolderName.text = item.name
            val count = item.videos.size
            holder.tvVideoCount.text = if (count == 1) "1 video" else "$count videos"
            holder.ivFolderBadge.visibility =
                if (item.name.equals("Camera", ignoreCase = true)) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { view ->
                animateRowClick(view) {
                    onClick(item)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class VideoAdapter(
        private val items: List<VideoItem>,
        private val onClick: (VideoItem) -> Unit
    ) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

        inner class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivFolderBadge: ImageView = view.findViewById(R.id.ivFolderBadge)
            val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
            val tvVideoCount: TextView = view.findViewById(R.id.tvVideoCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_folder_row, parent, false)
            return VideoViewHolder(view)
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            val item = items[position]
            holder.ivFolderBadge.visibility = View.GONE
            holder.tvFolderName.text = item.title
            holder.tvVideoCount.text = formatDuration(item.durationMs)
            holder.itemView.setOnClickListener { view ->
                animateRowClick(view) {
                    onClick(item)
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
