import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

const root = '/Users/jim/work/TrikeShed';
const out = path.dirname(new URL(import.meta.url).pathname);
const common = 'src/commonMain/kotlin/borg/trikeshed/';
const jvm = 'src/jvmMain/kotlin/borg/trikeshed/';
const files = {
 daemon:jvm+'daemon/OroborosDaemon.kt', net:common+'dag/ReteNetwork.kt', wm:common+'dag/ReteWorkingMemory.kt',
 alpha:common+'dag/ReteAlphaMemory.kt', beta:common+'dag/ReteBetaMemory.kt', agenda:common+'dag/ReteAgenda.kt',
 refr:common+'dag/ReteRefraction.kt', prods:common+'dag/ReteProduction.kt', job:common+'dag/JobDependencyProduction.kt',
 board:jvm+'kanban/module/KanbanModule.kt', rules:common+'kanban/rules/BoardProductions.kt', stale:common+'lcnc/rules/RunStaleProduction.kt',
 couch:common+'couch/CouchChangesFactElement.kt', bb:common+'graal/BlackboardChangesFactElement.kt', ns:common+'graal/BlackboardNamespaces.kt',
 graal:jvm+'graal/vitals/GraalFactElement.kt', boardfact:common+'kanban/BoardFactElement.kt', panel:common+'lcnc/PanelFacts.kt',
 runfacts:common+'lcnc/LcncRunFacts.kt', supervisor:common+'job/JobSupervisorElement.kt', nexus:common+'job/JobNexusBindings.kt',
 tee:common+'dag/KifTee.kt', plane:common+'dag/PlaneFacts.kt', kb:common+'kif/Kif.kt',
 corpus:jvm+'ontology/SumoCorpus.kt', classifier:common+'ontology/SumoClassifier.kt', upper:common+'ontology/SumoOntology.kt',
 closure:common+'collections/bits/ClosureIndex.kt', roaring:common+'collections/bits/RoaringSeries.kt',
 curator:common+'narsese/CuratorImpulseElement.kt', recipient:common+'narsese/CuratorImpulseRecipient.kt',
 causal:common+'narsese/CausalityReteElement.kt', pure:common+'narsese/CausalityRete.kt', ruleNodes:common+'narsese/RuleNodes.kt',
 kg:common+'narsese/KgNalBridge.kt', construction:common+'narsese/ConstructionGrammar.kt',
 ledger:jvm+'narsese/NarsDurableLedger.kt', beliefwire:jvm+'forge/server/BeliefWire.kt',
 inline:common+'lcnc/KanbanCausalNodes.kt', review:common+'kanban/BoardReviewBridge.kt',
 lattice:common+'cursor/TypeSubsumption.kt', typedef:common+'parse/confix/TypeDefOracle.kt', oracle:common+'confix/ConfixOracleService.kt',
 registry:common+'memory/SkillRegistry.kt', select:common+'memory/SkillSelect.kt', zip:common+'ontology/zipper/OntoZipper.kt',
 agent:common+'dag/ReteAgent.kt', bridge:common+'dag/ReteCausalBridge.kt', dag:common+'dag/BlackboardDagCausalGraph.kt',
 index:common+'graph/CausalGraphNode.kt', landmark:common+'dag/CausalLandmark.kt', hyper:jvm+'graal/subvm/Hypervisor.kt',
 tap:common+'forge/blackboard/ReteFireSurfaceProjection.kt', panama:common+'panama/PanamaInduction.kt', movie:jvm+'forge/movie/PanamaKanbanMovie.kt',
 pub:jvm+'lcnc/LcncPublisher.kt', facts:common+'lcnc/LcncFacts.kt', wire:jvm+'forge/server/ReteWire.kt',
 rdf:jvm+'forge/server/LcncRdfWire.kt', blip:jvm+'forge/server/LcncBlipWire.kt', legal:jvm+'narsese/LegalNodes.kt',
 state:common+'narsese/StateNodes.kt', mcp:common+'mcp/SparqlKifMcpServer.kt', memory:common+'memory/MemoryStore.kt',
 cycl:common+'kif/CycLToKif.kt', cyc:common+'ontology/OpenCycOntology.kt', hermes:jvm+'hermes/HermesPenDelegates.kt'
};
const cache = new Map();
function ref(file, needle) {
 const p=files[file] || file;
 if(!cache.has(p)) cache.set(p,fs.readFileSync(path.join(root,p),'utf8').split('\n'));
 const lines=cache.get(p);
 const line=typeof needle==='number'?needle:lines.findIndex(x=>x.includes(needle))+1;
 if(line<1 || line>lines.length) throw Error('Missing evidence: '+p+' '+needle);
 return {file:p,line};
}
const groups = [
 ['inputs','Fact bridges'],['core','Rete engine'],['rules','Productions'],['belief','Belief causality'],
 ['sumo','SUMO bitsets'],['isa','ISA and memory'],['agent','Causal agents'],['read','Read surfaces']
].map(([id,label],i)=>({id,label,color:i%6+1}));
const nodes=[],edges=[];
function n(id,label,g,file,needle,note=''){nodes.push({id,label,g,ref:ref(file,needle),note});}
function e(a,b,label,file,needle,view,type='flow'){edges.push({id:'e'+edges.length,a,b,label,ref:ref(file,needle),view,type});}

