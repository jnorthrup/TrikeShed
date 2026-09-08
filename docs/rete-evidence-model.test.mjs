import test from 'node:test';
import assert from 'node:assert/strict';
import {evidenceModel, incidentEvidence} from './rete-evidence-model.mjs';

const snapshot = () => ({schema:'trikeshed.rete-connections/v1', facts:{rows:[{partition:'p', id:'f', versionCid:'cid'}]},
  productions:[{ruleId:'r'}], trace:{receipts:[{ordinal:1, activationId:'a', productionId:'r', supportCids:['cid']}]},
  projections:[{partition:'p', id:'f', tracked:true, matchesFactSnapshot:true, tuples:[{kif:'(concept f Child)', held:true, atoms:['concept','f','Child']}]}],
  ontology:{nodes:[{name:'Child', preorderId:1, ancestorIds:[0], descendantIds:[]}, {name:'Parent', preorderId:0, ancestorIds:[], descendantIds:[1]}], directEdges:[['Child','Parent']]}});

test('all edges have exact serialized evidence and valid endpoints', () => {
  const data = snapshot(), model = evidenceModel(data);
  assert.equal(model.edges.length, 6);
  for (const e of model.edges) {
    assert.ok(model.nodes.has(e.from) && model.nodes.has(e.to));
    const raw = e.pointer.slice(1).split('/').reduce((v, key) => v[key], data);
    assert.deepEqual(e.raw, raw);
  }
  const ancestor = incidentEvidence(model, model.terms.get('Child'), 'ancestors').at(0);
  assert.equal(ancestor.to, model.terms.get('Parent'));
  assert.equal(ancestor.raw, 0);
});

test('missing bank, stale tee and absent tuples never become projection edges', () => {
  for (const change of [p => p.tracked = false, p => p.matchesFactSnapshot = false, p => p.tuples[0].held = false]) {
    const data = snapshot(); change(data.projections[0]);
    assert.equal(evidenceModel(data).edges.filter(e => e.kind === 'projected-and-held').length, 0);
  }
  const data = snapshot(); data.ontology = null; data.projections = [];
  assert.equal(evidenceModel(data).terms.size, 0);
});

test('hand-authored graphs and dangling bitset references fail closed', () => {
  assert.throws(() => evidenceModel({nodes:[], edges:[]}), /Not a Rete/);
  const data = snapshot(); data.ontology.nodes[0].ancestorIds = [99];
  assert.throws(() => evidenceModel(data), /absent preorder/);
});
