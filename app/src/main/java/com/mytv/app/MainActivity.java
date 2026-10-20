package com.mytv.app;

import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private FrameLayout playerContainer;
    private PlayerView playerView;
    private LinearLayout headerLayout;
    private ExoPlayer player;
    private ListView listViewChannels;
    
    private List<String> channelNames = new ArrayList<>();
    private List<String> channelUrls = new ArrayList<>();
    private List<String> channelReferrers = new ArrayList<>();
    private List<String> channelUserAgents = new ArrayList<>();
    private List<String> channelLicenseKeys = new ArrayList<>();

    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        try {
            playerContainer = findViewById(R.id.playerContainer);
            playerView = findViewById(R.id.playerView);
            headerLayout = findViewById(R.id.headerLayout);
            listViewChannels = findViewById(R.id.listViewChannels);

            DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                    .setAllowCrossProtocolRedirects(true);

            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpDataSourceFactory)
                    .setDrmSessionManagerProvider(new DefaultDrmSessionManagerProvider());

            player = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build();

            if (playerView != null) {
                playerView.setPlayer(player);
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                
                // Klik player untuk ubah rasio (FIT -> FILL -> ZOOM)
                playerView.setOnClickListener(v -> toggleResizeMode());
            }

            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Toast.makeText(MainActivity.this, "Siaran belum live / server offline", Toast.LENGTH_SHORT).show();
                }
            });

            // Sesuaikan tata letak dengan orientasi saat aplikasi pertama dibuka
            adjustLayoutForOrientation(getResources().getConfiguration().orientation);

            new Thread(() -> fetchPlaylistOnline("https://bit.ly/KPL203")).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        adjustLayoutForOrientation(newConfig.orientation);
    }

    private void adjustLayoutForOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Mode Landscape: Sembunyikan daftar channel & header, jadikan player Fullscreen
            if (headerLayout != null) headerLayout.setVisibility(View.GONE);
            if (listViewChannels != null) listViewChannels.setVisibility(View.GONE);
            
            if (playerContainer != null) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                playerContainer.setLayoutParams(params);
            }
            
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            // Mode Potret: Tampilkan kembali header & daftar channel di bawah
            if (headerLayout != null) headerLayout.setVisibility(View.VISIBLE);
            if (listViewChannels != null) listViewChannels.setVisibility(View.VISIBLE);
            
            if (playerContainer != null) {
                int heightPx = (int) (230 * getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
                playerContainer.setLayoutParams(params);
            }
            
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void toggleResizeMode() {
        if (playerView == null) return;
        
        switch (currentResizeMode) {
            case AspectRatioFrameLayout.RESIZE_MODE_FIT:
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                Toast.makeText(this, "Mode: FILL", Toast.LENGTH_SHORT).show();
                break;
            case AspectRatioFrameLayout.RESIZE_MODE_FILL:
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                Toast.makeText(this, "Mode: ZOOM", Toast.LENGTH_SHORT).show();
                break;
            case AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
            default:
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                Toast.makeText(this, "Mode: FIT", Toast.LENGTH_SHORT).show();
                break;
        }
        playerView.setResizeMode(currentResizeMode);
    }

    private void fetchPlaylistOnline(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            connection.connect();

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;
            String currentName = "Live Stream";
            String currentReferrer = "";
            String currentUserAgent = "";
            String currentLicenseKey = "";
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("<=")) continue;
                
                if (line.startsWith("#EXTINF:")) {
                    int commaIndex = line.indexOf(',');
                    if (commaIndex != -1) {
                        currentName = line.substring(commaIndex + 1).trim();
                    }
                } else if (line.startsWith("#EXTVLCOPT:http-referrer=")) {
                    currentReferrer = line.substring("#EXTVLCOPT:http-referrer=".length()).trim();
                } else if (line.startsWith("#EXTVLCOPT:http-user-agent=")) {
                    currentUserAgent = line.substring("#EXTVLCOPT:http-user-agent=".length()).trim();
                } else if (line.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                    currentLicenseKey = line.substring("#KODIPROP:inputstream.adaptive.license_key=".length()).trim();
                } else if (line.startsWith("http://") || line.startsWith("https://")) {
                    if (!line.contains("drive.google.com") && !line.contains("sites.google.com")) {
                        channelNames.add(currentName);
                        channelUrls.add(line);
                        channelReferrers.add(currentReferrer);
                        channelUserAgents.add(currentUserAgent);
                        channelLicenseKeys.add(currentLicenseKey);
                    }
                    currentName = "Live Stream";
                    currentReferrer = "";
                    currentUserAgent = "";
                    currentLicenseKey = "";
                }
            }
            reader.close();

            runOnUiThread(() -> {
                ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this, R.layout.item_channel, R.id.textChannelName, channelNames);
                if (listViewChannels != null) {
                    listViewChannels.setAdapter(adapter);
                    listViewChannels.setOnItemClickListener((parent, view, position, id) -> {
                        if (position < channelUrls.size() && !channelUrls.get(position).isEmpty()) {
                            playStream(
                                channelUrls.get(position), 
                                channelNames.get(position), 
                                channelReferrers.get(position), 
                                channelUserAgents.get(position),
                                channelLicenseKeys.get(position)
                            );
                        }
                    });
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void playStream(String url, String name, String referrer, String userAgent, String licenseKey) {
        try {
            if (player == null || url == null || url.isEmpty()) return;
            
            Toast.makeText(this, "Memutar: " + name, Toast.LENGTH_SHORT).show();

            DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory();
            
            if (!userAgent.isEmpty()) {
                dataSourceFactory.setUserAgent(userAgent);
            } else {
                dataSourceFactory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36");
            }
            
            if (!referrer.isEmpty()) {
                Map<String, String> headerMap = new HashMap<>();
                headerMap.put("Referer", referrer);
                dataSourceFactory.setDefaultRequestProperties(headerMap);
            }
            dataSourceFactory.setAllowCrossProtocolRedirects(true);

            MediaItem.Builder mediaItemBuilder = new MediaItem.Builder().setUri(Uri.parse(url));
            
            if (url.contains(".mpd") || url.contains("manifest")) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD);
            } else if (url.contains(".m3u8") || url.contains(".ts")) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
            }

            if (licenseKey != null && !licenseKey.isEmpty() && licenseKey.contains(":")) {
                String[] parts = licenseKey.split(":");
                if (parts.length == 2) {
                    String jwkJson = createClearKeyJwk(parts[0].trim(), parts[1].trim());
                    if (jwkJson != null) {
                        mediaItemBuilder.setDrmConfiguration(
                            new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                                .setLicenseUri("data:application/json;base64," + Base64.encodeToString(jwkJson.getBytes(), Base64.NO_WRAP))
                                .build()
                        );
                    }
                }
            }

            player.stop();
            DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
                    .setDrmSessionManagerProvider(new DefaultDrmSessionManagerProvider());
            
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItemBuilder.build()));
            player.prepare();
            player.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String createClearKeyJwk(String kidHex, String keyHex) {
        try {
            String kidB64 = hexToBase64Url(kidHex);
            String keyB64 = hexToBase64Url(keyHex);

            JSONObject keyObj = new JSONObject();
            keyObj.put("kty", "oct");
            keyObj.put("k", keyB64);
            keyObj.put("kid", kidB64);

            JSONArray keysArray = new JSONArray();
            keysArray.put(keyObj);

            JSONObject jwk = new JSONObject();
            jwk.put("keys", keysArray);
            jwk.put("type", "temporary");

            return jwk.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String hexToBase64Url(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
        }
    }
}
