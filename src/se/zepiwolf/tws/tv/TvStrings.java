package se.zepiwolf.tws.tv;

import android.content.Context;

/** Resolve against the current Activity configuration, never a cached locale. */
final class TvStrings {
    static String text(Context context, String key, Object... args) {
        int id = context.getResources().getIdentifier(key, "string", context.getPackageName());
        if (id == 0) throw new IllegalArgumentException("Missing TV string: " + key);
        return args.length == 0 ? context.getString(id) : context.getString(id, args);
    }
}
