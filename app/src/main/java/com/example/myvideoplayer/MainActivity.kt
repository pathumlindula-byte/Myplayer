package com.example.myvideoplayer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnRefreshMedia: TextView

    private val requestMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startScanVideos()
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_ZenoPlayer)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        btnRefreshMedia = findViewById(R.id.btnRefreshMedia)
        btnRefreshMedia.setOnClickListener { view ->
            animateGlassPill(view) {
                checkPermissionAndScan()
            }
        }
    }

    private fun animateGlassPill(view: View, action: () -> Unit) {
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

    private fun checkPermissionAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestMediaPermissionLauncher.launch(permissions)
    }

    private fun startScanVideos() {
        Toast.makeText(this, "Scanning videos...", Toast.LENGTH_SHORT).show()
        try {
            pickVideoLauncher.launch("video/*")
        } catch (_: Exception) {
            Toast.makeText(this, "Scan open කරන්න බැරි වුණා", Toast.LENGTH_SHORT).show()
        }
    }
}
