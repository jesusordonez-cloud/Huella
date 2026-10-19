package org.fraacmx.huella;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
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

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 2001;
    private static final int REQ_FILE_CHOOSER = 2002;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestRuntimePermissions();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.addJavascriptInterface(new NativeBridge(), "HuellaAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = assetLoader.shouldInterceptRequest(request.getUrl());
                return response != null ? response : super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) {
                    return false;
                }
                if (request.isForMainFrame()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch (Exception ignored) {
                    }
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (request.getOrigin() != null &&
                            "appassets.androidplatform.net".equals(request.getOrigin().getHost())) {
                        List<String> allowed = new ArrayList<>();
                        for (String resource : request.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                                if (Build.VERSION.SDK_INT < 23 ||
                                        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    allowed.add(resource);
                                }
                            }
                        }
                        if (!allowed.isEmpty()) {
                            request.grant(allowed.toArray(new String[0]));
                        } else {
                            request.deny();
                        }
                    } else {
                        request.deny();
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean granted = Build.VERSION.SDK_INT < 23 ||
                        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "No se pudo abrir el selector de imágenes", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.loadUrl(APP_URL);
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER && filePathCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("HuellaAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }

    private class NativeBridge {
        @JavascriptInterface
        public void saveBlob(String fileName, String base64Data, String mimeType) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                String safeName = sanitizeFileName(fileName);
                saveToDownloads(safeName, bytes, mimeType == null ? "application/octet-stream" : mimeType);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Guardado en Descargas/Huella", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "No se pudo guardar el archivo", Toast.LENGTH_LONG).show());
            }
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "Huella";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void saveToDownloads(String fileName, byte[] data, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Huella");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IllegalStateException("No se pudo crear el archivo");

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("No se pudo abrir el archivo");
                out.write(data);
                out.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        } else {
            File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File folder = new File(root, "Huella");
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IllegalStateException("No se pudo crear la carpeta");
            }
            File file = new File(folder, fileName);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
                out.flush();
            }
            Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scan.setData(Uri.fromFile(file));
            sendBroadcast(scan);
        }
    }
}
