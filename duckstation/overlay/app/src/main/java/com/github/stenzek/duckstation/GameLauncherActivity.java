package com.github.stenzek.duckstation;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * نقطهٔ ورود بستهٔ بازی: دیتا و بایوس را دریافت میکند، آنها را نصب میکند،
 * ایمج دیسک را آماده میسازد و سپس شبیهساز را اجرا میکند. تمام رابط کاربری
 * صرفنظر از زبان دستگاه فارسی است.
 */
public class GameLauncherActivity extends AppCompatActivity {
    private static final int REQUEST_STORAGE = 1;
    private static final int REQUEST_FILE = 3;
    private static final String BIOS_URL =
            "https://github.com/3xmadmad2110/ps1app/releases/download/share-bios/bios-pack.zip";
    private static final String BAZAAR_URL = "https://cafebazaar.ir/";
    private static final float PANEL_UP_DP = -125f;

    // نمای اصلی
    private ImageView coverView;
    private TextView gameNameView;
    private Button playButton;
    private Button downloadButton;
    private Button settingsButton;
    private ImageButton burgerButton;
    private View drawerScrim;
    private LinearLayout drawerView;
    private LinearLayout panelView;
    private LinearLayout stackView;
    private LinearLayout suggestView;
    private LinearLayout suggestRow;
    private LinearLayout brandFooterView;
    private TextView[] footerWords;
    private FrameLayout sparksHost;
    private View blob1;
    private View blob2;
    private View blob3;

    // صفحهها و پنجرهها
    private FrameLayout detailView;
    private ImageView detailCover;
    private TextView detailTitle;
    private TextView[] detailMetas;
    private TextView detailDesc;
    private Button detailBazaar;
    private FrameLayout storeView;
    private GridLayout storeGrid;
    private FrameLayout requestView;
    private FrameLayout contactView;
    private LinearLayout ticketForm;
    private View sheetScrim;
    private LinearLayout sheetView;
    private View settingsScrim;
    private LinearLayout settingsView;
    private View rateScrim;
    private LinearLayout rateBox;
    private FrameLayout exitView;
    private TextView toastView;

    // پنجرهٔ دانلود
    private TextView dlgStatus;
    private TextView dlgPercent;
    private TextView dlgDetail;
    private TextView dlgFile;
    private ProgressBar dlgProgress;
    private Button dlgPause;
    private Button dlgCancel;
    private Button dlgPlay;
    private TextView[] sheetWords;

    // امتیازدهی
    private TextView[] stars;
    private TextView rateHint;
    private Button rateGo;

    // تنظیمات گرافیک
    private Button[] graphicsButtons;

    // دادههای بازیها
    private String[] gameNames;
    private String[] gameMetas;
    private String[] gameDescs;
    private int[] gameHues;

    // وضعیت دانلود
    private String currentStatus = "";
    private String currentDetail = "";
    private int currentPercent = 0;
    private boolean currentIndeterminate = false;
    private boolean downloadEnabled = true;
    private boolean playEnabled = false;
    private boolean pauseEnabled = false;
    private boolean cancelEnabled = false;
    private String currentPhase;
    private String currentFile;

    private DataDownloader obbDownloader;
    private DataDownloader biosDownloader;
    private DataDownloader activeDownloader;
    private boolean busy;
    private boolean launching;
    private boolean menuOpen;

    private final Handler handler = new Handler();
    private final List<Spark> sparks = new ArrayList<>();
    private final List<Animator> ambientAnimators = new ArrayList<>();
    private final List<Animator> wordAnimators = new ArrayList<>();
    private Runnable toastRunnable;
    private Uri pickedFile;

    private static final class Spark {
        final View view;
        final boolean extra;
        final long delay;
        Animator animator;

        Spark(View view, boolean extra, long delay) {
            this.view = view;
            this.extra = extra;
            this.delay = delay;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // زبان شبیهساز قبل از باز شدن هر صفحهای روی فارسی ثابت میشود.
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString("Main/Language", "fa-IR").apply();
        setContentView(R.layout.activity_game_launcher);

        bindViews();
        loadGameData();
        gameNameView.setText(R.string.ps1_game_name_fa);
        setStatus(getString(R.string.ps1_status_need_data));

        setupLauncherButtons();
        setupMenu();
        setupOverlays();
        setupRating();
        buildSuggestions();
        buildStore();
        setupSparks();
        setupAmbientAnimations();
        startWordAnimations(footerWords);
        startWordAnimations(sheetWords);
        applyGraphicsSelection(loadGraphicsSelection());

        if (hasStoragePermissions()) {
            prepare();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
        }
    }

