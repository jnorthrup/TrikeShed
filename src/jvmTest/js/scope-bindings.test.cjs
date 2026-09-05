"use strict";
const {test}=require("node:test"),assert=require("node:assert/strict");
const fs=require("node:fs"),path=require("node:path"),vm=require("node:vm");
const patch=fs.readFileSync(path.resolve(__dirname,"../../commonMain/resources/web/patch.js"),"utf8");
const c=vm.createContext({CONTRACTS:{scope:{ins:["args?","when?"],outs:["returns"]}}});
vm.runInContext(patch.slice(patch.indexOf("function nodePorts("),patch.indexOf("function renderNodePorts(")),c);
const inlet={id:"in",type:"scope.in",params:{name:"text"}};
const outlet={id:"out",type:"scope.out",params:{name:"result"}};
const scope={id:"r1",type:"scope",children:[inlet,outlet]};

test("frame binding and yield paths connect actual child sockets without authoring wires",()=>{
  const wires=[{from:["source","returns"],to:["r1","args?"]}];
  const snapshot=JSON.stringify({scope,wires}),links=c.scopeBindingLinks([scope],wires);
  assert.equal(links.length,4);
  assert.equal(JSON.stringify(links[0].from),JSON.stringify(["r1","in","text"]));
  assert.equal(JSON.stringify(links[0].to),JSON.stringify(["in","out","value"]));
  assert.equal(links[1].kind,"argument");assert.match(links[1].label,/candidate.*precedence/);
  assert.equal(JSON.stringify(links[2].to),JSON.stringify(["r1","out","result"]));
  assert.equal(JSON.stringify(links[3].to),JSON.stringify(["r1","out","returns"]));
  assert.match(links[3].label,/not a constant/);
  assert.equal(JSON.stringify({scope,wires}),snapshot);
});
test("unwired args maps and guards do not invent a source",()=>{
  for(const wires of [[],[{from:["gate","value"],to:["r1","when?"]}]]){
    const links=c.scopeBindingLinks([scope],wires);
    assert.equal(links.filter(l=>l.kind==="argument").length,0);
    assert.match(links[0].label,/declaration.*runtime/);
  }
});
test("nested declarations stay in their immediate scope and projection is capped",()=>{
  const inner={id:"r2",type:"scope",children:[{id:"private",type:"scope.in",params:{name:"secret"}}]};
  const outer={...scope,children:[inlet,outlet,inner]};
  const links=c.scopeBindingLinks([outer,inner],[]);
  assert.equal(links.filter(l=>l.child.id==="private").length,1);
  assert.equal(links.find(l=>l.child.id==="private").scope.id,"r2");
  assert.equal(c.scopeBindingLinks([outer,inner],[],2).length,2);
});
test("return labels enumerate yields, not hardcoded result values",()=>{
  assert.equal(c.scopePortLabel(scope,"out","returns"),"returns {result}");
  assert.equal(c.scopePortLabel(scope,"in","args?"),"args? {bindings}");
  assert.equal(c.scopePortLabel(outlet,"in","value"),"value");
});
test("binding captions distinguish literal defaults, inherited bindings and yields",()=>{
  const seed={},emit={},el={querySelector:s=>s.endsWith(".seed")?seed:emit};
  c.refreshBindingCaption({...inlet,el});assert.equal(seed.textContent,"binding text");assert.equal(emit.textContent,"from enclosing bindings");
  c.refreshBindingCaption({...inlet,params:{name:"text",default:"hello"},el});assert.equal(emit.textContent,"default: hello");
  c.refreshBindingCaption({...outlet,_parentScope:scope,el});assert.equal(emit.textContent,"r1.returns.result");
});
