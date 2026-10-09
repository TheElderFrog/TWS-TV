package se.zepiwolf.tws.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Small native adaptation layer. Actions delegate to the original app. */
public final class TvSupport {
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static boolean supported(Activity a) { return a.getClass().getName().startsWith("se.zepiwolf.tws."); }
    public static void install(Activity a) {
        if (!supported(a) || sessions.containsKey(a)) return;
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        sessions.put(a, new Session(a));
    }
    public static void resume(Activity a) {
        if (!supported(a)) return;
        install(a);
        a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        main.postDelayed(() -> { Session s = sessions.get(a); if (s != null && !a.isFinishing()) s.attach(); }, 200);
    }
    public static void release(Activity a) {
        Session s = sessions.remove(a);
        if (s != null) s.detach();
    }
    public static boolean dispatch(Activity a, KeyEvent e) {
        Session s = sessions.get(a);
        if (s == null || a.isFinishing()) return false;
        try { return s.key(e); } catch (Exception ex) {
            android.util.Log.w("TwsTV", "Remote action failed", ex);
            return false;
        }
    }
    static int dp(Context c, int n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static int id(Activity a, String name) { return a.getResources().getIdentifier(name, "id", a.getPackageName()); }
    static String name(View v) {
        try { return v.getId() == View.NO_ID ? "" : v.getResources().getResourceEntryName(v.getId()); }
        catch (Exception e) { return ""; }
    }
    static boolean visible(View v) {
        if (!v.isShown() || v.getWidth() == 0 || v.getHeight() == 0) return false;
        Rect r = new Rect(); return v.getGlobalVisibleRect(r) && r.width() > 8 && r.height() > 8;
    }
    static boolean inside(View v, View parent) {
        while (v != null) {
            if (v == parent) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }
    static void all(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) all(g.getChildAt(i), out);
        }
    }
    static Object call(Object o, String method, Class<?>[] types, Object... args) throws Exception {
        return o.getClass().getMethod(method, types).invoke(o, args);
    }