n('couch','Couch changes','inputs','couch','class CouchChangesFactElement');
n('memory','MemoryStore','inputs','memory','class MemoryStore');
n('blackboard','ConfixBlackboard','inputs','daemon','val blackboardFacts');
n('bbbridge','Blackboard fact bridge','inputs','bb','class BlackboardChangesFactElement');
n('runtime','Graal runtime facts','inputs','graal','class GraalFactElement');
n('boardstore','BoardStore commits','inputs','board','store.committed.collect');
n('boardfacts','BoardFactElement','inputs','boardfact','class BoardFactElement');
n('clock','Board clock','inputs','boardfact','"now"');
n('panels','PanelFactBridge','inputs','panel','class PanelFactBridge');
n('publisher','LcncPublisher','inputs','pub','class LcncPublisher');
n('runs','LcncRunFacts','inputs','runfacts','class LcncRunFacts');
n('jobs','JobSupervisor','inputs','supervisor','class JobSupervisorElement','Owns an injected/default network; its job-board is absent from the sampled daemon fact plane.');
n('jobnet','Job ReteNetwork','core','supervisor','reteNetwork: borg.trikeshed.dag.ReteNetwork =','Distinct instance by default; never assumed to be the daemon network.');
n('net','Daemon ReteNetwork','core','daemon','val rete = borg.trikeshed.dag.ReteNetwork');
n('wm','Working memory','core','net','val workingMemory');
n('alpha','Alpha memory','core','net','val alphaMemory');
n('beta','Beta memory','core','net','val betaMemory','Equality join: dependsOn = jobId.');
n('interests','Interest counters','core','net','private val tracked');
n('registry','Production registry','core','net','val productions:');
n('refraction','Refraction','core','net','val refraction');
n('agenda','Agenda','core','net','val agenda');
n('sink','Production sink','core','board','ctx.rete.productionSink =');
n('jobcmd','Start / Block commands','core','net','if (cmd != null) output.send(cmd)');
n('jobrule','job-dependency','rules','job','class JobDependencyProduction');
for(const [id,label,cls] of [
 ['fan','fan-out','FanOutProduction'],['ready','dependency-ready','DependencyReadyProduction'],
 ['blocked','dependency-blocked','DependencyBlockedProduction'],['claim','claim','ClaimProduction'],
 ['wip','wip-breach','WipBreachProduction'],['stall','stall','StallProduction'],
 ['reaper','reaper','ReaperProduction'],['cycle','cycle-guard','CycleGuardProduction']
]) n(id,label,'rules','rules','class '+cls);
n('stale','run-stale','rules','stale','class RunStaleProduction');
n('constructrule','construction:<CID>','rules','construction','class ConstructionReteProduction','Dynamically registered by if_then constructions; absent from the sampled 10 productions.');
n('claimwork','BoardClaimWorker','rules','board','val claimWorker');
n('fanwork','BoardFanOutWorker','rules','board','val fanOutWorker');
n('receipts','Rule receipts','rules','board','"kanban/rule/${a.ruleId}');
n('staleout','Stale run marker','rules','board','LcncStaleMarker.merge');
n('review','BoardReviewBridge','belief','board','bridge?.onRuleFired(a)');
n('bag','BeliefBag','belief','causal','private val bag: BeliefBagElement');
n('terms','Term registry','belief','causal','private val terms:');
n('causal','CausalityReteElement','belief','causal','class CausalityReteElement');
n('eternal','Eternal rules','belief','pure','data class EternalRule');
n('match','CausalityRete alpha match','belief','pure','class CausalityRete');
n('firing','ReteFiring','belief','pure','data class ReteFiring');
n('mint','CAUSALITY mint','belief','causal','relation = RelationKind.CAUSALITY');
n('causalreceipt','Firing receipts','belief','daemon','"narsese/rete/firing/${firing.firingCid.hex}');
n('teach','Curator teach','belief','curator','class CuratorImpulseElement');
n('kg','KG / KIF / RDF bridge','belief','kg','object KgNalBridge');
n('admit','nal.rule.admit','belief','ruleNodes','fun ruleAdmitRunner');
n('fromkg','nal.rules.fromKg','belief','ruleNodes','fun rulesFromKgRunner');
n('durable','Rule + KIF ledgers','belief','ledger','object NarsDurableLedger');
n('construction','ConstructionReadingLoop','belief','construction','class ConstructionReadingLoop');
n('inline','nars.reteFire','belief','inline','val rete = CausalityRete','Runs a local pure matcher, separate from the daemon bag loop.');
n('tee','KifTee','sumo','tee','class KifTee');
n('plane','PlaneFacts projection','sumo','plane','object PlaneFacts');
n('kb','Shared KIF bank','sumo','daemon','val kifBank =');
n('kbclosure','KIF subclass closure','sumo','kb','private fun subclassClosure');
n('corpus','Pinned SUMO corpus','sumo','corpus','object SumoCorpus');
n('upper','SUMO upper spine','sumo','upper','object SumoOntology');
n('classifier','SumoClassifier','sumo','classifier','class SumoClassifier','SumoCorpus.pinned is lazy. No production caller of that property was found in commonMain/jvmMain.');
n('subclass','Subclass edges','sumo','classifier','val subclassEdges');
n('instance','Instance type sets','sumo','classifier','val instanceTypes =');
n('domain','Domain / range slots','sumo','classifier','val domainSlots');
n('disjoint','Disjoint masks','sumo','classifier','private fun disjointMask');
n('numbers','Number subtree','sumo','classifier','private fun numberIdsOf');
n('closure','ClosureIndex','sumo','closure','class ClosureIndex','Three separate callers: SumoClassifier, KifKnowledgeBase, IsALattice. Shared algorithm, distinct indexes.');
n('anc','Ancestor sets','sumo','closure','private val ancestors:');
n('desc','Descendant sets','sumo','closure','private val descendants:');
n('roaring','RoaringSeries','sumo','roaring','class RoaringSeries');
n('containers','Array / run / bitmap','sumo','roaring','class ArrayContainer');
n('classanswers','isA / domainOk / disjoint','sumo','classifier','fun isA(');
n('lattice','IsALattice','isa','lattice','class IsALattice');
n('typedef','TypeDefOracle','isa','typedef','val lattice    =');
n('oracle','ConfixOracleService','isa','oracle','row.lattice.isA');
n('skillreg','SkillRegistry','isa','registry','fun lattice()');
n('skillselect','SkillSelect','isa','select','OntoZipper.onBag');
n('zip','OntoZipper','isa','zip','class OntoZipper');
n('memindex','MemoryIndexLayer','isa','zip','val index: MemoryIndexLayer');
n('causalrank','Injected causal ranking','isa','zip','val causalRank:');
n('crumb','DerivationReceipt trail','isa','zip','fun crossTo(');
n('hermes','Hermes pen delegates','isa','hermes','private val planes:');
n('event','NodePlanning event','agent','bridge','fun project(');
n('bridge','ReteCausalBridge','agent','bridge','object ReteCausalBridge');
n('index','CausalGraphNodeIndex','agent','index','class CausalGraphNodeIndex');
n('nodefact','ReteFact.NodeFact','agent','bridge','val fact = ReteFact.NodeFact');
n('agent','ReteAgent.run','agent','agent','fun run(');
n('factagent','ReteAgent.runFacts','agent','agent','fun runFacts(');
n('agentfire','ReteAgent.Fire','agent','agent','data class Fire');
n('landmark','CausalLandmarkIndex','agent','landmark','class CausalLandmarkIndex','Optional agenda attachment API; no production call to attachLandmarkIndex was found.');
n('heat','SiteHeat observations','agent','hyper','rete.sink.trySend');
n('promote','leaf-promotion','agent','hyper','name = "leaf-promotion"');
n('lease','lease-budget','agent','hyper','name = "lease-budget"');
n('leaf','LeafTrainer.promote','agent','hyper','trainers[iso]?.promote');
n('revoke','Hypervisor.revoke','agent','hyper','"lease-budget" -> revoke');
n('pointcut','Pointcut blackboard','agent','hyper','PointcutBlackboardAdapter');
n('tap','ReteFireBoardTap','agent','tap','class ReteFireBoardTap');
n('tile','Board fire projection','agent','tap','object ReteFireSurfaceProjection');
n('panama','Panama induction','agent','panama',1,'Synthetic seven-node demonstration DAG; no real project interrogation.');
n('movie','PanamaKanbanMovie','agent','movie','graphIndex.bindAgent');
n('wire','ReteWire / RDF','read','wire','class ReteWire');
n('align','LCNC RDF align','read','rdf','class LcncRdfWire');
n('blip','LCNC blip','read','blip','class LcncBlipWire');
n('lcncfacts','LcncFacts','read','facts','class LcncFacts');
n('legal','legal.evidence','read','legal','fun evidenceRunner');
n('state','State snapshot / restore','read','state','object StateNodes');
n('query','Belief KIF query API','read','beliefwire','p == "/api/beliefs/query"');
n('mcp','SparqlKifMcpServer','read','mcp','class SparqlKifMcpServer','Defaults to its own upper-spine KIF bank, not the daemon shared instance.');
n('mcpkb','MCP KIF bank','read','mcp','private val kb:');
n('cycl','CycL / OpenCyc','read','cycl','object CycLToKif');

