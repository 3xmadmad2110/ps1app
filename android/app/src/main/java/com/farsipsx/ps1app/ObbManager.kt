package com.farsipsx.ps1app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object ObbManager {

    fun expectedName(packageName: String, versionCode: Int): String =
        "main.$versionCode.$packageName.obb"

    fun findObb(context: Context, packageName: String, versionCode: Int): File? {
        val dir = context.getObbDir() ?: return null
        val exact = File(dir, expectedName(packageName, versionCode))
        if (exact.isFile) return exact
        return dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("main.$versionCode.") &&
                it.name.endsWith(".$packageName.obb")
        }
    }

    fun readMeta(obb: File): GameMeta.Info {
        ZipFile(obb).use { zip ->
            val entry = zip.getEntry("meta.json") ?: error("meta.json داخل OBB وجود ندارد")
            val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            return GameMeta.Info(
                gameName = json.optString("gameName", "PS1 Game"),
                slug = json.optString("slug", ""),
                packageName = json.optString("packageName", ""),
                versionCode = json.optInt("versionCode", 1),
                bin = json.getString("bin"),
                cue = json.getString("cue"),
                cover = json.optString("cover", null)?.takeIf { it.isNotBlank() }
            )
        }
    }

    fun extract(obb: File, target: File, context: Context): GameMeta.Extracted {
        val meta = readMeta(obb)
        target.mkdirs()

        ZipFile(obb).use { zip ->
            fun copy(name: String?): File? {
                if (name.isNullOrBlank()) return null
                val entry = zip.getEntry(name) ?: return null
                val out = File(target, File(name).name)
                if (!out.exists() || out.length() != entry.size) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
                return out
            }

            val bin = copy(meta.bin) ?: error("فایل ${meta.bin} داخل OBB یافت نشد")
            val cue = copy(meta.cue) ?: error("فایل ${meta.cue} داخل OBB یافت نشد")
            val cover = copy(meta.cover)

            // Core can be bundled inside the APK (jniLibs) or shipped in the OBB.
            val bundled = File(context.applicationInfo.nativeLibraryDir, "libswanstation.so")
            val core = if (bundled.isFile) bundled else copy("libswanstation.so")?.also {
                it.setExecutable(true)
            }

            return GameMeta.Extracted(bin, cue, cover, core)
        }
    }
}
