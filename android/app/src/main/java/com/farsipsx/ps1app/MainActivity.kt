package com.farsipsx.ps1app

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var pathText: TextView
    private lateinit var startButton: Button
    private lateinit var downloadButton: Button
    private lateinit var expectedObbName: String
    private var downloadId = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            handleDownloadComplete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        pathText = findViewById(R.id.obb_path)
        startButton = findViewById(R.id.start_button)
        downloadButton = findViewById(R.id.download_button)

        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionCode
        expectedObbName = ObbManager.expectedName(packageName, version)
        pathText.text = getString(R.string.obb_path, packageName, expectedObbName)

        startButton.isEnabled = false
        downloadButton.visibility = View.GONE
        downloadButton.setOnClickListener { downloadData() }

        registerDownloads()

        val obb = ObbManager.findObb(this, packageName, version)
        if (obb == null) {
            statusText.setText(R.string.obb_missing)
            downloadButton.visibility = View.VISIBLE
            if (getString(R.string.data_download_url).isBlank()) {
                downloadButton.isEnabled = false
                statusText.setText(R.string.download_url_missing)
            }
            return
        }
        showObb(obb)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered yet.
        }
        super.onDestroy()
    }

    private fun registerDownloads() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
    }

    private fun showObb(obb: File) {
        try {
            val meta = ObbManager.readMeta(obb)
            statusText.text = getString(
                R.string.obb_found,
                meta.gameName,
                meta.bin,
                meta.cue,
                (obb.length() / 1048576L).toString()
            )
            startButton.isEnabled = true
            downloadButton.visibility = View.GONE
            startButton.setOnClickListener { launchGame(obb, meta) }
        } catch (error: Exception) {
            statusText.text = getString(R.string.obb_bad, error.message)
            startButton.isEnabled = false
            downloadButton.visibility = View.VISIBLE
            downloadButton.setText(R.string.download_again)
        }
    }

    private fun downloadData() {
        val url = getString(R.string.data_download_url).trim()
        if (url.isBlank()) {
            Toast.makeText(this, R.string.download_url_missing, Toast.LENGTH_LONG).show()
            return
        }

        downloadButton.isEnabled = false
        startButton.isEnabled = false
        statusText.setText(R.string.downloading)

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(getString(R.string.app_name))
            .setDescription(getString(R.string.downloading))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, expectedObbName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = manager.enqueue(request)
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    private fun handleDownloadComplete() {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        if (cursor == null) {
            downloadFailed("unknown")
            return
        }

        cursor.use {
            if (!it.moveToFirst()) {
                downloadFailed("unknown")
                return
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                downloadFailed(reason.toString())
                return
            }
        }

        val source = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), expectedObbName)
        val obbDir = getObbDir()
        if (!source.isFile || obbDir == null) {
            downloadFailed("file")
            return
        }

        val target = File(obbDir, expectedObbName)
        try {
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            if (!source.renameTo(target)) {
                FileOutputStream(target).use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                source.delete()
            }
            Toast.makeText(this, R.string.download_done, Toast.LENGTH_SHORT).show()
            recreate()
        } catch (error: Exception) {
            downloadFailed(error.message ?: "copy")
        }
    }

    private fun downloadFailed(reason: String) {
        statusText.text = getString(R.string.download_failed, reason)
        downloadButton.isEnabled = true
        downloadButton.setText(R.string.download_again)
        downloadId = -1L
    }

    private fun launchGame(obb: File, meta: GameMeta.Info) {
        try {
            val extracted = ObbManager.extract(obb, File(cacheDir, "game"), this)
            val intent = Intent(this, EmulatorActivity::class.java).apply {
                putExtra("bin", extracted.bin.absolutePath)
                putExtra("cue", extracted.cue.absolutePath)
                putExtra("core", extracted.core?.absolutePath)
                putExtra("name", meta.gameName)
            }
            startActivity(intent)
        } catch (error: Exception) {
            Toast.makeText(this, getString(R.string.extract_failed, error.message), Toast.LENGTH_LONG).show()
        }
    }
}
