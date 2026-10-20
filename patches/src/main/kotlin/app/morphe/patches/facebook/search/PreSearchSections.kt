/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.layout.theme.branchTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The search screen before anything is typed (#80), where Facebook can serve People you may know and
 * For you. Read from 583, 2026-10-20; the obfuscated names are for reviewers, the code never writes
 * one down.
 *
 * Its rows come from one paginated list, "null_state_results_paginating", read with its entry class
 * (arm64 LX/87d;->A00, entries LX/87e). The section naming itself "SearchTypeaheadPaginableSection"
 * (LX/Bne) draws each entry in its event method's item case (A2c): it reads the entry's node (A00, a
 * tree LX/7RO), then the node's module tree through a static (tree, Class, key, tag) reader
 * (LX/3gt;->A09 to LX/7RP), and draws that as a native template, or an empty row when there's none.
 * (The empty row is a Litho column with no children and no style, LX/2aC.) No field is named for a
 * section, but the node's "rendered_modules" string, which Facebook hands its own performance logger,
 * says what it holds: on the S22 (583, 2026-10-20) the For you section is the one entry whose node
 * lists TRENDING_QUERY_SUGGESTION there. The extension logs, with Debug logging on, each entry's type
 * names and module names, never its id or text, and answers the module to draw: null, Facebook's
 * empty row, while Hide For you in search is on and the entry is For you.
 */

/** The name the pre-search section gives itself. */
internal const val PRE_SEARCH_SECTION = "SearchTypeaheadPaginableSection"

/** The paginated list the section's entries come from. */
internal const val PRE_SEARCH_LIST = "null_state_results_paginating"

internal const val PRE_SEARCH_ENTRY =
    "$EXTENSION_PACKAGE/search/PreSearchSections;->entry(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

private const val PAGINABLE_LIST_READER = "getCachedPaginableTreeList"

/**
 * Where an entry is drawn: [read] is the `move-result-object` keeping the [module] tree read off the
 * [node], whose reader answers a [moduleType].
 */
internal data class PreSearchSite(val method: Method, val read: Int, val node: Int, val module: Int, val moduleType: String)

/**
 * The entry class of the pre-search list: the `const-class` loaded right before the
 * [PRE_SEARCH_LIST] name in a method handing both to [PAGINABLE_LIST_READER]. Null unless there's one.
 */
internal fun BytecodePatchContext.preSearchEntryType(): String? =
    classDefByStrings(PRE_SEARCH_LIST, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, PRE_SEARCH_LIST) }
        .mapNotNull(::listEntryType)
        .distinct()
        .singleOrNull()

private fun listEntryType(method: Method): String? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val types = code.indices.mapNotNull { index ->
        if (code[index].string() != PRE_SEARCH_LIST) return@mapNotNull null
        val type = (index - 1 downTo maxOf(0, index - 3)).firstOrNull { code[it].opcode == Opcode.CONST_CLASS }
            ?.let { ((code[it] as ReferenceInstruction).reference as TypeReference).type } ?: return@mapNotNull null
        val reads = code.subList(index + 1, minOf(code.size, index + 3)).any {
            (it as? ReferenceInstruction)?.reference.let { ref -> ref is MethodReference && ref.name == PAGINABLE_LIST_READER }
        }
        if (reads) type else null
    }
    return types.distinct().singleOrNull()
}

/** The classes outside the extension whose constructor names itself [PRE_SEARCH_SECTION]. */
internal fun BytecodePatchContext.preSearchSections(): List<ClassDef> =
    classDefByStrings(PRE_SEARCH_SECTION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .filter { section -> section.methods.any { it.name == "<init>" && holds(it, PRE_SEARCH_SECTION) } }

/**
 * Each place in [section]'s own (non-static) methods that reads an [entry]'s node with a no-argument
 * call, tests it for null, hands it first to a static (tree, Class, int, int) reader and keeps that
 * reader's answer: the module tree the entry is drawn from.
 */
internal fun preSearchSites(section: ClassDef, entry: String): List<PreSearchSite> =
    section.methods.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        code.indices.mapNotNull { index -> siteAt(method, code, index, entry) }
    }

private fun siteAt(method: Method, code: List<Instruction>, index: Int, entry: String): PreSearchSite? {
    val call = code[index]
    if (call.opcode != Opcode.INVOKE_VIRTUAL) return null
    val nodeRead = (call as ReferenceInstruction).reference as? MethodReference ?: return null
    if (nodeRead.definingClass != entry || nodeRead.parameterTypes.isNotEmpty() || !nodeRead.returnType.startsWith("L")) {
        return null
    }
    if (code.getOrNull(index + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val node = (code[index + 1] as OneRegisterInstruction).registerA
    if (code.getOrNull(index + 2)?.opcode != Opcode.IF_EQZ || (code[index + 2] as OneRegisterInstruction).registerA != node) {
        return null
    }
    val reader = (index + 3 until minOf(code.size, index + 9)).firstOrNull { at ->
        val instruction = code[at]
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode == Opcode.INVOKE_STATIC && ref != null &&
            ref.parameterTypes.map(CharSequence::toString).drop(1) == listOf("Ljava/lang/Class;", "I", "I") &&
            (instruction as FiveRegisterInstruction).registerC == node
    } ?: return null
    // Nothing between the null test and the reader may write the node's register.
    if (code.subList(index + 3, reader).any { (it as? OneRegisterInstruction)?.registerA == node }) return null
    if (code.getOrNull(reader + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val module = (code[reader + 1] as OneRegisterInstruction).registerA
    if (module == node) return null
    val moduleType = ((code[reader] as ReferenceInstruction).reference as MethodReference).returnType
    if (!moduleType.startsWith("L")) return null
    return PreSearchSite(method, reader + 1, node, module, moduleType)
}

/**
 * Right after the module tree is kept, hand the node and the module to the extension and keep its
 * answer as the module, cast back to the reader's type, so Facebook's own null test draws the empty
 * row for a null. Refuses unless the section, its entry class and one site are found, and nothing
 * jumps past the call.
 */
internal fun BytecodePatchContext.hookPreSearchSections() {
    val entry = preSearchEntryType() ?: throw PatchException("no single entry class for \"$PRE_SEARCH_LIST\"")
    val sections = preSearchSections()
    val section = sections.singleOrNull()
        ?: throw PatchException("expected one section naming itself \"$PRE_SEARCH_SECTION\", found ${sections.size}")
    val sites = preSearchSites(section, entry)
    val site = sites.singleOrNull()
        ?: throw PatchException("${section.type} reads an entry's module in ${sites.size} places, expected one")
    if (site.node > 15 || site.module > 15) {
        throw PatchException("${section.type}->${site.method.name} keeps an entry past v15")
    }
    // A label on the module's null test stays there, so a jump to it would skip the call.
    if (site.read + 1 in branchTargets(site.method.implementation!!.instructions.toList())) {
        throw PatchException("${section.type}->${site.method.name} jumps to the module's null test")
    }
    mutableClassDefBy(section.type).findMutableMethodOf(site.method).addInstructions(
        site.read + 1,
        """
            invoke-static { v${site.node}, v${site.module} }, $PRE_SEARCH_ENTRY
            move-result-object v${site.module}
            check-cast v${site.module}, ${site.moduleType}
        """,
    )
}

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun holds(method: Method, string: String) =
    method.implementation?.instructions?.any { it.string() == string } == true
