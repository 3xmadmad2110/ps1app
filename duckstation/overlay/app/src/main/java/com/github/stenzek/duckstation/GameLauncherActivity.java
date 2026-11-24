package com.github.stenzek.duckstation;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

/**
 * Entry point for the packaged game. It downloads the OBB and the shared BIOS
 * pack, installs both, extracts the disc image and then boots DuckStation.
 */
public class GameLauncherActivity extends AppCompatActivity {
    private static final int REQUEST_STORAGE = 1;
    private static final int REQUEST_SETTINGS = 2;
    private static final String BIOS_URL =
            "https://github.com/3xmadmad2110/ps1app/releases/download/share-bios/bios-pack.zip";

    private TextView gameNameView;
    private TextView statusView;
    private TextView detailView;
    private ProgressBar progressView;
    private Button downloadButton;
    private Button playButton;
    private Button pauseButton;
    private Button cancelButton;
    private Button settingsButton;

    private DataDownloader obbDownloader;
    private DataDownloader biosDownloader;
    private DataDownloader activeDownloader;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_launcher);

        gameNameView = findViewById(R.id.ps1_game_name);
        statusView = findViewById(R.id.ps1_status);
        detailView = findViewById(R.id.ps1_detail);
        progressView = findViewById(R.id.ps1_progress);
        downloadButton = findViewById(R.id.ps1_btn_download);
        playButton = findViewById(R.id.ps1_btn_play);
        pauseButton = findViewById(R.id.ps1_btn_pause);
        cancelButton = findViewById(R.id.ps1_btn_cancel);
        settingsButton = findViewById(R.id.ps1_btn_settings);

        gameNameView.setText(getApplicationInfo().loadLabel(getPackageManager()));
        setStatus(getString(R.string.ps1_status_need_data));

        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownloads();
            }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchGame();
            }
        });
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DataDownloader current = activeDownloader;
                if (current == null || !current.isRunning())
                    return;
                current.togglePause();
                pauseButton.setText(current.isPaused() ? R.string.ps1_resume : R.string.ps1_pause);
            }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelDownloads();
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(GameLauncherActivity.this, MainActivity.class), REQUEST_SETTINGS);
            }
        });

        if (hasStoragePermissions()) {
            prepare();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE)
            return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prepare();
        } else {
            setStatus(getString(R.string.ps1_error_prefix) +
                    getString(R.string.main_activity_external_storage_permissions_error));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS)
            refreshState();
    }

    private boolean hasStoragePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void prepare() {
        if (!AndroidHostInterface.hasInstance()) {
            if (!AndroidHostInterface.createInstance(this)) {
                setStatus(getString(R.string.ps1_error_prefix) + "DuckStation init failed");
                return;
            }
            AndroidHostInterface.getInstance().setContext(this);
        }
        refreshState();
    }

    private File obbFile() {
        File dir = getObbDir();
        if (dir == null)
            return null;
        String url = getString(R.string.ps1_data_url);
        int versionCode = 1;
        try {
            versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        String name = ObbData.expectedName(getPackageName(), versionCode);
        if (url != null && !url.trim().isEmpty()) {
            String candidate = url.substring(url.lastIndexOf('/') + 1);
            if (candidate.endsWith(".obb") && candidate.startsWith("main."))
                name = candidate;
        }
        return new File(dir, name);
    }

    private File biosZipFile() {
        return new File(getFilesDir(), "bios-pack.zip");
    }

    private File gameDirectory() {
        return new File(getExternalFilesDir(null), "game");
    }

    private File cueFile() {
        File dir = gameDirectory();
        if (!dir.isDirectory())
            return null;
        File[] files = dir.listFiles();
        if (files == null)
            return null;
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase().endsWith(".cue"))
                return file;
        }
        return null;
    }

    private void refreshState() {
        if (busy)
            return;
        boolean obbReady = obbFile() != null && obbFile().isFile();
        boolean biosReady = AndroidHostInterface.hasInstance()
                && AndroidHostInterface.getInstance().hasAnyBIOSImages();
        boolean gameReady = cueFile() != null;

        if (obbReady && biosReady && gameReady) {
            setStatus(getString(R.string.ps1_status_ready));
            setProgress(100, -1, -1);
            downloadButton.setEnabled(false);
            playButton.setEnabled(true);
        } else {
            setStatus(getString(R.string.ps1_status_need_data));
            setProgress(0, -1, -1);
            downloadButton.setEnabled(true);
            playButton.setEnabled(false);
        }
        pauseButton.setEnabled(false);
        cancelButton.setEnabled(false);
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    private void setProgress(int percent, long downloaded, long total) {
        if (percent < 0) {
            progressView.setIndeterminate(true);
        } else {
            progressView.setIndeterminate(false);
            progressView.setProgress(Math.max(0, Math.min(100, percent)));
        }
        if (downloaded >= 0 && total > 0) {
            detailView.setText(String.format(java.util.Locale.US,
                    getString(R.string.ps1_detail_format),
                    DataDownloader.formatBytes(downloaded),
                    DataDownloader.formatBytes(total),
                    "0"));
        } else {
            detailView.setText(R.string.ps1_detail_unknown);
        }
    }

    private void startDownloads() {
        if (busy || !hasStoragePermissions())
            return;
        prepare();
        busy = true;
        downloadButton.setEnabled(false);
        playButton.setEnabled(false);
        pauseButton.setEnabled(true);
        cancelButton.setEnabled(true);
        pauseButton.setText(R.string.ps1_pause);

        final File obb = obbFile();
        if (obb == null) {
            onFailure("OBB directory unavailable");
            return;
        }

        String dataUrl = getString(R.string.ps1_data_url);
        String biosUrl = getString(R.string.ps1_bios_url);
        if (biosUrl == null || biosUrl.trim().isEmpty())
            biosUrl = BIOS_URL;
        final String finalBiosUrl = biosUrl;

        setStatus(getString(R.string.ps1_status_downloading_obb));
        obbDownloader = new DataDownloader(this, dataUrl, obb, "ps1_obb", new DataDownloader.Callback() {
            @Override
            public void onProgress(int percent, long downloaded, long total, double speed) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!busy)
                            return;
                        progressView.setIndeterminate(false);
                        progressView.setProgress(Math.max(0, Math.min(100, percent)));
                        detailView.setText(String.format(java.util.Locale.US,
                                getString(R.string.ps1_detail_format),
                                DataDownloader.formatBytes(downloaded),
                                DataDownloader.formatBytes(total),
                                DataDownloader.formatBytes((long) speed)));
                    }
                });
            }

            @Override
            public void onStatus(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(message);
                    }
                });
            }

            @Override
            public void onSuccess() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startBiosDownload(finalBiosUrl);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onFailure(message);
                    }
                });
            }
        });
        activeDownloader = obbDownloader;
        obbDownloader.start();
    }

    private void startBiosDownload(String url) {
        if (!busy)
            return;
        setStatus(getString(R.string.ps1_status_downloading_bios));
        final File zip = biosZipFile();
        biosDownloader = new DataDownloader(this, url, zip, "ps1_bios", new DataDownloader.Callback() {
            @Override
            public void onProgress(int percent, long downloaded, long total, double speed) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!busy)
                            return;
                        progressView.setIndeterminate(false);
                        progressView.setProgress(Math.max(0, Math.min(100, percent)));
                        detailView.setText(String.format(java.util.Locale.US,
                                getString(R.string.ps1_detail_format),
                                DataDownloader.formatBytes(downloaded),
                                DataDownloader.formatBytes(total),
                                DataDownloader.formatBytes((long) speed)));
                    }
                });
            }

            @Override
            public void onStatus(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(message);
                    }
                });
            }

            @Override
            public void onSuccess() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finishSetup(zip);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onFailure(message);
                    }
                });
            }
        });
        activeDownloader = biosDownloader;
        biosDownloader.start();
    }

    private void finishSetup(File biosZip) {
        if (!busy)
            return;
        try {
            setStatus(getString(R.string.ps1_status_importing_bios));
            BiosInstaller.install(biosZip);

            setStatus(getString(R.string.ps1_status_extracting));
            File obb = obbFile();
            if (obb == null || !obb.isFile())
                throw new Exception("OBB not found");
            ObbData.extract(obb, gameDirectory());
        } catch (Exception e) {
            onFailure(String.valueOf(e.getMessage()));
            return;
        }

        busy = false;
        activeDownloader = null;
        setStatus(getString(R.string.ps1_download_done));
        setProgress(100, -1, -1);
        refreshState();
    }

    private void cancelDownloads() {
        if (obbDownloader != null)
            obbDownloader.cancel();
        if (biosDownloader != null)
            biosDownloader.cancel();
        busy = false;
        activeDownloader = null;
        setStatus(getString(R.string.ps1_download_cancelled));
        refreshState();
    }

    private void onFailure(String message) {
        busy = false;
        activeDownloader = null;
        downloadButton.setEnabled(true);
        pauseButton.setEnabled(false);
        cancelButton.setEnabled(false);
        setStatus(getString(R.string.ps1_error_prefix) + message);
    }

    private void launchGame() {
        File cue = cueFile();
        if (cue == null || !cue.isFile()) {
            setStatus(getString(R.string.ps1_error_prefix) + "Disc image missing");
            return;
        }
        if (!AndroidHostInterface.hasInstance())
            prepare();

        Intent intent = new Intent(this, EmulationActivity.class);
        intent.putExtra("bootPath", cue.getAbsolutePath());
        intent.putExtra("resumeState", false);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        if (obbDownloader != null)
            obbDownloader.cancel();
        if (biosDownloader != null)
            biosDownloader.cancel();
        super.onDestroy();
    }
}
