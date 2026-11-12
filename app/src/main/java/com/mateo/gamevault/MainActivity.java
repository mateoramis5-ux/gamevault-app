package com.mateo.gamevault;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html#inicio";
    private static final int REQ_FILE = 11, REQ_CAMERA_PHOTO = 12;
    private static final int PERM_CAMERA_PHOTO = 21, PERM_CAMERA_LIVE = 22, PERM_MEDIA = 23;

    private WebView web;
    private WebView hidden;
    private final java.util.ArrayDeque<String[]> pageQueue = new java.util.ArrayDeque<>();
    private String[] pageCur = null;
    private Runnable pageTimeout;
    private int pageGen = 0;

    private void setupHidden() {
        WebSettings hs = hidden.getSettings();
        hs.setJavaScriptEnabled(true);
        hs.setDomStorageEnabled(true);
        hs.setLoadsImagesAutomatically(false);
        hs.setBlockNetworkImage(true);
        hs.setUserAgentString(hs.getUserAgentString().replace("; wv", ""));
        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(hidden, true);
        for (String d : new String[]{"https://www.google.es", "https://www.google.com"}) {
            cm.setCookie(d, "CONSENT=YES+cb.20240101-00-p0.es+FX+000; Domain=" + d.replace("https://www", "") + "; Path=/");
            cm.setCookie(d, "SOCS=CAESHAgBEhJnd3NfMjAyNDAxMDEtMF9SQzIaAmVzIAEaBgiA_LyvBg; Domain=" + d.replace("https://www", "") + "; Path=/");
        }
        hidden.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (pageCur == null) return;
                final int g = pageGen;
                ui.postDelayed(() -> grabPage(g, 0), 2500);
            }
        });
    }

    private void nextPage() {
        if (pageCur != null || pageQueue.isEmpty()) return;
        pageCur = pageQueue.poll();
        final int g = ++pageGen;
        pageTimeout = () -> { if (g == pageGen && pageCur != null) grabPage(g, 99); };
        ui.postDelayed(pageTimeout, 30000);
        hidden.loadUrl(pageCur[1]);
    }

    private void grabPage(final int g, final int tries) {
        if (g != pageGen || pageCur == null) return;
        final boolean html = "html".equals(pageCur[2]);
        String js = "(function(){var t=document.title||'';var b=document.body?document.body.innerText:'';var busy=/just a moment|un momento|checking your browser|verify you are human/i.test(t+' '+b.slice(0,400));return JSON.stringify({busy:busy,url:location.href,body:" + (html ? "document.documentElement.outerHTML" : "b") + "});})()";
        hidden.evaluateJavascript(js, raw -> {
            if (g != pageGen || pageCur == null) return;
            try {
                String str = new org.json.JSONTokener(raw).nextValue().toString();
                org.json.JSONObject o = new org.json.JSONObject(str);
                if (o.optBoolean("busy") && tries < 6) { ui.postDelayed(() -> grabPage(g, tries + 1), 2500); return; }
                finishPage(200, o.optString("url") + "\n" + o.optString("body"));
            } catch (Exception e) {
                finishPage(0, "");
            }
        });
    }

    private void finishPage(int code, String body) {
        String[] cur = pageCur;
        pageCur = null;
        if (pageTimeout != null) ui.removeCallbacks(pageTimeout);
        hidden.stopLoading();
        if (cur != null) {
            String js = "window.gvFetchDone&&gvFetchDone(" + org.json.JSONObject.quote(cur[0]) + "," + code + "," + org.json.JSONObject.quote(body) + ")";
            web.evaluateJavascript(js, null);
        }
        ui.postDelayed(this::nextPage, 400);
    }
    private WebViewAssetLoader loader;
    private ValueCallback<Uri[]> fileCallback;
    private File cameraFile;
    private PermissionRequest pendingWebPermission;
    private boolean pageReady = false;
    private final List<String> pendingImages = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("gv", MODE_PRIVATE);
        loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF101219);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        hidden = new WebView(this);
        hidden.setAlpha(0f);
        root.addView(hidden, new android.widget.FrameLayout.LayoutParams(1080, 1920));
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        setupHidden();
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setSupportMultipleWindows(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!pageReady) {
                    pageReady = true;
                    ui.postDelayed(MainActivity.this::flushPending, 2500);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                boolean image = true;
                String[] acc = p.getAcceptTypes();
                StringBuilder all = new StringBuilder();
                if (acc != null) for (String a : acc) all.append(a == null ? "" : a);
                String accept = all.toString();
                if (accept.length() > 0 && !accept.contains("image")) image = false;
                if (image && p.isCaptureEnabled()) {
                    if (hasCamera()) launchCamera();
                    else requestPermissions(new String[]{Manifest.permission.CAMERA}, PERM_CAMERA_PHOTO);
                    return true;
                }
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(image ? "image/*" : "*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, image ? "Elegir foto" : "Elegir archivo"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    toast("No se pudo abrir la galería");
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                ui.post(() -> {
                    if (hasCamera()) request.grant(request.getResources());
                    else {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, PERM_CAMERA_LIVE);
                    }
                });
            }
        });

        if (saved != null) {
            String cf = saved.getString("cameraFile");
            if (cf != null) cameraFile = new File(cf);
        }
        web.loadUrl(START);
        handleShare(getIntent());
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraFile != null) out.putString("cameraFile", cameraFile.getAbsolutePath());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.gvBack&&window.gvBack())?'1':'0'", r -> {
            if (r == null || !r.contains("1")) moveTaskToBack(true);
        });
    }

    // ---------- Fotos ----------
    private boolean hasCamera() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            dir.mkdirs();
            cameraFile = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
            prefs.edit().putString("cameraFile", cameraFile.getAbsolutePath()).apply();
            Uri uri = FileProvider.getUriForFile(this, "com.mateo.gamevault.files", cameraFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA_PHOTO);
        } catch (Exception e) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = null;
            toast("No se pudo abrir la cámara");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (code == PERM_CAMERA_PHOTO) {
            if (ok) launchCamera();
            else {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = null;
                toast("Sin permiso de cámara");
            }
        } else if (code == PERM_MEDIA) {
            if (ok) deliverLatest();
            else web.evaluateJavascript("window.gvLatestFail&&gvLatestFail('perm')", null);
        } else if (code == PERM_CAMERA_LIVE && pendingWebPermission != null) {
            if (ok) pendingWebPermission.grant(pendingWebPermission.getResources());
            else pendingWebPermission.deny();
            pendingWebPermission = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        Uri[] uris = null;
        if (result == RESULT_OK) {
            if (req == REQ_CAMERA_PHOTO) {
                if (cameraFile == null) {
                    String cf = prefs.getString("cameraFile", null);
                    if (cf != null) cameraFile = new File(cf);
                }
                if (cameraFile != null && cameraFile.length() > 0) uris = new Uri[]{Uri.fromFile(cameraFile)};
            } else if (req == REQ_FILE && data != null) {
                ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    uris = new Uri[cd.getItemCount()];
                    for (int k = 0; k < cd.getItemCount(); k++) uris[k] = cd.getItemAt(k).getUri();
                } else if (data.getData() != null) uris = new Uri[]{data.getData()};
            }
        }
        prefs.edit().remove("cameraFile").apply();
        if (fileCallback != null) {
            // file:// del caché no es legible por la página: se pasa como content:// del FileProvider
            if (uris != null && req == REQ_CAMERA_PHOTO)
                uris = new Uri[]{FileProvider.getUriForFile(this, "com.mateo.gamevault.files", cameraFile)};
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        } else if (uris != null) {
            // Android cerró la app mientras estaba la cámara o la galería: la foto no se pierde
            for (Uri u : uris) {
                String d = readAsDataUrl(u);
                if (d != null) pendingImages.add(d);
            }
            if (pageReady) ui.postDelayed(this::flushPending, 1500);
        }
    }

    private String mediaPerm() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES" : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void deliverLatest() {
        new Thread(() -> {
            String d = null;
            try (android.database.Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID}, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    Uri u = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                    d = readAsDataUrl(u);
                }
            } catch (Exception ignored) {
            }
            final String data = d;
            ui.post(() -> {
                if (data == null) web.evaluateJavascript("window.gvLatestFail&&gvLatestFail('none')", null);
                else web.evaluateJavascript("window.gvShared&&window.gvShared([\"" + data + "\"])", null);
            });
        }).start();
    }

    private void handleShare(Intent intent) {
        if (intent == null) return;
        String act = intent.getAction();
        ArrayList<Uri> list = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(act)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) list.add(u);
            else if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
                list.add(intent.getClipData().getItemAt(0).getUri());
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(act)) {
            ArrayList<Uri> us = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) list.addAll(us);
        }
        if (list.isEmpty()) return;
        intent.setAction(null);
        for (Uri u : list) {
            String d = readAsDataUrl(u);
            if (d != null) pendingImages.add(d);
        }
        if (pendingImages.isEmpty()) toast("No se pudo leer la imagen compartida");
        else if (pageReady) ui.postDelayed(this::flushPending, 300);
    }

    private void flushPending() {
        if (pendingImages.isEmpty()) return;
        StringBuilder js = new StringBuilder("window.gvShared&&window.gvShared([");
        for (int k = 0; k < pendingImages.size(); k++) {
            if (k > 0) js.append(',');
            js.append('"').append(pendingImages.get(k)).append('"');
        }
        js.append("])");
        pendingImages.clear();
        web.evaluateJavascript(js.toString(), null);
    }

    private String readAsDataUrl(Uri u) {
        try {
            String mime = getContentResolver().getType(u);
            if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) return null;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                bytes = bo.toByteArray();
            }
            if (bytes.length > 9 * 1024 * 1024) { // reduce fotos enormes
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                if (b != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 90, bo);
                    bytes = bo.toByteArray();
                    mime = "image/jpeg";
                }
            }
            return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception e) {
            toast("No hay navegador para abrir el enlace");
        }
    }

    private void toast(String m) {
        ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    // ---------- Puente con la página ----------
    class Bridge {
        @JavascriptInterface
        public boolean saveFile(String name, String text) {
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                String mime = name.endsWith(".csv") ? "text/csv" : "application/json";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(data);
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    try (FileOutputStream fo = new FileOutputStream(new File(dir, name))) {
                        fo.write(data);
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private final java.util.Map<String, OutputStream> streams = new java.util.HashMap<>();

        @JavascriptInterface
        public String fileStart(String name, String mime) {
            try {
                OutputStream os;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "";
                    os = getContentResolver().openOutputStream(uri);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    os = new FileOutputStream(new File(dir, name));
                }
                if (os == null) return "";
                String id = "f" + System.nanoTime();
                streams.put(id, new java.io.BufferedOutputStream(os, 1 << 20));
                return id;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean fileAppend(String id, String b64) {
            try {
                OutputStream os = streams.get(id);
                if (os == null) return false;
                os.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean fileEnd(String id) {
            try {
                OutputStream os = streams.remove(id);
                if (os == null) return false;
                os.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String clipboardImage() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = cm.getPrimaryClip();
                if (clip == null) return "";
                for (int k = 0; k < clip.getItemCount(); k++) {
                    ClipData.Item it = clip.getItemAt(k);
                    if (it.getUri() != null) {
                        String d = readAsDataUrl(it.getUri());
                        if (d != null) return d;
                    }
                    CharSequence t = it.getText();
                    if (t != null && t.toString().startsWith("data:image/")) return t.toString();
                }
            } catch (Exception ignored) {
            }
            return "";
        }

        @JavascriptInterface
        public void fetchPage(final String id, final String url, final String mode) {
            ui.post(() -> { pageQueue.add(new String[]{id, url, mode}); nextPage(); });
        }

        @JavascriptInterface
        public void fetchText(final String id, final String url) {
            new Thread(() -> {
                int code = 0;
                String body = "";
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
                    c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0 && bo.size() < 3_000_000) bo.write(buf, 0, n);
                        in.close();
                        body = bo.toString("UTF-8");
                    }
                    String fin = c.getURL().toString();
                    body = fin + "\n" + body;
                } catch (Exception e) {
                    body = "";
                }
                final String js = "window.gvFetchDone&&gvFetchDone(" + org.json.JSONObject.quote(id) + "," + code + "," + org.json.JSONObject.quote(body) + ")";
                ui.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void latestImage() {
            ui.post(() -> {
                if (checkSelfPermission(mediaPerm()) == PackageManager.PERMISSION_GRANTED) deliverLatest();
                else requestPermissions(new String[]{mediaPerm()}, PERM_MEDIA);
            });
        }

        @JavascriptInterface
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
