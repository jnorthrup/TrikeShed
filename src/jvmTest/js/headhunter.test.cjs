'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');
const web = path.resolve(__dirname, '../../commonMain/resources/web');
const html = fs.readFileSync(path.join(web, 'headhunter.html'), 'utf8');
const source = fs.readFileSync(path.join(web, 'headhunter.js'), 'utf8');
const copy = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));
const row = (kind, number, fields) => ({id: kind + '/' + number, kind, cid: 'cid-' + kind + '-' + number, previousCid: null, updatedAtMs: 1000, fields});
const evidence = row('evidence', 1, {title: 'My project', category: 'project', text: 'Built a compiler.'});
const listing = row('listing', 1, {title: 'Compiler engineer', employer: 'A company', text: 'Maintain a compiler.'});
const application = row('application', 1, {listingId: listing.id, status: 'research', evidenceIds: [evidence.id]});

async function fixture(initial = [], override) {
  const dom = new JSDOM(html, {url: 'http://localhost:8888/headhunter', runScripts: 'outside-only'});
  const {window: w} = dom, db = copy(initial), calls = [], versions = new Map(db.map(r => [r.id, [copy(r)]]));
  w.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  w.HTMLDialogElement.prototype.close = function () { this.open = false; };
  w.HTMLElement.prototype.scrollIntoView = function () {};
  w.confirm = () => true;
  let sequence = 10;
  const response = (body, status = 200) => ({ok: status < 400, status, json: async () => copy(body)});
  w.fetch = async (url, options = {}) => {
    const body = options.body ? JSON.parse(options.body) : undefined;
    calls.push({url, body, method: options.method || 'GET'});
    const custom = override && await override(url, body, {db, calls, response}); if (custom) return custom;
    if (url === '/api/headhunter') return response({records: db, runs: [], model: {configured: false, detail: 'No provider configured.'}, program: {name: 'headhunter.prepare', document: {nodes: [{id: 'prepare', type: 'headhunter.prepare'}], wires: []}}});
    if (url.startsWith('/api/headhunter/collisions')) return response({collisions: []});
    if (url.startsWith('/api/headhunter/history')) return response({versions: versions.get(new URL(url, w.location.origin).searchParams.get('id')) || []});
    if (url.startsWith('/blackboard/neighbors')) return response({neighbors: []});
    if (url === '/api/headhunter/record') {
      const old = db.find(r => r.id === body.id);
      if (old && old.cid !== body.baseCid) return response({error: 'stale version'}, 409);
      const saved = {id: old?.id || body.kind + '/' + sequence, kind: body.kind, cid: 'revision-' + sequence++, previousCid: old?.cid || null, updatedAtMs: 2000, fields: body.fields};
      if (old) db[db.indexOf(old)] = saved; else db.unshift(saved);
      versions.set(saved.id, [copy(saved), ...(versions.get(saved.id) || [])]); return response(saved);
    }
    if (url === '/api/headhunter/credential') return response({configured: true});
    if (url === '/api/headhunter/prepare') return response({run: {runId: 'run-1', program: 'headhunter.prepare', status: 'completed', inputs: body}, artifacts: [], collisions: []});
    throw Error('Unexpected request ' + url);
  };
  w.eval(source); await tick();
  const $ = id => w.document.getElementById(id);
  const submit = async id => { $(id).dispatchEvent(new w.Event('submit', {bubbles: true, cancelable: true})); await tick(); };
  const input = (el, value) => { el.value = value; el.dispatchEvent(new w.Event('input', {bubbles: true})); };
  return {w, db, calls, $, submit, input, close: () => w.close()};
}

test('empty state has no seed material and never runs or captures on load', async t => {
  const f = await fixture(); t.after(f.close);
  assert.equal(f.$('welcome').hidden, false);
  assert.equal(f.$('application-workspace').hidden, true);
  assert.equal(f.$('prepare-model').disabled, true);
  assert.equal(f.$('record-form').closest('dialog').id, 'record-dialog');
  assert.equal(f.$('credential-form').closest('form').id, 'credential-form');
  assert.deepEqual(f.calls.map(c => c.url), ['/api/headhunter']);
  f.w.eval(fs.readFileSync(path.join(web, 'application-nav.js'), 'utf8'));
  const link = f.w.document.querySelector('[data-destination=headhunter]');
  assert.equal(link.textContent, 'Job agent');
  assert.equal(link.getAttribute('aria-current'), 'page');
  assert.equal(f.w.document.querySelector('[data-destination=board]').getAttribute('href'), '/blackboard');
});

