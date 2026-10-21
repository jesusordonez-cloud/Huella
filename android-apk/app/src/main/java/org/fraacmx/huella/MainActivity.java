package org.fraacmx.huella;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private static final int REQ_PERMISSIONS = 2001;
    private static final int REQ_FILE_CHOOSER = 2002;
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private LocationManager locationManager;
    private LocationListener activeLocationListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(5, 14, 10));
        getWindow().setNavigationBarColor(Color.rgb(5, 14, 10));
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 14, 10));

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);

        applySystemBarInsets(root);
        requestRuntimePermissions();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);

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
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
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
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "No se pudo abrir el selector de imágenes",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.loadUrl(APP_URL);
    }

    private void applySystemBarInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }

            v.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            boolean locationGranted = Build.VERSION.SDK_INT < 23 ||
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (locationGranted) requestBestLocation();
        }
    }

    private void requestBestLocation() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions();
            notifyLocationError("Permiso de ubicación pendiente");
            return;
        }

        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        }
        if (locationManager == null) {
            notifyLocationError("GPS no disponible");
            return;
        }

        Location best = null;
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null && net != null) best = gps.getAccuracy() <= net.getAccuracy() ? gps : net;
            else best = gps != null ? gps : net;
        } catch (SecurityException ignored) {
        }

        if (best != null) sendLocationToWeb(best);

        if (activeLocationListener != null) {
            try { locationManager.removeUpdates(activeLocationListener); } catch (Exception ignored) {}
        }

        activeLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                sendLocationToWeb(location);
                try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
                activeLocationListener = null;
            }

            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };

        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        0L,
                        0f,
                        activeLocationListener,
                        Looper.getMainLooper()
                );
                requested = true;
            }
        } catch (Exception ignored) {
        }

        if (!requested) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            0L,
                            0f,
                            activeLocationListener,
                            Looper.getMainLooper()
                    );
                    requested = true;
                }
            } catch (Exception ignored) {
            }
        }

        if (!requested && best == null) notifyLocationError("Activa la ubicación del teléfono");
    }

    private void sendLocationToWeb(Location location) {
        if (webView == null || location == null) return;
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        float acc = location.hasAccuracy() ? location.getAccuracy() : 0f;

        String js = "window.HuellaNativeLocation&&window.HuellaNativeLocation(" +
                lat + "," + lon + "," + acc + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void notifyLocationError(String message) {
        if (webView == null) return;
        String escaped = message.replace("\\", "\\\\").replace("'", "\\'");
        String js = "window.HuellaNativeLocationError&&window.HuellaNativeLocationError('" +
                escaped + "');";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private boolean isBiometricAvailable() {
        try {
            BiometricManager manager = BiometricManager.from(this);
            return manager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void requestBiometricAuth() {
        if (!isBiometricAvailable()) {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.HuellaBiometricUnavailable&&window.HuellaBiometricUnavailable();",
                        null
                );
            }
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result
                    ) {
                        super.onAuthenticationSucceeded(result);
                        if (webView != null) {
                            webView.evaluateJavascript(
                                    "window.HuellaBiometricSuccess&&window.HuellaBiometricSuccess();",
                                    null
                            );
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        if (webView != null) {
                            String msg = errString == null ? "Autenticación cancelada" :
                                    errString.toString()
                                            .replace("\\", "\\\\")
                                            .replace("'", "\\'");
                            webView.evaluateJavascript(
                                    "window.HuellaBiometricError&&window.HuellaBiometricError('" +
                                            msg + "');",
                                    null
                            );
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        if (webView != null) {
                            webView.evaluateJavascript(
                                    "window.HuellaBiometricError&&window.HuellaBiometricError('Huella no reconocida');",
                                    null
                            );
                        }
                    }
                }
        );

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Huella")
                .setSubtitle("Confirma tu identidad para ingresar")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancelar")
                .build();

        prompt.authenticate(info);
    }

    private void openOfflineMap(double lat, double lon) {
        try {
            Intent intent = new Intent(this, MapActivity.class);
            intent.putExtra("lat", lat);
            intent.putExtra("lon", lon);
            startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(
                    this,
                    "No se pudo abrir el mapa offline",
                    Toast.LENGTH_LONG
            ).show();
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.HuellaOfflineMapError&&window.HuellaOfflineMapError();",
                        null
                );
            }
        }
    }

    private void pushMapSnapshotToWeb() {
        if (webView == null) return;
        File file = new File(getFilesDir(), "offline_map_preview.png");
        if (!file.exists() || file.length() <= 0 || file.length() > 8_000_000) return;

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            int read;
            while (offset < bytes.length &&
                    (read = in.read(bytes, offset, bytes.length - offset)) > 0) {
                offset += read;
            }
            if (offset <= 0) return;
            String b64 = Base64.encodeToString(bytes, 0, offset, Base64.NO_WRAP);
            String js = "window.HuellaNativeMapSnapshot&&window.HuellaNativeMapSnapshot(" +
                    "'data:image/png;base64," + b64 + "');";
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {
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
        if (webView != null) {
            webView.onResume();
            webView.postDelayed(this::pushMapSnapshotToWeb, 350);
        }
    }

    @Override
    protected void onPause() {
        if (locationManager != null && activeLocationListener != null) {
            try { locationManager.removeUpdates(activeLocationListener); } catch (Exception ignored) {}
            activeLocationListener = null;
        }
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (locationManager != null && activeLocationListener != null) {
            try { locationManager.removeUpdates(activeLocationListener); } catch (Exception ignored) {}
        }
        if (webView != null) {
            webView.removeJavascriptInterface("HuellaAndroid");
            webView.destroy();
        }
        super.onDestroy();
    }

    private class NativeBridge {
        @JavascriptInterface
        public boolean biometricAvailable() {
            return isBiometricAvailable();
        }

        @JavascriptInterface
        public void requestBiometric() {
            runOnUiThread(() -> requestBiometricAuth());
        }

        @JavascriptInterface
        public void openOfflineMap(double lat, double lon) {
            runOnUiThread(() -> MainActivity.this.openOfflineMap(lat, lon));
        }

        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(() -> requestBestLocation());
        }

        @JavascriptInterface
        public void saveBlob(String fileName, String base64Data, String mimeType) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                String safeName = sanitizeFileName(fileName);
                saveToDownloads(
                        safeName,
                        bytes,
                        mimeType == null ? "application/octet-stream" : mimeType
                );
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Guardado en Descargas/Huella",
                        Toast.LENGTH_SHORT
                ).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "No se pudo guardar el archivo",
                        Toast.LENGTH_LONG
                ).show());
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
            values.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Huella"
            );
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
            );
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
            File root = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
            );
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
