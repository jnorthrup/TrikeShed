package borg.trikeshed.og1.types

/* ── VerdictKind — drives FSM continuation ──────────────────────────── */
enum class VerdictKind {
    COMPLETE,  // Story done — advance to next story or QUORUM
    EDIT,      // More rounds needed — stay in DELIVER
    RESTART,   // Fundamental wrong — reset worker goal
}

/* ── CodeLocation ──────────────────────────────────────────────────── */
data class CodeLocation(
    val path: String,
    val lines: String = "",
    val summary: String = "",
    val snippet: String = "",
) {
    fun render(): String {
        val parts = buildList<String> {
            if (path.isNotEmpty()) add(path)
            if (lines.isNotEmpty()) add("[$lines]")
        }
        val s = parts.joinToString(" ")
        return if (summary.isNotEmpty()) "$s — $summary" else s
    }
}

/* ── CriticVerdict ─────────────────────────────────────────────────── */
data class CriticVerdict(
    val verdict: VerdictKind = VerdictKind.EDIT,
    val summary: String = "",
    val feedback: String = "",
    val demands: List<String> = emptyList(),
    val critique: String = "",
    val reason: String = "",
    val guidance: String = "",
    val score: Int? = null,
    val validatedLocations: List<CodeLocation> = emptyList(),
    val rejectedLocations: List<CodeLocation> = emptyList(),
    val raw: String = "",
)

/* ── DeliveryRound — one worker+critic exchange ────────────────────── */
data class DeliveryRound(
    val roundNumber: Int,
    val workerGoal: String,
    val workerResult: String,
    val criticGoal: String,
    val criticResult: String,
    val verdict: CriticVerdict,
)

