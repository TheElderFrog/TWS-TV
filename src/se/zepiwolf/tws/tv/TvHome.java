package se.zepiwolf.tws.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import static se.zepiwolf.tws.tv.TvSupport.*;

/** Fixed side controls surround the original pager, adapter and network logic. */
final class TvHome {
    static boolean recycler(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }
    interface Actions {
        void search();
        void navigate(String item);
        void menu();
        void help();
    }
    final Activity activity;
    final ViewGroup root;
    final Actions actions;
    final LinearLayout left, right;
    final TextView query;
    final View previous, next;
    final List<View> leftStops = new ArrayList<>(), rightStops = new ArrayList<>();
    View lastImage;
    int pagerIndex = -1;
    boolean initialFocusPending = true;

    TvHome(Activity activity, ViewGroup root, Actions actions) {
        this.activity = activity; this.root = root; this.actions = actions;
        root.setBackgroundColor(0xff101213);
        left = rail(Gravity.LEFT); right = rail(Gravity.RIGHT);
        TextView brand = label("TWS TV", 20, 0xfff0f5f3);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        left.addView(brand, new LinearLayout.LayoutParams(-1, dp(activity, 48)));
        query = label("", 13, 0xffaebcb7); query.setMaxLines(3); query.setEllipsize(TextUtils.TruncateAt.END);
        query.setId(id(activity, "home_query"));
        query.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        query.setPadding(dp(activity, 10), 0, dp(activity, 10), 0);
        left.addView(query, new LinearLayout.LayoutParams(-1, dp(activity, 72)));
        separator(left);
        action(left, leftStops, "tv_search", "ic_search", () -> actions.search());
        action(left, leftStops, "tv_saved", "ic_last_search", () -> actions.navigate("saved_searches"));
        action(left, leftStops, "tv_filter", "ic_filter", () -> actions.navigate("filter"));
        action(left, leftStops, "tv_favourites", "ic_favorite", () -> actions.navigate("favourites"));
        spacer(left);
        action(left, leftStops, "tv_images", "ic_baseline_collections_bookmark_24", () -> focusGrid());

        TextView pages = label(TvStrings.text(activity, "tv_images"), 17, 0xfff0f5f3);
        right.addView(pages, new LinearLayout.LayoutParams(-1, dp(activity, 48)));
        View counter = activity.findViewById(id(activity, "txtPageNr"));
        if (counter instanceof TextView) {
            move(counter, right, dp(activity, 72));
            TextView text = (TextView) counter; text.setTextSize(15); text.setTextColor(0xffdfce9d);
            text.setGravity(Gravity.CENTER); text.setBackgroundColor(Color.TRANSPARENT); text.setAlpha(1);
            text.setMaxLines(3); text.setEllipsize(TextUtils.TruncateAt.END);
            text.setFocusable(false); text.setClickable(false);
        }
        separator(right);
        previous = action(right, rightStops, "tv_previous_page", "ic_arrow_back", () -> page(-1));
        next = action(right, rightStops, "tv_next_page", "ic_arrow_forward", () -> page(1));
        View hidden = activity.findViewById(id(activity, "txtHiddenPosts"));
        if (hidden instanceof TextView) {
            move(hidden, right, dp(activity, 64));
            TextView text = (TextView) hidden; text.setTextSize(13); text.setTextColor(0xffaebcb7);
            text.setGravity(Gravity.CENTER); text.setBackgroundColor(Color.TRANSPARENT); text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END); rightStops.add(hidden);
        }
        spacer(right);
        action(right, rightStops, "tv_app_menu", "ic_menu", () -> actions.menu());
        action(right, rightStops, "tv_help", "ic_info", () -> actions.help());
        View pager = activity.findViewById(id(activity, "recyclerViewPager"));
        try { call(pager, "setUserInputEnabled", new Class<?>[]{boolean.class}, false); }
        catch (Exception error) { android.util.Log.w("TwsTV", "Pager swipe lock unavailable", error); }
        update();
    }
    LinearLayout rail(int gravity) {
        LinearLayout rail = new LinearLayout(activity); rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(activity, 8), dp(activity, 12), dp(activity, 8), dp(activity, 12));
        rail.setBackgroundColor(0xff191d1f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(activity, 140), -1, gravity);
        params.topMargin = params.bottomMargin = dp(activity, 24);
        params.leftMargin = params.rightMargin = dp(activity, 16);
        root.addView(rail, params); return rail;
    }
    TextView label(String value, int size, int color) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size);
        text.setTextColor(color); text.setGravity(Gravity.CENTER); return text;
    }
    void move(View view, LinearLayout parent, int height) {
        ((ViewGroup) view.getParent()).removeView(view);
        parent.addView(view, new LinearLayout.LayoutParams(-1, dp(activity, height)));
    }
    void separator(LinearLayout rail) {
        View line = new View(activity); line.setBackgroundColor(0xff343b3c);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(activity, 1));
        params.bottomMargin = dp(activity, 12); rail.addView(line, params);
    }
    void spacer(LinearLayout rail) { rail.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1)); }
    View action(LinearLayout rail, List<View> stops, String key, String icon, Runnable action) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setId(id(activity, "home_" + key.substring(3)));
        row.setPadding(dp(activity, 10), 0, dp(activity, 8), 0);
        row.setFocusable(true); row.setFocusableInTouchMode(true); row.setClickable(true);
        row.setTag("home_action"); row.setContentDescription(TvStrings.text(activity, key));
        row.setTooltipText(TvStrings.text(activity, key));
        View originalMenu = key.equals("tv_app_menu") ? activity.findViewById(id(activity, "btnMenu")) : null;
        ImageView image;
        if (originalMenu instanceof ImageView) {
            // Keep the original popup's anchor visible, at its new right-rail location.
            ((ViewGroup) originalMenu.getParent()).removeView(originalMenu);
            image = (ImageView) originalMenu;
            image.setPadding(0, 0, 0, 0); image.setBackgroundColor(Color.TRANSPARENT);
            image.setMinimumWidth(0); image.setMinimumHeight(0);
            image.setFocusable(false); image.setFocusableInTouchMode(false);
        } else image = new ImageView(activity);
        int drawable = activity.getResources().getIdentifier(icon, "drawable", activity.getPackageName());
        if (drawable == 0) drawable = android.R.drawable.btn_star_big_off;
        image.setImageResource(drawable); image.setColorFilter(0xffc5d5cf);
        row.addView(image, new LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22)));
        TextView text = label(TvStrings.text(activity, key), 15, 0xffe7edeb);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); text.setMaxLines(2);
        text.setPadding(dp(activity, 8), 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            text.setAutoSizeTextTypeUniformWithConfiguration(11, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        row.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
        row.setBackground(background(false));
        row.setOnFocusChangeListener((view, focused) -> row.setBackground(background(focused)));
        row.setOnClickListener(view -> { initialFocusPending = false; action.run(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(activity, 56));
        params.bottomMargin = dp(activity, 8); rail.addView(row, params); stops.add(row); return row;
    }
    GradientDrawable background(boolean focused) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setCornerRadius(dp(activity, 4));
        drawable.setColor(focused ? 0xff213d42 : Color.TRANSPARENT);
        drawable.setStroke(dp(activity, focused ? 2 : 1), focused ? 0xff73e3ff : Color.TRANSPARENT);
        return drawable;
    }
    void update() {
        for (String name : new String[]{"app_bar", "bottom_nav"}) {
            View original = activity.findViewById(id(activity, name));
            if (original != null && original.getVisibility() != View.GONE) original.setVisibility(View.GONE);
        }
        View content = activity.findViewById(id(activity, "lLText"));
        if (content != null && content.getPaddingBottom() != 0) content.setPadding(0, 0, 0, 0);
        View pagerView = activity.findViewById(id(activity, "recyclerViewPager"));
        if (pagerView != null) {
            List<View> children = new ArrayList<>(); all(pagerView, children);
            for (View child : children) if (recycler(child)) {
                child.setFocusable(false);
                ((ViewGroup) child).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                if (name(child).equals("recyclerView")) {
                    int inset = dp(activity, 6);
                    if (child.getPaddingTop() != inset || child.getPaddingBottom() != inset)
                        child.setPadding(child.getPaddingLeft(), inset, child.getPaddingRight(), inset);
                    if (!((ViewGroup) child).getClipToPadding()) ((ViewGroup) child).setClipToPadding(true);
                }
            }
        }
        View field = activity.findViewById(id(activity, "search_src_text"));
        if (field instanceof EditText) {
            String value = ((EditText) field).getText().toString().trim();
            if (!value.contentEquals(query.getText())) query.setText(value);
        }
        try {
            View pager = activity.findViewById(id(activity, "recyclerViewPager"));
            int index = (Integer) call(pager, "getCurrentItem", new Class<?>[0]);
            Object adapter = call(pager, "getAdapter", new Class<?>[0]);
            int count = adapter == null ? 0 : (Integer) call(adapter, "getItemCount", new Class<?>[0]);
            available(previous, index > 0); available(next, index + 1 < count);
            if (pagerIndex != index) { pagerIndex = index; lastImage = null; }
        } catch (Exception ignored) { available(previous, false); available(next, false); }
        if (initialFocusPending) focusGrid();
    }
    void available(View button, boolean available) {
        // Keep boundary controls focusable, so updates cannot strand the remote focus.
        button.setAlpha(available ? 1f : 0.38f); button.setActivated(available);
    }
    void page(int direction) {
        update();
        if (!(direction < 0 ? previous : next).isActivated()) return;
        try {
            View pager = activity.findViewById(id(activity, "recyclerViewPager"));
            int index = (Integer) call(pager, "getCurrentItem", new Class<?>[0]);
            call(pager, "setCurrentItem", new Class<?>[]{int.class}, index + direction);
            update();
        } catch (Exception error) { android.util.Log.w("TwsTV", "Page button failed", error); }
    }
    boolean owns(View view) { return view != null && (inside(view, left) || inside(view, right)); }
    boolean containerFocus(View view) {
        View pager = activity.findViewById(id(activity, "recyclerViewPager"));
        return view != null && pager != null && inside(view, pager)
                && recycler(view);
    }
    void recoverGridFocus(View container) {
        initialFocusPending = true;
        if (container != null) container.setFocusable(false);
        focusGrid();
    }
    void settleFocus() {
        View focus = root.findFocus();
        if (root.isShown() && root.hasWindowFocus() && (focus == null || containerFocus(focus)))
            recoverGridFocus(focus);
    }
    void focusGrid() {
        if (lastImage != null && visible(lastImage) && inside(lastImage, root) && lastImage.requestFocus()) {
            initialFocusPending = false; return;
        }
        List<View> views = new ArrayList<>(); all(root, views);
        for (View view : views) if (name(view).equals("imgPreview") && visible(view)) {
            lastImage = view;
            if (view.requestFocus()) initialFocusPending = false;
            return;
        }
        if (!owns(activity.getCurrentFocus())) leftStops.get(0).requestFocus();
    }
    void railFocus(boolean toLeft) {
        List<View> stops = toLeft ? leftStops : rightStops;
        for (View view : stops) if (visible(view)) { view.requestFocus(); return; }
    }
    boolean direction(KeyEvent event) {
        int code = event.getKeyCode();
        if (code < KeyEvent.KEYCODE_DPAD_UP || code > KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        View focus = activity.getCurrentFocus();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down) initialFocusPending = false;
        if (containerFocus(focus)) {
            if (down) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
                    railFocus(code == KeyEvent.KEYCODE_DPAD_LEFT);
                else focusGrid();
            }
            return true;
        }
        if (owns(focus)) {
            if (!down) return true;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if ((inside(focus, left) && code == KeyEvent.KEYCODE_DPAD_RIGHT)
                        || (inside(focus, right) && code == KeyEvent.KEYCODE_DPAD_LEFT)) focusGrid();
            } else {
                List<View> stops = inside(focus, left) ? leftStops : rightStops;
                int index = stops.indexOf(focus), step = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                for (int i = index + step; i >= 0 && i < stops.size(); i += step)
                    if (visible(stops.get(i))) { stops.get(i).requestFocus(); break; }
            }
            return true;
        }
        if (!name(focus == null ? root : focus).equals("imgPreview")) return false;
        lastImage = focus;
        View child = focus;
        while (child.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) child.getParent();
            if (name(parent).equals("recyclerView")) {
                try {
                    int position = (Integer) call(parent, "getChildAdapterPosition", new Class<?>[]{View.class}, child);
                    int columns = 0;
                    int rowTop = parent.getChildAt(0).getTop();
                    for (int i = 0; i < parent.getChildCount(); i++)
                        if (parent.getChildAt(i).getTop() == rowTop) columns++;
                    if (columns == 0 || position < 0) return false;
                    Object adapter = call(parent, "getAdapter", new Class<?>[0]);
                    int count = (Integer) call(adapter, "getItemCount", new Class<?>[0]);
                    boolean leftEdge = code == KeyEvent.KEYCODE_DPAD_LEFT && position % columns == 0;
                    boolean rightEdge = code == KeyEvent.KEYCODE_DPAD_RIGHT && (position % columns == columns - 1 || position == count - 1);
                    if (leftEdge || rightEdge) { if (down) railFocus(leftEdge); return true; }
                    if (code == KeyEvent.KEYCODE_DPAD_UP && position < columns) return true;
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN && position / columns == (count - 1) / columns) return true;
                } catch (Exception error) { android.util.Log.w("TwsTV", "Grid route unavailable", error); }
                break;
            }
            child = parent;
        }
        return false;
    }
}
