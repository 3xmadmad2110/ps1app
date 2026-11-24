package com.farsipsx.ps1app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var pathText: TextView
    private lateinit var startButton: Button
    private lateinit var downloadButton: Button
    private lateinit var expectedObbName: String

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("download", Context.MODE_PRIVATE) }

    private var progressBar: ProgressBar? = null
    private var detailsText: TextView? = null
    private var speedText: TextView? = null
    private var toggleButton: Button? = null
    private var dialog: AlertDialog? = null
    private var downloadThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null

    private var downloadedBytes = 0L
    private var totalBytes = -1L
    private var lastReportBytes = 0L
    private var lastReportTime = 0L
    private var lastPersistBytes = 0L

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
        downloadButton.setOnClickListener { startFromButton() }

        val obb = ObbManager.findObb(this, packageName, version)
        if (obb != null) {
            showObb(obb)
            return
        }

        statusText.setText(R.string.obb_missing)
        downloadButton.visibility = View.VISIBLE
        restorePartial()
        if (downloadedBytes > 0L) {
            statusText.text = getString(R.string.download_resume_available, formatBytes(downloadedBytes))
            downloadButton.setText(R.string.resume_download)
        } else {
            downloadButton.setText(R.string.download_data)
        }
        if (getString(R.string.data_download_url).isBlank()) {
            downloadButton.isEnabled = false
            statusText.setText(R.string.download_url_missing)
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        progressBar = null
        detailsText = null
        speedText = null
        toggleButton = null
        super.onDestroy()
    }

    private fun keyBytes() = "bytes_$expectedObbName"
    private fun keyTotal() = "total_$expectedObbName"

    private fun partialFile(): File =
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir, expectedObbName)

    private fun restorePartial() {
        val file = partialFile()
        val saved = prefs.getLong(keyBytes(), 0L)
        if (file.isFile && saved > 0L && saved == file.length()) {
            downloadedBytes = saved
            totalBytes = prefs.getLong(keyTotal(), -1L)
        } else {
            downloadedBytes = 0L
            totalBytes = -1L
            if (file.exists()) file.delete()
            clearProgress()
        }
    }

    private fun startFromButton() {
        restorePartial()
        openDownloadDialog(start = true)
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

    private fun openDownloadDialog(start: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_download, null)
        progressBar = view.findViewById(R.id.download_progress_bar) as ProgressBar
        detailsText = view.findViewById(R.id.download_details) as TextView
        speedText = view.findViewById(R.id.download_speed) as TextView
        toggleButton = view.findViewById(R.id.download_toggle_button) as Button

        toggleButton?.setOnClickListener { togglePauseResume() }
        (view.findViewById(R.id.download_cancel_button) as Button).setOnClickListener { cancelDownload() }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.download_title)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog?.show()

        reportProgress(force = true)
        if (start) startDownload()
    }

    private fun startDownload() {
        if (running) return
        cancelled = false
        paused = false
        running = true
        lastReportBytes = downloadedBytes
        lastReportTime = 0L
        lastPersistBytes = downloadedBytes
        toggleButton?.setText(R.string.pause)
        detailsText?.setText(R.string.downloading)

        downloadThread = Thread { downloadLoop() }.apply {
            isDaemon = true
            start()
        }
    }

    private fun togglePauseResume() {
        if (cancelled) return
        if (running) {
            if (paused) {
                paused = false
                toggleButton?.setText(R.string.pause)
                detailsText?.setText(R.string.downloading)
            } else {
                paused = true
                connection?.disconnect()
                persistProgress(force = true)
                toggleButton?.setText(R.string.resume)
                detailsText?.setText(R.string.download_paused)
            }
        } else {
            toggleButton?.setText(R.string.pause)
            startDownload()
        }
    }

    private fun cancelDownload() {
        cancelled = true
        paused = false
        connection?.disconnect()
        clearProgress()
        partialFile().delete()
        running = false
        handler.post {
            dialog?.dismiss()
            dialog = null
        }
    }

    private fun downloadLoop() {
        var retries = 0
        try {
            while (!cancelled) {
                while (paused && !cancelled) Thread.sleep(50L)
                if (cancelled) return

                try {
                    val done = downloadChunk()
                    if (done) {
                        finishDownload()
                        return
                    }
                    if (paused || cancelled) continue
                } catch (error: Exception) {
                    if (cancelled) return
                    if (paused) continue
                    persistProgress(force = true)
                }

                retries++
                if (retries > MAX_RETRIES) {
                    paused = true
                    postError(getString(R.string.download_failed, "too many retries"))
                    return
                }
                postStatus(getString(R.string.download_retry, retries))
                Thread.sleep(1000L)
            }
        } catch (error: Exception) {
            if (!cancelled) postError(error.message ?: "error")
        } finally {
            running = false
            connection = null
        }
    }

    private fun downloadChunk(): Boolean {
        val url = getString(R.string.data_download_url).trim()
        if (url.isBlank()) throw IOException(getString(R.string.download_url_missing))

        val file = partialFile()
        file.parentFile?.mkdirs()

        val conn = openConnection(url, downloadedBytes)
        connection = conn
        var raf: RandomAccessFile? = null
        var input: InputStream? = null
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }
            val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val append = code == HttpURLConnection.HTTP_PARTIAL && downloadedBytes > 0L

            if (!append) {
                downloadedBytes = 0L
                persistProgress(force = true)
            }
            totalBytes = if (append) {
                parseTotal(conn.getHeaderField("Content-Range"), downloadedBytes, contentLength)
            } else {
                contentLength
            }

            raf = RandomAccessFile(file, "rw")
            if (append) {
                raf.seek(downloadedBytes)
            } else {
                raf.setLength(0L)
                raf.seek(0L)
            }
            input = conn.inputStream
            val buffer = ByteArray(64 * 1024)
            var finished = false

            while (!paused && !cancelled) {
                val read = input.read(buffer)
                if (read < 0) {
                    finished = true
                    break
                }
                raf.write(buffer, 0, read)
                downloadedBytes += read.toLong()
                reportProgress(force = false)
                if (downloadedBytes - lastPersistBytes >= PERSIST_STEP) {
                    persistProgress()
                    lastPersistBytes = downloadedBytes
                }
            }
            persistProgress(force = true)
            return finished && (totalBytes <= 0L || downloadedBytes >= totalBytes)
        } finally {
            try { input?.close() } catch (_: Exception) { }
            try { raf?.close() } catch (_: Exception) { }
            try { conn.disconnect() } catch (_: Exception) { }
            if (connection === conn) connection = null
        }
    }

    private fun openConnection(url: String, rangeStart: Long): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Range", "bytes=$rangeStart-")
            conn.setRequestProperty("User-Agent", "PS1App")

            val code = conn.responseCode
            if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                redirects++
                if (location.isNullOrBlank() || redirects > MAX_REDIRECTS) {
                    throw IOException("redirect failed")
                }
                current = URI(current).resolve(location).toString()
                continue
            }
            return conn
        }
    }

    private fun parseTotal(contentRange: String?, downloaded: Long, contentLength: Long): Long {
        if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
            contentRange.substringAfterLast('/').toLongOrNull()?.let { if (it > 0) return it }
        }
        return if (downloaded > 0L && contentLength > 0L) downloaded + contentLength else contentLength
    }

    private fun finishDownload() {
        val source = partialFile()
        val obbDir = getObbDir()
        if (!source.isFile || obbDir == null) {
            paused = true
            postError("file")
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
            clearProgress()
            handler.post {
                dialog?.dismiss()
                dialog = null
                Toast.makeText(this, R.string.download_done, Toast.LENGTH_SHORT).show()
                recreate()
            }
        } catch (error: Exception) {
            paused = true
            postError(error.message ?: "copy")
        }
    }

    private fun persistProgress(force: Boolean = false) {
        if (!force && downloadedBytes - lastPersistBytes < PERSIST_STEP) return
        prefs.edit()
            .putLong(keyBytes(), downloadedBytes)
            .putLong(keyTotal(), totalBytes)
            .apply()
        lastPersistBytes = downloadedBytes
    }

    private fun clearProgress() {
        prefs.edit().remove(keyBytes()).remove(keyTotal()).apply()
        downloadedBytes = 0L
        totalBytes = -1L
        lastPersistBytes = 0L
    }

    private fun reportProgress(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && lastReportTime != 0L && now - lastReportTime < REPORT_INTERVAL) return
        val elapsed = if (lastReportTime > 0L) now - lastReportTime else 0L
        val speed = if (elapsed > 0L) (downloadedBytes - lastReportBytes) * 1000.0 / elapsed else 0.0
        val percent = if (totalBytes > 0L) ((downloadedBytes * 100) / totalBytes).toInt().coerceAtMost(100) else -1

        handler.post {
            if (totalBytes > 0L) {
                progressBar?.isIndeterminate = false
                progressBar?.progress = percent
                detailsText?.text = getString(
                    R.string.download_progress,
                    percent,
                    formatBytes(downloadedBytes),
                    formatBytes(totalBytes)
                )
            } else {
                progressBar?.isIndeterminate = true
                detailsText?.text = getString(R.string.download_progress_unknown, formatBytes(downloadedBytes))
            }
            speedText?.text = getString(R.string.download_speed, formatBytes(speed.toLong()))
        }
        lastReportBytes = downloadedBytes
        lastReportTime = now
    }

    private fun postStatus(text: String) {
        handler.post { detailsText?.text = text }
    }

    private fun postError(message: String) {
        handler.post {
            toggleButton?.setText(R.string.resume)
            detailsText?.text = message
            statusText.text = message
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024L) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
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

    companion object {
        private const val MAX_REDIRECTS = 10
        private const val MAX_RETRIES = 10
        private const val REPORT_INTERVAL = 400L
        private const val PERSIST_STEP = 2L * 1024L * 1024L
    }
}
