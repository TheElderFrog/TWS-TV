package se.zepiwolf.tws.tv;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;

/** TV defaults are applied once; all original user settings remain editable. */
public final class TvApplication extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate();
        SharedPreferences p = getSharedPreferences("user_preferences", MODE_PRIVATE);
        if (!p.getBoolean("tv_defaults_v1", false)) {
            SharedPreferences.Editor e = p.edit();
            if (!p.contains("grid_width")) e.putInt("grid_width", 5);
            if (!p.contains("grid_height")) e.putInt("grid_height", 110);
            if (!p.contains("general_language")) e.putString("general_language", "zh-CN");
            e.putBoolean("tv_defaults_v1", true).apply();
        }
        registerActivityLifecycleCallbacks(this);
    }
    @Override public void onActivityCreated(Activity a, Bundle b) { TvSupport.install(a); }
    @Override public void onActivityResumed(Activity a) { TvSupport.resume(a); }
    @Override public void onActivityDestroyed(Activity a) { TvSupport.release(a); }
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
}
