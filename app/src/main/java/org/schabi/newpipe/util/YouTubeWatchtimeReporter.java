package org.schabi.newpipe.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.player.mediaitem.MediaItemTag;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reports YouTube watchtime using a hidden WebView so the shared WebView cookie jar can provide
 * the logged-in YouTube session.
 */
public final class YouTubeWatchtimeReporter {
    private static final String TAG = "YouTubeWatchtime";
    private static final boolean DEBUG = MainActivity.DEBUG;
    private static final long WATCHTIME_HEARTBEAT_MS = 30_000L;
    private static final String WATCHTIME_ENDPOINT = "https://www.youtube.com/api/stats/watchtime";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] TOKEN_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    @Nullable
    private static WebView beaconWebView;
    @Nullable
    private static Session currentSession;

    private YouTubeWatchtimeReporter() {
    }

    public static void onStreamChanged(@NonNull final Context context,
                                       @Nullable final MediaItemTag metadata,
                                       final long currentPositionMs) {
        runOnMainThread(() -> currentSession = Session.from(metadata, currentPositionMs));
    }

    public static void onPlaybackStarted(@NonNull final Context context,
                                         @Nullable final MediaItemTag metadata,
                                         final long currentPositionMs) {
        runOnMainThread(() -> {
            final Session session = getOrCreateSession(metadata, currentPositionMs);
            if (session == null) {
                destroyWebView();
                return;
            }

            session.segmentStartMs = Math.max(currentPositionMs, 0L);
            session.playing = true;
            ensureWebView(context.getApplicationContext());
        });
    }

    public static void onPlaybackPaused(@Nullable final MediaItemTag metadata,
                                        final long currentPositionMs) {
        runOnMainThread(() -> {
            final Session session = getOrCreateSession(metadata, currentPositionMs);
            if (session == null) {
                return;
            }

            session.segmentStartMs = Math.max(currentPositionMs, 0L);
            session.playing = false;
        });
    }

    public static void onPlaybackStopped() {
        runOnMainThread(() -> {
            currentSession = null;
            destroyWebView();
        });
    }

    public static void onSeek(@Nullable final MediaItemTag metadata, final long currentPositionMs) {
        runOnMainThread(() -> {
            final Session session = getOrCreateSession(metadata, currentPositionMs);
            if (session == null) {
                return;
            }

            session.segmentStartMs = Math.max(currentPositionMs, 0L);
        });
    }

    public static void onProgress(@NonNull final Context context,
                                  @Nullable final MediaItemTag metadata,
                                  final long currentPositionMs) {
        runOnMainThread(() -> {
            final Session session = getOrCreateSession(metadata, currentPositionMs);
            if (session == null || !session.playing) {
                return;
            }

            final long safePositionMs = Math.max(currentPositionMs, 0L);
            while (safePositionMs - session.segmentStartMs >= WATCHTIME_HEARTBEAT_MS) {
                final long endMs = session.segmentStartMs + WATCHTIME_HEARTBEAT_MS;
                sendWatchtimeBeacon(context.getApplicationContext(), session, endMs);
                session.segmentStartMs = endMs;
            }
        });
    }

    private static void runOnMainThread(@NonNull final Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            MAIN_HANDLER.post(runnable);
        }
    }

    @Nullable
    private static Session getOrCreateSession(@Nullable final MediaItemTag metadata,
                                              final long currentPositionMs) {
        final Session nextSession = Session.from(metadata, currentPositionMs);
        if (nextSession == null) {
            currentSession = null;
            return null;
        }

        if (currentSession == null || !currentSession.sameVideo(nextSession)) {
            currentSession = nextSession;
        }
        return currentSession;
    }

    private static void ensureWebView(@NonNull final Context context) {
        if (!DeviceUtils.supportsWebView()) {
            return;
        }
        if (beaconWebView != null) {
            return;
        }

        beaconWebView = new WebView(context);
        beaconWebView.getSettings().setJavaScriptEnabled(false);
        beaconWebView.getSettings().setLoadsImagesAutomatically(false);
        beaconWebView.getSettings().setBlockNetworkImage(true);
        beaconWebView.setWebViewClient(new WebViewClient());
    }

    private static void destroyWebView() {
        if (beaconWebView == null) {
            return;
        }
        beaconWebView.stopLoading();
        beaconWebView.loadUrl("about:blank");
        beaconWebView.destroy();
        beaconWebView = null;
    }

    private static void sendWatchtimeBeacon(@NonNull final Context context,
                                            @NonNull final Session session,
                                            final long endMs) {
        ensureWebView(context);
        if (beaconWebView == null) {
            return;
        }

        final String url = session.buildWatchtimeUrl(endMs);
        final Map<String, String> headers = new HashMap<>();
        headers.put("Referer", session.referer);

        if (DEBUG) {
            android.util.Log.d(TAG, "watchtime beacon: " + url);
        }

        CookieManager.getInstance().flush();
        beaconWebView.loadUrl(url, headers);
    }

    @Nullable
    private static String extractVideoId(@Nullable final String streamUrl) {
        if (TextUtils.isEmpty(streamUrl)) {
            return null;
        }

        final Uri uri = Uri.parse(streamUrl);
        final String queryVideoId = uri.getQueryParameter("v");
        if (!TextUtils.isEmpty(queryVideoId)) {
            return queryVideoId;
        }

        final String lastSegment = uri.getLastPathSegment();
        return TextUtils.isEmpty(lastSegment) ? null : lastSegment;
    }

    @NonNull
    private static String randomToken(final int length) {
        final StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(TOKEN_CHARS[RANDOM.nextInt(TOKEN_CHARS.length)]);
        }
        return builder.toString();
    }

    private static final class Session {
        @NonNull
        private final String videoId;
        @NonNull
        private final String watchUrl;
        @NonNull
        private final String referer;
        @NonNull
        private final String cpn = randomToken(16);
        @NonNull
        private final String cl = Long.toUnsignedString(Math.abs(RANDOM.nextLong()));
        @NonNull
        private final String ei = randomToken(16);
        @NonNull
        private final String of = randomToken(22);
        @NonNull
        private final String osid = randomToken(16) + ":" + randomToken(24);
        @NonNull
        private final String plid = randomToken(16);
        @NonNull
        private final String vm = randomToken(96);
        private final long durationSeconds;
        private long segmentStartMs;
        private boolean playing;

        private Session(@NonNull final String videoId,
                        @NonNull final String watchUrl,
                        final long durationSeconds,
                        final long startPositionMs) {
            this.videoId = videoId;
            this.watchUrl = watchUrl;
            this.referer = watchUrl;
            this.durationSeconds = Math.max(durationSeconds, 0L);
            this.segmentStartMs = Math.max(startPositionMs, 0L);
        }

        @Nullable
        static Session from(@Nullable final MediaItemTag metadata, final long currentPositionMs) {
            if (metadata == null || metadata.getServiceId() != ServiceList.YouTube.getServiceId()) {
                return null;
            }

            final String videoId = extractVideoId(metadata.getStreamUrl());
            if (TextUtils.isEmpty(videoId)) {
                return null;
            }

            final String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
            return new Session(videoId, watchUrl, metadata.getDurationSeconds(), currentPositionMs);
        }

        boolean sameVideo(@NonNull final Session other) {
            return videoId.equals(other.videoId);
        }

        @NonNull
        String buildWatchtimeUrl(final long endMs) {
            final double startSeconds = segmentStartMs / 1000.0;
            final double endSeconds = endMs / 1000.0;
            final long roundedEndSeconds = Math.round(endSeconds);
            final Uri.Builder builder = Uri.parse(WATCHTIME_ENDPOINT).buildUpon()
                    .appendQueryParameter("ns", "yt")
                    .appendQueryParameter("el", "detailpage")
                    .appendQueryParameter("cpn", cpn)
                    .appendQueryParameter("ver", "2")
                    .appendQueryParameter("cmt", formatSeconds(endSeconds))
                    .appendQueryParameter("fmt", "0")
                    .appendQueryParameter("fs", "0")
                    .appendQueryParameter("rt", formatSeconds(endSeconds + 1.0))
                    .appendQueryParameter("euri", "")
                    .appendQueryParameter("lact", Long.toString(endMs))
                    .appendQueryParameter("cl", cl)
                    .appendQueryParameter("state", "playing")
                    .appendQueryParameter("volume", "100")
                    .appendQueryParameter("subscribed", "1")
                    .appendQueryParameter("cbrand", Build.BRAND)
                    .appendQueryParameter("cbr", "Android WebView")
                    .appendQueryParameter("cbrver", Build.VERSION.RELEASE)
                    .appendQueryParameter("c", "WEB")
                    .appendQueryParameter("cver", "2.20260409.02.00")
                    .appendQueryParameter("cplayer", "UNIPLAYER")
                    .appendQueryParameter("cos", "Android")
                    .appendQueryParameter("cosver", Build.VERSION.RELEASE)
                    .appendQueryParameter("cplatform", "MOBILE")
                    .appendQueryParameter("hl",
                            Locale.getDefault().toLanguageTag().replace('-', '_'))
                    .appendQueryParameter("cr", Locale.getDefault().getCountry())
                    .appendQueryParameter("len", Long.toString(durationSeconds))
                    .appendQueryParameter("rtn", Long.toString(roundedEndSeconds))
                    .appendQueryParameter("afmt", "0")
                    .appendQueryParameter("idpj", "-1")
                    .appendQueryParameter("ldpj", "-1")
                    .appendQueryParameter("rti", Long.toString(roundedEndSeconds))
                    .appendQueryParameter("st", formatSeconds(startSeconds))
                    .appendQueryParameter("et", formatSeconds(endSeconds))
                    .appendQueryParameter("muted", "0")
                    .appendQueryParameter("vis", "1")
                    .appendQueryParameter("docid", videoId)
                    .appendQueryParameter("ei", ei)
                    .appendQueryParameter("plid", plid)
                    .appendQueryParameter("of", of)
                    .appendQueryParameter("osid", osid)
                    .appendQueryParameter("vm", vm);
            return builder.build().toString();
        }

        @NonNull
        private static String formatSeconds(final double seconds) {
            return String.format(Locale.US, "%.3f", Math.max(seconds, 0.0));
        }
    }
}
