package se.zepiwolf.tws.tv;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import static se.zepiwolf.tws.tv.TvSupport.*;

/** TV navigation owns focus; the original app still owns media and online actions. */
final class TvViewer {
    interface Actions { void page(int direction); void zoom(); }
    private enum Zone { MEDIA, DETAILS, ACTIONS, TRANSPORT }
    final Activity activity;
    final ViewGroup root;
    final Actions actions;
    final LinearLayout transport;
    final List<View> transportButtons = new ArrayList<>();
    final WeakHashMap<View, Integer> hidden = new WeakHashMap<>();
    final WeakHashMap<View, Boolean> focusable = new WeakHashMap<>();
    final WeakHashMap<View, Boolean> players = new WeakHashMap<>();
    View owner, full, lastFull, lastOwner, lastDetail;
    ImageButton playButton;
    TextView time;
    boolean videoBar, updating, closed;
    final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (transport.getVisibility() == View.VISIBLE) { syncPlayIcon(); syncTime(); }
            root.postDelayed(this, 1000);
        }
    };

    TvViewer(Activity activity, ViewGroup root, Actions actions) {
        this.activity = activity; this.root = root; this.actions = actions;
        transport = new LinearLayout(activity);
        transport.setGravity(Gravity.CENTER);
        transport.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
        transport.setBackgroundColor(0xee17191b);
        transport.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, dp(activity, 72), Gravity.BOTTOM);
        params.setMargins(dp(activity, 24), 0, dp(activity, 24), dp(activity, 12));
        root.addView(transport, params);
        root.postDelayed(ticker, 1000);
    }
    void close() { closed = true; root.removeCallbacks(ticker); root.removeView(transport); }

    View largest(View scope, String wanted, boolean requireVisible) {
        if (scope == null) return null;
        List<View> views = new ArrayList<>(); all(scope, views);
        View best = null; long size = -1; Rect rect = new Rect();
        for (View view : views) if (name(view).equals(wanted)) {
            if (requireVisible && !visible(view)) continue;
            long area = view.getGlobalVisibleRect(rect) ? (long) rect.width() * rect.height() : 0;
            if (area > size) { best = view; size = area; }
        }
        return best;
    }
    View find(String wanted) { return largest(owner, wanted, true); }
    View any(String wanted) { return largest(owner, wanted, false); }
    boolean isFull() { return full != null; }
    boolean isVideo() { return find("imgPlay") != null || find("videoView") != null; }
    View media() {
        for (String wanted : new String[]{"imgPlay", "videoView", "imageView", "gifView", "imgPreview"}) {
            View view = find(wanted); if (view != null) return view;
        }
        return null;
    }
    View ancestor(View view, String wanted) {
        while (view != null) {
            if (name(view).equals(wanted)) return view;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return null;
    }
    Zone zone(View view) {
        if (view != null && inside(view, transport)) return Zone.TRANSPORT;
        if (view != null && ancestor(view, "scrollView") != null) return Zone.DETAILS;
        if (view != null && ancestor(view, "lLButtons") != null) return Zone.ACTIONS;
        return Zone.MEDIA;
    }
    boolean detailFocus(View view) { return zone(view) == Zone.DETAILS; }
    boolean ringFocus(View view) {
        return Boolean.TRUE.equals(focusable.get(view)) && zone(view) != Zone.MEDIA
                && (!isFull() || inside(view, transport));
    }
    void visibility(View view, int value) {
        if (view != null && view.getVisibility() != value) view.setVisibility(value);
    }
    void hide(View view) {
        if (view != null && !hidden.containsKey(view)) {
            hidden.put(view, view.getVisibility()); visibility(view, View.INVISIBLE);
        }
    }
    void update(List<View> views) {
        if (updating) return;
        updating = true;
        try {
            full = largest(root, "contentFrameFullscreen", true);
            View pane = largest(root, "tv_media_pane", true);
            owner = full != null && full.getParent() instanceof View ? (View) full.getParent()
                    : pane != null && pane.getParent().getParent() instanceof View ? (View) pane.getParent().getParent() : null;
            for (View view : new ArrayList<>(hidden.keySet())) {
                if (!isFull() || view.getParent() != owner) visibility(view, hidden.remove(view));
            }
            if (isFull()) {
                for (View view : views) if (name(view).equals("tv_media_pane") && view.getParent() instanceof View) {
                    View row = (View) view.getParent(); if (row.getParent() == owner) hide(row);
                }
                hide(any("lLButtons"));
            } else visibility(transport, View.GONE);

            focusable.clear();
            View mediaTarget = media();
            for (View view : views) {
                if (inside(view, transport)) continue;
                String n = name(view);
                if (n.equals("btnBackL") || n.equals("btnBackR") || n.equals("imgMini") || n.startsWith("fLGo"))
                    visibility(view, View.GONE);
                if (n.equals("txtDescription") && view instanceof TextView && ((TextView) view).isTextSelectable())
                    ((TextView) view).setTextIsSelectable(false);
                boolean media = view == mediaTarget;
                boolean textAction = view instanceof TextView && ((TextView) view).length() > 0;
                boolean details = inside(view, owner) && ancestor(view, "scrollView") != null
                        && view.isClickable() && view.isEnabled()
                        && (textAction || n.equals("pLLDescription") || n.equals("pLLTags"));
                boolean action = inside(view, owner) && ancestor(view, "lLButtons") != null && view instanceof ImageButton;
                boolean allowed = media || (!isFull() && (details || action));
                if (view.isFocusable() != allowed) view.setFocusable(allowed);
                if (view.isFocusableInTouchMode() != allowed) view.setFocusableInTouchMode(allowed);
                if (allowed) focusable.put(view, true);
                if (n.equals("videoView") && !players.containsKey(view)) {
                    players.put(view, true);
                    invoke(view, "setControllerAnimationEnabled", new Class<?>[]{boolean.class}, false);
                    invoke(view, "setControllerAutoShow", new Class<?>[]{boolean.class}, false);
                    invoke(view, "setControllerShowTimeoutMs", new Class<?>[]{int.class}, 0);
                }
                if (n.equals("lLButtons") && view.getAlpha() != 1f) view.setAlpha(1f);
            }
            hideNativeControls();
            if (isFull() && (transportButtons.isEmpty() || videoBar != isVideo())) buildTransport();
            for (View button : transportButtons) focusable.put(button, true);
            syncPlayIcon();
            View focus = activity.getCurrentFocus();
            if (owner != lastOwner || full != lastFull) {
                visibility(transport, View.GONE); lastDetail = null; focusMedia();
            } else if (focus == null || !focus.isShown() || !Boolean.TRUE.equals(focusable.get(focus))) {
                if (transport.getVisibility() == View.VISIBLE) focusTransport(); else focusMedia();
            }
            lastOwner = owner; lastFull = full;
        } finally { updating = false; }
    }
    static void invoke(Object target, String method, Class<?>[] types, Object... args) {
        if (target == null) return;
        try { call(target, method, types, args); }
        catch (Exception error) { android.util.Log.w("TwsTV", "Viewer method unavailable: " + method, error); }
    }
    void hideNativeControls() {
        View player = any("videoView");
        View controller = largest(player, "exo_controller", false);
        // The original APK's pinned Media3 controller exposes hide() as g().
        if (controller != null && controller.getVisibility() == View.VISIBLE)
            invoke(controller, "g", new Class<?>[0]);
    }
    void syncPlayIcon() {
        View original = any("exo_play_pause");
        if (playButton != null && original instanceof ImageView) {
            Drawable drawable = ((ImageView) original).getDrawable();
            if (drawable != null && drawable.getConstantState() != null)
                playButton.setImageDrawable(drawable.getConstantState().newDrawable(activity.getResources()));
            int pause = activity.getResources().getIdentifier("exo_controls_pause_description", "string", activity.getPackageName());
            boolean playing = pause != 0 && activity.getString(pause).contentEquals(original.getContentDescription() == null ? "" : original.getContentDescription());
            String label = TvStrings.text(activity, playing ? "tv_pause" : "tv_play");
            playButton.setContentDescription(label); playButton.setTooltipText(label);
        }
    }
    void focusMedia() { View view = media(); if (view != null) view.requestFocus(); }
    void focusTransport() {
        if (transportButtons.isEmpty()) buildTransport();
        visibility(transport, View.VISIBLE);
        View target = playButton != null ? playButton : transportButtons.get(1);
        target.requestFocus();
        root.postDelayed(() -> {
            if (!closed && transport.getVisibility() == View.VISIBLE && zone(activity.getCurrentFocus()) != Zone.TRANSPORT)
                target.requestFocus();
        }, 80);
        syncTime();
    }
    void hideTransport() { visibility(transport, View.GONE); focusMedia(); }
    int drawable(String wanted) { return activity.getResources().getIdentifier(wanted, "drawable", activity.getPackageName()); }
    ImageButton button(String description, int icon, Runnable action) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(icon); button.setContentDescription(description); button.setTooltipText(description);
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        button.setFocusable(true); button.setFocusableInTouchMode(true);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(activity, 60), dp(activity, 60));
        params.setMargins(dp(activity, 6), 0, dp(activity, 6), 0);
        transport.addView(button, params); transportButtons.add(button); focusable.put(button, true);
        return button;
    }
    void buildTransport() {
        transport.removeAllViews(); transportButtons.clear(); playButton = null; time = null; videoBar = isVideo();
        if (videoBar) {
            time = new TextView(activity); time.setTextColor(0xffeeeeee); time.setTextSize(16);
            time.setGravity(Gravity.CENTER); time.setFocusable(false);
            transport.addView(time, new LinearLayout.LayoutParams(dp(activity, 144), -1));
            button(TvStrings.text(activity, "tv_rewind"), android.R.drawable.ic_media_rew, () -> mediaAction(KeyEvent.KEYCODE_MEDIA_REWIND));
            playButton = button(TvStrings.text(activity, "tv_play_pause"), drawable("ic_baseline_play_circle_outline_24"), () -> mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            button(TvStrings.text(activity, "tv_fast_forward"), android.R.drawable.ic_media_ff, () -> mediaAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD));
            if (any("btnUnmute") != null)
                button(TvStrings.text(activity, "tv_unmute"), drawable("ic_baseline_volume_up_24"), () -> { View mute = any("btnUnmute"); if (mute != null) mute.performClick(); });
        } else {
            button(TvStrings.text(activity, "tv_previous_image"), android.R.drawable.ic_media_previous, () -> actions.page(-1));
            button(TvStrings.text(activity, "tv_next_image"), android.R.drawable.ic_media_next, () -> actions.page(1));
            button(TvStrings.text(activity, "tv_zoom_in"), android.R.drawable.ic_menu_zoom, () -> { hideTransport(); actions.zoom(); });
        }
        button(TvStrings.text(activity, "tv_exit_fullscreen"), drawable("ic_fullscreen"), () -> toggleFullscreen());
        button(TvStrings.text(activity, "tv_details"), android.R.drawable.ic_menu_info_details, () -> {
            toggleFullscreen(); root.postDelayed(() -> focusDetails(), 250);
        });
    }
    void toggleFullscreen() {
        View toggle = find("imgFullscreenRight");
        if (toggle == null) toggle = find("imgFullscreenLeft");
        if (toggle == null) toggle = media();
        if (toggle != null) toggle.performClick();
        visibility(transport, View.GONE);
        root.postDelayed(() -> { List<View> views = new ArrayList<>(); all(root, views); update(views); focusMedia(); }, 180);
    }
    boolean mediaAction(int code) {
        View preview = find("imgPlay");
        if (preview != null) {
            if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) return true;
            preview.performClick(); root.postDelayed(() -> focusMedia(), 200); return true;
        }
        View player = find("videoView"); if (player == null) return false;
        View focus = activity.getCurrentFocus();
        player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        player.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        hideNativeControls(); syncPlayIcon();
        if (transport.getVisibility() == View.VISIBLE) {
            if (focus != null && inside(focus, transport)) focus.requestFocus(); else focusTransport();
        } else focusMedia();
        syncTime();
        return true;
    }
    String clock(long millis) {
        if (millis < 0) return "--:--";
        long seconds = millis / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    void syncTime() {
        if (time == null) return;
        try {
            Object player = call(any("videoView"), "getPlayer", new Class<?>[0]);
            if (player == null) return;
            // Verified getter names in this pinned APK: current position and duration in ms.
            long position = (Long) call(player, "m", new Class<?>[0]);
            long duration = (Long) call(player, "r", new Class<?>[0]);
            String label = clock(position) + " / " + clock(duration);
            if (!label.contentEquals(time.getText())) time.setText(label);
        } catch (Exception error) {
            View position = any("exo_position"), duration = any("exo_duration");
            if (position instanceof TextView && duration instanceof TextView)
                time.setText(((TextView) position).getText() + " / " + ((TextView) duration).getText());
        }
    }
    void activate(View focus) {
        if (zone(focus) == Zone.MEDIA) {
            if (isVideo()) {
                boolean preview = find("imgPlay") != null;
                if (!isFull()) toggleFullscreen();
                mediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                if (!preview) focusTransport();
            } else toggleFullscreen();
        } else if (focus != null && Boolean.TRUE.equals(focusable.get(focus))) focus.performClick();
    }
    List<View> inZone(Zone zone) {
        List<View> result = new ArrayList<>();
        List<View> views = new ArrayList<>(); all(root, views);
        for (View view : views) if (Boolean.TRUE.equals(focusable.get(view)) && zone(view) == zone && view.isShown()) result.add(view);
        Collections.sort(result, (left, right) -> {
            int[] l = new int[2], r = new int[2]; left.getLocationOnScreen(l); right.getLocationOnScreen(r);
            return l[1] != r[1] ? Integer.compare(l[1], r[1]) : Integer.compare(l[0], r[0]);
        });
        return result;
    }
    void focusDetails() {
        List<View> details = inZone(Zone.DETAILS);
        if (lastDetail != null && details.contains(lastDetail)) lastDetail.requestFocus();
        else if (!details.isEmpty()) details.get(0).requestFocus();
    }
    void focusActions() {
        View favourite = find("imgFavourite");
        if (favourite != null) favourite.requestFocus();
        else { List<View> buttons = inZone(Zone.ACTIONS); if (!buttons.isEmpty()) buttons.get(0).requestFocus(); }
    }
    boolean direction(KeyEvent event) {
        int code = event.getKeyCode(); if (code < 19 || code > 22) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        View focus = activity.getCurrentFocus(); Zone zone = zone(focus);
        if (zone == Zone.DETAILS) lastDetail = focus;
        if (zone == Zone.MEDIA) {
            if (code == 21 || code == 22) {
                if (isFull() && isVideo() && find("imgPlay") == null)
                    mediaAction(code == 21 ? KeyEvent.KEYCODE_MEDIA_REWIND : KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                else if (event.getRepeatCount() == 0) actions.page(code == 21 ? -1 : 1);
            } else if (isFull()) focusTransport();
            else if (code == 19) focusDetails(); else focusActions();
        } else if (zone == Zone.TRANSPORT) {
            if (code == 19) hideTransport();
            else if (code == 21 || code == 22) move(transportButtons, focus, code == 21 ? -1 : 1);
        } else if (zone == Zone.ACTIONS) {
            if (code == 19) focusMedia();
            else if (code == 21 || code == 22) move(inZone(Zone.ACTIONS), focus, code == 21 ? -1 : 1);
        } else {
            if (code == 21) focusMedia();
            else if (code == 19 || code == 20) {
                List<View> details = inZone(Zone.DETAILS); int index = details.indexOf(focus);
                if (code == 19 && index <= 0) focusMedia();
                else if (code == 20 && index == details.size() - 1) focusActions();
                else move(details, focus, code == 19 ? -1 : 1);
            }
        }
        return true;
    }
    void move(List<View> views, View focus, int delta) {
        if (views.isEmpty()) return;
        int index = views.indexOf(focus); int next = Math.max(0, Math.min(views.size() - 1, index + delta));
        views.get(next).requestFocus();
    }
    boolean back(KeyEvent event) {
        if (!isFull()) return false;
        if (event.getAction() == KeyEvent.ACTION_UP) toggleFullscreen();
        return true;
    }
}
