package net.gsantner.markor.portal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
    private static final List<String> DEFAULT_TAGS = Arrays.asList("idea", "todo", "reference", "meeting", "experiment");
    private static final List<String> DEFAULT_CLASSIFICATIONS = Arrays.asList(
            "quick-note",
            "journal-entry",
            "project-thought",
            "food-diary"
    );

    private final Handler _handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("MMMM d, yyyy | h:mm a", Locale.getDefault());
    private final PortalAudioRecorderController _recorder = new PortalAudioRecorderController();
    private final Runnable _previewRunnable = this::refreshMarkdownPreview;

    private DrawerLayout _drawerRoot;
    private View _mainContent;
    private MaterialToolbar _toolbar;
    private EditText _editor;
    private WebView _previewWeb;
    private TextView _dateTime;
    private TextView _status;
    private TextView _attachmentEmpty;
    private ChipGroup _quickTagsGroup;
    private LinearLayout _attachmentList;
    private LinearLayout _classificationList;
    private MaterialButton _recordButton;

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
        _renderMarkdown = _storage.isRenderMarkdownEnabled();
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
        _editor = findViewById(R.id.portal_editor);
        _previewWeb = findViewById(R.id.portal_preview_web);
        _dateTime = findViewById(R.id.portal_datetime);
        _status = findViewById(R.id.portal_status);
        _attachmentEmpty = findViewById(R.id.portal_attachment_empty);
        _quickTagsGroup = findViewById(R.id.portal_quick_tags_group);
        _attachmentList = findViewById(R.id.portal_attachment_list);
        _classificationList = findViewById(R.id.portal_classification_list);
        _recordButton = findViewById(R.id.portal_action_record);

        final MaterialButton camera = findViewById(R.id.portal_action_camera);
        final MaterialButton gallery = findViewById(R.id.portal_action_gallery);
        final MaterialButton save = findViewById(R.id.portal_action_save);
        final MaterialButton addCustomClassification = findViewById(R.id.portal_class_add_custom);
        final MaterialButton openSettings = findViewById(R.id.portal_open_settings_button);
        final MaterialButton openAttachments = findViewById(R.id.portal_open_attachments_button);
        final MaterialButton openClassification = findViewById(R.id.portal_open_classification_button);

        _toolbar.setNavigationOnClickListener(v -> showSettingsDialog());
        if (_toolbar.getNavigationIcon() != null) {
            _toolbar.getNavigationIcon().setTint(ContextCompat.getColor(this, R.color.white));
        }
        _toolbar.setNavigationContentDescription(R.string.portal_settings);
        _dateTime.setOnClickListener(v -> startActivity(new Intent(this, PortalSessionBrowserActivity.class)));

        _editor.addTextChangedListener(new TextWatcher() {
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
        save.setOnClickListener(v -> saveSession(true));
        addCustomClassification.setOnClickListener(v -> showAddClassificationDialog());
        openSettings.setOnClickListener(v -> showSettingsDialog());
        openAttachments.setOnClickListener(v -> _drawerRoot.openDrawer(GravityCompat.START));
        openClassification.setOnClickListener(v -> _drawerRoot.openDrawer(GravityCompat.END));
        attachSwipeOpener(_mainContent);
        attachSwipeOpener(_editor);
        attachSwipeOpener(_previewWeb);
        attachSwipeOpener(findViewById(R.id.portal_bottom_toolbar));

        _previewWeb.getSettings().setJavaScriptEnabled(false);
        _previewWeb.setBackgroundColor(ContextCompat.getColor(this, R.color.background));
    }

    private void attachSwipeOpener(@Nullable View target) {
        if (target == null) {
            return;
        }
        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    _swipeDownX = event.getRawX();
                    _swipeDownY = event.getRawY();
                    return false;
                case MotionEvent.ACTION_UP:
                    final float dx = event.getRawX() - _swipeDownX;
                    final float dy = event.getRawY() - _swipeDownY;
                    if (Math.abs(dx) > 140f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        if (dx > 0) {
                            _drawerRoot.openDrawer(GravityCompat.START);
                        } else {
                            _drawerRoot.openDrawer(GravityCompat.END);
                        }
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
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
        _editor.setText(stripManagedMetadata(content));
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
        final String body = _editor.getText() == null ? "" : _editor.getText().toString();
        return _tagManager.applyTags(body, tags);
    }

    private void refreshDateTime() {
        if (_sessionFile != null && _sessionFile.exists()) {
            _dateTime.setText(_dateFormat.format(new Date(_sessionFile.lastModified())));
        } else {
            _dateTime.setText(_dateFormat.format(new Date()));
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
        final int[] colors = {
                0xFFF4E3D7, 0xFFDCE8F5, 0xFFE4F3E5, 0xFFFDE6D8, 0xFFEDE3F6
        };
        int i = 0;
        for (String tag : visibleTags) {
            final Chip chip = new Chip(this);
            chip.setText("#" + tag);
            chip.setCheckable(true);
            chip.setChecked(_selectedTags.contains(tag));
            chip.setChipBackgroundColor(ColorStateList.valueOf(colors[i % colors.length]));
            chip.setTextColor(0xFF1F2937);
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
        final LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 10, 0, 10);

        final TextView label = new TextView(this);
        final LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelParams);
        label.setText(file.getName());
        label.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        label.setTextSize(15f);

        final ImageButton delete = new ImageButton(this);
        delete.setImageResource(R.drawable.ic_delete_black_24dp);
        delete.setBackgroundColor(0x00000000);
        delete.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
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
                        _classificationSlug = slug;
                        refreshClassificationDrawer();
                        refreshStatus();
                        schedulePreviewRefresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showSettingsDialog() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 24, 36, 0);

        final TextView saveLabel = makeDialogLabel(getString(R.string.portal_save_folder));
        final EditText saveInput = new EditText(this);
        saveInput.setText(_storage.getConfiguredRootPath());
        saveInput.setHint(getString(R.string.portal_save_folder_hint));

        final TextView draftLabel = makeDialogLabel(getString(R.string.portal_draft_folder));
        final EditText draftInput = new EditText(this);
        draftInput.setText(_storage.getConfiguredDraftPath());
        draftInput.setHint(getString(R.string.portal_draft_folder_hint));

        final SwitchCompat previewSwitch = new SwitchCompat(this);
        previewSwitch.setText(getString(R.string.portal_render_markdown));
        previewSwitch.setChecked(_renderMarkdown);

        root.addView(saveLabel);
        root.addView(saveInput);
        root.addView(draftLabel);
        root.addView(draftInput);
        root.addView(previewSwitch);

        new AlertDialog.Builder(this)
                .setTitle(R.string.portal_settings)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    _storage.setConfiguredRootPath(saveInput.getText() == null ? "" : saveInput.getText().toString());
                    _storage.setConfiguredDraftPath(draftInput.getText() == null ? "" : draftInput.getText().toString());
                    _storage.setRenderMarkdownEnabled(previewSwitch.isChecked());
                    _renderMarkdown = previewSwitch.isChecked();
                    _storage.ensureWritableRoot();
                    _storage.ensureDraftRoot();
                    applyRenderMode();
                    refreshStatus();
                })
                .setNeutralButton(R.string.portal_open_attachments, (dialog, which) -> _drawerRoot.openDrawer(GravityCompat.START))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private TextView makeDialogLabel(String text) {
        final TextView tv = new TextView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(this, R.color.primary_text));
        tv.setTextSize(14f);
        return tv;
    }

    private void applyRenderMode() {
        _editor.setVisibility(_renderMarkdown ? View.GONE : View.VISIBLE);
        _previewWeb.setVisibility(_renderMarkdown ? View.VISIBLE : View.GONE);
        refreshMarkdownPreview();
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
        final Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        i.setType("image/*");
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
        if (_recorder.isRecording()) {
            final long elapsed = Math.max(0, System.currentTimeMillis() - _recordStartedAt);
            final long sec = elapsed / 1000;
            _recordButton.setText(getString(R.string.portal_stop_recording, sec / 60, sec % 60));
            _recordButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
        } else {
            _recordButton.setText(getString(R.string.record_audio));
            _recordButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)));
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
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
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
            } catch (Exception e) {
                Toast.makeText(this, R.string.error_picture_selection, Toast.LENGTH_SHORT).show();
            }
        }
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
