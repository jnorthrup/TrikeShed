package borg.trikeshed.lcnc

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchJobLog
import borg.trikeshed.job.*
import kotlinx.coroutines.CoroutineScope

/** Per-case commands and receipts borrow the caller's existing storage owners. */
fun councilCaseRegistry(scope: CoroutineScope, cas: CasStore, couch: Couch, cap: Int = 64): CouncilCaseRegistry {
    require(couch.cas === cas) { "Council lifecycle and Couch must share CAS ownership" }
    return CouncilCaseRegistry(scope, TribunalInstance.schemaPlan(), cap,
        componentFactories = { caseId -> JobNexusComponentFactories(
            casStoreFactory = CasStoreFactory { cas },
            walFactory = JobLogFactory {
                CouchJobLog.open(couch, "council-log/${ContentId.of(caseId.encodeToByteArray()).hex}")
            },
        ) })
}
