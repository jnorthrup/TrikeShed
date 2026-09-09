(function(root,factory){const api=factory();if(typeof module==="object"&&module.exports)module.exports=api;else root.MuxCore=api;})(globalThis,()=>{
  "use strict";
  const active = new Set(["queued","running","joining"]);
  const number = value => Number.isFinite(Number(value)) ? Number(value) : 0;
  function uniqueCalls(calls){return [...new Map(calls.map(c=>[c.conversationId+":"+c.id+":"+c.startedAt,c])).values()];}
  function totals(calls){
    const result={attempts:calls.length,running:0,completed:0,failed:0,cancelled:0,cached:0,input:0,output:0,cacheRead:0,cacheWrite:0,medianMs:null};
    const latencies=[];
    for(const call of calls){
      if(call.status in result)result[call.status]++;
      if(call.cachedHit)result.cached++;
      else {result.input+=number(call.inputTokens);result.output+=number(call.outputTokens);result.cacheRead+=number(call.cacheReadTokens);result.cacheWrite+=number(call.cacheWriteTokens);}
      if(call.status==="completed"&&!call.cachedHit&&call.endedAt!=null)latencies.push(Math.max(0,call.endedAt-call.startedAt));
    }
    latencies.sort((a,b)=>a-b);
    if(latencies.length){const i=Math.floor(latencies.length/2);result.medianMs=latencies.length%2?latencies[i]:(latencies[i-1]+latencies[i])/2;}
    result.successRate=result.completed+result.failed ? result.completed/(result.completed+result.failed) : null;
    return result;
  }
  function quota(standing,now=Date.now()){
    if(!standing)return {known:false,remaining:null,used:null,resetMs:null,status:"unobserved"};
    const known=number(standing.limit)>0;
    const expired=number(standing.windowMs)>0&&now>=number(standing.windowStartMs)+number(standing.windowMs);
    return {known,remaining:known?Math.max(0,number(standing.limit)-number(standing.spent)):null,
      used:known?Math.min(1,Math.max(0,number(standing.spent)/number(standing.limit))):null,
      resetMs:Math.max(0,number(standing.windowStartMs)+number(standing.windowMs)-now),
      status:expired?"refreshing":standing.exhausted||standing.usable===false?"exhausted":"available"};
  }
  function buckets(calls,minutes,now=Date.now(),count=30){
    const span=minutes*60000,start=now-span,width=span/count;
    const bins=Array.from({length:count},(_,i)=>({at:start+i*width,completed:0,failed:0,cached:0}));
    for(const c of calls){const at=c.endedAt??c.startedAt;if(at<start||at>now)continue;
      const bin=bins[Math.min(count-1,Math.floor((at-start)/width))];
      if(c.cachedHit&&c.status==="completed")bin.cached++;else if(c.status==="completed")bin.completed++;else if(c.status==="failed")bin.failed++;
    }return bins;
  }
  function filterSessions(sessions,q,status){const search=q.toLowerCase();return sessions.filter(s=>{
    if(status==="archived"?!s.archived:s.archived)return false;
    if(status==="active"&&!active.has(s.status))return false;
    if(status==="failed"&&!["failed","partial","interrupted"].includes(s.status))return false;
    return [s.title,s.model,s.id].join(" ").toLowerCase().includes(search);
  });}
  function descendants(sessions,id){const found=new Set([id]);let changed=true;while(changed){changed=false;for(const s of sessions)if(!found.has(s.id)&&found.has(s.parentId)){found.add(s.id);changed=true;}}return found;}
  return {active,uniqueCalls,totals,quota,buckets,filterSessions,descendants};
});
