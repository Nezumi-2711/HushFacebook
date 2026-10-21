/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.media.taptoplay.isEnumNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val HIDE_SECTION = "$EXTENSION_PACKAGE/menu/MenuSections;->hideSection(Ljava/lang/Object;)Z"
internal const val HIDE_SERVER_SECTION = "$EXTENSION_PACKAGE/menu/MenuSections;->hideServerSection(Ljava/lang/Object;)Z"
internal const val MUSE_DISMISSED = "$EXTENSION_PACKAGE/menu/MuseCard;->dismissed(ZLjava/lang/Object;)Z"
internal const val SHORTCUTS_KEPT = "$EXTENSION_PACKAGE/menu/MenuShortcuts;->kept(Ljava/util/List;)Ljava/util/List;"
internal const val HELP_ROWS_KEPT = "$EXTENSION_PACKAGE/menu/MenuRows;->help(Ljava/util/List;)Ljava/util/List;"
internal const val SETTINGS_ROWS_KEPT = "$EXTENSION_PACKAGE/menu/MenuRows;->settings(Ljava/util/List;)Ljava/util/List;"
internal const val LIST_COPY_OF = "$IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST"

/**
 * Both sections of a Menu group ask the extension about the group as soon as they know it, and
 * hand back no children when it says the group goes. See MenuSectionAnchors.kt for how the Menu is
 * built. Every other group, Settings and Help included, builds as Facebook built it.
 *
 * The Muse card is a bookmark, not a group: right after its component reads whether the card was
 * dismissed, the answer and the bookmark go to the extension, which can answer yes instead. See
 * MuseCardAnchors.kt.
 *
 * The Meta AI shortcut is a bookmark too, one of the Menu's shortcut rows: right after their
 * section reads its list of bookmarks, the list goes through the extension, which can leave Meta
 * AI's out, and back into an ImmutableList. See MenuShortcutAnchors.kt.
 *
 * The Help and support and Settings and privacy rows come to the native section from two builders.
 * Right after each call the list goes through the extension, which leaves out the rows picked in
 * Menu tiles to hide, and back into an ImmutableList. See MenuRowAnchors.kt.
 *
 * Accounts whose Menu slides in from the side can get the server-drawn Bloks Menu there, which none
 * of the hooks above reach (#184). The server's answer to which Menu goes in the panel goes to the
 * extension, which answers the native one while any Menu switch acts. See MenuPanelAnchors.kt.
 */
