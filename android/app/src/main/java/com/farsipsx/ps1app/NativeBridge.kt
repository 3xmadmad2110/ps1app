package com.farsipsx.ps1app

import android.view.Surface

interface EmulatorHost {
    fun onAudioRate(sampleRate: Int)
    fun onAudioData(data: ByteArray, length: Int)
    fun onError(message: String)
}

object NativeBridge {
    init {
        System.loadLibrary("psxemu")
    }

    external fun nativeInit(
        surface: Surface,
        corePath: String,
        cuePath: String,
        systemDir: String,
        saveDir: String,
        host: EmulatorHost
    ): Boolean

    external fun nativeStart(): Boolean
    external fun nativeStop()
    external fun nativeKey(port: Int, button: Int, pressed: Boolean)
    external fun nativeDestroy()
}

object Pad {
    const val B = 0          // Circle
    const val Y = 1          // Triangle
    const val SELECT = 2
    const val START = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
    const val A = 8          // Cross
    const val X = 9          // Square
    const val L = 10
    const val R = 11
    const val L2 = 12
    const val R2 = 13
    const val L3 = 14
    const val R3 = 15
}
