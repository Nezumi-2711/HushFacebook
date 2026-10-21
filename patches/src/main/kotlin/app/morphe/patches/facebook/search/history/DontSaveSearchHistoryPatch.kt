/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.search.history

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Don't save search history"

/** The name of the mutation that adds a search to your history, held by the method that sends it. */
internal const val ADD_RECENT_SEARCH = "FB4ASearchTypeaheadAddRecentSearch"

private const val STRING = "Ljava/lang/String;"

internal const val SEARCH_HISTORY = "$EXTENSION_PACKAGE/search/SearchHistory;"
internal const val SKIP_SAVE = "$SEARCH_HISTORY->skipSave(Z)Z"

/**
 * Don't save search history (#196).
 *
 * A search goes into your history through one method, the one holding [ADD_RECENT_SEARCH] (583
 * arm64 `LX/8mp;->A03(GraphSearchQuery, 7uR, 8mr, String, String, String, Z, Z)String`). It answers
 * with a String and takes two booleans last, and the first of them is Facebook's own "don't save
 * this one": on the new path it skips the mutation and answers null, and on the older path, which
 * also keeps the search in the app's local list, it skips that list's logging and then the mutation
 * the same way. The search bar's typeahead and the keyword search both call it, and some of their
 * callers already pass true. First thing in it, the extension is handed that boolean and its answer
 * goes back into the same register, so with the switch on every search is one Facebook doesn't save.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val dontSaveSearchHistoryPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Don't save search history",
    description = "Stops Facebook from adding what you search for to your search history, so there's nothing " +
        "to clear by hand. Searches already there stay until you delete them. Starts off. Turn it on in " +
        "Hushfacebook settings > Search.",
) {
    category("Privacy")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        requireStatusMethod("searchHistory")
        val holders = classDefByStrings(ADD_RECENT_SEARCH, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val save = recentSearchSave(holders)
        val skip = skipRegister(save)

        mutableClassDefBy(save.definingClass).methods.single { it.descriptor() == save.descriptor() }.askFirst(skip)
        enableStatus("searchHistory")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The method among [holders] that adds a search to your history: the one holding
 * [ADD_RECENT_SEARCH] that answers with a String and takes two booleans last.
 */
internal fun recentSearchSave(holders: List<ClassDef>): Method {
    val found = holders.flatMap { methodsHolding(it, ADD_RECENT_SEARCH) }
        .filter { method ->
            method.returnType == STRING && method.parameterTypes.size >= 2 &&
                method.parameterTypes.takeLast(2).map(CharSequence::toString) == listOf("Z", "Z")
        }
        .distinctBy { it.descriptor() }
    return found.singleOrNull()
        ?: refuse(
            "expected one method holding \"$ADD_RECENT_SEARCH\" that answers with a String and takes two booleans " +
                "last, found ${found.size}: ${found.joinToString { it.descriptor() }}",
        )
}

private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

/**
 * The register [save]'s first of its two last booleans arrives in, Facebook's "don't save this
 * one". The parameters fill the last registers, after `this`, so the first one sits as far from
 * the end as the parameters are wide. Refused unless [save] tests it with `if-nez` and never writes
 * it, so every test sees what the caller passed.
 */
internal fun skipRegister(save: Method): Int {
    val types = save.parameterTypes.map(CharSequence::toString)
    val code = save.implementation ?: refuse("${save.descriptor()} has no code")
    val register = code.registerCount - types.sumOf(::width) + types.dropLast(2).sumOf(::width)
    val instructions = code.instructions.toList()
    if (instructions.none { it.opcode == Opcode.IF_NEZ && (it as OneRegisterInstruction).registerA == register }) {
        refuse("${save.descriptor()} never tests v$register, its first of two last booleans, with if-nez")
    }
    val written = instructions.any { instruction ->
        val target = (instruction as? OneRegisterInstruction)?.registerA ?: return@any false
        instruction.opcode.setsRegister() &&
            (target == register || (instruction.opcode.setsWideRegister() && target + 1 == register))
    }
    if (written) refuse("${save.descriptor()} writes v$register, so a test there may not see what the caller passed")
    return register
}

/**
 * First thing in the method that saves a search: hand the extension Facebook's "don't save this
 * one" in [register] and keep its answer there. The call takes it as a range, so any register
 * number works.
 */
internal fun MutableMethod.askFirst(register: Int) {
    addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $SKIP_SAVE
            move-result v$register
        """,
    )
}
