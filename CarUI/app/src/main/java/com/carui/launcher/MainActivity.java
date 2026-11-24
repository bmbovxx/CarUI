package com.carui.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.Manifest;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.AudioManager;
import android.net.Uri;
import android.view.KeyEvent;
import android.os.SystemClock;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private Location last;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        web.setBackgroundColor(0xFF0B0F17);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        } else {
            startLoc();
        }
        hideBars();
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        startLoc();
    }

    private boolean locOn = false;

    @Override
    protected void onResume() {
        super.onResume();
        if (!locOn) startLoc();
    }

    private void startLoc() {
        if (locOn || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            LocationListener ln = new LocationListener() {
                @Override
                public void onLocationChanged(Location l) { last = l; }
            };
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.getAllProviders().contains(p)) continue;
                lm.requestLocationUpdates(p, 1000, 1, ln);
                locOn = true;
                Location k = lm.getLastKnownLocation(p);
                if (k != null && last == null) last = k;
            }
        } catch (Exception e) { }
    }

    class Bridge {
        @JavascriptInterface
        public String apps() {
            JSONArray out = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN);
                q.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
                Collections.sort(list, new ResolveInfo.DisplayNameComparator(pm));
                for (ResolveInfo r : list) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    JSONObject o = new JSONObject();
                    o.put("n", r.loadLabel(pm).toString());
                    o.put("p", pkg);
                    String ic = "";
                    try { ic = icon(r.loadIcon(pm)); } catch (Exception x) { }
                    o.put("i", ic);
                    out.put(o);
                }
            } catch (Exception e) { }
            return out.toString();
        }

        @JavascriptInterface
        public String loc() {
            return last == null ? "" : last.getLatitude() + "," + last.getLongitude();
        }

        @JavascriptInterface
        public void navigate(String lat, String lng) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + lat + "," + lng));
                i.setPackage("com.google.android.apps.maps");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                try {
                    Intent j = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng));
                    j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(j);
                } catch (Exception x) { }
            }
        }

        @JavascriptInterface
        public String media() {
            try {
                JSONObject o = new JSONObject();
                String en = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
                if (en == null || !en.contains(getPackageName())) {
                    o.put("s", "noaccess");
                    return o.toString();
                }
                MediaController c = ctl();
                if (c == null || c.getMetadata() == null) {
                    o.put("s", "none");
                    return o.toString();
                }
                MediaMetadata md = c.getMetadata();
                PlaybackState ps = c.getPlaybackState();
                String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                long d = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                String key = t + "|" + a + "|" + d + "|" + (b != null);
                if (!key.equals(artKey)) {
                    artKey = key;
                    artB64 = b == null ? "" : encode(b);
                }
                long pos = 0;
                boolean pl = false;
                if (ps != null) {
                    pos = ps.getPosition();
                    pl = ps.getState() == PlaybackState.STATE_PLAYING;
                    if (pl) pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
                o.put("s", "ok");
                o.put("t", t == null ? "" : t);
                o.put("a", a == null ? "" : a);
                o.put("d", d);
                o.put("p", pos);
                o.put("pl", pl);
                o.put("k", key);
                return o.toString();
            } catch (Exception e) {
                return "{\"s\":\"none\"}";
            }
        }

        @JavascriptInterface
        public String mediaArt() { return artB64; }

        @JavascriptInterface
        public void mediaCmd(String cmd) {
            MediaController c = ctl();
            if (c == null) return;
            MediaController.TransportControls t = c.getTransportControls();
            if (cmd.equals("next")) t.skipToNext();
            else if (cmd.equals("prev")) t.skipToPrevious();
            else {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
            }
        }

        @JavascriptInterface
        public void mediaSeek(String ms) {
            MediaController c = ctl();
            if (c != null) c.getTransportControls().seekTo(Long.parseLong(ms));
        }

        @JavascriptInterface
        public void notifSettings() {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        @JavascriptInterface
        public String gps() {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ? "ok" : "denied";
        }

        @JavascriptInterface
        public int getVol() {
            AudioManager a = am();
            int m = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return m > 0 ? Math.round(a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / m) : 0;
        }

        @JavascriptInterface
        public void setVol(int pct) {
            AudioManager a = am();
            int m = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            a.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(pct * m / 100f), 0);
        }

        @JavascriptInterface
        public void launch(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }

        @JavascriptInterface
        public void settings() {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
    }

    private AudioManager am() {
        return (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            am().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            am().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private String artKey = "", artB64 = "";

    private MediaController ctl() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            MediaController first = null;
            for (MediaController c : l) {
                if (first == null) first = c;
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return c;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    private String encode(Bitmap b) {
        Bitmap sm = Bitmap.createScaledBitmap(b, 300, 300, true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        sm.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private String icon(Drawable d) {
        Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, 96, 96);
        d.draw(c);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideBars();
    }

    @Override
    public void onBackPressed() { /* launcher: do nothing */ }
}