    private void bindViews() {
        coverView = findViewById(R.id.ps1_cover);
        gameNameView = findViewById(R.id.ps1_game_name);
        playButton = findViewById(R.id.ps1_btn_play);
        downloadButton = findViewById(R.id.ps1_btn_download);
        settingsButton = findViewById(R.id.ps1_btn_settings);
        burgerButton = findViewById(R.id.ps1_burger);
        drawerScrim = findViewById(R.id.ps1_scrim);
        drawerView = findViewById(R.id.ps1_drawer);
        panelView = findViewById(R.id.ps1_panel);
        stackView = findViewById(R.id.ps1_stack);
        suggestView = findViewById(R.id.ps1_suggest);
        suggestRow = findViewById(R.id.ps1_suggest_row);
        brandFooterView = findViewById(R.id.ps1_brand_footer);
        footerWords = new TextView[]{
                findViewById(R.id.ps1_word1), findViewById(R.id.ps1_word2),
                findViewById(R.id.ps1_word3), findViewById(R.id.ps1_word4),
                findViewById(R.id.ps1_word5)};
        sparksHost = findViewById(R.id.ps1_sparks);
        blob1 = findViewById(R.id.ps1_blob1);
        blob2 = findViewById(R.id.ps1_blob2);
        blob3 = findViewById(R.id.ps1_blob3);

        detailView = findViewById(R.id.ps1_detail);
        detailCover = findViewById(R.id.ps1_detail_cover);
        detailTitle = findViewById(R.id.ps1_detail_title);
        detailMetas = new TextView[]{
                findViewById(R.id.ps1_detail_meta1), findViewById(R.id.ps1_detail_meta2),
                findViewById(R.id.ps1_detail_meta3), findViewById(R.id.ps1_detail_meta4)};
        detailDesc = findViewById(R.id.ps1_detail_desc);
        detailBazaar = findViewById(R.id.ps1_detail_bazaar);
        storeView = findViewById(R.id.ps1_store);
        storeGrid = findViewById(R.id.ps1_store_grid);
        requestView = findViewById(R.id.ps1_request);
        contactView = findViewById(R.id.ps1_contact);
        ticketForm = findViewById(R.id.ps1_ticket_form);
        sheetScrim = findViewById(R.id.ps1_sheet_scrim);
        sheetView = findViewById(R.id.ps1_sheet);
        settingsScrim = findViewById(R.id.ps1_set_scrim);
        settingsView = findViewById(R.id.ps1_set_sheet);
        rateScrim = findViewById(R.id.ps1_rate_scrim);
        rateBox = findViewById(R.id.ps1_rate_box);
        exitView = findViewById(R.id.ps1_exit);
        toastView = findViewById(R.id.ps1_toast);

        dlgStatus = findViewById(R.id.ps1_dlg_status);
        dlgPercent = findViewById(R.id.ps1_dlg_percent);
        dlgDetail = findViewById(R.id.ps1_dlg_detail);
        dlgFile = findViewById(R.id.ps1_dlg_file);
        dlgProgress = findViewById(R.id.ps1_dlg_progress);
        dlgPause = findViewById(R.id.ps1_dlg_pause);
        dlgCancel = findViewById(R.id.ps1_dlg_cancel);
        dlgPlay = findViewById(R.id.ps1_dlg_play);
        sheetWords = new TextView[]{
                findViewById(R.id.ps1_sheet_word1), findViewById(R.id.ps1_sheet_word2),
                findViewById(R.id.ps1_sheet_word3), findViewById(R.id.ps1_sheet_word4),
                findViewById(R.id.ps1_sheet_word5)};

        stars = new TextView[]{
                findViewById(R.id.ps1_star1), findViewById(R.id.ps1_star2),
                findViewById(R.id.ps1_star3), findViewById(R.id.ps1_star4),
                findViewById(R.id.ps1_star5)};
        rateHint = findViewById(R.id.ps1_rate_hint);
        rateGo = findViewById(R.id.ps1_rate_go);

        graphicsButtons = new Button[]{
                findViewById(R.id.ps1_gfx1), findViewById(R.id.ps1_gfx2),
                findViewById(R.id.ps1_gfx3), findViewById(R.id.ps1_gfx4)};
    }

    private void loadGameData() {
        gameNames = getResources().getStringArray(R.array.ps1_game_names);
        gameMetas = getResources().getStringArray(R.array.ps1_game_metas);
        gameDescs = getResources().getStringArray(R.array.ps1_game_descs);
        gameHues = getResources().getIntArray(R.array.ps1_game_hues);
    }

