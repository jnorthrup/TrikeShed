package borg.trikeshed.narsese

import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpRelation
import borg.trikeshed.nlp.NlpToken

/**
 * A normative clause read off the dependency parse: a subject noun phrase, a deontic or habitual
 * modal (`will`, `must`, `may`, `shall`, `should`, `cannot`, …) or `generic` for a present-tense
 * statement whose subject is one of the caller's generic subjects, a polarity, and the predicate
 * (verb, object phrase, oblique phrase). Passive voice marks the verb `be_<verb>`. A subordinate
 * clause opened by `if`, `when`, `unless`, … is the condition: marker, its subject, its verb.
 *
 *     No one can take advantage of his own wrong.
 *       → NormClause(subject="one", modal="cannot", affirmative=false, verb="take", obj="advantage", oblique="of his own wrong")
 */
data class NormClause(
    val subject: String,
    val modal: String,
    val affirmative: Boolean,
    val verb: String,
    val obj: String?,
    val oblique: String?,
    val sentence: String,
    val condition: String? = null,
    /** The subject's head lemma and its named-entity tag (`O` when none): what types the bearer. */
    val head: String = subject.substringAfterLast(' '),
    val ner: String = "O",
    /** The share of the clause's content words (its verb's span in the parse) this reading keeps: 1 when nothing was clipped. */
    val reified: Float = 1f,
) {
    /** The predicate as one term: verb and object. Restatements that differ only in qualification share it. */
    val predicate: String get() = listOfNotNull(verb, obj).joinToString("_") { it.replace(' ', '_') }
}

object NormClauses {
    private val modals = setOf("will", "must", "may", "shall", "should", "can", "cannot", "would", "ought")
    private val negators = setOf("not", "never", "no", "n't")
    private val modifiers = arrayOf("compound", "amod", "nmod:poss", "nummod")
    private val conditionMarks = setOf("if", "when", "unless", "until", "where", "whenever", "before", "after", "upon")

    /** Predicate words that carry legal force without a modal verb: the force, and whether it denies. */
    private val predicateForce = mapOf(
        "responsible" to ("must" to false), "liable" to ("must" to false), "require" to ("must" to false),
        "obligate" to ("must" to false), "oblige" to ("must" to false), "agree" to ("must" to false),
        "entitle" to ("may" to false), "entitled" to ("may" to false), "permit" to ("may" to false), "allow" to ("may" to false),
        "authorize" to ("may" to false), "eligible" to ("may" to false), "prohibit" to ("must" to true),
        "able" to ("may" to false), "need" to ("must" to false),
    )

    /** Verbs whose force falls on the verb they govern: "may elect to hold" holds. */
    private val choosing = setOf("choose", "elect", "decide", "opt", "wish", "want", "seek")

    /** Nouns that carry force over the verb they govern: "the right to", "an obligation to". */
    private val nounForce = mapOf("right" to "may", "obligation" to "must", "duty" to "must", "responsibility" to "must")

