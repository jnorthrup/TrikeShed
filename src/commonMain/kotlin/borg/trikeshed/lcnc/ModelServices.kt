package borg.trikeshed.lcnc

import keymux.CouchKeyStore
import keymux.KeyMux
import modelmux.ModelMux

object ModelMuxProviderKey : LcncServiceKey<suspend () -> ModelMux?>("ModelMuxProviderKey")
object KeyMuxProviderKey : LcncServiceKey<suspend () -> KeyMux?>("KeyMuxProviderKey")
object CredentialStoreKey : LcncServiceKey<CouchKeyStore?>("CredentialStoreKey")
object TribunalDialogKey : LcncServiceKey<TribunalDialog>("TribunalDialogKey")
object TribunalIngestKey : LcncServiceKey<suspend (String) -> String>("TribunalIngestKey")
object CouncilDialogKey : LcncServiceKey<CouncilDialog>("CouncilDialogKey")
object CouncilRecordKey : LcncServiceKey<RecordSeams>("CouncilRecordKey")
