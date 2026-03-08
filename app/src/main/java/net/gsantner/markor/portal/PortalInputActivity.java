package net.gsantner.markor.portal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Patterns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import net.gsantner.markor.R;
import net.gsantner.markor.format.FormatRegistry;
import net.gsantner.markor.format.markdown.MarkdownTextConverter;
import net.gsantner.markor.frontend.AttachLinkOrFileDialog;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PortalInputActivity extends AppCompatActivity {
    private static final int REQ_MEDIA_PICK = 1001;
    private static final int REQ_RECORD_AUDIO = 1002;
    private static final int REQ_CAMERA_CAPTURE = 1003;
    private static final int REQ_PICK_SAVE_DIR = 1004;
    private static final int REQ_PICK_DRAFT_DIR = 1005;
    private static final int MENU_SAVE_FOLDER = 1;
    private static final int MENU_DRAFT_FOLDER = 2;
    private static final int MENU_TOP_PREVIEW = 3;
    private static final int MENU_TOP_SAVE_DRAFT = 4;
    private static final String DEFAULT_CLASSIFICATION = "quick-note";
    private static final List<String> DEFAULT_TAGS = Arrays.asList("idea", "todo", "reference", "meeting", "experiment");
    private static final List<String> DEFAULT_CLASSIFICATIONS = Arrays.asList(
            DEFAULT_CLASSIFICATION,
            "journal-entry",
            "project-thought",
            "food-diary"
    );

    private final Handler _handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
    private final PortalAudioRecorderController _recorder = new PortalAudioRecorderController();
    private final Runnable _previewRunnable = this::refreshMarkdownPreview;
    private final Runnable _recordPulseRunnable = new Runnable() {
        @Override
        public void run() {
            if (_recordPulseRing == null || !_recorder.isRecording()) {
                return;
            }
            final float nextAlpha = _recordPulseRing.getAlpha() < 0.38f ? 0.92f : 0.18f;
            final float nextScale = _recordPulseRing.getScaleX() < 1.08f ? 1.28f : 1f;
            _recordPulseRing.animate().alpha(nextAlpha).scaleX(nextScale).scaleY(nextScale).setDuration(420).start();
            _handler.postDelayed(this, 460);
        }
    };

    private DrawerLayout _drawerRoot;
    private View _mainContent;
    private View _contentColumn;
    private MaterialToolbar _toolbar;
    private EditText _titleInput;
    private EditText _editor;
    private WebView _previewWeb;
    private TextView _status;
    private ChipGroup _quickTagsGroup;
    private View _formatScroll;
    private View _attachmentStrip;
    private LinearLayout _attachmentList;
    private LinearLayout _classificationList;
    private View _classificationDrawer;
    private View _publishArc;
    private View _sendFlashOverlay;
    private View _recordPulseRing;
    private View _swipeChevronTop;
    private AppCompatImageButton _recordButton;
    private MaterialButton _openClassificationButton;
    private MenuItem _previewMenuItem;
    private ObjectAnimator _swipeChevronTopAnimator;

    private PortalStorage _storage;
    private PortalSessionRepository _repo;
    private PortalTagManager _tagManager;
    private PortalTagStore _tagStore;

    private File _sessionFile;
    private File _pendingCameraFile;
    private File _activeRecordingFile;
    private long _recordStartedAt;
    private boolean _renderMarkdown;
    private boolean _keyboardVisible;
    private boolean _suppressEditorAutoFormat;
    private int _editorChangeStart;
    private int _editorChangeBefore;
    private int _editorChangeCount;
    private float _swipeDownX;
    private float _swipeDownY;
    private final List<String> _selectedTags = new ArrayList<>();
    private String _classificationSlug = DEFAULT_CLASSIFICATION;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.portal_activity_input);

        _storage = new PortalStorage(this);
        _repo = new PortalSessionRepository(_storage);
        _tagManager = new PortalTagManager();
        _tagStore = new PortalTagStore(this);

        bindViews();
        resolveSessionFromIntent();
        loadSession();
        _renderMarkdown = false;
        applyRenderMode();
        refreshDateTime();
        refreshStatus();
        renderQuickTags();
        refreshAttachmentDrawer();
        refreshClassificationDrawer();

        final String action = getIntent() != null ? getIntent().getAction() : PortalActions.ACTION_TEXT;
        if (PortalActions.ACTION_AUDIO.equals(action)) {
            _editor.post(this::toggleRecordingFromMainButton);
        } else if (PortalActions.ACTION_MEDIA.equals(action)) {
            _editor.post(this::openMediaPicker);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSession(false);
    }

    @Override
    protected void onDestroy() {
        _handler.removeCallbacksAndMessages(null);
        _handler.removeCallbacks(_recordPulseRunnable);
        stopSwipeHintPulse();
        if (_recorder.isRecording()) {
            _recorder.stop(false);
        }
        if (_pendingCameraFile != null && _pendingCameraFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            _pendingCameraFile.delete();
        }
        super.onDestroy();
    }

    private void bindViews() {
        _drawerRoot = findViewById(R.id.portal_drawer_root);
        _mainContent = findViewById(R.id.portal_main_content);
        _contentColumn = findViewById(R.id.portal_content_column);
        _toolbar = findViewById(R.id.portal_top_toolbar);
        _titleInput = findViewById(R.id.portal_title);
        _editor = findViewById(R.id.portal_editor);
        _previewWeb = findViewById(R.id.portal_preview_web);
        _status = findViewById(R.id.portal_status);
        _quickTagsGroup = findViewById(R.id.portal_quick_tags_group);
        _formatScroll = findViewById(R.id.portal_format_scroll);
        _attachmentStrip = findViewById(R.id.portal_attachment_strip);
        _attachmentList = findViewById(R.id.portal_attachment_list);
        _classificationList = findViewById(R.id.portal_classification_list);
        _classificationDrawer = findViewById(R.id.portal_classification_drawer);
        _publishArc = findViewById(R.id.portal_publish_arc);
        _sendFlashOverlay = findViewById(R.id.portal_send_flash_overlay);
        _recordPulseRing = findViewById(R.id.portal_record_pulse_ring);
        _swipeChevronTop = findViewById(R.id.portal_swipe_chevron_top);
        _recordButton = findViewById(R.id.portal_action_record);
        if (_recordButton != null) {
            _recordButton.setBackground(null);
            _recordButton.setPadding(0, 0, 0, 0);
        }

        final MaterialButton camera = findViewById(R.id.portal_action_camera);
        final MaterialButton gallery = findViewById(R.id.portal_action_gallery);
        final MaterialButton fmtHeading = findViewById(R.id.portal_format_heading);
        final MaterialButton fmtHeading2 = findViewById(R.id.portal_format_heading2);
        final MaterialButton fmtBold = findViewById(R.id.portal_format_bold);
        final MaterialButton fmtItalic = findViewById(R.id.portal_format_italic);
        final MaterialButton fmtBullets = findViewById(R.id.portal_format_bullets);
        final MaterialButton fmtNumbers = findViewById(R.id.portal_format_numbers);
        final MaterialButton fmtQuote = findViewById(R.id.portal_format_quote);
        final MaterialButton fmtUnderline = findViewById(R.id.portal_format_underline);
        final MaterialButton fmtLink = findViewById(R.id.portal_format_link);
        final MaterialButton fmtCode = findViewById(R.id.portal_format_code);
        final MaterialButton addCustomClassification = findViewById(R.id.portal_class_add_custom);
        final MaterialButton openSettings = findViewById(R.id.portal_open_settings_button);
        final MaterialButton openClassification = findViewById(R.id.portal_open_classification_button);
        final View bottomToolbar = findViewById(R.id.portal_bottom_toolbar);
        _openClassificationButton = openClassification;

        _toolbar.getMenu().clear();
        _previewMenuItem = _toolbar.getMenu().add(Menu.NONE, MENU_TOP_PREVIEW, Menu.NONE, R.string.portal_render_markdown);
        _previewMenuItem.setIcon(android.R.drawable.ic_menu_view);
        _previewMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        final MenuItem saveDraftMenuItem = _toolbar.getMenu().add(Menu.NONE, MENU_TOP_SAVE_DRAFT, Menu.NONE, R.string.save);
        saveDraftMenuItem.setIcon(android.R.drawable.ic_menu_save);
        saveDraftMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        _toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_TOP_PREVIEW) {
                _renderMarkdown = !_renderMarkdown;
                _storage.setRenderMarkdownEnabled(_renderMarkdown);
                applyRenderMode();
                return true;
            }
            if (item.getItemId() == MENU_TOP_SAVE_DRAFT) {
                saveSession(true);
                return true;
            }
            return false;
        });
        _toolbar.setNavigationOnClickListener(v -> showSettingsMenu(v));
        if (_toolbar.getNavigationIcon() != null) {
            _toolbar.getNavigationIcon().setTint(ContextCompat.getColor(this, R.color.white));
        }
        _toolbar.setNavigationContentDescription(R.string.portal_settings);
        _toolbar.setOnClickListener(v -> startActivity(new Intent(this, PortalSessionBrowserActivity.class)));

        _editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                _editorChangeStart = start;
                _editorChangeBefore = count;
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                _editorChangeStart = start;
                _editorChangeBefore = before;
                _editorChangeCount = count;
            }
            @Override public void afterTextChanged(Editable s) {
                maybeApplyEditorAutoFormatting(s);
                schedulePreviewRefresh();
            }
        });
        _titleInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                schedulePreviewRefresh();
            }
        });

        _recordButton.setOnClickListener(v -> toggleRecordingFromMainButton());
        _recordButton.setOnLongClickListener(v -> {
            openAudioFilePicker();
            return true;
        });
        camera.setOnClickListener(v -> openCameraCapture());
        gallery.setOnClickListener(v -> openMediaPicker());
        addCustomClassification.setOnClickListener(v -> showAddClassificationDialog());
        openSettings.setOnClickListener(this::showSettingsMenu);
        openClassification.setOnClickListener(v -> _drawerRoot.openDrawer(GravityCompat.END));
        fmtHeading.setOnClickListener(v -> toggleHeadingAtSelection());
        fmtHeading2.setOnClickListener(v -> toggleHeadingAtSelection(2));
        fmtBold.setOnClickListener(v -> wrapSelection("**", "**"));
        fmtItalic.setOnClickListener(v -> wrapSelection("*", "*"));
        fmtBullets.setOnClickListener(v -> prefixLines("- "));
        fmtNumbers.setOnClickListener(v -> prefixNumberedLines());
        fmtQuote.setOnClickListener(v -> prefixLines("> "));
        fmtUnderline.setOnClickListener(v -> wrapSelection("<u>", "</u>"));
        fmtLink.setOnClickListener(v -> formatLinkAtSelection());
        fmtCode.setOnClickListener(v -> wrapBlock("```\n", "\n```"));
        bottomToolbar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    _swipeDownX = event.getRawX();
                    _swipeDownY = event.getRawY();
                    updatePublishArc(0f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    final float dyMove = Math.max(0f, _swipeDownY - event.getRawY());
                    updatePublishArc(Math.min(1f, dyMove / dp(120)));
                    return true;
                case MotionEvent.ACTION_UP:
                    final float dx = event.getRawX() - _swipeDownX;
                    final float dy = event.getRawY() - _swipeDownY;
                    updatePublishArc(0f);
                    if (-dy > dp(50) && Math.abs(dy) > Math.abs(dx) * 1.15f) {
                        publishNote();
                        return true;
                    }
                    return Math.abs(dx) > 0 || Math.abs(dy) > 0;
                default:
                    updatePublishArc(0f);
                    return true;
            }
        });

        _previewWeb.getSettings().setJavaScriptEnabled(false);
        _previewWeb.getSettings().setAllowFileAccess(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
            _previewWeb.getSettings().setAllowFileAccessFromFileURLs(true);
            _previewWeb.getSettings().setAllowUniversalAccessFromFileURLs(true);
        }
        _previewWeb.setBackgroundColor(ContextCompat.getColor(this, R.color.background));
        startSwipeHintPulse();
        refreshPreviewActionState();
        _drawerRoot.setScrimColor(Color.TRANSPARENT);
        expandClassificationDrawerSwipeZone();
        _drawerRoot.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                _editor.clearFocus();
                _editor.setCursorVisible(false);
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                _editor.setCursorVisible(true);
            }
        });

        final View contentRoot = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (v, insets) -> {
            applyKeyboardState(insets.isVisible(WindowInsetsCompat.Type.ime()));
            return insets;
        });
        contentRoot.post(() -> {
            final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(contentRoot);
            applyKeyboardState(insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()));
        });
    }

    private void applyKeyboardState(boolean keyboardVisible) {
        if (_keyboardVisible == keyboardVisible) {
            return;
        }
        _keyboardVisible = keyboardVisible;
        if (_attachmentStrip != null) {
            _attachmentStrip.setVisibility((_renderMarkdown || keyboardVisible) ? View.GONE : (_attachmentList.getChildCount() > 0 ? View.VISIBLE : View.GONE));
        }
        final View bottomToolbar = findViewById(R.id.portal_bottom_toolbar);
        if (bottomToolbar != null) {
            bottomToolbar.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
        }
        if (_contentColumn != null) {
            final int bottom = keyboardVisible ? 0 : dp(156);
            _contentColumn.setPadding(
                    _contentColumn.getPaddingLeft(),
                    _contentColumn.getPaddingTop(),
                    _contentColumn.getPaddingRight(),
                    bottom
            );
        }
    }

    private void attachSwipeOpener(@Nullable View target) {
        if (target == null) {
            return;
        }
        target.setOnTouchListener((v, event) -> {
            final int classificationWidth = _classificationDrawer == null ? 0 : _classificationDrawer.getWidth();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    _swipeDownX = event.getRawX();
                    _swipeDownY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    final float moveDx = event.getRawX() - _swipeDownX;
                    final float moveDy = event.getRawY() - _swipeDownY;
                    if (Math.abs(moveDx) > 6f && Math.abs(moveDx) > Math.abs(moveDy)) {
                        if (moveDx < 0 && classificationWidth > 0 && _classificationDrawer != null) {
                            final float drag = Math.min(classificationWidth, Math.max(dp(10), Math.abs(moveDx)));
                            _classificationDrawer.setTranslationX(classificationWidth - drag);
                            return true;
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    final float dx = event.getRawX() - _swipeDownX;
                    final float dy = event.getRawY() - _swipeDownY;
                    if (Math.abs(dx) > dp(72) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        if (dx < 0) {
                            if (_classificationDrawer != null) {
                                _classificationDrawer.setTranslationX(0f);
                            }
                            _drawerRoot.openDrawer(GravityCompat.END);
                            return true;
                        }
                    }
                    resetDrawerTranslations();
                    return true;
                default:
                    resetDrawerTranslations();
                    return true;
            }
        });
    }

    private void resetDrawerTranslations() {
        if (_classificationDrawer != null) {
            _classificationDrawer.setTranslationX(0f);
        }
    }

    private void expandClassificationDrawerSwipeZone() {
        if (_drawerRoot == null) {
            return;
        }
        try {
            final Field rightDraggerField = DrawerLayout.class.getDeclaredField("mRightDragger");
            rightDraggerField.setAccessible(true);
            final Object rightDragger = rightDraggerField.get(_drawerRoot);
            if (rightDragger == null) {
                return;
            }
            final Field edgeSizeField = rightDragger.getClass().getDeclaredField("mEdgeSize");
            edgeSizeField.setAccessible(true);
            final int current = edgeSizeField.getInt(rightDragger);
            final int target = Math.max(current, getResources().getDisplayMetrics().widthPixels / 3);
            edgeSizeField.setInt(rightDragger, target);
        } catch (Exception ignored) {
        }
    }

    private void updatePublishArc(float progress) {
        if (_publishArc == null) {
            return;
        }
        _publishArc.setTranslationY(-dp(12) * progress);
        _publishArc.setScaleX(1f + (progress * 0.08f));
        _publishArc.setScaleY(1f + (progress * 0.12f));
    }

    private void resolveSessionFromIntent() {
        final String path = getIntent() != null ? getIntent().getStringExtra(PortalActions.EXTRA_SESSION_PATH) : null;
        File session = (path != null) ? new File(path) : null;
        if (session == null || !session.exists()) {
            try {
                session = _repo.createSession();
            } catch (Exception e) {
                Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        _sessionFile = session;
    }

    private void loadSession() {
        if (_sessionFile == null || !_sessionFile.isFile()) {
            return;
        }
        final String content = _repo.readContent(_sessionFile);
        final List<String> parsedTags = _tagManager.parseAllTags(content);
        _selectedTags.clear();
        _classificationSlug = DEFAULT_CLASSIFICATION;
        for (String tag : parsedTags) {
            if (isClassificationTag(tag)) {
                _classificationSlug = toClassificationSlug(tag);
            } else {
                _selectedTags.add(tag);
            }
        }
        if (TextUtils.isEmpty(_classificationSlug)) {
            _classificationSlug = DEFAULT_CLASSIFICATION;
        }
        final String cleaned = stripManagedMetadata(content);
        _titleInput.setText(extractTitle(cleaned));
        _editor.setText(stripLeadingTitle(cleaned));
        _editor.setSelection(_editor.getText().length());
    }

    private String stripManagedMetadata(@NonNull String content) {
        String cleaned = content.replaceAll("(?m)^Tags:\\s*(?:#[a-zA-Z0-9_-]+\\s*)*$\\n?", "");
        if (cleaned.startsWith("---\n")) {
            final int end = cleaned.indexOf("\n---", 4);
            if (end > 0) {
                String frontMatter = cleaned.substring(4, end).replaceAll("(?m)^tags:.*$\\n?", "").trim();
                final int contentStart = cleaned.indexOf('\n', end + 1);
                final String remainder = contentStart >= 0 ? cleaned.substring(contentStart + 1) : "";
                if (frontMatter.isEmpty()) {
                    cleaned = remainder;
                } else {
                    cleaned = "---\n" + frontMatter + "\n---\n\n" + remainder;
                }
            }
        }
        cleaned = stripAttachmentMarkup(cleaned);
        return cleaned.replaceFirst("^\\s+", "");
    }

    private void saveSession(boolean notify) {
        if (_sessionFile == null) {
            return;
        }
        final String content = buildContentForSave();
        final boolean ok = _repo.saveContent(_sessionFile, content);
        if (ok) {
            final List<String> usedTags = new ArrayList<>(_selectedTags);
            if (!TextUtils.isEmpty(_classificationSlug)) {
                usedTags.add(toClassificationTag(_classificationSlug));
            }
            _tagStore.recordTagUse(usedTags);
            refreshAttachmentDrawer();
        }
        if (notify) {
            Toast.makeText(this, ok ? R.string.saved : R.string.could_not_save_file, Toast.LENGTH_SHORT).show();
        }
    }

    private String buildContentForSave() {
        final List<String> tags = new ArrayList<>(new LinkedHashSet<>(_selectedTags));
        if (!TextUtils.isEmpty(_classificationSlug)) {
            tags.add(toClassificationTag(_classificationSlug));
        }
        final String title = _titleInput.getText() == null ? "" : _titleInput.getText().toString().trim();
        final String body = _editor.getText() == null ? "" : _editor.getText().toString();
        final String withTitle = title.isEmpty() ? body : "# " + title + "\n\n" + body;
        final String withAttachments = withTitle + buildAttachmentMarkdown();
        return _tagManager.applyTags(withAttachments, tags);
    }

    private String stripAttachmentMarkup(@NonNull String content) {
        return PortalAttachmentPreviewHelper.stripLegacyAttachmentMarkup(content);
    }

    private String buildAttachmentMarkdown() {
        if (_sessionFile == null) {
            return "";
        }
        final File[] files = _storage.getAttachmentDirForSession(_sessionFile).listFiles();
        if (files == null || files.length == 0) {
            return "";
        }
        final List<File> sorted = new ArrayList<>(Arrays.asList(files));
        Collections.sort(sorted, Comparator.comparing(File::getName));
        final StringBuilder out = new StringBuilder();
        out.append("\n\n");
        for (File file : sorted) {
            final String rel = _storage.relativeToSession(_sessionFile, file);
            final String title = GsFileUtils.getFilenameWithoutExtension(file);
            if (isImageFile(file)) {
                out.append("![").append(title).append("](").append(rel).append(")\n\n");
            } else {
                out.append("<audio src='").append(rel).append("' controls><a href='").append(rel).append("'>")
                        .append(title).append("</a></audio>\n\n");
            }
        }
        return out.toString().trim().isEmpty() ? "" : out.toString();
    }

    private void refreshDateTime() {
        if (_sessionFile != null && _sessionFile.exists()) {
            _toolbar.setTitle(_dateFormat.format(new Date(_sessionFile.lastModified())));
        } else {
            _toolbar.setTitle(_dateFormat.format(new Date()));
        }
    }

    private void refreshStatus() {
        if (_recorder.isRecording()) {
            final long elapsed = Math.max(0, System.currentTimeMillis() - _recordStartedAt);
            final long sec = elapsed / 1000;
            _status.setVisibility(View.VISIBLE);
            _status.setText(getString(R.string.portal_recording_status, sec / 60, sec % 60));
        } else {
            _status.setText("");
            _status.setVisibility(View.GONE);
        }
        updateRecordButtonState();
    }

    private int countAttachments() {
        if (_sessionFile == null) {
            return 0;
        }
        final File[] files = _storage.getAttachmentDirForSession(_sessionFile).listFiles();
        return files == null ? 0 : files.length;
    }

    private void renderQuickTags() {
        _quickTagsGroup.removeAllViews();
        final LinkedHashSet<String> visibleTags = new LinkedHashSet<>();
        for (String selected : _selectedTags) {
            if (!isClassificationTag(selected)) {
                visibleTags.add(selected);
            }
        }
        final List<String> topTags = new ArrayList<>();
        for (String tag : _tagStore.getTopTags(10)) {
            if (!isClassificationTag(tag)) {
                topTags.add(tag);
            }
        }
        if (topTags.isEmpty()) {
            topTags.addAll(DEFAULT_TAGS);
        }
        visibleTags.addAll(topTags);
        visibleTags.addAll(DEFAULT_TAGS);
        final Chip addChip = new Chip(this);
        addChip.setText("+");
        addChip.setEnsureMinTouchTargetSize(false);
        addChip.setChipMinHeight(dp(34));
        addChip.setMinHeight(dp(34));
        addChip.setMinimumHeight(dp(34));
        addChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        addChip.setChipStartPadding(dp(8));
        addChip.setChipEndPadding(dp(8));
        addChip.setChipStrokeWidth(dp(1));
        addChip.setChipBackgroundColor(ColorStateList.valueOf(0x00000000));
        addChip.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.portal_chip_unselected_stroke)));
        addChip.setTextColor(ContextCompat.getColor(this, R.color.portal_chip_unselected_text));
        addChip.setOnClickListener(v -> showAddTagDialog());
        _quickTagsGroup.addView(addChip);
        for (String tag : visibleTags) {
            final Chip chip = new Chip(this);
            chip.setText(tag);
            chip.setCheckable(false);
            chip.setChecked(false);
            chip.setCheckedIconVisible(false);
            chip.setChipIconVisible(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setChipMinHeight(dp(34));
            chip.setMinHeight(dp(34));
            chip.setMinimumHeight(dp(34));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setChipStartPadding(dp(12));
            chip.setChipEndPadding(dp(12));
            chip.setTextStartPadding(dp(8));
            chip.setTextEndPadding(dp(8));
            chip.setChipStrokeWidth(dp(1));
            final boolean selected = _selectedTags.contains(tag);
            chip.setChipBackgroundColor(ColorStateList.valueOf(selected
                    ? ContextCompat.getColor(this, R.color.accent)
                    : ContextCompat.getColor(this, R.color.portal_chip_unselected_bg)));
            chip.setChipStrokeColor(ColorStateList.valueOf(selected
                    ? 0x00F04B4B
                    : ContextCompat.getColor(this, R.color.portal_chip_unselected_stroke)));
            chip.setTextColor(selected
                    ? ContextCompat.getColor(this, R.color.white)
                    : ContextCompat.getColor(this, R.color.portal_chip_unselected_text));
            chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            chip.setOnClickListener(v -> toggleTagSelection(tag));
            chip.setOnLongClickListener(v -> {
                showDeleteTagDialog(tag);
                return true;
            });
            _quickTagsGroup.addView(chip);
        }
    }

    private void toggleTagSelection(@NonNull String tag) {
        if (_selectedTags.contains(tag)) {
            _selectedTags.remove(tag);
        } else {
            _selectedTags.add(tag);
        }
        renderQuickTags();
        schedulePreviewRefresh();
    }

    private void showAddTagDialog() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.portal_add_tag_hint));
        new AlertDialog.Builder(this)
                .setTitle(R.string.tags)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final List<String> normalized = _tagManager.normalize(Collections.singletonList(input.getText() == null ? "" : input.getText().toString()));
                    if (!normalized.isEmpty()) {
                        final String tag = normalized.get(0);
                        if (!isClassificationTag(tag) && !_selectedTags.contains(tag)) {
                            _selectedTags.add(tag);
                            renderQuickTags();
                            schedulePreviewRefresh();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeleteTagDialog(@NonNull String tag) {
        showPortalConfirmDialog(
                getString(R.string.delete),
                "Delete tag \"" + tag + "\"?",
                "Delete",
                () -> {
                    _selectedTags.remove(tag);
                    _tagStore.removeTag(tag);
                    renderQuickTags();
                    schedulePreviewRefresh();
                }
        );
    }

    private String extractTitle(@NonNull String body) {
        final String trimmed = body.replaceFirst("^\\s+", "");
        if (!trimmed.startsWith("# ")) {
            return "";
        }
        final int lineEnd = trimmed.indexOf('\n');
        if (lineEnd < 0) {
            return trimmed.substring(2).trim();
        }
        return trimmed.substring(2, lineEnd).trim();
    }

    private String stripLeadingTitle(@NonNull String body) {
        final String trimmed = body.replaceFirst("^\\s+", "");
        if (!trimmed.startsWith("# ")) {
            return trimmed;
        }
        final int firstBreak = trimmed.indexOf('\n');
        if (firstBreak < 0) {
            return "";
        }
        int start = firstBreak + 1;
        while (start < trimmed.length() && trimmed.charAt(start) == '\n') {
            start++;
        }
        return trimmed.substring(start);
    }

    private void toggleHeadingAtSelection() {
        toggleHeadingAtSelection(1);
    }

    private void toggleHeadingAtSelection(int level) {
        final Editable e = _editor.getText();
        final String marker = level <= 1 ? "# " : "## ";
        int start = Math.max(0, _editor.getSelectionStart());
        while (start > 0 && e.charAt(start - 1) != '\n') {
            start--;
        }
        if (e.toString().startsWith(marker, start)) {
            e.delete(start, start + marker.length());
        } else if (level > 1 && e.toString().startsWith("# ", start)) {
            e.replace(start, start + 2, marker);
        } else {
            e.insert(start, marker);
        }
    }

    private void wrapSelection(@NonNull String prefix, @NonNull String suffix) {
        final Editable e = _editor.getText();
        int start = Math.max(0, _editor.getSelectionStart());
        int end = Math.max(0, _editor.getSelectionEnd());
        if (start > end) {
            final int tmp = start;
            start = end;
            end = tmp;
        }
        if (start == end) {
            e.insert(start, prefix + suffix);
            _editor.setSelection(start + prefix.length());
            return;
        }
        e.insert(end, suffix);
        e.insert(start, prefix);
        _editor.setSelection(end + prefix.length() + suffix.length());
    }

    private void wrapBlock(@NonNull String prefix, @NonNull String suffix) {
        final Editable e = _editor.getText();
        int start = Math.max(0, _editor.getSelectionStart());
        int end = Math.max(0, _editor.getSelectionEnd());
        if (start > end) {
            final int tmp = start;
            start = end;
            end = tmp;
        }
        if (start == end) {
            e.insert(start, prefix + suffix);
            _editor.setSelection(start + prefix.length());
            return;
        }
        e.insert(end, suffix);
        e.insert(start, prefix);
        _editor.setSelection(end + prefix.length() + suffix.length());
    }

    private void prefixLines(@NonNull String prefix) {
        final Editable e = _editor.getText();
        int start = Math.max(0, _editor.getSelectionStart());
        int end = Math.max(0, _editor.getSelectionEnd());
        if (start > end) {
            final int tmp = start;
            start = end;
            end = tmp;
        }
        final String selected = e.subSequence(start, end).toString();
        if (selected.isEmpty()) {
            e.insert(start, prefix);
            _editor.setSelection(start + prefix.length());
            return;
        }
        final String[] lines = selected.split("\\n", -1);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(prefix).append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        e.replace(start, end, out.toString());
        _editor.setSelection(start + out.length());
    }

    private void prefixNumberedLines() {
        final Editable e = _editor.getText();
        int start = Math.max(0, _editor.getSelectionStart());
        int end = Math.max(0, _editor.getSelectionEnd());
        if (start > end) {
            final int tmp = start;
            start = end;
            end = tmp;
        }
        final String selected = e.subSequence(start, end).toString();
        if (selected.isEmpty()) {
            e.insert(start, "1. ");
            return;
        }
        final String[] lines = selected.split("\\n", -1);
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(i + 1).append(". ").append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        e.replace(start, end, out.toString());
    }

    private void maybeApplyEditorAutoFormatting(@NonNull Editable e) {
        if (_suppressEditorAutoFormat) {
            return;
        }
        if (_editorChangeCount > _editorChangeBefore) {
            final int end = Math.min(e.length(), _editorChangeStart + _editorChangeCount);
            final String inserted = e.subSequence(Math.max(0, _editorChangeStart), end).toString();
            if (inserted.contains("\n")) {
                continueListOnNewline(e);
            }
            return;
        }
        if (_editorChangeBefore > _editorChangeCount) {
            maybeRemoveEmptyListMarkerOnBackspace(e);
        }
    }

    private void continueListOnNewline(@NonNull Editable e) {
        final int cursor = Math.max(0, _editor.getSelectionStart());
        final int currentLineStart = findLineStart(e, cursor);
        final int previousLineEnd = Math.max(0, currentLineStart - 1);
        final int previousLineStart = findLineStart(e, previousLineEnd);
        final String previousLine = e.subSequence(previousLineStart, previousLineEnd).toString();

        String prefix = null;
        java.util.regex.Matcher bullet = java.util.regex.Pattern.compile("^(\\s*[-*+]\\s).+").matcher(previousLine);
        if (bullet.matches()) {
            prefix = bullet.group(1);
        } else {
            java.util.regex.Matcher numbered = java.util.regex.Pattern.compile("^(\\s*)(\\d+)\\.\\s+.+").matcher(previousLine);
            if (numbered.matches()) {
                final int next = Integer.parseInt(numbered.group(2)) + 1;
                prefix = numbered.group(1) + next + ". ";
            }
        }
        if (TextUtils.isEmpty(prefix)) {
            return;
        }
        _suppressEditorAutoFormat = true;
        try {
            e.insert(cursor, prefix);
            _editor.setSelection(cursor + prefix.length());
        } finally {
            _suppressEditorAutoFormat = false;
        }
    }

    private void maybeRemoveEmptyListMarkerOnBackspace(@NonNull Editable e) {
        final int cursor = Math.max(0, _editor.getSelectionStart());
        final int lineStart = findLineStart(e, cursor);
        int lineEnd = cursor;
        while (lineEnd < e.length() && e.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        final String line = e.subSequence(lineStart, lineEnd).toString();
        if (!line.matches("^\\s*([-*+]|\\d+\\.)$")) {
            return;
        }
        _suppressEditorAutoFormat = true;
        try {
            e.delete(lineStart, lineEnd);
            _editor.setSelection(lineStart);
        } finally {
            _suppressEditorAutoFormat = false;
        }
    }

    private int findLineStart(@NonNull Editable e, int index) {
        int start = Math.max(0, Math.min(index, e.length()));
        while (start > 0 && e.charAt(start - 1) != '\n') {
            start--;
        }
        return start;
    }

    private void formatLinkAtSelection() {
        final Editable e = _editor.getText();
        int start = Math.max(0, _editor.getSelectionStart());
        int end = Math.max(0, _editor.getSelectionEnd());
        if (start == end) {
            while (start > 0 && e.charAt(start - 1) != '\n') {
                start--;
            }
            while (end < e.length() && e.charAt(end) != '\n') {
                end++;
            }
        } else if (start > end) {
            final int tmp = start;
            start = end;
            end = tmp;
        }
        final String selected = e.subSequence(start, end).toString().trim();
        if (selected.isEmpty()) {
            return;
        }
        final String[] pieces = selected.split("\\s+");
        String url = null;
        String title = "";
        for (int i = pieces.length - 1; i >= 0; i--) {
            if (Patterns.WEB_URL.matcher(pieces[i]).matches()) {
                url = pieces[i];
                title = selected.substring(0, selected.lastIndexOf(url)).trim();
                break;
            }
        }
        if (TextUtils.isEmpty(url) && Patterns.WEB_URL.matcher(selected).matches()) {
            url = selected;
        }
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (TextUtils.isEmpty(title)) {
            title = url;
        }
        final String formatted = AttachLinkOrFileDialog.formatLink(title, url, FormatRegistry.FORMAT_MARKDOWN);
        e.replace(start, end, formatted);
        _editor.setSelection(start + formatted.length());
    }

    private void refreshAttachmentDrawer() {
        _attachmentList.removeAllViews();
        if (_sessionFile == null) {
            _attachmentStrip.setVisibility(View.GONE);
            refreshStatus();
            return;
        }
        final File[] files = _storage.getAttachmentDirForSession(_sessionFile).listFiles();
        if (files == null || files.length == 0) {
            _attachmentStrip.setVisibility(View.GONE);
            refreshStatus();
            return;
        }
        _attachmentStrip.setVisibility(_renderMarkdown ? View.GONE : View.VISIBLE);
        final List<File> sorted = new ArrayList<>(Arrays.asList(files));
        Collections.sort(sorted, Comparator.comparing(File::getName));
        for (File file : sorted) {
            _attachmentList.addView(buildAttachmentRow(file));
        }
        refreshStatus();
    }

    private View buildAttachmentRow(@NonNull File file) {
        if (isImageFile(file)) {
            final FrameLayout frame = new FrameLayout(this);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(236), dp(176));
            lp.rightMargin = 12;
            frame.setLayoutParams(lp);
            frame.setForegroundGravity(Gravity.TOP | Gravity.END);

            final ImageView preview = new ImageView(this);
            final FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            preview.setLayoutParams(previewParams);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImageBitmap(loadImagePreview(file));
            preview.setOnClickListener(v -> showImagePreview(file));
            preview.setBackground(createRoundedBackground(0xFF111827, 24));

            final ImageButton delete = new ImageButton(this);
            final FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
            deleteParams.topMargin = dp(10);
            deleteParams.rightMargin = dp(10);
            delete.setLayoutParams(deleteParams);
            delete.setImageResource(R.drawable.ic_delete_black_24dp);
            delete.setBackground(createRoundedBackground(ContextCompat.getColor(this, R.color.accent), 20));
            delete.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white)));
            delete.setScaleType(ImageView.ScaleType.CENTER);
            delete.setOnClickListener(v -> deleteAttachment(file));

            frame.addView(preview);
            frame.addView(delete);
            return frame;
        }

        final LinearLayout row = new LinearLayout(this);
        final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(dp(268), ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.rightMargin = 12;
        row.setLayoutParams(rowParams);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(16), dp(16), dp(14));
        row.setBackground(createAudioAttachmentBackground());
        row.setOnClickListener(v -> showAudioPreview(file));

        final LinearLayout topRow = new LinearLayout(this);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setOrientation(LinearLayout.HORIZONTAL);

        final ImageButton play = new ImageButton(this);
        final LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        playParams.rightMargin = dp(12);
        play.setLayoutParams(playParams);
        play.setImageResource(android.R.drawable.ic_media_play);
        play.setBackground(createRoundedBackground(0x33FFFFFF, 21));
        play.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white)));
        play.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        play.setOnClickListener(v -> showAudioPreview(file));

        final PortalAudioVisualizerView visualizer = new PortalAudioVisualizerView(this);
        final LinearLayout.LayoutParams visualizerParams = new LinearLayout.LayoutParams(0, dp(56), 1f);
        visualizer.setLayoutParams(visualizerParams);
        visualizer.setOnClickListener(v -> showAudioPreview(file));

        final ImageButton delete = new ImageButton(this);
        final LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        deleteParams.leftMargin = dp(10);
        delete.setLayoutParams(deleteParams);
        delete.setImageResource(R.drawable.ic_delete_black_24dp);
        delete.setBackground(createRoundedBackground(0x24FFFFFF, 18));
        delete.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white)));
        delete.setOnClickListener(v -> deleteAttachment(file));

        final TextView label = new TextView(this);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        labelParams.topMargin = dp(12);
        label.setLayoutParams(labelParams);
        label.setText(file.getName());
        label.setTextColor(ContextCompat.getColor(this, R.color.white));
        label.setTextSize(13f);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setOnClickListener(v -> showAudioPreview(file));

        topRow.addView(play);
        topRow.addView(visualizer);
        topRow.addView(delete);
        row.addView(topRow);
        row.addView(label);
        return row;
    }

    private void deleteAttachment(@NonNull File file) {
        if (_sessionFile == null || !file.exists()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        saveSession(false);
        refreshAttachmentDrawer();
        schedulePreviewRefresh();
    }

    private void refreshClassificationDrawer() {
        _classificationList.removeAllViews();
        final Set<String> all = new LinkedHashSet<>(DEFAULT_CLASSIFICATIONS);
        all.removeAll(_storage.getHiddenDefaultClassifications());
        all.addAll(_storage.getCustomClassifications());
        if (!TextUtils.isEmpty(_classificationSlug)) {
            all.add(_classificationSlug);
        }
        for (String slug : all) {
            _classificationList.addView(buildClassificationButton(slug));
        }
        if (_openClassificationButton != null) {
            _openClassificationButton.setText(TextUtils.isEmpty(_classificationSlug)
                    ? getString(R.string.portal_open_classification)
                    : humanizeSlug(_classificationSlug));
        }
    }

    private View buildClassificationButton(@NonNull String slug) {
        final MaterialButton button = new MaterialButton(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        lp.leftMargin = 0;
        lp.rightMargin = 0;
        button.setLayoutParams(lp);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp(54));
        button.setPadding(dp(22), 0, dp(22), 0);
        button.setText(humanizeSlug(slug));
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setCornerRadius(dp(27));
        final boolean selected = slug.equals(_classificationSlug);
        if (selected) {
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
            button.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.background)));
            button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)));
            button.setStrokeWidth(dp(1));
            button.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        }
        button.setOnClickListener(v -> {
            _classificationSlug = slug;
            refreshClassificationDrawer();
            refreshStatus();
            schedulePreviewRefresh();
            _drawerRoot.closeDrawer(GravityCompat.END);
        });
        button.setOnLongClickListener(v -> {
            showDeleteClassificationDialog(slug);
            return true;
        });
        return button;
    }

    private void showAddClassificationDialog() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.portal_add_classification_hint));
        new AlertDialog.Builder(this)
                .setTitle(R.string.portal_add_classification)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String slug = normalizeSlug(input.getText() == null ? "" : input.getText().toString());
                    if (!slug.isEmpty()) {
                        if (DEFAULT_CLASSIFICATIONS.contains(slug)) {
                            _storage.restoreDefaultClassification(slug);
                        } else {
                            _storage.recordCustomClassification(slug);
                        }
                        _classificationSlug = slug;
                        refreshClassificationDrawer();
                        refreshStatus();
                        schedulePreviewRefresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeleteClassificationDialog(@NonNull String slug) {
        showPortalConfirmDialog(
                getString(R.string.delete),
                "Delete note type \"" + humanizeSlug(slug) + "\"?",
                "Delete",
                () -> {
                    if (DEFAULT_CLASSIFICATIONS.contains(slug)) {
                        _storage.hideDefaultClassification(slug);
                    } else {
                        _storage.removeCustomClassification(slug);
                    }
                    if (slug.equals(_classificationSlug)) {
                        _classificationSlug = getFallbackClassification(slug);
                    }
                    refreshClassificationDrawer();
                    refreshStatus();
                    schedulePreviewRefresh();
                }
        );
    }

    private void showSettingsMenu(@NonNull View anchor) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.setBackgroundColor(0x660B1020);
        overlay.setOnClickListener(v -> dialog.dismiss());

        final LinearLayout card = new LinearLayout(this);
        final FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        final int margin = dp(12);
        cardParams.leftMargin = margin;
        cardParams.rightMargin = margin;
        card.setLayoutParams(cardParams);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        card.setBackground(createRoundedBackground(ContextCompat.getColor(this, R.color.portal_drawer_surface), 28));
        card.setClickable(true);

        final TextView title = new TextView(this);
        title.setText(R.string.portal_settings);
        title.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        final View underline = new View(this);
        final LinearLayout.LayoutParams underlineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        underlineParams.topMargin = dp(10);
        underline.setLayoutParams(underlineParams);
        underline.setBackgroundColor(0xCCFFFFFF);

        final LinearLayout pills = new LinearLayout(this);
        final LinearLayout.LayoutParams pillsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pillsParams.topMargin = dp(16);
        pills.setLayoutParams(pillsParams);
        pills.setOrientation(LinearLayout.VERTICAL);

        pills.addView(createSettingsSheetButton(R.string.portal_save_folder, () -> {
            dialog.dismiss();
            openDirectoryPicker(REQ_PICK_SAVE_DIR);
        }));
        pills.addView(createSettingsSheetButton(R.string.portal_draft_folder, () -> {
            dialog.dismiss();
            openDirectoryPicker(REQ_PICK_DRAFT_DIR);
        }));

        card.addView(title);
        card.addView(underline);
        card.addView(pills);
        overlay.addView(card);
        dialog.setContentView(overlay);
        dialog.show();
    }

    private void showPortalConfirmDialog(@NonNull String titleText,
                                         @NonNull String messageText,
                                         @NonNull String confirmText,
                                         @NonNull Runnable onConfirm) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.setBackgroundColor(0x660B1020);
        overlay.setOnClickListener(v -> dialog.dismiss());

        final LinearLayout card = new LinearLayout(this);
        final FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        final int margin = dp(24);
        cardParams.leftMargin = margin;
        cardParams.rightMargin = margin;
        card.setLayoutParams(cardParams);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        card.setBackground(createRoundedBackground(ContextCompat.getColor(this, R.color.portal_drawer_surface), 28));
        card.setClickable(true);

        final TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        final TextView message = new TextView(this);
        final LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        messageParams.topMargin = dp(10);
        message.setLayoutParams(messageParams);
        message.setText(messageText);
        message.setTextColor(ContextCompat.getColor(this, R.color.secondary_text));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);

        final LinearLayout actions = new LinearLayout(this);
        final LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionsParams.topMargin = dp(18);
        actions.setLayoutParams(actionsParams);
        actions.setGravity(Gravity.END);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        final MaterialButton cancel = new MaterialButton(this);
        cancel.setText(android.R.string.cancel);
        cancel.setTextColor(ContextCompat.getColor(this, R.color.secondary_text));
        cancel.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        cancel.setRippleColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.portal_chip_unselected_stroke)));
        cancel.setOnClickListener(v -> dialog.dismiss());

        final MaterialButton confirm = new MaterialButton(this);
        final LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        confirmParams.leftMargin = dp(8);
        confirm.setLayoutParams(confirmParams);
        confirm.setText(confirmText);
        confirm.setTextColor(ContextCompat.getColor(this, R.color.white));
        confirm.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
        confirm.setCornerRadius(dp(20));
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });

        actions.addView(cancel);
        actions.addView(confirm);
        card.addView(title);
        card.addView(message);
        card.addView(actions);
        overlay.addView(card);
        dialog.setContentView(overlay);
        dialog.show();
    }

    @NonNull
    private View createSettingsSheetButton(int textRes, @NonNull Runnable onClick) {
        final TextView button = new TextView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        lp.bottomMargin = dp(12);
        button.setLayoutParams(lp);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setText(textRes);
        button.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        button.setBackground(createRoundedStrokeBackground(
                adjustColor(ContextCompat.getColor(this, R.color.portal_drawer_surface), isDarkModeEnabled() ? 0.92f : 0.97f),
                0x33FFFFFF,
                22,
                1
        ));
        button.setClickable(true);
        button.setFocusable(true);
        button.setForeground(ContextCompat.getDrawable(this, android.R.drawable.list_selector_background));
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    @NonNull
    private GradientDrawable createRoundedStrokeBackground(int fillColor, int strokeColor, int radiusDp, int strokeDp) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int adjustColor(int color, float factor) {
        final int alpha = Color.alpha(color);
        final int red = Math.max(0, Math.min(255, Math.round(Color.red(color) * factor)));
        final int green = Math.max(0, Math.min(255, Math.round(Color.green(color) * factor)));
        final int blue = Math.max(0, Math.min(255, Math.round(Color.blue(color) * factor)));
        return Color.argb(alpha, red, green, blue);
    }

    private void openDirectoryPicker(int requestCode) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    private void publishNote() {
        playSendFlash();
        if (_recorder.isRecording()) {
            stopRecordingAndAttach();
        }
        saveSession(true);
        try {
            if (_sessionFile != null && _sessionFile.exists()) {
                _storage.exportSessionToSendRoot(_sessionFile);
            }
        } catch (Exception e) {
            Toast.makeText(this, R.string.could_not_save_file, Toast.LENGTH_SHORT).show();
        }
        try {
            _sessionFile = _repo.createSession();
            _titleInput.setText("");
            _editor.setText("");
            _selectedTags.clear();
            _classificationSlug = DEFAULT_CLASSIFICATION;
            refreshDateTime();
            refreshStatus();
            renderQuickTags();
            refreshAttachmentDrawer();
            refreshClassificationDrawer();
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void playSendFlash() {
        if (_sendFlashOverlay == null) {
            return;
        }
        _sendFlashOverlay.animate().cancel();
        _sendFlashOverlay.setVisibility(View.VISIBLE);
        _sendFlashOverlay.setAlpha(0f);
        _sendFlashOverlay.animate()
                .alpha(0.55f)
                .setDuration(180)
                .withEndAction(() -> _sendFlashOverlay.animate()
                        .alpha(0.22f)
                        .setDuration(220)
                        .withEndAction(() -> _sendFlashOverlay.animate()
                                .alpha(0.44f)
                                .setDuration(220)
                                .withEndAction(() -> _sendFlashOverlay.animate()
                                        .alpha(0f)
                                        .setDuration(380)
                                        .withEndAction(() -> _sendFlashOverlay.setVisibility(View.GONE))
                                        .start())
                                .start())
                        .start())
                .start();
    }

    private void applyRenderMode() {
        _editor.setVisibility(_renderMarkdown ? View.GONE : View.VISIBLE);
        _previewWeb.setVisibility(_renderMarkdown ? View.VISIBLE : View.GONE);
        _titleInput.setVisibility(View.VISIBLE);
        _formatScroll.setVisibility(View.VISIBLE);
        _attachmentStrip.setVisibility(_renderMarkdown ? View.GONE : (_attachmentList.getChildCount() > 0 ? View.VISIBLE : View.GONE));
        refreshPreviewActionState();
        refreshMarkdownPreview();
    }

    private void refreshPreviewActionState() {
        if (_previewMenuItem == null || _previewMenuItem.getIcon() == null) {
            return;
        }
        _previewMenuItem.getIcon().setTint(ContextCompat.getColor(
                this,
                _renderMarkdown ? R.color.accent : R.color.white
        ));
    }

    private void schedulePreviewRefresh() {
        _handler.removeCallbacks(_previewRunnable);
        _handler.postDelayed(_previewRunnable, 220);
    }

    private void refreshMarkdownPreview() {
        if (!_renderMarkdown || _previewWeb == null || _sessionFile == null) {
            return;
        }
        try {
            final String rawBodySource = PortalAttachmentPreviewHelper.stripLegacyAttachmentMarkup(
                    _editor.getText() == null ? "" : _editor.getText().toString()
            );
            final String bodySource = normalizeQuoteBlocksForPreview(rawBodySource);
            String htmlBody = MarkdownTextConverter.flexmarkRenderer.render(
                    MarkdownTextConverter.flexmarkParser.parse(bodySource)
            );
            htmlBody = htmlBody.replaceAll("(?s)<p>&gt;\\s*(.*?)</p>", "<blockquote><p>$1</p></blockquote>");
            final String attachmentCards = PortalAttachmentPreviewHelper.buildAttachmentCardsHtml(_sessionFile);
            final String backgroundColor = toCssColor(ContextCompat.getColor(this, R.color.background));
            final String textColor = toCssColor(ContextCompat.getColor(this, R.color.primary_text));
            final String accentColor = toCssColor(ContextCompat.getColor(this, R.color.accent));
            final String blockquoteBackground = isDarkModeEnabled() ? "rgba(227,92,99,.14)" : "#fff1ef";
            final String blockquoteText = isDarkModeEnabled() ? "rgba(255,255,255,.88)" : "#374151";
            final String blockquoteShadow = isDarkModeEnabled()
                    ? "0 8px 20px rgba(0,0,0,.26)"
                    : "0 8px 20px rgba(17,24,39,.08)";
            final String html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1' />"
                    + "<style>html,body{margin:0;background:" + backgroundColor + ";}"
                    + "body{font-family:sans-serif;padding:18px;color:" + textColor + ";background:" + backgroundColor + ";}"
                    + ".portal-preview-shell{padding-bottom:28px;}"
                    + ".portal-preview-body{padding:2px 6px 0;}"
                    + ".portal-preview-body,.portal-preview-body p,.portal-preview-body li,.portal-preview-body code,.portal-preview-body pre{color:" + textColor + ";}"
                    + ".portal-preview-body h1,.portal-preview-body h2,.portal-preview-body h3,.portal-preview-body h4,.portal-preview-body h5,.portal-preview-body h6{color:" + textColor + ";text-decoration:none;}"
                    + ".portal-preview-body h1 a,.portal-preview-body h2 a,.portal-preview-body h3 a,.portal-preview-body h4 a,.portal-preview-body h5 a,.portal-preview-body h6 a{color:inherit!important;text-decoration:none!important;pointer-events:none;}"
                    + ".portal-preview-body a{color:" + accentColor + ";}"
                    + ".portal-preview-body blockquote{margin:18px 0!important;padding:16px 18px 16px 20px!important;border:1px solid rgba(227,92,99,.28);border-left:6px solid " + accentColor + ";border-radius:18px;background:" + blockquoteBackground + ";color:" + blockquoteText + ";box-shadow:" + blockquoteShadow + ";}"
                    + ".portal-preview-body blockquote p{margin:0!important;}"
                    + ".portal-preview-body img{max-width:100%;height:auto;border-radius:18px;}"
                    + ".portal-preview-body audio{width:100%;margin-top:16px;}"
                    + ".portal-preview-body pre{white-space:pre-wrap;}"
                    + PortalAttachmentPreviewHelper.buildAttachmentCardsCss()
                    + "</style></head><body><div class='portal-preview-shell'>"
                    + "<section class='portal-preview-body'>" + htmlBody + "</section>"
                    + attachmentCards
                    + "</div></body></html>";
            final String baseUrl = _sessionFile.getParentFile() == null ? null : _sessionFile.getParentFile().toURI().toString();
            _previewWeb.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null);
        } catch (Exception ignored) {
        }
    }

    @NonNull
    private String normalizeQuoteBlocksForPreview(@NonNull String text) {
        final String[] lines = text.split("\\r?\\n", -1);
        final StringBuilder out = new StringBuilder();
        final StringBuilder quote = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("> ")) {
                if (quote.length() > 0) {
                    quote.append("\n");
                }
                quote.append(line.substring(2));
                continue;
            }
            if (quote.length() > 0) {
                out.append("<blockquote>")
                        .append(quote.toString().replace("\n", "<br/>"))
                        .append("</blockquote>\n");
                quote.setLength(0);
            }
            out.append(line).append("\n");
        }
        if (quote.length() > 0) {
            out.append("<blockquote>")
                    .append(quote.toString().replace("\n", "<br/>"))
                    .append("</blockquote>\n");
        }
        return out.toString();
    }

    private void openMediaPicker() {
        final Intent i;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
            startActivityForResult(i, REQ_MEDIA_PICK);
            return;
        } else {
            i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(Intent.createChooser(i, getString(R.string.portal_gallery)), REQ_MEDIA_PICK);
    }

    private void openAudioFilePicker() {
        final BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.Theme_AppCompat_DayNight_Dialog_Rounded);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));

        final TextView title = new TextView(this);
        title.setText("Select Audio");
        title.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        final TextView subtitle = new TextView(this);
        final LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(6);
        subtitle.setLayoutParams(subtitleParams);
        subtitle.setTextColor(ContextCompat.getColor(this, R.color.secondary_text));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);

        final ListView listView = new ListView(this);
        final LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420)
        );
        listParams.topMargin = dp(14);
        listView.setLayoutParams(listParams);
        listView.setDivider(new ColorDrawable(ContextCompat.getColor(this, R.color.portal_divider)));
        listView.setDividerHeight(1);
        listView.setBackgroundColor(Color.TRANSPARENT);
        listView.setCacheColorHint(Color.TRANSPARENT);

        final File storageRoot = Environment.getExternalStorageDirectory();
        final File[] currentDir = new File[]{storageRoot};
        final List<File> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                labels
        );
        listView.setAdapter(adapter);

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                labels.clear();
                entries.clear();
                final File dir = currentDir[0];
                subtitle.setText(dir.getAbsolutePath());
                if (dir.getParentFile() != null && dir.getAbsolutePath().startsWith(storageRoot.getAbsolutePath())) {
                    entries.add(dir.getParentFile());
                    labels.add("..");
                }
                final File[] children = dir.listFiles();
                if (children != null) {
                    final List<File> folders = new ArrayList<>();
                    final List<File> audioFiles = new ArrayList<>();
                    for (File child : children) {
                        if (!child.canRead() || child.isHidden()) {
                            continue;
                        }
                        if (child.isDirectory()) {
                            folders.add(child);
                        } else if (isAudioFile(child)) {
                            audioFiles.add(child);
                        }
                    }
                    Collections.sort(folders, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                    Collections.sort(audioFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                    for (File folder : folders) {
                        entries.add(folder);
                        labels.add("[Folder] " + folder.getName());
                    }
                    for (File audio : audioFiles) {
                        entries.add(audio);
                        labels.add(audio.getName());
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };

        listView.setOnItemClickListener((parent, view, position, id) -> {
            final File selected = entries.get(position);
            if (selected.isDirectory()) {
                currentDir[0] = selected;
                refresh.run();
            } else {
                dialog.dismiss();
                attachDraftAudioFile(selected);
            }
        });

        refresh.run();
        root.addView(title);
        root.addView(subtitle);
        root.addView(listView);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private void openCameraCapture() {
        if (_sessionFile == null) {
            return;
        }
        try {
            _pendingCameraFile = _storage.createImageFileForSession(_sessionFile);
            final Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, R.string.error_picture_selection, Toast.LENGTH_SHORT).show();
                return;
            }
            final String authority = new GsContextUtils().getFileProvider(this);
            final Uri uri = FileProvider.getUriForFile(this, authority, _pendingCameraFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_picture_selection, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleRecordingFromMainButton() {
        if (_recorder.isRecording()) {
            stopRecordingAndAttach();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        startRecordingNow();
    }

    private void startRecordingNow() {
        if (_sessionFile == null) {
            return;
        }
        try {
            _activeRecordingFile = _storage.createAudioFileForSession(_sessionFile);
            _recorder.start(_activeRecordingFile);
            _recordStartedAt = System.currentTimeMillis();
            _handler.removeCallbacks(_recordPulseRunnable);
            if (_recordPulseRing != null) {
                _recordPulseRing.setVisibility(View.VISIBLE);
                _recordPulseRing.setAlpha(0.18f);
                _recordPulseRing.setScaleX(1f);
                _recordPulseRing.setScaleY(1f);
            }
            _handler.post(_recordPulseRunnable);
            tickRecordingStatus();
            refreshStatus();
        } catch (Exception e) {
            Toast.makeText(this, R.string.record_audio, Toast.LENGTH_SHORT).show();
            _activeRecordingFile = null;
        }
    }

    private void stopRecordingAndAttach() {
        if (!_recorder.isRecording()) {
            return;
        }
        _recorder.stop(true);
        final File recorded = _recorder.consumeOutputFile();
        _handler.removeCallbacks(_previewRunnable);
        _handler.removeCallbacks(_recordPulseRunnable);
        if (_recordPulseRing != null) {
            _recordPulseRing.animate().cancel();
            _recordPulseRing.setVisibility(View.GONE);
            _recordPulseRing.setAlpha(0.18f);
            _recordPulseRing.setScaleX(1f);
            _recordPulseRing.setScaleY(1f);
        }
        if (recorded != null && recorded.isFile()) {
            attachAudioFile(recorded);
        }
        _activeRecordingFile = null;
        refreshStatus();
        refreshAttachmentDrawer();
    }

    private void tickRecordingStatus() {
        if (!_recorder.isRecording()) {
            return;
        }
        refreshStatus();
        _handler.postDelayed(this::tickRecordingStatus, 250);
    }

    private void updateRecordButtonState() {
        if (_recordButton == null) {
            return;
        }
        _recordButton.setBackgroundColor(Color.TRANSPARENT);
        _recordButton.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white)));
        if (_recorder.isRecording()) {
            if (_recordPulseRing != null) {
                _recordPulseRing.setVisibility(View.VISIBLE);
            }
            _recordButton.setContentDescription(getString(R.string.portal_stop_recording_accessibility));
        } else {
            if (_recordPulseRing != null) {
                _recordPulseRing.animate().cancel();
                _recordPulseRing.setVisibility(View.GONE);
                _recordPulseRing.setAlpha(0.18f);
                _recordPulseRing.setScaleX(1f);
                _recordPulseRing.setScaleY(1f);
            }
            _recordButton.setContentDescription(getString(R.string.record_audio));
        }
    }

    private void startSwipeHintPulse() {
        if (_swipeChevronTop == null) {
            return;
        }
        stopSwipeHintPulse();
        _swipeChevronTopAnimator = ObjectAnimator.ofFloat(_swipeChevronTop, View.ALPHA, 0.30f, 0.70f);
        _swipeChevronTopAnimator.setDuration(1200);
        _swipeChevronTopAnimator.setRepeatCount(ValueAnimator.INFINITE);
        _swipeChevronTopAnimator.setRepeatMode(ValueAnimator.REVERSE);

        _swipeChevronTopAnimator.start();
    }

    private void stopSwipeHintPulse() {
        if (_swipeChevronTopAnimator != null) {
            _swipeChevronTopAnimator.cancel();
            _swipeChevronTopAnimator = null;
        }
    }

    private void showAttachSheet() {
        final BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.Theme_AppCompat_DayNight_Dialog_Rounded);
        final View view = getLayoutInflater().inflate(R.layout.portal_sheet_attach, null, false);
        final View attachImage = view.findViewById(R.id.portal_attach_image);
        final View attachAudio = view.findViewById(R.id.portal_attach_audio);
        attachImage.setOnClickListener(v -> {
            dialog.dismiss();
            openMediaPicker();
        });
        attachAudio.setOnClickListener(v -> {
            dialog.dismiss();
            showDraftAudioPicker();
        });
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private void showDraftAudioPicker() {
        final List<File> allAudio = new ArrayList<>();
        collectAudioFilesRecursive(_storage.getDraftRoot(), allAudio);
        if (!_storage.getDraftRoot().equals(_storage.getSessionsDir())) {
            collectAudioFilesRecursive(_storage.getSessionsDir(), allAudio);
        }
        if (allAudio.isEmpty()) {
            Toast.makeText(this, R.string.empty_directory, Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(allAudio, Comparator.comparingLong(File::lastModified).reversed());
        final int limit = Math.min(50, allAudio.size());
        final String[] labels = new String[limit];
        for (int i = 0; i < limit; i++) {
            final File f = allAudio.get(i);
            labels[i] = f.getName() + "  (" + new Date(f.lastModified()) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.audio)
                .setItems(labels, (dialog, which) -> attachDraftAudioFile(allAudio.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void collectAudioFilesRecursive(@Nullable File root, @NonNull List<File> out) {
        if (root == null || !root.exists()) {
            return;
        }
        if (root.isFile()) {
            final String ext = GsFileUtils.getFilenameExtension(root).toLowerCase(Locale.ENGLISH);
            if (".m4a".equals(ext) || ".mp3".equals(ext) || ".wav".equals(ext) || ".ogg".equals(ext) || ".aac".equals(ext)) {
                out.add(root);
            }
            return;
        }
        final File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (File child : files) {
            collectAudioFilesRecursive(child, out);
        }
    }

    private boolean isAudioFile(@NonNull File file) {
        final String ext = GsFileUtils.getFilenameExtension(file).toLowerCase(Locale.ENGLISH);
        return ".m4a".equals(ext) || ".mp3".equals(ext) || ".wav".equals(ext)
                || ".ogg".equals(ext) || ".aac".equals(ext) || ".flac".equals(ext);
    }

    private void attachDraftAudioFile(@NonNull File source) {
        if (_sessionFile == null) {
            return;
        }
        try {
            final File copied = _storage.copyIntoSessionAttachmentDir(_sessionFile, source);
            attachAudioFile(copied);
            refreshAttachmentDrawer();
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void attachAudioFile(@NonNull File file) {
        if (_sessionFile == null) {
            return;
        }
        saveSession(false);
        refreshAttachmentDrawer();
        schedulePreviewRefresh();
    }

    private void attachImageFile(@NonNull File file) {
        if (_sessionFile == null) {
            return;
        }
        saveSession(false);
        refreshAttachmentDrawer();
        schedulePreviewRefresh();
    }

    private void insertAtCursor(@NonNull String text) {
        final Editable e = _editor.getText();
        int start = Math.max(0, _editor.getSelectionStart());
        int end = Math.max(0, _editor.getSelectionEnd());
        if (start > end) {
            final int tmp = start;
            start = end;
            end = tmp;
        }
        e.replace(start, end, text);
        _editor.setSelection(start + text.length());
    }

    @SuppressLint("Range")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_SAVE_DIR || requestCode == REQ_PICK_DRAFT_DIR) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final String path = resolveTreeUriToPath(data.getData());
                if (!TextUtils.isEmpty(path)) {
                    if (requestCode == REQ_PICK_SAVE_DIR) {
                        _storage.setConfiguredRootPath(path);
                        _storage.ensureWritableRoot();
                    } else {
                        _storage.setConfiguredDraftPath(path);
                        _storage.ensureDraftRoot();
                    }
                    refreshStatus();
                    Toast.makeText(this, path, Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if (_sessionFile == null) {
            return;
        }
        if (requestCode == REQ_CAMERA_CAPTURE) {
            if (resultCode == RESULT_OK && _pendingCameraFile != null && _pendingCameraFile.isFile()) {
                attachImageFile(_pendingCameraFile);
            } else if (_pendingCameraFile != null && _pendingCameraFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                _pendingCameraFile.delete();
            }
            _pendingCameraFile = null;
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_MEDIA_PICK) {
            try {
                if (data.getClipData() != null) {
                    final int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        final Uri uri = data.getClipData().getItemAt(i).getUri();
                        if (uri != null) {
                            copyAndAttachImageUri(uri);
                        }
                    }
                } else if (data.getData() != null) {
                    copyAndAttachImageUri(data.getData());
                }
            } catch (Exception e) {
                Toast.makeText(this, R.string.error_picture_selection, Toast.LENGTH_SHORT).show();
            }
            return;
        }
    }

    private void copyAndAttachImageUri(@NonNull Uri uri) throws Exception {
        String name = "image-" + System.currentTimeMillis() + ".jpg";
        final Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                final int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    final String n = cursor.getString(idx);
                    if (!TextUtils.isEmpty(n)) {
                        name = n;
                    }
                }
            }
            cursor.close();
        }
        final File attachmentDir = _storage.getAttachmentDirForSession(_sessionFile);
        final File dest = GsFileUtils.findNonConflictingDest(attachmentDir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            final byte[] buffer = new byte[8192];
            int read;
            while (in != null && (read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        attachImageFile(dest);
    }

    private boolean isImageFile(@NonNull File file) {
        final String ext = GsFileUtils.getFilenameExtension(file).toLowerCase(Locale.ENGLISH);
        return ".jpg".equals(ext) || ".jpeg".equals(ext) || ".png".equals(ext) || ".webp".equals(ext) || ".gif".equals(ext) || ".heic".equals(ext);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @NonNull
    private GradientDrawable createRoundedBackground(int color, int radiusDp) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    @NonNull
    private GradientDrawable createAudioAttachmentBackground() {
        final GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF5E4545, 0xFF433236}
        );
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    @Nullable
    private Bitmap loadImagePreview(@NonNull File file) {
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Math.min(bounds.outWidth / 480, bounds.outHeight / 480));
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) {
                return null;
            }
            final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            final int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            final Matrix matrix = new Matrix();
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                matrix.postRotate(90f);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                matrix.postRotate(180f);
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                matrix.postRotate(270f);
            }
            if (!matrix.isIdentity()) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            }
            return bmp;
        } catch (Exception ignored) {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        }
    }

    private void showImagePreview(@NonNull File file) {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final ImageView image = new ImageView(this);
        image.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        image.setBackgroundColor(0xFF111827);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageURI(Uri.fromFile(file));
        image.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        final float dx = event.getRawX() - downX;
                        final float dy = event.getRawY() - downY;
                        if (Math.abs(dy) > 120f && Math.abs(dy) > Math.abs(dx)) {
                            dialog.dismiss();
                            return true;
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        dialog.setContentView(image);
        dialog.show();
    }

    private void showAudioPreview(@NonNull File file) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.setBackgroundColor(0x660B1020);
        overlay.setOnClickListener(v -> dialog.dismiss());

        final LinearLayout root = new LinearLayout(this);
        final FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        final int horizontalMargin = dp(28);
        rootParams.leftMargin = horizontalMargin;
        rootParams.rightMargin = horizontalMargin;
        root.setLayoutParams(rootParams);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(18));
        root.setBackground(createAudioAttachmentBackground());
        root.setClickable(true);

        final TextView title = new TextView(this);
        final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        title.setLayoutParams(titleParams);
        title.setText(file.getName());
        title.setTextColor(ContextCompat.getColor(this, R.color.white));
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);

        final PortalAudioVisualizerView visualizerView = new PortalAudioVisualizerView(this);
        final LinearLayout.LayoutParams visualizerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(96)
        );
        visualizerParams.topMargin = dp(16);
        visualizerView.setLayoutParams(visualizerParams);

        final SeekBar seek = new SeekBar(this);
        final LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        seekParams.topMargin = dp(8);
        seek.setLayoutParams(seekParams);
        seek.setThumbTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
        seek.setProgressTintList(ColorStateList.valueOf(0xFFF0B6B0));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x3DFFFFFF));

        final LinearLayout controlsRow = new LinearLayout(this);
        final LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        controlsParams.topMargin = dp(12);
        controlsRow.setLayoutParams(controlsParams);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);

        final ImageButton playPause = new ImageButton(this);
        final LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        playPause.setLayoutParams(playParams);
        playPause.setBackground(createRoundedBackground(0x33FFFFFF, 21));
        playPause.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white)));
        playPause.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playPause.setContentDescription(getString(R.string.play));
        updateAudioPreviewPlayButton(playPause, false);

        final TextView timeLabel = new TextView(this);
        final LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        timeParams.leftMargin = dp(14);
        timeLabel.setLayoutParams(timeParams);
        timeLabel.setTextColor(0xDFFFFFFF);
        timeLabel.setTextSize(13f);
        timeLabel.setGravity(Gravity.END);
        timeLabel.setText("0:00");

        controlsRow.addView(playPause);
        controlsRow.addView(timeLabel);
        root.addView(title);
        root.addView(visualizerView);
        root.addView(seek);
        root.addView(controlsRow);
        overlay.addView(root);
        dialog.setContentView(overlay);

        final MediaPlayer player = new MediaPlayer();
        final Handler uiHandler = new Handler(Looper.getMainLooper());
        final Visualizer[] visualizerRef = new Visualizer[1];
        final Runnable seekSync = new Runnable() {
            @Override
            public void run() {
                if (player.isPlaying()) {
                    seek.setProgress(player.getCurrentPosition());
                    timeLabel.setText(formatPlaybackTime(player.getCurrentPosition(), player.getDuration()));
                    uiHandler.postDelayed(this, 150);
                }
            }
        };

        try {
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            seek.setMax(player.getDuration());
            timeLabel.setText(formatPlaybackTime(0, player.getDuration()));
            visualizerRef[0] = new Visualizer(player.getAudioSessionId());
            visualizerRef[0].setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            visualizerRef[0].setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    visualizerView.updateFft(fft);
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true);
            visualizerRef[0].setEnabled(true);
        } catch (Exception e) {
            dialog.dismiss();
            return;
        }

        playPause.setOnClickListener(v -> {
            if (player.isPlaying()) {
                player.pause();
                updateAudioPreviewPlayButton(playPause, false);
            } else {
                player.start();
                updateAudioPreviewPlayButton(playPause, true);
                uiHandler.post(seekSync);
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    player.seekTo(progress);
                }
                timeLabel.setText(formatPlaybackTime(progress, player.getDuration()));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        player.setOnCompletionListener(mp -> {
            updateAudioPreviewPlayButton(playPause, false);
            seek.setProgress(seek.getMax());
            timeLabel.setText(formatPlaybackTime(player.getDuration(), player.getDuration()));
        });
        dialog.setOnDismissListener(d -> {
            uiHandler.removeCallbacksAndMessages(null);
            if (visualizerRef[0] != null) {
                try {
                    visualizerRef[0].setEnabled(false);
                    visualizerRef[0].release();
                } catch (Exception ignored) {
                }
            }
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
        });
        dialog.show();
    }

    private void updateAudioPreviewPlayButton(@NonNull ImageButton button, boolean isPlaying) {
        button.setImageResource(isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        button.setContentDescription(getString(isPlaying ? R.string.pause : R.string.play));
    }

    @NonNull
    private String formatPlaybackTime(int currentMs, int totalMs) {
        return formatMinutesSeconds(currentMs) + " / " + formatMinutesSeconds(totalMs);
    }

    @NonNull
    private String formatMinutesSeconds(int millis) {
        final int safeMillis = Math.max(0, millis);
        final int totalSeconds = safeMillis / 1000;
        return (totalSeconds / 60) + ":" + String.format(Locale.US, "%02d", totalSeconds % 60);
    }

    @NonNull
    private String getFallbackClassification(@NonNull String deletedSlug) {
        final LinkedHashSet<String> options = new LinkedHashSet<>(DEFAULT_CLASSIFICATIONS);
        options.removeAll(_storage.getHiddenDefaultClassifications());
        options.addAll(_storage.getCustomClassifications());
        options.remove(deletedSlug);
        if (!options.isEmpty()) {
            return options.iterator().next();
        }
        return DEFAULT_CLASSIFICATION;
    }

    private boolean isDarkModeEnabled() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    @NonNull
    private String toCssColor(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    @Nullable
    private String resolveTreeUriToPath(@NonNull Uri treeUri) {
        try {
            final String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId.startsWith("primary:")) {
                final String rel = docId.substring("primary:".length());
                return new File(Environment.getExternalStorageDirectory(), rel).getAbsolutePath();
            }
            if (docId.startsWith("raw:")) {
                return docId.substring("raw:".length());
            }
            final int split = docId.indexOf(':');
            if (split > 0) {
                final String volume = docId.substring(0, split);
                final String rel = docId.substring(split + 1);
                return new File("/storage/" + volume, rel).getAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecordingNow();
            } else {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean isClassificationTag(@NonNull String tag) {
        return tag.startsWith("class-");
    }

    private String toClassificationSlug(@NonNull String tag) {
        return tag.substring("class-".length());
    }

    private String toClassificationTag(@NonNull String slug) {
        return "class-" + normalizeSlug(slug);
    }

    private String normalizeSlug(@NonNull String raw) {
        return raw.trim()
                .toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String humanizeSlug(@NonNull String slug) {
        final String[] parts = slug.split("-");
        final StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
