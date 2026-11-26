package com.example.myvideoplayer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private data class VideoItem(
        val uri: Uri,
        val title: String,
        val folderName: String,
        val folderPath: String
    )

    private data class FolderItem(
        val name: String,
        val path: String,
        val videoCount: Int
    )

    private lateinit var btnHomeBack: ImageButton
    private lateinit var tvHomeTitle: TextView
    private lateinit var btnRefreshMedia: TextView
    private lateinit var tvEmptyHome: TextView
    private lateinit var rvMediaList: RecyclerView

    private val allVideos = mutableListOf<VideoItem>()
    private val folders = mutableListOf<FolderItem>()
    private var currentFolderPath: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadMediaFolders()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        btnHomeBack = findViewById(R.id.btnHomeBack)
        tvHomeTitle = findViewById(R.id.tvHomeTitle)
        btnRefreshMedia = findViewById(R.id.btnRefreshMedia)
        tvEmptyHome = findViewById(R.id.tvEmptyHome)
        rvMediaList = findViewById(R.id.rvMediaList)

        rvMediaList.layoutManager = LinearLayoutManager(this)

        btnRefreshMedia.setOnClickListener { view ->
            animateScanButton(view) {
                checkPermissionAndLoad()
            }
        }

        btnHomeBack.setOnClickListener { view ->
            animateScanButton(view) {
                if (currentFolderPath != null) {
                    currentFolderPath = null
                    showFoldersList()
                }
            }
        }

        checkPermissionAndLoad()
    }

    private fun animateScanButton(view: View, action: () -> Unit) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.90f)
            .scaleY(0.90f)
            .setDuration(75L)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setInterpolator(OvershootInterpolator(2.3f))
                    .setDuration(170L)
                    .start()
                action()
            }
            .start()
    }

    private fun checkPermissionAndLoad() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions)
    }

    private fun loadMediaFolders() {
        allVideos.clear()
        folders.clear()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA
        )

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                val folderMap = LinkedHashMap<String, MutableList<VideoItem>>()

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(nameCol) ?: "Video"
                    val path = cursor.getString(dataCol) ?: ""
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val parentFile = if (path.isNotEmpty()) File(path).parentFile else null
                    val folderPath = parentFile?.absolutePath ?: "Internal"
                    val folderName = when {
                        parentFile == null -> "Internal memory"
                        parentFile.absolutePath == "/storage/emulated/0" -> "Internal memory"
                        else -> parentFile.name.ifBlank { "Folder" }
                    }

                    val item = VideoItem(uri, title, folderName, folderPath)
                    allVideos.add(item)
                    folderMap.getOrPut(folderPath) { mutableListOf() }.add(item)
                }

                for ((path, list) in folderMap) {
                    val name = list.firstOrNull()?.folderName ?: "Folder"
                    folders.add(FolderItem(name, path, list.size))
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Videos load කරන්න බැරි වුණා", Toast.LENGTH_SHORT).show()
        }

        if (currentFolderPath != null) {
            showVideosInFolder(currentFolderPath!!)
        } else {
            showFoldersList()
        }
    }

    private fun collectTextViews(view: View, list: MutableList<TextView>) {
        if (view is TextView) {
            list.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTextViews(view.getChildAt(i), list)
            }
        }
    }

    private fun bindRowTexts(itemView: View, titleText: String, subtitleText: String) {
        val textViews = mutableListOf<TextView>()
        collectTextViews(itemView, textViews)
        if (textViews.isNotEmpty()) {
            textViews[0].text = titleText
        }
        if (textViews.size >= 2) {
            textViews[1].text = subtitleText
        }
    }

    private fun showFoldersList() {
        btnHomeBack.visibility = View.GONE
        tvHomeTitle.text = "Zeno Player"

        if (folders.isEmpty()) {
            tvEmptyHome.visibility = View.VISIBLE
            rvMediaList.visibility = View.GONE
            return
        }

        tvEmptyHome.visibility = View.GONE
        rvMediaList.visibility = View.VISIBLE

        rvMediaList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_folder_row, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val folder = folders[position]
                val countText = if (folder.videoCount == 1) "1 video" else "${folder.videoCount} videos"
                bindRowTexts(holder.itemView, folder.name, countText)

                holder.itemView.setOnClickListener {
                    currentFolderPath = folder.path
                    showVideosInFolder(folder.path)
                }
            }

            override fun getItemCount(): Int = folders.size
        }
    }

    private fun showVideosInFolder(folderPath: String) {
        val folderVideos = allVideos.filter { it.folderPath == folderPath }
        btnHomeBack.visibility = View.VISIBLE
        tvHomeTitle.text = folderVideos.firstOrNull()?.folderName ?: "Videos"

        if (folderVideos.isEmpty()) {
            tvEmptyHome.visibility = View.VISIBLE
            rvMediaList.visibility = View.GONE
            return
        }

        tvEmptyHome.visibility = View.GONE
        rvMediaList.visibility = View.VISIBLE

        rvMediaList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_folder_row, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val video = folderVideos[position]
                bindRowTexts(holder.itemView, video.title, "Tap to play")

                holder.itemView.setOnClickListener {
                    val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = video.uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                }
            }

            override fun getItemCount(): Int = folderVideos.size
        }
    }
}
