export function evidenceModel(data) {
  if (data?.schema !== 'trikeshed.rete-connections/v1') throw new Error('Not a Rete connection snapshot');
  if (!Array.isArray(data.facts?.rows) || !Array.isArray(data.trace?.receipts) || !Array.isArray(data.productions) || !Array.isArray(data.projections)) {
    throw new Error('Missing fact, production, admission or projection rows');
  }
  const nodes = new Map(), edges = [];
  const id = (kind, ...parts) => JSON.stringify([kind, ...parts]);
  function node(kind, parts, label, pointer, raw) {
    const key = id(kind, ...parts);
    if (!nodes.has(key)) nodes.set(key, {id:key, kind, label:String(label), pointer, raw});
    return key;
  }
  function edge(from, to, kind, pointer, raw) { edges.push({from, to, kind, pointer, raw}); }
  const terms = new Map();
  const ids = new Map();
  for (const [i, row] of (data.ontology?.nodes ?? []).entries()) {
    if (terms.has(row.name) || ids.has(row.preorderId) || !Number.isInteger(row.preorderId)) throw new Error('Invalid ontology identity');
    terms.set(row.name, node('term', [row.name], row.name, `/ontology/nodes/${i}`, row));
    ids.set(row.preorderId, row.name);
  }
  for (const row of data.ontology?.nodes ?? []) {
    for (const key of ['ancestorIds', 'descendantIds']) {
      if (!Array.isArray(row[key]) || row[key].some(n => !ids.has(n))) throw new Error('Bitset refers to an absent preorder ID');
    }
  }
  for (const [i, [child, parent]] of (data.ontology?.directEdges ?? []).entries()) {
    if (!terms.has(child) || !terms.has(parent)) throw new Error('Subclass endpoint is absent');
    edge(terms.get(child), terms.get(parent), 'asserted-subclass', `/ontology/directEdges/${i}`, [child, parent]);
  }
  for (const [i, p] of data.productions.entries()) node('production', [p.ruleId], p.ruleId, `/productions/${i}`, p);
  const facts = new Map();
  for (const [i, fact] of data.facts.rows.entries()) {
    const factId = node('fact', [fact.partition, fact.id], `${fact.partition}/${fact.id}`, `/facts/rows/${i}`, fact);
    facts.set(id('fact', fact.partition, fact.id), factId);
    const cid = node('content', [fact.versionCid], fact.versionCid, `/facts/rows/${i}/versionCid`, fact.versionCid);
    edge(factId, cid, 'current-version', `/facts/rows/${i}/versionCid`, fact.versionCid);
  }
  for (const [i, receipt] of data.trace.receipts.entries()) {
    const pointer = `/trace/receipts/${i}`;
    const activation = node('admission', [receipt.ordinal], `${receipt.activationId} #${receipt.ordinal}`, pointer, receipt);
    const production = node('production', [receipt.productionId], receipt.productionId, `${pointer}/productionId`, receipt.productionId);
    edge(production, activation, 'refraction-admitted', pointer, receipt);
    for (const [j, support] of receipt.supportCids.entries()) {
      const content = node('content', [support], support, `${pointer}/supportCids/${j}`, support);
      edge(content, activation, 'declared-support', `${pointer}/supportCids/${j}`, support);
    }
  }
  for (const [i, projection] of data.projections.entries()) {
    const factId = facts.get(id('fact', projection.partition, projection.id));
    if (!factId || projection.tracked !== true || projection.matchesFactSnapshot !== true) continue;
    for (const [j, tuple] of (projection.tuples ?? []).entries()) {
      if (tuple.held !== true) continue;
      const pointer = `/projections/${i}/tuples/${j}`;
      const tupleId = node('tuple', [tuple.kif], tuple.kif, pointer, tuple);
      edge(factId, tupleId, 'projected-and-held', pointer, tuple);
      for (const atom of new Set(tuple.atoms)) {
        if (terms.has(atom)) edge(tupleId, terms.get(atom), 'exact-atom-reference', `${pointer}/atoms`, tuple.atoms);
      }
    }
  }
  const sumoTerms = new Map(), sumoIds = new Map();
  for (const [i, row] of (data.classifier?.terms ?? []).entries()) {
    const pointer = `/classifier/terms/${i}`;
    const key = node('sumo', [row.name], row.name, pointer, row);
    sumoTerms.set(row.name, key);
    if (row.preorderId !== null) {
      if (!Number.isInteger(row.preorderId) || sumoIds.has(row.preorderId)) throw new Error('Invalid classifier class ID');
      sumoIds.set(row.preorderId, key);
    }
    if (terms.has(row.name)) edge(terms.get(row.name), key, 'same-term-name; separate-index', `${pointer}/name`, row.name);
  }
  for (const row of data.classifier?.terms ?? []) {
    for (const kind of ['ANCESTORS', 'DESCENDANTS', 'INSTANCES', 'DISJOINT']) {
      if (!Array.isArray(row.masks?.[kind]) || row.masks[kind].some(bit => !sumoIds.has(bit))) throw new Error('Invalid classifier bitset reference');
    }
  }
  for (const kind of ['domains', 'ranges']) {
    for (const [i, slot] of (data.classifier?.[kind] ?? []).entries()) {
      const from = sumoTerms.get(slot.predicate), to = sumoTerms.get(slot.class);
      if (!from || !to) throw new Error('Missing classifier slot term');
      edge(from, to, `${kind === 'domains' ? 'domain' : 'range'}${slot.subclass ? '-subclass' : ''}${slot.argument ? ' ' + slot.argument : ''}`, `/classifier/${kind}/${i}`, slot);
    }
  }
  const semanticTerms = new Map();
  function semanticTerm(name, pointer) {
    const term = node('semantic-term', [name], name, pointer, name);
    if (!semanticTerms.has(name)) {
      if (terms.has(name)) edge(term, terms.get(name), 'exact-term-reference', pointer, name);
      if (sumoTerms.has(name)) edge(term, sumoTerms.get(name), 'exact-term-reference', pointer, name);
      semanticTerms.set(name, term);
    }
    return term;
  }
  for (const [i, rule] of (data.semantic?.rules ?? []).entries()) {
    const pointer = `/semantic/rules/${i}`;
    const ruleId = node('eternal-rule', [rule.ruleCid], `${rule.antecedent} ${rule.copula} ${rule.consequent}`, pointer, rule);
    edge(semanticTerm(rule.antecedent, pointer + '/antecedent'), ruleId, 'rule-antecedent', pointer, rule);
    edge(ruleId, semanticTerm(rule.consequent, pointer + '/consequent'), 'rule-consequent', pointer, rule);
  }
  for (const [i, assertion] of (data.semantic?.assertions ?? []).entries()) {
    const pointer = `/semantic/assertions/${i}`;
    const signal = node('belief', [assertion.angular], `${assertion.subject}: ${assertion.relation}`, pointer, assertion);
    edge(signal, semanticTerm(assertion.subject, pointer + '/subject'), 'registered-subject', pointer, assertion);
  }
  for (const [i, firing] of (data.semantic?.firings ?? []).entries()) {
    const pointer = `/semantic/firings/${i}`;
    const fired = node('firing', [firing.firingCid], `${firing.antecedent} -> ${firing.consequent}`, pointer, firing);
    const rule = node('eternal-rule', [firing.ruleCid], firing.ruleCid, pointer + '/ruleCid', firing.ruleCid);
    const matched = node('belief', [firing.matchedAngular], firing.matchedSubject, pointer + '/matchedAngular', firing.matchedAngular);
    const consequent = node('belief', [firing.consequentAngular], firing.consequent, pointer + '/consequentAngular', firing.consequentAngular);
    edge(rule, fired, 'observed-rule-firing', pointer, firing);
    edge(matched, fired, 'matched-assertion', pointer, firing);
    edge(fired, consequent, firing.resident ? 'offered; consequent-resident' : 'offered; consequent-not-resident', pointer, firing);
  }
  return {nodes, edges, terms, ids, sumoTerms, sumoIds};
}