test('evidence notes save real input and stale edits stay available for correction', async t => {
  const f = await fixture([evidence]); t.after(f.close);
  f.$('evidence-records').querySelector('button').click(); await tick();
  const editor = f.$('record-form').elements.text;
  f.input(editor, 'My corrected compiler experience.');
  f.db[0].cid = 'changed-elsewhere';
  await f.submit('record-form');
  assert.equal(f.$('record-dialog').open, true);
  assert.match(f.$('record-message').textContent, /changed after you opened/);
  assert.equal(editor.value, 'My corrected compiler experience.');
  const write = f.calls.find(c => c.url === '/api/headhunter/record');
  assert.equal(write.body.baseCid, evidence.cid);
  assert.equal(write.body.fields.text, 'My corrected compiler experience.');
  assert.equal(f.db[0].fields.text, evidence.fields.text);
});

test('preparation uses persisted application/evidence IDs and saves instructions before the run', async t => {
  const f = await fixture([application, evidence, listing]); t.after(f.close);
  f.input(f.$('prepare-instructions'), 'Emphasize compiler work.');
  f.$('prepare-assemble').click(); await tick();
  const write = f.calls.find(c => c.url === '/api/headhunter/record');
  const run = f.calls.find(c => c.url === '/api/headhunter/prepare');
  assert.equal(write.body.baseCid, application.cid);
  assert.deepEqual(run.body, {applicationId: application.id, evidenceIds: [evidence.id], instructions: 'Emphasize compiler work.', mode: 'assemble'});
  assert.ok(f.calls.indexOf(write) < f.calls.indexOf(run));
  assert.equal(f.$('run-summary').hidden, false);
  assert.match(f.$('run-summary').textContent, /run-1/);
  assert.equal(f.calls.some(c => /send|submit|contact/.test(c.url)), false);
});

test('refresh does not replace the optimistic version behind unsaved preparation choices', async t => {
  const f = await fixture([application, evidence, listing]); t.after(f.close);
  f.input(f.$('prepare-instructions'), 'Keep my changes.');
  f.db.find(r => r.id === application.id).cid = 'new-application-version';
  f.$('refresh').click(); await tick(); f.$('save-selection').click(); await tick();
  assert.match(f.$('notice').textContent, /changed after you opened/);
  assert.equal(f.$('prepare-instructions').value, 'Keep my changes.');
  assert.equal(f.calls.find(c => c.url === '/api/headhunter/record').body.baseCid, application.cid);
});

test('credential input is submitted separately, cleared, and never rendered or persisted in browser storage', async t => {
  const savedSource = row('source', 1, {title: 'Source', origin: 'https://jobs.example.com'});
  const f = await fixture([savedSource]); t.after(f.close);
  const form = f.$('credential-form'); form.elements.sourceId.value = savedSource.id; form.elements.origin.value = savedSource.fields.origin;
  form.elements.cookie.value = 'session=private-test-value'; await f.submit('credential-form');
  const write = f.calls.find(c => c.url === '/api/headhunter/credential');
  assert.deepEqual(write.body, {sourceId: savedSource.id, origin: savedSource.fields.origin, cookie: 'session=private-test-value'});
  assert.equal(form.elements.cookie.type, 'password'); assert.equal(form.elements.cookie.value, '');
  assert.equal(f.w.document.body.textContent.includes('private-test-value'), false);
  assert.equal(f.w.localStorage.length, 0); assert.equal(f.w.sessionStorage.length, 0);
  assert.equal(f.calls.some(c => c.url === '/api/headhunter/record'), false);
});

test('failed extraction shows retained source and does not fabricate evidence', async t => {
  const retained = row('source', 1, {title: 'Unreadable PDF', lastCapture: {status: 'configuration_required', originalCid: 'original-pdf', error: 'Document extraction is unavailable.'}});
  const f = await fixture([], (url, body, {response}) => url === '/api/headhunter/capture' ? response({source: retained, record: null, extraction: retained.fields.lastCapture, error: 'Document extraction is unavailable.'}, 422) : null); t.after(f.close);
  f.$('capture-form').elements.text.value = 'Material to capture'; await f.submit('capture-form');
  assert.match(f.$('capture-status').textContent, /extraction is unavailable/);
  assert.match(f.$('capture-results').textContent, /source is retained/);
  assert.equal(f.$('evidence-records').querySelectorAll('.record-card').length, 0);
  assert.equal(f.$('source-records').querySelectorAll('.record-card').length, 1);
});

