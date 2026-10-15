package borg.trikeshed.lcnc

import borg.trikeshed.collections.associative.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.yaml.YamlParser
import kotlin.test.*

class CamelRoutePlanTest {
    private val mounted = listOf(CamelLinkage.SPINE)

    private fun route(vararg steps: Any?): Map<String, Any?> =
        linkedMapOf("from" to "direct:in", "steps" to steps.toList())

    private fun branch(vararg steps: Any?): Map<String, Any?> =
        linkedMapOf("bodyEquals" to "match", "steps" to steps.toList())

    private fun choice(branches: List<*>, otherwise: List<*>): Map<String, Any?> =
        mapOf("choice" to linkedMapOf("when" to branches, "otherwise" to otherwise))

    private fun nested(depth: Int, leaf: Any? = mapOf("stop" to true), otherwise: Boolean = false): Any? =
        (0 until depth).fold(leaf) { step, _ ->
            if (otherwise) choice(emptyList<Any?>(), listOf(step))
            else choice(listOf(branch(step)), emptyList<Any?>())
        }

    private fun refused(value: Any?): IllegalArgumentException =
        assertFailsWith<IllegalArgumentException> { CamelRoutePlan.parse(value) }

    @Test
    fun confixProjectionPreservesEveryStepAndCanonicalFieldOrder() {
        val value = reify("""
            {"steps":[
              {"setBody":"hi\n\"水\""},
              {"setHeader":{"value":"v", "name":"x"}},
              {"choice":{"otherwise":[{"stop":true}], "when":[
                {"steps":[{"to":"mock:first"}], "bodyEquals":"hi"},
                {"bodyEquals":"other", "steps":[{"setBody":""},{"to":"log:second"}]}
              ]}},
              {"to":"file:out?fileName=result.txt"}
            ], "from":"direct:in"}
        """.trimIndent())
        val plan = CamelRoutePlan.parse(value)
        assertEquals("direct:in", plan.from)
        assertEquals(4, plan.steps.size)
        assertEquals(CamelRouteStep.SetBody("hi\n\"水\""), plan.steps[0])
        assertEquals(CamelRouteStep.SetHeader("x", "v"), plan.steps[1])
        val choice = assertIs<CamelRouteStep.Choice>(plan.steps[2])
        assertEquals(2, choice.branches.size)
        assertEquals("hi", choice.branches[0].bodyEquals)
        assertEquals(CamelRouteStep.To("mock:first"), choice.branches[0].steps[0])
        assertSame(CamelRouteStep.Stop, choice.otherwise[0])

        val canonical = plan.toValue()
        assertEquals(value, canonical)
        assertEquals(listOf("from", "steps"), canonical.keys.toList())
        val steps = canonical["steps"] as List<*>
        val header = (steps[1] as Map<*, *>)["setHeader"] as Map<*, *>
        assertEquals(listOf("name", "value"), header.keys.toList())
        val branches = (steps[2] as Map<*, *>)["choice"] as Map<*, *>
        assertEquals(listOf("when", "otherwise"), branches.keys.toList())
        assertEquals(listOf("bodyEquals", "steps"), ((branches["when"] as List<*>)[0] as Map<*, *>).keys.toList())
        assertEquals(canonical, CamelRoutePlan.parse(reify(jsonOf(canonical))).toValue())
        plan.validate(CamelLinkage.Reach.LOCAL, mounted)
    }

    @Test
    fun reifiedConfixYamlAndCborUseTheSameModel() {
        val expected = route(mapOf("setBody" to "literal"), mapOf("to" to "mock:out"), mapOf("stop" to true))
        val yaml = YamlParser.reify("""
            from: direct:in
            steps:
              - setBody: literal
              - to: mock:out
              - stop: true
        """.trimIndent())
        assertEquals(expected, CamelRoutePlan.parse(yaml).toValue())
        val cbor = Cbor.decode(Cbor.encode(expected.toItem())).toAny()
        assertEquals(expected, CamelRoutePlan.parse(cbor).toValue())
    }