e('memory','couch','external subscriber contract for Couch mutation stream','memory',202,'inputs','optional');
e('couch','net','assert / modify / retract per database','couch',95,'inputs');
e('blackboard','bbbridge','queued key changes + namespace admission','bb','class BlackboardChangesFactElement','inputs');
e('bbbridge','net','assert / modify / retract: blackboard partition','bb',207,'inputs');
e('runtime','net','bounded state + event facts: graal partition','graal',338,'inputs');
e('boardstore','boardfacts','committed card snapshots','board','facts.onCommitted(ev)','inputs');
e('clock','boardfacts','kind=now tick','boardfact',76,'inputs');
e('boardfacts','net','card lifecycle / dependencies / time','boardfact',67,'inputs');
e('publisher','panels','program / node / cable / violation projection','pub','val panelFacts','inputs');
e('panels','net','diff assertions, modifications and removals','panel',211,'inputs');
e('runs','net','consumed document CIDs in project partitions','runfacts',55,'inputs');
e('jobs','jobnet','job-board fact channel','supervisor',114,'inputs');
e('jobnet','jobs','agenda -> command channel','supervisor',100,'inputs');
e('publisher','blackboard','published LCNC entries','pub','class LcncPublisher','inputs');
e('boardstore','blackboard','kanban/committed receipts','board',349,'inputs');

