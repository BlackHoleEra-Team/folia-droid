package top.izuna.foliamajor.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// src/main/java/.../MediaControlService.java
// 前台服务 + MediaSession：声音仍在 WebView 里放，这里只做三件事——
// 1) 把播放状态和歌曲信息画成通知栏/锁屏的媒体卡（MediaStyle）；
// 2) 接住通知、锁屏与耳机的播放/暂停/上下首，转回页面里的一套处理器；
// 3) 不碰音频焦点：WebView 的音频焦点由 Chromium 自己管（AudioFocusDelegate），我们再申请一份，
//    两份焦点会互相顶掉——后申请的那份把先申请的判成 LOSS，服务就把刚播的歌暂停了。
// 状态的唯一来源是页面（foliaNative.mediaUpdate），这里不自行推断。

public class MediaControlService extends Service {
    public static final String CHANNEL_ID = "folia_playback";
    private static final int NOTIFICATION_ID = 20260701;
    static final String ACTION_PLAY = "team.bhe.foliadroid.MEDIA_PLAY";
    static final String ACTION_PAUSE = "team.bhe.foliadroid.MEDIA_PAUSE";
    static final String ACTION_NEXT = "team.bhe.foliadroid.MEDIA_NEXT";
    static final String ACTION_PREV = "team.bhe.foliadroid.MEDIA_PREV";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final WeakReference<MediaControlService> EMPTY = new WeakReference<>(null);

    private static volatile WeakReference<MediaControlService> instance = EMPTY;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionCompat session;
    private boolean playing;
    private long durationMs;
    private long positionMs;
    private String coverUrl = "";
    private Bitmap coverBitmap;
    private String coverInFlight = "";
    private String title = "";
    private String artist = "";
    private String album = "";
    // 通知只在「该变」的时候重建：进度每秒都在走，但歌名、封面、播放状态没变就不该重画，
    // 否则部分 ROM 上大图标会跟着每秒闪一下。
    private String lastNotificationSignature = "";

    @Override
    public void onCreate() {
        super.onCreate();
        instance = new WeakReference<>(this);
        running = true;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.media_channel_name), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        session = new MediaSessionCompat(this, "folia");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { sendCommand("play"); }

            @Override
            public void onPause() { sendCommand("pause"); }

            @Override
            public void onSkipToNext() { sendCommand("next"); }

            @Override
            public void onSkipToPrevious() { sendCommand("prev"); }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) sendCommand("play");
            else if (ACTION_PAUSE.equals(action)) sendCommand("pause");
            else if (ACTION_NEXT.equals(action)) sendCommand("next");
            else if (ACTION_PREV.equals(action)) sendCommand("prev");
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        instance = EMPTY;
        running = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void sendCommand(String command) {
        MainActivity.evaluateJs(
                "window.dispatchEvent(new CustomEvent('folia-native-media',{detail:'" + command + "'}));");
    }

    /** 页面推来的最新状态。先确保服务在前台，再把画面更新交给实例。 */
    private static volatile boolean running = false;

    static void updateState(Context context, JSONObject json) {
        try {
            if (!running) {
                context.startForegroundService(new Intent(context, MediaControlService.class));
            }
        } catch (IllegalStateException error) {
            // 应用正在后台被系统限制时起不来服务：本次状态只更新会话，不出通知。
        }
        MediaControlService service = instance.get();
        if (service != null) service.applyState(json);
    }

    static void stopPresenting(Context context) {
        MediaControlService service = instance.get();
        if (service == null) return;
        service.main.post(() -> {
            service.session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f)
                    .build());
            service.stopForeground(true);
            service.playing = false;
            context.stopService(new Intent(context, MediaControlService.class));
        });
    }

    private void applyState(JSONObject json) {
        main.post(() -> {
            playing = json.optBoolean("playing", playing);
            durationMs = Math.round(json.optDouble("durationSec", durationMs / 1000.0) * 1000);
            positionMs = Math.round(json.optDouble("positionSec", positionMs / 1000.0) * 1000);
            title = json.optString("title", getString(R.string.app_name));
            artist = json.optString("artist", "");
            album = json.optString("album", "");
            coverUrl = json.optString("coverUrl", coverUrl);

            MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
            if (coverBitmap != null) {
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, coverBitmap);
            }
            session.setMetadata(meta.build());

            session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY
                            | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE
                            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                    .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                            positionMs, playing ? 1f : 0f)
                    .build());

            String signature = playing + "|" + title + "|" + artist + "|" + album + "|" + coverUrl
                    + "|" + (coverBitmap == null ? 0 : System.identityHashCode(coverBitmap));
            if (!signature.equals(lastNotificationSignature)) {
                lastNotificationSignature = signature;
                postNotification();
            }

            if (!coverInFlight.equals(coverUrl)) {
                loadCover(coverUrl);
            }
        });
    }

    private void postNotification() {
        Notification notification = buildNotification(title, artist, album, playing);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification(String title, String artist, String album, boolean nowPlaying) {
        Intent openApp = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = openApp != null
                ? PendingIntent.getActivity(this, 0, openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : null;
        String line = album.isEmpty() ? artist : artist + " · " + album;
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(line)
                .setLargeIcon(coverBitmap)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.folia_prev, "", pending(ACTION_PREV, 1))
                .addAction(nowPlaying ? R.drawable.folia_pause : R.drawable.folia_play,
                        "", pending(nowPlaying ? ACTION_PAUSE : ACTION_PLAY, 2))
                .addAction(R.drawable.folia_next, "", pending(ACTION_NEXT, 3))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private PendingIntent pending(String action, int code) {
        return PendingIntent.getService(this, code,
                new Intent(this, MediaControlService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int got;
        while ((got = in.read(chunk)) > 0) buffer.write(chunk, 0, got);
        return buffer.toByteArray();
    }

    private void loadCover(String url) {
        if (url == null || url.isEmpty() || url.equals(coverInFlight)) return;
        coverInFlight = url == null ? "" : url;
        IO.execute(() -> {
            Bitmap bitmap = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(6000);
                InputStream in = connection.getInputStream();
                byte[] raw = readAll(in);
                in.close();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                int longest = Math.max(bounds.outWidth, bounds.outHeight);
                options.inSampleSize = longest > 1024 ? Integer.highestOneBit(longest / 1024) : 1;
                bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
            } catch (Exception ignored) {
                // 封面拿不到就沿用上一张；通知不因此重画。
            }
            Bitmap fetched = bitmap;
            main.post(() -> {
                if (fetched != null) {
                    coverBitmap = fetched;
                    // 新封面到了：签名带着位图身份，重画一次就好，不会每秒重画。
                    if (session != null) postNotification();
                }
            });
        });
    }

}
