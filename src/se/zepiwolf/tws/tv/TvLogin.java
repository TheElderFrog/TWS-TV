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
        TextView title = text("账号登录", 26); panel.addView(title);
        username = new EditText(activity); username.setHint("用户名");
        username.setSingleLine(true); username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        secret = new EditText(activity); secret.setHint("密码");
        secret.setSingleLine(true); secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        for (EditText field : new EditText[]{username, secret}) {
            field.setTextColor(Color.WHITE); field.setTextSize(20); field.setShowSoftInputOnFocus(false);
            panel.addView(field, new LinearLayout.LayoutParams(dp(440), dp(62)));
        }
        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER);
        submit = button("登录", () -> submitNative()); actions.addView(submit);
        phone = button("手机扫码", () -> showPairing()); actions.addView(phone);
        mode = button("使用 API 密钥", () -> toggleMode()); actions.addView(mode);
        panel.addView(actions);
        status = text("", 17); panel.addView(status);
        panel.addView(button("返回", () -> activity.finish()), new LinearLayout.LayoutParams(dp(140), dp(48)));
        root.addView(panel, new ViewGroup.LayoutParams(-1, -1));
        phone.requestFocus();
    }
    void toggleMode() {
            apiMode = !apiMode; secret.setText("");
            secret.setHint(apiMode ? "API 密钥" : "密码");
            mode.setText(apiMode ? "使用账号密码" : "使用 API 密钥");
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
        secret.setText(""); setBusy(true); status.setText("正在验证账号…");
        worker.execute(() -> {
            try {
                String key = new AccountAuth().authenticate(user, credential, api);
                main.post(() -> {
                    if (closed || activity.isFinishing()) return;
                    try { commit(user, key); }
                    catch (Exception ex) { setBusy(false); status.setText("保存登录信息失败，请重试"); }
                });
            } catch (Exception ex) {
                main.post(() -> { if (!closed) { setBusy(false); status.setText(message(ex)); } });
            }
        });
    }
    static String message(Exception ex) {
        return ex instanceof AccountAuth.Failure ? ex.getMessage() : "连接网站失败，请检查网络后重试";
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
        setBusy(true); status.setText("正在准备手机登录…");
        worker.execute(() -> {
            try {
                Pairing created = new Pairing();
                main.post(() -> {
                    if (closed) { created.stop(); return; }
                    setBusy(false); status.setText(""); pairing = created; created.show();
                });
            } catch (Exception ex) {
                main.post(() -> { if (!closed) { setBusy(false); status.setText("无法创建手机连接，请确认电视已连接局域网"); } });
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
        final Runnable expiry = () -> { if (!stopped) { stop(); if (dialog != null) dialog.dismiss(); Toast.makeText(activity, "二维码已过期，请重新打开手机扫码", Toast.LENGTH_LONG).show(); } };
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
            panel.addView(text("手机与电视连接同一网络，二维码 10 分钟内有效", 16));
            dialog = new AlertDialog.Builder(activity).setTitle("手机登录").setView(panel).setNegativeButton("取消", null).create();
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
                if (!success[0]) throw new AccountAuth.Failure("二维码已关闭，请重新扫码");
                reply(socket, 200, "application/json", new JSONObject().put("ok", true).put("message", "电视已登录，可以关闭本页").toString());
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
            return "<!doctype html><html lang=zh-CN><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>TWS TV 登录</title>"
                + "<style>*{box-sizing:border-box}body{margin:0;background:#0d151e;color:#fff;font:17px system-ui}main{max-width:480px;margin:auto;padding:24px}h1{font-size:26px}label{display:block;margin:18px 0 6px}input,select,button{width:100%;padding:14px;font:inherit;border-radius:6px;border:1px solid #526775;background:#17212c;color:white}button{margin-top:24px;background:#216378}a{color:#73e3ff}p{line-height:1.5}</style>"
                + "<main><h1>TWS TV 登录</h1><label for=mode>登录方式</label><select id=mode><option value=password>账号密码</option><option value=api>API 密钥</option></select>"
                + "<form id=form><label for=user>用户名</label><input id=user maxlength=30 autocomplete=username required><label id=secretLabel for=secret>密码</label><input id=secret type=password autocomplete=current-password required>"
                + "<button id=submit>登录电视</button></form><p id=status role=status></p><p><a href='https://e621.net/api_keys' target=_blank rel=noopener>管理 API 密钥</a></p></main>"
                + "<script src='" + path + "/crypto.js'></script><script>const mode=document.getElementById('mode'),secret=document.getElementById('secret'),user=document.getElementById('user'),button=document.getElementById('submit'),status=document.getElementById('status');"
                + "mode.onchange=()=>{secret.value='';document.getElementById('secretLabel').textContent=mode.value==='api'?'API 密钥':'密码';secret.autocomplete=mode.value==='api'?'off':'current-password'};"
                + "const rsa=new JSEncrypt();rsa.setPublicKey(" + JSONObject.quote(publicKey) + ");document.getElementById('form').onsubmit=async e=>{e.preventDefault();button.disabled=true;status.textContent='正在验证账号…';try{"
                + "if(new TextEncoder().encode(user.value).length>200||new TextEncoder().encode(secret.value).length>200)throw Error('输入内容过长');const payload={user:rsa.encrypt(user.value),secret:rsa.encrypt(secret.value),mode:mode.value};secret.value='';if(!payload.user||!payload.secret)throw Error('加密失败，请重新打开二维码');"
                + "const r=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}),data=await r.json();status.textContent=data.message;if(data.ok){document.getElementById('form').remove();mode.disabled=true}else button.disabled=false;"
                + "}catch(e){status.textContent=e.message==='Failed to fetch'?'连接电视失败，请检查二维码是否仍打开':e.message;button.disabled=false}};</script></html>";
        }
    }
}
