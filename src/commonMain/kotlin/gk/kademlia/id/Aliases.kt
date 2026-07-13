package gk.kademlia.id

import borg.trikeshed.dht.id.NUID as BorgNUID
import borg.trikeshed.dht.id.WorkerNUID as BorgWorkerNUID
import borg.trikeshed.dht.id.ElectionNUID as BorgElectionNUID

typealias NUID<TNum> = BorgNUID<TNum>
typealias WorkerNUID = BorgWorkerNUID
typealias ElectionNUID = BorgElectionNUID