e('net','wm','serialized assert / modify / retract','net','val result = workingMemory.assert','core');
e('wm','alpha','accept current fact; retract removes membership','net','alphaMemory.accept(result.fact)','core');
e('wm','beta','accept both join sides; retract both sides','net','betaMemory.acceptLeft(result.fact)','core');
e('wm','interests','incremental field=value counts per partition','net','countInterests(board.id, null, fields)','core');
e('registry','interests','register interests; recount existing partitions','net','fun register(p: ReteProduction)','core');
e('interests','registry','gate evaluation in affected partition','net','if (!interested(p, partitionId)) continue','core');
e('registry','refraction','evaluate -> activation -> record support identity','net','if (refraction.record(a))','core');
e('wm','refraction','modify / retract invalidate old support CID','net','refraction.invalidateBySupport(oldFact.versionCid)','core');
e('wm','agenda','modify / retract remove stale activations','net','agenda.removeBySupport(oldFact.versionCid)','core');
e('refraction','agenda','start-job / block-job admitted activations','net','agenda.add(a)','core');
e('refraction','sink','all non-job admitted activations','net','productionSink?.invoke(a)','core');
e('agenda','jobcmd','pop activation and lower job command','net','if (cmd != null) output.send(cmd)','core');
e('jobcmd','jobs','Start / Block command intake','supervisor',100,'core');
e('jobnet','wm','same engine implementation; separate instance','supervisor',274,'core','optional');
e('net','registry','construction registers default job-dependency','net','register(JobDependencyProduction())','core');
e('landmark','agenda','optional differential heuristic ordering','net','agenda.landmarkIndex = index','core','optional');