    private static final class Session implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnGlobalFocusChangeListener, ViewTreeObserver.OnPreDrawListener {
        final Activity a;
        final boolean home, post;
        final WeakHashMap<View, Boolean> configured = new WeakHashMap<>();
        ViewGroup root;
        FocusRing ring;
        TvHome homeUi;
        TvLogin login;
        TvViewer viewer;
        TvSearch searchPage;
        boolean attached, longCenter, zoomMode;
        Session(Activity a) {
            this.a = a;
            home = a.getClass().getSimpleName().equals("MainActivity");
            post = a.getClass().getSimpleName().equals("PostActivity");
        }
        void attach() {
            if (attached) { update(); return; }
            root = (ViewGroup) a.findViewById(android.R.id.content);
            if (root == null || root.getChildCount() == 0) return;
            attached = true;
            if (a.getClass().getSimpleName().equals("LoginActivity")) login = new TvLogin(a, root);
            ring = new FocusRing(a, root, post);
            ((ViewGroup) a.getWindow().getDecorView()).getOverlay().add(ring);
            root.getViewTreeObserver().addOnGlobalLayoutListener(this);
            root.getViewTreeObserver().addOnGlobalFocusChangeListener(this);
            root.getViewTreeObserver().addOnPreDrawListener(this);
            if (home && root instanceof FrameLayout) homeUi = new TvHome(a, root, new TvHome.Actions() {
                @Override public void search() { Session.this.search(); }
                @Override public void navigate(String item) { nav(item); }
                @Override public void menu() { click("btnMenu"); }
                @Override public void help() { Session.this.help(); }
            });
            if (post) {
                viewer = new TvViewer(a, root, new TvViewer.Actions() {
                    @Override public void page(int direction) { turnPost(direction); }
                    @Override public void zoom() { Session.this.zoom(1.5f); }
                });
                ring.viewer = viewer;
            }
            update();
            if (home) focusImage();
            if (home || post) main.postDelayed(() -> focusImage(), 800);
            if (home) main.postDelayed(() -> {
                View focus = a.getCurrentFocus();
                if (focus instanceof EditText && ((EditText) focus).length() == 0) {
                    ((InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(focus.getWindowToken(), 0);
                    focusImage();
                }
            }, 1500);
        }
        void detach() {
            if (searchPage != null) searchPage.close(false);
            if (login != null) login.close();
            if (viewer != null) viewer.close();
            if (root != null && root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                root.getViewTreeObserver().removeOnGlobalFocusChangeListener(this);
                root.getViewTreeObserver().removeOnPreDrawListener(this);
            }
            if (ring != null) ((ViewGroup) a.getWindow().getDecorView()).getOverlay().remove(ring);
        }
        @Override public void onGlobalLayout() { update(); }
        @Override public boolean onPreDraw() {
            if (homeUi != null && searchPage == null) homeUi.settleFocus();
            if (ring != null) ring.refresh();
            return true;
        }
        @Override public void onGlobalFocusChanged(View old, View current) {
            if (homeUi != null && searchPage == null && homeUi.containerFocus(current)) {
                homeUi.recoverGridFocus(current);
                return;
            }
            if (current != null && (searchPage == null || !searchPage.listFocus(current)) && (!post || (viewer != null && viewer.detailFocus(current)))) {
                int margin = post ? dp(a, 4) : 0;
                current.requestRectangleOnScreen(new Rect(-margin, -margin, current.getWidth() + margin, current.getHeight() + margin), false);
            }
            if (ring != null) ring.invalidate();
        }
        void update() {
            if (root == null) return;
            if (homeUi != null) homeUi.update();
            List<View> views = new ArrayList<>(); all(root, views);
            for (View v : views) {
                String n = name(v);
                if (n.equals("adContainer")) {
                    v.setMinimumHeight(0);
                    if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                    ViewGroup.LayoutParams params = v.getLayoutParams();
                    if (params != null && params.height != 0) { params.height = 0; v.setLayoutParams(params); }
                }
                if (!configured.containsKey(v)) {
                    configured.put(v, Boolean.TRUE);
                    if (v.isClickable() || v instanceof EditText || n.equals("imgPreview") || n.equals("imageView") || n.equals("gifView") || (post && n.equals("videoView"))) {
                        v.setFocusable(true); v.setFocusableInTouchMode(true);
                    }
                    // One thumbnail is one remote stop; its info button remains available by long OK.
                    if (home && (n.equals("imgInfoBtn") || n.equals("btnMenu"))) {
                        v.setFocusable(false); v.setFocusableInTouchMode(false);
                    }
                    if (v instanceof EditText && (searchPage == null || !searchPage.owns(v))) ((EditText) v).setShowSoftInputOnFocus(false);
                    if (home && n.equals("search_src_text") && v instanceof android.widget.AutoCompleteTextView) {
                        android.widget.AutoCompleteTextView field = (android.widget.AutoCompleteTextView) v;
                        field.setThreshold(Integer.MAX_VALUE); field.dismissDropDown();
                    }
                    if (v instanceof ScrollView) v.setFocusable(false);
                    if (v instanceof TextView && !(v instanceof EditText)) {
                        TextView t = (TextView) v;
                        float sp = t.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity;
                        if (sp > 0 && sp < 15 && !n.equals("txtInfo")) t.setTextSize(15);
                    }
                }
                if (post && (n.equals("imageView") || n.equals("imgPreview") || n.equals("gifView"))) fitMedia(v);
            }
            if (viewer != null) viewer.update(views);
            if (ring != null) {
                View decor = a.getWindow().getDecorView();
                ring.layout(0, 0, decor.getWidth(), decor.getHeight()); ring.invalidate();
            }
        }
        void fitMedia(View v) {
            View full = findVisible("contentFrameFullscreen");
            if (full != null) return;
            View pane = ancestor(v, "tv_media_pane");
            if (pane == null || pane.getHeight() <= 0) return;
            ViewGroup.LayoutParams p = v.getLayoutParams();
            if (p != null && (p.height != pane.getHeight() || p.width != ViewGroup.LayoutParams.MATCH_PARENT)) {
                p.height = pane.getHeight(); p.width = ViewGroup.LayoutParams.MATCH_PARENT; v.setLayoutParams(p);
            }
        }
        View ancestor(View v, String n) {
            while (v != null) {
                if (name(v).equals(n)) return v;
                v = v.getParent() instanceof View ? (View) v.getParent() : null;
            }
            return null;
        }
        View findVisible(String n) {
            if (root == null) return null;
            List<View> views = new ArrayList<>(); all(root, views);
            View best = null; long area = 0; Rect r = new Rect();
            for (View v : views) if (name(v).equals(n) && visible(v) && v.getGlobalVisibleRect(r)) {
                long size = (long) r.width() * r.height();
                if (size > area) { best = v; area = size; }
            }
            return best;
        }
        View image() {
            if (viewer != null) return viewer.media();
            View best = null; long area = 0; Rect r = new Rect();
            for (String n : new String[]{"imageView", "gifView", "imgPreview"}) {
                View v = findVisible(n);
                if (v != null && v.getGlobalVisibleRect(r)) {
                    long size = (long) r.width() * r.height();
                    if (size > area) { best = v; area = size; }
                }
            }
            return best;
        }
        void focusImage() {
            if (searchPage != null) return;
            if (homeUi != null) { homeUi.focusGrid(); return; }
            View v = image();
            if (v != null) v.requestFocus();
        }
        boolean mediaAction(int code) {
            return viewer != null && viewer.mediaAction(code);
        }
        void toggleFullscreen() {
            if (viewer != null) viewer.toggleFullscreen();
        }
        void turnPost(int direction) {
            zoomMode = false;
            int code = direction < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            a.onKeyUp(code, new KeyEvent(KeyEvent.ACTION_UP, code));
            main.postDelayed(() -> { update(); focusImage(); }, 250);
        }
        void click(String n) {
            View v = findVisible(n);
            if (v == null && home) v = a.findViewById(id(a, n));
            if (v != null) v.performClick();
            else Toast.makeText(a, TvStrings.text(a, "tv_unavailable"), Toast.LENGTH_SHORT).show();
        }
        void nav(String n) {
            View nav = a.findViewById(id(a, "bottom_nav"));
            if (nav != null) {
                try { call(nav, "setSelectedItemId", new Class<?>[]{int.class}, id(a, n)); return; }
                catch (Exception ex) { android.util.Log.w("TwsTV", "Navigation item unavailable", ex); }
            }
            click("btnMenu");
        }
        View searchInput() {
            View search = a.findViewById(id(a, "searchView"));
            List<View> children = new ArrayList<>(); if (search != null) all(search, children);
            for (View v : children) if (v instanceof EditText) return v;
            return null;
        }
        void search() {
            View input = searchInput();
            View original = a.findViewById(id(a, "searchView"));
            if (input instanceof EditText && original != null && searchPage == null) {
                searchPage = new TvSearch(a, root, original, (EditText) input, () -> {
                    searchPage = null; ring.search = null; update(); focusImage();
                });
                ring.search = searchPage;
            }
        }
        void page(int delta) {
            try {
                View pager = a.findViewById(id(a, "recyclerViewPager"));
                if (pager != null) {
                    int index = (Integer) call(pager, "getCurrentItem", new Class<?>[0]);
                    Object adapter = call(pager, "getAdapter", new Class<?>[0]);
                    int count = (Integer) call(adapter, "getItemCount", new Class<?>[0]);
                    if (index + delta >= 0 && index + delta < count) call(pager, "setCurrentItem", new Class<?>[]{int.class}, index + delta);
                    else Toast.makeText(a, TvStrings.text(a, "tv_page_boundary"), Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ex) { android.util.Log.w("TwsTV", "Page unavailable", ex); }
        }
        void help() {
            new AlertDialog.Builder(a).setTitle(TvStrings.text(a, "tv_help_title"))
                .setMessage(TvStrings.text(a, "tv_help_body"))
                .setPositiveButton(TvStrings.text(a, "tv_ok"), null).show();
        }
        void menu() {
            List<String> labels = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
            if (post) {
                add(labels, actions, TvStrings.text(a, "tv_fullscreen"), () -> toggleFullscreen());
                if (findVisible("imgPlay") != null || findVisible("videoView") != null)
                    add(labels, actions, TvStrings.text(a, "tv_play_pause"), () -> mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                add(labels, actions, TvStrings.text(a, "tv_previous_image"), () -> turnPost(-1));
                add(labels, actions, TvStrings.text(a, "tv_next_image"), () -> turnPost(1));
                if (viewer != null && !viewer.isVideo()) {
                    add(labels, actions, TvStrings.text(a, "tv_zoom_pan"), () -> zoom(1.5f));
                    add(labels, actions, TvStrings.text(a, "tv_zoom_out"), () -> zoom(0.67f));
                    add(labels, actions, TvStrings.text(a, "tv_reset_zoom"), () -> resetZoom());
                }
                for (String[] action : new String[][]{{TvStrings.text(a, "tv_toggle_favourite"), "imgFavourite"}, {TvStrings.text(a, "tv_download"), "imgDownload"}, {TvStrings.text(a, "tv_comments"), "imgComments"}, {TvStrings.text(a, "tv_more"), "imgMore"}})
                    if (findVisible(action[1]) != null) add(labels, actions, action[0], () -> click(action[1]));
            } else if (home) {
                add(labels, actions, TvStrings.text(a, "tv_search"), () -> search());
                add(labels, actions, TvStrings.text(a, "tv_previous_page"), () -> page(-1));
                add(labels, actions, TvStrings.text(a, "tv_next_page"), () -> page(1));
                add(labels, actions, TvStrings.text(a, "tv_settings"), () -> click("btnMenu"));
            }
            add(labels, actions, TvStrings.text(a, "tv_help"), () -> help());
            AlertDialog dialog = new AlertDialog.Builder(a).setTitle(TvStrings.text(a, "tv_shortcuts"))
                .setItems(labels.toArray(new String[0]), (d, index) -> actions.get(index).run()).create();
            dialog.setOnShowListener(d -> { dialog.getListView().setFocusable(true); dialog.getListView().requestFocus(); dialog.getListView().setSelection(0); });
            dialog.show();
        }
        void add(List<String> labels, List<Runnable> actions, String label, Runnable action) { labels.add(label); actions.add(action); }
        void zoom(float factor) {
            View v = image();
            if (v == null) return;
            try {
                float scale = (Float) call(v, "getScale", new Class<?>[0]);
                PointF center = (PointF) call(v, "getCenter", new Class<?>[0]);
                if (center == null) { Toast.makeText(a, TvStrings.text(a, "tv_not_loaded"), Toast.LENGTH_SHORT).show(); return; }
                float min = (Float) call(v, "getMinScale", new Class<?>[0]);
                float max = (Float) call(v, "getMaxScale", new Class<?>[0]);
                call(v, "setScaleAndCenter", new Class<?>[]{float.class, PointF.class}, Math.max(min, Math.min(max, scale * factor)), center);
                zoomMode = true; v.requestFocus();
                Toast.makeText(a, TvStrings.text(a, "tv_zoom_help"), Toast.LENGTH_SHORT).show();
            } catch (Exception ex) { Toast.makeText(a, TvStrings.text(a, "tv_zoom_unsupported"), Toast.LENGTH_SHORT).show(); }
        }
        void resetZoom() {
            View v = image(); if (v == null) return;
            try { call(v, "resetScaleAndCenter", new Class<?>[0]); } catch (Exception ignored) {}
            zoomMode = false;
        }
        void pan(int code) {
            View v = image(); if (v == null) return;
            try {
                float scale = (Float) call(v, "getScale", new Class<?>[0]);
                PointF c = (PointF) call(v, "getCenter", new Class<?>[0]); if (c == null) return;
                float step = dp(a, 48) / Math.max(scale, 0.01f);
                c.x += code == 21 ? -step : code == 22 ? step : 0;
                c.y += code == 19 ? -step : code == 20 ? step : 0;
                call(v, "setScaleAndCenter", new Class<?>[]{float.class, PointF.class}, scale, c);
            } catch (Exception ignored) { zoomMode = false; }
        }
        boolean key(KeyEvent e) {
            if (searchPage != null) return searchPage.key(e);
            int code = e.getKeyCode(); boolean up = e.getAction() == KeyEvent.ACTION_UP;
            View focus = a.getCurrentFocus();
            if (post && (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                    || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
                if (!up && e.getRepeatCount() == 0) return mediaAction(code);
                return findVisible("imgPlay") != null || findVisible("videoView") != null;
            }
            if (homeUi != null && homeUi.direction(e)) return true;
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_BUTTON_Y) { if (up) menu(); return true; }
            if (zoomMode && code == KeyEvent.KEYCODE_BACK) { if (up) { zoomMode = false; Toast.makeText(a, TvStrings.text(a, "tv_zoom_exited"), Toast.LENGTH_SHORT).show(); } return true; }
            if (zoomMode && code >= 19 && code <= 22) { if (!up) pan(code); return true; }
            if (viewer != null && code == KeyEvent.KEYCODE_BACK && viewer.back(e)) return true;
            if (viewer != null && viewer.direction(e)) return true;
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BUTTON_A) {
                if (focus instanceof EditText) {
                    if (home && focus == searchInput()) { if (up) search(); return true; }
                    if (code == KeyEvent.KEYCODE_ENTER) return false;
                    if (up) ((InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT);
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) longCenter = false;
                    if (e.getRepeatCount() > 0 && e.getEventTime() - e.getDownTime() >= 600 && !longCenter) {
                        longCenter = true;
                        if (post) menu(); else if (focus != null && focus.isLongClickable()) focus.performLongClick(); else menu();
                    }
                } else if (up && !longCenter) {
                    if (zoomMode) zoom(1.4f);
                    else if (viewer != null) viewer.activate(focus);
                    else if (focus != null && focus.isClickable()) focus.performClick();
                    else if (post) toggleFullscreen();
                    else focusImage();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_UP) { if (up) { if (home) page(1); else if (post) turnPost(1); } return true; }
            if (code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { if (up) { if (home) page(-1); else if (post) turnPost(-1); } return true; }
            return false;
        }
    }

    private static final class FocusRing extends View {
        final ViewGroup root;
        final boolean post;
        TvViewer viewer;
        TvSearch search;
        View lastFocus;
        final Rect lastBounds = new Rect();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect rect = new Rect(); final RectF box = new RectF();
        FocusRing(Context c, ViewGroup root, boolean post) { super(c); this.root = root; this.post = post; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void refresh() {
            View focus = root.findFocus(); Rect bounds = new Rect();
            if (focus != null) focus.getGlobalVisibleRect(bounds);
            if (focus != lastFocus || !bounds.equals(lastBounds)) {
                lastFocus = focus; lastBounds.set(bounds); invalidate();
            }
        }
        @Override protected void onDraw(Canvas canvas) {
            View focus = root.findFocus();
            if (focus == null || !focus.isShown() || !focus.getGlobalVisibleRect(rect)) return;
            if (search != null && search.listFocus(focus)) return;
            if ("home_action".equals(focus.getTag())) return;
            if (!post && TvHome.recycler(focus)) return;
            if (post && (viewer == null || !viewer.ringFocus(focus))) return;
            if ((long) rect.width() * rect.height() > (long) root.getWidth() * root.getHeight() * 85 / 100) return;
            int[] pos = new int[2]; getLocationOnScreen(pos);
            box.set(rect); box.offset(-pos[0], -pos[1]); box.inset(-dp(getContext(), 2), -dp(getContext(), 2));
            if (post && focus instanceof android.widget.ImageButton) {
                float half = dp(getContext(), 28);
                float x = box.centerX(), y = box.centerY();
                box.set(x - half, y - half, x + half, y + half);
            }
            int save = canvas.save();
            View parent = focus.getParent() instanceof View ? (View) focus.getParent() : null;
            Rect clip = new Rect();
            while (parent != null && parent != root) {
                boolean viewport = name(parent).equals("recyclerView") || name(parent).equals("lLText")
                        || parent.getClass().getName().endsWith("RecyclerView") || parent instanceof ScrollView;
                if (viewport && parent instanceof ViewGroup && parent.getGlobalVisibleRect(clip)) {
                    int[] location = new int[2]; parent.getLocationOnScreen(location);
                    ViewGroup group = (ViewGroup) parent;
                    if (group.getClipToPadding()) {
                        clip.left = Math.max(clip.left, location[0] + parent.getPaddingLeft());
                        clip.top = Math.max(clip.top, location[1] + parent.getPaddingTop());
                        clip.right = Math.min(clip.right, location[0] + parent.getWidth() - parent.getPaddingRight());
                        clip.bottom = Math.min(clip.bottom, location[1] + parent.getHeight() - parent.getPaddingBottom());
                    }
                    clip.offset(-pos[0], -pos[1]); canvas.clipRect(clip);
                }
                parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(getContext(), 3)); paint.setColor(0xff73e3ff);
            canvas.drawRoundRect(box, dp(getContext(), 8), dp(getContext(), 8), paint);
            canvas.restoreToCount(save);
        }
    }
}
