/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;

import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * The Background color row (issue #214): the table of resources route two wrote over, read as
 * AmoledResources loads, the colours its loader gives them, and the page colour route three reads.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledResourcesTest {
    private static final int NAVY = 0xFF0D1117;
    private static final int PAGE_ID = 0x7f060020;
    private static final AmoledResources.LoaderMaker MAKER = AmoledResources.maker;

    @Rule
    public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void forget() {
        AmoledResources.useTable(null);
        AmoledResources.maker = MAKER;
        AmoledTheme.useBackground(0xFF000000);
        Settings.AMOLED_BACKGROUND.resetToDefault();
        AmoledTheme.useLight(0);
        Settings.AMOLED_LIGHT_BACKGROUND.resetToDefault();
        DarkMode.answer(true);
    }

    /**
     * Issue #214: the Light background color row is read with the Background color row as Facebook
     * starts, and a colour its rule refuses, kept from before or written by hand, leaves Facebook's
     * white.
     */
    @Test
    public void theLightRowIsReadAsFacebookStartsAndARefusedColourLeavesWhite() {
        DarkMode.answer(false);
        Settings.AMOLED_LIGHT_BACKGROUND.save("#000000");
        AmoledTheme.load();
        assertEquals("black is refused", 0xFFFFFFFF, AmoledTheme.apply(0xFFFFFFFF, AmoledThemeTest.Token.CARD_BACKGROUND));

        Settings.AMOLED_LIGHT_BACKGROUND.save("#A5A5A5");
        AmoledTheme.load();
        assertEquals("one step under the darkest grey is refused", 0xFFFFFFFF,
                AmoledTheme.apply(0xFFFFFFFF, AmoledThemeTest.Token.CARD_BACKGROUND));

        Settings.AMOLED_LIGHT_BACKGROUND.save("#FFF8E7");
        AmoledTheme.load();
        assertEquals(0xFFFFF8E7, AmoledTheme.apply(0xFFFFFFFF, AmoledThemeTest.Token.CARD_BACKGROUND));
    }

    /**
     * Facebook builds Home's feed as it starts, on the application's resources, before its first
     * activity. A loader given to each activity as it was created came too late for the feed, and
     * its page, its composer and the Reels row kept the patch's colour on a phone, so the row's
     * colour reaches the application's assets as the row is read.
     */
    @Test
    public void theApplicationsResourcesTakeTheRowsColourAsFacebookStarts() throws Exception {
        AmoledResources.useTable("7f060002=ff242526/p;7f060010=p");
        ResourcesLoader made = new ResourcesLoader();
        AmoledResources.maker = table -> made;
        Resources application = RuntimeEnvironment.getApplication().getResources();

        AmoledTheme.load();
        assertFalse("a blank row keeps the patch's colour", loaders(application).contains(made));

        Settings.AMOLED_BACKGROUND.save("#0D1117");
        AmoledTheme.load();
        assertEquals(NAVY, AmoledTheme.background());
        assertTrue(loaders(application).contains(made));
    }

    /** The loaders on {@code resources}' assets: hidden in the SDK, public in the framework Robolectric runs. */
    @SuppressWarnings("unchecked")
    private static List<ResourcesLoader> loaders(Resources resources) throws ReflectiveOperationException {
        return (List<ResourcesLoader>) Resources.class.getMethod("getLoaders").invoke(resources);
    }

    @Test
    public void theTableIsReadSortedAndAnUnreadableEntryIsLeftOut() {
        AmoledResources.Table table = AmoledResources.parse(
                "7f060010=p;7f060002=ff242526/p;zz=p;7f060005=p/x;7f060003=p/p;7f060004=p/p/p;7f060010=ff000000");
        assertArrayEquals(new int[] {0x7f060002, 0x7f060003, 0x7f060010}, table.ids);
        assertArrayEquals(new boolean[] {false, true, true}, table.defaultWritten);
        assertArrayEquals(new boolean[] {true, true, false}, table.hasNight);
        assertEquals(0xFF242526, table.defaults[0]);
        assertEquals(0, AmoledResources.parse(null).ids.length);
        assertEquals(0, AmoledResources.parse("").ids.length);
    }

    @Test
    public void theLoaderGivesTheRowsColourWhereRouteTwoWroteAndThePagesResourceToo() {
        AmoledResources.Table table = AmoledResources.parse("7f060002=ff242526/p;7f060010=p");
        int[][] given = AmoledResources.colours(table, NAVY, PAGE_ID);
        assertArrayEquals(new int[] {0x7f060002, 0x7f060010, PAGE_ID}, given[0]);
        assertArrayEquals("Facebook's default stays where route two left it", new int[] {0xFF242526, NAVY, NAVY}, given[1]);
        assertArrayEquals(new int[] {NAVY, 0, 0}, given[2]);
        assertArrayEquals(new int[] {1, 0, 0}, given[3]);

        assertEquals("a build without the page's resource", 2, AmoledResources.colours(table, NAVY, 0)[0].length);
        assertEquals("one the table lists already", 2, AmoledResources.colours(table, NAVY, 0x7f060010)[0].length);
    }

    @Test
    public void wroteAnswersForTheTablesIdsOnly() {
        AmoledResources.useTable("7f060002=ff242526/p;7f060010=p");
        assertTrue(AmoledResources.wrote(0x7f060010));
        assertFalse(AmoledResources.wrote(0x7f060011));
    }

    enum Token { WASH }

    /**
     * Patched black, the row navy: Facebook's own black stays black on route one, as routes two and
     * three leave it, while a grey and a patched colour that isn't black take the row's.
     */
    @Test
    public void facebooksBlackStaysBlackUnderTheRowsColour() {
        DarkMode.answer(true);
        AmoledTheme.useBackground(0xFF000000);
        AmoledTheme.use(NAVY, AmoledBackground.lightestStep(NAVY));
        assertEquals(0xFF000000, AmoledTheme.apply(0xFF000000, Token.WASH));
        assertEquals(NAVY, AmoledTheme.apply(0xFF101011, Token.WASH));

        int sand = 0xFF5B513F;
        AmoledTheme.useBackground(sand, AmoledBackground.lightestStep(sand));
        AmoledTheme.use(NAVY, AmoledBackground.lightestStep(NAVY));
        assertEquals("a resource still holding the patched colour", NAVY, AmoledTheme.apply(sand, Token.WASH));
        assertEquals(0xFF000000, AmoledTheme.apply(0xFF000000, Token.WASH));
    }

    /** Route three reads PAGE and PAGE_WIDE, and a long holds the colour as const-wide/32 sign-extends it. */
    @Test
    public void theRowsColourGoesWhereRouteThreeReadsIt() {
        AmoledTheme.useBackground(0xFF000000);
        AmoledTheme.use(NAVY, AmoledBackground.lightestStep(NAVY));
        assertEquals(NAVY, AmoledTheme.PAGE);
        assertEquals((long) NAVY, AmoledTheme.PAGE_WIDE);
        assertEquals(NAVY, AmoledTheme.background());
        assertEquals("the patch's colour stays what the row stands in for", 0xFF000000, AmoledTheme.written());
    }
}
