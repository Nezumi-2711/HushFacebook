/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The "3 new comments" pill that pops up over an open comment list, read from 583 on 2026-10-18
 * (#185). The obfuscated names are for reviewers; the code finds the spot by its shape.
 *
 * The comment list's adapter observer (583 arm64 LX/W52;->A06(II)V, armv7 LX/Vfc;->A06(II)V) hears
 * each insert. When the list was at its end it scrolls to the new comment with
 * LinearLayoutManager.scrollToPositionWithOffset; otherwise it counts the new ones and writes the
 * pill's text with the plural helper, a static `(Resources, int, int) -> String` call on the
 * "%1$d new comments" plural. It then checks that text's length and returns before the pill is
 * built when it's empty. The hook goes right after the text comes back: the extension answers an
 * empty text while the switch is on, so Facebook's own check skips the pill.
 */

private const val LIST_LAYOUT = "Landroidx/recyclerview/widget/LinearLayoutManager;"

internal const val NEW_COMMENTS_PILL = "$COMMENT_SHEET_OPTIONS->newCommentsPill(Ljava/lang/String;)Ljava/lang/String;"

private const val PATCH = "Comment sheet options (new comments pill)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The pill's text goes through the extension. Nameless, so Comment sheet options carries it and its
 * fixture tests run it on its own. It finds the spot before changing anything and refuses unless
 * there's exactly one.
 */
internal val newCommentsPillPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        hookNewCommentsPill()
    }
}

/** Where the pill's text comes back: the `move-result-object`'s index and the register it fills. */
internal data class PillText(val method: Method, val index: Int, val register: Int)

internal fun BytecodePatchContext.hookNewCommentsPill() {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classDef: ClassDef ->
        if (!classDef.type.startsWith(EXTENSION_CLASSES)) classes += classDef
    }
    val text = newCommentsPillText(classes)
    val method = mutableClassDefBy(text.method.definingClass).findMutableMethodOf(text.method)
    method.addInstructions(
        text.index + 1,
        """
            invoke-static/range { v${text.register} .. v${text.register} }, $NEW_COMMENTS_PILL
            move-result-object v${text.register}
        """,
    )
}

private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)

private fun calls(code: List<Instruction>, owner: String, name: String) =
    code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call -> call.definingClass == owner && call.name == name } == true }

/** A static `(Resources, int, int) -> String` call, the shape of Facebook's plural helper. */
private fun isPluralText(call: MethodReference) = call.returnType == "Ljava/lang/String;" &&
    call.params() == listOf("Landroid/content/res/Resources;", "I", "I")

/**
 * The one place in [classes] the pill's text is written. The method is a non-static `(II)V` insert
 * callback that calls both LinearLayoutManager.findLastCompletelyVisibleItemPosition and
 * scrollToPositionWithOffset, and it holds one static plural-text call whose result is taken by a
 * `move-result-object` and then measured with String.length() before anything else reads it.
 */
internal fun newCommentsPillText(classes: Iterable<ClassDef>): PillText {
    val found = mutableListOf<PillText>()
    for (classDef in classes) {
        for (method in classDef.methods) {
            if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
                method.parameterTypes.map(CharSequence::toString) != listOf("I", "I")
            ) continue
            val code = method.implementation?.instructions?.toList() ?: continue
            if (!calls(code, LIST_LAYOUT, "findLastCompletelyVisibleItemPosition") ||
                !calls(code, LIST_LAYOUT, "scrollToPositionWithOffset")
            ) continue
            for ((index, instruction) in code.withIndex()) {
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (instruction.opcode != Opcode.INVOKE_STATIC || !isPluralText(call)) continue
                val result = code.getOrNull(index + 1) ?: continue
                if (result.opcode != Opcode.MOVE_RESULT_OBJECT) continue
                val register = (result as OneRegisterInstruction).registerA
                val measured = code.drop(index + 2).take(3).any { next ->
                    val read = (next as? ReferenceInstruction)?.reference as? MethodReference
                    next.opcode == Opcode.INVOKE_VIRTUAL && read?.definingClass == "Ljava/lang/String;" &&
                        read.name == "length" && (next as FiveRegisterInstruction).registerC == register
                }
                if (measured) found += PillText(method, index + 1, register)
            }
        }
    }
    return found.singleOrNull() ?: refuse("expected one comment list writing the new comments text, found ${found.size}")
}
