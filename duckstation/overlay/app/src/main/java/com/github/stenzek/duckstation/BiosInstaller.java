package com.github.stenzek.duckstation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Imports every 512 KB BIOS image in the shared BIOS pack into DuckStation. */
public class BiosInstaller {
    public static class Result {
        public final int imported;
        public final int skipped;

        Result(int imported, int skipped) {
            this.imported = imported;
            this.skipped = skipped;
        }
    }

    public static Result install(File zipFile) throws Exception {
        if (!AndroidHostInterface.hasInstance())
            throw new Exception("DuckStation host interface is not ready");

        ZipFile zip = new ZipFile(zipFile);
        int imported = 0;
        int skipped = 0;
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory())
                    continue;
                byte[] bytes;
                InputStream stream = zip.getInputStream(entry);
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = stream.read(buffer)) > 0)
                        output.write(buffer, 0, read);
                    bytes = output.toByteArray();
                } finally {
                    stream.close();
                }

                if (bytes.length != 512 * 1024) {
                    skipped++;
                    continue;
                }
                // Returns null for invalid or already-imported images; that is fine.
                if (AndroidHostInterface.getInstance().importBIOSImage(bytes) != null)
                    imported++;
                else
                    skipped++;
            }
        } finally {
            zip.close();
        }
        return new Result(imported, skipped);
    }
}
