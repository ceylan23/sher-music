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
            String clienttime = String.valueOf(System.currentTimeMillis()/1000);

            // standard params
            p.put("dfid", dfid);
            p.put("mid", mid);
            p.put("uuid", "-");
            p.put("appid", APP_ID);
            p.put("clientver", CLIENT_VER);
            p.put("clienttime", clienttime);
            p.put("srcappid", SRC_APP_ID);
            if (cookies.containsKey("token"))  p.put("token", cookies.get("token"));
            if (cookies.containsKey("userid")) p.put("userid", cookies.get("userid"));

            String base, method = "GET", encType = "android", xrouter = null;
            String postData = body.length > 0 ? new String(body, "UTF-8") : "";
            Map<String,String> extraHeaders = new LinkedHashMap<>();

            // ---- route mapping ----

            if (path.contains("/search/complex")) {
                base = "https://complexsearch.kugou.com"; path = "/v6/search/complex";
                p.put("platform","AndroidFilter");
                move(p,"keywords","keyword");
                dflt(p,"page","1"); dflt(p,"pagesize","30"); dflt(p,"cursor","0");

            } else if (path.contains("/search/hot")) {
                base = "https://gateway.kugou.com"; path = "/api/v3/search/hot_tab";
                p.put("navid","1"); p.put("plat","2");
                xrouter = "msearch.kugou.com";

            } else if (path.contains("/search/lyric")) {
                base = "https://gateway.kugou.com"; path = "/v1/search/lyric";
                p.put("platform","AndroidFilter");
                move(p,"keywords","keyword");
                dflt(p,"page","1"); dflt(p,"pagesize","5");

            } else if (path.contains("/search/")) {
                base = "https://gateway.kugou.com"; path = "/v3/search/song";
                p.put("platform","AndroidFilter");
                move(p,"keywords","keyword");
                dflt(p,"page","1"); dflt(p,"pagesize","30");
                p.put("iscorrection","1"); p.put("albumhide","0"); p.put("nocollect","0");

            } else if (path.contains("/recommend/songs")) {
                // KuGou expects POST, but web UI calls via GET
                base = "https://gateway.kugou.com"; path = "/everyday_song_recommend";
                method = "POST"; xrouter = "everydayrec.service.kugou.com";
                p.put("platform","android");
                dflt(p,"userid","0");
                postData = "{\"platform\":\"android\",\"userid\":" + p.get("userid") + "}";

            } else if (path.contains("/personal/fm")) {
                // KuGou expects POST, but web UI calls via GET
                base = "https://gateway.kugou.com"; path = "/v2/personal_recommend";
                method = "POST"; xrouter = "persnfm.service.kugou.com";
                // No body, no special key - standard android signing on query params only

            } else if (path.contains("/yueku/banner")) {
                base = "https://gateway.kugou.com"; path = "/ads.gateway/v3/listen_banner";
                method = "POST";
                p.put("plat","0"); p.put("channel","201"); p.put("operator","7");
                p.put("apiver","5"); p.put("mode","normal");
                postData = "{\"plat\":\"0\",\"channel\":\"201\",\"operator\":\"7\",\"apiver\":\"5\",\"mode\":\"normal\"}";

            } else if (path.contains("/top/playlist")) {
                // KuGou expects POST, but web UI calls via GET
                base = "https://gateway.kugou.com"; path = "/v2/special_recommend";
                method = "POST"; xrouter = "specialrec.service.kugou.com";
                String catId = p.getOrDefault("category_id","0");
                String pg = p.getOrDefault("page","1");
                String ps = p.getOrDefault("pagesize","30");
                p.put("key", signParamsKey(clienttime));
                p.remove("category_id");
                // Build JSON body matching Node.js module
                postData = "{\"appid\":\"" + APP_ID + "\",\"mid\":\"" + mid + "\","
                    + "\"clientver\":\"" + CLIENT_VER + "\",\"platform\":\"android\","
                    + "\"clienttime\":\"" + clienttime + "\",\"userid\":\"" + p.getOrDefault("userid","0") + "\","
                    + "\"module_id\":1,\"page\":" + pg + ",\"pagesize\":" + ps + ","
                    + "\"key\":\"" + signParamsKey(clienttime) + "\","
                    + "\"special_recommend\":{\"withtag\":1,\"withsong\":1,\"sort\":1,"
                    + "\"ugc\":1,\"is_selected\":0,\"withrecommend\":1,\"area_code\":1,"
                    + "\"categoryid\":" + catId + "},"
                    + "\"req_multi\":1,\"retrun_min\":5,\"return_special_falg\":1}";

            } else if (path.contains("/playlist/track/all")) {
                base = "https://gateway.kugou.com"; path = "/pubsongs/v2/get_other_list_file_nofilt";
                String id = p.getOrDefault("id","");
                int page = Integer.parseInt(p.getOrDefault("page","1"));
                int ps2 = Integer.parseInt(p.getOrDefault("pagesize","100"));
                p.clear(); p.put("dfid",dfid); p.put("mid",mid); p.put("uuid","-");
                p.put("appid",APP_ID); p.put("clientver",CLIENT_VER);
                p.put("clienttime",clienttime); p.put("srcappid",SRC_APP_ID);
                p.put("global_collection_id",id);
                p.put("area_code","1"); p.put("mode","1");
                p.put("pagesize",String.valueOf(ps2));
                p.put("begin_idx",String.valueOf((page-1)*ps2));
                p.put("extend_fields","abtags,hot_cmt,popularization");

            } else if (path.contains("/rank/list")) {
                base = "https://gateway.kugou.com"; path = "/ocean/v6/rank/list";
                p.put("plat","2"); dflt(p,"withsong","1"); dflt(p,"parentid","0");

            } else if (path.contains("/rank/audio")) {
                // KuGou expects POST, but web UI calls via GET
                base = "https://gateway.kugou.com"; path = "/openapi/kmr/v2/rank/audio";
                method = "POST";
                String rankId = p.getOrDefault("rankid","");
                String pg = p.getOrDefault("page","1");
                String ps3 = p.getOrDefault("pagesize","30");
                p.remove("rankid");
                // Build JSON body matching Node.js module
                postData = "{\"show_portrait_mv\":1,\"show_type_total\":1,"
                    + "\"filter_original_remarks\":1,\"area_code\":1,"
                    + "\"pagesize\":" + ps3 + ",\"rank_cid\":"
                    + p.getOrDefault("rank_cid","0") + ","
                    + "\"type\":1,\"page\":" + pg + ",\"rank_id\":\"" + rankId + "\"}";
                extraHeaders.put("kg-tid", "369");

            } else if (path.contains("/user/detail")) {
                base = "https://gateway.kugou.com"; path = "/v3/get_my_info";
                method = "POST"; xrouter = "usercenter.kugou.com";
                p.put("usertype","1");
                postData = "{\"usertype\":1}";

            } else if (path.contains("/user/playlist")) {
                base = "https://gateway.kugou.com"; path = "/v7/get_all_list";
                method = "POST"; xrouter = "cloudlist.service.kugou.com";
                p.put("type","2"); p.put("total_ver","979");
                dflt(p,"page","1"); dflt(p,"pagesize","30");
                String uid = p.getOrDefault("userid","0");
                postData = "{\"userid\":\"" + uid + "\",\"type\":2,"
                    + "\"page\":" + p.getOrDefault("page","1") + ","
                    + "\"pagesize\":" + p.getOrDefault("pagesize","30") + "}";

            } else if (path.contains("/user/history")) {
                base = "https://gateway.kugou.com"; path = "/playhistory/v1/get_songs";
                method = "POST";
                p.put("source_classify","app"); p.put("to_subdivide_sr","1");
                postData = "{\"source_classify\":\"app\",\"to_subdivide_sr\":1}";

            } else if (path.contains("/song/url")) {
                base = "https://gateway.kugou.com"; path = "/v5/url";
                xrouter = "trackercdn.kugou.com";
                String hash = p.getOrDefault("hash","").toLowerCase();
                String albumId = p.getOrDefault("album_id","");
                String quality = p.getOrDefault("quality","128");
                String freePart = p.getOrDefault("free_part","0");
                p.clear(); p.put("appid",APP_ID); p.put("clientver","11430");
                p.put("dfid",dfid); p.put("mid",mid); p.put("uuid","-");
                p.put("clienttime",clienttime);
                p.put("key", md5(hash + SIGN_KEY_SALT + APP_ID + mid + "0"));
                p.put("hash",hash); p.put("album_id",albumId);
                p.put("album_audio_id","0"); p.put("area_code","1");
                p.put("behavior","play"); p.put("cdnBackup","1");
                p.put("cmd","26"); p.put("module","");
                p.put("pid","2"); p.put("pidversion","3001");
                p.put("page_id","151369488");
                p.put("ppage_id","463467626,350369493,788954147");
                p.put("ssa_flag","is_fromtrack");
                p.put("IsFreePart","1".equals(freePart) ? "1" : "0");
                p.put("quality",quality); p.put("version","11430");

            } else if (path.contains("/lyric")) {
                base = "https://lyrics.kugou.com"; path = "/download";
                dflt(p,"ver","1"); dflt(p,"client","android"); dflt(p,"fmt","krc");
                dflt(p,"charset","utf8");

            } else if (path.contains("/login/qr/key")) {
                base = "https://login-user.kugou.com"; path = "/v2/get_qrcode";
                p.clear(); p.put("appid","1005"); p.put("srcappid",SRC_APP_ID); p.put("plat","4");
                encType = "web";

            } else if (path.contains("/login/qr/create")) {
                base = "https://login-user.kugou.com"; path = "/v2/create_qrcode";
                String key = p.getOrDefault("key","");
                p.clear(); p.put("appid","1005"); p.put("srcappid",SRC_APP_ID);
                p.put("key",key); p.put("qrimg","true");
                encType = "web";

            } else if (path.contains("/login/qr/check")) {
                base = "https://login-user.kugou.com"; path = "/v2/get_userinfo_qrcode";
                String key = p.getOrDefault("key","");
                p.clear(); p.put("appid","1005"); p.put("srcappid",SRC_APP_ID);
                p.put("plat","4"); p.put("qrcode",key);
                encType = "web";

            } else if (path.contains("/register/dev")) {
                base = "https://gateway.kugou.com"; path = "/v5/register_dev";
                p.put("platform","android");
                encType = "register";

            } else {
                base = "https://gateway.kugou.com";
                // fallback: keep path as-is
            }

            // sign (song_url uses notSign - only key param, no signature)
            boolean notSign = path.contains("/v5/url");
            String sig = notSign ? "" : sign(p, encType, postData);

            // build URL
            StringBuilder url = new StringBuilder(base).append(path).append("?");
            for (Map.Entry<String,String> e : p.entrySet())
                url.append(enc(e.getKey())).append("=").append(enc(e.getValue())).append("&");
            if (!notSign) url.append("signature=").append(sig);

            String resp;
            if ("POST".equals(method)) {
                resp = httpPost(url.toString(), postData, dfid, mid, clienttime, xrouter, extraHeaders);
            } else {
                resp = httpGet(url.toString(), dfid, mid, clienttime, xrouter, extraHeaders);
            }
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

    private static String signParamsKey(String data) {
        return md5(APP_ID + ANDROID_SALT + CLIENT_VER + data);
    }

    /* ---- HTTP ---- */

    private String httpGet(String urlStr, String dfid, String mid, String clienttime,
                            String xrouter, Map<String,String> extraHeaders) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("dfid", dfid);
            c.setRequestProperty("mid", mid);
            c.setRequestProperty("clienttime", clienttime);
            c.setRequestProperty("kg-rc", "1");
            c.setRequestProperty("kg-thash", "5d816a0");
            c.setRequestProperty("kg-rec", "1");
            c.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
            if (xrouter != null) c.setRequestProperty("x-router", xrouter);
            if (extraHeaders != null) for (Map.Entry<String,String> e : extraHeaders.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            return slurp(is);
        } catch (Exception e) {
            Log.e(TAG, "httpGet: " + e.getMessage());
            return "{\"status\":0,\"error\":\""+e.getMessage()+"\"}";
        }
    }

    private String httpPost(String urlStr, String postData, String dfid, String mid,
                            String clienttime, String xrouter, Map<String,String> extraHeaders) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("dfid", dfid);
            c.setRequestProperty("mid", mid);
            c.setRequestProperty("clienttime", clienttime);
            c.setRequestProperty("kg-rc", "1");
            c.setRequestProperty("kg-thash", "5d816a0");
            c.setRequestProperty("kg-rec", "1");
            c.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
            c.setRequestProperty("Content-Type", "application/json");
            if (xrouter != null) c.setRequestProperty("x-router", xrouter);
            if (extraHeaders != null) for (Map.Entry<String,String> e : extraHeaders.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
            if (postData != null && !postData.isEmpty()) {
                OutputStream os = c.getOutputStream();
                os.write(postData.getBytes("UTF-8"));
                os.flush(); os.close();
            }
            InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            return slurp(is);
        } catch (Exception e) {
            Log.e(TAG, "httpPost: " + e.getMessage());
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
