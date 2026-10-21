/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

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

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Don't save search history (#196): with its switch on, every search Facebook would save is
 * answered as one it doesn't, and counted. A search Facebook already keeps out stays out and isn't
 * counted. Off, paused, or before the settings are ready, Facebook's own answer comes back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SearchHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.DONT_SAVE_SEARCH_HISTORY.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.SEARCH_HISTORY + ":")) return line;
        }
        return null;
    }

    @Test
    public void onEverySearchIsKeptOutAndCounted() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        assertTrue("a search was saved", SearchHistory.skipSave(false));
        assertTrue("a search Facebook keeps out was saved", SearchHistory.skipSave(true));
        assertEquals(FamilyNames.SEARCH_HISTORY + ": invoked 2, 1 found, 0 missing. Counted: "
                + SearchHistory.KEPT_OUT + " 1", statusLine());
    }

    @Test
    public void offFacebooksOwnAnswerComesBack() {
        assertFalse("off, a search was kept out", SearchHistory.skipSave(false));
        assertTrue("off, a search Facebook keeps out was saved", SearchHistory.skipSave(true));
        assertFalse("off, a search was counted", String.valueOf(statusLine()).contains(SearchHistory.KEPT_OUT));
    }

    @Test
    public void pausedFacebooksOwnAnswerComesBack() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse("paused, a search was kept out", SearchHistory.skipSave(false));
        assertTrue("paused, a search Facebook keeps out was saved", SearchHistory.skipSave(true));
    }

    @Test
    public void beforeTheSettingsAreReadyFacebooksOwnAnswerComesBack() {
        Settings.DONT_SAVE_SEARCH_HISTORY.save(true);
        SettingsContextRule.withoutContext(() -> {
            assertFalse("before the settings were ready, a search was kept out", SearchHistory.skipSave(false));
            assertTrue(SearchHistory.skipSave(true));
        });
    }

    @Test
    public void startsOff() {
        Settings.DONT_SAVE_SEARCH_HISTORY.resetToDefault();
        assertFalse(Settings.DONT_SAVE_SEARCH_HISTORY.get());
        assertNull("nothing was reported before a search", statusLine());
    }
}
