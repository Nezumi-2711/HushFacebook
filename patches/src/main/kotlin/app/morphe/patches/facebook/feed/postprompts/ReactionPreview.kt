/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.layout.theme.INLINE_REACTIONS
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/*
 * The reaction preview under a post (#158). Read from 583, 2026-10-18, arm64 and armv7; the
 * obfuscated names here are for reviewers, the code never writes one down.
 *
 * Two drawers paint it, and both are made to return before painting while the switch is on. The
 * views keep their size and their click handlers, so a tap where the preview was still works.
 * - The stacked reaction faces at the end of the button row: the Litho component Facebook names
 *   FDSInlineReactions (arm64 LX/3Ec;) has one static factory that returns a Drawable subclass (arm64
 *   LX/3Ed;, the facepile), whose draw(Canvas) paints each face and the ring between them.
 * - The bling bar's reaction icons and count line: PillsBlingBarView, a view whose class name Facebook
 *   keeps. Its onDraw(Canvas) paints the segments and nothing else, as the background is View.draw's.
 * The button numbers are not here (they are the count builder's, PostCounts.kt).
 */

internal const val PILLS_BLING_BAR = "Lcom/facebook/feedback/reactions/ui/PillsBlingBarView;"
internal const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
internal const val CANVAS = "Landroid/graphics/Canvas;"
internal const val HIDE_REACTION_PREVIEW = "$POST_PROMPTS->hideReactionPreview()Z"

/** Whether [method] is `draw(Canvas)` or `onDraw(Canvas)` returning void. */
internal fun isDrawMethod(method: Method, name: String): Boolean =
    method.name == name && method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf(CANVAS) &&
        method.implementation != null

/**
 * The types [component]'s static methods return that [isDrawable] accepts: the facepile its factory
 * builds.
 */
internal fun facepileTypes(component: ClassDef, isDrawable: (String) -> Boolean): List<String> =
    component.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
        .map { it.returnType }.filter(isDrawable).distinct()

/**
 * Asks the extension first thing and returns before anything is painted when it says so. At the
 * first instruction no local holds anything yet, so v0 is free there.
 */
internal fun MutableMethod.skipDrawWhen(ask: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            $ask
            move-result v0
            if-eqz v0, :facebooks_draw
            return-void
        """,
        ExternalLabel("facebooks_draw", getInstruction(0)),
    )
}

/**
 * Finds the facepile through FDSInlineReactions and the bling bar by its kept class name, and has
 * each draw method return early while the switch is on. Refuses unless each step finds one.
 */
internal fun BytecodePatchContext.hideReactionPreview() {
    val components = classDefByStrings(INLINE_REACTIONS, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
    val component = components.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one component named $INLINE_REACTIONS, found ${components.size}",
    )
    val faces = facepileTypes(component) { type ->
        classDefByOrNull(type)?.let { it.superclass == DRAWABLE && it.methods.any { m -> isDrawMethod(m, "draw") } } == true
    }
    val facepile = faces.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one Drawable built by ${component.type}'s static factory, found ${faces.size}",
    )
    val bar = classDefByOrNull(PILLS_BLING_BAR) ?: throw PatchException("$PATCH: $PILLS_BLING_BAR isn't in this build")
    val barDraws = bar.methods.filter { isDrawMethod(it, "onDraw") }
    if (barDraws.size != 1) throw PatchException("$PATCH: expected one onDraw(Canvas) in ${bar.type}, found ${barDraws.size}")

    val factories = component.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == facepile }
    val factory = factories.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static method on ${component.type} returning $facepile, found ${factories.size}",
    )

    // Comments draw the same facepile, so it learns which surface it was built for before it can be asked.
    tagCommentFaces(component, classDefBy(facepile), factory)
    mutableClassDefBy(facepile).methods.single { isDrawMethod(it, "draw") }.skipDrawWhen(facepileAsk(facepile))
    mutableClassDefBy(bar.type).methods.single { isDrawMethod(it, "onDraw") }
        .skipDrawWhen("invoke-static { }, $HIDE_REACTION_PREVIEW")
}
