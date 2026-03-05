package net.gsantner.markor.portal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import net.gsantner.markor.R;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PortalInputActivity extends AppCompatActivity {
    private static final int REQ_MEDIA_PICK = 1001;
    private static final int REQ_RECORD_AUDIO = 1002;

    private final Handler _handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat _dateFormat = new SimpleDateFormat("MMMM d, yyyy | h:mm a", Locale.getDefault());

    private EditText _editor;
    private TextView _dateTime;
    private ChipGroup _quickTagsGroup;

    private PortalStorage _storage;
    private PortalSessionRepository _repo;
    private PortalTagManager _tagManager;
    private PortalTagStore _tagStore;

    private File _sessionFile;

    private final PortalAudioRecorderController _recorder = new PortalAudioRecorderController();
    private BottomSheetDialog _recorderSheet;
    private TextView _recorderTimer;
    private MaterialButton _recorderStartStop;
    private MaterialButton _recorderPlay;
    private SeekBar _recorderSeek;
    private File _pendingAudioFile;
    private long _recordStartedAt;
    private MediaPlayer _previewPlayer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.portal_activity_input);

        _storage = new PortalStorage(this);
        _repo = new PortalSessionRepository(_storage);
        _tagManager = new PortalTagManager();
        _tagStore = new PortalTagStore(this);

        _editor = findViewById(R.id.portal_editor);
        _dateTime = findViewById(R.id.portal_datetime);
        _quickTagsGroup = findViewById(R.id.portal_quick_tags_group);
        final View bottomToolbar = findViewById(R.id.portal_bottom_toolbar);
        final ExtendedFloatingActionButton save = findViewById(R.id.portal_save_fab);
        final ImageButton mic = findViewById(R.id.portal_action_mic);
        final ImageButton media = findViewById(R.id.portal_action_media);

        save.setOnClickListener(v -> saveSession(true));
        mic.setOnClickListener(v -> requestRecordPermissionThenShow());
        media.setOnClickListener(v -> showAttachSheet());
        _dateTime.setOnClickListener(v -> startActivity(new Intent(this, PortalSessionBrowserActivity.class)));
        _dateTime.setOnLongClickListener(v -> {
            showStorageFolderDialog();
            return true;
        });
        bottomToolbar.setOnTouchListener(new View.OnTouchListener() {
            float startY = -1f;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getY();
                        break;
                    case MotionEvent.ACTION_UP:
                        if (startY > 0 && (startY - event.getY()) > 40f) {
                            showAttachSheet();
                            return true;
                        }
                        break;
                }
                return false;
            }
        });

        resolveSessionFromIntent();
        loadSession();
        refreshDateTime();
        renderQuickTags();

        final String action = getIntent() != null ? getIntent().getAction() : PortalActions.ACTION_TEXT;
        if (PortalActions.ACTION_AUDIO.equals(action)) {
            _editor.post(this::requestRecordPermissionThenShow);
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
        releasePreviewPlayer();
        if (_pendingAudioFile != null && _pendingAudioFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            _pendingAudioFile.delete();
            _pendingAudioFile = null;
        }
        super.onDestroy();
    }

    private void resolveSessionFromIntent() {
        String path = getIntent() != null ? getIntent().getStringExtra(PortalActions.EXTRA_SESSION_PATH) : null;
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
        setTitle(session.getName());
    }

    private void loadSession() {
        if (_sessionFile != null && _sessionFile.isFile()) {
            _editor.setText(_repo.readContent(_sessionFile));
            _editor.setSelection(_editor.getText().length());
        }
    }

    private void saveSession(boolean notify) {
        if (_sessionFile == null) {
            return;
        }
        final String text = _editor.getText() == null ? "" : _editor.getText().toString();
        final boolean ok = _repo.saveContent(_sessionFile, text);
        if (notify) {
            Toast.makeText(this, ok ? R.string.saved : R.string.could_not_save_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshDateTime() {
        _dateTime.setText(_dateFormat.format(new Date()));
    }

    private void openMediaPicker() {
        final Intent i = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, getString(R.string.image)), REQ_MEDIA_PICK);
    }

    private void showAttachSheet() {
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        final View root = LayoutInflater.from(this).inflate(R.layout.portal_sheet_attach, null, false);
        final MaterialButton image = root.findViewById(R.id.portal_attach_image);
        final MaterialButton audio = root.findViewById(R.id.portal_attach_audio);
        image.setOnClickListener(v -> {
            sheet.dismiss();
            openMediaPicker();
        });
        audio.setOnClickListener(v -> {
            sheet.dismiss();
            showDraftAudioPicker();
        });
        sheet.setContentView(root);
        sheet.show();
    }

    private void showDraftAudioPicker() {
        final List<File> allAudio = new ArrayList<>();
        collectAudioFilesRecursive(_storage.getSessionsDir(), allAudio);
        if (allAudio.isEmpty()) {
            Toast.makeText(this, R.string.empty_directory, Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(allAudio, Comparator.comparingLong(File::lastModified).reversed());
        final String[] labels = new String[Math.min(50, allAudio.size())];
        for (int i = 0; i < labels.length; i++) {
            final File f = allAudio.get(i);
            labels[i] = f.getName() + "  (" + new Date(f.lastModified()) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.audio))
                .setItems(labels, (d, which) -> attachDraftAudioFile(allAudio.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void attachDraftAudioFile(@NonNull File source) {
        if (_sessionFile == null) {
            return;
        }
        try {
            final File copied = _storage.copyIntoSessionAttachmentDir(_sessionFile, source);
            final String rel = _storage.relativeToSession(_sessionFile, copied);
            final String title = GsFileUtils.getFilenameWithoutExtension(copied);
            insertAtCursor("\n<audio src='" + rel + "' controls><a href='" + rel + "'>" + title + "</a></audio>\n");
            saveSession(false);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_could_not_open_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void collectAudioFilesRecursive(@Nullable File root, @NonNull List<File> out) {
        if (root == null || !root.isDirectory()) {
            return;
        }
        final File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectAudioFilesRecursive(f, out);
                continue;
            }
            final String ext = GsFileUtils.getFilenameExtension(f).toLowerCase(Locale.ENGLISH);
            if (".m4a".equals(ext) || ".mp3".equals(ext) || ".wav".equals(ext) || ".ogg".equals(ext) || ".aac".equals(ext)) {
                out.add(f);
            }
        }
    }

    private void showFormattingMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 1, getString(R.string.bold));
        pm.getMenu().add(0, 2, 2, getString(R.string.italic));
        pm.getMenu().add(0, 3, 3, getString(R.string.unordered_list));
        pm.setOnMenuItemClickListener(this::onFormatMenuItem);
        pm.show();
    }

    private boolean onFormatMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                wrapSelection("**", "**");
                return true;
            case 2:
                wrapSelection("_", "_");
                return true;
            case 3:
                prefixLines("- ");
                return true;
            default:
                return false;
        }
    }

    private void wrapSelection(String pre, String post) {
        Editable e = _editor.getText();
        int s = Math.max(0, _editor.getSelectionStart());
        int en = Math.max(0, _editor.getSelectionEnd());
        if (s > en) {
            int tmp = s; s = en; en = tmp;
        }
        e.insert(en, post);
        e.insert(s, pre);
    }

    private void prefixLines(String prefix) {
        Editable e = _editor.getText();
        int s = Math.max(0, _editor.getSelectionStart());
        int en = Math.max(0, _editor.getSelectionEnd());
        if (s > en) {
            int tmp = s; s = en; en = tmp;
        }
        String selected = e.subSequence(s, en).toString();
        if (TextUtils.isEmpty(selected)) {
            e.insert(s, prefix);
            return;
        }
        String[] lines = selected.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(prefix).append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        e.replace(s, en, out.toString());
    }

    private void requestRecordPermissionThenShow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        showRecorderSheet();
    }

    private void showRecorderSheet() {
        if (_recorderSheet == null) {
            _recorderSheet = new BottomSheetDialog(this);
            View root = LayoutInflater.from(this).inflate(R.layout.portal_sheet_recorder, null, false);
            _recorderTimer = root.findViewById(R.id.portal_recorder_timer);
            _recorderStartStop = root.findViewById(R.id.portal_recorder_start_stop);
            _recorderPlay = root.findViewById(R.id.portal_recorder_play);
            _recorderSeek = root.findViewById(R.id.portal_recorder_seek);
            MaterialButton saveBtn = root.findViewById(R.id.portal_recorder_save);
            MaterialButton discardBtn = root.findViewById(R.id.portal_recorder_discard);

            _recorderStartStop.setOnClickListener(v -> toggleRecording());
            _recorderPlay.setOnClickListener(v -> togglePreviewPlayback());
            _recorderSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && _previewPlayer != null) {
                        _previewPlayer.seekTo(progress);
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            saveBtn.setOnClickListener(v -> saveRecordingAndInsert());
            discardBtn.setOnClickListener(v -> {
                _recorder.stop(false);
                releasePreviewPlayer();
                _pendingAudioFile = null;
                _recorderSheet.dismiss();
                updateRecorderViews();
            });

            _recorderSheet.setOnDismissListener(d -> {
                if (_recorder.isRecording()) {
                    _recorder.stop(false);
                }
                releasePreviewPlayer();
                if (_pendingAudioFile != null && _pendingAudioFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    _pendingAudioFile.delete();
                    _pendingAudioFile = null;
                }
                _handler.removeCallbacksAndMessages(null);
                updateRecorderViews();
            });

            _recorderSheet.setContentView(root);
        }
        updateRecorderViews();
        _recorderSheet.show();
    }

    private void toggleRecording() {
        if (_recorder.isRecording()) {
            _recorder.stop(true);
            _pendingAudioFile = _recorder.consumeOutputFile();
            _handler.removeCallbacksAndMessages(null);
        } else {
            try {
                _pendingAudioFile = _storage.createAudioFileForSession(_sessionFile);
                _recorder.start(_pendingAudioFile);
                _recordStartedAt = System.currentTimeMillis();
                tickTimer();
            } catch (Exception e) {
                Toast.makeText(this, R.string.record_audio, Toast.LENGTH_SHORT).show();
                _pendingAudioFile = null;
            }
        }
        updateRecorderViews();
    }

    private void tickTimer() {
        if (!_recorder.isRecording()) {
            return;
        }
        final long elapsed = Math.max(0, System.currentTimeMillis() - _recordStartedAt);
        final long sec = elapsed / 1000;
        _recorderTimer.setText(String.format(Locale.ENGLISH, "%02d:%02d", sec / 60, sec % 60));
        _handler.postDelayed(this::tickTimer, 250);
    }

    private void updateRecorderViews() {
        if (_recorderStartStop == null) {
            return;
        }
        _recorderStartStop.setText(_recorder.isRecording() ? R.string.stop : R.string.record_audio);
        final boolean canPreview = !_recorder.isRecording() && _pendingAudioFile != null && _pendingAudioFile.isFile();
        if (_recorderPlay != null) {
            _recorderPlay.setEnabled(canPreview);
            _recorderPlay.setText(_previewPlayer != null && _previewPlayer.isPlaying() ? R.string.pause : R.string.play);
        }
        if (_recorderSeek != null) {
            _recorderSeek.setEnabled(canPreview);
        }
        if (!_recorder.isRecording() && _pendingAudioFile == null) {
            _recorderTimer.setText("00:00");
            if (_recorderSeek != null) {
                _recorderSeek.setProgress(0);
            }
        }
    }

    private void saveRecordingAndInsert() {
        if (_recorder.isRecording()) {
            _recorder.stop(true);
            _pendingAudioFile = _recorder.consumeOutputFile();
        }
        releasePreviewPlayer();
        if (_pendingAudioFile == null || !_pendingAudioFile.isFile()) {
            Toast.makeText(this, R.string.record_audio, Toast.LENGTH_SHORT).show();
            return;
        }
        final String rel = _storage.relativeToSession(_sessionFile, _pendingAudioFile);
        final String title = GsFileUtils.getFilenameWithoutExtension(_pendingAudioFile);
        insertAtCursor("\n<audio src='" + rel + "' controls><a href='" + rel + "'>" + title + "</a></audio>\n");
        _pendingAudioFile = null;
        saveSession(false);
        if (_recorderSheet != null) {
            _recorderSheet.dismiss();
        }
    }

    private void togglePreviewPlayback() {
        if (_pendingAudioFile == null || !_pendingAudioFile.isFile()) {
            return;
        }
        try {
            if (_previewPlayer == null) {
                _previewPlayer = new MediaPlayer();
                _previewPlayer.setDataSource(_pendingAudioFile.getAbsolutePath());
                _previewPlayer.prepare();
                if (_recorderSeek != null) {
                    _recorderSeek.setMax(_previewPlayer.getDuration());
                }
                _previewPlayer.setOnCompletionListener(mp -> {
                    if (_recorderSeek != null) {
                        _recorderSeek.setProgress(_recorderSeek.getMax());
                    }
                    updateRecorderViews();
                });
            }
            if (_previewPlayer.isPlaying()) {
                _previewPlayer.pause();
            } else {
                _previewPlayer.start();
                tickPreviewSeek();
            }
            updateRecorderViews();
        } catch (Exception ignored) {
            releasePreviewPlayer();
        }
    }

    private void tickPreviewSeek() {
        if (_previewPlayer != null && _previewPlayer.isPlaying() && _recorderSeek != null) {
            _recorderSeek.setProgress(_previewPlayer.getCurrentPosition());
            _handler.postDelayed(this::tickPreviewSeek, 200);
        }
    }

    private void releasePreviewPlayer() {
        if (_previewPlayer != null) {
            try {
                _previewPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                _previewPlayer.release();
            } catch (Exception ignored) {
            }
            _previewPlayer = null;
        }
    }

    private void renderQuickTags() {
        if (_quickTagsGroup == null) {
            return;
        }
        _quickTagsGroup.removeAllViews();
        List<String> topTags = _tagStore.getTopTags(10);
        if (topTags.isEmpty()) {
            topTags = java.util.Arrays.asList("idea", "todo", "reference", "meeting", "experiment");
        }
        final int[] colors = {
                0xFFE3F2FD, 0xFFE8F5E9, 0xFFFFF3E0, 0xFFF3E5F5, 0xFFFFEBEE
        };
        for (int i = 0; i < topTags.size(); i++) {
            final String tag = topTags.get(i);
            Chip c = new Chip(this);
            c.setText("#" + tag);
            c.setChipBackgroundColor(ColorStateList.valueOf(colors[i % colors.length]));
            c.setTextColor(0xFF1F2937);
            c.setOnClickListener(v -> applyQuickTag(tag));
            _quickTagsGroup.addView(c);
        }
    }

    private void applyQuickTag(String tag) {
        final List<String> current = _tagManager.parseAllTags(_editor.getText().toString());
        if (!current.contains(tag)) {
            current.add(tag);
        }
        final String applied = _tagManager.applyTags(_editor.getText().toString(), current);
        _editor.setText(applied);
        _editor.setSelection(_editor.getText().length());
        _tagStore.recordTagUse(java.util.Collections.singletonList(tag));
        saveSession(false);
        renderQuickTags();
    }

    private void showStorageFolderDialog() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.select_storage_folder));
        input.setText(_storage.getConfiguredRootPath());
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.save_location))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    final String p = input.getText() == null ? "" : input.getText().toString().trim();
                    _storage.setConfiguredRootPath(p);
                    if (_storage.ensureWritableRoot()) {
                        Toast.makeText(this, getString(R.string.saved_location_to, _storage.getNotebookRoot().getAbsolutePath()), Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.error_cannot_create_notebook_dir__appspecific, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void insertAtCursor(String text) {
        Editable e = _editor.getText();
        int s = Math.max(0, _editor.getSelectionStart());
        int en = Math.max(0, _editor.getSelectionEnd());
        if (s > en) {
            int tmp = s; s = en; en = tmp;
        }
        e.replace(s, en, text);
    }

    private void showTagSheet() {
        final BottomSheetDialog sheet = new BottomSheetDialog(this);
        final View root = LayoutInflater.from(this).inflate(R.layout.portal_sheet_tags, null, false);
        final ChipGroup selected = root.findViewById(R.id.portal_tags_selected_group);
        final ChipGroup suggestions = root.findViewById(R.id.portal_tags_suggestions_group);
        final TextInputEditText input = root.findViewById(R.id.portal_tags_input);
        final MaterialButton add = root.findViewById(R.id.portal_tags_add);
        final MaterialButton apply = root.findViewById(R.id.portal_tags_apply);

        final List<String> current = _tagManager.parseAllTags(_editor.getText().toString());
        final List<String> selectedTags = new ArrayList<>(current);

        renderSelectedTags(selected, selectedTags);
        renderSuggestionTags(suggestions, selectedTags);

        add.setOnClickListener(v -> {
            final String raw = input.getText() == null ? "" : input.getText().toString();
            final List<String> normalized = _tagManager.normalize(java.util.Collections.singletonList(raw));
            if (!normalized.isEmpty() && !selectedTags.contains(normalized.get(0))) {
                selectedTags.add(normalized.get(0));
                input.setText("");
                renderSelectedTags(selected, selectedTags);
                renderSuggestionTags(suggestions, selectedTags);
            }
        });

        apply.setOnClickListener(v -> {
            final List<String> normalized = _tagManager.normalize(selectedTags);
            final String applied = _tagManager.applyTags(_editor.getText().toString(), normalized);
            _editor.setText(applied);
            _editor.setSelection(_editor.getText().length());
            _tagStore.recordTagUse(normalized);
            saveSession(false);
            sheet.dismiss();
        });

        sheet.setContentView(root);
        sheet.show();
    }

    private void renderSelectedTags(ChipGroup group, List<String> selectedTags) {
        group.removeAllViews();
        for (String t : new ArrayList<>(selectedTags)) {
            Chip c = new Chip(this);
            c.setText("#" + t);
            c.setCloseIconVisible(true);
            c.setOnCloseIconClickListener(v -> {
                selectedTags.remove(t);
                renderSelectedTags(group, selectedTags);
                final ChipGroup sugg = ((View) group.getParent()).findViewById(R.id.portal_tags_suggestions_group);
                renderSuggestionTags(sugg, selectedTags);
            });
            group.addView(c);
        }
    }

    private void renderSuggestionTags(ChipGroup group, List<String> selectedTags) {
        group.removeAllViews();
        final List<String> top = _tagStore.getTopTags(12);
        for (String t : top) {
            if (selectedTags.contains(t)) {
                continue;
            }
            Chip c = new Chip(this);
            c.setText("#" + t);
            c.setOnClickListener(v -> {
                selectedTags.add(t);
                final ChipGroup selectedGroup = ((View) group.getParent()).findViewById(R.id.portal_tags_selected_group);
                renderSelectedTags(selectedGroup, selectedTags);
                renderSuggestionTags(group, selectedTags);
            });
            group.addView(c);
        }
    }

    @SuppressLint("Range")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_MEDIA_PICK) {
            final Uri uri = data.getData();
            if (uri == null || _sessionFile == null) {
                return;
            }
            try {
                String name = "image-" + System.currentTimeMillis() + ".jpg";
                android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) {
                    if (c.moveToFirst()) {
                        final int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            final String n = c.getString(idx);
                            if (!TextUtils.isEmpty(n)) {
                                name = n;
                            }
                        }
                    }
                    c.close();
                }

                final File attachmentDir = _storage.getAttachmentDirForSession(_sessionFile);
                final File dest = GsFileUtils.findNonConflictingDest(attachmentDir, name);

                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while (in != null && (read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                final String rel = _storage.relativeToSession(_sessionFile, dest);
                insertAtCursor("\n![" + GsFileUtils.getFilenameWithoutExtension(dest) + "](" + rel + ")\n");
                saveSession(false);
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
                showRecorderSheet();
            } else {
                Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