export function incidentEvidence(model, selected, mode = 'direct') {
  const focus = model.nodes.get(selected);
  if (!focus) return [];
  if (mode === 'direct' || !['term', 'sumo'].includes(focus.kind)) return model.edges.filter(e => e.from === selected || e.to === selected);
  if (focus.kind === 'sumo') {
    const kind = {ancestors:'ANCESTORS', descendants:'DESCENDANTS', instances:'INSTANCES', disjoint:'DISJOINT'}[mode];
    return (focus.raw.masks[kind] ?? []).map((bit, i) => ({
      from:mode === 'descendants' ? model.sumoIds.get(bit) : selected,
      to:mode === 'descendants' ? selected : model.sumoIds.get(bit),
      kind:`${mode}-bit`, pointer:`${focus.pointer}/masks/${kind}/${i}`, raw:bit,
    }));
  }
  if (!['ancestors', 'descendants'].includes(mode)) return [];
  const key = mode === 'ancestors' ? 'ancestorIds' : 'descendantIds';
  return focus.raw[key].map((bit, i) => ({
    from:mode === 'ancestors' ? selected : model.terms.get(model.ids.get(bit)),
    to:mode === 'ancestors' ? model.terms.get(model.ids.get(bit)) : selected,
    kind:mode === 'ancestors' ? 'ancestor-bit' : 'descendant-bit',
    pointer:`${focus.pointer}/${key}/${i}`, raw:bit,
  }));
}
