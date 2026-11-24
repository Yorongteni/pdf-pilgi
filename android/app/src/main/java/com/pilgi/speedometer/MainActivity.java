package com.pilgi.speedometer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1;
    private static final int REQ_NATIVE_GPS = 2;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private LocationManager locationManager;
    private boolean gpsWanted = false;      // 웹 화면에서 시작하기를 눌렀는지
    private boolean gpsListening = false;
    private GeolocationPermissions.Callback pendingCallback;
    private String pendingOrigin;

    /** 휴대폰 GPS 칩에서 직접 받은 위치·속도를 웹 화면으로 전달 */
    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            String js = String.format(Locale.US,
                    "window.nativeFix&&window.nativeFix(%f,%f,%s,%f,%d)",
                    loc.getLatitude(), loc.getLongitude(),
                    loc.hasSpeed() ? String.format(Locale.US, "%f", loc.getSpeed()) : "null",
                    loc.hasAccuracy() ? loc.getAccuracy() : 99f,
                    loc.getTime());
            web.evaluateJavascript(js, null);
        }

        @Override
        public void onProviderEnabled(String provider) {
            web.evaluateJavascript("window.nativeError&&window.nativeError(3)", null);
        }

        @Override
        public void onProviderDisabled(String provider) {
            web.evaluateJavascript("window.nativeError&&window.nativeError(4)", null);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    /** 웹 화면에서 호출: AndroidGps.start() */
    private class GpsBridge {
        @JavascriptInterface
        public void start() {
            runOnUiThread(() -> {
                gpsWanted = true;
                if (hasFineLocation()) {
                    startGps();
                } else {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_NATIVE_GPS);
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF07090C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        web.addJavascriptInterface(new GpsBridge(), "AndroidGps");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasAnyLocation()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingOrigin = origin;
                    pendingCallback = callback;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }
        });

        web.loadUrl(START_URL);
    }

    private boolean hasFineLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAnyLocation() {
        return hasFineLocation()
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startGps() {
        if (gpsListening || !hasFineLocation() || locationManager == null) return;
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            web.evaluateJavascript("window.nativeError&&window.nativeError(4)", null);
        }
        try {
            // 0ms, 0m: GPS 칩이 내주는 모든 측정값(보통 1초에 1번, 기종에 따라 더 자주)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f,
                    gpsListener, Looper.getMainLooper());
            gpsListening = true;
        } catch (SecurityException | IllegalArgumentException e) {
            web.evaluateJavascript("window.nativeError&&window.nativeError(2)", null);
        }
    }

    private void stopGps() {
        if (gpsListening && locationManager != null) {
            locationManager.removeUpdates(gpsListener);
        }
        gpsListening = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && pendingCallback != null) {
            pendingCallback.invoke(pendingOrigin, hasAnyLocation(), false);
            pendingCallback = null;
            pendingOrigin = null;
        } else if (requestCode == REQ_NATIVE_GPS) {
            if (hasFineLocation()) {
                startGps();
            } else {
                web.evaluateJavascript("window.nativeError&&window.nativeError(1)", null);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (gpsWanted) startGps();
    }

    @Override
    protected void onPause() {
        stopGps();
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopGps();
        web.destroy();
        super.onDestroy();
    }
}