e('registry','jobrule','default registration; salience 100','net','register(JobDependencyProduction())','rules');
e('wm','jobrule','submitted jobs and dependencies','job',35,'rules');
e('beta','jobrule','dependency equality-join tokens','job','jobTokens','rules');
e('jobrule','refraction','start-job if complete; block-job if failed','job',47,'rules');
for(const [id,needle] of [['fan','FanOutProduction()'],['ready','DependencyReadyProduction()'],['blocked','DependencyBlockedProduction()'],['claim','ClaimProduction()'],['wip','WipBreachProduction()'],['stall','StallProduction()'],['reaper','ReaperProduction('],['cycle','CycleGuardProduction()'],['stale','RunStaleProduction()']]) {
 e('registry',id,'KanbanModule registers production','board',needle,'rules');
 e(id,'refraction','emits activation with support CIDs',id==='stale'?'stale':'rules',id==='stale'?'class RunStaleProduction':'class '+({fan:'FanOut',ready:'DependencyReady',blocked:'DependencyBlocked',claim:'Claim',wip:'WipBreach',stall:'Stall',reaper:'Reaper',cycle:'CycleGuard'}[id])+'Production','rules');
}
for(const id of ['fan','ready','blocked','claim','wip','cycle']) e('boardfacts',id,'kind=card interest; joins current card state','rules','class '+({fan:'FanOut',ready:'DependencyReady',blocked:'DependencyBlocked',claim:'Claim',wip:'WipBreach',cycle:'CycleGuard'}[id])+'Production','rules');
for(const id of ['stall','reaper']) e('clock',id,'kind=now interest + card timestamps','rules','class '+(id==='stall'?'Stall':'Reaper')+'Production','rules');
e('runs','stale','kind=lcnc-consumed; compare recorded/current CID','stale',38,'rules');
e('couch','stale','current project document/index versions','stale',70,'rules');
e('sink','receipts','every activation -> kanban/rule/...','board',201,'rules');
e('sink','review','onRuleFired','board','bridge?.onRuleFired(a)','rules');
e('sink','boardstore','dependency-ready -> READY','board',219,'rules');
e('sink','boardstore','dependency-blocked -> BLOCKED','board',292,'rules');
e('sink','boardstore','reaper -> READY; third strike BLOCKED','board',317,'rules');
e('receipts','reaper','count prior strike receipts','board','priorStrikes =','rules');
e('sink','claimwork','claim -> asynchronous worker','board','claimWorker.claim(','rules');
e('claimwork','boardstore','RUNNING -> model/agent work -> REVIEW','board',168,'rules');
e('sink','fanwork','fan-out -> asynchronous worker','board','fanOutWorker.fanOut(','rules');
e('fanwork','boardstore','submit children, READY moves, dependency join','board',183,'rules');
e('sink','staleout','run-stale -> merge per-run marker','board','LcncStaleMarker.merge','rules');
e('receipts','blackboard','publish activation receipts','board',200,'rules');
e('receipts','bbbridge','kanban/rule/ excluded from fact admission','ns','Namespace("kanban/rule/"','rules','excluded');
e('staleout','bbbridge','lcnc/stale/ excluded from fact admission','ns','Namespace("lcnc/stale/"','rules','excluded');
e('constructrule','registry','dynamic construction:<CID> registration','construction',174,'rules');
e('wm','constructrule','concept equals construction subject','construction',187,'rules');
e('constructrule','refraction','subject / consequent activation','construction',189,'rules');

