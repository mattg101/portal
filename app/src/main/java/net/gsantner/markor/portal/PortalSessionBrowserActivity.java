package net.gsantner.markor.portal;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import net.gsantner.markor.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PortalSessionBrowserActivity extends AppCompatActivity {

    private PortalSessionRepository _repo;
    private SessionsAdapter _adapter;
    private EditText _search;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.portal_activity_session_browser);

        _repo = new PortalSessionRepository(new PortalStorage(this));
        _search = findViewById(R.id.portal_browser_search);
        SwipeRefreshLayout swipe = findViewById(R.id.portal_browser_swipe);
        RecyclerView list = findViewById(R.id.portal_browser_list);

        list.setLayoutManager(new LinearLayoutManager(this));
        _adapter = new SessionsAdapter(item -> {
            Intent i = new Intent(this, PortalEntryActivity.class)
                    .setAction(PortalActions.ACTION_TEXT)
                    .putExtra(PortalActions.EXTRA_SESSION_PATH, item.file.getAbsolutePath());
            startActivity(i);
            finish();
        });
        list.setAdapter(_adapter);

        swipe.setOnRefreshListener(() -> {
            reload();
            swipe.setRefreshing(false);
        });

        _search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { reload(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        findViewById(R.id.portal_browser_back).setOnClickListener(v -> finish());

        reload();
    }

    private void reload() {
        final String q = _search.getText() == null ? "" : _search.getText().toString();
        _adapter.setItems(_repo.listSessions(q));
    }

    private static class SessionsAdapter extends RecyclerView.Adapter<SessionsViewHolder> {
        interface OnClick {
            void onClick(PortalSessionRepository.SessionItem item);
        }

        private final OnClick _onClick;
        private final List<PortalSessionRepository.SessionItem> _items = new ArrayList<>();
        private final SimpleDateFormat _dt = new SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault());

        SessionsAdapter(OnClick onClick) {
            _onClick = onClick;
        }

        void setItems(@NonNull List<PortalSessionRepository.SessionItem> items) {
            _items.clear();
            _items.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public SessionsViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.portal_item_session, parent, false);
            return new SessionsViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SessionsViewHolder h, int position) {
            final PortalSessionRepository.SessionItem item = _items.get(position);
            h.time.setText(_dt.format(new Date(item.modifiedAt)));
            h.preview.setText(item.preview);
            h.audio.setVisibility(item.hasAudio ? View.VISIBLE : View.GONE);
            h.image.setVisibility(item.hasImage ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> _onClick.onClick(item));
        }

        @Override
        public int getItemCount() {
            return _items.size();
        }
    }

    private static class SessionsViewHolder extends RecyclerView.ViewHolder {
        final android.widget.TextView time;
        final android.widget.TextView preview;
        final View audio;
        final View image;

        SessionsViewHolder(@NonNull View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.portal_session_time);
            preview = itemView.findViewById(R.id.portal_session_preview);
            audio = itemView.findViewById(R.id.portal_session_icon_audio);
            image = itemView.findViewById(R.id.portal_session_icon_image);
        }
    }
}
