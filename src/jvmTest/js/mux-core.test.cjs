const {test}=require('node:test'),assert=require('node:assert/strict');
const core=require('../../commonMain/resources/web/mux-core.js');
test('unknown and expired quota do not imply available numerical headroom',()=>{
  assert.equal(core.quota({limit:0,spent:42,usable:true,windowStartMs:100,windowMs:1000},200).remaining,null);
  assert.equal(core.quota({limit:100,spent:150,usable:false,windowStartMs:100,windowMs:1000},200).remaining,0);
  assert.equal(core.quota({limit:100,spent:100,exhausted:true,windowStartMs:100,windowMs:1000},2000).status,'refreshing');
});
test('local cache hits do not inflate provider token totals or latency',()=>{
  const t=core.totals([{status:'completed',startedAt:100,endedAt:1100,inputTokens:10,outputTokens:5},{status:'completed',startedAt:100,endedAt:101,cachedHit:true,inputTokens:999,outputTokens:999},{status:'failed',startedAt:100,endedAt:105}]);
  assert.equal(t.input+t.output,15);assert.equal(t.cached,1);assert.equal(t.medianMs,1000);assert.equal(t.successRate,2/3);
});
test('charts retain time boundaries and separate cache hits from completions',()=>{
  const bins=core.buckets([{status:'completed',endedAt:0},{status:'completed',endedAt:60000,cachedHit:true},{status:'failed',endedAt:1},{status:'completed',endedAt:-1},{status:'completed',endedAt:60001}],1,60000,2);
  assert.equal(bins[0].completed,1);assert.equal(bins[0].failed,1);assert.equal(bins[1].cached,1);assert.equal(bins[1].completed,0);
});
test('receipt merge respects process restarts and replaces active records',()=>{
  const calls=core.uniqueCalls([{id:1,startedAt:1,status:'running'},{id:1,startedAt:1,status:'completed'},{id:1,startedAt:5,status:'failed'}]);
  assert.equal(calls.length,2);assert.equal(calls[0].status,'completed');
});
test('session filters and cyclic ancestry are bounded',()=>{
  const sessions=[{id:1,parentId:2,title:'Parent',status:'running'},{id:2,parentId:1,title:'Child',status:'failed'},{id:3,title:'Other',status:'completed',archived:true}];
  assert.deepEqual([...core.descendants(sessions,1)],[1,2]);assert.equal(core.filterSessions(sessions,'','active').length,1);assert.equal(core.filterSessions(sessions,'other','archived').length,1);
});
