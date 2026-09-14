(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const state = {records: [], runs: [], model: {configured: false}, extraction: {}, program: null, applicationId: '', edit: null, artifact: null, selectionBase: null, selectionDirty: false, recordDirty: false, artifactDirty: false, preparing: false, collisionTicket: 0, loaded: false};
  const labels = {evidence: 'evidence', source: 'source', employer: 'employer', listing: 'listing', contact: 'contact', representation: 'representation', application: 'application', artifact: 'artifact', review: 'review', action: 'action'};
  const categories = ['resume', 'experience', 'project', 'commentary', 'correction'];
  const schema = {
    evidence: [['title', 'Title', 'text', true], ['category', 'Category', categories], ['text', 'Evidence, context, or correction', 'textarea', true], ['correctionOf', 'Corrects evidence', '@evidence']],
    source: [['title', 'Source name', 'text', true], ['origin', 'Origin or upload', 'text'], ['url', 'Source URL', 'url'], ['mode', 'Retrieval method', ['http']], ['startAfter', 'Keep text after this exact marker', 'text'], ['endBefore', 'Stop before this exact marker', 'text'], ['replacements', 'Literal text corrections', 'replacements'], ['notes', 'Source notes', 'textarea']],
    employer: [['name', 'Employer name', 'text', true], ['domain', 'Website or domain', 'text'], ['notes', 'Notes', 'textarea']],
    listing: [['title', 'Job title', 'text', true], ['employerId', 'Saved employer', '@employer'], ['employer', 'Employer name', 'text'], ['requisition', 'Requisition', 'text'], ['url', 'Listing URL', 'url'], ['sourceId', 'Source', '@source'], ['contactId', 'Contact', '@contact'], ['text', 'Listing text', 'textarea', true]],
    contact: [['name', 'Contact name', 'text', true], ['role', 'Role', 'text'], ['email', 'Email', 'email'], ['phone', 'Phone', 'tel'], ['employerId', 'Employer', '@employer'], ['notes', 'Notes', 'textarea']],
    representation: [['employerId', 'Employer', '@employer'], ['listingId', 'Listing', '@listing'], ['contactId', 'Recruiter or contact', '@contact'], ['status', 'Status', ['active', 'released', 'expired']], ['startDate', 'Start date', 'date'], ['endDate', 'End date', 'date'], ['notes', 'Terms and scope of representation', 'textarea']],
    application: [['title', 'Working title', 'text'], ['listingId', 'Listing', '@listing', true], ['contactId', 'Contact', '@contact'], ['status', 'Status', ['research', 'preparing', 'ready', 'closed']], ['notes', 'Application notes', 'textarea']],
    action: [['applicationId', 'Application', '@application', true], ['type', 'Action', ['email', 'phone', 'application', 'note']], ['status', 'Record type', ['prepared', 'reported']], ['notes', 'What was prepared or actually done', 'textarea', true], ['reference', 'Supporting reference', 'text']]
  };
  function node(tag, cls, text) {
    const element = document.createElement(tag);
    if (cls) element.className = cls;
    if (text != null) element.textContent = String(text);
    return element;
  }
  function button(text, action, cls = 'quiet') {
    const b = node('button', cls, text); b.type = 'button'; b.addEventListener('click', action); return b;
  }
  function records(kind) { return state.records.filter(r => r.kind === kind); }
  function record(id) { return state.records.find(r => r.id === id); }
  function title(r) {
    if (!r) return 'Unavailable record';
    const f = r.fields || {};
    if (f.title || f.name) return f.title || f.name;
    if (r.kind === 'application') return title(record(f.listingId)) + ' · ' + (f.status || 'research');
    if (r.kind === 'representation') return [title(record(f.employerId || f.listingId)), f.status].filter(Boolean).join(' · ');
    return f.type || r.id;
  }
  function date(value) { const d = new Date(Number(value)); return Number.isFinite(d.getTime()) ? d.toLocaleString() : 'Date unavailable'; }
  function badge(text, cls = '') { return node('span', 'badge ' + cls, text); }
  function notice(message, error = false) {
    const el = $('notice'); el.hidden = !message; el.textContent = message || ''; el.classList.toggle('error', error);
  }
  function errorText(error) { return error?.message || 'The request could not be completed.'; }
  async function api(path, body, secret = false) {
    const response = await fetch('/api/headhunter' + path, body === undefined ? {cache: 'no-store'} : {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    let value;
    try { value = await response.json(); } catch (_) { throw Error(secret ? 'Source access could not be saved.' : 'The job agent returned an unreadable response. Refresh or check the service.'); }
    if (!response.ok || value?.ok === false) {
      const message = response.status === 409 ? 'This record changed after you opened it. Your edits are still here. Review the latest version before saving again.' : secret ? 'Source access could not be saved. Check the source and origin.' : (typeof value?.error === 'string' ? value.error : typeof value?.message === 'string' ? value.message : 'Request failed (' + response.status + ').');
      const error = Error(message); if (!secret) error.response = value; throw error;
    }
    return value;
  }
  function upsert(value) {
    const r = value?.record || value;
    if (!r?.id || !r.kind || !r.fields || !r.cid) throw Error('The service did not return a saved record.');
    const index = state.records.findIndex(item => item.id === r.id);
    if (index < 0) state.records.unshift(r); else state.records[index] = r;
    return r;
  }
  async function save(kind, fields, previous) { return upsert(await api('/record', {kind, ...(previous ? {id: previous.id, baseCid: previous.cid} : {}), fields})); }
  async function busy(target, action) {
    const controls = target.matches('button') ? [target] : [...target.querySelectorAll('button,input,select,textarea')];
    const previous = controls.map(control => control.disabled); target.setAttribute('aria-busy', 'true');
    try { const pending = action(); controls.forEach(control => control.disabled = true); return await pending; }
    finally { controls.forEach((control, i) => control.disabled = previous[i]); target.removeAttribute('aria-busy'); }
  }
  function option(select, value, text) { const o = node('option', '', text); o.value = value; select.append(o); }
  function options(select, kind, blank, selected = select.value) {
    select.replaceChildren(); option(select, '', blank);
    records(kind).forEach(r => option(select, r.id, title(r)));
    if (selected && !records(kind).some(r => r.id === selected)) option(select, selected, 'Unavailable record · ' + selected);
    select.value = selected;
  }
  function go(section) { location.hash = section; }
  function showSection() {
    const name = location.hash.slice(1).split('?')[0];
    const section = ['applications', 'evidence', 'records', 'sources', 'program'].includes(name) ? name : 'applications';
    document.querySelectorAll('.hh-section').forEach(el => el.hidden = el.id !== section);
    document.querySelectorAll('[data-section]').forEach(a => { if (a.dataset.section === section) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
  }
  function empty(parent, heading, detail, action) {
    const el = node('div', 'empty'); el.append(node('h3', '', heading), node('p', 'small', detail));
    if (action) { const row = node('div', 'actions'); row.append(action); el.append(row); }
    parent.append(el);
  }
  function recordCard(r) {
    const card = node('article', 'record-card');
    card.append(badge(r.fields.category || r.fields.status || r.kind), node('h3', '', title(r)));
    const excerpt = r.fields.text || r.fields.notes || r.fields.url || r.fields.email || '';
    if (excerpt) card.append(node('p', 'record-preview', excerpt));
    if (r.kind === 'source' && r.fields.lastCapture) { const extraction = r.fields.lastCapture; card.append(badge(extraction.status || 'captured', extraction.status === 'ready' ? '' : 'warning')); if (extraction.error) card.append(node('p', 'small error-text', extraction.error)); }
    const related = ['employerId', 'listingId', 'contactId', 'applicationId', 'sourceId', 'correctionOf'].filter(key => r.fields[key]);
    if (related.length) card.append(node('p', 'record-meta', related.map(key => title(record(r.fields[key]))).join(' · ')));
    card.append(node('p', 'record-meta', 'Updated ' + date(r.updatedAtMs)));
    const actions = node('div', 'actions');
    actions.append(button('Edit', () => openRecord(r.kind, r)), button('History', () => openRecord(r.kind, r, true)));
    if (r.kind === 'listing') actions.append(button('Create application', () => openRecord('application', null, false, {listingId: r.id}), 'primary'));
    if (r.kind === 'application') actions.append(button('Prepare', () => selectApplication(r.id)));
    if (r.kind === 'source') actions.append(button('Use saved source', () => {
      $('capture-source').value = r.id; $('capture-form').elements.inputMode.value = 'saved';
      if (['evidence', 'listing'].includes(r.fields.captureKind)) $('capture-kind').value = r.fields.captureKind;
      if (categories.includes(r.fields.category)) $('capture-category').value = r.fields.category;
      captureMode(); go('sources'); $('capture-form').scrollIntoView({block: 'start'});
    }));
    card.append(actions); return card;
  }
  function renderRecords() {
    document.querySelectorAll('[data-count]').forEach(el => el.textContent = String(records(el.dataset.count).length));
    const evidence = $('evidence-records'); evidence.replaceChildren();
    records('evidence').forEach(r => evidence.append(recordCard(r)));
    if (!evidence.children.length) empty(evidence, 'Your experience belongs here', 'Import a document or write a note. Your saved evidence will be available for selection in every application.', button('Import evidence', () => capture('evidence'), 'primary'));
    const kind = $('record-kind').value, search = $('record-search').value.toLowerCase();
    $('new-record').textContent = 'New ' + labels[kind];
    const list = $('record-list'); list.replaceChildren();
    records(kind).filter(r => JSON.stringify(r.fields).toLowerCase().includes(search)).forEach(r => list.append(recordCard(r)));
    if (!list.children.length) empty(list, search ? 'No matching records' : 'No ' + labels[kind] + ' records yet', search ? 'Try a different name or clear the search.' : 'Add the details you have. Link related records as you learn more.');
    const sources = $('source-records'); sources.replaceChildren(); records('source').forEach(r => sources.append(recordCard(r)));
    if (!sources.children.length) sources.append(node('p', 'small muted', 'No saved sources. Add one to keep its origin and access configuration together.'));
    options($('capture-source'), 'source', 'No saved source'); options($('credential-source'), 'source', 'Choose a source'); options($('capture-contact'), 'contact', 'No contact');
    options($('application-select'), 'application', 'Choose an application', state.applicationId);
    $('welcome').hidden = records('application').length > 0;
    $('extraction-status').textContent = state.extraction.documents === true ? 'Document extraction is available for PDF, DOCX, HTML, and other supported documents.' : state.extraction.documents === false ? 'Plain text import is available. Document extraction is unavailable; failed parsing retains the source for a later attempt.' : 'Document extraction availability has not been reported by the service.';
  }
  function applicationInputs() {
    const application = record(state.applicationId);
    return application ? {applicationId: application.id, evidenceIds: Array.isArray(application.fields.evidenceIds) ? application.fields.evidenceIds : [], instructions: application.fields.instructions || ''} : null;
  }
  function selectedInputs() {
    const runId = $('run-select').value;
    if (!runId) return applicationInputs();
    const inputs = state.runs.find(run => run.runId === runId)?.inputs;
    return inputs?.request || inputs || null;
  }
  function renderModel() {
    $('model-status').textContent = (state.model.configured ? 'Model configured. ' : 'Model preparation unavailable. ') + (state.model.detail || (state.model.configured ? 'Availability is checked when invoked.' : 'Check credential presence in KeyMux and model routing in ModelMux.'));
    $('model-status').className = 'small ' + (state.model.configured ? 'success-text' : 'muted');
    $('prepare-model').disabled = !state.model.configured || state.preparing || !state.applicationId;
    $('prepare-assemble').disabled = state.preparing || !state.applicationId;
    $('rerun-model').disabled = !state.model.configured || state.preparing || !selectedInputs();
    $('rerun-assemble').disabled = state.preparing || !selectedInputs();
    [$('application-select'), $('prepare-instructions'), $('save-selection'), ...$('evidence-selection').querySelectorAll('input')].forEach(control => control.disabled = state.preparing);
  }
  function renderApplication(resetChoices = false) {
    const app = record(state.applicationId); $('application-workspace').hidden = !app;
    if (!app) { renderProgram(); renderModel(); return; }
    const f = app.fields, listing = record(f.listingId), summary = $('application-summary'); summary.replaceChildren();
    summary.append(badge(f.status || 'research'), node('h3', '', title(listing)));
    const details = node('dl');
    [['Employer', title(record(listing?.fields.employerId)) === 'Unavailable record' ? listing?.fields.employer : title(record(listing?.fields.employerId))], ['Requisition', listing?.fields.requisition], ['Contact', f.contactId ? title(record(f.contactId)) : null]].forEach(([label, value]) => { if (value) details.append(node('dt', '', label), node('dd', '', value)); });
    summary.append(details);
    if (listing) { const text = node('details'); text.append(node('summary', '', 'Read listing'), node('p', 'artifact-preview', listing.fields.text || 'No listing text saved.')); summary.append(text, button('Edit listing', () => openRecord('listing', listing))); }
    else summary.append(node('p', 'error-text small', 'This application needs an available listing. Edit the application to choose one.'));
    if (f.notes) summary.append(node('p', 'record-preview', f.notes));
    if (resetChoices || !state.selectionDirty) {
      state.selectionBase = app;
      const selection = $('evidence-selection'); selection.replaceChildren();
      const ids = new Set(Array.isArray(f.evidenceIds) ? f.evidenceIds : []);
      records('evidence').forEach(e => {
        const row = node('div', 'selection'), label = node('label'), check = node('input'); check.type = 'checkbox'; check.value = e.id; check.checked = ids.has(e.id);
        check.addEventListener('change', () => { state.selectionDirty = true; });
        const words = node('span'); words.append(node('strong', '', title(e)), node('small', '', (e.fields.category || 'evidence') + ' · ' + (e.fields.text || '').slice(0,115)));
        label.append(check, words); row.append(label, button('Read', () => openRecord('evidence', e))); selection.append(row);
      });
      const missing = [...ids].filter(id => !record(id));
      if (missing.length) selection.append(node('p', 'error-text small', 'Unavailable evidence: ' + missing.join(', ') + '. Save a corrected selection before preparation.'));
      if (!records('evidence').length) selection.append(node('p', 'small muted', 'Add your evidence before preparing materials.'));
      $('prepare-instructions').value = f.instructions || ''; state.selectionDirty = false;
    }
    renderArtifacts(); renderActions(); renderProgram(); renderModel();
  }
  function selectApplication(id) {
    if (state.selectionDirty && id !== state.applicationId && !window.confirm('Discard unsaved preparation choices?')) { $('application-select').value = state.applicationId; return; }
    state.applicationId = id; state.selectionDirty = false; $('application-select').value = id;
    const url = new URL(location.href); if (id) url.searchParams.set('application', id); else url.searchParams.delete('application'); history.replaceState(null, '', url);
    $('run-summary').hidden = true; renderApplication(true); void loadCollisions(); go('applications');
  }
  async function saveSelection() {
    const app = state.selectionBase; if (!app || app.id !== state.applicationId) throw Error('Choose an application first.');
    const fields = {...app.fields, evidenceIds: [...$('evidence-selection').querySelectorAll('input:checked')].map(el => el.value), instructions: $('prepare-instructions').value};
    if (JSON.stringify(fields) !== JSON.stringify(app.fields)) await save('application', fields, app);
    state.selectionBase = record(state.applicationId); state.selectionDirty = false; renderProgram(); return applicationInputs();
  }
  async function loadCollisions() {
    const id = state.applicationId, ticket = ++state.collisionTicket, host = $('collisions');
    host.replaceChildren(node('p', 'small muted', id ? 'Checking saved records…' : 'Choose an application.'));
    if (!id) return;
    try { const result = await api('/collisions?applicationId=' + encodeURIComponent(id)); if (ticket === state.collisionTicket) renderCollisions(result.collisions || []); }
    catch (e) { if (ticket === state.collisionTicket) host.replaceChildren(node('p', 'error-text small', errorText(e))); }
  }
  function renderCollisions(collisions) {
    const host = $('collisions'); host.replaceChildren();
    if (!collisions.length) { host.append(node('p', 'small', 'No matching conflicts were returned for this application.')); return; }
    for (const match of collisions) {
      const el = node('article', 'collision'); el.append(node('strong', '', match.reason || 'Matching ' + (match.kind || 'record')));
      if (match.record) el.append(node('p', '', title(match.record)));
      for (const evidence of match.evidence || []) el.append(node('p', '', (evidence.field || 'Match') + ': ' + String(evidence.value ?? '')));
      const target = record(match.id) || match.record;
      if (target && schema[target.kind]) { const review = reviewFor(target); if (review) el.append(badge(review.fields.decision)); el.append(button('Review record', () => openRecord(target.kind, target))); }
      host.append(el);
    }
  }
  function renderActions() {
    const host = $('application-actions'); host.replaceChildren();
    const actions = records('action').filter(r => r.fields.applicationId === state.applicationId);
    for (const r of actions) { const item = node('div', 'activity-item'); item.append(badge(r.fields.status || 'recorded'), node('strong', '', r.fields.type || title(r)), node('p', 'record-preview', r.fields.notes || ''), node('p', 'record-meta', date(r.updatedAtMs)), button('View record', () => openRecord('action', r))); host.append(item); }
    if (!actions.length) host.append(node('p', 'small muted', 'No actions recorded for this application.'));
  }
  function reviewFor(r) {
    let latest;
    for (const review of records('review')) {
      if (review.fields.subjectId === r.id && review.fields.subjectCid === r.cid &&
          (!latest || Number(review.updatedAtMs) > Number(latest.updatedAtMs))) latest = review;
    }
    return latest;
  }
  function provenance(host, artifact) {
    const list = node('ul', 'sources-list');
    for (const source of artifact.fields.sources || []) {
      const item = node('li'), sourceRecord = record(source.id);
      const label = sourceRecord ? title(sourceRecord) : source.id || 'Unavailable evidence';
      if (sourceRecord && schema[sourceRecord.kind]) item.append(button(label, () => openRecord(sourceRecord.kind, sourceRecord, true, {}, source.cid)));
      else item.append(node('span', 'small muted', label));
      item.append(node('span', 'record-meta', ' · ' + String(source.cid || 'version unavailable') + (sourceRecord && source.cid !== sourceRecord.cid ? ' · newer version available' : ''))); list.append(item);
    }
    host.append(node('h4', '', 'Evidence references'));
    if (list.children.length) host.append(list); else host.append(node('p', 'small error-text', 'No evidence references are attached. Review this before use.'));
  }
  function renderArtifacts() {
    const host = $('artifacts'); host.replaceChildren();
    const artifacts = records('artifact').filter(r => r.fields.applicationId === state.applicationId)
      .sort((a, b) => Number(b.updatedAtMs) - Number(a.updatedAtMs));
    if (!artifacts.length) { empty(host, 'No prepared artifacts yet', 'Select evidence and run preparation. Saved materials and their sources will appear here.'); return; }
    for (const artifact of artifacts) {
      const f = artifact.fields, card = node('article', 'artifact-card'), review = reviewFor(artifact);
      card.append(badge(f.type || 'artifact'), badge(f.generation === 'model' ? 'Model draft' : 'Evidence assembly'), badge(review ? review.fields.decision : 'Needs review', review?.fields.decision === 'approved' ? '' : 'warning'), node('h3', '', title(artifact)));
      card.append(node('pre', 'artifact-preview', f.content || 'No content saved.')); provenance(card, artifact);
      const actions = node('div', 'actions'); actions.append(button('Edit & review', () => openArtifact(artifact), 'primary'));
      for (const format of ['markdown', 'html', 'text']) { const a = node('a', '', format === 'markdown' ? 'Markdown' : format === 'html' ? 'HTML' : 'Text'); a.href = '/api/headhunter/artifact?id=' + encodeURIComponent(artifact.id) + '&format=' + format; a.setAttribute('download', ''); actions.append(a); }
      card.append(actions); host.append(card);
    }
  }
  function renderProgram() {
    const p = state.program; $('open-program').disabled = !p?.name;
    $('program-name').textContent = p?.name || 'Composition unavailable'; $('program-document').textContent = p ? (typeof p.document === 'string' ? p.document : JSON.stringify(p.document, null, 2)) : 'The service did not return a program.';
    const nodes = $('program-nodes'); nodes.replaceChildren();
    let doc = p?.document; if (typeof doc === 'string') { try { doc = JSON.parse(doc); } catch (_) { doc = null; } }
    for (const n of doc?.nodes || []) { const el = node('div', 'program-node'); el.append(node('strong', '', n.label || n.title || n.type || n.id), node('span', 'muted', [n.id, n.type].filter(Boolean).join(' · '))); nodes.append(el); }
    const selected = $('run-select').value; $('run-select').replaceChildren(); option($('run-select'), '', 'Current application choices');
    state.runs.forEach(run => option($('run-select'), run.runId, date(run.startedAtMs) + ' · ' + (run.status || 'recorded'))); $('run-select').value = state.runs.some(r => r.runId === selected) ? selected : '';
    const inputs = selectedInputs(); $('program-inputs').textContent = inputs ? JSON.stringify(inputs, null, 2) : 'No application selected.';
    $('program-selection').textContent = inputs ? 'Saved application: ' + inputs.applicationId + ($('run-select').value ? ' · This replay uses the evidence versions pinned by that run.' : ' · Current saved choices use current evidence versions.') + (state.selectionDirty && !$('run-select').value ? ' Unsaved choices in Applications are not shown here.' : '') : 'Select an application in the Applications section.';
    $('rerun-assemble').disabled = !inputs || state.preparing; $('rerun-model').disabled = !inputs || !state.model.configured || state.preparing;
  }
  function runSummary(host, run) {
    host.replaceChildren(); host.hidden = false;
    host.append(badge(run.status || 'recorded'), node('p', '', 'Run ' + (run.runId || 'receipt unavailable')));
    if (run.error) host.append(node('p', 'error-text', typeof run.error === 'string' ? run.error : JSON.stringify(run.error)));
    if (run.receiptCid) host.append(node('p', 'record-meta', 'Receipt ' + run.receiptCid));
    const details = node('details'); details.append(node('summary', '', 'Run receipt'), node('pre', 'code', JSON.stringify(run, null, 2))); host.append(details);
  }
  async function prepare(mode, rerun = false) {
    if (state.preparing) return;
    notice(''); state.preparing = true; renderModel();
    try {
      const inputs = rerun ? selectedInputs() : await saveSelection();
      if (!inputs?.applicationId) throw Error('Choose a saved application first.');
      if (!Array.isArray(inputs.evidenceIds) || !inputs.evidenceIds.length) throw Error('Select at least one evidence record before preparation.');
      notice(mode === 'model' ? 'Preparing drafts with the configured model…' : 'Assembling selected evidence…');
      const result = await api('/prepare', {applicationId: inputs.applicationId, evidenceIds: inputs.evidenceIds, instructions: inputs.instructions || '', ...(rerun && inputs.versions ? {versions: inputs.versions} : {}), mode});
      if (!result.run) throw Error('Preparation returned no run receipt.');
      await refresh(false);
      if (state.applicationId !== inputs.applicationId) { state.applicationId = inputs.applicationId; state.selectionDirty = false; }
      renderRecords(); renderApplication(true); renderCollisions(result.collisions || []); runSummary($('run-summary'), result.run); runSummary($('program-run'), result.run);
      const status = String(result.run.status || '').toLowerCase();
      if (['failed', 'refused', 'cancelled', 'error'].includes(status) || result.run.ok === false) notice('Preparation ' + (status || 'failed') + '. Read the run receipt for the reason.', true);
      else notice('Preparation returned a run receipt. Review the saved artifacts and their evidence before use.');
    } catch (e) {
      if (e.response?.run) {
        runSummary($('run-summary'), e.response.run); runSummary($('program-run'), e.response.run);
        try { await refresh(); } catch (_) { /* The returned failure receipt remains available even if refresh fails. */ }
      }
      notice(errorText(e), true);
    }
    finally { state.preparing = false; renderModel(); }
  }
  async function refresh(render = true) {
    const data = await api('');
    if (!Array.isArray(data.records)) throw Error('The job agent did not return its record list.');
    state.records = data.records; state.runs = Array.isArray(data.runs) ? data.runs : []; state.model = data.model || {configured: false, detail: 'Model configuration is unavailable.'}; state.extraction = data.extraction || {}; state.program = data.program || null;
    if (!state.loaded) { const selected = new URLSearchParams(location.search).get('application'); state.applicationId = record(selected)?.kind === 'application' ? selected : records('application')[0]?.id || ''; state.loaded = true; }
    if (render) { renderRecords(); renderApplication(); renderProgram(); void loadCollisions(); }
  }
  function replacementRow(from = '', to = '') {
    const row = node('div', 'fields replacement-row');
    for (const [key, text, value] of [['from', 'Original text', from], ['to', 'Replacement text', to]]) { const label = node('label', 'field'), input = node('textarea'); input.rows = 2; input.dataset.replacement = key; input.value = value; label.append(node('span', '', text), input); row.append(label); }
    row.append(button('Remove correction', () => { row.remove(); state.recordDirty = true; })); return row;
  }
  function openRecord(kind, value, historyOpen = false, defaults = {}, historyCid) {
    if (!schema[kind]) return;
    const dialog = $('record-dialog'); if (dialog.open && state.recordDirty && !window.confirm('Discard unsaved record edits?')) return;
    state.edit = {kind, value, defaults, historyCid}; state.recordDirty = false;
    $('record-dialog-title').textContent = (value ? 'Edit ' : 'New ') + labels[kind]; $('record-message').textContent = '';
    const fields = {...(value?.fields || defaults)}; if (kind === 'source') Object.assign(fields, fields.extraction || {}); if (kind === 'action' && !fields.applicationId) fields.applicationId = state.applicationId;
    const host = $('record-fields'); host.replaceChildren();
    for (const [name, label, type, required] of schema[kind]) {
      if (type === 'replacements') {
        const wrapper = node('div', 'field wide'), list = node('div', 'stack'); list.id = 'source-replacements';
        wrapper.append(node('h4', '', label), node('p', 'small muted', 'Replace exact text after extraction. The original material stays available.'));
        for (const [from, to] of Object.entries(fields.replacements || {})) list.append(replacementRow(from, to));
        wrapper.append(list, button('Add text correction', () => { list.append(replacementRow()); state.recordDirty = true; })); host.append(wrapper); continue;
      }
      const wrapper = node('label', 'field' + (type === 'textarea' ? ' wide' : '')); wrapper.append(node('span', '', label + (required ? ' *' : '')));
      let input;
      if (Array.isArray(type) || type.startsWith('@')) {
        input = node('select');
        if (Array.isArray(type)) { type.forEach(v => option(input, v, v === 'http' ? 'HTTP' : v.replaceAll('-', ' '))); if (fields[name] && !type.includes(fields[name])) option(input, fields[name], fields[name] + ' (unavailable)'); }
        else options(input, type.slice(1), 'Choose ' + labels[type.slice(1)], fields[name] || '');
      } else { input = node(type === 'textarea' ? 'textarea' : 'input'); if (type !== 'textarea') input.type = type; else input.rows = kind === 'evidence' || kind === 'listing' ? 10 : 4; }
      input.name = name; input.required = !!required; if (fields[name] != null) input.value = String(fields[name]); wrapper.append(input); host.append(wrapper);
    }
    $('record-history').hidden = !value; $('record-history').open = historyOpen; $('history-list').replaceChildren();
    $('record-neighbors').hidden = !value; $('record-neighbors').open = false; $('neighbor-records').replaceChildren();
    $('record-review').hidden = !value || !['listing', 'contact', 'representation', 'application', 'action'].includes(kind);
    $('record-review-notes').value = ''; $('record-review-decision').value = 'approved';
    if (value) void loadHistory(value.id);
    if (!dialog.open) dialog.showModal();
  }
  async function loadHistory(id) {
    $('history-list').replaceChildren(node('p', 'small muted', 'Loading versions…'));
    try {
      const result = await api('/history?id=' + encodeURIComponent(id)); if (state.edit?.value?.id !== id) return;
      const host = $('history-list'); host.replaceChildren();
      for (const r of result.versions || []) { const el = node('details', 'history-item'); el.open = r.cid === state.edit.historyCid; el.append(node('summary', '', date(r.updatedAtMs) + ' · ' + r.cid), node('pre', 'code', JSON.stringify(r.fields, null, 2))); host.append(el); }
      if (!host.children.length) host.append(node('p', 'small muted', 'No versions were returned.'));
    } catch (e) { if (state.edit?.value?.id === id) $('history-list').replaceChildren(node('p', 'error-text small', errorText(e))); }
  }
  async function loadNeighbors() {
    const id = state.edit?.value?.id; if (!id || !$('record-neighbors').open) return;
    const host = $('neighbor-records'); host.replaceChildren(node('p', 'small muted', 'Finding related facts…'));
    try {
      const response = await fetch('/blackboard/neighbors?key=' + encodeURIComponent('headhunter/' + id));
      if (!response.ok) throw Error('Related facts are unavailable (' + response.status + ').');
      const data = await response.json(); if (state.edit?.value?.id !== id) return;
      host.replaceChildren();
      for (const neighbor of data.neighbors || []) {
        const row = node('div', 'history-item'), related = record(String(neighbor.key).replace(/^headhunter\//, ''));
        row.append(node('strong', '', related ? title(related) : neighbor.key));
        if (neighbor.terms?.length) row.append(node('p', 'small muted', 'Shared text: ' + neighbor.terms.join(', ')));
        if (neighbor.concepts?.length) row.append(node('p', 'small muted', 'Shared concepts: ' + neighbor.concepts.join(', ')));
        for (const reference of neighbor.references || []) row.append(node('p', 'record-meta', 'Recorded reference: ' + reference.from + ' → ' + reference.to));
        if (related && schema[related.kind]) row.append(button('Review related record', () => openRecord(related.kind, related)));
        const link = node('a', 'small', 'Inspect relationships'); link.href = '/blackboard/neighbors?key=' + encodeURIComponent(neighbor.key); link.target = '_blank'; link.rel = 'noopener'; row.append(link); host.append(row);
      }
      if (!host.children.length) host.append(node('p', 'small muted', 'No supported related facts were returned.'));
    } catch (e) { if (state.edit?.value?.id === id) host.replaceChildren(node('p', 'small error-text', errorText(e))); }
  }
  async function saveRecord(event) {
    event.preventDefault(); const form = event.currentTarget, edit = state.edit;
    await busy(form, async () => {
      try {
        const fields = {...(edit.value?.fields || edit.defaults)};
        const values = new FormData(form); for (const [key, , type] of schema[edit.kind]) if (type !== 'replacements' && !(edit.kind === 'source' && ['startAfter', 'endBefore'].includes(key))) fields[key] = values.get(key) || '';
        if (edit.kind === 'source') {
          const extraction = {...(fields.extraction || {})};
          for (const key of ['startAfter', 'endBefore']) { const marker = values.get(key); if (marker) extraction[key] = marker; else delete extraction[key]; }
          const replacements = {};
          for (const row of $('source-replacements').children) {
            const from = row.querySelector('[data-replacement=from]').value, to = row.querySelector('[data-replacement=to]').value;
            if (!from && !to) continue;
            if (!from) throw Error('A text correction needs original text to match.');
            if (Object.hasOwn(replacements, from)) throw Error('Each text correction must have different original text.');
            Object.defineProperty(replacements, from, {value: to, enumerable: true, writable: true, configurable: true});
          }
          extraction.replacements = replacements; fields.extraction = extraction;
        }
        if (edit.kind === 'representation' && !fields.employerId && !fields.listingId) throw Error('Choose an employer or listing for this representation.');
        const result = await save(edit.kind, fields, edit.value); state.recordDirty = false; $('record-dialog').close();
        if (edit.kind === 'application') state.applicationId = result.id;
        renderRecords(); renderApplication(); void loadCollisions(); notice('Saved ' + title(result) + '.');
      } catch (e) { $('record-message').textContent = errorText(e); $('record-message').className = 'small error-text'; }
    });
  }
  function openArtifact(r) {
    if ($('artifact-dialog').open && state.artifactDirty && !window.confirm('Discard unsaved artifact edits?')) return;
    state.artifact = r; state.artifactDirty = false; $('artifact-dialog-title').textContent = title(r);
    const form = $('artifact-form'); form.elements.title.value = r.fields.title || title(r); form.elements.content.value = r.fields.content || '';
    $('artifact-provenance').replaceChildren(); provenance($('artifact-provenance'), r);
    $('artifact-message').textContent = ''; $('review-message').textContent = ''; $('review-form').reset();
    $('review-version').textContent = 'Review applies to saved version ' + r.cid + '. Save content changes before reviewing.';
    if (!$('artifact-dialog').open) $('artifact-dialog').showModal();
  }
  async function saveArtifact(event) {
    event.preventDefault(); await busy(event.currentTarget, async () => {
      try {
        const form = event.currentTarget, r = state.artifact;
        const saved = await save('artifact', {...r.fields, title: form.elements.title.value, content: form.elements.content.value, reviewStatus: 'proposed'}, r);
        state.artifactDirty = false; openArtifact(saved); renderArtifacts(); $('artifact-message').textContent = 'Revision saved. Review this version before use.';
      } catch (e) { $('artifact-message').textContent = errorText(e); }
    });
  }
  async function saveReview(event) {
    event.preventDefault(); await busy(event.currentTarget, async () => {
      try {
        if (state.artifactDirty) throw Error('Save the artifact revision before reviewing it.');
        const form = event.currentTarget, artifact = state.artifact;
        await save('review', {subjectId: artifact.id, subjectCid: artifact.cid, applicationId: artifact.fields.applicationId, decision: form.elements.decision.value, notes: form.elements.notes.value});
        renderArtifacts(); $('review-message').textContent = 'Review saved for this artifact version.';
      } catch (e) { $('review-message').textContent = errorText(e); }
    });
  }
  function capture(kind) { $('capture-kind').value = kind; captureMode(); go('sources'); $('capture-form').scrollIntoView({block: 'start'}); }
  function captureMode() {
    const form = $('capture-form'), mode = form.elements.inputMode.value;
    form.querySelectorAll('[data-input]').forEach(el => { el.hidden = el.dataset.input !== mode; el.querySelectorAll('input,textarea').forEach(input => input.disabled = el.hidden); });
    $('capture-listing-fields').hidden = $('capture-kind').value !== 'listing';
    $('capture-category-field').hidden = $('capture-kind').value !== 'evidence';
  }
  function fileBase64(file) { return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]); reader.onerror = () => reject(Error('Could not read ' + file.name)); reader.readAsDataURL(file); }); }
  async function importMaterial(event) {
    event.preventDefault(); const form = event.currentTarget;
    await busy(form, async () => {
      $('capture-results').replaceChildren(); $('capture-status').textContent = 'Importing…'; $('capture-status').className = 'small';
      try {
        const values = Object.fromEntries(new FormData(form)), mode = form.elements.inputMode.value;
        const base = {kind: values.kind}; for (const field of ['sourceId', 'title', 'employer', 'requisition', 'contactId']) if (values[field] && (values.kind === 'listing' || !['employer', 'requisition', 'contactId'].includes(field))) base[field] = values[field];
        if (values.kind === 'evidence') base.category = values.category;
        const inputs = [];
        if (mode === 'file') {
          const files = [...form.elements.files.files]; if (!files.length) throw Error('Choose a document to import.');
          for (const file of files) { if (file.size > 2 * 1024 * 1024) throw Error(file.name + ' exceeds the 2 MiB browser upload limit.'); }
          for (const file of files) inputs.push({...base, filename: file.name, mediaType: file.type || undefined, base64: await fileBase64(file)});
        } else if (mode === 'saved') {
          const saved = record(base.sourceId);
          if (!saved || (!saved.fields.url && !saved.fields.originalCid)) throw Error('Choose a saved source with a URL or a retained original.');
          inputs.push(base);
        } else { const text = String(values[mode] || '').trim(); if (!text) throw Error(mode === 'url' ? 'Enter a source URL.' : 'Paste some text to import.'); inputs.push({...base, [mode]: text}); }
        let saved = 0;
        for (const input of inputs) {
          $('capture-status').textContent = 'Importing ' + (saved + 1) + ' of ' + inputs.length + '…';
          const result = await api('/capture', input), extraction = result.extraction || {};
          if (result.source?.id) upsert(result.source);
          if (result.record?.id) { const r = upsert(result.record); saved++; $('capture-results').append(recordCard(r)); }
          const status = extraction.status || (result.record ? 'ready' : 'unknown');
          if (status !== 'ready') { $('capture-results').append(node('p', 'error-text small', extraction.error || 'Import status: ' + status)); if (!result.record) throw Error(extraction.error || 'The material could not be extracted (' + status + ').'); }
        }
        renderRecords(); renderApplication(); $('capture-status').textContent = saved + ' record' + (saved === 1 ? '' : 's') + ' saved.';
      } catch (e) {
        if (e.response?.source?.id) { const source = upsert(e.response.source); $('capture-results').append(recordCard(source), node('p', 'small muted', 'The source is retained. Evidence or listing text was not created for this failed extraction.')); }
        $('capture-status').textContent = errorText(e); $('capture-status').className = 'small error-text'; renderRecords();
      }
    });
  }
  async function saveCredential(event) {
    event.preventDefault(); const form = event.currentTarget;
    await busy(form, async () => {
      try {
        const url = new URL(form.elements.origin.value); if (!['http:', 'https:'].includes(url.protocol)) throw Error('Use an HTTP origin.'); const origin = url.origin;
        await api('/credential', {sourceId: form.elements.sourceId.value, origin, cookie: form.elements.cookie.value}, true);
        form.elements.cookie.value = ''; $('credential-status').textContent = 'Source access saved for ' + origin + '.';
        try { await refresh(); } catch (_) { notice('Source access was saved, but the record list could not be refreshed.', true); }
      } catch (_) { $('credential-status').textContent = 'Source access could not be saved. Check the source, origin, and service configuration.'; }
      finally { form.elements.cookie.value = ''; }
    });
  }
  document.addEventListener('click', event => {
    const target = event.target.closest('button'); if (!target) return;
    if (target.dataset.new) openRecord(target.dataset.new);
    if (target.dataset.go) go(target.dataset.go);
    if (target.dataset.capture) capture(target.dataset.capture);
    if (target.hasAttribute('data-close-dialog')) {
      const dialog = target.closest('dialog'), dirty = dialog.id === 'record-dialog' ? state.recordDirty : state.artifactDirty;
      if (!dirty || window.confirm('Discard unsaved edits?')) { dialog.close(); if (dialog.id === 'record-dialog') state.recordDirty = false; else state.artifactDirty = false; }
    }
  });
  for (const dialog of document.querySelectorAll('dialog')) dialog.addEventListener('cancel', event => { const dirty = dialog.id === 'record-dialog' ? state.recordDirty : state.artifactDirty; if (dirty && !window.confirm('Discard unsaved edits?')) event.preventDefault(); else if (dialog.id === 'record-dialog') state.recordDirty = false; else state.artifactDirty = false; });
  $('record-form').addEventListener('submit', saveRecord); $('record-form').addEventListener('input', event => { if (event.target.closest('#record-fields')) state.recordDirty = true; });
  $('record-neighbors').addEventListener('toggle', () => void loadNeighbors());
  $('save-record-review').addEventListener('click', event => { void busy(event.currentTarget, async () => {
    try {
      if (state.recordDirty) throw Error('Save record changes before reviewing this version.');
      const subject = state.edit?.value; if (!subject) throw Error('Save this record before reviewing it.');
      await save('review', {subjectId: subject.id, subjectCid: subject.cid, decision: $('record-review-decision').value, notes: $('record-review-notes').value, ...(state.applicationId ? {applicationId: state.applicationId} : {})});
      $('record-message').textContent = 'Review saved for this record version.'; $('record-message').className = 'small success-text'; void loadCollisions();
    } catch (e) { $('record-message').textContent = errorText(e); $('record-message').className = 'small error-text'; }
  }); });
  $('artifact-form').addEventListener('submit', saveArtifact); $('artifact-form').addEventListener('input', () => state.artifactDirty = true);
  $('review-form').addEventListener('submit', saveReview); $('capture-form').addEventListener('submit', importMaterial); $('credential-form').addEventListener('submit', saveCredential);
  $('capture-form').addEventListener('change', captureMode); $('record-kind').addEventListener('change', renderRecords); $('record-search').addEventListener('input', renderRecords);
  $('new-record').addEventListener('click', () => openRecord($('record-kind').value));
  $('application-select').addEventListener('change', event => selectApplication(event.target.value));
  $('edit-application').addEventListener('click', () => openRecord('application', record(state.applicationId)));
  $('prepare-instructions').addEventListener('input', () => { state.selectionDirty = true; });
  $('save-selection').addEventListener('click', event => { void busy(event.currentTarget, async () => { try { await saveSelection(); notice('Preparation choices saved.'); } catch (e) { notice(errorText(e), true); } }); });
  $('prepare-assemble').addEventListener('click', () => void prepare('assemble')); $('prepare-model').addEventListener('click', () => void prepare('model'));
  $('rerun-assemble').addEventListener('click', () => void prepare('assemble', true)); $('rerun-model').addEventListener('click', () => void prepare('model', true));
  $('run-select').addEventListener('change', () => { renderProgram(); const run = state.runs.find(r => r.runId === $('run-select').value); if (run) runSummary($('program-run'), run); else $('program-run').replaceChildren(); });
  $('refresh-collisions').addEventListener('click', () => void loadCollisions());
  $('refresh').addEventListener('click', event => { void busy(event.currentTarget, async () => { try { await refresh(); notice('Records refreshed.'); } catch (e) { notice(errorText(e), true); } }); });
  $('open-program').addEventListener('click', event => { void busy(event.currentTarget, async () => { try { const p = await api('/program', {}); if (!p.name) throw Error('The service did not return the saved program.'); location.href = '/panels?load=' + encodeURIComponent(p.name); } catch (e) { notice(errorText(e), true); } }); });
  $('credential-source').addEventListener('change', event => { const source = record(event.target.value); if (source?.fields.origin) $('credential-form').elements.origin.value = source.fields.origin; });
  window.addEventListener('hashchange', showSection);
  window.addEventListener('beforeunload', event => { if (state.selectionDirty || state.recordDirty || state.artifactDirty) { event.preventDefault(); event.returnValue = ''; } });
  window.addEventListener('pagehide', () => { $('credential-form').elements.cookie.value = ''; });
  showSection(); captureMode(); void refresh().catch(e => notice(errorText(e), true));
})();
