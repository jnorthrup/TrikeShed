package borg.trikeshed.lcnc

import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.agent.keymux.CouchKeyStore
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.ModelMux
import kotlin.coroutines.CoroutineContext

object BrainClientKey : LcncServiceKey<BrainClient>("BrainClientKey")
object MuxContextKey : LcncServiceKey<CoroutineContext>("MuxContextKey")
object ModelMuxProviderKey : LcncServiceKey<suspend () -> ModelMux?>("ModelMuxProviderKey")
object KeyMuxProviderKey : LcncServiceKey<suspend () -> KeyMux?>("KeyMuxProviderKey")
object CredentialStoreKey : LcncServiceKey<CouchKeyStore?>("CredentialStoreKey")
object TribunalDialogKey : LcncServiceKey<TribunalDialog>("TribunalDialogKey")
object TribunalRecordKey : LcncServiceKey<suspend (String) -> String>("TribunalRecordKey")
object CouncilDialogKey : LcncServiceKey<CouncilDialog>("CouncilDialogKey")
object CouncilRecordKey : LcncServiceKey<RecordSeams>("CouncilRecordKey")
