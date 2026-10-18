/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.feed.TypedFeedUnit;
import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.SettingReadsForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The Reels clean-up hooks: the chip filter keeps every chip it doesn't know and the order of the
 * rest, the Follow and footer hooks answer their switches, and every one of them takes Facebook's
 * path when paused, before the settings are ready, switched off, or when it fails.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelDeclutterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String REMIX = "XFBFBShortsRemixAttribution";
    private static final String STARS = "XFBFBShortsSendStarsAttribution";
    private static final String SONG = "XFBFBShortsSoundtrackTitle";
    private static final String LOCATION = "XFBFBShortsLocationAttribution";

    /** The patch is in Morphe Manager's default selection with its switches off; these tests turn them on. */
    @Before
    public void turnTheSwitchOn() {
        Settings.HIDE_REEL_CHIPS.save(true);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_REEL_CHIPS.resetToDefault();
        Settings.HIDE_REEL_FOLLOW_BUTTON.resetToDefault();
        Settings.HIDE_REEL_SOCIAL_FOOTER.resetToDefault();
        Settings.HIDE_REEL_COUNTS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
        ReelDeclutter.forgetLoggedForTests();
    }

    private static TypedFeedUnit chip(String type) {
        return new TypedFeedUnit(type);
    }

    private static String line(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ": ")) return line;
        }
        return null;
    }

    /** The patch is in Morphe Manager's default selection, so all three switches start off. */
    @Test
    public void everySwitchStartsOff() {
        assertFalse(Settings.HIDE_REEL_CHIPS.defaultValue);
        assertFalse(Settings.HIDE_REEL_FOLLOW_BUTTON.defaultValue);
        assertFalse(Settings.HIDE_REEL_SOCIAL_FOOTER.defaultValue);
    }

    /**
     * Every type the switch names goes, and nothing else: the song line, a location, a type
     * Facebook adds later and a chip whose type can't be read all stay, in the order they came.
     */
    @Test
    public void onlyTheNamedChipsGoAndTheRestKeepTheirOrder() {
        List<Object> chips = new ArrayList<>();
        for (String hidden : ReelDeclutter.HIDDEN_CHIPS) chips.add(chip(hidden));
        TypedFeedUnit song = chip(SONG);
        TypedFeedUnit location = chip(LOCATION);
        TypedFeedUnit future = chip("XFBFBShortsSomethingNewAttribution");
        TypedFeedUnit.Unreadable unreadable = new TypedFeedUnit.Unreadable();
        chips.add(1, song);
        chips.add(4, location);
        chips.add(future);
        chips.add(0, unreadable);

        Object[] kept = ReelDeclutter.filterChips(Collections.unmodifiableList(chips));
        assertArrayEquals(new Object[]{unreadable, song, location, future}, kept);
        assertEquals(8, ReelDeclutter.HIDDEN_CHIPS.length);
    }

    /** Each hidden type on its own, so a name dropped from the list fails here by name. */
    @Test
    public void eachNamedChipGoesOnItsOwn() {
        for (String hidden : ReelDeclutter.HIDDEN_CHIPS) {
            TypedFeedUnit song = chip(SONG);
            assertArrayEquals(hidden, new Object[]{song},
                    ReelDeclutter.filterChips(Arrays.asList(chip(hidden), song)));
        }
    }

    /** A list with nothing to drop is Facebook's own: null tells the patch to keep it. */
    @Test
    public void aListWithNothingToDropIsLeftAsItCame() {
        assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(SONG), chip(LOCATION))));
        assertNull(ReelDeclutter.filterChips(Collections.emptyList()));
        assertNull("not a list", ReelDeclutter.filterChips(new Object()));
        assertNull(ReelDeclutter.filterChips(null));
    }

    /** Facebook's list can't hold null, and neither can the one rebuilt from the answer. */
    @Test
    public void aListHoldingNullIsLeftAsItCame() {
        assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), null)));
    }

    @Test
    public void switchedOffEveryChipStays() {
        Settings.HIDE_REEL_CHIPS.save(false);
        assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(STARS))));
        assertEquals(ReelDeclutter.CHIPS_ROUTE + ": 1 lists, 2 items, 0 removed", line(ReelDeclutter.CHIPS_ROUTE));
    }

    /** A list that throws while it's read is kept, and the report names the hook that threw. */
    @Test
    public void aListThatThrowsIsKeptAndReported() {
        List<Object> broken = new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("released");
            }

            @Override
            public int size() {
                return 2;
            }
        };
        assertNull(ReelDeclutter.filterChips(broken));
        assertTrue(HookStatus.missing(FamilyNames.REEL_DECLUTTER).toString(), HookStatus.missing(FamilyNames.REEL_DECLUTTER)
                .contains("a working 'reel chips' hook (it threw java.lang.IllegalStateException)"));
    }

    /** The report counts every list and chip type seen, and each chip taken out under its type. */
    @Test
    public void theReportCountsEachChipTypeSeenAndRemoved() {
        ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG)));
        ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(STARS), new TypedFeedUnit.Unreadable()));
        assertEquals(ReelDeclutter.CHIPS_ROUTE + ": 2 lists, 5 items, 3 removed. Last reason: " + STARS
                + ". Removed: " + REMIX + " 2, " + STARS + " 1. Kinds: " + REMIX + " 2, " + STARS + " 1, " + SONG
                + " 1, " + ReelDeclutter.UNREADABLE + " 1", line(ReelDeclutter.CHIPS_ROUTE));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains(FamilyNames.REEL_DECLUTTER + ": invoked 2, 0 found, 0 missing"));
    }

    /**
     * Facebook's Follow check answers no while the switch is on, so the author row is built with no
     * Follow button, on the Reels tab as anywhere else. The report counts the check under its kind.
     */
    @Test
    public void theFollowCheckAnswersNoWhileTheSwitchIsOn() {
        assertTrue(ReelDeclutter.hideFollowButton());
        assertEquals(ReelDeclutter.FOLLOW_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: " + ReelDeclutter.FOLLOW
                + ". Removed: " + ReelDeclutter.FOLLOW + " 1. Kinds: " + ReelDeclutter.FOLLOW + " 1",
                line(ReelDeclutter.FOLLOW_ROUTE));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains(FamilyNames.REEL_DECLUTTER + ": invoked 1, 0 found, 0 missing"));
    }

    /** The mutation control: off, Facebook's own check runs, and only the asking is counted. */
    @Test
    public void switchedOffFacebooksFollowCheckRuns() {
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        assertFalse(ReelDeclutter.hideFollowButton());
        assertEquals(ReelDeclutter.FOLLOW_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: " + ReelDeclutter.FOLLOW + " 1",
                line(ReelDeclutter.FOLLOW_ROUTE));
    }

    /**
     * One switch runs both of the row's hooks: the Follow check, and the getter that removes the
     * Following button an author you already follow gets. Each is counted under its own kind, so a
     * report says which of them Facebook asked.
     */
    @Test
    public void bothFollowButtonsGoWhileTheSwitchIsOn() {
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideFollowingButton());
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        assertFalse(ReelDeclutter.hideFollowButton());
        assertFalse(ReelDeclutter.hideFollowingButton());
        assertEquals(ReelDeclutter.FOLLOW_ROUTE + ": 4 lists, 4 items, 2 removed. Last reason: " + ReelDeclutter.FOLLOWING
                + ". Removed: " + ReelDeclutter.FOLLOW + " 1, " + ReelDeclutter.FOLLOWING + " 1. Kinds: "
                + ReelDeclutter.FOLLOW + " 2, " + ReelDeclutter.FOLLOWING + " 2", line(ReelDeclutter.FOLLOW_ROUTE));
    }

    /**
     * A Follow switch that can't be read leaves both of Facebook's answers alone, and the report
     * names each hook that threw. The read fails after the call is counted, which stands in for
     * anything that can throw there.
     */
    @Test
    public void aFollowSwitchThatCantBeReadLeavesFacebooksAnswers() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_FOLLOW_BUTTON);
        try {
            assertFalse(ReelDeclutter.hideFollowButton());
            assertFalse(ReelDeclutter.hideFollowingButton());
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_FOLLOW_BUTTON);
        }
        String threw = " hook (it threw " + NullPointerException.class.getName() + ")";
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.FOLLOW + "'" + threw,
                        "a working '" + ReelDeclutter.FOLLOWING + "'" + threw),
                HookStatus.missing(FamilyNames.REEL_DECLUTTER));
        assertTrue("the Follow hook didn't come back once its switch could be read", ReelDeclutter.hideFollowButton());
    }

    /** The search pill switch starts off, builds the pill while off, and answers the empty component while on. */
    @Test
    public void theSearchPillIsBuiltUntilItsSwitchIsOn() {
        assertFalse(Settings.HIDE_REEL_SEARCH_PILL.defaultValue);
        assertFalse(ReelDeclutter.hideSearchPill());
        Settings.HIDE_REEL_SEARCH_PILL.save(true);
        try {
            assertTrue(ReelDeclutter.hideSearchPill());
        } finally {
            Settings.HIDE_REEL_SEARCH_PILL.resetToDefault();
        }
        assertEquals(ReelDeclutter.SEARCH_ROUTE + ": 2 lists, 2 items, 1 removed. Last reason: " + ReelDeclutter.SEARCH_PILL
                + ". Removed: " + ReelDeclutter.SEARCH_PILL + " 1. Kinds: " + ReelDeclutter.SEARCH_PILL + " 2",
                line(ReelDeclutter.SEARCH_ROUTE));
    }

    /**
     * The sidebar counts (#180): the switch starts off, so every button keeps its text. On, a text
     * holding a number becomes one zero-width space, which keeps the text row and so the icons'
     * places, while a word, a missing text and an empty one stay as they came. Off again, the
     * numbers are back.
     */
    @Test
    public void theReelCountsStayUntilTheirSwitchIsOn() {
        assertFalse(Settings.HIDE_REEL_COUNTS.defaultValue);
        assertEquals("1.2K", ReelDeclutter.countText("1.2K"));

        Settings.HIDE_REEL_COUNTS.save(true);
        String hidden = ReelDeclutter.countText("1.2K");
        assertEquals(ReelDeclutter.NO_COUNT, hidden);
        assertEquals("the hidden count isn't one character", 1, hidden.length());
        assertEquals("the hidden count isn't a zero-width space", 0x200B, hidden.charAt(0));
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText("0"));
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText("12 M"));
        // A count in Eastern Arabic digits, as Arabic shows them.
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText(String.valueOf((char) 0x0661) + (char) 0x0662));
        assertEquals("a word lost its text", "Share", ReelDeclutter.countText("Share"));
        assertEquals("Like", ReelDeclutter.countText("Like"));
        assertEquals("", ReelDeclutter.countText(""));
        assertNull("a missing text got one", ReelDeclutter.countText(null));
        assertEquals(ReelDeclutter.COUNTS_ROUTE + ": 4 lists, 4 items, 4 removed. Last reason: " + ReelDeclutter.COUNT
                + ". Removed: " + ReelDeclutter.COUNT + " 4. Kinds: " + ReelDeclutter.COUNT + " 4",
                line(ReelDeclutter.COUNTS_ROUTE));

        Settings.HIDE_REEL_COUNTS.save(false);
        assertEquals("turning the switch off didn't bring the count back", "1.2K", ReelDeclutter.countText("1.2K"));
    }

    @Test
    public void pausedOrNotReadyTheReelCountsStay() {
        Settings.HIDE_REEL_COUNTS.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertEquals(why + " hid a reel count", "345", ReelDeclutter.countText("345"));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertEquals("345", ReelDeclutter.countText("345")));
        assertEquals("the count didn't go again after the pause", ReelDeclutter.NO_COUNT, ReelDeclutter.countText("345"));
    }

    /** A counts switch that can't be read leaves the text, and the report names the hook that threw. */
    @Test
    public void aCountsSwitchThatCantBeReadLeavesTheText() {
        SettingReadsForTests.breakReads(Settings.HIDE_REEL_COUNTS);
        try {
            assertEquals("27", ReelDeclutter.countText("27"));
        } finally {
            SettingReadsForTests.mend(Settings.HIDE_REEL_COUNTS);
        }
        assertEquals(Arrays.asList("a working '" + ReelDeclutter.COUNT + "' hook (it threw "
                + NullPointerException.class.getName() + ")"), HookStatus.missing(FamilyNames.REEL_DECLUTTER));
    }

    /** The counts switch answers for nothing else, and the other switches don't answer for it. */
    @Test
    public void theCountsSwitchRunsOnlyItsOwnHook() {
        assertEquals("the other switches hid a count", "9", ReelDeclutter.countText("9"));
        Settings.HIDE_REEL_CHIPS.save(false);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        Settings.HIDE_REEL_COUNTS.save(true);
        assertFalse("the counts switch hid the Follow button", ReelDeclutter.hideFollowButton());
        assertFalse("the counts switch skipped a footer query", ReelDeclutter.skipHotComment());
        assertFalse("the counts switch hid the search pill", ReelDeclutter.hideSearchPill());
        assertEquals(ReelDeclutter.NO_COUNT, ReelDeclutter.countText("9"));
    }

    @Test
    public void bothFooterQueriesAreSkippedWhileTheirSwitchIsOn() {
        assertTrue(ReelDeclutter.skipHotComment());
        assertTrue(ReelDeclutter.skipSocialBubbles());
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        assertFalse(ReelDeclutter.skipHotComment());
        assertFalse(ReelDeclutter.skipSocialBubbles());
        assertEquals(ReelDeclutter.FOOTER_ROUTE + ": 4 lists, 4 items, 2 removed. Last reason: "
                + ReelDeclutter.SOCIAL_BUBBLES + ". Removed: " + ReelDeclutter.SOCIAL_BUBBLES + " 1, "
                + ReelDeclutter.HOT_COMMENT + " 1. Kinds: " + ReelDeclutter.SOCIAL_BUBBLES + " 2, "
                + ReelDeclutter.HOT_COMMENT + " 2", line(ReelDeclutter.FOOTER_ROUTE));
    }

    /** One switch never answers for another. */
    @Test
    public void eachSwitchRunsOnlyItsOwnHooks() {
        Settings.HIDE_REEL_CHIPS.save(false);
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideFollowingButton());
        assertTrue(ReelDeclutter.skipHotComment());
        Settings.HIDE_REEL_CHIPS.save(true);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(false);
        assertTrue(ReelDeclutter.skipSocialBubbles());
        assertEquals(1, ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG))).length);
        Settings.HIDE_REEL_FOLLOW_BUTTON.save(true);
        Settings.HIDE_REEL_SOCIAL_FOOTER.save(false);
        assertTrue(ReelDeclutter.hideFollowButton());
        assertTrue(ReelDeclutter.hideFollowingButton());
        assertEquals(1, ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG))).length);
    }

    @Test
    public void pausedEveryHookTakesFacebooksPath() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertNull(why + " filtered the chips", ReelDeclutter.filterChips(Arrays.asList(chip(REMIX))));
            assertFalse(why + " hid the Follow button", ReelDeclutter.hideFollowButton());
            assertFalse(why + " hid the Following button", ReelDeclutter.hideFollowingButton());
            assertFalse(why + " skipped the hot comment", ReelDeclutter.skipHotComment());
            assertFalse(why + " skipped the bubbles", ReelDeclutter.skipSocialBubbles());
        }
        PauseForTests.resume();
        assertTrue("the Follow hook didn't come back after the pause", ReelDeclutter.hideFollowButton());
        assertTrue("the Following hook didn't come back after the pause", ReelDeclutter.hideFollowingButton());
    }

    @Test
    public void untilTheSettingsAreReadyEveryHookTakesFacebooksPath() {
        SettingsContextRule.withoutContext(() -> {
            assertNull(ReelDeclutter.filterChips(Arrays.asList(chip(REMIX))));
            assertFalse(ReelDeclutter.hideFollowButton());
            assertFalse(ReelDeclutter.hideFollowingButton());
            assertFalse(ReelDeclutter.skipHotComment());
            assertFalse(ReelDeclutter.skipSocialBubbles());
        });
        assertEquals(1, ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(SONG))).length);
    }

    private static int occurrences(String text, String of) {
        int count = 0;
        for (int at = text.indexOf(of); at >= 0; at = text.indexOf(of, at + 1)) count++;
        return count;
    }

    /**
     * With debug logging on, each kind of change is logged once, which tells a report what the
     * switches did on this phone without a line for every reel. Off, nothing is remembered.
     */
    @Test
    public void eachKindOfChangeIsLoggedOnce() {
        LogBufferManager.clearLogBuffer();
        try {
            ReelDeclutter.hideFollowButton();
            ReelDeclutter.hideFollowingButton();
            BaseSettings.DEBUG.save(true);
            for (int i = 0; i < 3; i++) {
                ReelDeclutter.filterChips(Arrays.asList(chip(REMIX), chip(STARS)));
                ReelDeclutter.hideFollowButton();
                ReelDeclutter.hideFollowingButton();
                ReelDeclutter.skipHotComment();
                ReelDeclutter.skipSocialBubbles();
            }
            String log = LogBufferManager.buildExportText();
            assertEquals(log, 1, occurrences(log, "Reel chips: hid " + REMIX));
            assertEquals(log, 1, occurrences(log, "Reel chips: hid " + STARS));
            assertEquals(log, 1, occurrences(log, "Reel Follow button: hid the Follow button"));
            assertEquals(log, 1, occurrences(log, "Reel Follow button: hid the Following button"));
            assertEquals(log, 1, occurrences(log, "Reel footer: skipped the " + ReelDeclutter.HOT_COMMENT + " query"));
            assertEquals(log, 1, occurrences(log, "Reel footer: skipped the " + ReelDeclutter.SOCIAL_BUBBLES + " query"));
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }
}
