package com.farsipsx.ps1app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var pickButton: Button
    private lateinit var expectedObbName: String
    private var obbFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        pickButton = findViewById(R.id.pick_button)

        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionCode
        expectedObbName = ObbManager.expectedName(packageName, version)
        findViewById<TextView>(R.id.obb_path).text = "Android/obb/$packageName/$expectedObbName"

        val obb = ObbManager.findObb(this, packageName, version)
        if (obb == null) {
            statusText.setText(R.string.obb_missing)
            startButton.isEnabled = false
            pickButton.visibility = View.VISIBLE
            pickButton.setOnClickListener { pickObb() }
            return
        }

        obbFile = obb
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
            pickButton.visibility = View.GONE
            startButton.setOnClickListener { launchGame(obb, meta) }
        } catch (error: Exception) {
            statusText.text = getString(R.string.obb_bad, error.message)
            startButton.isEnabled = false
        }
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

    private fun pickObb() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_OBB)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OBB || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val dir = getObbDir() ?: return
        val target = File(dir, expectedObbName)
        try {
            contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(target).use { output -> input?.copyTo(output) }
            }
            Toast.makeText(this, R.string.obb_copied, Toast.LENGTH_SHORT).show()
            recreate()
        } catch (error: Exception) {
            Toast.makeText(this, getString(R.string.copy_failed, error.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    companion object {
        private const val REQUEST_OBB = 1001
    }
}