    @Test
    fun reifiedArraysAreSnapshottedBeforeReturning() {
        val target = mutableMapOf<String, Any?>("to" to "mock:out")
        val children = arrayOf<Any?>(target)
        val branch = mutableMapOf<String, Any?>("bodyEquals" to "yes", "steps" to children)
        val branches = arrayOf<Any?>(branch)
        val fallback = mutableListOf<Any?>(mapOf("stop" to true))
        val steps = arrayOf<Any?>(mapOf("choice" to mapOf("when" to branches, "otherwise" to fallback)))
        val source = mutableMapOf<String, Any?>("from" to "direct:in", "steps" to steps)
        val plan = CamelRoutePlan.parse(source)
        val before = plan.toValue()
        source["from"] = "smtp:host"
        target["to"] = "smtp:host"
        children[0] = null
        branch["bodyEquals"] = "changed"
        branches[0] = null
        fallback.clear()
        steps[0] = null
        assertEquals(before, plan.toValue())
        plan.validate(CamelLinkage.Reach.LOCAL, mounted)
    }

    @Test
    fun rejectsEncodedDocumentsHostObjectsAndNonArrayContainers() {
        val host = object { override fun toString(): String = error("must not coerce host objects") }
        for (value in listOf(null, true, 3, host, "{}", byteArrayOf(), emptyList<Any?>())) refused(value)
        for (value in listOf(null, host, mapOf("to" to "mock:out"), setOf(mapOf("stop" to true)), sequenceOf(true))) {
            refused(mapOf("from" to "direct:in", "steps" to value))
        }
        refused(mapOf("from" to host, "steps" to emptyList<Any?>()))
        refused(route(mapOf("to" to host)))
        refused(route(mapOf("setBody" to host)))
        refused(route(mapOf("setHeader" to mapOf("name" to "x", "value" to host))))
        refused(route(choice(listOf(mapOf("bodyEquals" to host, "steps" to emptyList<Any?>())), emptyList<Any?>())))
    }

    @Test
    fun rejectsMissingUnknownAndMalformedKeysAtEveryObjectLevel() {
        val hostKey = object { override fun toString(): String = error("must not stringify keys") }
        val root = route(mapOf("stop" to true))
        val header = mapOf("name" to "x", "value" to "y")
        val branchFields = branch(mapOf("stop" to true))
        val choiceFields = mapOf("when" to listOf(branchFields), "otherwise" to emptyList<Any?>())
        fun malformed(map: Map<String, Any?>): List<Map<*, *>> =
            map.keys.map { map - it } +
                listOf(map + ("extra" to true), map + ("" to true)) +
                listOf(null, 7, hostKey).map { key -> (map - map.keys.first()) + (key to true) }

        for (value in malformed(root)) refused(value)
        for (value in malformed(header)) refused(route(mapOf("setHeader" to value)))
        for (value in malformed(choiceFields)) refused(route(mapOf("choice" to value)))
        for (value in malformed(branchFields)) refused(route(choice(listOf(value), emptyList<Any?>())))
        for (key in listOf(null, 7, hostKey)) refused(route(mapOf(key to true)))
        for (key in root.keys) refused(root + (key to null))
        for (key in header.keys) refused(route(mapOf("setHeader" to (header + (key to null)))))
        for (key in choiceFields.keys) refused(route(mapOf("choice" to (choiceFields + (key to null)))))
        for (key in branchFields.keys) refused(route(choice(listOf(branchFields + (key to null)), emptyList<Any?>())))
    }

    @Test
    fun rejectsDuplicateFieldsRetainedByConfixReification() {
        for (json in listOf(
            """{"from":"direct:in","from":"direct:other","steps":[]}""",
            """{"from":"direct:in","steps":[{"to":"mock:a","to":"mock:b"}]}""",
            """{"from":"direct:in","steps":[{"setHeader":{"name":"x","name":"y"}}]}""",
            """{"from":"direct:in","steps":[{"choice":{"when":[],"when":[]}}]}""",
            """{"from":"direct:in","steps":[{"choice":{"when":[{"bodyEquals":"x","bodyEquals":"y"}],"otherwise":[]}}]}""",
        )) refused(reify(json))
    }

