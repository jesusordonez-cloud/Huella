package org.fraacmx.huella;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;
import org.maplibre.android.module.http.HttpRequestUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;

public class MapActivity extends Activity {
    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

    private MapView mapView;
    private MapLibreMap map;
    private TextView statusText;
    private ProgressBar progressBar;
    private Spinner radiusSpinner;
    private Button downloadButton;

    private double latitude;
    private double longitude;
    private boolean finishingWithSnapshot = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        latitude = getIntent().getDoubleExtra("lat", 0.0);
        longitude = getIntent().getDoubleExtra("lon", 0.0);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder()
                                .header("User-Agent", "Huella/4.1 (Forest Rangers Alliance A.C.)")
                                .build()
                ))
                .build();
        try {
            HttpRequestUtil.setOkHttpClient(client);
            MapLibre.getInstance(this);
        } catch (Throwable error) {
            Toast.makeText(this, "No se pudo iniciar el motor del mapa", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 14, 10));

        mapView = new MapView(this);
        root.addView(mapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        topBar.setBackgroundColor(Color.argb(115, 4, 14, 10));

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTextColor(Color.WHITE);
        back.setBackgroundColor(Color.argb(90, 255, 255, 255));
        topBar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView title = new TextView(this);
        title.setText("  Mapa offline");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        topBar.addView(title, titleParams);

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(70)
        );
        topParams.gravity = Gravity.TOP;
        root.addView(topBar, topParams);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        panel.setBackgroundColor(Color.argb(205, 5, 16, 12));

        TextView heading = new TextView(this);
        heading.setText("Descargar zona para trabajar sin internet");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(16);
        panel.addView(heading);

        TextView desc = new TextView(this);
        desc.setText("El mapa queda guardado dentro de Huella. Máximo 5 km alrededor del GPS actual.");
        desc.setTextColor(Color.argb(185, 255, 255, 255));
        desc.setTextSize(12);
        desc.setPadding(0, dp(3), 0, dp(10));
        panel.addView(desc);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        radiusSpinner = new Spinner(this);
        String[] radii = {"1 km", "3 km", "5 km"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                radii
        );
        radiusSpinner.setAdapter(adapter);
        controls.addView(radiusSpinner, new LinearLayout.LayoutParams(0, dp(52), 1));

        downloadButton = new Button(this);
        downloadButton.setText("Descargar");
        controls.addView(downloadButton, new LinearLayout.LayoutParams(dp(135), dp(52)));
        panel.addView(controls);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
        );
        progressParams.topMargin = dp(9);
        panel.addView(progressBar, progressParams);

        statusText = new TextView(this);
        statusText.setText("Mapa disponible en línea. Puedes descargar la zona actual.");
        statusText.setTextColor(Color.argb(210, 255, 255, 255));
        statusText.setTextSize(11);
        statusText.setPadding(0, dp(8), 0, 0);
        panel.addView(statusText);

        TextView attribution = new TextView(this);
        attribution.setText("OpenFreeMap © OpenMapTiles · Data © OpenStreetMap contributors");
        attribution.setTextColor(Color.argb(150, 255, 255, 255));
        attribution.setTextSize(9);
        attribution.setPadding(0, dp(8), 0, 0);
        panel.addView(attribution);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.gravity = Gravity.BOTTOM;
        panelParams.leftMargin = dp(10);
        panelParams.rightMargin = dp(10);
        panelParams.bottomMargin = dp(10);
        root.addView(panel, panelParams);

        setContentView(root);

        try {
            mapView.onCreate(savedInstanceState);
            mapView.getMapAsync(mapLibreMap -> {
                map = mapLibreMap;
                try {
                    map.setStyle(STYLE_URL, style -> {
                        LatLng here = new LatLng(latitude, longitude);
                        map.setCameraPosition(new CameraPosition.Builder()
                                .target(here)
                                .zoom(15.5)
                                .build());
                        map.addMarker(new MarkerOptions()
                                .position(here)
                                .title("Ubicación GPS"));
                    });
                } catch (Throwable error) {
                    statusText.setText("No se pudo cargar el mapa. Revisa la conexión y vuelve a intentar.");
                }
            });
        } catch (Throwable error) {
            Toast.makeText(this, "No se pudo abrir el mapa", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        back.setOnClickListener(v -> saveSnapshotAndFinish());
        downloadButton.setOnClickListener(v -> downloadSelectedArea());
    }

    private void downloadSelectedArea() {
        int pos = radiusSpinner.getSelectedItemPosition();
        double radiusKm = pos == 0 ? 1.0 : (pos == 1 ? 3.0 : 5.0);

        double latDelta = radiusKm / 111.32;
        double lonDelta = radiusKm / (111.32 * Math.max(0.2, Math.cos(Math.toRadians(latitude))));

        LatLngBounds bounds = new LatLngBounds.Builder()
                .include(new LatLng(latitude + latDelta, longitude + lonDelta))
                .include(new LatLng(latitude - latDelta, longitude - lonDelta))
                .build();

        OfflineTilePyramidRegionDefinition definition =
                new OfflineTilePyramidRegionDefinition(
                        STYLE_URL,
                        bounds,
                        13.0,
                        16.0,
                        1.0f,
                        false
                );

        String metadataText = "{\"name\":\"Huella " + radiusKm + " km\",\"lat\":" +
                latitude + ",\"lon\":" + longitude + "}";
        byte[] metadata = metadataText.getBytes(StandardCharsets.UTF_8);

        downloadButton.setEnabled(false);
        statusText.setText("Preparando descarga offline…");
        progressBar.setProgress(0);

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

                                int percent = 0;
                                if (required > 0) {
                                    percent = (int) Math.min(100, (completed * 100L) / required);
                                }
                                progressBar.setProgress(percent);

                                double mb = status.getCompletedResourceSize() / (1024.0 * 1024.0);
                                statusText.setText(
                                        "Descargando " + percent + "% · " +
                                        String.format(java.util.Locale.US, "%.1f MB", mb)
                                );

                                if (status.isComplete()) {
                                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                    progressBar.setProgress(100);
                                    statusText.setText("Mapa offline listo para usar sin internet.");
                                    downloadButton.setEnabled(true);
                                    Toast.makeText(
                                            MapActivity.this,
                                            "Zona offline descargada",
                                            Toast.LENGTH_SHORT
                                    ).show();
                                }
                            }

                            @Override
                            public void onError(OfflineRegionError error) {
                                statusText.setText("Error de descarga: " + error.getMessage());
                                downloadButton.setEnabled(true);
                            }

                            @Override
                            public void mapboxTileCountLimitExceeded(long limit) {
                                statusText.setText("La zona supera el límite de descarga.");
                                downloadButton.setEnabled(true);
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                            }
                        });

                        region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                    }

                    @Override
                    public void onError(String error) {
                        statusText.setText("No se pudo iniciar la descarga: " + error);
                        downloadButton.setEnabled(true);
                    }
                }
        );
    }

    private void saveSnapshotAndFinish() {
        if (finishingWithSnapshot) return;
        finishingWithSnapshot = true;

        if (map == null) {
            finish();
            return;
        }

        map.snapshot(bitmap -> {
            try {
                File file = new File(getFilesDir(), "offline_map_preview.png");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out);
                }
            } catch (Exception ignored) {
            }
            finish();
        });
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    public void onBackPressed() {
        saveSnapshotAndFinish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}