e('teach','kb','SUMO-grounded verdict assertions + durable tee','curator','knowledgeBank.assertNew(expr)','belief');
e('teach','bag','discounted signals via BeliefIntake.Mint','curator','bag.intake.send(','belief');
e('teach','terms','register angular -> subject / object','curator','rete?.register(','belief');
e('bag','causal','snapshot live beliefs','causal','val snapshot = bag.snapshot()','belief');
e('terms','causal','project only registered signal identities','causal','termsSnap[signal.angular] ?: continue','belief');
e('causal','match','250 ms fireLive -> pure matcher','daemon','causalityRete.fireLive()','belief');
e('eternal','match','antecedent alpha index; equivalence both ways','pure','alpha = index.mapValues','belief');
e('match','firing','term match -> discounted/floored support','pure','out.add(firing(rule, assertion, consequent))','belief');
e('firing','mint','seen-firing dedupe + derivation receipt','causal','if (!seenFirings.add(firing.firingCid)) continue','belief');
e('mint','bag','budgeted CAUSALITY potential intake','causal','bag.intake.send(','belief');
e('mint','terms','register consequent for later chaining','causal','register(consequentAngular,','belief');
e('firing','causalreceipt','firings SharedFlow collector','daemon','causalityRete.firings.collect','belief');
e('causalreceipt','blackboard','narsese/rete/firing/<CID>','daemon',923,'belief');
e('causalreceipt','bbbridge','firing receipts excluded from fact admission','ns','Namespace("narsese/rete/firing/"','belief','excluded');
e('admit','causal','union eternal rules; immutable matcher swap','ruleNodes','element.admit(offered.toSeries())','belief');
e('admit','durable','offered rules -> durability callback','ruleNodes','if (ledger != null)','belief');
e('kg','fromkg','interchange text -> EternalRules','ruleNodes','KgNalBridge.bridgeToRules','belief');
e('fromkg','causal','admit; report rejected temporal rules','ruleNodes',104,'belief');
e('causal','eternal','rules view of admitted matcher','causal','val rules: Series<EternalRule>','belief');
e('durable','causal','boot thaw Couch + JSONL rule union','daemon',1854,'belief');
e('durable','kb','boot thaw KIF assertions','daemon',1824,'belief');
e('teach','durable','new taught axiom ledger','curator','ledger?.let','belief');
e('construction','bag','CAS-checked evidence aggregation -> mint','construction',161,'belief');
e('construction','kb','(causes subject object) via kifSink','construction',168,'belief');
e('construction','constructrule','if_then -> register production','construction',169,'belief');
e('inline','match','local rule + assertions -> fire','inline',57,'belief');
e('review','bag','flush -> TurnReviewElement.reviewTurn -> observations','review','return review.reviewTurn(facts, succeeded)','belief');
e('upper','kg','emitSumoSpine -> EternalRule.fromKif','kg','fun emitSumoSpine','belief','optional');
e('cycl','kg','CycL -> KIF -> EternalRule','kg','fun cyclToEternalRules','belief','optional');

e('net','tee','observer on successful ASSERT / MODIFY / RETRACT','tee','net.observe','sumo');
e('wm','plane','fact fields / CID -> KIF tuples + RDF','plane','object PlaneFacts','sumo');
e('plane','tee','toKif(fact)','tee','val next = PlaneFacts.toKif','sumo');
e('tee','kb','replace old/new projection; retract removed facts','tee','bank.replace(gone, next)','sumo');
e('corpus','teach','Merge + Mid-level text as groundTheory','daemon','groundTheory = borg.trikeshed.ontology.SumoCorpus.text()','sumo');
e('upper','teach','fallback upper theory when corpus text blank','daemon','groundTheory = borg.trikeshed.ontology.SumoCorpus.text()','sumo','optional');
e('teach','kb','bootstrap ground theory once','curator','KifExpr.parseAll(groundTheory)','sumo');
e('kb','kbclosure','subclass query; cache invalidated by subclass changes','kb','val closure = subclassClosure()','sumo');
e('kbclosure','closure','build index over current subclass tuples','kb',228,'sumo');
e('closure','kbclosure','ancestorNodes -> transitive name pairs','kb',230,'sumo');
e('kbclosure','kb','closure candidates added to query bindings','kb','for ((child, parent) in closure)','sumo');
e('corpus','classifier','lazy pinned classifier parse; no production consumer found','corpus','val pinned: SumoClassifier','sumo','optional');
e('classifier','subclass','parse top-level subclass forms','classifier','"subclass" -> if','sumo');
e('subclass','closure','parent lists -> DFS preorder closure','classifier','val closure = ClosureIndex.build','sumo');
e('classifier','instance','explicit instance + implicit Class typing','classifier','val instanceTypes =','sumo');
e('closure','instance','union self + ancestor IDs for every direct type','classifier','directTypes[t].toRoaring().forEach','sumo');
e('classifier','domain','domain / domainSubclass / range / rangeSubclass','classifier','"domain", "domainSubclass" ->','sumo');
e('classifier','disjoint','disjoint / partition / disjointDecomposition pairs','classifier','"partition", "disjointDecomposition" ->','sumo');
e('closure','disjoint','inherit ancestor exclusions; close them downward','classifier','for (d in declaredDisjoint[a])','sumo');
e('classifier','numbers','literal leaves in the Number subtree','classifier','private fun numberIdsOf','sumo');
e('closure','numbers','close numeric leaf memberships upward','classifier','if (c >= 0) { acc.add(closure.id(c));','sumo');
e('closure','anc','compute proper ancestor sets','closure','fun ancestorIds','sumo');
e('closure','desc','compute proper descendant sets','closure','fun descendantIds','sumo');
e('anc','roaring','Roaring preorder-id set per node','closure','private val ancestors:','sumo');
e('desc','roaring','Roaring preorder-id set per node','closure','private val descendants:','sumo');
e('roaring','containers','choose smallest array / run / bitmap representation','roaring','runBytes < arrayBytes','sumo');
e('anc','classanswers','ancestor membership / subclassOf','classifier','return a >= 0 && b >= 0 && closure.isA','sumo');
e('instance','classanswers','class-as-subclass UNION instance types','classifier','return asClass or instanceTypes','sumo');
e('domain','classanswers','membership constraints and declared result class','classifier','fun domainOk(','sumo');
e('disjoint','classanswers','mask intersection','classifier','return disjointMask(ca).intersects','sumo');
e('numbers','classanswers','numeric slot membership','classifier','return numberIdsOf(literal).contains','sumo');
e('desc','classanswers','subclassesOf -> descendantIds','classifier','return namesOfIds(closure.descendantIds(c))','sumo');

