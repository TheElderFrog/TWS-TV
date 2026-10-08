package se.zepiwolf.tws.tv;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** A private HTTPS session per attempt; never installs a global cookie handler. */
final class AccountAuth {
    private static final String HOST = "https://e621.net";
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    static final class Failure extends Exception {
        final Object[] args;
        Failure(String key, Object... args) { super(key); this.args = args; }
    }
    static final class Reply {
        final int code; final String body;
        Reply(int code, String body) { this.code = code; this.body = body; }
    }
    String authenticate(String username, String secret, boolean apiMode) throws Exception {
        if (username.isEmpty() || username.length() > 30 || username.contains(":")) throw new Failure("tv_invalid_username");
        if (secret.isEmpty()) throw new Failure(apiMode ? "tv_enter_api" : "tv_enter_password");
        String key = apiMode ? secret : fetchKey(username, secret);
        String basic = Base64.encodeToString((username + ":" + key).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        Reply check = request("/favorites.json?limit=1", null, null, "Basic " + basic);
        if (check.code == 401 || check.code == 403) throw new Failure("tv_auth_failed");
        require(check);
        new JSONObject(check.body).getJSONArray("posts");
        return key;
    }
    private String fetchKey(String username, String password) throws Exception {
        // The JSON variant returns an HTML error page with the session's CSRF meta tag.
        Reply page = request("/session/new.json", null, null, null);
        Element token = Jsoup.parse(page.body).selectFirst("meta[name=csrf-token]");
        if (token == null) throw new Failure("tv_browser_required");
        String csrf = token.attr("content");
        Reply login = request("/session.json", "session[name]=" + encode(username) + "&session[password]=" + encode(password) + "&session[remember]=0", csrf, null);
        if (login.code == 401) {
            JSONObject error = new JSONObject(login.body);
            if ("totp_required".equals(error.optString("code"))) throw new Failure("tv_totp_required");
            throw new Failure("tv_wrong_password");
        }
        require(login);
        Reply keys = request("/api_keys.json", null, null, null);
        require(keys);
        JSONArray list = new JSONArray(keys.body);
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String expiry = item.optString("expires_at", "");
            boolean active = item.isNull("expires_at") || expiry.isEmpty();
            if (!active) {
                try {
                    android.text.format.Time date = new android.text.format.Time("UTC");
                    active = date.parse3339(expiry) && date.toMillis(false) > now;
                }
                catch (Exception ignored) { }
            }
            if (active && !item.optString("key").isEmpty()) return item.getString("key");
        }
        Reply created = request("/api_keys.json", "api_key[name]=TWS-TV-" + System.currentTimeMillis() + "&api_key[duration]=never", csrf, null);
        require(created);
        String key = new JSONObject(created.body).optString("key");
        if (key.isEmpty()) throw new Failure("tv_create_api_error");
        return key;
    }
    private static String encode(String text) throws Exception { return URLEncoder.encode(text, "UTF-8"); }
    private static void require(Reply reply) throws Failure {
        if (reply.code == 429) throw new Failure("tv_rate_limit");
        if (reply.code < 200 || reply.code >= 300) throw new Failure("tv_http_error", reply.code);
    }
    private Reply request(String path, String body, String csrf, String auth) throws Exception {
        URL url = new URL(HOST + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "TWS-TV/2.0 (local Android TV adaptation)");
            connection.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, List<String>> entry : cookies.get(url.toURI(), java.util.Collections.emptyMap()).entrySet())
                connection.setRequestProperty(entry.getKey(), android.text.TextUtils.join("; ", entry.getValue()));
            if (auth != null) connection.setRequestProperty("Authorization", auth);
            if (csrf != null) connection.setRequestProperty("X-CSRF-Token", csrf);
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                connection.getOutputStream().write(data);
            }
            int code = connection.getResponseCode();
            cookies.put(url.toURI(), connection.getHeaderFields());
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Reply(code, input == null ? "" : read(input, 2 * 1024 * 1024));
        } finally { connection.disconnect(); }
    }
    static String read(InputStream input, int limit) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (result.size() + count > limit) throw new Failure("tv_response_large");
                result.write(buffer, 0, count);
            }
            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
