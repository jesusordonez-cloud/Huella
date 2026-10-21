package org.fraacmx.huella;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.provider.DocumentsContract;
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

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.Style;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;
import org.maplibre.android.snapshotter.MapSnapshotter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private static final int REQ_PERMISSIONS = 2001;
    private static final int REQ_FILE_CHOOSER = 2002;
    private static final int REQ_SAVE_FOLDER = 2003;
    private static final String PREFS_NAME = "huella_prefs";
    private static final String PREF_SAVE_TREE = "save_tree_uri";
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html?v=423";
    private static final String MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String fileChooserPurpose = "generic";
    private LocationManager locationManager;
    private LocationListener activeLocationListener;
    private MapSnapshotter activeSnapshotter;

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
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);

        try {
            MapLibre.getInstance(this);
        } catch (Throwable ignored) {
        }

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

    private void notifyOfflineMapProgress(int percent, double mb, String status) {
        if (webView == null) return;
        String safe = status == null ? "" : status
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String js = "window.HuellaOfflineDownloadProgress&&window.HuellaOfflineDownloadProgress(" +
                percent + "," +
                String.format(Locale.US, "%.2f", mb) + ",'" +
                safe + "');";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void notifyOfflineMapError(String message) {
        if (webView == null) return;
        String safe = message == null ? "No se pudo preparar el mapa offline" : message
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        String js = "window.HuellaOfflineMapError&&window.HuellaOfflineMapError('" +
                safe + "');";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void downloadOfflineMap(double lat, double lon, double radiusKm) {
        runOnUiThread(() -> {
            try {
                double safeRadius = Math.max(1.0, Math.min(200.0, radiusKm));
                double latDelta = safeRadius / 111.32;
                double lonDelta = safeRadius /
                        (111.32 * Math.max(0.2, Math.cos(Math.toRadians(lat))));

                LatLngBounds bounds = new LatLngBounds.Builder()
                        .include(new LatLng(lat + latDelta, lon + lonDelta))
                        .include(new LatLng(lat - latDelta, lon - lonDelta))
                        .build();

                double minZoom;
                double maxZoom;
                if (safeRadius <= 5.0) {
                    minZoom = 13.0;
                    maxZoom = 16.0;
                } else if (safeRadius <= 10.0) {
                    minZoom = 12.0;
                    maxZoom = 15.0;
                } else if (safeRadius <= 25.0) {
                    minZoom = 11.0;
                    maxZoom = 14.0;
                } else if (safeRadius <= 50.0) {
                    minZoom = 10.0;
                    maxZoom = 13.0;
                } else if (safeRadius <= 100.0) {
                    minZoom = 9.0;
                    maxZoom = 12.0;
                } else {
                    minZoom = 8.0;
                    maxZoom = 11.0;
                }

                OfflineTilePyramidRegionDefinition definition =
                        new OfflineTilePyramidRegionDefinition(
                                MAP_STYLE_URL,
                                bounds,
                                minZoom,
                                maxZoom,
                                1.0f,
                                false
                        );

                String metadataText = "{\"name\":\"Huella " + safeRadius +
                        " km\",\"lat\":" + lat + ",\"lon\":" + lon + "}";
                byte[] metadata = metadataText.getBytes(StandardCharsets.UTF_8);

                notifyOfflineMapProgress(0, 0.0, "Preparando descarga…");

                OfflineManager manager = OfflineManager.getInstance(this);
                manager.createOfflineRegion(
                        definition,
                        metadata,
                        new OfflineManager.CreateOfflineRegionCallback() {
                            @Override
                            public void onCreate(OfflineRegion region) {
                                region.setObserver(new OfflineRegion.OfflineRegionObserver() {
                                    @Override
                                    public void onStatusChanged(OfflineRegionStatus status) {
                                        long completed = status.getCompletedResourceCount();
                                        long required = status.getRequiredResourceCount();
                                        int percent = required > 0
                                                ? (int) Math.min(100, (completed * 100L) / required)
                                                : 0;
                                        double mb = status.getCompletedResourceSize() /
                                                (1024.0 * 1024.0);
                                        long completedTiles = status.getCompletedTileCount();

                                        if (!status.isComplete() &&
                                                (mb >= 49.0 || completedTiles >= 2950)) {
                                            region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                            notifyOfflineMapError(
                                                    "La descarga alcanzó el límite seguro del proveedor. Reduce el área o vuelve a intentarlo."
                                            );
                                            return;
                                        }

                                        if (status.isComplete()) {
                                            region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                            notifyOfflineMapProgress(
                                                    100,
                                                    mb,
                                                    "Zona offline lista"
                                            );
                                            requestMapSnapshot(lat, lon);
                                        } else {
                                            notifyOfflineMapProgress(
                                                    percent,
                                                    mb,
                                                    "Descargando mapa…"
                                            );
                                        }
                                    }

                                    @Override
                                    public void onError(OfflineRegionError error) {
                                        notifyOfflineMapError(
                                                error == null
                                                        ? "Error descargando el mapa"
                                                        : error.getMessage()
                                        );
                                    }

                                    @Override
                                    public void mapboxTileCountLimitExceeded(long limit) {
                                        region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                        notifyOfflineMapError(
                                                "La zona seleccionada supera el límite de descarga"
                                        );
                                    }
                                });
                                region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                            }

                            @Override
                            public void onError(String error) {
                                notifyOfflineMapError(error);
                            }
                        }
                );
            } catch (Throwable error) {
                notifyOfflineMapError("No se pudo iniciar la descarga offline");
            }
        });
    }

    private void requestMapSnapshot(double lat, double lon) {
        runOnUiThread(() -> {
            try {
                if (activeSnapshotter != null) {
                    try { activeSnapshotter.cancel(); } catch (Throwable ignored) {}
                    activeSnapshotter = null;
                }

                MapSnapshotter.Options options = new MapSnapshotter.Options(900, 560)
                        .withPixelRatio(1.0f)
                        .withStyleBuilder(new Style.Builder().fromUri(MAP_STYLE_URL))
                        .withCameraPosition(
                                new CameraPosition.Builder()
                                        .target(new LatLng(lat, lon))
                                        .zoom(15.5)
                                        .build()
                        );

                activeSnapshotter = new MapSnapshotter(
                        getApplicationContext(),
                        options
                );

                notifyOfflineMapProgress(0, 0.0, "Preparando vista…");

                activeSnapshotter.start(
                        snapshot -> {
                            try {
                                Bitmap source = snapshot.getBitmap();
                                Bitmap bitmap = source.copy(
                                        Bitmap.Config.ARGB_8888,
                                        true
                                );

                                Canvas canvas = new Canvas(bitmap);
                                Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
                                outer.setColor(Color.WHITE);
                                canvas.drawCircle(
                                        bitmap.getWidth() / 2f,
                                        bitmap.getHeight() / 2f,
                                        15f,
                                        outer
                                );
                                Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
                                inner.setColor(Color.rgb(255, 70, 76));
                                canvas.drawCircle(
                                        bitmap.getWidth() / 2f,
                                        bitmap.getHeight() / 2f,
                                        10f,
                                        inner
                                );

                                File file = new File(
                                        getFilesDir(),
                                        "offline_map_preview.png"
                                );
                                try (FileOutputStream out = new FileOutputStream(file)) {
                                    bitmap.compress(
                                            Bitmap.CompressFormat.PNG,
                                            95,
                                            out
                                    );
                                }

                                pushMapSnapshotToWeb();
                                notifyOfflineMapProgress(
                                        100,
                                        0.0,
                                        "Vista del mapa lista"
                                );
                            } catch (Throwable error) {
                                notifyOfflineMapError(
                                        "No se pudo generar la vista del mapa"
                                );
                            } finally {
                                activeSnapshotter = null;
                            }
                        },
                        error -> {
                            activeSnapshotter = null;
                            notifyOfflineMapError(
                                    error == null
                                            ? "No se pudo cargar la vista del mapa"
                                            : error
                            );
                        }
                );
            } catch (Throwable error) {
                activeSnapshotter = null;
                notifyOfflineMapError("No se pudo preparar la vista del mapa");
            }
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void chooseSaveFolder() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            );
            String saved = prefs().getString(PREF_SAVE_TREE, null);
            if (Build.VERSION.SDK_INT >= 26 && saved != null && !saved.isEmpty()) {
                try {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(saved));
                } catch (Exception ignored) {
                }
            }
            startActivityForResult(intent, REQ_SAVE_FOLDER);
        } catch (Exception error) {
            Toast.makeText(
                    this,
                    "No se pudo abrir el selector de carpetas",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String selectedFolderLabel() {
        String saved = prefs().getString(PREF_SAVE_TREE, null);
        if (saved == null || saved.isEmpty()) return "Descargas/Huella";
        try {
            Uri uri = Uri.parse(saved);
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (id == null || id.isEmpty()) return "Carpeta personalizada";
            int colon = id.indexOf(':');
            if (colon >= 0) {
                String volume = id.substring(0, colon);
                String path = id.substring(colon + 1);
                if ("primary".equalsIgnoreCase(volume)) {
                    return path.isEmpty() ? "Almacenamiento interno" : path;
                }
                return path.isEmpty() ? volume : volume + "/" + path;
            }
            return id;
        } catch (Exception ignored) {
            return "Carpeta personalizada";
        }
    }

    private void notifySaveFolderToWeb() {
        if (webView == null) return;
        String label = selectedFolderLabel()
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        webView.evaluateJavascript(
                "window.HuellaSaveFolderChanged&&window.HuellaSaveFolderChanged('" +
                        label + "');",
                null
        );
    }

    private String saveToPreferredLocation(
            String fileName,
            byte[] data,
            String mimeType
    ) throws Exception {
        String saved = prefs().getString(PREF_SAVE_TREE, null);
        if (saved != null && !saved.isEmpty()) {
            try {
                Uri treeUri = Uri.parse(saved);
                String documentId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId
                );
                Uri fileUri = DocumentsContract.createDocument(
                        getContentResolver(),
                        parent,
                        mimeType,
                        fileName
                );
                if (fileUri == null) {
                    throw new IllegalStateException("No se pudo crear el archivo");
                }
                try (OutputStream out = getContentResolver().openOutputStream(
                        fileUri,
                        "w"
                )) {
                    if (out == null) {
                        throw new IllegalStateException("No se pudo abrir el archivo");
                    }
                    out.write(data);
                    out.flush();
                }
                return selectedFolderLabel();
            } catch (Exception customError) {
                // Si Android revocó el permiso, se regresa al destino seguro.
                prefs().edit().remove(PREF_SAVE_TREE).apply();
            }
        }

        saveToDownloads(fileName, data, mimeType);
        return "Descargas/Huella";
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

        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] result = WebChromeClient.FileChooserParams.parseResult(
                        resultCode,
                        data
                );
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }

            if ("logo".equals(fileChooserPurpose) && webView != null) {
                webView.postDelayed(() -> webView.evaluateJavascript(
                        "window.HuellaResumeCamera&&window.HuellaResumeCamera();",
                        null
                ), 650);
            }
            fileChooserPurpose = "generic";
            return;
        }

        if (requestCode == REQ_SAVE_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                int flags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                         Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(
                            treeUri,
                            flags
                    );
                } catch (Exception ignored) {
                }
                prefs().edit()
                        .putString(PREF_SAVE_TREE, treeUri.toString())
                        .apply();
                Toast.makeText(
                        this,
                        "Carpeta de guardado actualizada",
                        Toast.LENGTH_SHORT
                ).show();
                if (webView != null) {
                    webView.post(this::notifySaveFolderToWeb);
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.postDelayed(this::pushMapSnapshotToWeb, 350);
            webView.postDelayed(() -> webView.evaluateJavascript(
                    "window.HuellaNativeResume&&window.HuellaNativeResume();",
                    null
            ), 500);
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
        if (activeSnapshotter != null) {
            try { activeSnapshotter.cancel(); } catch (Throwable ignored) {}
            activeSnapshotter = null;
        }
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
            runOnUiThread(() -> MainActivity.this.requestMapSnapshot(lat, lon));
        }

        @JavascriptInterface
        public void downloadOfflineMap(double lat, double lon, double radiusKm) {
            MainActivity.this.downloadOfflineMap(lat, lon, radiusKm);
        }

        @JavascriptInterface
        public void requestMapSnapshot(double lat, double lon) {
            MainActivity.this.requestMapSnapshot(lat, lon);
        }

        @JavascriptInterface
        public void setFileChooserPurpose(String purpose) {
            fileChooserPurpose = purpose == null ? "generic" : purpose;
        }

        @JavascriptInterface
        public void chooseSaveFolder() {
            runOnUiThread(() -> MainActivity.this.chooseSaveFolder());
        }

        @JavascriptInterface
        public String getSaveFolderLabel() {
            return selectedFolderLabel();
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
                String destination = saveToPreferredLocation(
                        safeName,
                        bytes,
                        mimeType == null ? "application/octet-stream" : mimeType
                );
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Guardado en " + destination,
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