test('artifact edits retain evidence/version links and reviews bind only to saved revisions', async t => {
  const artifact = row('artifact', 1, {applicationId: application.id, type: 'resume', format: 'markdown', title: 'Resume draft', content: '<img src=x onerror="window.injected=1">\nCompiler work.', generation: 'assembled', sources: [{id: evidence.id, cid: evidence.cid}], reviewStatus: 'proposed'});
  const f = await fixture([application, evidence, listing, artifact]); t.after(f.close);
  assert.equal(f.$('artifacts').querySelector('img'), null);
  assert.equal(f.w.injected, undefined);
  assert.match(f.$('artifacts').textContent, new RegExp(evidence.cid));
  [...f.$('artifacts').querySelectorAll('button')].find(b => b.textContent === 'Edit & review').click();
  f.input(f.$('artifact-form').elements.content, 'Revised supported experience.');
  await f.submit('review-form');
  assert.match(f.$('review-message').textContent, /Save the artifact revision/);
  assert.equal(f.calls.filter(c => c.body?.kind === 'review').length, 0);
  await f.submit('artifact-form'); await f.submit('review-form');
  const edit = f.calls.find(c => c.body?.kind === 'artifact'), review = f.calls.find(c => c.body?.kind === 'review');
  assert.equal(edit.body.baseCid, artifact.cid); assert.deepEqual(edit.body.fields.sources, artifact.fields.sources);
  assert.equal(review.body.fields.subjectCid, f.db.find(r => r.id === artifact.id).cid);
  assert.equal(review.body.fields.decision, 'approved');
  assert.match(f.$('artifacts').textContent, /approved/);
});

test('restored oldest-first reviews show the newest decision for the exact artifact version', async t => {
  const artifact = row('artifact', 1, {applicationId: application.id, type: 'resume', title: 'Resume draft', content: 'Compiler work.', generation: 'assembled', sources: [{id: evidence.id, cid: evidence.cid}]});
  const approval = row('review', 1, {subjectId: artifact.id, subjectCid: artifact.cid, decision: 'approved'});
  const rejection = {...row('review', 2, {subjectId: artifact.id, subjectCid: artifact.cid, decision: 'rejected'}), updatedAtMs: 2000};
  const otherVersion = {...row('review', 3, {subjectId: artifact.id, subjectCid: 'different-artifact-version', decision: 'approved'}), updatedAtMs: 5000};
  const otherSubject = {...row('review', 4, {subjectId: 'artifact/other', subjectCid: artifact.cid, decision: 'approved'}), updatedAtMs: 6000};
  const f = await fixture([application, evidence, listing, artifact, approval, rejection, otherVersion, otherSubject]); t.after(f.close);
  const decision = () => [...f.$('artifacts').querySelectorAll('.badge')].map(el => el.textContent).find(text => ['approved', 'rejected', 'changes-requested'].includes(text));
  assert.equal(decision(), 'rejected');
  f.db.push({...row('review', 5, {subjectId: artifact.id, subjectCid: artifact.cid, decision: 'changes-requested'}), updatedAtMs: 3000});
  f.$('refresh').click(); await tick();
  assert.equal(decision(), 'changes-requested');
});

test('browser rejects oversize uploads before acquiring bytes or calling capture', async t => {
  const f = await fixture(); t.after(f.close);
  const form = f.$('capture-form'); form.elements.inputMode.value = 'file'; form.dispatchEvent(new f.w.Event('change', {bubbles: true}));
  Object.defineProperty(form.elements.files, 'files', {value: [{name: 'oversize.pdf', size: 2 * 1024 * 1024 + 1}]});
  await f.submit('capture-form');
  assert.match(f.$('capture-status').textContent, /2 MiB browser upload limit/);
  assert.equal(f.calls.some(c => c.url === '/api/headhunter/capture'), false);
});

test('a previous LCNC run replays its nested request and pinned evidence versions', async t => {
  const pinned = {applicationId: application.id, evidenceIds: [evidence.id], instructions: 'Original instruction', mode: 'assemble', versions: {[application.id]: 'old-application', [evidence.id]: 'old-evidence', [listing.id]: 'old-listing'}};
  const run = {runId: 'previous-run', startedAtMs: 1000, status: 'completed', inputs: {request: pinned}};
  const f = await fixture([application, evidence, listing], (url, body, {db, response}) => url === '/api/headhunter' ? response({records: db, runs: [run], model: {configured: false}, program: {name: 'headhunter.prepare', document: {nodes: []}}}) : null); t.after(f.close);
  f.$('run-select').value = run.runId; f.$('run-select').dispatchEvent(new f.w.Event('change'));
  assert.match(f.$('program-selection').textContent, /versions pinned by that run/);
  f.$('rerun-assemble').click(); await tick();
  assert.deepEqual(f.calls.find(c => c.url === '/api/headhunter/prepare').body, pinned);
  assert.equal(f.calls.some(c => c.url === '/api/headhunter/record'), false);
});

