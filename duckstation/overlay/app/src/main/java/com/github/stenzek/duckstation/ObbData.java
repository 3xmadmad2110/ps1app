package com.github.stenzek.duckstation;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Locates the game OBB, reads its metadata and extracts disc files. */
public class ObbData {
    public static class Meta {
        public final String gameName;
        public final String bin;
        public final String cue;

        Meta(String gameName, String bin, String cue) {
            this.gameName = gameName;
            this.bin = bin;
            this.cue = cue;
        }
    }

    public static class Extracted {
        public final File bin;
        public final File cue;

        Extracted(File bin, File cue) {
            this.bin = bin;
            this.cue = cue;
        }
    }

    public static String expectedName(String packageName, int versionCode) {
        return "main." + versionCode + "." + packageName + ".obb";
    }

    public static File findObb(Context context) {
        File dir = context.getObbDir();
        if (dir == null)
            return null;
        String packageName = context.getPackageName();
        int versionCode;
        try {
            versionCode = context.getPackageManager().getPackageInfo(packageName, 0).versionCode;
        } catch (Exception e) {
            versionCode = 1;
        }
        String prefix = "main." + versionCode + ".";
        String suffix = "." + packageName + ".obb";
        File exact = new File(dir, expectedName(packageName, versionCode));
        if (exact.isFile())
            return exact;
        File[] files = dir.listFiles();
        if (files == null)
            return null;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(prefix) && file.getName().endsWith(suffix))
                return file;
        }
        return null;
    }

    public static Meta readMeta(File obb) throws Exception {
        ZipFile zip = new ZipFile(obb);
        try {
            ZipEntry entry = zip.getEntry("meta.json");
            if (entry == null)
                throw new Exception("meta.json not found in OBB");
            InputStream stream = zip.getInputStream(entry);
            String text;
            try {
                byte[] bytes = new byte[(int) entry.getSize()];
                int read = 0;
                while (read < bytes.length) {
                    int count = stream.read(bytes, read, bytes.length - read);
                    if (count < 0)
                        break;
                    read += count;
                }
                text = new String(bytes, 0, read, "UTF-8");
            } finally {
                stream.close();
            }
            JSONObject json = new JSONObject(text);
            return new Meta(
                    json.optString("gameName", "PS1 Game"),
                    json.getString("bin"),
                    json.getString("cue"));
        } finally {
            zip.close();
        }
    }

    public static Extracted extract(File obb, File target) throws Exception {
        Meta meta = readMeta(obb);
        if (!target.exists())
            target.mkdirs();

        ZipFile zip = new ZipFile(obb);
        try {
            File bin = copyEntry(zip, meta.bin, target);
            File cue = copyEntry(zip, meta.cue, target);
            if (bin == null)
                throw new Exception("BIN file not found in OBB: " + meta.bin);
            if (cue == null)
                throw new Exception("CUE file not found in OBB: " + meta.cue);
            return new Extracted(bin, cue);
        } finally {
            zip.close();
        }
    }

    private static File copyEntry(ZipFile zip, String name, File target) throws Exception {
        if (name == null || name.trim().isEmpty())
            return null;
        ZipEntry entry = zip.getEntry(name);
        if (entry == null)
            return null;
        File out = new File(target, new File(name).getName());
        if (out.exists() && out.length() == entry.getSize())
            return out;
        InputStream input = zip.getInputStream(entry);
        FileOutputStream output = new FileOutputStream(out);
        try {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0)
                output.write(buffer, 0, read);
        } finally {
            output.close();
            input.close();
        }
        return out;
    }
}