    /**
     * Clauses of [doc]; a modal-less present-tense clause counts only when its subject lemma is in [genericSubjects].
     * A word the text's [parties] define ([PartyAliases]) reads as that party's noun: a pronoun as subject, object,
     * possessor or the subject of a condition, and a bare noun alias ("customer"). "You may cancel" is the user's norm.
     * Each subject the parse gives a verb (enhanced dependencies spread a verb over "the Seller or Coinbase") is a clause.
     */
    fun extract(doc: NlpDocument, genericSubjects: Set<String> = emptySet(), parties: Map<String, PartyAliases.Party> = emptyMap()): List<NormClause> {
        val out = ArrayList<NormClause>()
        for (s in doc.sentences.values()) {
            val tokens = s.tokens.values().associateBy { it.index }
            val deps = s.dependencies.values()
            fun kids(head: Int, vararg rel: String) = deps.filter { d -> d.governor == head && rel.any { d.relation.lowercase().startsWith(it) } }
            fun partyOf(t: NlpToken) = parties[t.lemma.lowercase()]?.takeIf {
                t.tag.startsWith("PRP") || (t.tag.startsWith("NN") || t.tag.startsWith("JJ")) && kids(t.index, "compound", "amod").isEmpty()
            }
            fun party(t: NlpToken): String? = partyOf(t)?.noun
            fun phrase(t: NlpToken, depth: Int = 0): String {
                party(t)?.let { return it }
                val mods = kids(t.index, *modifiers).mapNotNull { tokens[it.dependent] }.filter { it.index < t.index }.sortedBy { it.index }
                // A possessive pronoun keeps its own form: CoreNLP lemmatises "his" to "he"; a party's possessive is the party.
                // Any other possessive pronoun or a demonstrative/quantifying determiner does not name the bearer.
                // One `of` complement stays with its head: "Division 8 of the California Uniform Commercial Code".
                val of = if (depth > 0) null else kids(t.index, "nmod:of").mapNotNull { tokens[it.dependent] }.firstOrNull { it.index > t.index }
                // A participle modifier keeps its form ("supported digital assets"); a number after the head stays ("section 2.7").
                val numbers = kids(t.index, "nummod").mapNotNull { tokens[it.dependent] }.filter { it.index > t.index }.sortedBy { it.index }
                return (mods.mapNotNull { m -> when {
                    m.tag.startsWith("PRP") -> party(m)
                    m.tag == "VBN" || m.tag == "VBG" -> m.word.lowercase()
                    else -> m.lemma.lowercase().takeIf { it !in setOf("such", "said", "same") }
                } } + t.lemma.lowercase() + numbers.map { it.word.lowercase() } + listOfNotNull(of?.let { "of " + phrase(it, depth + 1) })).joinToString(" ")
            }
            // A to-infinitive the predicate governs, read as its verb, object and its own complement: "wait amount of time".
            fun complement(v: NlpToken, depth: Int = 0): String = (listOf(v.lemma.lowercase()) +
                listOfNotNull(kids(v.index, "obj", "dobj").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let { phrase(it) }) +
                listOfNotNull(if (depth >= 2) null else kids(v.index, "xcomp").mapNotNull { tokens[it.dependent] }
                    .firstOrNull { it.tag.startsWith("VB") }?.let { complement(it, depth + 1) })).joinToString(" ")
            fun modalOf(verb: NlpToken) = kids(verb.index, "aux").mapNotNull { tokens[it.dependent] }
                .firstOrNull { it.lemma.lowercase() in modals || it.word.lowercase() in modals }
            fun clauseVerb(i: Int) = tokens[i]?.let { it.tag.startsWith("VB") && kids(i, "nsubj").isNotEmpty() && modalOf(it) != null } == true
            // The clause's own words: the verb's dependents, transitively, short of a coordinate clause, another modal clause
            // and the auxiliaries; the share of its content words the clause keeps is how whole the parse reified it.
            fun span(verb: NlpToken): List<NlpToken> {
                val seen = HashSet<Int>(); val at = ArrayList<NlpToken>(); val stack = ArrayList<Int>().apply { add(verb.index) }
                while (stack.isNotEmpty()) {
                    val h = stack.removeAt(stack.size - 1)
                    if (!seen.add(h)) continue
                    tokens[h]?.let(at::add)
                    for (d in deps) if (d.governor == h) {
                        val r = d.relation.lowercase()
                        if (r.startsWith("conj") || r.startsWith("parataxis") || r.startsWith("aux") || r.startsWith("cop") || r == "punct") continue
                        if (d.dependent != verb.index && clauseVerb(d.dependent)) continue
                        stack.add(d.dependent)
                    }
                }
                return at
            }
            fun content(t: NlpToken) = t.tag.startsWith("NN") || t.tag.startsWith("VB") || t.tag.startsWith("JJ") || t.tag == "CD" || party(t) != null
            val text = doc.text.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim()
            // Predicates: verbs, and adjectives under a copula ("may be subject to", "are responsible for").
            // A conjunct of a verb that takes an object is a verb whatever its tag ("cancel orders, block/NN withdrawals").
            fun conjHead(t: NlpToken) = deps.firstOrNull { it.dependent == t.index && it.relation.lowercase().startsWith("conj") }?.let { tokens[it.governor] }
            for (verb in tokens.values.filter { it.tag.startsWith("VB") || it.tag.startsWith("JJ") &&
                    (it.lemma.lowercase() in predicateForce || kids(it.index, "cop").isNotEmpty()) ||
                    it.tag.startsWith("NN") && conjHead(it)?.tag?.startsWith("VB") == true && kids(it.index, "obj", "dobj").isNotEmpty() }) {
                // A bare copula ("the case is") carries no clause.
                if (verb.lemma.lowercase() == "be" && kids(verb.index, "obj", "dobj", "xcomp", "obl").isEmpty()) continue
                // A coordinated predicate with no subject of its own shares its head's subjects and modal: "we will cancel
                // all open orders, block all withdrawals and bar ...".
                val head = conjHead(verb)
                // A clause a negated predicate embeds is not asserted: "Coinbase does not guarantee that any order will be executed".
                val embedder = deps.firstOrNull { it.dependent == (head ?: verb).index && it.relation.lowercase() == "ccomp" }?.let { tokens[it.governor] }
                if (embedder != null && kids(embedder.index, "advmod", "neg").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() in negators }) continue
                // A participle shares its head's subject only when the head is a participle too ("executed, accepted or recorded");
                // "staked ETH and associated rewards" is a modifier.
                val subjects = kids(verb.index, "nsubj").ifEmpty {
                    head?.takeIf { verb.tag != "VBN" || it.tag == "VBN" }?.let { kids(it.index, "nsubj") }.orEmpty()
                }
                for (subjDep in subjects) {
                val subj = tokens[subjDep.dependent] ?: continue
                // The relation says it is a nominal subject; the tagger often calls a capitalised defined term an adjective
                // ("The Executive/JJ shall comply"). Pronouns, wh-words and determiners still name no one unless a party's.
                if ((subj.tag.startsWith("PRP") || subj.tag.startsWith("W") || subj.tag == "DT" || subj.tag == "EX" || subj.tag == "CD") && party(subj) == null) continue
                val modal = modalOf(verb) ?: head?.let(::modalOf)
                // Legal force a predicate word carries instead of (or over) a modal verb: "you are responsible for",
                // "you agree to", "Coinbase is entitled to", "you are prohibited from"; and "have the right / an
                // obligation to V", where the noun carries it. The action is then the governed verb.
                val lemma = verb.lemma.lowercase()
                var action = verb
                var force: String? = null
                var forceNegated = false
                var forgo = false
                predicateForce[lemma]?.let { (f, negative) ->
                    // The parser sometimes leaves the to-infinitive as a bare `dep` ("be able to opt-in"); its "to" marks it.
                    val governed = (kids(verb.index, "xcomp", "advcl") + kids(verb.index, "dep").filter { d -> kids(d.dependent, "mark").any { tokens[it.dependent]?.lemma?.lowercase() == "to" } })
                        .mapNotNull { tokens[it.dependent] }.firstOrNull { it.tag.startsWith("VB") && it.index > verb.index }
                    // The force binds the subject only as the predicate's patient or holder: "you are required to" and
                    // "you are responsible for", not "we may require you to"; "agree" binds its own subject to what follows.
                    val binds = when {
                        verb.tag.startsWith("JJ") -> true
                        lemma == "agree" || lemma == "need" -> governed != null
                        else -> subjDep.relation.lowercase().endsWith(":pass")
                    }
                    if (!binds) return@let
                    force = f; forceNegated = negative
                    governed?.let { action = it }
                }
                if (lemma == "have" || lemma == "bear") kids(verb.index, "obj", "dobj").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let { noun ->
                    val f = nounForce[noun.lemma.lowercase()] ?: return@let
                    val governed = kids(noun.index, "acl").mapNotNull { tokens[it.dependent] }.firstOrNull { it.tag.startsWith("VB") } ?: return@let
                    action = governed; force = f
                    // "no obligation to V" grants leave to forgo V; "no right to V" denies it.
                    val none = kids(noun.index, "det").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() == "no" }
                    if (none && f == "must") { force = "may"; forgo = true } else if (none) forceNegated = true
                }
                // A choice verb is the choosing of what it governs: "may elect to hold" is "may hold".
                if (action.lemma.lowercase() in choosing) kids(action.index, "xcomp").mapNotNull { tokens[it.dependent] }
                    .firstOrNull { it.tag.startsWith("VB") }?.let { action = it }
                // A party stating something in the present tense commits to it ("Coinbase does not guarantee ...").
                // The tense is on the copula or the do-support auxiliary when there is one ("does not guarantee").
                val tense = (kids(verb.index, "cop", "aux").mapNotNull { tokens[it.dependent] }.firstOrNull { it.lemma.lowercase() in setOf("be", "do") }
                    ?: verb).tag
                // A relative or adverbial clause in the present tense describes ("the amount you owe"); it states no norm.
                // Only the sentence's main predicate, or a conjunct of it, states; any other attachment describes.
                // Enhanced dependencies can close a conj cycle; a token seen once on the walk is not re-entered.
                val walked = HashSet<Int>()
                fun main(t: NlpToken): Boolean = deps.any { it.dependent == t.index && it.relation.lowercase() == "root" } ||
                    walked.add(t.index) && deps.filter { it.dependent == t.index }.all { d ->
                        d.relation.lowercase().startsWith("conj") && tokens[d.governor]?.let(::main) == true }
                val subordinate = !main(verb)
                val generic = modal == null && force == null && !subordinate && tense in setOf("VBZ", "VBP") &&
                    (subj.lemma.lowercase() in genericSubjects || party(subj) != null)
                if (modal == null && force == null && !generic) continue
                // "the bill will be dismissed": the subject is the patient, so the predicate is passive.
                val passive = if (action === verb && subjDep.governor == verb.index) subjDep.relation.lowercase().endsWith(":pass") else kids(action.index, "aux:pass").isNotEmpty()
                // Negation on the verb ("will not"), in the modal ("cannot"), or on the subject ("no one").
                val negated = forceNegated || kids(verb.index, "advmod", "neg").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() in negators } ||
                    kids(subj.index, "det", "advmod").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() in negators } ||
                    modal?.word?.lowercase() == "cannot" ||
                    // "shall bear no liability": a "no" on the object denies the predicate.
                    kids(action.index, "obj", "dobj").mapNotNull { tokens[it.dependent] }.any { o -> kids(o.index, "det").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() == "no" } }
                val objToken = kids(action.index, "obj", "dobj", "xcomp").mapNotNull { tokens[it.dependent] }.firstOrNull()
                    // A wh-word or pronoun object names no term: the clause it opens is not this norm's object. A party's pronoun names the party.
                    ?.takeIf { !it.tag.startsWith("W") && (!it.tag.startsWith("PRP") || party(it) != null) }
                // A verb object reads with its own object; a noun object keeps the to-infinitive its verb governs beside it
                // ("require user wait amount of time").
                // A clausal complement is the object: "does not guarantee that any order will be executed" -> "order be_execute".
                fun clause(c: NlpToken) = (listOfNotNull(kids(c.index, "nsubj").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let { phrase(it) }) +
                    ((if (kids(c.index, "aux:pass").isNotEmpty()) "be_" else "") + c.lemma.lowercase())).joinToString(" ")
                val objPhrase = objToken?.let { o ->
                    if (o.tag.startsWith("VB")) complement(o)
                    else (listOf(phrase(o)) + listOfNotNull(kids(action.index, "xcomp").mapNotNull { tokens[it.dependent] }
                        .firstOrNull { it !== o && it.tag.startsWith("VB") }?.let { complement(it) })).joinToString(" ")
                } ?: kids(action.index, "ccomp").mapNotNull { tokens[it.dependent] }
                    .firstOrNull { c -> kids(c.index, "mark").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() in setOf("that", "whether") } }?.let(::clause)
                fun oblique(o: NlpToken) = listOfNotNull(kids(o.index, "case").mapNotNull { tokens[it.dependent] }.firstOrNull()?.lemma?.lowercase(), phrase(o)).joinToString(" ")
                // The oblique is the first after the predicate; one fronted before it frames the clause instead.
                val obls = kids(action.index, "obl").mapNotNull { tokens[it.dependent] }
                val obl = obls.firstOrNull { it.index > action.index }?.let(::oblique)
                // "In the event that X" / "in such an event" fronted: the clause holds in that event.
                val event = (obls + if (action !== verb) kids(verb.index, "obl").mapNotNull { tokens[it.dependent] } else emptyList())
                    .firstOrNull { it.index < verb.index && it.lemma.lowercase() in setOf("event", "case") }?.let { e ->
                        (listOf("in event") + listOfNotNull(kids(e.index, "acl", "ccomp", "acl:relcl").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let(::clause))).joinToString(" ")
                    }
                val obj = objToken
                // An adverb after the predicate qualifies what is required or allowed: "may not be processed immediately".
                val manner = kids(action.index, "advmod").mapNotNull { tokens[it.dependent] }
                    .filter { it.index > action.index && it.tag.startsWith("RB") && it.lemma.lowercase() !in negators }.sortedBy { it.index }
                    .joinToString(" ") { it.lemma.lowercase() }.ifEmpty { null }
                // The condition is mark + premise; the premise reads as a fact does (subject, copula, head), and
                // a pronoun subject ("when it is due") is the clause's object, else its subject; a party's pronoun is the party.
                val condition = event ?: kids(verb.index, "advcl").mapNotNull { tokens[it.dependent] }.firstNotNullOfOrNull { v ->
                    kids(v.index, "mark", "advmod").mapNotNull { tokens[it.dependent] }.firstOrNull { it.lemma.lowercase() in conditionMarks }?.let { mark ->
                        val s = kids(v.index, "nsubj").mapNotNull { tokens[it.dependent] }.firstOrNull()
                            ?.let { if (it.tag.startsWith("PRP") && party(it) == null) obj ?: subj else it }
                        listOfNotNull(mark.lemma.lowercase(), s?.let { phrase(it) }, "be".takeIf { kids(v.index, "cop").isNotEmpty() },
                            v.lemma.lowercase(), kids(v.index, "obj", "dobj").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let { phrase(it) }).joinToString(" ")
                    }
                }
                // The other subjects of the verb are their own clauses' words, not words this one dropped.
                val kept = (subjects.mapNotNull { tokens[it.dependent]?.let { t -> phrase(t) } } + listOfNotNull(verb.lemma.lowercase(), action.lemma.lowercase(), objPhrase, manner, obl, condition))
                    .flatMap { it.split(' ', '_') }.toSet()
                val words = span(verb).filter(::content)
                out.add(NormClause(
                    subject = phrase(subj),
                    // What a party says it does in the present tense it commits to, as "will".
                    modal = force ?: modal?.lemma?.lowercase()?.let { if (it == "can" && negated) "cannot" else it }
                        ?: if (party(subj) != null) "will" else "generic",
                    affirmative = !negated,
                    verb = (if (forgo) "forgo_" else "") + (if (passive) "be_" else "") +
                        (if (action === verb && verb.tag.startsWith("JJ")) "be_" else "") + action.lemma.lowercase(),
                    obj = objPhrase,
                    oblique = listOfNotNull(manner, obl).joinToString(" ").ifEmpty { null },
                    sentence = text,
                    condition = condition,
                    head = party(subj) ?: subj.lemma.lowercase(),
                    ner = partyOf(subj)?.ner?.takeIf { it != "O" } ?: subj.ner,
                    reified = if (words.isEmpty()) 1f else words.count { (party(it) ?: it.lemma.lowercase()) in kept }.toFloat() / words.size,
                ))
                }
            }
        }
        // Every other asserted clause, as OpenIE reads it: (subject; relation; object) with the relation's
        // own polarity. A modal clause is already above; a coreferent subject reads as its chain's name.
        for (s in doc.sentences.values()) {
            if (s.relations.isEmpty() && s.kbp.isEmpty()) continue
            val tokens = s.tokens.values().associateBy { it.index }
            val text = doc.text.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim()
            // A party's pronoun is the party; any other pronoun names no one without coref.
            fun partyOf(t: NlpToken) = if (t.tag.startsWith("PRP")) parties[t.lemma.lowercase()] else null
            fun content(t: NlpToken) = t.tag.startsWith("NN") || t.tag.startsWith("VB") || t.tag.startsWith("JJ") || t.tag == "CD" || partyOf(t) != null
            val words = tokens.values.count(::content).coerceAtLeast(1)
            // What a triple reifies: the extractor's confidence times the share of the sentence's content words its spans hold.
            fun share(r: NlpRelation) = (r.confidence.toFloat() * (r.subject + r.relation + r.`object`).distinct()
                .mapNotNull { tokens[it] }.count(::content) / words).coerceIn(0f, 1f)
            val named = HashMap<IntRange, String>()
            for (m in doc.mentions) if (m.sentence == s.index && !m.representative)
                doc.mentions.firstOrNull { it.chain == m.chain && it.representative }?.let { named[m.span] = it.text }
            // Of the nested spans OpenIE emits for one clause, keep the most confident per subject head and relation.
            val best = LinkedHashMap<Pair<Int, String>, NlpRelation>()
            for (r in s.relations) {
                val rel = r.relation.mapNotNull { tokens[it] }
                if (rel.any { it.lemma.lowercase() in modals || it.word.lowercase() in modals }) continue
                val head = r.subject.lastOrNull { tokens[it]?.let { t -> t.tag.startsWith("NN") || partyOf(t) != null } == true } ?: continue
                val key = head to rel.filter { it.tag.startsWith("VB") }.joinToString("_") { it.lemma.lowercase() }
                // A bare copula states no relation; its object is the predicate, which the fact tupler reads.
                if (key.second.isEmpty() || key.second == "be") continue
                // An object that is a pronoun or a whole clause is not a term.
                val objTokens = r.`object`.mapNotNull { tokens[it] }
                if (objTokens.isNotEmpty() && objTokens.all { it.tag.startsWith("W") || it.tag.startsWith("PRP") || it.tag == "DT" }) continue
                if (objTokens.any { it.tag.startsWith("VB") } && objTokens.size > 4) continue
                val prior = best[key]
                if (prior == null || r.confidence > prior.confidence ||
                    r.confidence == prior.confidence && r.`object`.count() > prior.`object`.count()) best[key] = r
            }
            for ((key, r) in best) {
                val rel = r.relation.mapNotNull { tokens[it] }
                val head = tokens[key.first]!!
                val negated = rel.any { it.lemma.lowercase() in negators }
                // Determiners, possessives and pronoun modifiers are not part of the bearer's name.
                // A party's pronoun or possessive is the party.
                fun term(ts: List<NlpToken>) = ts.mapNotNull { partyOf(it)?.noun ?: it.takeIf { !it.tag.startsWith("DT") && !it.tag.startsWith("PRP") &&
                    it.tag != "PDT" && it.tag != "POS" && it.lemma.lowercase() !in setOf("such", "said", "same") }?.lemma?.lowercase() }.joinToString(" ")
                // A coreferent subject reads as its chain's representative mention.
                val subject = named[r.subject]?.lowercase() ?: term(r.subject.mapNotNull { tokens[it] })
                if (subject.isBlank()) continue
                val obj = term(r.`object`.mapNotNull { tokens[it] }).takeIf { it.isNotBlank() }
                out.add(NormClause(
                    subject = subject.lowercase(), modal = "generic", affirmative = !negated,
                    verb = key.second, obj = obj, oblique = null, sentence = text,
                    head = partyOf(head)?.noun ?: head.lemma.lowercase(), ner = partyOf(head)?.ner?.takeIf { it != "O" } ?: head.ner,
                    reified = share(r),
                ))
            }
            // Stanford relation extraction (KBP): typed relations between named mentions (org:founded_by,
            // per:spouse), asserted facts; the slot name is the verb, the mentions are bearer and object.
            for (r in s.kbp) {
                val head = r.subject.lastOrNull()?.let { tokens[it] } ?: continue
                // Without coref a pronoun names no one, unless it is a party's: neither bearer nor object is a term then.
                val objects = r.`object`.mapNotNull { tokens[it] }
                if (head.tag.startsWith("PRP") && partyOf(head) == null || objects.all { it.tag.startsWith("PRP") && partyOf(it) == null }) continue
                val bearer = partyOf(head)
                out.add(NormClause(
                    subject = bearer?.noun ?: r.subjectText.lowercase(), modal = "generic", affirmative = true,
                    verb = r.relationText, obj = objects.singleOrNull()?.let(::partyOf)?.noun ?: r.objectText.lowercase(), oblique = null, sentence = text,
                    head = bearer?.noun ?: head.lemma.lowercase(), ner = bearer?.ner?.takeIf { it != "O" } ?: head.ner,
                    reified = share(r),
                ))
            }
        }
        return out
    }

    /**
     * Facts of [doc] in premise form: per subject, its phrase, `be` when the head is copular, and the
     * head lemma ("The rent is due." → "rent be due"), so a fact matches the premise of a condition.
     */
    fun facts(doc: NlpDocument): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in doc.sentences.values()) {
            val tokens = s.tokens.values().associateBy { it.index }
            val deps = s.dependencies.values()
            fun kids(head: Int, vararg rel: String) = deps.filter { d -> d.governor == head && rel.any { d.relation.lowercase().startsWith(it) } }
            fun phrase(t: NlpToken) = (kids(t.index, *modifiers).mapNotNull { tokens[it.dependent] }.filter { it.index < t.index }.sortedBy { it.index } + t)
                .joinToString(" ") { (if (it.tag == "PRP$") it.word else it.lemma).lowercase() }
            for (d in deps) if (d.relation.lowercase().startsWith("nsubj")) {
                val head = tokens[d.governor] ?: continue
                val subj = tokens[d.dependent]?.takeIf { it.tag.startsWith("NN") } ?: continue
                out.add(listOfNotNull(phrase(subj), "be".takeIf { kids(head.index, "cop").isNotEmpty() }, head.lemma.lowercase()).joinToString(" "))
            }
        }
        return out
    }
}
