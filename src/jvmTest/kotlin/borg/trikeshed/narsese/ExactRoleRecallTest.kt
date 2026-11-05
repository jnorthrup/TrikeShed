package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Exact source-local manual-KG selection/attention, not English parsing, inference or legal accuracy. */
class ExactRoleRecallTest {
    private val owners = PropositionAttentionTest()
    private val c2 = "(pays Tenant Landlord)\n(owes Tenant Landlord)\n"
    private val c2Cid = ContentId("sha256:856e48302ec7ce9e8db378cad6244b49c8510d80376b914c95c37a811420ba05")
    private fun request(source: ContentId = c2Cid, predicate: String = "pays", subject: String = "Tenant", obj: String? = null): Map<String, Any?> =
        _m["sourceContext" j source.value, "predicate" j predicate, "subject" j subject] + (obj?.let { _m["object" j it] } ?: emptyMap())
    @Suppress("UNCHECKED_CAST") private fun fields(value: Any?) = value as Map<String, Any?>
    private fun matches(value: Map<String, Any?>) = (value["matches"] as List<*>).map(::fields)
    private fun selected(output: Map<String, Any?>) = fields(output["selection"])
    private suspend fun importCollision(bag: BeliefBagElement) {
        assertEquals(c2Cid, ContentId.of(c2.encodeToByteArray()))
        val error = assertFailsWith<BeliefAdmissionRefused> { bag.ingestKg(c2) }
        assertEquals(BeliefAdmissionIssue.PROPOSITION_MISMATCH, error.issue)
    }
    private suspend fun retain(bag: BeliefBagElement, text: String): ContentId {
        val batch = KgRetention.prepare(text, KgNalBridge.bridge(text)); bag.retainKg(batch); return batch.source.cid
    }
    private fun wired(request: Any?, port: String = "request?", authored: Map<String, Any?> = this.request(predicate = "owes")): LcncProgram {
        val p = LcncProgram("Exact role request", s_[
            LcncNode("request", "json.value", _m["value" j jsonOf(request)]),
            LcncNode("recall", "nal.recall", _m["mode" j "exact-role", "request" j jsonOf(authored)])],
            s_[LcncWire("request", "value", "recall", port)])
        return LcncProgramConfix.fromJson(p.name, LcncProgramConfix.toJson(p))
    }
    private fun walker(bag: BeliefBagElement) = LcncRunner(PureNodes.registry { 123L } + _m["nal.recall" j NalNodes.recallRunner(bag)])

