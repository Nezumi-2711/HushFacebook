/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Hide the search pill under a reel's caption (#173). Read from 583, 2026-10-17, arm64 and armv7;
 * the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - The pill is a Litho component Facebook names FbShortsSearchElevatedCTAComponent (arm64
 *   LX/dG9;). Its static initializer holds that name and its render method holds the
 *   search_pivot_view_tag literal. A second class (arm64 LX/bCo;) holds the same name in its
 *   initializer, but it has no render and no view tag.
 * - One static builder makes the component (arm64 LX/Ux1;->A01): it is the only method that does
 *   new-instance of it. Facebook's own early exit, taken when the query text is missing, is its last
 *   three instructions: a no-argument static call, its move-result-object and the return. That call
 *   builds the empty component every caller already copes with, so the switch answers with it.
 * - The builder's callers are the reel footer's render methods (arm64 LX/AQ0; and LX/AQ1;).
 */

internal const val ELEVATED_CTA = "FbShortsSearchElevatedCTAComponent"
internal const val PIVOT_VIEW_TAG = "search_pivot_view_tag"
internal const val HIDE_SEARCH_PILL = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->hideSearchPill()Z"

private const val PATCH = "Clean up Reels"

/** Whether [classDef] is the pill component: its initializer names it and its render holds the view tag. */
internal fun isPillComponent(classDef: ClassDef): Boolean =
    classDef.methods.any { it.name == "<clinit>" && holdsString(it, ELEVATED_CTA) } &&
        classDef.methods.any { it.name == "render" && holdsString(it, PIVOT_VIEW_TAG) }

/**
 * Facebook's empty answer in [builder]: the no-argument static call that ends it, as its last three
 * instructions (call, move-result-object, return-object). Null when the method doesn't end that way.
 */
internal fun emptyComponentCall(builder: Method): MethodReference? {
    val code = builder.implementation?.instructions?.toList() ?: return null
    if (code.size < 3) return null
    val (call, result, ret) = code.takeLast(3)
    if (call.opcode != Opcode.INVOKE_STATIC || result.opcode != Opcode.MOVE_RESULT_OBJECT || ret.opcode != Opcode.RETURN_OBJECT) return null
    val reference = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return reference.takeIf { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
}

/** Whether [method] is a static builder that does new-instance of [component] and ends in the empty answer. */
internal fun isPillBuilder(method: Method, component: String): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as? TypeReference)?.type == component
        } == true && emptyComponentCall(method) != null

/** The invoke-static operand the hook writes for [this] call. */
internal fun MethodReference.asCall(): String = "$definingClass->$name()$returnType"

/**
 * Asks the extension first thing and answers Facebook's own empty component when it says so. At
 * the first instruction no local holds anything yet, so v0 is free there.
 */
internal fun MutableMethod.hidePillWhen(hook: String, empty: MethodReference) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_pill
            invoke-static { }, ${empty.asCall()}
            move-result-object v0
            return-object v0
        """,
        ExternalLabel("facebooks_pill", getInstruction(0)),
    )
}

/**
 * Finds the pill component by its kept literals, then the one builder of it, and has the builder
 * answer Facebook's empty component while the switch is on. Refuses unless each step finds one.
 */
internal fun BytecodePatchContext.hideSearchPill() {
    val components = classDefByStrings(ELEVATED_CTA, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.filter(::isPillComponent).distinctBy { it.type }
    val component = components.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one component naming $ELEVATED_CTA that renders $PIVOT_VIEW_TAG, found ${components.size}",
    )
    val builders = mutableListOf<Pair<String, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_PACKAGE) || classDef.type == component.type) return@classDefForEach
        classDef.methods.filter { isPillBuilder(it, component.type) }.forEach { builders += classDef.type to it }
    }
    val (owner, builder) = builders.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static builder of ${component.type} ending in an empty component, found ${builders.size}",
    )
    val empty = emptyComponentCall(builder)!!
    val method = mutableClassDefBy(owner).methods.single {
        it.name == builder.name && it.returnType == builder.returnType &&
            it.parameterTypes.map(CharSequence::toString) == builder.parameterTypes.map(CharSequence::toString)
    }
    method.hidePillWhen(HIDE_SEARCH_PILL, empty)
}
