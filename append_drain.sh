cat << 'INNER_EOF' >> src/commonMain/kotlin/forge/doc/WorkDrain.kt

suspend fun drainSession9145925316375278666(store: JulesBoardStore) {
    val workId = "session:9145925316375278666"
    store.appendWork(workId, JulesCause.WorkDrained(
        workId = workId,
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId,
            "necromancer",
            "necromanced",
            ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            "superseded-by-review",
            "superseded-by-review",
            LexicalMemory(
                "Superseded necromanced work",
                "Superseded necromanced work",
                "Superseded via drain script."
            ),
            0L,
            null
        ),
        at = 0L
    ))
}
INNER_EOF
