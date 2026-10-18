/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Opens a video tapped in the feed on its own, with no reels to swipe to after it (#161).
 *
 * <p>A video or reel tapped in the feed opens one of Facebook's full-screen players: a screen of its
 * own over the main one, or, for a reel in the feed, the Warion viewer inside the main screen. The
 * player starts with that one video, then fills in reels after it, and those are the endless reels a
 * swipe up leads to. The patch tells this class when a feed video tap goes through Facebook's Warion
 * step, where the reels viewer opens from, and when a Warion viewer opens and closes, and asks it
 * before every "fetch more" a reels feed makes and every time a reels data source asks whether it
 * shows its seed video alone.
 *
 * <p>With the switch on, a reels feed or data source that never asked before is taken to be a tapped
 * video's player when it first asks while a Warion viewer is open, while the full-screen player is the
 * screen in front, or within {@link #WINDOW_MS} of a Warion step or of the reels viewer opening from a
 * feed. It gets no more reels (its seed video
 * alone, in the data source's case) then and on every later ask. One held for a Warion viewer is
 * decided again on its first ask after that viewer closes. One that first asked from the main screen
 * with no viewer open, the Reels and Video tabs' among them, keeps Facebook's answer. Off, paused, before the settings are ready, or when anything
 * here fails, everything gets Facebook's own answer.
 */
public final class FeedVideoAlone {
    /** Counted once for each player a feed video opened that this kept to that video. */
    static final String OPENED = "Feed video opened on its own";

    /** Counted each time that player asked for more reels and got none. */
    static final String HELD_BACK = "More reels held back";

    /** How long after a feed video tap its player's first ask for more is still taken as that player's. */
    static final long WINDOW_MS = 5000;

    /** Facebook's full-screen player, the manifest screen a video or reel tapped in the feed opens in. */
    static final Set<String> PLAYER_SCREENS = Set.of(
            "com.facebook.katana.immersiveactivity.ImmersiveActivity",
            "com.facebook.katana.immersiveactivity.ImmersivePortraitActivity");

    /**
     * The surfaces of Facebook's feeds, a player origin's part before "::": home, groups, profiles,
     * pages, a single post, and the Following and Friends feeds. The Reels tab, a profile's reels,
     * search and notifications aren't among them.
     */
    static final Set<String> FEED_SURFACES = Set.of("newsfeed", "group", "user_timeline", "profile",
            "page_timeline", "permalink", "following_np_subtab", "friendly_feed");

    /** The screen created or resumed last, or an empty reference. */
    private static volatile WeakReference<Activity> front = new WeakReference<>(null);

    private static boolean watching;

    private static final long NONE = Long.MIN_VALUE;

    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /**
     * Every feed that asked while the switch was on, and whether it's a tapped video's player. Told
     * apart by identity, never {@code equals}, so two of Facebook's feeds can't share an answer.
     */
    private static final List<Feed> FEEDS = new ArrayList<>();

    /** The Warion viewers open now, oldest first. Guarded by FEEDS. */
    private static final List<Viewer> VIEWERS = new ArrayList<>();

    /** When the last feed video tap that no player has used yet came, or NONE. */
    private static final AtomicLong TAPPED_AT = new AtomicLong(NONE);

    private static final class Feed {
        final WeakReference<Object> feed;
        final boolean player;
        /** The Warion viewer open when it first asked, whose closing ends the hold, or null. */
        @Nullable final Viewer viewer;

        Feed(Object feed, boolean player, @Nullable Viewer viewer) {
            this.feed = new WeakReference<>(feed);
            this.player = player;
            this.viewer = viewer;
        }
    }

    /** A Warion viewer, Facebook's fragment showing a feed reel and what comes after it. */
    private static final class Viewer {
        final WeakReference<Object> fragment;
        volatile boolean closed;
        boolean counted;

        Viewer(Object fragment) {
            this.fragment = new WeakReference<>(fragment);
        }
    }

    /** Stands in for the uptime clock when a test sets it. */
    @Nullable
    static volatile LongSupplier clockForTests;

    private FeedVideoAlone() {
    }

    /** Whether the switch is on and the patch is running. */
    static boolean on() {
        // Settings mustn't load before the extension has its context.
        return Utils.settingsReady() && Settings.FEED_VIDEOS_ALONE.get();
    }

    /**
     * Called once per Facebook start, from the settings entry's onApplicationCreate, with Clean up
     * Reels in the build: from then on each screen that's created or resumed is the one in front.
     */
    public static synchronized void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Screens());
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed video screens", failure);
        }
    }

    /** Records [screen] as the one in front. */
    static void inFront(@Nullable Activity screen) {
        front = new WeakReference<>(screen);
    }

    /** Whether the screen in front is the full-screen player. */
    static boolean playerInFront() {
        Activity screen = front.get();
        return screen != null && !screen.isFinishing() && PLAYER_SCREENS.contains(screen.getClass().getName());
    }

    /** Keeps the screen in front up to date. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
            inFront(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            inFront(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /** Injection point, first thing in Facebook's Warion step, as a feed video opens its player. Never throws. */
    public static void feedVideoTapped() {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                TAPPED_AT.set(NONE);
                return;
            }
            HookStatus.bound(FAMILY, "feed video tap");
            TAPPED_AT.set(now());
            Logger.printDebug(() -> "Feed videos on their own: a feed video is opening its player");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed video tap", failure);
        }
    }

    /**
     * Injection point, in Facebook's reels viewer entry as it reads where the video opens from, before
     * the viewer's data source first asks. A feed reel goes this way on some accounts with no Warion
     * step (S25 2026-10-18), so a video opening from a feed surface counts as a feed video tap. One
     * opening from anywhere else is left to Facebook. Never throws.
     *
     * @param origin Facebook's player origin, "surface::subsurface" as text.
     */
    public static void reelsViewerOpening(@Nullable Object origin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                TAPPED_AT.set(NONE);
                return;
            }
            if (origin == null) return;
            HookStatus.bound(FAMILY, "reels viewer entry");
            String where = origin.toString();
            if (fromFeed(where)) {
                TAPPED_AT.set(now());
                Logger.printDebug(() -> "Feed videos on their own: a feed video is opening the reels viewer from " + where);
            } else {
                Logger.printDebug(() -> "Feed videos on their own: the reels viewer is opening from " + where + ", not a feed");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reels viewer entry", failure);
        }
    }

    /** Whether [origin], a player origin as text, names a feed's surface. */
    static boolean fromFeed(String origin) {
        int cut = origin.indexOf("::");
        return FEED_SURFACES.contains(cut < 0 ? origin : origin.substring(0, cut));
    }

    /**
     * Injection point, as Facebook's video fragment reads whether it's a Warion viewer while it's
     * created. Never throws.
     *
     * @param fragment the fragment being created.
     * @param inWarion whether it's a Warion viewer, as Facebook read it.
     */
    public static void warionOpening(@Nullable Object fragment, boolean inWarion) {
        try {
            HookStatus.invoked(FAMILY);
            if (!inWarion || fragment == null || !on()) return;
            HookStatus.bound(FAMILY, "warion viewer");
            synchronized (FEEDS) {
                VIEWERS.add(new Viewer(fragment));
            }
            Logger.printDebug(() -> "Feed videos on their own: a Warion viewer opened");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "warion viewer", failure);
        }
    }

    /** Injection point, first thing as Facebook's video fragment is destroyed. Never throws. */
    public static void warionClosed(@Nullable Object fragment) {
        try {
            if (fragment == null) return;
            synchronized (FEEDS) {
                for (Iterator<Viewer> each = VIEWERS.iterator(); each.hasNext(); ) {
                    Viewer viewer = each.next();
                    Object held = viewer.fragment.get();
                    if (held == null || held == fragment) {
                        viewer.closed = true;
                        each.remove();
                    }
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "warion viewer", failure);
        }
    }

    /**
     * Injection point, first thing in a reels feed's fetch more. True answers Facebook's caller that
     * nothing was fetched. Never throws.
     *
     * @param feed the reels feed asking, Facebook's own object behind the fetch.
     */
    public static boolean holdsBack(@Nullable Object feed) {
        return alone(feed, "reels fetch more", "reels feed");
    }

    /**
     * Injection point, first thing in a reels data source's answer to whether it shows its seed video
     * alone, which skips both the reels Facebook would add after it and every later fetch more. True
     * answers yes. Never throws.
     *
     * @param source the data source asking.
     */
    public static boolean seedOnly(@Nullable Object source) {
        return alone(source, "seed video only", "reels data source");
    }

    /** Whether [asker] is a tapped feed video's player, deciding on its first ask. */
    private static boolean alone(@Nullable Object asker, String road, String what) {
        try {
            HookStatus.invoked(FAMILY);
            if (asker == null || !on()) return false;
            HookStatus.bound(FAMILY, road);
            boolean alone;
            boolean first;
            boolean opened = false;
            Viewer bound = null;
            synchronized (FEEDS) {
                Feed known = null;
                for (Iterator<Feed> each = FEEDS.iterator(); each.hasNext(); ) {
                    Feed seen = each.next();
                    Object held = seen.feed.get();
                    if (held == null) {
                        each.remove();
                    } else if (held == asker) {
                        // Held for a viewer that has closed: decided again, in case Facebook reuses it.
                        if (seen.viewer != null && seen.viewer.closed) each.remove();
                        else known = seen;
                    }
                }
                first = known == null;
                if (first) {
                    // The tap goes to one player only, even with a tap landing while this reads it.
                    long tapped = TAPPED_AT.get();
                    boolean fromTap = tapped != NONE && now() - tapped <= WINDOW_MS && TAPPED_AT.compareAndSet(tapped, NONE);
                    bound = openViewer();
                    alone = fromTap || playerInFront() || bound != null;
                    FEEDS.add(new Feed(asker, alone, alone ? bound : null));
                    if (bound != null && !bound.counted) {
                        // A viewer's feed and data source both ask; it's one video opened.
                        bound.counted = true;
                        opened = true;
                    } else if (bound == null && alone) {
                        opened = true;
                    }
                } else {
                    alone = known.player;
                }
            }
            if (first) {
                if (opened) HookStatus.counted(FAMILY, OPENED);
                String id = asker.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(asker));
                Activity screen = front.get();
                String where = (screen == null ? "no screen" : screen.getClass().getName()) + (bound != null ? ", Warion viewer open" : "");
                boolean player = alone;
                Logger.printDebug(() -> player
                        ? "Feed videos on their own: " + what + " " + id + " is a tapped feed video's player (" + where + "), nothing comes after the video"
                        : "Feed videos on their own: " + what + " " + id + " asked from " + where + " and keeps Facebook's answer");
            }
            if (alone) HookStatus.counted(FAMILY, HELD_BACK);
            return alone;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, road, failure);
            return false;
        }
    }

    /** The newest Warion viewer still open, or null. Called holding FEEDS. */
    @Nullable
    private static Viewer openViewer() {
        for (int at = VIEWERS.size() - 1; at >= 0; at--) {
            Viewer viewer = VIEWERS.get(at);
            if (!viewer.closed && viewer.fragment.get() != null) return viewer;
        }
        return null;
    }

    private static long now() {
        LongSupplier clock = clockForTests;
        return clock != null ? clock.getAsLong() : SystemClock.uptimeMillis();
    }

    /** Forgets every feed, viewer, any pending tap and the stand-in clock. Tests only. */
    static void forgetForTests() {
        synchronized (FEEDS) {
            FEEDS.clear();
            VIEWERS.clear();
        }
        TAPPED_AT.set(NONE);
        clockForTests = null;
        inFront(null);
    }
}