e('typedef','lattice','CBOR type-definition parent edges','typedef','val lattice    =','isa');
e('skillreg','lattice','skill is-a declarations','registry','fun lattice()','isa');
e('lattice','closure','isA cache built from current edge series','lattice','val index = borg.trikeshed.collections.bits.ClosureIndex.build','isa');
e('closure','lattice','ancestor bit test for isA','lattice','return idx.index.isA','isa');
e('lattice','oracle','subtype query result','oracle','row.lattice.isA','isa');
e('lattice','zip','directSupers / directSubs traversal','zip','fun up():','isa');
e('lattice','skillselect','supertypes + sibling expansion','select','lattice.supertypes','isa');
e('bag','zip','recallNear Hamming-ball neighbors','zip','bag.recallNear','isa');
e('memindex','zip','queryByPath taxonomy descendants','zip','index.queryByPath','isa');
e('causalrank','zip','injected ranked causal neighbors','zip','val ranked = rank(workId, k)','isa');
e('crumb','zip','required evidence for crossTo plane jump','zip','fun crossTo(','isa');
e('zip','crumb','newest-first breadcrumb trail','zip','val crumb: Crumb','isa');
e('zip','skillselect','bag / taxonomy / causal walks','select','OntoZipper.onBag','isa');
e('hermes','skillselect','plane adapters provided to skill selection','hermes','val picks = selectSkills','isa','optional');

e('event','bridge','project NodePlanning event','bridge','fun project(','agent');
e('bridge','index','indexNodePlanning -> addOrGet','bridge','indexNodePlanning','agent');
e('bridge','nodefact','one NodeFact mirrors causal node','bridge','val fact = ReteFact.NodeFact','agent');
e('index','agent','bound agent channel on indexed causal nodes','index','boundAgent?.sink?.trySend(node)','agent');
e('agent','agentfire','node-planning rule transform callback','dag','transform = { n -> ReteAgent.Fire','agent');
e('heat','factagent','SiteHeat via runFacts channel','hyper','rete.sink.trySend','agent');
e('factagent','promote','count threshold + SELF_CONTAINED profile','hyper','f is ReteFact.SiteHeat && f.count >= promoteAfter','agent');
e('factagent','lease','count exceeds unrevoked call budget','hyper','name = "lease-budget"','agent');
e('promote','leaf','onFire -> promote method','hyper','trainers[iso]?.promote','agent');
e('lease','revoke','onFire -> revoke isolate','hyper','"lease-budget" -> revoke','agent');
e('promote','agentfire','record Fire','hyper','fires += fire','agent');
e('lease','agentfire','record Fire','hyper','fires += fire','agent');
e('leaf','pointcut','trainer transitions and delegate receipts','hyper','onTransition =','agent');
e('pointcut','blackboard','pointcut/<type>/<method>/<site> landing','ns','Namespace("pointcut/"','agent');
e('index','landmark','causal distance graph','landmark','class CausalLandmarkIndex','agent','optional');
e('agentfire','tap','bounded onFire queue','tap','fun onFire(','agent');
e('tap','tile','drain -> board section projection','tap','projection.project(drain()','agent');
e('panama','index','movie feeds synthetic DAG entries into index','movie','graphIndex.addOrGet(node)','agent','optional');
e('movie','agent','bind movie Rete agent to graph index','movie','graphIndex.bindAgent(reteAgent)','agent');

