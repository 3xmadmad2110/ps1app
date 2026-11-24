package com.github.stenzek.duckstation;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/**
 * Small resumable HTTP downloader with pause/resume and progress reporting.
 * Used by GameLauncherActivity to fetch the OBB (and the shared BIOS).
 */
public class DataDownloader {
    public interface Callback {
        void onProgress(int percent, long downloaded, long total, double speedBytesPerSecond);

        void onStatus(String message);

        void onSuccess();

        void onError(String message);
    }

    private final Context context;
    private final String url;
    private final File destination;
    private final String prefKey;
    private final Callback callback;

    private volatile Thread thread;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;

    private volatile long downloadedBytes;
    private volatile long totalBytes = -1L;

    public DataDownloader(Context context, String url, File destination, String prefKey, Callback callback) {
        this.context = context;
        this.url = url;
        this.destination = destination;
        this.prefKey = prefKey;
        this.callback = callback;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("ps1_downloads", Context.MODE_PRIVATE);
    }

    public long savedBytes() {
        return prefs().getLong("bytes_" + prefKey, 0L);
    }

    public boolean hasPartial() {
        return savedBytes() > 0L && destination.isFile() && destination.length() == savedBytes();
    }

    private void persist() {
        prefs().edit()
                .putLong("bytes_" + prefKey, downloadedBytes)
                .putLong("total_" + prefKey, totalBytes)
                .apply();
    }

    private void clear() {
        prefs().edit()
                .remove("bytes_" + prefKey)
                .remove("total_" + prefKey)
                .apply();
        downloadedBytes = 0L;
        totalBytes = -1L;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }

    public void start() {
        if (running)
            return;
        cancelled = false;
        paused = false;
        running = true;
        downloadedBytes = hasPartial() ? savedBytes() : 0L;
        totalBytes = prefs().getLong("total_" + prefKey, -1L);
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ps1-data-download");
        thread.setDaemon(true);
        thread.start();
    }

    public void togglePause() {
        if (!running)
            return;
        if (paused) {
            paused = false;
        } else {
            paused = true;
            HttpURLConnection conn = connection;
            if (conn != null)
                conn.disconnect();
            persist();
            callback.onStatus(context.getString(R.string.ps1_download_paused));
        }
    }

    public void cancel() {
        cancelled = true;
        paused = false;
        HttpURLConnection conn = connection;
        if (conn != null)
            conn.disconnect();
        clear();
        if (destination.exists())
            destination.delete();
        running = false;
    }

    private void loop() {
        int retries = 0;
        try {
            while (!cancelled) {
                while (paused && !cancelled)
                    Thread.sleep(50L);
                if (cancelled)
                    return;

                try {
                    if (downloadChunk()) {
                        clear();
                        callback.onSuccess();
                        return;
                    }
                    if (paused || cancelled)
                        continue;
                } catch (Exception e) {
                    if (cancelled)
                        return;
                    if (paused)
                        continue;
                    persist();
                }

                retries++;
                if (retries > 10) {
                    callback.onError("too many retries");
                    return;
                }
                callback.onStatus(String.format(Locale.US, context.getString(R.string.ps1_download_retry), retries));
                Thread.sleep(1000L);
            }
        } catch (Exception e) {
            if (!cancelled)
                callback.onError(String.valueOf(e.getMessage()));
        } finally {
            running = false;
            connection = null;
        }
    }

    private boolean downloadChunk() throws Exception {
        HttpURLConnection conn = openConnection(url, downloadedBytes);
        connection = conn;
        RandomAccessFile file = null;
        InputStream input = null;
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL)
                throw new IOException("HTTP " + code);

            String range = conn.getHeaderField("Content-Range");
            String lengthHeader = conn.getHeaderField("Content-Length");
            long contentLength = -1L;
            try {
                if (lengthHeader != null)
                    contentLength = Long.parseLong(lengthHeader);
            } catch (NumberFormatException ignored) {
            }

            boolean append = code == HttpURLConnection.HTTP_PARTIAL && downloadedBytes > 0L;
            if (!append) {
                downloadedBytes = 0L;
                persist();
            }

            if (append) {
                totalBytes = parseTotal(range, downloadedBytes, contentLength);
            } else {
                totalBytes = contentLength;
            }

            File parent = destination.getParentFile();
            if (parent != null)
                parent.mkdirs();

            file = new RandomAccessFile(destination, "rw");
            if (append) {
                file.seek(downloadedBytes);
            } else {
                file.setLength(0L);
                file.seek(0L);
            }

            input = conn.getInputStream();
            byte[] buffer = new byte[64 * 1024];
            long lastReportBytes = downloadedBytes;
            long lastReportTime = 0L;
            long lastPersistBytes = downloadedBytes;
            boolean finished = false;

            while (!paused && !cancelled) {
                int read = input.read(buffer);
                if (read < 0) {
                    finished = true;
                    break;
                }
                file.write(buffer, 0, read);
                downloadedBytes += read;

                long now = System.currentTimeMillis();
                if (lastReportTime == 0L || now - lastReportTime >= 400L) {
                    double speed = lastReportTime > 0L
                            ? (downloadedBytes - lastReportBytes) * 1000.0 / (now - lastReportTime)
                            : 0.0;
                    int percent = totalBytes > 0L
                            ? (int) Math.min(100L, downloadedBytes * 100L / totalBytes)
                            : -1;
                    callback.onProgress(percent, downloadedBytes, totalBytes, speed);
                    lastReportBytes = downloadedBytes;
                    lastReportTime = now;
                }
                if (downloadedBytes - lastPersistBytes >= 2L * 1024L * 1024L) {
                    persist();
                    lastPersistBytes = downloadedBytes;
                }
            }
            persist();
            return finished && (totalBytes <= 0L || downloadedBytes >= totalBytes);
        } finally {
            try {
                if (input != null)
                    input.close();
            } catch (Exception ignored) {
            }
            try {
                if (file != null)
                    file.close();
            } catch (Exception ignored) {
            }
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
            if (connection == conn)
                connection = null;
        }
    }

    private HttpURLConnection openConnection(String target, long rangeStart) throws Exception {
        String current = target;
        int redirects = 0;
        while (true) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            conn.setRequestProperty("User-Agent", "PS1App");

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                redirects++;
                if (location == null || location.trim().isEmpty() || redirects > 10)
                    throw new IOException("redirect failed");
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            return conn;
        }
    }

    private long parseTotal(String contentRange, long downloaded, long contentLength) {
        if (contentRange != null && contentRange.contains("/")) {
            try {
                long value = Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1).trim());
                if (value > 0)
                    return value;
            } catch (NumberFormatException ignored) {
            }
        }
        return downloaded > 0L && contentLength > 0L ? downloaded + contentLength : contentLength;
    }

    public static String formatBytes(long value) {
        if (value < 1024L)
            return value + " B";
        double kb = value / 1024.0;
        if (kb < 1024.0)
            return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0)
            return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
