/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.menu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/** The Menu tiles list (#153): what a deep link keys to, and how the saved list is read and kept. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MenuTilesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.HIDDEN_MENU_TILES.resetToDefault();
    }

    @Test
    public void aDeepLinkKeysToItsFirstWord() {
        assertEquals("marketplace", MenuTiles.keyOf("fb://marketplace"));
        assertEquals("marketplace", MenuTiles.keyOf("fb://marketplace_tab"));
        assertEquals("gaming", MenuTiles.keyOf("FB://Gaming/home?ref=menu"));
        assertEquals("marketplace", MenuTiles.keyOf("https://www.facebook.com/marketplace/"));
        assertEquals("watch", MenuTiles.keyOf("https://m.facebook.com/watch/?ref=bookmarks#top"));
        assertEquals("groups", MenuTiles.keyOf("https://facebook.com//groups/feed&x=1"));
    }

    @Test
    public void aLinkWithNoWordOrNotFacebooksKeysToNothing() {
        assertNull(MenuTiles.keyOf(null));
        assertNull(MenuTiles.keyOf(""));
        assertNull(MenuTiles.keyOf("marketplace"));
        assertNull(MenuTiles.keyOf("fb://"));
        assertNull(MenuTiles.keyOf("fb://?a=b"));
        assertNull(MenuTiles.keyOf("https://example.com/marketplace"));
        assertNull(MenuTiles.keyOf("https://notfacebook.com/marketplace"));
        assertNull(MenuTiles.keyOf("fb://Market place"));
    }

    @Test
    public void thePickedKeysAreSavedCleanAndKeepOrder() {
        assertEquals(MenuTiles.Hidden.SAVED, MenuTiles.hide(Arrays.asList("gaming", "marketplace")));
        assertEquals("gaming,marketplace", Settings.HIDDEN_MENU_TILES.savedValue());
        assertEquals(Arrays.asList("gaming", "marketplace"), new ArrayList<>(MenuTiles.hidden()));
        assertEquals(MenuTiles.Hidden.SAVED, MenuTiles.hide(Collections.<String>emptyList()));
        assertEquals("", Settings.HIDDEN_MENU_TILES.savedValue());
    }

    @Test
    public void aListThatIsntCleanIsCleanedAndAKeyNotInTheCatalogIsKept() {
        assertEquals("gaming,new_tile", MenuTiles.clean("gaming, ,Bad Key,gaming,new_tile"));
        assertTrue(MenuTiles.isClean("gaming,new_tile"));
        assertFalse(MenuTiles.isClean("gaming,gaming"));
        assertFalse(MenuTiles.isClean(null));
        assertEquals(2, MenuTiles.count("gaming,new_tile"));
        Settings.HIDDEN_MENU_TILES.save("new_tile");
        assertTrue(MenuTiles.choices().containsAll(MenuTiles.CATALOG));
        assertTrue(MenuTiles.choices().contains("new_tile"));
    }

    @Test
    public void aListTooLongForTheSettingSavesNothing() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 100; i++) keys.add("tile_number_" + i);
        assertEquals(MenuTiles.Hidden.NO_ROOM, MenuTiles.hide(keys));
        assertEquals("", Settings.HIDDEN_MENU_TILES.savedValue());
    }

    @Test
    public void everyCatalogTileHasAReadableLabel() {
        for (String key : MenuTiles.CATALOG) {
            assertFalse(key, MenuTiles.label(key).isEmpty());
            assertFalse(key + " is labeled with its key", MenuTiles.label(key).equals(key));
        }
        assertEquals("New tile", MenuTiles.label("new_tile"));
    }
}