e('net','wire','snapshot -> JSON facts / RDF Turtle','wire','selection.select(network.snapshot())','read');
e('registry','wire','registered rule IDs, salience, interests','wire','network.productions.all()','read');
e('registry','align','join program terms with watched interests','daemon','productions = { reteProductions.all() }','read');
e('causal','align','join program terms with eternal rule terms','daemon','causalRules = {','read');
e('kb','align','KIF query bindings for program terms','daemon','facts = { pattern -> curatorImpulse','read');
e('net','blip','current fact snapshot','blip','network.snapshot()','read');
e('kb','blip','KIF binding query','daemon','kif = { pattern -> curatorImpulse','read');
e('registry','blip','production interests','daemon',1931,'read');
e('publisher','lcncfacts','vocabulary + program corpus + learned bindings','pub','val facts = LcncFacts.of','read');
e('lcncfacts','kb','assert LCNC vocabulary / bindings into shared bank','pub','into = kifBank ?:','read');
e('kb','legal','query document evidence and corpus','legal','fun evidenceRunner','read');
e('kb','state','snapshot knowledge bank','state','kif: KifKnowledgeBase','read');
e('state','kb','restore KIF assertions from CAS','state','private fun restoreKif','read');
e('kb','query','curator.queryBank results','beliefwire','c.queryBank(pattern)','read');
e('upper','mcpkb','bootstrapUpper into default MCP bank','mcp','fun bootstrapUpper','read');
e('mcpkb','mcp','KIF/SPARQL-like queries','mcp','private val kb:','read');
e('cycl','mcpkb','CycL / OpenCyc bootstrap projection','mcp','fun bootstrapUpper','read');

const nodeIds=new Set(nodes.map(n=>n.id));
for(const link of edges) if(!nodeIds.has(link.a)||!nodeIds.has(link.b)) throw Error('Unknown edge endpoint '+link.id);
const live = JSON.parse(execFileSync('curl',['--max-time','5','-sS','http://localhost:8888/api/rete/productions'],{encoding:'utf8'}));
const facts = JSON.parse(execFileSync('curl',['--max-time','5','-sS','http://localhost:8888/api/rete/facts'],{encoding:'utf8',maxBuffer:64*1024*1024}));
const parts = Object.entries(Object.groupBy(facts.facts,x=>x.partition)).map(([partition,rows])=>({partition,count:rows.length}));
const board = JSON.parse(execFileSync('curl',['--max-time','5','-sS','http://localhost:8888/blackboard/board'],{encoding:'utf8',maxBuffer:32*1024*1024}));
const runtime = {at:new Date().toISOString(),productions:live.productions,facts:facts.count,partitions:parts,blackboardRevision:board.revision,causalFiringReceipts:Object.keys(board.board).filter(k=>k.startsWith('narsese/rete/firing/')).length};

// Graphviz supplies edge routing; the browser retains readable, unscaled labels and scrolling.
function layout(ns,es) {
 const dot='digraph G { graph [rankdir=LR, nodesep=.28, ranksep=.6, pad=.16, bgcolor="transparent"]; node [shape=box,width=2.2,height=.66,fixedsize=true,label=""]; '+ns.map(n=>JSON.stringify(n.id)+';').join('')+es.filter(e=>e.type!=='excluded').map(e=>JSON.stringify(e.a)+' -> '+JSON.stringify(e.b)+' [id='+JSON.stringify(e.id)+'];').join('')+'}';
 const parsed=JSON.parse(execFileSync('dot',['-Tjson'],{input:dot,encoding:'utf8',maxBuffer:32*1024*1024}));
 const [,,w,h]=parsed.bb.split(',').map(Number);
 const coords=Object.fromEntries(parsed.objects.map(o=>{const [x,y]=o.pos.split(',').map(Number);return [o.name,{x,y:h-y}]}));
 const paths={};
 for(const edge of parsed.edges||[]) {
  const op=edge._draw_?.find(d=>d.op==='b');
  if(op) paths[edge.id]=op.points.map(([x,y])=>[x,h-y]);
 }
 return {w:w+24,h:h+24,nodes:coords,paths};
}
const layouts={};
for(const group of groups){const es=edges.filter(e=>e.view===group.id);const ids=new Set(es.flatMap(e=>[e.a,e.b]));layouts[group.id]=layout(nodes.filter(n=>ids.has(n.id)),es);}
layouts.all=layout(nodes,edges);
const data={root,groups,nodes,edges,layouts,runtime};
fs.writeFileSync(path.join(out,'connections.json'),JSON.stringify(data,null,2));
const template=fs.readFileSync(path.join(out,'graph-template.html'),'utf8');
const result=template.replace('/* GRAPH_DATA */',JSON.stringify(data).replaceAll('<','\\u003c'));
fs.writeFileSync(path.join(out,'rete-sumo-causal-graph.html'),result);
console.log(JSON.stringify({nodes:nodes.length,connections:edges.length,sourceFiles:cache.size,bytes:Buffer.byteLength(result),live:runtime},null,2));
