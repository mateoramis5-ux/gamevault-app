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
    private static final int PERM_CAMERA_PHOTO = 21, PERM_CAMERA_LIVE = 22;

    private WebView web;
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
        setContentView(web);
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
        public String version() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
