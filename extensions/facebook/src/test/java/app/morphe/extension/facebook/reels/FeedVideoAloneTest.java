/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import com.facebook.katana.activity.FbMainTabActivity;
import com.facebook.katana.immersiveactivity.ImmersiveActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Open feed videos on their own (#161): the first new reels feed to ask for more soon after a feed
 * video tap or the reels viewer opening from a feed surface, or from the full-screen player, is that
 * video's player and gets no, every time; so is a
 * feed or data source first asking while a Warion viewer is open, until that viewer closes; a feed
 * that asked before, one asking long after the tap, or one asking from the main screen keeps
 * Facebook's answer; off or paused, nothing is held back.
 */
@RunWith(RobolectricTestRunner.class)
public class FeedVideoAloneTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final AtomicLong clock = new AtomicLong(1_000_000);

    @Before
    public void start() {
        HookStatus.clear();
        FeedVideoAlone.forgetForTests();
        FeedVideoAlone.clockForTests = clock::get;
        Settings.FEED_VIDEOS_ALONE.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FEED_VIDEOS_ALONE.resetToDefault();
        FeedVideoAlone.forgetForTests();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndBelongsToCleanUpReels() {
        Settings.FEED_VIDEOS_ALONE.resetToDefault();
        assertFalse(Settings.FEED_VIDEOS_ALONE.get());
        assertFalse("asked as each player asks, so no restart", Settings.FEED_VIDEOS_ALONE.rebootApp);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.FEED_VIDEOS_ALONE));
    }

    @Test
    public void theTappedVideosPlayerGetsNoMoreReelsEveryTime() {
        Object reelsTab = new Object();
        assertFalse("a feed asking with no tap keeps Facebook's answer", FeedVideoAlone.holdsBack(reelsTab));

        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(800);
        assertFalse("the Reels tab asked before the tap", FeedVideoAlone.holdsBack(reelsTab));
        Object player = new Object();
        assertTrue(FeedVideoAlone.holdsBack(player));
        clock.addAndGet(60_000);
        assertTrue("later asks of the same player", FeedVideoAlone.holdsBack(player));
        assertFalse(FeedVideoAlone.holdsBack(reelsTab));

        String line = statusLine();
        assertTrue(line, line.contains(FeedVideoAlone.OPENED + " 1"));
        assertTrue(line, line.contains(FeedVideoAlone.HELD_BACK + " 2"));
    }

    @Test
    public void oneTapMakesOnePlayer() {
        FeedVideoAlone.feedVideoTapped();
        assertTrue(FeedVideoAlone.holdsBack(new Object()));
        assertFalse("the tap was used by the first player", FeedVideoAlone.holdsBack(new Object()));

        FeedVideoAlone.feedVideoTapped();
        assertTrue("a second tap opens a second player alone", FeedVideoAlone.holdsBack(new Object()));
    }

    /** Equal to every other one, the way a Facebook class with its own equals might be. */
    private static final class EqualFeed {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualFeed;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    public void feedsAreToldApartByIdentityNotEquals() {
        Object reelsTab = new EqualFeed();
        assertFalse(FeedVideoAlone.holdsBack(reelsTab));
        FeedVideoAlone.feedVideoTapped();
        Object player = new EqualFeed();
        assertTrue("an equal but different feed is a new feed", FeedVideoAlone.holdsBack(player));
        assertFalse("the Reels tab keeps its own answer", FeedVideoAlone.holdsBack(reelsTab));
        assertTrue(FeedVideoAlone.holdsBack(player));
    }

    @Test
    public void aFeedFirstAskingFromTheFullScreenPlayerIsThatPlayers() {
        Activity main = Robolectric.buildActivity(FbMainTabActivity.class).get();
        Activity player = Robolectric.buildActivity(ImmersiveActivity.class).get();
        FeedVideoAlone.Screens screens = new FeedVideoAlone.Screens();

        screens.onActivityResumed(main);
        Object reelsTab = new Object();
        assertFalse("the Reels tab asks from the main screen", FeedVideoAlone.holdsBack(reelsTab));

        screens.onActivityCreated(player, null);
        Object playersFeed = new Object();
        assertTrue("no Warion step, the player in front is enough", FeedVideoAlone.holdsBack(playersFeed));
        assertFalse("a feed that asked before keeps its answer", FeedVideoAlone.holdsBack(reelsTab));

        screens.onActivityResumed(main);
        assertTrue("back on the main screen the player's feed is still held", FeedVideoAlone.holdsBack(playersFeed));
        assertFalse("a new feed asking from the main screen", FeedVideoAlone.holdsBack(new Object()));

        screens.onActivityResumed(player);
        player.finish();
        assertFalse("a player on its way out", FeedVideoAlone.holdsBack(new Object()));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 1"));
    }

    @Test
    public void offTheFullScreenPlayerHoldsNothingBack() {
        new FeedVideoAlone.Screens().onActivityCreated(Robolectric.buildActivity(ImmersiveActivity.class).get(), null);
        Settings.FEED_VIDEOS_ALONE.save(false);
        assertFalse(FeedVideoAlone.holdsBack(new Object()));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("paused", FeedVideoAlone.holdsBack(new Object()));
    }

    @Test
    public void aWarionViewersFeedAndDataSourceKeepItsVideoAlone() {
        Object reelsTab = new Object();
        assertFalse("asked before any viewer opened", FeedVideoAlone.holdsBack(reelsTab));
        Object other = new Object();
        FeedVideoAlone.warionOpening(other, false);
        assertFalse("a video fragment that isn't a Warion viewer arms nothing", FeedVideoAlone.seedOnly(new Object()));

        Object viewer = new Object();
        FeedVideoAlone.warionOpening(viewer, true);
        Object source = new Object();
        Object loader = new Object();
        assertTrue("the viewer's data source shows the video alone", FeedVideoAlone.seedOnly(source));
        clock.addAndGet(60_000);
        assertTrue("its loader asks after the swipe, long after any tap", FeedVideoAlone.holdsBack(loader));
        assertTrue(FeedVideoAlone.seedOnly(source));
        assertFalse("the Reels tab keeps its answer", FeedVideoAlone.holdsBack(reelsTab));

        String line = statusLine();
        assertTrue("one video opened, though two of its objects asked: " + line, line.contains(FeedVideoAlone.OPENED + " 1"));
        assertTrue(line, line.contains(FeedVideoAlone.HELD_BACK + " 3"));
    }

    @Test
    public void closingTheViewerLetsItsObjectsBeDecidedAgain() {
        Object viewer = new Object();
        FeedVideoAlone.warionOpening(viewer, true);
        Object source = new Object();
        assertTrue(FeedVideoAlone.seedOnly(source));

        FeedVideoAlone.warionClosed(new Object());
        assertTrue("another fragment closing changes nothing", FeedVideoAlone.seedOnly(source));
        FeedVideoAlone.warionClosed(viewer);
        assertFalse("reused for the Reels tab after the viewer closed", FeedVideoAlone.seedOnly(source));
        assertFalse("a new one asking with no viewer open", FeedVideoAlone.holdsBack(new Object()));
        assertFalse("that answer sticks", FeedVideoAlone.seedOnly(source));

        Object next = new Object();
        FeedVideoAlone.warionOpening(next, true);
        assertTrue("a second viewer gets its video alone too", FeedVideoAlone.seedOnly(new Object()));
        assertFalse("but an object that asked from the main screen keeps its answer", FeedVideoAlone.seedOnly(source));
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 2"));
    }

    @Test
    public void offOrPausedAWarionViewerHoldsNothingBack() {
        Settings.FEED_VIDEOS_ALONE.save(false);
        FeedVideoAlone.warionOpening(new Object(), true);
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("a viewer opened while off arms nothing", FeedVideoAlone.seedOnly(new Object()));

        Object viewer = new Object();
        FeedVideoAlone.warionOpening(viewer, true);
        Object source = new Object();
        assertTrue(FeedVideoAlone.seedOnly(source));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", FeedVideoAlone.seedOnly(source));
        PauseForTests.resume();
        Settings.FEED_VIDEOS_ALONE.save(false);
        assertFalse("off", FeedVideoAlone.seedOnly(source));
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertTrue("on again, still that viewer's", FeedVideoAlone.seedOnly(source));
        FeedVideoAlone.warionOpening(null, true);
        FeedVideoAlone.warionClosed(null);
        assertFalse("nobody asking is left alone", FeedVideoAlone.seedOnly(null));
    }

    /** Stands in for Facebook's PlayerOrigin, which reads as "surface::subsurface". */
    private static Object origin(String text) {
        return new Object() {
            @Override
            public String toString() {
                return text;
            }
        };
    }

    @Test
    public void aReelsViewerOpeningFromAFeedKeepsItsVideoAlone() {
        Object reelsTab = new Object();
        FeedVideoAlone.reelsViewerOpening(origin("fb_shorts_tab::fb_shorts_viewer"));
        assertFalse("the Reels tab opening its viewer", FeedVideoAlone.seedOnly(reelsTab));

        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::fb_shorts_native_in_feed_unit"));
        Object source = new Object();
        assertTrue("a feed reel's data source asking inside the entry", FeedVideoAlone.seedOnly(source));
        clock.addAndGet(60_000);
        assertTrue("and every time after", FeedVideoAlone.seedOnly(source));
        assertFalse("one opening is one player", FeedVideoAlone.seedOnly(new Object()));
        assertFalse("the Reels tab keeps its answer", FeedVideoAlone.seedOnly(reelsTab));

        for (String feed : new String[]{"group::feed_story", "user_timeline", "permalink::shared_video_feed_story",
                "friendly_feed::fb_shorts_reshare_feed_unit"}) {
            FeedVideoAlone.reelsViewerOpening(origin(feed));
            assertTrue(feed, FeedVideoAlone.seedOnly(new Object()));
        }
        for (String elsewhere : new String[]{"profile_reels_tab::fb_shorts_profile_viewer", "search::fb_shorts_viewer",
                "notifications", "video_home::feed_story", "newsfeeds::feed_story", ""}) {
            FeedVideoAlone.reelsViewerOpening(origin(elsewhere));
            assertFalse(elsewhere, FeedVideoAlone.seedOnly(new Object()));
        }
        assertTrue(statusLine(), statusLine().contains(FeedVideoAlone.OPENED + " 5"));
    }

    @Test
    public void offOrPausedTheViewersOriginArmsNothing() {
        Settings.FEED_VIDEOS_ALONE.save(false);
        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::feed_story"));
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("opened while off", FeedVideoAlone.seedOnly(new Object()));

        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::feed_story"));
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused", FeedVideoAlone.seedOnly(new Object()));
        FeedVideoAlone.reelsViewerOpening(origin("newsfeed::feed_story"));
        PauseForTests.resume();
        assertFalse("an opening while paused arms nothing", FeedVideoAlone.seedOnly(new Object()));

        FeedVideoAlone.reelsViewerOpening(null);
        assertFalse("no origin", FeedVideoAlone.seedOnly(new Object()));
    }

    @Test
    public void aFeedFirstAskingLongAfterTheTapKeepsFacebooksAnswer() {
        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        assertFalse(FeedVideoAlone.holdsBack(new Object()));
    }

    @Test
    public void offOrPausedNothingIsHeldBack() {
        Object player = new Object();
        FeedVideoAlone.feedVideoTapped();
        assertTrue(FeedVideoAlone.holdsBack(player));

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(why);
            assertFalse("paused by " + why, FeedVideoAlone.holdsBack(player));
            FeedVideoAlone.feedVideoTapped();
            PauseForTests.resume();
            assertFalse("a tap while paused arms nothing", FeedVideoAlone.holdsBack(new Object()));
        }

        Settings.FEED_VIDEOS_ALONE.save(false);
        assertFalse(FeedVideoAlone.holdsBack(player));
        FeedVideoAlone.feedVideoTapped();
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("a tap while off arms nothing", FeedVideoAlone.holdsBack(new Object()));
        assertTrue("on again, the player is still held", FeedVideoAlone.holdsBack(player));
        assertFalse("nobody asking is left alone", FeedVideoAlone.holdsBack(null));
    }
}
