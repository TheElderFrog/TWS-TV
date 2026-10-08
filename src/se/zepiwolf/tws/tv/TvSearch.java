package se.zepiwolf.tws.tv;

import android.app.Activity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static se.zepiwolf.tws.tv.TvSupport.*;

/** Full-screen search surface; keeps the original provider and search submission. */
final class TvSearch {
    final Activity activity;
    final ViewGroup root;
    final View originalSearch;
    final EditText originalInput;
    final Runnable onClose;
    final View previousFocus;
    final int softInputMode;
    final int screenHeight;
    final List<View> previousViews = new ArrayList<>();
    final List<Integer> previousVisibility = new ArrayList<>();
    final LinearLayout page;
    final SearchInput input;
    final ListView suggestions;
    final TextView status;
    final ImageButton submitButton;
    final SuggestionAdapter adapter = new SuggestionAdapter();
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    int generation;
    int selectedPosition;
    boolean closed, backWasKeyboard, keyboardDismissed;
    long keyboardRequestedAt;
    Runnable pending;

    TvSearch(Activity activity, ViewGroup root, View originalSearch, EditText originalInput, Runnable onClose) {
        this.activity = activity; this.root = root; this.originalSearch = originalSearch;
        this.originalInput = originalInput; this.onClose = onClose;
        previousFocus = activity.getCurrentFocus();
        softInputMode = activity.getWindow().getAttributes().softInputMode;
        android.util.DisplayMetrics display = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(display); screenHeight = display.heightPixels;
        for (int index = 0; index < root.getChildCount(); index++) {
            View view = root.getChildAt(index);
            previousViews.add(view); previousVisibility.add(view.getVisibility()); view.setVisibility(View.INVISIBLE);
        }
        page = new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(activity, 32), dp(activity, 20), dp(activity, 32), dp(activity, 20));
        page.setBackgroundColor(0xff111416);
        page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView title = new TextView(activity); title.setText("搜索"); title.setTextSize(22); title.setTextColor(0xffeeeeee);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(activity, 36));
        page.addView(title, titleParams);
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(activity, 64)));
        int backIcon = activity.getResources().getIdentifier("ic_arrow_back", "drawable", activity.getPackageName());
        header.addView(icon("返回", backIcon != 0 ? backIcon : android.R.drawable.ic_media_previous, () -> back()), new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)));
        input = new SearchInput(activity); input.setId(View.generateViewId());
        input.setSingleLine(true); input.setTextSize(22); input.setTextColor(0xffeeeeee); input.setHintTextColor(0xffaeb8bc);
        input.setHint("搜索标签或历史记录"); input.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
        input.setInputType(originalInput.getInputType());
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setShowSoftInputOnFocus(false);
        input.setBackground(background(0xff242a2d, 0xff64767b));
        input.setFocusable(true); input.setFocusableInTouchMode(true);
        input.setOnClickListener(view -> showKeyboard());
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(activity, 56), 1);
        inputParams.setMargins(dp(activity, 12), 0, dp(activity, 12), 0); header.addView(input, inputParams);
        header.addView(icon("清空", android.R.drawable.ic_menu_close_clear_cancel, () -> {
            input.setText(""); input.requestFocus();
        }), new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)));
        submitButton = icon("搜索", android.R.drawable.ic_menu_search, () -> submit(input.getText().toString()));
        header.addView(submitButton, new LinearLayout.LayoutParams(dp(activity, 56), dp(activity, 56)));
        status = new TextView(activity); status.setTextSize(16); status.setTextColor(0xffb5bec2);
        status.setGravity(Gravity.CENTER_VERTICAL); page.addView(status, new LinearLayout.LayoutParams(-1, dp(activity, 36)));
        suggestions = new ListView(activity); suggestions.setId(View.generateViewId());
        suggestions.setDivider(new ColorDrawable(0xff343b3e)); suggestions.setDividerHeight(dp(activity, 1));
        suggestions.setClipToPadding(true); suggestions.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        suggestions.setFocusable(true); suggestions.setFocusableInTouchMode(true);
        suggestions.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        suggestions.setAdapter(adapter);
        suggestions.setSelector(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        suggestions.setOnFocusChangeListener((view, focused) -> suggestions.setSelector(focused
                ? background(0xff234447, 0xff73e3ff) : new ColorDrawable(android.graphics.Color.TRANSPARENT)));
        suggestions.setOnItemClickListener((parent, view, position, itemId) -> {
            if (position >= 0 && position < adapter.items.size()) submit(adapter.items.get(position).query);
        });
        page.addView(suggestions, new LinearLayout.LayoutParams(-1, 0, 1));
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                submit(input.getText().toString()); return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { load(text.toString()); }
            @Override public void afterTextChanged(Editable text) {}
        });
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        input.setText(originalInput.getText()); input.setSelection(input.length()); input.requestFocus();
        page.postDelayed(() -> { if (!closed) showKeyboard(); }, 300);
    }
    boolean owns(View view) { return inside(view, page); }
    boolean listFocus(View view) { return owns(view) && (view == suggestions || inside(view, suggestions)); }
    GradientDrawable background(int color, int border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(activity, 4)); drawable.setStroke(dp(activity, 2), border); return drawable;
    }
    ImageButton icon(String label, int drawable, Runnable action) {
        ImageButton button = new ImageButton(activity); button.setImageResource(drawable);
        button.setContentDescription(label); button.setTooltipText(label);
        button.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setFocusable(true); button.setFocusableInTouchMode(true); button.setOnClickListener(view -> action.run());
        return button;
    }
    boolean keyboardVisible() {
        if (keyboardDismissed) return false;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = page.getRootWindowInsets();
            if (insets != null) return insets.isVisible(WindowInsets.Type.ime());
        }
        Rect visible = new Rect(); root.getWindowVisibleDisplayFrame(visible);
        return screenHeight - visible.bottom > dp(activity, 120);
    }
    boolean keyboardOpening() { return keyboardRequestedAt > 0 && SystemClock.uptimeMillis() - keyboardRequestedAt < 1500; }
    void showKeyboard() {
        keyboardDismissed = false;
        input.setShowSoftInputOnFocus(true); input.requestFocus();
        keyboardRequestedAt = SystemClock.uptimeMillis();
        ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }
    void hideKeyboard() {
        keyboardDismissed = true;
        keyboardRequestedAt = 0; input.setShowSoftInputOnFocus(false);
        ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.requestFocus();
    }
    void back() { if (keyboardVisible() || keyboardOpening()) hideKeyboard(); else close(true); }
    boolean backKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
            backWasKeyboard = keyboardVisible() || keyboardOpening();
        if (event.getAction() == KeyEvent.ACTION_UP) { if (backWasKeyboard) hideKeyboard(); else close(true); }
        return true;
    }
    boolean key(KeyEvent event) {
        if (closed) return false;
        int code = event.getKeyCode(); boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (code == KeyEvent.KEYCODE_BACK) return backKey(event);
        View focus = activity.getCurrentFocus();
        if (keyboardVisible()) return false;
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (!up) return true;
            if (focus == input && code == KeyEvent.KEYCODE_DPAD_CENTER) showKeyboard();
            else if (focus == input) submit(input.getText().toString());
            else if (focus == suggestions) {
                int position = selectedPosition;
                if (position >= 0 && position < adapter.items.size()) submit(adapter.items.get(position).query);
            } else if (focus != null && owns(focus)) focus.performClick();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!up && !adapter.items.isEmpty()) {
                select(focus == suggestions ? Math.min(selectedPosition + 1, adapter.items.size() - 1) : 0);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && focus == suggestions) {
            if (!up) { if (selectedPosition == 0) input.requestFocus(); else select(selectedPosition - 1); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && focus == suggestions) { if (!up) input.requestFocus(); return true; }
        if (code == KeyEvent.KEYCODE_MENU) return true;
        return false;
    }
    void select(int position) {
        selectedPosition = position;
        suggestions.requestFocus(); alignSelection();
        // ListView's focus layout can otherwise choose a row near the old focus rectangle.
        suggestions.post(() -> { if (!closed && suggestions.hasFocus()) alignSelection(); });
    }
    void alignSelection() {
        int first = suggestions.getFirstVisiblePosition();
        View row = suggestions.getChildAt(selectedPosition - first);
        int height = dp(activity, 68);
        int bottom = Math.max(0, suggestions.getHeight() - suggestions.getPaddingTop() - suggestions.getPaddingBottom() - height);
        int top = row == null ? (selectedPosition < first ? 0 : bottom)
                : Math.max(0, Math.min(bottom, row.getTop() - suggestions.getPaddingTop()));
        suggestions.setSelectionFromTop(selectedPosition, top);
    }
    String column(Cursor cursor, String name) {
        int index = cursor.getColumnIndex(name); return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }
    void load(String query) {
        int request = ++generation;
        if (pending != null) page.removeCallbacks(pending);
        submitButton.setEnabled(!query.trim().isEmpty());
        status.setText("正在加载联想");
        adapter.items = new ArrayList<>(); adapter.notifyDataSetChanged(); selectedPosition = 0;
        pending = () -> worker.execute(() -> {
            List<Suggestion> items = new ArrayList<>(); boolean failed = false;
            HashSet<String> queries = new HashSet<>();
            try {
                SearchManager manager = (SearchManager) activity.getSystemService(Context.SEARCH_SERVICE);
                SearchableInfo info = manager.getSearchableInfo(activity.getComponentName());
                if (info == null || info.getSuggestAuthority() == null) throw new IllegalStateException("No search provider");
                Uri.Builder uri = new Uri.Builder().scheme("content").authority(info.getSuggestAuthority());
                if (info.getSuggestPath() != null) uri.appendEncodedPath(info.getSuggestPath());
                uri.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY).appendQueryParameter("limit", "64");
                String selection = info.getSuggestSelection();
                String[] args = selection == null ? null : new String[]{query};
                if (selection == null) uri.appendPath(query);
                try (Cursor cursor = activity.getContentResolver().query(uri.build(), null, selection, args, null)) {
                    if (cursor != null) while (cursor.moveToNext() && items.size() < 64) {
                        String title = column(cursor, SearchManager.SUGGEST_COLUMN_TEXT_1);
                        String prefix = column(cursor, SearchManager.SUGGEST_COLUMN_TEXT_2);
                        if (title.isEmpty()) continue;
                        if (column(cursor, SearchManager.SUGGEST_COLUMN_FORMAT).equals("html"))
                            title = Html.fromHtml(title, Html.FROM_HTML_MODE_LEGACY).toString();
                        String fullQuery = prefix.trim().isEmpty() ? title.trim() : prefix.trim() + " " + title.trim();
                        if (queries.add(fullQuery)) items.add(new Suggestion(title, prefix, fullQuery));
                    }
                }
            } catch (Exception error) {
                failed = true; android.util.Log.w("TwsTV", "Search suggestions unavailable", error);
            }
            final boolean error = failed;
            page.post(() -> {
                if (closed || request != generation) return;
                adapter.items = items; adapter.notifyDataSetChanged();
                status.setText(error ? "联想暂不可用" : items.isEmpty() ? "没有联想结果" : "联想（" + items.size() + "）");
                if (suggestions.hasFocus() && !items.isEmpty()) select(0);
            });
        });
        page.postDelayed(pending, 250);
    }
    void submit(String query) {
        if (closed || query.trim().isEmpty()) return;
        try {
            call(originalSearch, "r", new Class<?>[]{CharSequence.class}, query.trim());
            close(false);
            originalInput.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        } catch (Exception error) {
            android.util.Log.w("TwsTV", "Search submission unavailable", error);
            if (!closed) status.setText("搜索暂不可用");
        }
    }
    void close(boolean restoreFocus) {
        if (closed) return;
        closed = true; ++generation; worker.shutdownNow();
        if (pending != null) page.removeCallbacks(pending);
        hideKeyboard(); root.removeView(page);
        for (int index = 0; index < previousViews.size(); index++) previousViews.get(index).setVisibility(previousVisibility.get(index));
        activity.getWindow().setSoftInputMode(softInputMode);
        onClose.run();
        if (restoreFocus && previousFocus != null && previousFocus.isShown()) previousFocus.requestFocus();
    }
    final class SearchInput extends EditText {
        SearchInput(Context context) { super(context); }
        @Override public boolean onKeyPreIme(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_BACK && !closed) return backKey(event);
            return super.onKeyPreIme(code, event);
        }
    }
    static final class Suggestion {
        final String title, prefix, query;
        Suggestion(String title, String prefix, String query) { this.title = title; this.prefix = prefix; this.query = query; }
    }
    final class SuggestionAdapter extends BaseAdapter {
        List<Suggestion> items = new ArrayList<>();
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert instanceof LinearLayout) row = (LinearLayout) convert;
            else {
                row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
                row.setLayoutParams(new ListView.LayoutParams(-1, dp(activity, 68)));
                ImageView icon = new ImageView(activity); icon.setImageResource(android.R.drawable.ic_menu_search);
                row.addView(icon, new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)));
                LinearLayout text = new LinearLayout(activity); text.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(activity, 16); row.addView(text, params);
                TextView title = new TextView(activity); title.setTextSize(20); title.setTextColor(0xffeeeeee); title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END); text.addView(title);
                TextView prefix = new TextView(activity); prefix.setTextSize(15); prefix.setTextColor(0xffbbc4c8); prefix.setSingleLine(true); text.addView(prefix);
            }
            Suggestion item = items.get(position);
            LinearLayout text = (LinearLayout) row.getChildAt(1);
            ((TextView) text.getChildAt(0)).setText(item.title);
            TextView prefix = (TextView) text.getChildAt(1); prefix.setText(item.prefix);
            prefix.setVisibility(item.prefix.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