    private void setupLauncherButtons() {
        findViewById(R.id.ps1_cover_click).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail(0, false);
            }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playEnabled)
                    animateLaunch();
            }
        });
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                hideOverlay(detailView);
                showDownloadSheet();
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                showOverlay(settingsView);
                settingsScrim.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setupMenu() {
        burgerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (menuOpen)
                    closeMenu();
                else
                    openMenu();
            }
        });
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
            }
        });
        findViewById(R.id.ps1_menu_store).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                showOverlay(storeView);
            }
        });
        findViewById(R.id.ps1_menu_request).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                showOverlay(requestView);
            }
        });
        findViewById(R.id.ps1_menu_contact).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                showOverlay(contactView);
            }
        });
        findViewById(R.id.ps1_menu_site).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                showToast(getString(R.string.ps1_site) + " بزودی معرفی میشود.");
            }
        });
        findViewById(R.id.ps1_menu_exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openRating();
            }
        });
    }

    private void openMenu() {
        menuOpen = true;
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.animate().alpha(1f).setDuration(280);
        drawerView.animate().translationX(0f).setDuration(340).setInterpolator(
                new android.view.animation.DecelerateInterpolator());
        burgerButton.setImageResource(R.drawable.ic_exit);
    }

    private void closeMenu() {
        if (!menuOpen)
            return;
        menuOpen = false;
        drawerScrim.animate().alpha(0f).setDuration(280).withEndAction(new Runnable() {
            @Override
            public void run() {
                drawerScrim.setVisibility(View.GONE);
            }
        });
        drawerView.animate().translationX(-dp(320)).setDuration(340);
        burgerButton.setImageResource(R.drawable.ic_menu);
    }

    private void setupOverlays() {
        findViewById(R.id.ps1_detail_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlay(detailView);
                hideOverlay(storeView);
            }
        });
        detailBazaar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(BAZAAR_URL);
            }
        });
        findViewById(R.id.ps1_store_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlay(storeView);
            }
        });
        findViewById(R.id.ps1_req_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlay(requestView);
            }
        });
        findViewById(R.id.ps1_contact_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlay(contactView);
            }
        });

        setupRequestForm();
        setupContactForm();
        setupDownloadSheet();
        setupGraphicsSheet();
    }

    private void setupRequestForm() {
        final TextView error = findViewById(R.id.ps1_req_error);
        final TextView ok = findViewById(R.id.ps1_req_ok);
        final EditText name = findViewById(R.id.ps1_req_name);
        final EditText desc = findViewById(R.id.ps1_req_desc);
        final Button fileButton = findViewById(R.id.ps1_req_file);

        fileButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent,
                        getString(R.string.ps1_req_file_hint)), REQUEST_FILE);
            }
        });
        findViewById(R.id.ps1_req_submit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(name.getText().toString().trim())) {
                    error.setVisibility(View.VISIBLE);
                    ok.setVisibility(View.GONE);
                    name.requestFocus();
                    return;
                }
                error.setVisibility(View.GONE);
                ok.setVisibility(View.VISIBLE);
                name.setText("");
                desc.setText("");
                pickedFile = null;
                ((TextView) findViewById(R.id.ps1_req_file)).setText(R.string.ps1_req_file_hint);
            }
        });
    }

    private void setupContactForm() {
        findViewById(R.id.ps1_contact_tg).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToast(getString(R.string.ps1_toast_telegram));
            }
        });
        findViewById(R.id.ps1_contact_bale).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showToast(getString(R.string.ps1_toast_bale));
            }
        });
        findViewById(R.id.ps1_ticket_toggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ticketForm.setVisibility(ticketForm.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        });
        final EditText title = findViewById(R.id.ps1_ticket_title);
        final EditText body = findViewById(R.id.ps1_ticket_body);
        final TextView error = findViewById(R.id.ps1_ticket_error);
        final TextView ok = findViewById(R.id.ps1_ticket_ok);
        findViewById(R.id.ps1_ticket_submit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(title.getText().toString().trim())
                        || TextUtils.isEmpty(body.getText().toString().trim())) {
                    error.setVisibility(View.VISIBLE);
                    ok.setVisibility(View.GONE);
                    return;
                }
                error.setVisibility(View.GONE);
                ok.setVisibility(View.VISIBLE);
                title.setText("");
                body.setText("");
            }
        });
    }

    private void setupDownloadSheet() {
        dlgPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DataDownloader current = activeDownloader;
                if (current == null || !current.isRunning())
                    return;
                current.togglePause();
                updatePauseLabel();
            }
        });
        dlgCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelDownloads();
                hideDownloadSheet();
            }
        });
        findViewById(R.id.ps1_sheet_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideDownloadSheet();
            }
        });
        sheetScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideDownloadSheet();
            }
        });
        dlgPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideDownloadSheet();
                animateLaunch();
            }
        });
    }

    private void setupGraphicsSheet() {
        findViewById(R.id.ps1_set_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSettingsSheet();
            }
        });
        settingsScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideSettingsSheet();
            }
        });
        for (int i = 0; i < graphicsButtons.length; i++) {
            final int index = i;
            graphicsButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PreferenceManager.getDefaultSharedPreferences(GameLauncherActivity.this)
                            .edit().putInt("ps1_graphics", index).apply();
                    applyGraphicsSelection(index);
                }
            });
        }
    }

    private void setupRating() {
        rateScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideRating();
            }
        });
        for (int i = 0; i < stars.length; i++) {
            final int index = i;
            stars[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int j = 0; j < stars.length; j++) {
                        stars[j].setTextColor(j <= index
                                ? getResources().getColor(android.R.color.holo_orange_light)
                                : getResources().getColor(android.R.color.darker_gray));
                    }
                    String count = String.valueOf(index + 1);
                    rateHint.setText(getString(R.string.ps1_rate_selected, toFa(count)));
                    rateGo.setEnabled(true);
                }
            });
        }
        rateGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(BAZAAR_URL);
            }
        });
        findViewById(R.id.ps1_rate_later).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideRating();
                showOverlay(exitView);
            }
        });
        findViewById(R.id.ps1_exit_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlay(exitView);
            }
        });
    }

    private void buildSuggestions() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 1; i < gameNames.length; i++)
            indices.add(i);
        Collections.shuffle(indices);
        for (int i = 0; i < 3 && i < indices.size(); i++) {
            final int index = indices.get(i);
            View card = makeGameCard(index, (int) dp(92));
            suggestRow.addView(card);
        }
    }

    private void buildStore() {
        for (int i = 1; i < gameNames.length; i++) {
            final int index = i;
            View card = makeGameCard(index, (int) dp(130));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec((i - 1) / 2),
                    GridLayout.spec((i - 1) % 2, 1f));
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.setMargins(0, 0, (int) dp(11), (int) dp(11));
            card.setLayoutParams(lp);
            storeGrid.addView(card);
        }
    }

    private View makeGameCard(final int index, int width) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ps1_row_card);
        card.setPadding((int) dp(8), (int) dp(8), (int) dp(8), (int) dp(8));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail(index, true);
            }
        });

        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageResource(R.drawable.ps1_cover);
        image.setColorFilter(hueFilter(gameHues[index]));
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                width, LinearLayout.LayoutParams.WRAP_CONTENT);
        image.setLayoutParams(imageLp);

        TextView label = new TextView(this);
        label.setText(gameNames[index]);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(0xFFe8edf7);
        label.setTextSize(11);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = (int) dp(7);
        label.setLayoutParams(labelLp);

        card.addView(image);
        card.addView(label);
        return card;
    }

    private ColorMatrixColorFilter hueFilter(float degrees) {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setRotate(0, degrees);
        matrix.setRotate(1, degrees * 0.6f);
        return new ColorMatrixColorFilter(matrix);
    }

    private void openDetail(int index, boolean fromStore) {
        detailTitle.setText(gameNames[index]);
        detailMetas[0].setText(gameMetas[index]);
        detailMetas[1].setText(R.string.ps1_detail_translated);
        detailMetas[1].setTextColor(0xFF22d3ee);
        detailMetas[2].setVisibility(View.GONE);
        detailMetas[3].setVisibility(View.GONE);
        detailDesc.setText(gameDescs[index]);
        detailBazaar.setVisibility(fromStore ? View.VISIBLE : View.GONE);
        if (index == 0) {
            detailMetas[0].setText(R.string.ps1_detail_platform);
            detailMetas[1].setText(R.string.ps1_detail_disc);
            detailMetas[1].setTextColor(0xFFe8edf7);
            detailMetas[2].setText(R.string.ps1_detail_year);
            detailMetas[2].setVisibility(View.VISIBLE);
            detailMetas[3].setText(R.string.ps1_detail_translated);
            detailMetas[3].setTextColor(0xFF22d3ee);
            detailMetas[3].setVisibility(View.VISIBLE);
            detailCover.setColorFilter(null);
            detailDesc.setText(R.string.ps1_detail_desc);
        } else {
            detailCover.setColorFilter(hueFilter(gameHues[index]));
        }
        showOverlay(detailView);
    }

    private void showOverlay(View view) {
        hideAllOverlays(view);
        view.setAlpha(0f);
        view.setTranslationY(dp(36));
        view.setVisibility(View.VISIBLE);
        view.animate().alpha(1f).translationY(0f).setDuration(360)
                .setInterpolator(new android.view.animation.DecelerateInterpolator());
    }

    private void hideOverlay(View view) {
        if (view.getVisibility() != View.VISIBLE)
            return;
        view.animate().alpha(0f).translationY(dp(24)).setDuration(240)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                        view.setTranslationY(0f);
                    }
                });
    }

    private void hideAllOverlays(View keep) {
        View[] all = new View[]{detailView, storeView, requestView, contactView,
                sheetView, settingsView, rateBox, exitView};
        for (View view : all) {
            if (view != keep)
                hideOverlay(view);
        }
        if (keep != sheetView)
            sheetScrim.setVisibility(View.GONE);
        if (keep != settingsView)
            settingsScrim.setVisibility(View.GONE);
        if (keep != rateBox)
            rateScrim.setVisibility(View.GONE);
    }

    private void showDownloadSheet() {
        showOverlay(sheetView);
        sheetScrim.setVisibility(View.VISIBLE);
        syncDownloadState();
        if (!busy && downloadEnabled)
            startDownloads();
    }

    private void hideDownloadSheet() {
        hideOverlay(sheetView);
        sheetScrim.setVisibility(View.GONE);
    }

    private void hideSettingsSheet() {
        hideOverlay(settingsView);
        settingsScrim.setVisibility(View.GONE);
    }

    private void openRating() {
        closeMenu();
        hideAllOverlays(rateBox);
        rateScrim.setVisibility(View.VISIBLE);
        showOverlay(rateBox);
    }

    private void hideRating() {
        hideOverlay(rateBox);
        rateScrim.setVisibility(View.GONE);
    }

    private int loadGraphicsSelection() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getInt("ps1_graphics", 0);
    }

    private void applyGraphicsSelection(int index) {
        for (int i = 0; i < graphicsButtons.length; i++)
            graphicsButtons[i].setBackgroundResource(i == index
                    ? R.drawable.ps1_btn_download : R.drawable.ps1_btn_settings);
    }

    private void showToast(String message) {
        toastView.setText(message);
        toastView.setVisibility(View.VISIBLE);
        toastView.setAlpha(1f);
        if (toastRunnable != null)
            handler.removeCallbacks(toastRunnable);
        toastRunnable = new Runnable() {
            @Override
            public void run() {
                toastView.animate().alpha(0f).setDuration(280).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        toastView.setVisibility(View.GONE);
                        toastView.setAlpha(1f);
                    }
                });
            }
        };
        handler.postDelayed(toastRunnable, 2600);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            showToast("امکان باز کردن پیوند وجود ندارد.");
        }
    }

    @Override
    public void onBackPressed() {
        if (exitView.getVisibility() == View.VISIBLE) {
            hideOverlay(exitView);
        } else if (rateBox.getVisibility() == View.VISIBLE) {
            hideRating();
        } else if (settingsView.getVisibility() == View.VISIBLE) {
            hideSettingsSheet();
        } else if (sheetView.getVisibility() == View.VISIBLE) {
            hideDownloadSheet();
        } else if (requestView.getVisibility() == View.VISIBLE) {
            hideOverlay(requestView);
        } else if (contactView.getVisibility() == View.VISIBLE) {
            hideOverlay(contactView);
        } else if (storeView.getVisibility() == View.VISIBLE) {
            hideOverlay(storeView);
        } else if (detailView.getVisibility() == View.VISIBLE) {
            hideOverlay(detailView);
        } else if (menuOpen) {
            closeMenu();
        } else {
            openRating();
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
            syncDownloadState();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            pickedFile = data.getData();
            String name = displayName(pickedFile);
            ((TextView) findViewById(R.id.ps1_req_file)).setText(name);
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.moveToFirst())
                    name = cursor.getString(index);
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null)
            name = uri.getLastPathSegment();
        return TextUtils.isEmpty(name) ? getString(R.string.ps1_req_file_hint) : name;
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
                setStatus(getString(R.string.ps1_error_prefix) + "خطا در راهاندازی شبیهساز");
                syncDownloadState();
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
        if (busy || launching)
            return;
        File obb = obbFile();
        boolean obbReady = false;
        if (obb != null && obb.isFile() && obb.length() > 0L) {
            try {
                ObbData.readMeta(obb);
                obbReady = true;
            } catch (Exception ignored) {
                // فایل ناقص است و باید از نقطهٔ قطع ادامه پیدا کند.
            }
        }
        boolean biosReady = AndroidHostInterface.hasInstance()
                && AndroidHostInterface.getInstance().hasAnyBIOSImages();
        boolean gameReady = cueFile() != null;

        if (obbReady && biosReady && gameReady) {
            setStatus(getString(R.string.ps1_status_ready));
            setProgress(100, -1, -1);
            downloadEnabled = false;
            playEnabled = true;
        } else {
            setStatus(getString(R.string.ps1_status_need_data));
            setProgress(0, -1, -1);
            downloadEnabled = true;
            playEnabled = false;
        }
        pauseEnabled = false;
        cancelEnabled = false;
        syncDownloadState();
    }

    private void syncDownloadState() {
        dlgStatus.setText(currentStatus);
        dlgDetail.setText(currentDetail);
        dlgPercent.setText(toFa(String.valueOf(Math.max(0, currentPercent))) + "٪");
        dlgProgress.setIndeterminate(currentIndeterminate);
        if (!currentIndeterminate)
            dlgProgress.setProgress(currentPercent);

        downloadButton.setVisibility(downloadEnabled ? View.VISIBLE : View.GONE);
        playButton.setEnabled(playEnabled);
        dlgPause.setEnabled(pauseEnabled);
        dlgCancel.setEnabled(cancelEnabled);
        dlgPlay.setVisibility(playEnabled ? View.VISIBLE : View.GONE);

        LinearLayout.LayoutParams playLp;
        if (downloadEnabled) {
            playLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            playLp.setMargins(0, 0, (int) dp(10), 0);
        } else {
            playLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            playLp.setMargins(0, 0, 0, 0);
        }
        playButton.setLayoutParams(playLp);

        LinearLayout.LayoutParams downloadLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        downloadButton.setLayoutParams(downloadLp);

        updatePauseLabel();
    }

    private void setStatus(String message) {
        currentStatus = message;
        if (dlgStatus != null)
            dlgStatus.setText(message);
    }

    private void setProgress(int percent, long downloaded, long total) {
        setProgress(percent, downloaded, total, -1);
    }

    private void setProgress(int percent, long downloaded, long total, double speed) {
        currentIndeterminate = percent < 0;
        if (!currentIndeterminate)
            currentPercent = Math.max(0, Math.min(100, percent));

        dlgProgress.setIndeterminate(currentIndeterminate);
        if (!currentIndeterminate)
            dlgProgress.setProgress(currentPercent);

        String text;
        if (downloaded >= 0 && total > 0) {
            long speedBytes = speed < 0 ? 0L : (long) speed;
            text = getString(R.string.ps1_detail_format,
                    formatSize(downloaded), formatSize(total), formatSize(speedBytes));
        } else {
            text = getString(R.string.ps1_detail_unknown);
        }
        currentDetail = text;
        dlgDetail.setText(text);
        dlgPercent.setText(toFa(String.valueOf(Math.max(0, currentPercent))) + "٪");
    }

    private void setPhase(int labelRes, String file) {
        currentPhase = getString(labelRes);
        currentFile = file;
        if (currentPhase.equals(getString(R.string.ps1_dlg_phase_bios))) {
            dlgFile.setText(getString(R.string.ps1_status_downloading_bios));
        } else if (currentPhase.equals(getString(R.string.ps1_dlg_phase_import))) {
            dlgFile.setText(getString(R.string.ps1_status_importing_bios));
        } else if (currentPhase.equals(getString(R.string.ps1_dlg_phase_done))) {
            dlgFile.setText(getString(R.string.ps1_dlg_phase_done));
        } else {
            dlgFile.setText(R.string.ps1_dlg_waiting);
        }
    }

    private void updatePauseLabel() {
        int label = activeDownloader != null && activeDownloader.isPaused()
                ? R.string.ps1_resume : R.string.ps1_pause;
        dlgPause.setText(label);
    }

    private void startDownloads() {
        if (busy || !hasStoragePermissions())
            return;
        prepare();
        busy = true;
        downloadEnabled = false;
        playEnabled = false;
        pauseEnabled = true;
        cancelEnabled = true;
        updatePauseLabel();

        final File obb = obbFile();
        if (obb == null) {
            onFailure("پوشهٔ دیتا در دسترس نیست");
            return;
        }

        String dataUrl = getString(R.string.ps1_data_url);
        String biosUrl = getString(R.string.ps1_bios_url);
        if (biosUrl == null || biosUrl.trim().isEmpty())
            biosUrl = BIOS_URL;
        final String finalBiosUrl = biosUrl;

        setPhase(R.string.ps1_dlg_phase_obb, obb.getName());
        setStatus(getString(R.string.ps1_status_downloading_obb));
        obbDownloader = new DataDownloader(this, dataUrl, obb, "ps1_obb", new DataDownloader.Callback() {
            @Override
            public void onProgress(int percent, long downloaded, long total, double speed) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (busy)
                            setProgress(percent, downloaded, total, speed);
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
        syncDownloadState();
    }

    private void startBiosDownload(String url) {
        if (!busy)
            return;
        final File zip = biosZipFile();
        setPhase(R.string.ps1_dlg_phase_bios, zip.getName());
        setStatus(getString(R.string.ps1_status_downloading_bios));
        biosDownloader = new DataDownloader(this, url, zip, "ps1_bios", new DataDownloader.Callback() {
            @Override
            public void onProgress(int percent, long downloaded, long total, double speed) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (busy)
                            setProgress(percent, downloaded, total, speed);
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
        syncDownloadState();
    }

    private void finishSetup(File biosZip) {
        if (!busy)
            return;
        try {
            setPhase(R.string.ps1_dlg_phase_import, biosZip.getName());
            setStatus(getString(R.string.ps1_status_importing_bios));
            BiosInstaller.install(biosZip);

            File obb = obbFile();
            if (obb == null || !obb.isFile())
                throw new Exception("دیتای بازی پیدا نشد");
            setPhase(R.string.ps1_dlg_phase_extract, obb.getName());
            setStatus(getString(R.string.ps1_status_extracting));
            ObbData.extract(obb, gameDirectory());
        } catch (Exception e) {
            onFailure(String.valueOf(e.getMessage()));
            return;
        }

        busy = false;
        activeDownloader = null;
        setPhase(R.string.ps1_dlg_phase_done, "");
        setStatus(getString(R.string.ps1_download_done));
        setProgress(100, -1, -1);
        refreshState();
        syncDownloadState();
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
        syncDownloadState();
    }

    private void onFailure(String message) {
        busy = false;
        activeDownloader = null;
        downloadEnabled = true;
        playEnabled = false;
        pauseEnabled = false;
        cancelEnabled = false;
        setStatus(getString(R.string.ps1_error_prefix) + message);
        syncDownloadState();
    }

    private void animateLaunch() {
        if (launching || busy)
            return;
        launching = true;
        closeMenu();
        hideAllOverlays(null);

        suggestView.animate().alpha(0f).translationY(dp(18)).setDuration(350);
        brandFooterView.animate().alpha(0f).setDuration(350);

        for (Spark spark : sparks) {
            if (!spark.extra)
                continue;
            spark.view.setVisibility(View.VISIBLE);
            if (spark.animator != null)
                spark.animator.cancel();
            spark.animator = sparkAnimation(spark.view, 1800, 0);
            spark.animator.start();
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                spinPanel();
            }
        }, 4000);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                zoomPanel();
            }
        }, 6200);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startEmulation();
            }
        }, 7250);
    }

    private void spinPanel() {
        panelView.setCameraDistance(12000f);
        ObjectAnimator rotation = ObjectAnimator.ofFloat(panelView, "rotationY",
                0f, 25f, 70f, 140f, 240f, 360f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(panelView, "scaleX",
                1f, 1.02f, 1.05f, 1.07f, 1.05f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(panelView, "scaleY",
                1f, 1.02f, 1.05f, 1.07f, 1.05f, 1f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(rotation, scaleX, scaleY);
        set.setDuration(2200);
        set.start();
    }

    private void zoomPanel() {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(panelView, "scaleX", 1f, 6.5f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(panelView, "scaleY", 1f, 6.5f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(panelView, "alpha", 1f, 0f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(950);
        set.setInterpolator(new AccelerateInterpolator(1.4f));
        set.start();
    }

    private void startEmulation() {
        File cue = cueFile();
        if (cue == null || !cue.isFile()) {
            resetLaunchVisuals();
            setStatus(getString(R.string.ps1_error_prefix) + "ایمج دیسک پیدا نشد");
            syncDownloadState();
            return;
        }
        if (!AndroidHostInterface.hasInstance())
            prepare();
        Intent intent = new Intent(this, EmulationActivity.class);
        intent.putExtra("bootPath", cue.getAbsolutePath());
        intent.putExtra("resumeState", false);
        startActivity(intent);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                resetLaunchVisuals();
            }
        }, 200);
    }

    private void resetLaunchVisuals() {
        launching = false;
        panelView.setRotationY(0f);
        panelView.setScaleX(1f);
        panelView.setScaleY(1f);
        panelView.setAlpha(1f);
        suggestView.animate().alpha(1f).translationY(0f).setDuration(350);
        brandFooterView.animate().alpha(1f).setDuration(350);
        for (Spark spark : sparks) {
            if (!spark.extra)
                continue;
            spark.view.setVisibility(View.GONE);
            if (spark.animator != null)
                spark.animator.cancel();
            spark.animator = sparkAnimation(spark.view, 6000, spark.delay);
            spark.animator.start();
        }
    }

    private void setupSparks() {
        sparksHost.post(new Runnable() {
            @Override
            public void run() {
                int width = sparksHost.getWidth();
                int height = sparksHost.getHeight();
                float[] lefts = {0.15f, 0.38f, 0.62f, 0.84f, 0.27f,
                        0.08f, 0.30f, 0.52f, 0.76f, 0.92f, 0.22f, 0.60f,
                        0.45f, 0.12f, 0.36f, 0.58f, 0.80f, 0.95f, 0.20f,
                        0.50f, 0.72f, 0.06f, 0.42f, 0.88f};
                long[] delays = {0, 1500, 3000, 4300, 5100, 400, 900, 1300, 1800, 2200,
                        2700, 3100, 3600, 200, 500, 800, 1100, 1400, 1700, 2000,
                        2300, 2600, 2900, 3200};
                for (int i = 0; i < lefts.length; i++) {
                    View view = new View(GameLauncherActivity.this);
                    view.setBackgroundResource(R.drawable.ps1_spark);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            (int) dp(5), (int) dp(5));
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.leftMargin = (int) (width * lefts[i]);
                    lp.topMargin = (int) Math.max(0, height - dp(12));
                    view.setLayoutParams(lp);
                    boolean extra = i >= 5;
                    if (extra)
                        view.setVisibility(View.GONE);
                    sparksHost.addView(view);
                    Spark spark = new Spark(view, extra, delays[i]);
                    spark.animator = sparkAnimation(view, 6000, delays[i]);
                    spark.animator.start();
                    sparks.add(spark);
                }
            }
        });
    }

    private Animator sparkAnimation(View view, long duration, long startDelay) {
        PropertyValuesHolder alpha = PropertyValuesHolder.ofKeyframe("alpha",
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.2f, 0.95f),
                Keyframe.ofFloat(0.55f, 0.95f),
                Keyframe.ofFloat(0.88f, 0.8f),
                Keyframe.ofFloat(1f, 0f));
        PropertyValuesHolder translation = PropertyValuesHolder.ofKeyframe("translationY",
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.55f, -dp(260)),
                Keyframe.ofFloat(1f, -dp(420)));
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view, alpha, translation);
        animator.setDuration(duration);
        animator.setStartDelay(startDelay);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        return animator;
    }

    private void setupAmbientAnimations() {
        addAmbient(ObjectAnimator.ofFloat(blob1, "translationX", 0f, dp(45)), 10000, true);
        addAmbient(ObjectAnimator.ofFloat(blob1, "translationY", 0f, dp(75)), 10000, true);
        addAmbient(ObjectAnimator.ofFloat(blob2, "translationX", 0f, -dp(35)), 12000, true);
        addAmbient(ObjectAnimator.ofFloat(blob2, "translationY", 0f, -dp(85)), 12000, true);
        addAmbient(ObjectAnimator.ofFloat(blob3, "translationX", 0f, dp(35)), 8500, true);
        addAmbient(ObjectAnimator.ofFloat(blob3, "translationY", 0f, -dp(60)), 8500, true);
    }

    private void addAmbient(ObjectAnimator animator, long duration, boolean reverse) {
        animator.setDuration(duration);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        if (reverse)
            animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.start();
        ambientAnimators.add(animator);
    }

    private void startWordAnimations(TextView[] words) {
        for (int i = 0; i < words.length; i++) {
            PropertyValuesHolder alpha = PropertyValuesHolder.ofKeyframe("alpha",
                    Keyframe.ofFloat(0f, 0f),
                    Keyframe.ofFloat(0.16f, 1f),
                    Keyframe.ofFloat(0.58f, 1f),
                    Keyframe.ofFloat(0.76f, 0f),
                    Keyframe.ofFloat(1f, 0f));
            PropertyValuesHolder translation = PropertyValuesHolder.ofKeyframe("translationY",
                    Keyframe.ofFloat(0f, dp(12)),
                    Keyframe.ofFloat(0.16f, 0f),
                    Keyframe.ofFloat(0.58f, 0f),
                    Keyframe.ofFloat(0.76f, -dp(10)),
                    Keyframe.ofFloat(1f, dp(12)));
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofKeyframe("scaleX",
                    Keyframe.ofFloat(0f, 0.9f),
                    Keyframe.ofFloat(0.16f, 1f),
                    Keyframe.ofFloat(0.58f, 1f),
                    Keyframe.ofFloat(0.76f, 0.97f),
                    Keyframe.ofFloat(1f, 0.9f));
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofKeyframe("scaleY",
                    Keyframe.ofFloat(0f, 0.9f),
                    Keyframe.ofFloat(0.16f, 1f),
                    Keyframe.ofFloat(0.58f, 1f),
                    Keyframe.ofFloat(0.76f, 0.97f),
                    Keyframe.ofFloat(1f, 0.9f));
            ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(words[i],
                    alpha, translation, scaleX, scaleY);
            animator.setDuration(12000);
            animator.setStartDelay(i * 550L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.start();
            wordAnimators.add(animator);
        }
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            long mb = Math.round(bytes / (1024.0 * 1024.0));
            return toFa(String.valueOf(mb)) + " مگابایت";
        }
        if (bytes >= 1024L) {
            long kb = Math.round(bytes / 1024.0);
            return toFa(String.valueOf(kb)) + " کیلوبایت";
        }
        return toFa(String.valueOf(bytes)) + " بایت";
    }

    private String toFa(String value) {
        if (value == null)
            return "";
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            switch (chars[i]) {
                case '0': chars[i] = '۰'; break;
                case '1': chars[i] = '۱'; break;
                case '2': chars[i] = '۲'; break;
                case '3': chars[i] = '۳'; break;
                case '4': chars[i] = '۴'; break;
                case '5': chars[i] = '۵'; break;
                case '6': chars[i] = '۶'; break;
                case '7': chars[i] = '۷'; break;
                case '8': chars[i] = '۸'; break;
                case '9': chars[i] = '۹'; break;
                default: break;
            }
        }
        return new String(chars);
    }

    private float dp(float value) {
        return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        for (Animator animator : ambientAnimators)
            animator.cancel();
        for (Animator animator : wordAnimators)
            animator.cancel();
        for (Spark spark : sparks) {
            if (spark.animator != null)
                spark.animator.cancel();
        }
        if (obbDownloader != null)
            obbDownloader.cancel();
        if (biosDownloader != null)
            biosDownloader.cancel();
        super.onDestroy();
    }
}
