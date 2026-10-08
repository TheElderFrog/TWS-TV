package se.zepiwolf.tws.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;

/** Phone pairing lives only while the QR dialog is open. */
final class TvLogin {
    final Activity activity;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final EditText username, secret;
    final TextView status;
    final Button submit, mode, phone;
    volatile boolean closed, busy;
    boolean apiMode;
    Pairing pairing;

    TvLogin(Activity activity, ViewGroup root) {
        this.activity = activity;
        for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).setVisibility(View.GONE);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(40), dp(16), dp(40), dp(16));
        panel.setBackgroundColor(0xff0d151e);
        TextView title = text(TvStrings.text(activity, "tv_login_title"), 26); panel.addView(title);
        username = new EditText(activity); username.setHint(TvStrings.text(activity, "tv_username"));
        username.setSingleLine(true); username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        secret = new EditText(activity); secret.setHint(TvStrings.text(activity, "tv_password"));
        secret.setSingleLine(true); secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        for (EditText field : new EditText[]{username, secret}) {
            field.setTextColor(Color.WHITE); field.setTextSize(20); field.setShowSoftInputOnFocus(false);
            panel.addView(field, new LinearLayout.LayoutParams(dp(440), dp(62)));
        }
        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER);
        submit = button(TvStrings.text(activity, "tv_login"), () -> submitNative()); actions.addView(submit);
        phone = button(TvStrings.text(activity, "tv_scan"), () -> showPairing()); actions.addView(phone);
        mode = button(TvStrings.text(activity, "tv_use_api"), () -> toggleMode()); actions.addView(mode);
        panel.addView(actions);
        status = text("", 17); panel.addView(status);
        panel.addView(button(TvStrings.text(activity, "tv_back"), () -> activity.finish()), new LinearLayout.LayoutParams(dp(140), dp(48)));
        root.addView(panel, new ViewGroup.LayoutParams(-1, -1));
        phone.requestFocus();
    }
    void toggleMode() {
            apiMode = !apiMode; secret.setText("");
            secret.setHint(apiMode ? TvStrings.text(activity, "tv_api_key") : TvStrings.text(activity, "tv_password"));
            mode.setText(apiMode ? TvStrings.text(activity, "tv_use_password") : TvStrings.text(activity, "tv_use_api"));
    }
    int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    TextView text(String value, int size) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.WHITE); view.setGravity(Gravity.CENTER); return view;
    }
    Button button(String value, Runnable action) {
        Button view = new Button(activity); view.setText(value); view.setTextSize(17);
        view.setOnClickListener(v -> action.run()); return view;
    }
    void setBusy(boolean value) {
        busy = value; submit.setEnabled(!value); mode.setEnabled(!value); phone.setEnabled(!value);
        username.setEnabled(!value); secret.setEnabled(!value);
    }
    void submitNative() {
        if (busy) return;
        String user = username.getText().toString().trim(), credential = secret.getText().toString();
        final boolean api = apiMode;
        secret.setText(""); setBusy(true); status.setText(TvStrings.text(activity, "tv_verifying"));
        worker.execute(() -> {
            try {
                String key = new AccountAuth().authenticate(user, credential, api);
                main.post(() -> {
                    if (closed || activity.isFinishing()) return;
                    try { commit(user, key); }
                    catch (Exception ex) { setBusy(false); status.setText(TvStrings.text(activity, "tv_save_error")); }
                });
            } catch (Exception ex) {
                main.post(() -> { if (!closed) { setBusy(false); status.setText(message(ex)); } });
            }
        });
    }
    String message(Exception ex) {
        return ex instanceof AccountAuth.Failure
            ? TvStrings.text(activity, ex.getMessage(), ((AccountAuth.Failure) ex).args)
            : TvStrings.text(activity, "tv_network_error");
    }
    void commit(String user, String key) throws Exception {
        EditText originalUser = (EditText) activity.findViewById(resource("eTUsername"));
        EditText originalKey = (EditText) activity.findViewById(resource("eTKey"));
        originalUser.setText(user); originalKey.setText(key);
        // Save through the original app after a read-only authentication check.
        activity.getClass().getMethod("I").invoke(activity);
    }
    int resource(String name) { return activity.getResources().getIdentifier(name, "id", activity.getPackageName()); }
    void showPairing() {
        if (busy || pairing != null) return;
        setBusy(true); status.setText(TvStrings.text(activity, "tv_pair_preparing"));
        worker.execute(() -> {
            try {
                Pairing created = new Pairing();
                main.post(() -> {
                    if (closed) { created.stop(); return; }
                    setBusy(false); status.setText(""); pairing = created; created.show();
                });
            } catch (Exception ex) {
                main.post(() -> { if (!closed) { setBusy(false); status.setText(TvStrings.text(activity, "tv_pair_error")); } });
            }
        });
    }
    void close() { closed = true; if (pairing != null) pairing.stop(); worker.shutdownNow(); }

    final class Pairing {
        final ServerSocket server;
        final KeyPair keys;
        final String path = "/" + UUID.randomUUID().toString().replace("-", "");
        final String host, url, crypto;
        volatile boolean stopped, completed;
        AlertDialog dialog;
        final Runnable expiry = () -> { if (!stopped) { stop(); if (dialog != null) dialog.dismiss(); Toast.makeText(activity, TvStrings.text(activity, "tv_pair_expired"), Toast.LENGTH_LONG).show(); } };
        Pairing() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); keys = generator.generateKeyPair();
            InetAddress local = address();
            if (local == null) throw new Exception("No LAN address");
            crypto = AccountAuth.read(activity.getAssets().open("tv-login-crypto.js"), 256 * 1024);
            server = new ServerSocket(0, 4, local);
            host = local.getHostAddress() + ":" + server.getLocalPort(); url = "http://" + host + path;
        }
        InetAddress address() throws Exception {
            InetAddress fallback = null;
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress value : Collections.list(iface.getInetAddresses())) {
                    if (value instanceof Inet4Address && value.isSiteLocalAddress()) {
                        if (iface.getName().startsWith("wlan") || iface.getName().startsWith("eth")) return value;
                        if (fallback == null) fallback = value;
                    }
                }
            }
            return fallback;
        }
        void show() {
            LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(Gravity.CENTER); panel.setPadding(dp(16), dp(8), dp(16), dp(8));
            try {
                int size = 512;
                BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size);
                int[] pixels = new int[size * size];
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                ImageView qr = new ImageView(activity); qr.setImageBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888));
                panel.addView(qr, new LinearLayout.LayoutParams(dp(208), dp(208)));
            } catch (Exception ignored) { }
            panel.addView(text(url, 16));
            panel.addView(text(TvStrings.text(activity, "tv_pair_hint"), 16));
            dialog = new AlertDialog.Builder(activity).setTitle(TvStrings.text(activity, "tv_phone_login")).setView(panel).setNegativeButton(TvStrings.text(activity, "tv_cancel"), null).create();
            dialog.setOnDismissListener(d -> { stop(); if (pairing == this) pairing = null; });
            dialog.show(); main.postDelayed(expiry, 10 * 60 * 1000);
            Thread thread = new Thread(() -> {
                while (!stopped) {
                    try (Socket socket = server.accept()) { socket.setSoTimeout(10000); serve(socket); }
                    catch (Exception ignored) { }
                }
            }, "TwsPhoneLogin"); thread.setDaemon(true); thread.start();
        }
        void stop() { stopped = true; main.removeCallbacks(expiry); try { server.close(); } catch (Exception ignored) { } }
        void serve(Socket socket) throws Exception {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String request = line(input), method = request.split(" ")[0], target = request.split(" ")[1];
            String requestHost = "", origin = "", contentType = ""; int length = 0, headers = 0;
            for (String header = line(input); !header.isEmpty(); header = line(input)) {
                if ((headers += header.length()) > 8192) throw new Exception("Headers too large");
                int colon = header.indexOf(':'); if (colon < 0) continue;
                String name = header.substring(0, colon).trim(), value = header.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Host")) requestHost = value;
                if (name.equalsIgnoreCase("Origin")) origin = value;
                if (name.equalsIgnoreCase("Content-Type")) contentType = value;
                if (name.equalsIgnoreCase("Content-Length")) length = Integer.parseInt(value);
            }
            if (!host.equals(requestHost) || (!origin.isEmpty() && !origin.equals("http://" + host))) { reply(socket, 403, "text/plain", "Forbidden"); return; }
            if (method.equals("GET") && target.equals(path + "/crypto.js")) { reply(socket, 200, "application/javascript", crypto); return; }
            if (method.equals("GET") && target.equals(path)) { reply(socket, 200, "text/html; charset=UTF-8", page()); return; }
            if (!method.equals("POST") || !target.equals(path) || !contentType.startsWith("application/json") || length <= 0 || length > 4096 || completed) {
                reply(socket, 404, "text/plain", "Not found"); return;
            }
            byte[] body = new byte[length]; int offset = 0;
            while (offset < length) { int count = input.read(body, offset, length - offset); if (count < 0) throw new Exception("Incomplete request"); offset += count; }
            try {
                JSONObject payload = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String user = decrypt(payload.getString("user")).trim(), credential = decrypt(payload.getString("secret"));
                boolean api = "api".equals(payload.optString("mode"));
                String key = new AccountAuth().authenticate(user, credential, api);
                CountDownLatch saved = new CountDownLatch(1); final boolean[] success = {false};
                main.post(() -> {
                    try { if (!stopped && !closed && !activity.isFinishing()) { commit(user, key); success[0] = true; completed = true; } }
                    catch (Exception ignored) { }
                    finally { saved.countDown(); }
                });
                saved.await(5, TimeUnit.SECONDS);
                if (!success[0]) throw new AccountAuth.Failure("tv_pair_closed");
                reply(socket, 200, "application/json", new JSONObject().put("ok", true).put("message", TvStrings.text(activity, "tv_login_done")).toString());
                main.post(() -> { if (dialog != null) dialog.dismiss(); });
            } catch (Exception ex) {
                reply(socket, 400, "application/json", new JSONObject().put("ok", false).put("message", message(ex)).toString());
            }
        }
        String decrypt(String encrypted) throws Exception {
            byte[] bytes = Base64.decode(encrypted, Base64.DEFAULT);
            if (bytes.length != 256) throw new Exception("Invalid ciphertext");
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.DECRYPT_MODE, keys.getPrivate());
            return new String(cipher.doFinal(bytes), StandardCharsets.UTF_8);
        }
        String line(BufferedInputStream input) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value;
            while ((value = input.read()) != -1) { if (value == '\n') break; if (bytes.size() >= 4096) throw new Exception("Line too long"); if (value != '\r') bytes.write(value); }
            if (value == -1) throw new Exception("Closed"); return bytes.toString("UTF-8");
        }
        void reply(Socket socket, int status, String type, String body) throws Exception {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + status + " Response\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; form-action 'none'\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes); output.flush();
        }
        String page() {
            String publicKey = "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(keys.getPublic().getEncoded(), Base64.NO_WRAP) + "\n-----END PUBLIC KEY-----";
            try {
                JSONObject labels = new JSONObject();
                for (String key : new String[]{"tv_web_title", "tv_login_method", "tv_account_password", "tv_api_key", "tv_username", "tv_password", "tv_login_tv", "tv_manage_api", "tv_verifying", "tv_input_long", "tv_encrypt_error", "tv_phone_network"})
                    labels.put(key, TvStrings.text(activity, key));
                String template = AccountAuth.read(activity.getAssets().open("tv-login.html"), 64 * 1024);
                return template.replace("{{language}}", activity.getResources().getConfiguration().getLocales().get(0).toLanguageTag())
                    .replace("{{translations}}", labels.toString().replace("<", "\\u003c"))
                    .replace("{{public_key}}", JSONObject.quote(publicKey)).replace("{{path}}", path);
            } catch (Exception error) {
                throw new IllegalStateException("Phone login template unavailable", error);
            }
        }
    }
}
