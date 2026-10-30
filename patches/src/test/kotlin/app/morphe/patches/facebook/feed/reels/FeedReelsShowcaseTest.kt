/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.reels

import app.morphe.Fixtures
import app.morphe.RepoFiles
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.treeFieldKey
import app.morphe.patches.facebook.shared.FEED_UNIT_EDGE
import app.morphe.patches.facebook.shared.admittedAsFeedFunnel
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reels anchors beyond the category, found in every Facebook build the bundle declares the
 * way the patch finds them: the showcase unit's one story type accessor, with the evidence it's held
 * to, and the one pre-EOF injector, which with `addNewEdgeToCollection` (or, on 583, the body it
 * hands everything to) is all the feed's tail adder is called from. The fixture half reads the bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without
 * it; the rest reads the sources and builds its own methods.
 */
class FeedReelsShowcaseTest {
    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version ->
            Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }
        }
    }

    @Test
    fun `the story type key is the one Facebook's accessor loads`() {
        // Read off both builds' accessor with the dex probe, 2026-09-25.
        assertEquals(0x300bc816, treeFieldKey(SHOWCASE_STORY_TYPE_FIELD))
    }

    @Test
    fun `the patch requires the story types the extension hides`() {
        val source = File(RepoFiles.root, "extensions/facebook/src/main/java/app/morphe/extension/facebook/feed/ShowcaseType.java")
        val array = Regex("""REELS_TYPES\s*=\s*\{([^}]*)\}""").find(source.readText())
            ?: throw AssertionError("ShowcaseType.java has no REELS_TYPES array")
        val hidden = Regex(""""([A-Z_]+)"""").findAll(array.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(REELS_SHOWCASE_TYPES, hidden)
    }

    /** The hook and the stub the patch writes calls to are the extension's, by name and shape. */
    @Test
    fun `the patch and the extension agree on the hook and the stub`() {
        val feed = "extensions/facebook/src/main/java/app/morphe/extension/facebook/feed"
        val filter = File(RepoFiles.root, "$feed/FeedFilter.java").readText()
        assertTrue("the extension has no public static boolean hidePreEofReels()",
            Regex("""public static boolean hidePreEofReels\(\)""").containsMatchIn(filter))
        assertTrue(HIDE_PRE_EOF_REELS.endsWith("/feed/FeedFilter;->hidePreEofReels()Z"))
        val showcase = File(RepoFiles.root, "$feed/ShowcaseType.java").readText()
        assertTrue("the extension has no public static Object $STORY_TYPE_STUB(Object)",
            Regex("""public static Object $STORY_TYPE_STUB\(Object \w+\)""").containsMatchIn(showcase))
        assertTrue(SHOWCASE_TYPE.endsWith("/feed/ShowcaseType;"))
        assertTrue("the extension's showcase type name differs",
            showcase.contains("static final String UNIT_TYPE = \"$SHOWCASE_FEED_UNIT\";"))
    }

    @Test
    fun `every declared build has one showcase story type accessor, the one its renderer reads`() {
        val checked = mutableMapOf<String, String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val units = unitClassesNamed(FixtureDex.classesHolding(bundle, SHOWCASE_FEED_UNIT), SHOWCASE_FEED_UNIT)
                assertEquals("${bundle.name}: ${units.map { it.type }}", 1, units.size)
                val unit = units.single()
                assertTrue("${bundle.name}: ${unit.type} isn't public", AccessFlags.PUBLIC.isSet(unit.accessFlags))

                val accessors = showcaseStoryTypeAccessors(unit)
                assertEquals("${bundle.name}: ${accessors.map { it.name }}", 1, accessors.size)
                val accessor = accessors.single()

                // The control: the unit has another public enum accessor of the same shape, which
                // asks getCachedEnum under another key and isn't picked.
                val siblings = unit.methods.filter {
                    it.name != accessor.name && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") &&
                        !AccessFlags.STATIC.isSet(it.accessFlags)
                }
                siblings.forEach { assertFalse("${bundle.name}: ${it.name} passed too", isShowcaseStoryTypeAccessor(it)) }
                assertTrue("${bundle.name}: ${unit.type} has no other enum accessor to hold this against",
                    siblings.any { sibling ->
                        sibling.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getCachedEnum"
                        } == true
                    })

                val storyType = FixtureDex.classes(bundle, setOf(accessor.returnType))[accessor.returnType]
                    ?: throw AssertionError("${bundle.name} has no ${accessor.returnType}")
                val names = categoryNames(storyType)
                assertEquals("${bundle.name}: reels story types missing", emptyList<String>(),
                    REELS_SHOWCASE_TYPES.filterNot(names::contains))
                // The control: the types that aren't reels are read the same way, and stay out.
                assertTrue("${bundle.name}: $names", names.containsAll(listOf("SHOWCASE_MARKETPLACE", "SHOWCASE_EVENTS",
                    "SHOWCASE_FB_HASHTAG_DEEP_DIVE")))

                val renderers = FixtureDex.classesHolding(bundle, SHOWCASE_NOT_HANDLED)
                    .flatMap { methodsHolding(it, SHOWCASE_NOT_HANDLED) }
                // The renderer reads the type itself, or (585) takes the enum from its caller, which does.
                val takers = renderers.filter { renderer -> renderer.parameterTypes.any { it.toString() == accessor.returnType } }
                val handers = if (takers.isEmpty()) emptyList() else FixtureDex.methodsWhere(bundle,
                    { dex -> dex.methodSection.any { call -> takers.any { isCallTo(call, it) } } },
                ) { method -> takers.any { callsMethod(method, it) } }
                assertTrue("${bundle.name}: no renderer reads the type through ${accessor.name}()",
                    renderers.any { renderer -> rendersByStoryType(renderer, accessor, handers.filter { callsMethod(it, renderer) }) })

                val validators = FixtureDex.classesHolding(bundle, FB_SHORTS_IFU_VALIDATOR)
                    .flatMap { methodsHolding(it, FB_SHORTS_IFU_VALIDATOR) }
                assertTrue("${bundle.name}: no getName() answers $FB_SHORTS_IFU_VALIDATOR",
                    validators.any { it.name == "getName" && it.returnType == "Ljava/lang/String;" })
                checked[version] = "${unit.type}->${accessor.name}()${accessor.returnType}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", declaredBundles().keys, checked.keys)
    }

    @Test
    fun `every declared build has one pre-EOF injector, and the tail adder has no third caller`() {
        val checked = mutableMapOf<String, String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val adders = FixtureDex.classesHolding(bundle, ADD_ELEMENT_AT_TAIL).flatMap(::tailAdders)
                assertEquals("${bundle.name}: ${adders.map { it.toString() }}", 1, adders.size)
                val adder = adders.single()

                val found = FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
                    dex.stringSection.any { it.startsWith(PRE_EOF_INJECTOR_PREFIX) } ||
                        dex.methodSection.any { isCallTo(it, adder) }
                }) { isPreEofInjector(it) || callsMethod(it, adder) }
                val injectors = found.filter(::isPreEofInjector)
                assertEquals("${bundle.name}: ${injectors.map { it.toString() }}", 1, injectors.size)
                val injector = injectors.single()
                assertTrue("${bundle.name}: the injector holds no \"$PRE_EOF_ADAPTER\"", holdsString(injector, PRE_EOF_ADAPTER))
                assertTrue("${bundle.name}: the injector doesn't call the tail adder", callsMethod(injector, adder))
                val locals = injector.implementation!!.registerCount - 1
                assertTrue("${bundle.name}: the injector has no local for the hook", locals >= 1)

                val callers = found.filter { callsMethod(it, adder) }
                // 583's funnel hands its edge to a method of its own class, and so does 585's (a private
                // instance method the batch add calls as well); that one calls the adder.
                val funnel = FixtureDex.methodsWhere(bundle,
                    { dex -> dex.stringSection.any { it == ADD_NEW_EDGE_TO_COLLECTION } },
                ) { admittedAsFeedFunnel(it) }.single()
                val owner = FixtureDex.classes(bundle, setOf(funnel.definingClass)).getValue(funnel.definingClass)
                val bodies = funnelHandOffs(funnel, owner).map(::signatureOf)
                val isBody = { method: Method -> signatureOf(method) in bodies }
                assertTrue("${bundle.name}: the funnel wraps ${bodies.size} bodies, expected the one the adder is called from",
                    if (bodies.isEmpty()) callers.any { it.name == ADD_NEW_EDGE_TO_COLLECTION } else bodies.size == 1 && callers.any(isBody))
                assertNull("${bundle.name}", tailCallerProblem(callers, injector, isBody))
                assertEquals("${bundle.name}: ${callers.map { it.name }}", listOf(injector.name),
                    callers.filterNot { it.name == ADD_NEW_EDGE_TO_COLLECTION || isBody(it) }.map { it.name })

                // The negative: a third method adding at the tail is refused, and so is a set
                // without the injector.
                val third = caller("Lfixture/Elsewhere;", "addAtTail", adder)
                assertTrue(bundle.name, tailCallerProblem(callers + third, injector, isBody)!!.contains("has 3 callers"))
                assertNotNull(bundle.name, tailCallerProblem(
                    callers.filter { it.name == ADD_NEW_EDGE_TO_COLLECTION || isBody(it) } + third, injector, isBody))
                checked[version] = "${injector.definingClass} calls ${adder.definingClass}->${adder.name}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", declaredBundles().keys, checked.keys)
    }

    /** A method of [owner] named [name] that calls [target] on an edge and returns. */
    private fun caller(owner: String, name: String, target: Method): Method {
        val call = ImmutableMethodReference(target.definingClass, target.name, listOf(FEED_UNIT_EDGE), "Z")
        return ImmutableMethod(
            owner, name, listOf(ImmutableMethodParameter(FEED_UNIT_EDGE, null, null)), "V",
            AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(
                2,
                listOf(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, call), ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null, null,
            ),
        )
    }

    @Test
    fun `the checks refuse what the patch refuses`() {
        val adder = ImmutableMethod("Lfixture/Collection;", "tail", listOf(ImmutableMethodParameter(FEED_UNIT_EDGE, null, null)),
            "Z", AccessFlags.PUBLIC.value, null, null, null)
        val funnel = caller("Lfixture/Manager;", ADD_NEW_EDGE_TO_COLLECTION, adder)
        val injector = caller("Lfixture/PreEof;", "${PRE_EOF_INJECTOR_PREFIX}fixture", adder)
        assertNull(tailCallerProblem(listOf(funnel, injector), injector))
        assertNotNull("the funnel alone", tailCallerProblem(listOf(funnel), injector))
        assertNotNull("the injector twice", tailCallerProblem(listOf(injector, injector), injector))
        assertNotNull("the injector and another method, no funnel",
            tailCallerProblem(listOf(injector, caller("Lfixture/Other;", "add", adder)), injector))
        assertTrue(tailCallerProblem(listOf(funnel, injector, caller("Lfixture/Other;", "add", adder)), injector)!!
            .contains("Lfixture/Other;->add"))
        assertTrue(callsMethod(funnel, adder))
        assertFalse(callsMethod(funnel, injector))

        // The body the feed guard went on stands in for the funnel, and only that one.
        val body = caller("Lfixture/Manager;", "A06", adder)
        val guarded = { method: Method -> signatureOf(method) == signatureOf(body) }
        assertNull(tailCallerProblem(listOf(body, injector), injector, guarded))
        assertNotNull("another method of the class", tailCallerProblem(
            listOf(caller("Lfixture/Manager;", "A07", adder), injector), injector, guarded))
        assertTrue("the body and the funnel both", tailCallerProblem(listOf(body, funnel, injector), injector, guarded)!!
            .contains("has 3 callers"))

        // A unit class with two story type accessors is ambiguous, and one with a getTypeName()
        // that answers another literal isn't the showcase class. The key read any other way, or
        // another key read the same way, isn't the accessor.
        fun accessor(name: String, key: Int = treeFieldKey(SHOWCASE_STORY_TYPE_FIELD), reader: String = "getCachedEnum",
                     flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value) =
            ImmutableMethod("Lfixture/Unit;", name, emptyList(), "Lfixture/Type;", flags, null, null,
                ImmutableMethodImplementation(4, listOf(
                    ImmutableInstruction31i(Opcode.CONST, 0, key),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 3, 0, 2, 1, 0, ImmutableMethodReference(
                        "Lcom/facebook/graphql/modelutil/BaseModelWithTree;", reader,
                        listOf("I", "Ljava/lang/Class;", "Ljava/lang/Enum;"), "Ljava/lang/Enum;")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null))
        val two = ImmutableClassDef("Lfixture/Unit;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            null, listOf(accessor("first"), accessor("second"), accessor("third", reader = "getCachedModel"),
                accessor("fourth", key = treeFieldKey("showcase_header_title")),
                accessor("fifth", flags = AccessFlags.PRIVATE.value)))
        assertEquals(listOf("first", "second"), showcaseStoryTypeAccessors(two).map { it.name }.sorted())
        assertEquals(emptyList<Any>(), unitClassesNamed(listOf(two), SHOWCASE_FEED_UNIT))

        // The shared unit class answers other type names too: only the literal picks it.
        fun typed(type: String, answers: String) = ImmutableClassDef(type, AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod(type, "getTypeName", emptyList(),
                "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(1, listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(answers)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), null, null))))
        val showcase = typed("Lfixture/Showcase;", SHOWCASE_FEED_UNIT)
        assertEquals(listOf(showcase.type), unitClassesNamed(listOf(typed("Lfixture/Tray;", "StoriesTrayFeedUnit"), showcase),
            SHOWCASE_FEED_UNIT).map { it.type })
    }

    private val manager = "Lfixture/Manager;"

    private fun invoke(opcode: Opcode, owner: String, name: String, params: List<String>, returns: String): Instruction =
        ImmutableInstruction35c(opcode, 2, 0, 1, 0, 0, 0, ImmutableMethodReference(owner, name, params, returns))

    private fun managerMethod(name: String, params: List<String>, returns: String, code: List<Instruction>): ImmutableMethod =
        ImmutableMethod(manager, name, params.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PRIVATE.value, null, null, ImmutableMethodImplementation(4, code, null, null))

    @Test
    fun `a funnel that only wraps a method of its class hands the adder's caller over to that method`() {
        val body = managerMethod("A0D", listOf(FEED_UNIT_EDGE), "Z", listOf(ImmutableInstruction11x(Opcode.RETURN, 0)))
        // 585: a null check, then a private instance method of the class, called with the edge.
        val wrapper = managerMethod(ADD_NEW_EDGE_TO_COLLECTION, listOf(FEED_UNIT_EDGE), "Z", listOf(
            invoke(Opcode.INVOKE_DIRECT, manager, "A0D", listOf(FEED_UNIT_EDGE), "Z"),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)))
        val owner = ImmutableClassDef(manager, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(wrapper, body, managerMethod("A07", emptyList(), "Ljava/lang/Long;", listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))))
        assertEquals(listOf("A0D"), funnelHandOffs(wrapper, owner).map { it.name })

        // 583: the same through a static call.
        val staticWrapper = managerMethod(ADD_NEW_EDGE_TO_COLLECTION, listOf(FEED_UNIT_EDGE), "Z", listOf(
            invoke(Opcode.INVOKE_STATIC, manager, "A0D", listOf(FEED_UNIT_EDGE), "Z"),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)))
        assertEquals(listOf("A0D"), funnelHandOffs(staticWrapper, owner).map { it.name })

        // A funnel that does the work itself, one that calls itself, one that calls another class and
        // one that branches before it hands anything over hand nothing over.
        assertEquals(emptyList<String>(), funnelHandOffs(body, owner).map { it.name })
        val recursive = managerMethod(ADD_NEW_EDGE_TO_COLLECTION, listOf(FEED_UNIT_EDGE), "Z", listOf(
            invoke(Opcode.INVOKE_DIRECT, manager, ADD_NEW_EDGE_TO_COLLECTION, listOf(FEED_UNIT_EDGE), "Z"),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)))
        assertEquals(emptyList<String>(), funnelHandOffs(recursive, owner).map { it.name })
        val elsewhere = managerMethod(ADD_NEW_EDGE_TO_COLLECTION, listOf(FEED_UNIT_EDGE), "Z", listOf(
            invoke(Opcode.INVOKE_STATIC, "Lfixture/Other;", "A0D", listOf(FEED_UNIT_EDGE), "Z"),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)))
        assertEquals(emptyList<String>(), funnelHandOffs(elsewhere, owner).map { it.name })
        val branching = managerMethod(ADD_NEW_EDGE_TO_COLLECTION, listOf(FEED_UNIT_EDGE), "Z", listOf(
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 4),
            invoke(Opcode.INVOKE_DIRECT, manager, "A0D", listOf(FEED_UNIT_EDGE), "Z"),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), ImmutableInstruction11x(Opcode.RETURN, 0)))
        assertEquals(emptyList<String>(), funnelHandOffs(branching, owner).map { it.name })

        // The body stands in for the funnel in the caller check, and a second method of the class doesn't.
        val adder = ImmutableMethod("Lfixture/Collection;", "tail", listOf(ImmutableMethodParameter(FEED_UNIT_EDGE, null, null)),
            "Z", AccessFlags.PUBLIC.value, null, null, null)
        val injector = caller("Lfixture/PreEof;", "${PRE_EOF_INJECTOR_PREFIX}fixture", adder)
        val bodies = funnelHandOffs(wrapper, owner).map(::signatureOf)
        val isBody = { method: Method -> signatureOf(method) in bodies }
        assertNull(tailCallerProblem(listOf(body, injector), injector, isBody))
        val sibling = managerMethod("A0E", listOf(FEED_UNIT_EDGE), "Z", listOf(ImmutableInstruction11x(Opcode.RETURN, 0)))
        assertNotNull(tailCallerProblem(listOf(sibling, injector), injector, isBody))
        assertNotNull("the wrapper and its body both", tailCallerProblem(listOf(wrapper, body, injector), injector, isBody))
    }

    @Test
    fun `a renderer that takes the story type is held to a caller reading it through the accessor`() {
        val accessor = ImmutableMethod("Lfixture/Unit;", "A01", emptyList(), "Lfixture/Type;", AccessFlags.PUBLIC.value,
            null, null, null)
        fun method(name: String, params: List<String>, code: List<Instruction>) =
            ImmutableMethod("Lfixture/Renderer;", name, params.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, code + ImmutableInstruction10x(Opcode.RETURN_VOID), null, null))
        val reads = invoke(Opcode.INVOKE_VIRTUAL, "Lfixture/Unit;", "A01", emptyList(), "Lfixture/Type;")
        val takes = method("render", listOf("Lfixture/Type;"), emptyList())
        val readsItself = method("renderAlone", emptyList(), listOf(reads))
        val readsOther = method("readOther", emptyList(), listOf(invoke(Opcode.INVOKE_VIRTUAL, "Lfixture/Unit;", "A02", emptyList(), "Lfixture/Type;")))
        val handsOver = method("handOver", emptyList(), listOf(reads))

        assertTrue("reads the type itself", rendersByStoryType(readsItself, accessor, emptyList()))
        assertTrue("takes the type, caller reads it", rendersByStoryType(takes, accessor, listOf(handsOver)))
        assertFalse("takes the type, no caller", rendersByStoryType(takes, accessor, emptyList()))
        assertFalse("takes the type, caller reads another accessor", rendersByStoryType(takes, accessor, listOf(readsOther)))
        val takesOther = method("renderOther", listOf("Lfixture/Other;"), emptyList())
        assertFalse("takes another type, caller reads the accessor", rendersByStoryType(takesOther, accessor, listOf(handsOver)))
    }
}
