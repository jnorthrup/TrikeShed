package borg.trikeshed.lcnc

import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.rdf.RdfGraph

object CasStoreKey : LcncServiceKey<CasStore>("CasStoreKey")
object KifKnowledgeBaseKey : LcncServiceKey<KifKnowledgeBase>("KifKnowledgeBaseKey")
object KifSinkKey : LcncServiceKey<(String) -> Unit>("KifSinkKey")
object RdfGraphProviderKey : LcncServiceKey<() -> RdfGraph>("RdfGraphProviderKey")
object ReteNetworkKey : LcncServiceKey<ReteNetwork>("ReteNetworkKey")
object LegalCitationsKey : LcncServiceKey<(String) -> List<Map<String, Any?>>>("LegalCitationsKey")
