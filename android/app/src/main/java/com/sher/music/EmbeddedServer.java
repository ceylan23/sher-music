package com.sher.music;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Embedded HTTP server: serves web UI from assets + proxies API calls to KuGou with signing.
 * This makes the app fully self-contained - no external server needed.
 */
public class EmbeddedServer extends Thread {
    private static final String TAG = "SherServer";
    private final Context context;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private int port;

    private static final String ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp";
    private static final String WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    private static final String SIGN_KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d";
    private static final String APP_ID = "1005";
    private static final String CLIENT_VER = "20489";
    private static final String SRC_APP_ID = "2919";
    private static final String UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    public EmbeddedServer(Context context) {
        this.context = context;
        setDaemon(true);
        setName("SherServer");
    }

    public int getPort() { return port; }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            port = serverSocket.getLocalPort();
            Log.i(TAG, "Listening on 127.0.0.1:" + port);
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    new Thread(() -> handle(s)).start();
                } catch (IOException e) {
                    if (running) Log.e(TAG, "accept", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "start", e);
        }
    }

    public void stopServer() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    /* ---- connection handler ---- */

    private void handle(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            String reqLine = reader.readLine();
            if (reqLine == null) { socket.close(); return; }
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) { socket.close(); return; }
            String method = parts[0];
            String raw = URLDecoder.decode(parts[1], "UTF-8");

            Map<String,String> headers = new LinkedHashMap<>();
            int clen = 0;
            String h;
            while ((h = reader.readLine()) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0) {
                    String k = h.substring(0,i).trim().toLowerCase();
                    String v = h.substring(i+1).trim();
                    headers.put(k, v);
                    if (k.equals("content-length")) clen = Integer.parseInt(v);
                }
            }
            byte[] body = new byte[0];
            if (clen > 0) { body = new byte[clen]; int r=0; while(r<clen) r+=in.read(body,r,clen-r); }

            String path = raw, query = "";
            int qi = raw.indexOf('?');
            if (qi >= 0) { path = raw.substring(0, qi); query = raw.substring(qi+1); }

            Map<String,String> cookies = parseCookies(headers.get("cookie"));

            // routing
            if (path.equals("/") || path.equals("/index.html")) {
                write(out, 200, "text/html", readFile("index.html"));
            } else if (isApi(path)) {
                routeApi(out, path, query, body, cookies);
            } else {
                String fn = path.startsWith("/") ? path.substring(1) : path;
                try { write(out, 200, mime(fn), readFile(fn)); }
                catch (IOException e) { write(out, 404, "text/plain", "Not Found"); }
            }
            out.flush();
            socket.close();
        } catch (Exception e) {
            Log.e(TAG, "handle: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /* ---- API routing ---- */

    private boolean isApi(String path) {
        String s = path.startsWith("/") ? path.substring(1) : path;
        int sl = s.indexOf('/');
        String first = sl > 0 ? s.substring(0, sl) : s;
        // any non-file path is treated as API
        return !first.contains(".");
    }

    private void routeApi(OutputStream out, String path, String query,
                          byte[] body, Map<String,String> cookies) {
        try {
            Map<String,String> p = parseQuery(query);
            String mid = UUID.randomUUID().toString().replace("-","");
            String dfid = cookies.getOrDefault("dfid",
                UUID.randomUUID().toString().replace("-","") + "-" + System.currentTimeMillis());

            // standard params
            p.put("dfid", dfid);
            p.put("mid", mid);
            p.put("uuid", "-");
            p.put("appid", APP_ID);
            p.put("clientver", CLIENT_VER);
            p.put("clienttime", String.valueOf(System.currentTimeMillis()/1000));
            p.put("srcappid", SRC_APP_ID);
            if (cookies.containsKey("token"))  p.put("token", cookies.get("token"));
            if (cookies.containsKey("userid")) p.put("userid", cookies.get("userid"));

            String base, api, encType = "android";
            String postData = body.length > 0 ? new String(body, "UTF-8") : "";

            if (path.contains("/search/complex")) {
                base = "https://complexsearch.kugou.com";
                api = "/v6/search/complex";
                p.put("platform","AndroidFilter");
                move(p,"keywords","keyword");
                dflt(p,"page","1"); dflt(p,"pagesize","30"); dflt(p,"cursor","0");

            } else if (path.contains("/search/lyric")) {
                base = "https://gateway.kugou.com";
                api = "/v1/search/lyric";
                p.put("platform","AndroidFilter");
                move(p,"keywords","keyword");
                dflt(p,"page","1"); dflt(p,"pagesize","5");

            } else if (path.contains("/search/")) {
                base = "https://gateway.kugou.com";
                api = "/v3/search/song";
                p.put("platform","AndroidFilter");
                move(p,"keywords","keyword");
                dflt(p,"page","1"); dflt(p,"pagesize","30");
                p.put("iscorrection","1"); p.put("albumhide","0"); p.put("nocollect","0");

            } else if (path.contains("/song/url")) {
                base = "https://gateway.kugou.com";
                api = "/v5/url";
                String hash = p.getOrDefault("hash","");
                String albumId = p.getOrDefault("album_id","");
                p.clear();
                p.put("appid",APP_ID); p.put("clientver",CLIENT_VER);
                p.put("dfid",dfid); p.put("mid",mid); p.put("uuid","-");
                p.put("clienttime",String.valueOf(System.currentTimeMillis()/1000));
                p.put("key", md5(hash + SIGN_KEY_SALT + APP_ID + mid + "0"));
                p.put("pid","2"); p.put("behavior","play");
                p.put("area_code","1"); p.put("hash",hash);
                p.put("album_id",albumId); p.put("module","");
                p.put("openbear","1"); p.put("cmd","26"); p.put("version","1070");

            } else if (path.contains("/login/qr/key")) {
                base = "https://login-user.kugou.com";
                api = "/v2/get_qrcode";
                p.clear(); p.put("appid","1005"); p.put("srcappid",SRC_APP_ID); p.put("plat","4");
                encType = "web";

            } else if (path.contains("/login/qr/create")) {
                base = "https://login-user.kugou.com";
                api = "/v2/create_qrcode";
                String key = p.getOrDefault("key","");
                p.clear(); p.put("appid","1005"); p.put("srcappid",SRC_APP_ID);
                p.put("key",key); p.put("qrimg","true");
                encType = "web";

            } else if (path.contains("/login/qr/check")) {
                base = "https://login-user.kugou.com";
                api = "/v2/get_userinfo_qrcode";
                String key = p.getOrDefault("key","");
                p.clear(); p.put("appid","1005"); p.put("srcappid",SRC_APP_ID);
                p.put("plat","4"); p.put("qrcode",key);
                encType = "web";

            } else if (path.contains("/register/dev")) {
                base = "https://gateway.kugou.com";
                api = "/v5/register_dev";
                p.put("platform","android");
                encType = "register";

            } else if (path.contains("/lyric")) {
                base = "https://gateway.kugou.com";
                api = path;
                // pass through

            } else if (path.contains("/recommend/songs")) {
                base = "https://gateway.kugou.com";
                api = "/v2/get_recommend";

            } else {
                base = "https://gateway.kugou.com";
                api = path;
            }

            String sig = sign(p, encType, postData);
            StringBuilder url = new StringBuilder(base).append(api).append("?");
            for (Map.Entry<String,String> e : p.entrySet())
                url.append(enc(e.getKey())).append("=").append(enc(e.getValue())).append("&");
            url.append("signature=").append(sig);

            String resp = httpGet(url.toString(), dfid, mid);
            write(out, 200, "application/json", resp);
        } catch (Exception e) {
            Log.e(TAG, "routeApi: " + e.getMessage(), e);
            try { write(out, 502, "application/json", "{\"error\":\""+e.getMessage()+"\"}"); }
            catch (IOException ignored) {}
        }
    }

    /* ---- signing ---- */

    private String sign(Map<String,String> p, String type, String data) {
        if ("web".equals(type)) {
            StringBuilder sb = new StringBuilder(WEB_SALT);
            p.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()));
            sb.append(WEB_SALT);
            return md5(sb.toString());
        } else if ("register".equals(type)) {
            StringBuilder sb = new StringBuilder("1014");
            p.values().stream().sorted().forEach(sb::append);
            sb.append("1014");
            return md5(sb.toString());
        } else {
            StringBuilder sb = new StringBuilder(ANDROID_SALT);
            p.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()));
            if (!data.isEmpty()) sb.append(data);
            sb.append(ANDROID_SALT);
            return md5(sb.toString());
        }
    }

    private static String md5(String s) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /* ---- HTTP ---- */

    private String httpGet(String urlStr, String dfid, String mid) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("dfid", dfid);
            c.setRequestProperty("mid", mid);
            c.setRequestProperty("clienttime", String.valueOf(System.currentTimeMillis()/1000));
            InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            return slurp(is);
        } catch (Exception e) {
            Log.e(TAG, "httpGet: " + e.getMessage());
            return "{\"status\":0,\"error\":\""+e.getMessage()+"\"}";
        }
    }

    /* ---- helpers ---- */

    private void write(OutputStream out, int code, String type, String body) throws IOException {
        write(out, code, type, body.getBytes("UTF-8"));
    }
    private void write(OutputStream out, int code, String type, byte[] data) throws IOException {
        String st = code == 200 ? "OK" : "Error";
        String hdr = "HTTP/1.1 "+code+" "+st+"\r\n"+
            "Content-Type: "+type+";charset=UTF-8\r\n"+
            "Content-Length: "+data.length+"\r\n"+
            "Connection: close\r\n"+
            "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(hdr.getBytes("UTF-8"));
        out.write(data);
    }

    private byte[] readFile(String name) throws IOException {
        AssetManager am = context.getAssets();
        InputStream is = am.open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String slurp(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder(); char[] c = new char[4096]; int n;
        while ((n = r.read(c)) != -1) sb.append(c, 0, n);
        r.close();
        return sb.toString();
    }

    private static Map<String,String> parseQuery(String q) {
        Map<String,String> m = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) try {
                m.put(URLDecoder.decode(pair.substring(0,i),"UTF-8"),
                      URLDecoder.decode(pair.substring(i+1),"UTF-8"));
            } catch (Exception e) {
                m.put(pair.substring(0,i), pair.substring(i+1));
            }
        }
        return m;
    }

    private static Map<String,String> parseCookies(String hdr) {
        Map<String,String> c = new HashMap<>();
        if (hdr == null) return c;
        for (String p : hdr.split(";")) {
            int i = p.trim().indexOf('=');
            if (i > 0) c.put(p.trim().substring(0,i), p.trim().substring(i+1));
        }
        return c;
    }

    private static void move(Map<String,String> m, String from, String to) {
        if (m.containsKey(from)) { m.put(to, m.get(from)); m.remove(from); }
    }
    private static void dflt(Map<String,String> m, String k, String v) {
        if (!m.containsKey(k)) m.put(k, v);
    }
    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private static String mime(String f) {
        if (f.endsWith(".html")) return "text/html";
        if (f.endsWith(".css"))  return "text/css";
        if (f.endsWith(".js"))   return "application/javascript";
        if (f.endsWith(".json")) return "application/json";
        if (f.endsWith(".png"))  return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".svg"))  return "image/svg+xml";
        return "application/octet-stream";
    }
}