    @Test
    fun rejectsAmbiguousAndOpenEndedStepForms() {
        for (value in listOf(null, "stop", emptyMap<String, Any?>(), mapOf("to" to "mock:out", "stop" to true))) {
            refused(route(value))
        }
        for (key in listOf("toD", "recipientList", "routingSlip", "process", "bean", "simple", "script", "filter")) {
            refused(route(mapOf(key to "arbitrary")))
        }
        for (value in listOf(null, false, 1, "true", emptyMap<String, Any?>())) refused(route(mapOf("stop" to value)))
        for (value in listOf(null, false, 1, listOf("text"), mapOf("simple" to "body"))) {
            refused(route(mapOf("setBody" to value)))
            refused(route(mapOf("to" to value)))
            refused(route(mapOf("setHeader" to mapOf("name" to "x", "value" to value))))
        }
    }

    @Test
    fun rejectsBlankEndpointsAndHeaderNamesButPreservesLiteralText() {
        for (blank in listOf("", " ", "\t\n")) {
            refused(mapOf("from" to blank, "steps" to emptyList<Any?>()))
            refused(route(mapOf("to" to blank)))
            refused(route(mapOf("setHeader" to mapOf("name" to blank, "value" to "v"))))
        }
        val value = route(
            mapOf("setBody" to "\${body} {{literal}}"),
            mapOf("setHeader" to mapOf("name" to " X ", "value" to "")),
            choice(listOf(mapOf("bodyEquals" to "", "steps" to emptyList<Any?>())), emptyList<Any?>()),
        )
        assertEquals(value, CamelRoutePlan.parse(value).toValue())
        assertEquals(route(), CamelRoutePlan.parse(route()).toValue())
    }

    @Test
    fun rejectsEndpointInterpolationInSourceAndNestedDestinations() {
        for (uri in listOf("{{endpoint}}", "direct:{{name}}", "\${header.destination}", "direct:\$simple{header.name}",
            "file:out?fileName=\${header.name}", "direct:{{name:default}}")) {
            refused(mapOf("from" to uri, "steps" to emptyList<Any?>()))
            refused(route(nested(3, mapOf("to" to uri))))
            refused(route(nested(3, mapOf("to" to uri), otherwise = true)))
        }
    }

    @Test
    fun linkageRemainsTheStaticEndpointPolicy() {
        for (reach in CamelLinkage.Reach.entries) {
            for (modules in listOf(emptyList(), mounted, listOf("camel", "camel-mail", "camel-jdbc"))) {
                for (uri in CamelLinkage.known.map { "${it.scheme}:target" } + listOf("unknown:target", "no-scheme")) {
                    val plan = CamelRoutePlan.parse(route(mapOf("to" to uri)))
                    val refusal = CamelLinkage.refusal(plan.from, reach, modules) ?: CamelLinkage.refusal(uri, reach, modules)
                    if (refusal == null) plan.validate(reach, modules)
                    else assertEquals(refusal, assertFailsWith<IllegalArgumentException> { plan.validate(reach, modules) }.message)
                }
            }
        }
        for (uri in listOf("direct:in", "log:orders?level=INFO", "mock:out", "file:out?fileName=result.txt")) {
            CamelRoutePlan(uri, s_[CamelRouteStep.To(uri)]).validate(CamelLinkage.Reach.LOCAL, mounted)
        }
    }

    @Test
    fun linkageTraversesEveryBranchOtherwiseAndStepsAfterStop() {
        val network = mapOf("to" to "smtp:host")
        val plans = listOf(
            mapOf("from" to "smtp:host", "steps" to emptyList<Any?>()),
            route(mapOf("stop" to true), network),
            route(choice(listOf(branch(mapOf("to" to "mock:ok")), branch(nested(2, network))), emptyList<Any?>())),
            route(nested(3, network, otherwise = true)),
        )
        for (value in plans) {
            val plan = CamelRoutePlan.parse(value)
            val departments = listOf("camel", "camel-mail")
            var effects = 0
            val failure = assertFailsWith<IllegalArgumentException> {
                plan.validate(CamelLinkage.Reach.LOCAL, departments)
                effects++
            }
            assertEquals(0, effects)
            assertEquals(CamelLinkage.refusal("smtp:host", CamelLinkage.Reach.LOCAL, departments), failure.message)
            assertFailsWith<IllegalArgumentException> { plan.validate(CamelLinkage.Reach.DEPARTMENT, mounted) }
            plan.validate(CamelLinkage.Reach.DEPARTMENT, departments)
        }
    }