    @Test fun actualC2StructuredWireSelectsPaysThenExplicitAttentionChangesOnlyBudget() = runBlocking {
        val cas = PropositionAttentionTest.Store()
        owners.owner(cas) { bag ->
            importCollision(bag)
            val mapped = KgNalBridge.bridge(c2).first(); val signal = assertNotNull(bag.signalOf(mapped.signal(c2Cid.value).angular))
            val before = SignalCodec.encode(signal); val puts = cas.puts.size
            for (port in s_["request", "request?"].view) {
                val output = walker(bag).runProcedure(wired(request(), port)).nodeOutputs.getValue("recall")
                val selection = selected(output); val match = matches(selection).single()
                assertEquals("MATCHES_IN_COMPLETE_SOURCE", selection["status"]); assertEquals(true, selection["complete"])
                assertEquals("pays", match["predicate"]); assertEquals("Tenant", match["subject"]); assertEquals("Landlord", match["object"])
                assertEquals(signal.propositionCid, match["propositionCid"]); assertEquals("MATCH_RESIDENT", match["residentStatus"])
                assertNull(match["subjectCid"]); assertNull(match["objectCid"], "No documentary-source fallback becomes a role identity")
                assertEquals(c2Cid.value, signal.subjectCid, "Legacy routing fallback stays a separate signal field")
                assertEquals(1, (output["beliefs"] as List<*>).size)
                val typed = (((selection["results"] as Map<*, *>)["results"] as Map<*, *>)["bindings"] as List<*>).single() as Map<*, *>
                assertEquals(_m["type" j "literal", "value" j "Landlord"], typed["object"])
                assertEquals("uri", (typed["proposition"] as Map<*, *>)["type"])
                assertEquals(c2, (selection["source"] as Map<*, *>)["quote"])
                assertEquals(2, selection["definitions"])
                assertEquals("not-performed", selection["attention"]); assertEquals("request-local", selection["persistence"])
                assertContentEquals(before, SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
                assertEquals(puts, cas.puts.size)
                val priorBudget = assertNotNull(bag.budgetOf(signal.angular))
                val command = _m["angular" j match["angular"], "propositionCid" j match["propositionCid"], "p" j .95]
                val attended = BeliefsNodes.attendRunner(bag).run(LcncNode("attend", "nal.attend"), command)
                assertEquals(true, fields(attended["attended"])["processed"])
                assertEquals(BudgetCoord(.95f, priorBudget.df, priorBudget.qf), bag.budgetOf(signal.angular))
            }
            assertContentEquals(before, SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
            assertEquals(puts, cas.puts.size)
        }
    }

    @Test fun sameAngularOwesAndOtherContextRemainDocumentaryMatchesWithoutWrongResidentAttention() = runBlocking {
        val cas = PropositionAttentionTest.Store()
        owners.owner(cas) { bag ->
            importCollision(bag)
            val a = KgNalBridge.bridge(c2)[0].signal(c2Cid.value); val before = SignalCodec.encode(assertNotNull(bag.signalOf(a.angular)))
            val output = NalNodes.recallRunner(bag).run(LcncNode("recall", "nal.recall", _m["mode" j "exact-role"]), _m["request?" j request(predicate = "owes")])
            val owes = matches(selected(output)).single()
            assertEquals("RESIDENT_PID_MISMATCH", owes["residentStatus"]); assertTrue((output["beliefs"] as List<*>).isEmpty())
            assertNotEquals(a.propositionCid, owes["propositionCid"]); assertEquals(a.angular.toString(), owes["angular"])
            val refusal = assertFailsWith<BeliefAdmissionRefused> { bag.attendProposition(a.angular, ContentId(owes["propositionCid"] as String), p = 1f) }
            assertEquals(BeliefAdmissionIssue.PROPOSITION_MISMATCH, refusal.issue)
            val reversed = bag.recallRoles(request(subject = "Landlord", obj = "Tenant"))
            assertEquals("NO_MATCH_IN_COMPLETE_SOURCE", reversed["status"]); assertEquals(true, reversed["complete"])
            val absentContext = bag.recallRoles(request(ContentId.of("unavailable source".encodeToByteArray())))
            assertEquals("UNVERIFIED_SOURCE", absentContext["status"]); assertEquals(false, absentContext["complete"])
            val other = retain(bag, "; another declared source context\n(pays Tenant Landlord)\n")
            val elsewhere = matches(bag.recallRoles(request(other))).single()
            assertEquals(other.value, elsewhere["sourceContext"]); assertNotEquals(a.propositionCid, elsewhere["propositionCid"])
            assertEquals("RESIDENT_PID_MISMATCH", elsewhere["residentStatus"])
            assertContentEquals(before, SignalCodec.encode(assertNotNull(bag.signalOf(a.angular))))
        }
    }

    @Test fun duplicateOccurrencesAndRepeatedSelectionNeverAddEvidenceAndEvictionCannotBeRevived() = runBlocking {
        val cas = PropositionAttentionTest.Store()
        owners.owner(cas, floor = .5f) { bag ->
            val text = "(pays Tenant Landlord)\n".repeat(65)
            bag.ingestKg(text); val source = ContentId.of(text.encodeToByteArray())
            val signal = bag.snapshot().values.single(); val before = SignalCodec.encode(signal); val puts = cas.puts.size
            repeat(10) {
                val selection = bag.recallRoles(request(source)); assertEquals(1, selection["definitions"])
                val match = matches(selection).single(); assertEquals("MATCH_RESIDENT", match["residentStatus"])
                bag.attendProposition(signal.angular, ContentId(match["propositionCid"] as String), p = .9f)
            }
            assertContentEquals(before, SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
            assertEquals(puts, cas.puts.size)
            bag.attendProposition(signal.angular, ContentId(assertNotNull(signal.propositionCid)), p = .1f)
            bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed()
            assertNull(bag.signalOf(signal.angular))
            val match = matches(bag.recallRoles(request(source))).single()
            assertEquals("MATCH_NOT_RESIDENT", match["residentStatus"])
            val refusal = assertFailsWith<BeliefAdmissionRefused> { bag.attendProposition(signal.angular, ContentId(match["propositionCid"] as String), p = 1f) }
            assertEquals(BeliefAdmissionIssue.RESIDENT_REQUIRED, refusal.issue); assertNull(bag.signalOf(signal.angular))
        }
    }

    @Test fun unsupportedWholeSourcesAndMissingUnrelatedDefinitionsNeverReturnPartialNoMatch() = runBlocking {
        val cas = PropositionAttentionTest.Store()
        owners.owner(cas) { bag ->
            for (tail in s_["(not (pays Tenant Landlord))", "(=> (assignedTo ?x ?y) (mayRead ?x ?y))",
                "(ist AgreementA (pays Tenant Landlord))", "(holdsDuring A (pays Tenant Landlord))", "malformed"].view) {
                val source = cas.put(("(pays Tenant Landlord)\n" + tail).encodeToByteArray())
                val result = bag.recallRoles(request(source))
                assertEquals("UNSUPPORTED_SOURCE_PROFILE", result["status"]); assertEquals(false, result["complete"]); assertTrue(matches(result).isEmpty())
            }
            for (otherProfile in s_["<urn:T> <urn:p> <urn:O> .", "{\"guardAttachments\":{\"kind\":\"english-guard-attachment-observations/1\"}}"].view)
                assertEquals("UNSUPPORTED_SOURCE_PROFILE", bag.recallRoles(request(cas.put(otherProfile.encodeToByteArray())))["status"])
            val source = retain(bag, "(pays Tenant Landlord)\n(labels Clerk Record)\n")
            val definitions = KgNalBridge.bridge(assertNotNull(cas.get(source)).decodeToString())
            val unrelated = definitions.last().propositionCid(source.value)
            cas.unavailable.add(unrelated)
            val missing = bag.recallRoles(request(source, predicate = "absent"))
            assertEquals("UNVERIFIED_SOURCE", missing["status"]); assertEquals(false, missing["complete"]); assertTrue(matches(missing).isEmpty())
            cas.unavailable.clear()
            cas.blobs[unrelated] = "substituted definition".encodeToByteArray()
            assertEquals("UNVERIFIED_SOURCE", bag.recallRoles(request(source))["status"])
            val many = (0 until 65).joinToString("\n") { "(labels Clerk$it Record)" }
            val atBound = bag.recallRoles(request(retain(bag, many.substringBeforeLast('\n')), predicate = "labels", subject = "Clerk0"))
            assertEquals("MATCHES_IN_COMPLETE_SOURCE", atBound["status"]); assertEquals(64, atBound["definitions"])
            val bounded = bag.recallRoles(request(retain(bag, many), predicate = "labels", subject = "Clerk0"))
            assertEquals("BOUNDED", bounded["status"]); assertEquals(false, bounded["complete"]); assertTrue(matches(bounded).isEmpty())
            assertEquals(0, bag.size)
        }
    }

    @Test fun lexicalLiteralsRemainTypedAndNeverBecomeInjectedPatternsOrEntityFallbacks() = runBlocking {
        val cas = PropositionAttentionTest.Store()
        owners.owner(cas) { bag ->
            val text = "(labels Tenant \"notice; (exact) 🧾\")"
            bag.ingestKg(text); val source = ContentId.of(text.encodeToByteArray())
            val objectToken = KgNalBridge.bridge(text).single().triplet.obj
            val selection = bag.recallRoles(request(source, "labels", obj = objectToken))
            assertEquals(objectToken, matches(selection).single()["object"])
            val typed = (((selection["results"] as Map<*, *>)["results"] as Map<*, *>)["bindings"] as List<*>).single() as Map<*, *>
            assertEquals(_m["type" j "literal", "value" j objectToken], typed["object"])
            val noInjection = bag.recallRoles(request(source, "labels", obj = "\" . ?s ?p ?o . #"))
            assertEquals("NO_MATCH_IN_COMPLETE_SOURCE", noInjection["status"])
            assertFailsWith<IllegalArgumentException> { bag.recallRoles(request(source, "labels") + _m["subjectCid" j source.value]) }
        }
    }

    @Test fun structuredCanonicalAndOptionalWirePresenceNeverFallBackFromNullOrBadRequest() = runBlocking {
        val cas = PropositionAttentionTest.Store()
        owners.owner(cas) { bag ->
            importCollision(bag)
            for (port in s_["request", "request?"].view) {
                assertFailsWith<IllegalArgumentException> { walker(bag).runProcedure(wired(null, port, request())) }
                assertFailsWith<IllegalArgumentException> { walker(bag).runProcedure(wired(request() + _m["object" j null], port, request())) }
            }
            val runner = NalNodes.recallRunner(bag); val node = LcncNode("recall", "nal.recall", _m["mode" j "exact-role", "request" j jsonOf(request())])
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["request" j null, "request?" j request()]) }
            assertFailsWith<IllegalArgumentException> { runner.run(node, _m["request" j request(), "request?" j null]) }
            assertFailsWith<IllegalArgumentException> { runner.run(node.copy(params = _m["mode" j "exact-role"]), emptyMap()) }
            assertFailsWith<IllegalArgumentException> { bag.recallRoles("{\"sourceContext\":\"${c2Cid.value}\",\"predicate\":\"pays\",\"subject\":\"Tenant\",\"subject\":\"Other\"}") }
            val old = runner.run(LcncNode("legacy", "nal.recall", _m["mode" j "top"]), _m["request" j null])
            assertEquals(1, (old["beliefs"] as List<*>).size); assertFalse(old.containsKey("selection"))
            val contract = assertNotNull(LcncContracts.find("nal.recall"))
            assertTrue("request?" in contract.inputs); assertEquals("json", contract.inputKinds["request"])
            assertTrue("selection" in contract.outputs)
        }
    }
}
