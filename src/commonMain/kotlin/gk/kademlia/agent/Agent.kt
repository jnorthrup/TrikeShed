package gk.kademlia.agent

import borg.trikeshed.dht.agent.Agent as BorgAgent
import borg.trikeshed.dht.agent.WorldRouter as BorgWorldRouter
import borg.trikeshed.dht.agent.WorldNetwork as BorgWorldNetwork

typealias Agent<TNum, Sz> = BorgAgent<TNum, Sz>
typealias WorldRouter = BorgWorldRouter
typealias WorldNetwork = BorgWorldNetwork
