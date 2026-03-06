package net.gsantner.markor.portal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.BitmapFactory;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import net.gsantner.markor.R;
import net.gsantner.markor.format.markdown.MarkdownTextConverter;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    private static final List<String> DEFAULT_TAGS = Arrays.asList("idea", "todo", "reference", "meeting", "experiment");
    private static final List<String> DEFAULT_CLASSIFICATIONS = Arrays.asList(
            "quick-note",
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
            if (_recordButton == null || !_recorder.isRecording()) {
                return;
            }
            final float nextAlpha = _recordButton.getAlpha() < 0.85f ? 1f : 0.68f;
            final float nextScale = _recordButton.getScaleX() < 1.04f ? 1.08f : 1f;
            _recordButton.animate().alpha(nextAlpha).scaleX(nextScale).scaleY(nextScale).setDuration(320).start();
            _handler.postDelayed(this, 360);
        }
    };

    private DrawerLayout _drawerRoot;
    private View _mainContent;
    private MaterialToolbar _toolbar;
    private EditText _titleInput;
    private EditText _editor;
    private WebView _previewWeb;
    private TextView _status;
    private TextView _attachmentEmpty;
    private ChipGroup _quickTagsGroup;
    private View _formatScroll;
    private LinearLayout _attachmentList;
    private LinearLayout _classificationList;
    private View _attachmentDrawer;
    private View _classificationDrawer;
    private View _publishArc;
    private MaterialButton _recordButton;
    private MenuItem _previewMenuItem;

    private PortalStorage _storage;
    private PortalSessionRepository _repo;
    private PortalTagManager _tagManager;
    private PortalTagStore _tagStore;

    private File _sessionFile;
    private File _pendingCameraFile;
    private File _activeRecordingFile;
    private long _recordStartedAt;
    private boolean _renderMarkdown;
    private float _swipeDownX;
    private float _swipeDownY;
    private final List<String> _selectedTags = new ArrayList<>();
    private String _classificationSlug = "";

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
        _toolbar = findViewById(R.id.portal_top_toolbar);
        _titleInput = findViewById(R.id.portal_title);
        _editor = findViewById(R.id.portal_editor);
        _previewWeb = findViewById(R.id.portal_preview_web);
        _status = findViewById(R.id.portal_status);
        _attachmentEmpty = findViewById(R.id.portal_attachment_empty);
        _quickTagsGroup = findViewById(R.id.portal_quick_tags_group);
        _formatScroll = findViewById(R.id.portal_format_scroll);
        _attachmentList = findViewById(R.id.portal_attachment_list);
        _classificationList = findViewById(R.id.portal_classification_list);
        _attachmentDrawer = findViewById(R.id.portal_attachment_drawer);
        _classificationDrawer = findViewById(R.id.portal_classification_drawer);
        _publishArc = findViewById(R.id.portal_publish_arc);
        _recordButton = findViewById(R.id.portal_action_record);

        final MaterialButton camera = findViewById(R.id.portal_action_camera);
        final MaterialButton gallery = findViewById(R.id.portal_action_gallery);
        final MaterialButton fmtHeading = findViewById(R.id.portal_format_heading);
        final MaterialButton fmtBold = findViewById(R.id.portal_format_bold);
        final MaterialButton fmtItalic = findViewById(R.id.portal_format_italic);
        final MaterialButton fmtBullets = findViewById(R.id.portal_format_bullets);
        final MaterialButton fmtNumbers = findViewById(R.id.portal_format_numbers);
        final MaterialButton fmtQuote = findViewById(R.id.portal_format_quote);
        final MaterialButton addCustomClassification = findViewById(R.id.portal_class_add_custom);
        final MaterialButton openSettings = findViewById(R.id.portal_open_settings_button);
        final MaterialButton openAttachments = findViewById(R.id.portal_open_attachments_button);
        final MaterialButton openClassification = findViewById(R.id.portal_open_classification_button);
        final View bottomToolbar = findViewById(R.id.portal_bottom_toolbar);

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
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
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
            showDraftAudioPicker();
            return true;
        });
        camera.setOnClickListener(v -> openCameraCapture());
        gallery.setOnClickListener(v -> openMediaPicker());
        addCustomClassification.setOnClickListener(v -> showAddClassificationDialog());
        openSettings.setOnClickListener(this::showSettingsMenu);
        openAttachments.setOnClickListener(v -> _drawerRoot.openDrawer(GravityCompat.START));
        openClassification.setOnClickListener(v -> _drawerRoot.openDrawer(GravityCompat.END));
        fmtHeading.setOnClickListener(v -> toggleHeadingAtSelection());
        fmtBold.setOnClickListener(v -> wrapSelection("**", "**"));
        fmtItalic.setOnClickListener(v -> wrapSelection("_", "_"));
        fmtBullets.setOnClickListener(v -> prefixLines("- "));
        fmtNumbers.setOnClickListener(v -> prefixNumberedLines());
        fmtQuote.setOnClickListener(v -> prefixLines("> "));
        attachSwipeOpener(_mainContent);
        attachSwipeOpener(_editor);
        attachSwipeOpener(_previewWeb);
        bottomToolbar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    _swipeDownX = event.getRawX();
                    _swipeDownY = event.getRawY();
                    updatePublishArc(0f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    final float dyMove = Math.max(0f, _swipeDownY - event.getRawY());
                    updatePublishArc(Math.min(1f, dyMove / 180f));
                    return true;
                case MotionEvent.ACTION_UP:
                    final float dx = event.getRawX() - _swipeDownX;
                    final float dy = event.getRawY() - _swipeDownY;
                    updatePublishArc(0f);
                    if (-dy > 120f && Math.abs(dy) > Math.abs(dx) * 1.2f) {
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
        _previewWeb.setBackgroundColor(ContextCompat.getColor(this, R.color.background));
        refreshPreviewActionState();
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
        contentRoot.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            final Rect visible = new Rect();
            contentRoot.getWindowVisibleDisplayFrame(visible);
            final int heightDiff = contentRoot.getRootView().getHeight() - visible.height();
            final boolean keyboardVisible = heightDiff > (int) (120 * getResources().getDisplayMetrics().density);
            bottomToolbar.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
        });
    }

    private void attachSwipeOpener(@Nullable View target) {
        if (target == null) {
            return;
        }
        target.setOnTouchListener((v, event) -> {
            final int attachmentWidth = _attachmentDrawer == null ? 0 : _attachmentDrawer.getWidth();
            final int classificationWidth = _classificationDrawer == null ? 0 : _classificationDrawer.getWidth();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    _swipeDownX = event.getRawX();
                    _swipeDownY = event.getRawY();
                    return false;
                case MotionEvent.ACTION_MOVE:
                    final float moveDx = event.getRawX() - _swipeDownX;
                    final float moveDy = event.getRawY() - _swipeDownY;
                    if (Math.abs(moveDx) > 24f && Math.abs(moveDx) > Math.abs(moveDy)) {
                        if (moveDx > 0 && attachmentWidth > 0 && _attachmentDrawer != null) {
                            final float progress = Math.min(1f, moveDx / attachmentWidth);
                            _attachmentDrawer.setTranslationX((-attachmentWidth) + (attachmentWidth * progress));
                        } else if (moveDx < 0 && classificationWidth > 0 && _classificationDrawer != null) {
                            final float progress = Math.min(1f, Math.abs(moveDx) / classificationWidth);
                            _classificationDrawer.setTranslationX(classificationWidth - (classificationWidth * progress));
                        }
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                    final float dx = event.getRawX() - _swipeDownX;
                    final float dy = event.getRawY() - _swipeDownY;
                    if (Math.abs(dx) > 140f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        if (dx > 0) {
                            if (_attachmentDrawer != null) {
                                _attachmentDrawer.setTranslationX(0f);
                            }
                            _drawerRoot.openDrawer(GravityCompat.START);
                        } else {
                            if (_classificationDrawer != null) {
                                _classificationDrawer.setTranslationX(0f);
                            }
                            _drawerRoot.openDrawer(GravityCompat.END);
                        }
                        return true;
                    }
                    resetDrawerTranslations();
                    return false;
                default:
                    resetDrawerTranslations();
                    return false;
            }
        });
    }

    private void resetDrawerTranslations() {
        if (_attachmentDrawer != null) {
            _attachmentDrawer.setTranslationX(0f);
        }
        if (_classificationDrawer != null) {
            _classificationDrawer.setTranslationX(0f);
        }
    }

    private void updatePublishArc(float progress) {
        if (_publishArc == null) {
            return;
        }
        _publishArc.setScaleX(1f + (progress * 0.35f));
        _publishArc.setScaleY(1f + (progress * 0.55f));
        _publishArc.setAlpha(0.65f + (progress * 0.35f));
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
        _classificationSlug = "";
        for (String tag : parsedTags) {
            if (isClassificationTag(tag)) {
                _classificationSlug = toClassificationSlug(tag);
            } else {
                _selectedTags.add(tag);
            }
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
        String cleaned = content.replaceAll("(?m)^!\\[[^\\]]*]\\([^\\)]*\\)\\s*$\\n?", "");
        cleaned = cleaned.replaceAll("(?ms)^<audio\\s+src='[^']+'\\s+controls>.*?</audio>\\s*$\\n?", "");
        return cleaned.replaceAll("\\n{3,}", "\n\n");
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
        final int attachmentCount = countAttachments();
        final String classification = TextUtils.isEmpty(_classificationSlug)
                ? getString(R.string.portal_unclassified)
                : humanizeSlug(_classificationSlug);
        if (_recorder.isRecording()) {
            final long elapsed = Math.max(0, System.currentTimeMillis() - _recordStartedAt);
            final long sec = elapsed / 1000;
            _status.setText(getString(R.string.portal_recording_status, sec / 60, sec % 60));
        } else {
            _status.setText(getString(R.string.portal_status_summary, attachmentCount, classification));
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
        int i = 0;
        for (String tag : visibleTags) {
            final Chip chip = new Chip(this);
            chip.setText("#" + tag);
            chip.setCheckable(true);
            chip.setChecked(_selectedTags.contains(tag));
            final boolean selected = _selectedTags.contains(tag);
            chip.setChipBackgroundColor(ColorStateList.valueOf(selected ? 0xFFF04B4B : 0xFFE7E2DB));
            chip.setTextColor(selected ? 0xFFFFFFFF : 0xFF1E2135);
            chip.setOnClickListener(v -> toggleTagSelection(tag));
            _quickTagsGroup.addView(chip);
            i++;
        }
        final Chip addChip = new Chip(this);
        addChip.setText("+");
        addChip.setChipBackgroundColor(ColorStateList.valueOf(0xFF1E2135));
        addChip.setTextColor(0xFFFFFFFF);
        addChip.setOnClickListener(v -> showAddTagDialog());
        _quickTagsGroup.addView(addChip);
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
        final Editable e = _editor.getText();
        int start = Math.max(0, _editor.getSelectionStart());
        while (start > 0 && e.charAt(start - 1) != '\n') {
            start--;
        }
        if (e.toString().startsWith("# ", start)) {
            e.delete(start, start + 2);
        } else {
            e.insert(start, "# ");
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
        e.insert(end, suffix);
        e.insert(start, prefix);
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

    private void refreshAttachmentDrawer() {
        _attachmentList.removeAllViews();
        if (_sessionFile == null) {
            _attachmentEmpty.setVisibility(View.VISIBLE);
            refreshStatus();
            return;
        }
        final File[] files = _storage.getAttachmentDirForSession(_sessionFile).listFiles();
        if (files == null || files.length == 0) {
            _attachmentEmpty.setVisibility(View.VISIBLE);
            refreshStatus();
            return;
        }
        _attachmentEmpty.setVisibility(View.GONE);
        final List<File> sorted = new ArrayList<>(Arrays.asList(files));
        Collections.sort(sorted, Comparator.comparing(File::getName));
        for (File file : sorted) {
            _attachmentList.addView(buildAttachmentRow(file));
        }
        refreshStatus();
    }

    private View buildAttachmentRow(@NonNull File file) {
        if (isImageFile(file)) {
            final LinearLayout frame = new LinearLayout(this);
            frame.setOrientation(LinearLayout.HORIZONTAL);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 12;
            frame.setLayoutParams(lp);

            final ImageView preview = new ImageView(this);
            final LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(0, 280, 1f);
            preview.setLayoutParams(previewParams);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImageBitmap(loadImagePreview(file));
            preview.setOnClickListener(v -> showImagePreview(file));

            final ImageButton delete = new ImageButton(this);
            final LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(88, 280);
            delete.setLayoutParams(deleteParams);
            delete.setImageResource(R.drawable.ic_delete_black_24dp);
            delete.setBackgroundColor(ContextCompat.getColor(this, R.color.accent));
            delete.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white)));
            delete.setScaleType(ImageView.ScaleType.CENTER);
            delete.setOnClickListener(v -> deleteAttachment(file));

            frame.addView(preview);
            frame.addView(delete);
            return frame;
        }

        final LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 10, 0, 10);

        final TextView label = new TextView(this);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, 116, 1f);
        label.setLayoutParams(labelParams);
        label.setText(file.getName());
        label.setBackgroundColor(ContextCompat.getColor(this, R.color.primary));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(24, 0, 24, 0);
        label.setTextColor(ContextCompat.getColor(this, R.color.white));
        label.setTextSize(15f);
        label.setOnClickListener(v -> showAudioPreview(file));

        final ImageButton delete = new ImageButton(this);
        final LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(88, 116);
        delete.setLayoutParams(deleteParams);
        delete.setImageResource(R.drawable.ic_delete_black_24dp);
        delete.setBackgroundColor(ContextCompat.getColor(this, R.color.accent));
        delete.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white)));
        delete.setOnClickListener(v -> deleteAttachment(file));

        row.addView(label);
        row.addView(delete);
        return row;
    }

    private void deleteAttachment(@NonNull File file) {
        if (_sessionFile == null) {
            return;
        }
        final String rel = _storage.relativeToSession(_sessionFile, file);
        final String title = GsFileUtils.getFilenameWithoutExtension(file);
        final Editable e = _editor.getText();
        final String audioBlock = "\n<audio src='" + rel + "' controls><a href='" + rel + "'>" + title + "</a></audio>\n";
        final String imageBlock = "\n![" + title + "](" + rel + ")\n";
        String content = e.toString().replace(audioBlock, "\n").replace(imageBlock, "\n");
        content = content.replace(rel, "");
        _editor.setText(content.replaceAll("\\n{3,}", "\n\n"));
        _editor.setSelection(_editor.getText().length());
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        saveSession(false);
        refreshAttachmentDrawer();
        schedulePreviewRefresh();
    }

    private void refreshClassificationDrawer() {
        _classificationList.removeAllViews();
        final Set<String> all = new LinkedHashSet<>(DEFAULT_CLASSIFICATIONS);
        all.addAll(_storage.getCustomClassifications());
        if (!TextUtils.isEmpty(_classificationSlug)) {
            all.add(_classificationSlug);
        }
        for (String slug : all) {
            _classificationList.addView(buildClassificationButton(slug));
        }
    }

    private View buildClassificationButton(@NonNull String slug) {
        final MaterialButton button = new MaterialButton(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        button.setLayoutParams(lp);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setText(humanizeSlug(slug));
        button.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        button.setCornerRadius(18);
        final boolean selected = slug.equals(_classificationSlug);
        if (selected) {
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
            button.setTextColor(ContextCompat.getColor(this, R.color.white));
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.background)));
            button.setStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)));
            button.setStrokeWidth(2);
            button.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        }
        button.setOnClickListener(v -> {
            _classificationSlug = slug;
            refreshClassificationDrawer();
            refreshStatus();
            schedulePreviewRefresh();
            _drawerRoot.closeDrawer(GravityCompat.END);
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
                        _storage.recordCustomClassification(slug);
                        _classificationSlug = slug;
                        refreshClassificationDrawer();
                        refreshStatus();
                        schedulePreviewRefresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSettingsMenu(@NonNull View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add(Menu.NONE, MENU_SAVE_FOLDER, Menu.NONE, R.string.portal_save_folder);
        menu.getMenu().add(Menu.NONE, MENU_DRAFT_FOLDER, Menu.NONE, R.string.portal_draft_folder);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_SAVE_FOLDER:
                    openDirectoryPicker(REQ_PICK_SAVE_DIR);
                    return true;
                case MENU_DRAFT_FOLDER:
                    openDirectoryPicker(REQ_PICK_DRAFT_DIR);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
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
        if (_recorder.isRecording()) {
            stopRecordingAndAttach();
        }
        saveSession(true);
        try {
            _sessionFile = _repo.createSession();
            _titleInput.setText("");
            _editor.setText("");
            _selectedTags.clear();
            _classificationSlug = "";
            refreshDateTime();
            refreshStatus();
            renderQuickTags();
            refreshAttachmentDrawer();
            refreshClassificationDrawer();
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyRenderMode() {
        _editor.setVisibility(_renderMarkdown ? View.GONE : View.VISIBLE);
        _previewWeb.setVisibility(_renderMarkdown ? View.VISIBLE : View.GONE);
        _titleInput.setVisibility(_renderMarkdown ? View.GONE : View.VISIBLE);
        _formatScroll.setVisibility(_renderMarkdown ? View.GONE : View.VISIBLE);
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
            final String htmlBody = MarkdownTextConverter.flexmarkRenderer.render(
                    MarkdownTextConverter.flexmarkParser.parse(buildContentForSave())
            );
            final String html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1' />"
                    + "<style>body{font-family:sans-serif;padding:18px;color:#111827;background:#EEEEEE;}img{max-width:100%;height:auto;}audio{width:100%;margin-top:16px;}pre{white-space:pre-wrap;}</style>"
                    + "</head><body>" + htmlBody + "</body></html>";
            final String baseUrl = _sessionFile.getParentFile() == null ? null : _sessionFile.getParentFile().toURI().toString();
            _previewWeb.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null);
        } catch (Exception ignored) {
        }
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
            _recordButton.setAlpha(1f);
            _recordButton.setScaleX(1f);
            _recordButton.setScaleY(1f);
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
        _recordButton.animate().cancel();
        _recordButton.setAlpha(1f);
        _recordButton.setScaleX(1f);
        _recordButton.setScaleY(1f);
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
        _recordButton.setText("");
        if (_recorder.isRecording()) {
            _recordButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
            _recordButton.setContentDescription(getString(R.string.portal_stop_recording_accessibility));
        } else {
            _recordButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
            _recordButton.setContentDescription(getString(R.string.record_audio));
        }
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
        final String rel = _storage.relativeToSession(_sessionFile, file);
        final String title = GsFileUtils.getFilenameWithoutExtension(file);
        insertAtCursor("\n<audio src='" + rel + "' controls><a href='" + rel + "'>" + title + "</a></audio>\n");
        saveSession(false);
    }

    private void attachImageFile(@NonNull File file) {
        if (_sessionFile == null) {
            return;
        }
        final String rel = _storage.relativeToSession(_sessionFile, file);
        final String title = GsFileUtils.getFilenameWithoutExtension(file);
        insertAtCursor("\n![" + title + "](" + rel + ")\n");
        saveSession(false);
        refreshAttachmentDrawer();
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
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.background));

        final TextView title = new TextView(this);
        title.setText(file.getName());
        title.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        title.setTextSize(18f);

        final PortalAudioVisualizerView visualizerView = new PortalAudioVisualizerView(this);
        visualizerView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 180));

        final SeekBar seek = new SeekBar(this);
        final MaterialButton playPause = new MaterialButton(this);
        playPause.setText(R.string.play);

        root.addView(title);
        root.addView(visualizerView);
        root.addView(seek);
        root.addView(playPause);
        dialog.setContentView(root);

        final MediaPlayer player = new MediaPlayer();
        final Handler uiHandler = new Handler(Looper.getMainLooper());
        final Visualizer[] visualizerRef = new Visualizer[1];
        final Runnable seekSync = new Runnable() {
            @Override
            public void run() {
                if (player.isPlaying()) {
                    seek.setProgress(player.getCurrentPosition());
                    uiHandler.postDelayed(this, 150);
                }
            }
        };

        try {
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            seek.setMax(player.getDuration());
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
                playPause.setText(R.string.play);
            } else {
                player.start();
                playPause.setText(R.string.pause);
                uiHandler.post(seekSync);
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    player.seekTo(progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        player.setOnCompletionListener(mp -> {
            playPause.setText(R.string.play);
            seek.setProgress(seek.getMax());
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
