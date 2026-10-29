/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Whether a feed post was posted in a group the person isn't in (issue #195). Home shows a friend's
 * post in a public group with a Join button next to the group's name, and it isn't a suggestion:
 * no recommendation flag is set and the edge comes as an ordinary story.
 *
 * <p>A story's {@code to} is the profile it was posted to, typed Profile, which answers
 * {@code getTypeName()} with "Group" for a post in a group. Facebook's own code reads it that way.
 * The group's {@code viewer_join_state} is the enum Facebook's Join button reads: it shows the
 * button for CAN_JOIN and CAN_REQUEST, the two {@link #NOT_JOINED} names. MEMBER, REQUESTED,
 * CANNOT_JOIN_OR_REQUEST and the unset default all keep the post, and so does a story whose state
 * Home didn't fetch, which reads as the default.
 *
 * <p>A group post a friend commented on or shared reaches Home wrapped in the friend's activity
 * ("Tasha replied to a comment."), a story posted to no profile of its own: the group, and the
 * Join button Facebook draws, are on the post it wraps ({@code attached_story}), so that post's
 * group decides, through the accessor Hide posts with words fills. A build without that patch
 * can't read the wrapped post and keeps it. The second report on #195 (2026-10-29) showed one of
 * those getting through while every post posted straight to a joinable group was hidden.
 *
 * <p>The story's accessor of {@code to} and the profile's accessor of its join state are Redex
 * names, so the patch fills in {@link #storyTarget} and {@link #joinState}. The enum's constant
 * names survive the obfuscator. What the report counts is a shape, never the group.
 *
 * <p>Home asks the server for the join state only while a MobileConfig answer says so, and an
 * account without it reads the unset default on every group post. The patch hands that answer to
 * {@link #askForJoinState}, which says yes while the switch is on.
 */
public final class GroupPosts {
    /** The route the report counts each story read for its group on, while the switch is on. */
    static final String ROUTE = "Groups you're not in";

    /** The type name a story's target answers when the post was posted in a group. */
    static final String GROUP_TYPE = "Group";
    /** The GraphQL fields read, for the report. */
    static final String TARGET_FIELD = "to";
    static final String JOIN_STATE_FIELD = "viewer_join_state";

    /** The join states Facebook shows its Join button for, the only two that hide a post. */
    static final String[] NOT_JOINED = {"CAN_JOIN", "CAN_REQUEST"};

    /** What {@link #read} answers when there's no state to read, each its own shape for the report. */
    static final String NOT_A_STORY = "not a story";
    static final String NOT_PATCHED = "accessor not patched";
    static final String NO_TARGET = "not posted to a profile";
    static final String RELEASED = "tree released";
    static final String NOT_A_GROUP = "not posted in a group";
    static final String NO_STATE = "no join state";
    static final String NOT_AN_ENUM = "join state not an enum";
    static final String READ_FAILED = "read failed";
    /** The read went through the post the story wraps: what it found, under this, for the report. */
    static final String WRAPPED = "wrapped ";
    /** A story posted to no profile whose wrapped post can't be read: Hide posts with words isn't in the build. */
    static final String NO_WRAPPED_READER = "wrapped post not readable";

    private static final String FAMILY = FamilyNames.SUGGESTED_POSTS;

    /** Counted under the patch's name each time Home is asked for join states Facebook wouldn't ask for. */
    static final String ASKED = "Home asked for groups' join states";

    private static final String QUERY_HOOK = "group join state query";

    /**
     * The accessors a read goes through: the two stubs the patch filled, and the story's
     * {@code attached_story} that Hide posts with words fills, or stand-ins in a test.
     */
    static final class Readers {
        final StoryFlag.Accessor target;
        final StoryFlag.Accessor joinState;
        final StoryFlag.Accessor attached;

        Readers(StoryFlag.Accessor target, StoryFlag.Accessor joinState) {
            this(target, joinState, PostText.ATTACHED);
        }

        Readers(StoryFlag.Accessor target, StoryFlag.Accessor joinState, StoryFlag.Accessor attached) {
            this.target = target;
            this.joinState = joinState;
            this.attached = attached;
        }
    }

    static final Readers PATCHED = new Readers(GroupPosts::storyTarget, GroupPosts::joinState);

    private GroupPosts() {
    }

    /**
     * Injection point, filled in by the patch: the story's {@code to}, or null when it has none.
     * Only a GraphQLStory may be passed.
     */
    public static Object storyTarget(Object story) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection point, filled in by the patch: the profile's {@code viewer_join_state} enum, or
     * Facebook's unset constant when it has none. Only a story's {@code to} may be passed.
     */
    public static Object joinState(Object profile) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * Injection point, in Home's query builder, right before it hands over its answer for
     * {@code should_include_viewer_join_state}. Never throws.
     *
     * @param facebooks whether Facebook would ask for each group's join state.
     * @return whether to ask for it.
     */
    public static boolean askForJoinState(boolean facebooks) {
        try {
            HookStatus.invoked(FAMILY);
            if (facebooks || !Utils.settingsReady() || !Settings.HIDE_GROUP_POSTS_NOT_JOINED.get()) {
                Logger.printDebug(() -> "Group posts: kept Facebook's " + facebooks + " for join states");
                return facebooks;
            }
            HookStatus.bound(FAMILY, QUERY_HOOK);
            HookStatus.counted(FAMILY, ASKED);
            Logger.printDebug(() -> "Group posts: asked Home for join states");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, QUERY_HOOK, failure);
            Logger.printException(() -> "Group posts: could not read the switch for join states", failure);
            return facebooks;
        }
    }

    /**
     * The join state's constant name for a post in a group, or why there is none. A story posted to
     * no profile is read again through the post it wraps, and what that found comes back under
     * {@link #WRAPPED}. Never throws: an accessor that throws is recorded in Hook status and the
     * post reads as {@link #READ_FAILED}, which keeps it.
     */
    static String read(@Nullable Object feedUnit, Readers readers) {
        Class<?> story = StoryFlag.members().story;
        if (feedUnit == null || story == null || !story.isInstance(feedUnit)) return NOT_A_STORY;
        if (FeedFilter.released(feedUnit)) return RELEASED;
        try {
            Object target = readers.target.model(feedUnit);
            if (target == StoryFlag.NOT_PATCHED) {
                HookStatus.missingMember(FAMILY, "method", StoryFlag.STORY_CLASS, "the " + TARGET_FIELD + " accessor");
                return NOT_PATCHED;
            }
            if (target != null) return stateOf(target, readers, "");
            // Posted to no profile: a post a friend commented on or shared wraps the group's post.
            Object wrapped = readers.attached.model(feedUnit);
            if (wrapped == StoryFlag.NOT_PATCHED) return NO_WRAPPED_READER;
            if (wrapped == null || !story.isInstance(wrapped)) return NO_TARGET;
            if (FeedFilter.released(wrapped)) return RELEASED;
            Object wrappedTarget = readers.target.model(wrapped);
            if (wrappedTarget == null || wrappedTarget == StoryFlag.NOT_PATCHED) return NO_TARGET;
            return stateOf(wrappedTarget, readers, WRAPPED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "group join state reader", failure);
            return READ_FAILED;
        }
    }

    /**
     * The join state's constant name of [target], the profile a story was posted to, or why there
     * is none, each under [prefix] for the report. Throws what an accessor throws.
     */
    private static String stateOf(Object target, Readers readers, String prefix) {
        if (FeedFilter.released(target)) return RELEASED;
        if (!GROUP_TYPE.equals(FeedFilter.typeName(target))) return prefix + NOT_A_GROUP;
        Object state = readers.joinState.model(target);
        if (state == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FAMILY, "method", GROUP_TYPE, "the " + JOIN_STATE_FIELD + " accessor");
            return NOT_PATCHED;
        }
        if (state == null) return prefix + NO_STATE;
        if (!(state instanceof Enum)) return prefix + NOT_AN_ENUM;
        HookStatus.bound(FAMILY, GROUP_TYPE + "#" + JOIN_STATE_FIELD);
        return prefix + ((Enum<?>) state).name();
    }

    /**
     * Whether what a read found is a group the person could join, so isn't in, the post posted to
     * it straight or wrapped in a friend's activity.
     */
    static boolean notJoined(@Nullable String kind) {
        if (kind == null) return false;
        String state = kind.startsWith(WRAPPED) ? kind.substring(WRAPPED.length()) : kind;
        for (String joinable : NOT_JOINED) {
            if (joinable.equals(state)) return true;
        }
        return false;
    }
}