test('a failed preparation keeps the actual LCNC run receipt inspectable', async t => {
  const failed = {runId: 'failed-run', program: 'headhunter.prepare', status: 'failed', inputs: {request: {applicationId: application.id}}, error: 'Provider unavailable'};
  const f = await fixture([application, evidence, listing], (url, body, {db, response}) => {
    if (url === '/api/headhunter') return response({records: db, runs: [], model: {configured: true, detail: 'Availability is checked when invoked.'}, program: {name: 'headhunter.prepare', document: {nodes: []}}});
    return url === '/api/headhunter/prepare' ? response({run: failed, error: failed.error}, 503) : null;
  }); t.after(f.close);
  assert.equal(f.$('model-status').textContent, 'Model configured. Availability is checked when invoked.');
  f.$('prepare-model').click(); await tick();
  assert.match(f.$('notice').textContent, /Provider unavailable/);
  assert.match(f.$('run-summary').textContent, /failed-run/);
  assert.match(f.$('program-run').textContent, /Provider unavailable/);
  assert.ok(f.calls.filter(c => c.url === '/api/headhunter').length > 1);
});

test('source extraction rules save nested markers and literal corrections without losing access reference', async t => {
  const savedSource = row('source', 1, {title: 'Saved upload', origin: 'upload', credentialRef: 'opaque-reference', extraction: {startAfter: 'Beginning', replacements: {'Typo': 'Correct'}}});
  const f = await fixture([savedSource]); t.after(f.close);
  f.$('source-records').querySelector('button').click(); await tick();
  const form = f.$('record-form'); f.input(form.elements.startAfter, ''); f.input(form.elements.endBefore, 'End');
  const correction = f.$('source-replacements').querySelector('[data-replacement=to]'); f.input(correction, 'Updated correction');
  await f.submit('record-form');
  const write = f.calls.find(c => c.url === '/api/headhunter/record');
  assert.equal(write.body.fields.credentialRef, 'opaque-reference');
  assert.deepEqual(write.body.fields.extraction, {endBefore: 'End', replacements: {'Typo': 'Updated correction'}});
  assert.equal(Object.hasOwn(write.body.fields, 'endBefore'), false);
  assert.equal(write.body.baseCid, savedSource.cid);
});

test('neighbor retrieval exposes association evidence without creating identity links', async t => {
  const contact = row('contact', 1, {name: 'A contact', notes: 'Compiler team'});
  const f = await fixture([evidence, contact], (url, body, {response}) => url.startsWith('/blackboard/neighbors') ? response({neighbors: [{key: 'headhunter/' + contact.id, terms: ['compiler'], concepts: ['ComputerProgram'], references: []}]}) : null); t.after(f.close);
  f.$('evidence-records').querySelector('button').click(); await tick();
  const toggled = new Promise(resolve => f.$('record-neighbors').addEventListener('toggle', resolve, {once: true}));
  f.$('record-neighbors').open = true; await toggled; await tick();
  assert.match(f.$('neighbor-records').textContent, /A contact/);
  assert.match(f.$('neighbor-records').textContent, /Shared text: compiler/);
  assert.match(f.$('record-neighbors').textContent, /do not confirm identity/);
  assert.equal(f.calls.some(c => c.url === '/api/headhunter/record'), false);
});

test('saved originals can be extracted again using the source record and its corrected rules', async t => {
  const savedSource = row('source', 1, {title: 'Retained resume', origin: 'upload', originalCid: 'original-bytes', extraction: {replacements: {'Old': 'Corrected'}}});
  const f = await fixture([savedSource], (url, body, {response}) => url === '/api/headhunter/capture' ? response({source: savedSource, record: evidence, extraction: {status: 'ready'}}) : null); t.after(f.close);
  [...f.$('source-records').querySelectorAll('button')].find(b => b.textContent === 'Use saved source').click();
  await f.submit('capture-form');
  assert.deepEqual(f.calls.find(c => c.url === '/api/headhunter/capture').body, {kind: 'evidence', category: 'resume', sourceId: savedSource.id});
  assert.equal(f.$('evidence-records').querySelectorAll('.record-card').length, 1);
  assert.match(f.$('capture-status').textContent, /1 record saved/);
});
