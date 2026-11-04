// Read-only final-capture audit. Complements, and does not replace, the preserved DATA_ONLY audit.
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const base=require('./audit.cjs');
const sha=bytes=>'sha256:'+crypto.createHash('sha256').update(bytes).digest('hex');
const json=bytes=>JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
function load(root){
 const read=name=>fs.readFileSync(path.join(root,name));
 return {root,data:base.load(root),browserBytes:read('browser-result.json'),browser:json(read('browser-result.json')),
  cleanupBytes:read('evidence-cleanup.json'),cleanup:json(read('evidence-cleanup.json')),
  reopenedBytes:read('reopened-cas-readbacks.json'),reopened:json(read('reopened-cas-readbacks.json')),
  dataAuditBytes:read('independent-audit/audit.json'),dataAudit:json(read('independent-audit/audit.json')),
  finalCbor:read('cas-readbacks/'+json(read('browser-result.json')).observations.inspection.receiptCid.slice(7)+'.cbor'),
  finalSource:read('cas-readbacks/'+json(read('browser-result.json')).observations.inspection.originalCid.slice(7)+'.txt')};
}
function audit(input){
 const dataReport=base.audit(input.data),recorded=input.dataAudit,expected={...recorded};
 delete expected.auditorCid;delete expected.negativeControls;delete expected.negativeControlScope;
 assert.deepEqual(dataReport,expected,'Preserved DATA_ONLY report no longer describes the same inputs');
 assert.equal(recorded.mode,'DATA_ONLY');assert.equal(recorded.browserCaptureCompletion,'NOT_ATTESTED');
 assert.equal(recorded.auditorCid,sha(fs.readFileSync(path.join(__dirname,'audit.cjs'))));
 const b=input.browser,o=b.observations,prior=input.data.stage2.observations,readonly=o.readonlyEvidencePass;
 assert.equal(b.result,'PASS');for(const key of Object.keys(prior))assert.deepEqual(o[key],prior[key],'Final capture changed prior observation '+key);
 assert.equal(o.continuation.priorProgressCid,dataReport.stage1ProgressCid);assert.equal(o.continuation.priorInspectionCid,dataReport.freshInspectionCid);
 assert.equal(readonly.priorProgressCid,dataReport.stage2ProgressCid);assert.equal(readonly.receiptCid,o.run.receiptCid);assert.equal(readonly.refreshes,1);
 assert.deepEqual(JSON.parse(readonly.sourceText),o.run.outputs.source,'Captured source facet differs from actual retained run output');
 assert.deepEqual(b.browserErrors,[]);assert.deepEqual(b.externalBrowserRequests,[]);assert(Array.isArray(b.newRequests)&&b.newRequests.length>0);
 const allowedPosts=b.newRequests.filter(r=>r.method==='POST');assert(allowedPosts.length>0);for(const request of b.newRequests){assert(['GET','POST'].includes(request.method));if(request.method==='POST')assert.equal(request.path,'/api/lcnc/workspace','Final capture attempted parser, query, run, upload or another effect');}
 assert.equal(b.writes.filter(w=>w.path==='/api/documents/english').length,1);assert.equal(b.writes.filter(w=>w.path==='/api/lcnc/run'&&w.body?.document).length,1);
 const evidence=b.evidence;for(const key of ['modelCalls','configuredModelCount','automaticIntake','reviewIntakeCalls','analysisReteFacts','analysisBlackboardEntries','continuationParserCalls'])assert.equal(evidence[key],0);
 assert.equal(evidence.parserCalls,1);assert.equal(evidence.inspectionCount,1);assert.equal(evidence.inspectionReceiptCid,dataReport.freshInspectionCid);assert.equal(evidence.sourceCid,dataReport.sourceCid);assert.equal(evidence.originalSourceRetained,true);for(const key of ['beliefBagPresent','reviewOwnerPresent','htxPresent'])assert.equal(evidence[key],false);
 assert.equal(evidence.networkDenyBefore.result,'DENIED');assert.equal(evidence.networkDenyAfter.result,'DENIED');
 const cleaned=input.cleanup;for(const key of ['wireDrained','kanbanDrained','scopesDrained','allSupervisorsCompleted','listenerStopped'])assert.equal(cleaned[key],true);assert.equal(cleaned.parserCalls,1);assert.equal(cleaned.continuationParserCalls,0);
 for(const key of ['parserCalls','continuationParserCalls','inspectionCount','modelCalls','configuredModelCount','automaticIntake','reviewIntakeCalls','analysisReteFacts','analysisBlackboardEntries'])assert.equal(cleaned.safetyAfterDrain[key],evidence[key]);
 assert.equal(sha(input.finalCbor),dataReport.freshInspectionCid);assert(input.finalCbor.equals(input.data.cborBytes));assert.equal(sha(input.finalSource),dataReport.sourceCid);assert(input.finalSource.equals(input.data.sourceBytes));
 const blockMap=new Map([[o.run.receiptCid,input.data.runBytes],[o.run.programCid,input.data.programBytes],[o.run.invocationsCid,input.data.invocationBytes]]),expectedRetained=new Set([dataReport.freshInspectionCid,...blockMap.keys()]);
 assert.deepEqual(new Set(evidence.retainedReadbacks.map(r=>r.receiptCid)),expectedRetained,'An unsaved workspace hash must not be treated as a stored CAS promise');
 const reopened=input.reopened;assert.equal(reopened.result,'PASS');assert.equal(reopened.inspectionCid,dataReport.freshInspectionCid);assert.equal(reopened.originalCid,dataReport.sourceCid);assert.equal(reopened.extractedTextCid,dataReport.sourceCid);assert.equal(reopened.sourceBytes,228);assert.equal(reopened.reusedOldInspection,false);
 assert.deepEqual(reopened.selectedSourceSnapshots,base.decodeCbor(input.finalCbor).sourceSnapshots);assert.equal(reopened.storedJsonReadbacks.length,3);
 for(const row of reopened.storedJsonReadbacks){assert(blockMap.has(row.cid));const stored=fs.readFileSync(path.join(input.root,'cas-readbacks',row.cid.slice(7)+'.json'));assert.equal(sha(stored),row.cid);assert(stored.equals(blockMap.get(row.cid)));assert.equal(row.bytes,stored.length);assert.equal(row.matchesActualContentResponse,true);}
 for(const record of evidence.retainedReadbacks){const block=record.receiptCid===dataReport.freshInspectionCid?input.finalCbor:blockMap.get(record.receiptCid);assert(block);assert.equal(record.bytes,block.length);assert.equal(sha(block),record.receiptCid);}
 return {result:'PASS',mode:'THREE_STAGE_RETAINED_EVIDENCE_CAPTURE',scope:'One actual fresh parser read, one retained current copular run, then a separate read-only native Evidence reopening with explicit Refresh. Not an uninterrupted UI walkthrough or a lifecycle fix.',
  preservedDataOnlyAuditCid:sha(input.dataAuditBytes),baseAuditorCid:recorded.auditorCid,stage1ProgressCid:dataReport.stage1ProgressCid,stage2ProgressCid:dataReport.stage2ProgressCid,
  finalBrowserResultCid:sha(input.browserBytes),finalCleanupCid:sha(input.cleanupBytes),afterCloseReopenReportCid:sha(input.reopenedBytes),freshInspectionCid:dataReport.freshInspectionCid,sourceCid:dataReport.sourceCid,
  sourceBytes:228,sourceTokens:dataReport.sourceTokens,fullParserNodes:dataReport.fullParserNodes,copularRows:3,totalActualParserReads:1,totalAuthoredRuns:1,continuationActualParserReads:0,finalCaptureNewParserQueryOrRunPosts:0,finalCaptureRefreshes:1,
  finalSourceAndInspectionBytesUnchanged:true,rawStoredJsonBlocksCompared:3,successfulContentJsonMatchesRawCas:true,finalOwnersDrained:true,
  limitations:['v68 Reading-workspace lifecycle defect remains unresolved; the later UI repair is separate work','All earlier gallery, drawer and exporter failures remain preserved; this is explicitly a staged capture','Final-stage POST /api/lcnc/workspace requests inspect current canvas state; their unsaved document hashes are not automatically CAS blocks','Copular syntax is not class membership, positive legal force, condition applicability or comprehension','The data audit verifies recorded outputs and byte identity, not parser execution authenticity from a CID alone','Fixture outgoing denial is recorded separately; this script does not exercise runtime network isolation']};
}
function controls(original){
 const cases=[['extra-parser-post',x=>x.browser.newRequests.push({path:'/api/documents/english',method:'POST'})],['extra-query-or-run-post',x=>x.browser.newRequests.push({path:'/api/lcnc/run',method:'POST'})],['captured-source-wrong-graph',x=>{const value=JSON.parse(x.browser.observations.readonlyEvidencePass.sourceText);value.x.namedGraph='urn:trikeshed:english:wrong';x.browser.observations.readonlyEvidencePass.sourceText=JSON.stringify(value);}],['after-close-source-row-mismatch',x=>{x.reopened.selectedSourceSnapshots[0].rev='invented-revision';}]];
 return cases.map(([name,mutate])=>{const changed={...original,browser:structuredClone(original.browser),cleanup:structuredClone(original.cleanup),reopened:structuredClone(original.reopened)};mutate(changed);let rejected=false;try{audit(changed);}catch(error){rejected=true;}assert(rejected,'Final negative control was not rejected: '+name);return {mutation:name,rejected:true};});
}
if(require.main===module){const root=path.resolve(process.argv[2]||path.join(__dirname,'..')),input=load(root),report=audit(input);report.finalAuditorCid=sha(fs.readFileSync(__filename));report.preservedBaseNegativeControls=base.negativeControls(input.data);report.finalCaptureNegativeControls=controls(input);report.negativeControlScope='In-memory copies only; all real proof files remain unchanged';process.stdout.write(JSON.stringify(report,null,2)+'\n');}
module.exports={load,audit,controls};
