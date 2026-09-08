package borg.trikeshed.lcnc

import borg.trikeshed.jules.BrainClient
import keymux.CouchKeyStore
import keymux.KeyMux
import modelmux.ModelMux
import kotlin.coroutines.CoroutineContext

object BrainClientKey : LcncServiceKey<BrainClient>("BrainClientKey")
object MuxContextKey : LcncServiceKey<CoroutineContext>("MuxContextKey")
object ModelMuxProviderKey : LcncServiceKey<suspend () -> ModelMux?>("ModelMuxProviderKey")
object KeyMuxProviderKey : LcncServiceKey<suspend () -> KeyMux?>("KeyMuxProviderKey")
object CredentialStoreKey : LcncServiceKey<CouchKeyStore?>("CredentialStoreKey")
object TribunalDialogKey : LcncServiceKey<TribunalDialog>("TribunalDialogKey")
object TribunalIngestKey : LcncServiceKey<suspend (String) -> String>("TribunalIngestKey")
object CouncilDialogKey : LcncServiceKey<CouncilDialog>("CouncilDialogKey")
object CouncilRecordKey : LcncServiceKey<RecordSeams>("CouncilRecordKey")
