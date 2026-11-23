package com.farsipsx.ps1app

object GameMeta {
    const val BASE_PACKAGE = "com.farsipsx.ps1app"

    data class Info(
        val gameName: String,
        val slug: String,
        val packageName: String,
        val versionCode: Int,
        val bin: String,
        val cue: String,
        val cover: String?
    )

    data class Extracted(
        val bin: java.io.File,
        val cue: java.io.File,
        val cover: java.io.File?,
        val core: java.io.File?
    )
}