@Suppress("unused")
val hideMenuPromotionsPatch = bytecodePatch(
    name = "Hide Menu promotions",
    description = "Hides the Upgrades and Also from Meta sections and the Muse card in Facebook's Menu, so the " +
        "Menu has fewer ads for Meta's products. Settings, Help and your shortcuts stay. On by default. Turn it " +
        "off in Hushfacebook settings > Menu. A switch there, off by default, also hides the Meta AI shortcut, and " +
        "a list there hides the Menu tiles and Help and Settings rows you pick, such as Gaming or Orders and payments.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val isGroupEnum = { type: String -> classDefByOrNull(type)?.let { isEnumNaming(it, SECTION_NAMES) } == true }

        val native = single(NATIVE_SECTION_KEY, "native Menu group section", ::isNativeSectionChildren)
        val nativeRead = nativeGroupRead(native, isGroupEnum) ?: throw PatchException(
            "$PATCH: ${native.definingClass}->${native.name} doesn't make one call answering the Menu's group enum " +
                "(${SECTION_NAMES.joinToString()}) and keep its answer",
        )
        val server = single(SERVER_POSITIONS.first(), "server Menu group section", ::isServerSectionChildren)
        val serverRead = serverGroupRead(server, isGroupEnum) ?: throw PatchException(
            "$PATCH: ${server.definingClass}->${server.name} doesn't read one group field of its own class",
        )
        if (serverRead.type != nativeRead.type) {
            throw PatchException(
                "$PATCH: the native section's group is ${nativeRead.type} and the server section's ${serverRead.type}",
            )
        }

        val rows = rowListHooks(native)
        val nativeMethod = mutableClassDefBy(native.definingClass).findMutableMethodOf(native)
        nativeMethod.buildNothingWhenHidden(nativeRead, children(native), HIDE_SECTION)
        nativeMethod.passRowListsThrough(rows)
        mutableClassDefBy(server.definingClass).findMutableMethodOf(server)
            .buildNothingWhenHidden(serverRead, children(server), HIDE_SERVER_SECTION)

        val render = single(NT_DISMISSED_UPDATE, "Menu bookmark render", ::isBookmarkRender)
        val component = mutableClassDefBy(render.definingClass)
        val dismissal = dismissalRead(component, render) ?: throw PatchException(
            "$PATCH: ${render.definingClass}->${render.name} doesn't make one dismissal read before " +
                "\"$NT_DISMISSED_UPDATE\" and compare its answer at once",
        )
        component.findMutableMethodOf(render).askAboutDismissal(dismissal)

        val folder = single(FOLDER_SECTION, "Menu bookmarks folder section", ::isFolderSectionChildren)
        val listRead = bookmarkListRead(folder) ?: throw PatchException(
            "$PATCH: ${folder.definingClass}->${folder.name} doesn't read one bookmark list of its own class",
        )
        mutableClassDefBy(folder.definingClass).findMutableMethodOf(folder).passBookmarksThroughShortcuts(listRead)
        askAboutBloksPanel()
        askAboutBloksMenuPage()
        enableStatus("menuPromotions")
    }
}

/** The one children builder of a class loading [string] that [wanted] takes, outside the extension. */
private fun BytecodePatchContext.single(string: String, what: String, wanted: (Method) -> Boolean): Method {
    val found = classDefByStrings(string, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .flatMap { classDef -> classDef.methods.filter(wanted) }
    return found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what loading \"$string\", found ${found.size}")
}

private fun BytecodePatchContext.children(method: Method): ChildrenList = childrenList(method) { call ->
    classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) }
} ?: throw PatchException(
    "$PATCH: ${method.definingClass}->${method.name} doesn't read its children from one list it builds",
)

/**
 * Right after the section puts its group in a register, the group goes to [hook], and a yes hands
 * back a new, empty list of children built the way the section builds its own. A no goes on with
 * the section's own next instruction. The list's field is read through a 4-bit operand, so the
 * one register borrowed is a free local no higher than v15; the group goes through the range form,
 * which names any register.
 */
internal fun MutableMethod.buildNothingWhenHidden(read: GroupRead, list: ChildrenList, hook: String) {
    val index = read.index + 1
    val answer = freeLocalsAt(PATCH, index, 1).single()
    addInstructionsWithLabels(
        index,
        """
            invoke-static/range { v${read.register} .. v${read.register} }, $hook
            move-result v$answer
            if-eqz v$answer, :facebook
            new-instance v$answer, ${list.builder}
            invoke-direct/range { v$answer .. v$answer }, ${list.builder}-><init>()V
            iget-object v$answer, v$answer, ${list.children}
            return-object v$answer
        """.trimIndent(),
        ExternalLabel("facebook", getInstruction(index)),
    )
}

/**
 * Right after the folder section reads its bookmarks, a list that's there goes through
 * [SHORTCUTS_KEPT], which never answers null for one, and back through ImmutableList.copyOf into
 * the same register. `copyOf` hands an ImmutableList back as it is, so a list the extension left
 * alone is Facebook's own. A missing list goes on to the section's own null check untouched.
 */
internal fun MutableMethod.passBookmarksThroughShortcuts(read: BookmarkListRead) =
    passListThrough(read.index, read.register, SHORTCUTS_KEPT)

/**
 * Right after the instruction at [at] puts a list in [register], a list that's there goes through
 * [hook] and back through ImmutableList.copyOf into the same register. A missing list goes on to
 * the method's own next instruction untouched.
 */
