package borg.trikeshed.lcnc

/**
 * CABLES ARE NEVER UNTYPED.
 *
 * A cable carries exactly one type — the type of what flows through it — fixed
 * by its source port, and the sink port must be that type. The surface does not
 * have to SHOW the type; it must OBEY it. There is no subtyping between kinds:
 * a wire whose two ends differ is not a cable.
 *
 * Kind names are what CCEK moves:
 *  - the five Confix slots — `json`, `text`, `id`, `num`, `trigger` — where a
 *    runner genuinely moves a Confix value of that slot (a literal, a rendered
 *    board, a tick);
 *  - the exact CCEK type — `List<TurnFact>` — wherever the runner says one. A
 *    Confix slot is never used in place of a CCEK type unless they match;
 *  - [CCEK_ANY] — a sink whose runner declares `Any` (`display` renders
 *    anything). That is the runner's exact type, not a wildcard;
 *  - [UNRESOLVED] — a ring port that has declared nothing and has no cable yet.
 *    The first cable plugged into it fixes its type ([LcncTypeCheck]); until
 *    then nothing can be claimed about it, which is not the same as accepting
 *    anything.
 *
 * `beliefs.introspect.field` is a Confix object — `json` is honest there. What
 * `beliefs.review.facts` consumes is `List<TurnFact>`, and calling that `json`
 * is how the curator wired a summary into a review and reviewed nothing.
 */
object LcncKinds {

    /** A ring port that has declared no type and has no cable yet. The canvas spells it `*`. */
    const val UNRESOLVED = "*"

    /** A sink whose runner declares `Any` — display, note. Exact, not generic. */
    const val CCEK_ANY = "Any"

    /** The five Confix slots. */
    val CONFIX_SLOTS: List<String> = listOf("json", "text", "id", "num", "trigger")

    /** A CCEK type name: `List<TurnFact>`, `KanbanCard`, `Any` — capitalised, as Kotlin spells it. */
    fun isCcekType(kind: String): Boolean = kind.firstOrNull()?.isUpperCase() == true

    /**
     * A TYPE VARIABLE — `T` — on a pass-through node such as `coalesce<T>(a?: T, b: T): T`.
     * Per node: the first cable into any of its `T` ports fixes `T`, every other `T` port
     * must be that type, and a `T` output carries it ([LcncTypeCheck]). Exact, not generic.
     */
    fun isTypeVariable(kind: String): Boolean = kind.length == 1 && kind[0].isUpperCase()

    /**
     * A LITERAL is a source-less node whose output port is named by one of its
     * params — its value is authored, not computed (`json.value`, `list.pairs`,
     * `text.value`). Its type is what its value matches ([LcncFacts.refineLiteral]).
     */
    fun isLiteral(contract: LcncPortContract, outputPort: String): Boolean =
        contract.inputs.isEmpty() && outputPort in contract.outputs && outputPort in contract.params
}
