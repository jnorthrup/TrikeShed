cat << 'MERGE' > /tmp/merge_cas2.diff
<<<<<<< SEARCH
    fun put(doc: borg.trikeshed.parse.confix.ConfixDoc): ContentId {
=======
    override fun put(doc: borg.trikeshed.parse.confix.ConfixDoc): ContentId {
>>>>>>> REPLACE
<<<<<<< SEARCH
    open fun get(cid: ContentId): ByteArray? {
=======
    override fun get(cid: ContentId): ByteArray? {
>>>>>>> REPLACE
MERGE
patch -u src/commonMain/kotlin/borg/trikeshed/job/CasStore.kt -i /tmp/merge_cas2.diff
