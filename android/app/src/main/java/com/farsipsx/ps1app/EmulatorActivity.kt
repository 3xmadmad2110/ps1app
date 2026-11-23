package com.farsipsx.ps1app

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

class EmulatorActivity : Activity(), EmulatorHost, SurfaceHolder.Callback {

    private var audioTrack: AudioTrack? = null
    private var initialized = false
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulator)

        findViewById<TextView>(R.id.game_title).text = intent.getStringExtra("name") ?: getString(R.string.app_name)
        findViewById<SurfaceView>(R.id.emulator_surface).holder.addCallback(this)
        bindButtons()
    }

    private fun bindButtons() {
        val map = mapOf(
            R.id.btn_cross to Pad.A,
            R.id.btn_circle to Pad.B,
            R.id.btn_square to Pad.X,
            R.id.btn_triangle to Pad.Y,
            R.id.btn_l to Pad.L,
            R.id.btn_r to Pad.R,
            R.id.btn_start to Pad.START,
            R.id.btn_select to Pad.SELECT,
            R.id.btn_up to Pad.UP,
            R.id.btn_down to Pad.DOWN,
            R.id.btn_left to Pad.LEFT,
            R.id.btn_right to Pad.RIGHT
        )
        for ((id, button) in map) {
            findViewById<View>(id).setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        NativeBridge.nativeKey(0, button, true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        NativeBridge.nativeKey(0, button, false)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (initialized) return
        val core = intent.getStringExtra("core")
        val cue = intent.getStringExtra("cue")
        if (core == null || cue == null) {
            onError(getString(R.string.core_missing))
            return
        }
        val saveDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        initialized = NativeBridge.nativeInit(
            holder.surface, core, cue, filesDir.absolutePath, saveDir, this
        )
        if (!initialized) {
            onError(getString(R.string.core_load_failed))
            return
        }
        running = NativeBridge.nativeStart()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

    override fun onAudioRate(sampleRate: Int) {
        runOnUiThread {
            try {
                audioTrack?.release()
                val channel = AudioFormat.CHANNEL_OUT_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channel, encoding)
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channel)
                    .build()
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(max(minBuffer, sampleRate))
                    .build()
                audioTrack?.play()
            } catch (error: Exception) {
                onError("AudioTrack: ${error.message}")
            }
        }
    }

    override fun onAudioData(data: ByteArray, length: Int) {
        if (length <= 0) return
        try {
            audioTrack?.write(data, 0, length)
        } catch (_: Exception) {
            // Device may be busy; drop this buffer.
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            findViewById<TextView>(R.id.game_title).text = message
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Pad.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Pad.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Pad.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Pad.RIGHT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> Pad.A
            KeyEvent.KEYCODE_ESCAPE -> Pad.B
            KeyEvent.KEYCODE_BUTTON_L1 -> Pad.L
            KeyEvent.KEYCODE_BUTTON_R1 -> Pad.R
            else -> -1
        }
        if (button >= 0 && running) {
            NativeBridge.nativeKey(0, button, event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (running) NativeBridge.nativeStop()
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (running) NativeBridge.nativeStop()
        NativeBridge.nativeDestroy()
        audioTrack?.release()
        audioTrack = null
        super.onDestroy()
    }
}