    @Test
    fun stepBudgetIsInclusiveAndSharedAcrossBranches() {
        val stop = mapOf("stop" to true)
        CamelRoutePlan.parse(mapOf("from" to "direct:in", "steps" to List(128) { stop }))
        refused(mapOf("from" to "direct:in", "steps" to List(129) { stop }))
        fun branched(fallbackSize: Int) = route(choice(
            listOf(mapOf("bodyEquals" to "x", "steps" to List(63) { stop })), List(fallbackSize) { stop },
        ))
        CamelRoutePlan.parse(branched(64)).validate(CamelLinkage.Reach.LOCAL, mounted)
        refused(branched(65))
    }

    @Test
    fun emptyBranchesHaveAnAggregateBudget() {
        val branch = branch()
        CamelRoutePlan.parse(route(choice(List(128) { branch }, emptyList<Any?>())))
        refused(route(choice(List(129) { branch }, emptyList<Any?>())))
        refused(route(choice(List(64) { branch }, emptyList<Any?>()), choice(List(65) { branch }, emptyList<Any?>())))
    }

    @Test
    fun depthBudgetCoversWhenOtherwiseAndCycles() {
        for (otherwise in listOf(false, true)) {
            CamelRoutePlan.parse(route(nested(8, otherwise = otherwise))).validate(CamelLinkage.Reach.LOCAL, mounted)
            refused(route(nested(9, otherwise = otherwise)))
        }
        val cycle = mutableListOf<Any?>()
        cycle.add(choice(emptyList<Any?>(), cycle))
        refused(mapOf("from" to "direct:in", "steps" to cycle))
    }

    @Test
    fun textBudgetCountsUtf8BytesAcrossAllStringFields() {
        val from = "direct:in"
        val exact = "x".repeat(65536 - from.length)
        CamelRoutePlan.parse(route(mapOf("setBody" to exact)))
        refused(route(mapOf("setBody" to exact + "x")))
        val unicode = "é".repeat((65536 - from.length) / 2)
        CamelRoutePlan.parse(route(mapOf("setBody" to unicode)))
        refused(route(mapOf("setBody" to unicode + "é")))

        val value = route(
            mapOf("setBody" to "x".repeat(65536 - from.length - "namevalueequalsmock:out".length)),
            mapOf("setHeader" to mapOf("name" to "name", "value" to "value")),
            choice(listOf(mapOf("bodyEquals" to "equals", "steps" to listOf(mapOf("to" to "mock:out")))), emptyList<Any?>()),
        )
        CamelRoutePlan.parse(value).validate(CamelLinkage.Reach.LOCAL, mounted)
        refused(value + ("from" to from + "x"))
    }

    @Test
    fun oversizedArraysAreRefusedBeforeReadingElements() {
        val oversized = object : AbstractList<Any?>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): Any? = error("oversized array elements must not be read")
        }
        refused(mapOf("from" to "direct:in", "steps" to oversized))
        refused(route(choice(oversized, emptyList<Any?>())))
    }

    @Test
    fun directlyConstructedPlansCannotBypassShapeOrBudgetValidation() {
        val invalid = listOf(
            CamelRoutePlan(" ", emptySeriesOf()),
            CamelRoutePlan("direct:in", s_[CamelRouteStep.To("direct:{{target}}")]),
            CamelRoutePlan("direct:in", s_[CamelRouteStep.SetHeader("", "v")]),
            CamelRoutePlan("direct:in", s_[CamelRouteStep.SetBody("x".repeat(65536))]),
            CamelRoutePlan("direct:in", -1 j { CamelRouteStep.Stop }),
            CamelRoutePlan("direct:in", 129 j { error("oversized series must not be read") }),
            CamelRoutePlan("direct:in", s_[CamelRouteStep.Choice(129 j { error("oversized branches must not be read") }, emptySeriesOf())]),
        )
        for (plan in invalid) {
            assertFailsWith<IllegalArgumentException> { plan.validate(CamelLinkage.Reach.LOCAL, mounted) }
            assertFailsWith<IllegalArgumentException> { plan.toValue() }
        }
        lateinit var cycle: CamelRouteStep
        cycle = CamelRouteStep.Choice(emptySeriesOf(), 1 j { cycle })
        val plan = CamelRoutePlan("direct:in", s_[cycle])
        assertFailsWith<IllegalArgumentException> { plan.validate(CamelLinkage.Reach.LOCAL, mounted) }
        assertFailsWith<IllegalArgumentException> { plan.toValue() }
    }
}
