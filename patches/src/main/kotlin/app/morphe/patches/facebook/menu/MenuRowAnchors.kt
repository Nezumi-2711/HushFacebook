/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where the Menu's Help and support and Settings and privacy rows come from, on 583 (#153).
 *
 * The native group section (see MenuSectionAnchors.kt) switches on its group. Help and support
 * gets its rows from a builder taking only the user session (583 `LX/92y;->A05`, its 32-bit build
 * `LX/945;->A05`), Settings and privacy from the one SettingsRowAnchors.kt finds (`A08`), on the
 * same class. Both hand back an ImmutableList of the plain row items SettingsRowAnchors.kt
 * describes, and both keep that list in a field and hand it back on the next call, so the hook
 * filters the section's copy right after each call, never the kept list. Help's row ids are
 * constants; Settings' are constants and the server's bookmark ids.
 */

/** The Help and support row list build: a virtual call on [owner] taking only the user session and handing back an ImmutableList. */
internal fun isHelpRowListBuild(call: MethodReference, owner: String): Boolean =
    call.definingClass == owner && call.returnType == IMMUTABLE_LIST &&
        call.parameterTypes.map { it.toString() } == listOf(USER_SESSION)

/** Where [method] keeps a row list: the `move-result-object` at [index], into [register]. */
internal data class RowListRead(val index: Int, val register: Int)

private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/**
 * The result of each virtual call in [method] that [wanted] picks, in order, or null when one of
 * them isn't kept by a `move-result-object` right after it.
 */
internal fun rowListReads(method: Method, wanted: (MethodReference) -> Boolean): List<RowListRead>? {
    val code = method.implementation?.instructions?.toList() ?: return null
    return code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode in VIRTUAL_CALLS && call != null && wanted(call)
    }.map { at ->
        val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
        RowListRead(at + 1, (result as OneRegisterInstruction).registerA)
    }
}

/** The types [method] constructs. */
internal fun constructedTypes(method: Method): Set<String> =
    method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .toSet()

/**
 * Whether [item] is a row item the extension can read: a full constructor over a title, two
 * addresses, icons and an id, and exactly one instance field of type CharSequence (the title)
 * and one of type long (the id), which are what MenuRows looks up.
 */
internal fun isReadableRowItem(item: ClassDef): Boolean {
    val instance = item.fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
    return fullConstructor(item) != null &&
        instance.count { it.type == "Ljava/lang/CharSequence;" } == 1 && instance.count { it.type == "J" } == 1
}
