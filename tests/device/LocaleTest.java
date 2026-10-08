package se.zepiwolf.tws.tv.localetest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONObject;

public final class LocaleTest extends Instrumentation {
    private Bundle options;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        options = arguments;
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String locale = options.getString("systemLocale");
            if (locale != null) {
                Class<?> am = Class.forName("android.app.ActivityManager");
                Object service = am.getDeclaredMethod("getService").invoke(null);
                Class<?> api = Class.forName("android.app.IActivityManager");
                Configuration config = (Configuration) api.getMethod("getConfiguration").invoke(service);
                config.setLocale(Locale.forLanguageTag(locale));
                Configuration.class.getField("userSetLocale").setBoolean(config, true);
                api.getMethod("updatePersistentConfiguration", Configuration.class).invoke(service, config);
            }
            Context target = getContext().createPackageContext("se.zepiwolf.tws.tv", 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = getContext().getAssets().open("strings.json")) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = stream.read(chunk)) != -1) bytes.write(chunk, 0, count);
            }
            JSONObject catalog = new JSONObject(bytes.toString("UTF-8"));
            String[] languages = {"en-US", "zh-CN", "zh-TW", "zh-HK", "zh-Hant-HK", "ja-JP", "fr-FR"};
            int[] columns = {1, 0, 2, 2, 2, 3, 1};
            int checks = 0;
            for (int i = 0; i < languages.length; i++) {
                Configuration config = new Configuration(target.getResources().getConfiguration());
                config.setLocale(Locale.forLanguageTag(languages[i]));
                Context localized = target.createConfigurationContext(config);
                Iterator<String> keys = catalog.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    int id = localized.getResources().getIdentifier(key, "string", target.getPackageName());
                    String actual = id == 0 ? "<missing>" : localized.getString(id);
                    String expected = catalog.getJSONArray(key).getString(columns[i]);
                    if (!expected.equals(actual)) throw new AssertionError(languages[i] + " " + key);
                    checks++;
                }
            }
            result.putString("stream", "PASS " + checks + " Android resource checks; systemLocale=" + locale + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            result.putString("stream", "FAIL " + error.toString() + "\n");
            finish(0, result);
        }
    }
}