internal fun MutableMethod.passListThrough(at: Int, register: Int, hook: String) {
    val index = at + 1
    addInstructionsWithLabels(
        index,
        """
            if-eqz v$register, :facebook
            invoke-static/range { v$register .. v$register }, $hook
            move-result-object v$register
            invoke-static/range { v$register .. v$register }, $LIST_COPY_OF
            move-result-object v$register
        """.trimIndent(),
        ExternalLabel("facebook", getInstruction(index)),
    )
}

/** A row list build the native section calls, by its signature, and the extension hook its list goes through. */
internal data class RowListHook(val call: String, val hook: String)

private fun signature(call: MethodReference) =
    "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"

/**
 * The native section's two row list builds, Settings and privacy's then Help and support's, each
 * with its hook, once both are found once on one class and share the one row item MenuRows can
 * read. [classDef] finds a class of the app by type.
 */
internal fun rowListHooks(native: Method, classDef: (String) -> ClassDef?): List<RowListHook> {
    val settings = rowListBuilds(native).singleOrNull() ?: throw PatchException(
        "$PATCH: expected ${native.definingClass}->${native.name} to build one Settings and privacy row list",
    )
    val helps = native.implementation!!.instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { isHelpRowListBuild(it, settings.definingClass) }
        .distinctBy(::signature)
    val help = helps.singleOrNull() ?: throw PatchException(
        "$PATCH: expected ${native.definingClass}->${native.name} to build one Help and support row list, found ${helps.size}",
    )
    val owner = classDef(settings.definingClass)
        ?: throw PatchException("$PATCH: the row list builder ${settings.definingClass} isn't in this build")
    val builders = listOf(settings, help).map { call ->
        owner.methods.singleOrNull { signature(it) == signature(call) && it.implementation != null }
            ?: throw PatchException("$PATCH: ${signature(call)} has no body in this build")
    }
    val items = builders.map(::constructedTypes).reduce { one, other -> one intersect other }
        .filter { type -> classDef(type)?.let(::isReadableRowItem) == true }
    if (items.size != 1) {
        throw PatchException("$PATCH: the Help and Settings row builders share ${items.size} readable row items, not one")
    }
    return listOf(RowListHook(signature(settings), SETTINGS_ROWS_KEPT), RowListHook(signature(help), HELP_ROWS_KEPT))
}

private fun BytecodePatchContext.rowListHooks(native: Method) = rowListHooks(native) { classDefByOrNull(it) }

/**
 * Each of [hooks]' lists, right after the section keeps it, goes through its hook. The reads are
 * found on this method as it is now, after the group hook moved its code down, and hooked from the
 * last up so the earlier ones stay where they were found.
 */
internal fun MutableMethod.passRowListsThrough(hooks: List<RowListHook>) {
    hooks.map { hook ->
        val reads = rowListReads(this) { signature(it) == hook.call }
            ?: throw PatchException("$PATCH: $definingClass->$name doesn't keep the list ${hook.call} hands back")
        val read = reads.singleOrNull()
            ?: throw PatchException("$PATCH: expected $definingClass->$name to call ${hook.call} once, found ${reads.size}")
        read to hook.hook
    }.sortedByDescending { it.first.index }.forEach { (read, hook) -> passListThrough(read.index, read.register, hook) }
}

/**
 * Right after the bookmark component reads whether its card was dismissed, the answer and the
 * bookmark go to [MUSE_DISMISSED], and what it says replaces Facebook's answer before the render
 * compares it with the card's state. Both registers fit the non-range call's 4-bit operands, which
 * [dismissalRead] makes sure of.
 */
internal fun MutableMethod.askAboutDismissal(read: DismissalRead) = addInstructions(
    read.index + 1,
    """
        invoke-static { v${read.answer}, v${read.bookmark} }, $MUSE_DISMISSED
        move-result v${read.answer}
    """.trimIndent(),
)