/* ── Story ─────────────────────────────────────────────────────────── */
data class Story(
    val id: String,
    val name: String,
    val description: String,
    val dependencies: List<String> = emptyList(),
    val acceptance: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id, "name" to name, "description" to description,
        "dependencies" to dependencies, "acceptance" to acceptance,
    )

    companion object {
        fun fromMap(data: Map<String, Any?>): Story = Story(
            id = data["id"]?.toString() ?: "",
            name = data["name"]?.toString() ?: "unnamed",
            description = data["description"]?.toString() ?: "",
            dependencies = (data["dependencies"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            acceptance = (data["acceptance"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
        )
    }
}

/* ── FanoutPlan ────────────────────────────────────────────────────── */
data class FanoutPlan(
    val task: String,
    val stories: List<Story> = emptyList(),
    val completed: Set<String> = emptySet(),
    val critiqueHistory: List<String> = emptyList(),
) {
    val isComplete: Boolean get() = stories.isNotEmpty() && completed.containsAll(stories.map { it.id })
    val remainingStories: List<Story> get() = stories.filter { it.id !in completed }
    val readyStories: List<Story> get() = remainingStories.filter { story ->
        story.dependencies.all { dep -> dep in completed }
    }

    fun withStoryCompleted(storyId: String): FanoutPlan =
        copy(completed = completed + storyId, critiqueHistory = critiqueHistory)

    fun withCritique(critique: String): FanoutPlan =
        copy(critiqueHistory = critiqueHistory + critique)

    companion object {
        fun fromMap(data: Map<String, Any?>): FanoutPlan {
            val raw = data["stories"] as? List<*> ?: return FanoutPlan(task = data["task"]?.toString() ?: "")
            val stories = raw.mapNotNull { (it as? Map<*, *>)?.let { m -> Story.fromMap(m.mapKeys { e -> e.key.toString() }.mapValues { e -> e.value }) } }
            return FanoutPlan(
                task = data["task"]?.toString() ?: "",
                stories = stories,
                completed = (data["completed"] as? List<*>)?.mapNotNull { it?.toString() }?.toSet() ?: emptySet(),
                critiqueHistory = (data["critiqueHistory"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
            )
        }
    }
}

/* ── Validate story dependency graph ───────────────────────────────── */
fun validateStoryDependencies(stories: List<Story>): Boolean {
    val ids = stories.map { it.id }.toSet()
    if (ids.isEmpty()) return false
    if (stories.any { it.id.isEmpty() }) return false
    for (s in stories) {
        for (d in s.dependencies) if (d !in ids || d == s.id) return false
    }
    val graph = stories.associate { it.id to it.dependencies.toSet() }
    val visited = mutableSetOf<String>()
    val stack = mutableSetOf<String>()
    fun dfs(node: String): Boolean {
        if (node in stack) return false
        if (node in visited) return true
        stack.add(node)
        for (dep in graph[node] ?: emptySet()) if (!dfs(dep)) return false
        stack.remove(node); visited.add(node); return true
    }
    return ids.all { dfs(it) }
}

/* ── Branch / Brainstorm types ─────────────────────────────────────── */

enum class BranchStatus { EXPLORING, DONE }
enum class QuestionType { ASK_TEXT, ASK_CHOICE, ASK_MULTIPLE }

data class Answer(
    val text: String,
    val confidence: Float = 1f,
    val source: String = "",
)

data class BranchQuestion(
    val id: String = generateOg1Id(),
    val type: QuestionType = QuestionType.ASK_TEXT,
    val text: String = "",
    val answer: Answer? = null,
    val answeredAt: Double? = null,
)

data class Branch(
    val id: String = generateOg1Id(),
    val scope: String = "",
    val status: BranchStatus = BranchStatus.EXPLORING,
    val questions: List<BranchQuestion> = emptyList(),
    val finding: String? = null,
) {
    val isDone: Boolean get() = status == BranchStatus.DONE
    val hasUnanswered: Boolean get() = questions.any { it.answer == null }

    fun withQuestion(question: BranchQuestion): Branch = copy(questions = questions + question)
    fun withAnswer(questionId: String, answer: Answer, answeredAt: Double): Branch =
        copy(questions = questions.map { q ->
            if (q.id == questionId) q.copy(answer = answer, answeredAt = answeredAt) else q
        })
    fun withComplete(finding: String): Branch = copy(status = BranchStatus.DONE, finding = finding)
}

data class BrainstormState(
    val sessionId: String = generateOg1Id(),
    val browserSessionId: String? = null,
    val request: String = "",
    val createdAt: Double = currentTimeSeconds(),
    val updatedAt: Double = currentTimeSeconds(),
    val branches: Map<String, Branch> = emptyMap(),
    val branchOrder: List<String> = emptyList(),
) {
    val isComplete: Boolean get() = branches.values.all { it.isDone }
    val nextExploringBranch: Branch? get() = branchOrder.mapNotNull { branches[it] }.firstOrNull { !it.isDone }
    val branchCount: Int get() = branches.size
    val doneCount: Int get() = branches.values.count { it.isDone }

    fun withBranches(inputs: List<BranchInput>): BrainstormState {
        val newBranches = branches.toMutableMap()
        val newOrder = branchOrder.toMutableList()
        for (inp in inputs) {
            val branch = Branch(id = inp.id.ifEmpty { generateOg1Id() }, scope = inp.scope)
            newBranches[inp.id] = branch
            newOrder.add(inp.id)
        }
        return copy(branches = newBranches, branchOrder = newOrder, updatedAt = currentTimeSeconds())
    }

    fun withQuestion(branchId: String, question: BranchQuestion): BrainstormState {
        val branch = branches[branchId] ?: return this
        return copy(branches = branches + (branchId to branch.withQuestion(question)), updatedAt = currentTimeSeconds())
    }

    fun withAnswer(questionId: String, answer: Answer, answeredAt: Double = currentTimeSeconds()): BrainstormState {
        val newBranches = branches.mapValues { (_, branch) ->
            if (branch.questions.any { it.id == questionId }) branch.withAnswer(questionId, answer, answeredAt) else branch
        }
        return copy(branches = newBranches, updatedAt = answeredAt)
    }

    fun withBranchComplete(branchId: String, finding: String): BrainstormState {
        val branch = branches[branchId] ?: return this
        return copy(branches = branches + (branchId to branch.withComplete(finding)), updatedAt = currentTimeSeconds())
    }
}

data class BranchInput(
    val id: String = generateOg1Id(),
    val scope: String = "",
)

/* ── Utilities ─────────────────────────────────────────────────────── */
expect fun generateOg1Id(): String
expect fun currentTimeSeconds(): Double