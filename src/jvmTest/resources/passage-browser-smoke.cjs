// Opt-in browser proof: production documents bundle and a synthetic, read-only HTTP backend.
// From this worktree: DOCUMENTS_BUNDLE=/absolute/path/to/documents.js node src/jvmTest/resources/passage-browser-smoke.cjs
// The backend is mocked; this proves browser navigation and rendering, not CAS integrity or server authorization.
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const assert = require('assert');
const { chromium } = require('playwright');

const web = path.resolve('src/commonMain/resources/web');
const bundle = process.env.DOCUMENTS_BUNDLE;
if (!bundle || !fs.existsSync(bundle)) throw Error('Set DOCUMENTS_BUNDLE to a freshly built documents.js');
const sha = value => 'sha256:' + crypto.createHash('sha256').update(value).digest('hex');
// A primary repository may keep spaces, + and & in its lowercased directory name.
const project = 'docs and law+&';
const documentId = "contracts/buyer's.pdf";
const otherId = 'other.txt';
const receiptCid = sha('synthetic retained curation receipt');
const sourceCid = sha('%PDF synthetic source');
const quote = '§ 1 😀 <b>literal</b>';
const text = `Before ${quote}. Between. ${quote}. After.`;
const textCid = sha(text);
const changedCid = sha('a different extraction');
const begin = text.lastIndexOf(quote), end = begin + quote.length;
const original = { project, id: documentId, cid: sourceCid, rev: '1-original', seq: 1 };
const extraction = { project, id: `${documentId}.extract.md`, cid: textCid, rev: '1-extract', seq: 2 };
const receipt = {
  receiptCid,
  record: {
    source: { name: documentId, text, originalCid: sourceCid, extractedTextCid: textCid,
      project: original, extraction },
    proposals: [{ subject:'Party', predicate:'must', object:'act', quote,
      begin: 0, end: 1, quotationBegin: begin, quotationEnd: end }],
    submitted: [], duplicates: [], toolReceipts: [],
  },
  nlpAxioms: [],
};

let changed = false, sourceChanged = false, receiptMismatch = false, hold = null, pending = null;
const requests = [];
function json(res, value, status = 200) {
  res.writeHead(status, { 'Content-Type':'application/json', 'Cache-Control':'no-store' });
  res.end(JSON.stringify(value));
}
function replyMaybeHeld(res, kind, value) {
  if (hold === kind) {
    hold = null;
    pending = { res, value, kind };
    return;
  }
  json(res, value);
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://fixture');
  requests.push({ method:req.method, path:u.pathname });
  if (u.pathname === '/_test/changed') { changed = u.searchParams.get('value') === '1'; return json(res, {changed}); }
  if (u.pathname === '/_test/source-changed') { sourceChanged = u.searchParams.get('value') === '1'; return json(res, {sourceChanged}); }
  if (u.pathname === '/_test/receipt-mismatch') { receiptMismatch = u.searchParams.get('value') === '1'; return json(res, {receiptMismatch}); }
  if (u.pathname === '/_test/hold') { hold = u.searchParams.get('kind'); return json(res, {hold}); }
  if (u.pathname === '/_test/state') return json(res, {held:pending?.kind || null});
  if (u.pathname === '/_test/release') {
    const held = pending; pending = null;
    if (held) json(held.res, held.value);
    return json(res, {released:!!held});
  }
  if (u.pathname === '/documents') {
    res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
    return res.end(fs.readFileSync(path.join(web, 'documents.html')));
  }
  if (u.pathname === '/kotlin/documents/documents.js') {
    res.writeHead(200, {'Content-Type':'application/javascript'});
    return res.end(fs.readFileSync(bundle));
  }
  if (u.pathname.endsWith('.css')) {
    const file = path.join(web, path.basename(u.pathname));
    if (fs.existsSync(file)) { res.writeHead(200, {'Content-Type':'text/css'}); return res.end(fs.readFileSync(file)); }
  }
  if (u.pathname === '/api/projects') return json(res, { scopes:[{name:project,kind:'assets',docs:2,path:'/synthetic'}] });
  if (u.pathname === '/api/documents') {
    if (u.searchParams.has('cid')) {
      if (u.searchParams.get('cid') !== receiptCid) return json(res, {error:'absent'}, 404);
      return replyMaybeHeld(res, 'receipt', receiptMismatch ? {...receipt,receiptCid:sha('other receipt')} : receipt);
    }
    return json(res, {available:false,records:[{receiptCid,name:documentId}]});
  }
  const docs = '/api/projects/' + encodeURIComponent(project) + '/docs';
  if (u.pathname === docs) return json(res, {project,docs:[
    {...original,length:100,contentType:'application/pdf'},
    {project,id:otherId,cid:sha('Other document'),rev:'1-other',seq:3,length:14,contentType:'text/plain'},
  ]});
  if (u.pathname === docs + '/' + encodeURIComponent(documentId)) {
    const current = {...extraction,cid:changed ? changedCid : textCid};
    return replyMaybeHeld(res, 'document', {
      project,id:documentId,cid:current.cid,rev:current.rev,seq:current.seq,
      contentType:'application/pdf',text:changed ? 'a different extraction' : text,
      extract:changed ? 'a different extraction' : text,
      sourceRevision:{...original,cid:sourceChanged ? sha('%PDF changed source') : sourceCid,length:100,contentType:'application/pdf'},
      displayTextRevision:current,extractionRevision:current,
    });
  }
  if (u.pathname === docs + '/' + encodeURIComponent(otherId)) {
    const other = {project,id:otherId,cid:sha('Other document'),rev:'1-other',seq:3};
    return json(res, {...other,contentType:'text/plain',text:'Other document',sourceRevision:other,displayTextRevision:other,extractionRevision:null});
  }
  return json(res, {error:'not_found'}, 404);
});

async function waitHeld(page, base, kind) {
  await page.waitForFunction(async ({base,kind}) => {
    const response = await fetch(base + '/_test/state');
    return (await response.json()).held === kind;
  }, {base,kind}, {timeout:10000});
}

server.listen(0, '127.0.0.1', async () => {
  let browser, page;
  const base = 'http://127.0.0.1:' + server.address().port;
  const errors = [];
  try {
    browser = await chromium.launch({headless:true,executablePath:'/usr/bin/chromium',args:['--no-sandbox']});
    page = await browser.newPage({viewport:{width:1400,height:900}});
    page.on('pageerror', e => errors.push(e.message));
    const attempt = base + '/documents#curation/' + encodeURIComponent(receiptCid);
    await page.goto(attempt, {waitUntil:'domcontentloaded'});
    const link = page.locator('a[data-passage-receipt]').first();
    await link.waitFor({timeout:30000});
    const href = await link.getAttribute('href');
    const parsed = new URL(href, base);
    assert.equal(parsed.searchParams.get('passageReceipt'), receiptCid);
    assert.equal(parsed.searchParams.get('passageProposal'), '0');
    assert.equal(decodeURIComponent(parsed.hash), '#' + project + '/' + documentId);
    await link.click();
    const selection = page.locator('[data-passage-result]');
    await selection.locator('mark').waitFor({timeout:30000});
    assert.equal(await selection.locator('mark').textContent(), quote);
    assert.equal(await selection.locator('b').count(), 0, 'quotation markup must remain text');
    assert((await selection.textContent()).includes('Between.'), 'the retained offsets select the second duplicate occurrence');

    // Leaving a passage clears only its parameters; reload and browser history must not replay it.
    await page.evaluate(() => {
      const url = new URL(location.href); url.searchParams.set('keep', 'unrelated & value');
      history.replaceState(history.state, '', url.pathname + url.search + url.hash);
    });
    await page.locator('[data-doc="other.txt"]').first().click();
    await page.getByRole('heading',{name:otherId,exact:true}).waitFor();
    let currentUrl = new URL(page.url());
    assert.equal(currentUrl.searchParams.get('keep'), 'unrelated & value');
    assert(!currentUrl.searchParams.has('passageReceipt') && !currentUrl.searchParams.has('passageProposal'));
    await page.reload({waitUntil:'domcontentloaded'});
    await page.getByRole('heading',{name:otherId,exact:true}).waitFor({timeout:30000});
    assert.equal(await page.locator('[data-passage-result]').count(), 0, 'reload must not replay an old passage');
    await page.goBack();
    await page.getByRole('heading',{name:documentId,exact:true}).waitFor({timeout:30000});
    currentUrl = new URL(page.url());
    assert.equal(currentUrl.searchParams.get('keep'), 'unrelated & value');
    assert(!currentUrl.searchParams.has('passageReceipt') && !currentUrl.searchParams.has('passageProposal'));
    assert.equal(await page.locator('[data-passage-result]').count(), 0, 'Back opens the document, not the departed passage');
    await page.goForward();
    await page.getByRole('heading',{name:otherId,exact:true}).waitFor({timeout:30000});

    for (const destination of ['project', 'curation']) {
      await page.goto(attempt, {waitUntil:'domcontentloaded'});
      await page.locator('a[data-passage-receipt]').first().click();
      await selection.locator('mark').waitFor({timeout:30000});
      await page.evaluate(() => {
        const url = new URL(location.href); url.searchParams.set('keep', 'still here');
        history.replaceState(history.state, '', url.pathname + url.search + url.hash);
      });
      if (destination === 'project') {
        await page.locator('[data-project]').first().click();
        await page.waitForFunction(hash => location.hash === hash, '#' + encodeURIComponent(project));
      } else {
        await page.locator('[data-curate]').first().click();
        await page.waitForFunction(() => location.hash === '#curation');
      }
      currentUrl = new URL(page.url());
      assert.equal(currentUrl.searchParams.get('keep'), 'still here');
      assert(!currentUrl.searchParams.has('passageReceipt') && !currentUrl.searchParams.has('passageProposal'),
        `${destination} navigation retained the old passage`);
    }

    await page.request.post(base + '/_test/changed?value=1');
    await page.goto(attempt, {waitUntil:'domcontentloaded'});
    await page.locator('a[data-passage-receipt]').first().click();
    await selection.getByText(/Passage unavailable/i).waitFor({timeout:30000});
    assert.equal(await selection.locator('mark').count(), 0, 'a changed text CID must not highlight a different extraction');
    assert.match((await selection.textContent()).toLowerCase(), /unavailable|changed|match/);

    await page.request.post(base + '/_test/changed?value=0');
    await page.request.post(base + '/_test/source-changed?value=1');
    await page.goto(attempt, {waitUntil:'domcontentloaded'});
    await page.locator('a[data-passage-receipt]').first().click();
    await selection.getByText(/Passage unavailable/i).waitFor({timeout:30000});
    assert.equal(await selection.locator('mark').count(), 0, 'a stale source revision must not reuse its old extraction');
    assert.match((await selection.textContent()).toLowerCase(), /unavailable|changed|match/);
    await page.request.post(base + '/_test/source-changed?value=0');
    await page.goto(attempt, {waitUntil:'domcontentloaded'});
    await page.locator('a[data-passage-receipt]').first().waitFor({timeout:30000});
    await page.request.post(base + '/_test/receipt-mismatch?value=1');
    await page.locator('a[data-passage-receipt]').first().click();
    await selection.getByText(/Passage unavailable/i).waitFor({timeout:30000});
    assert.equal(await selection.locator('mark').count(), 0, 'a different returned receipt must not satisfy the link');
    await page.request.post(base + '/_test/receipt-mismatch?value=0');
    for (const kind of ['receipt','document']) {
      await page.goto(attempt, {waitUntil:'domcontentloaded'});
      await page.locator('a[data-passage-receipt]').first().waitFor({timeout:30000});
      await page.request.post(base + '/_test/hold?kind=' + kind);
      await page.locator('a[data-passage-receipt]').first().click();
      await waitHeld(page, base, kind);
      const loadingUrl = new URL(page.url());
      assert.equal(loadingUrl.hash, parsed.hash, `loading ${kind} must preserve the target project/document hash`);
      assert.equal(loadingUrl.searchParams.get('passageReceipt'), receiptCid);
      const other = page.locator('[data-doc="other.txt"]').first();
      await other.waitFor({timeout:10000});
      await other.click();
      await page.getByRole('heading',{name:otherId,exact:true}).waitFor({timeout:10000});
      await page.request.post(base + '/_test/release');
      await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));
      assert(await page.getByRole('heading',{name:otherId,exact:true}).isVisible(), `late ${kind} response replaced the selected document`);
      assert.equal(await page.locator('[data-passage-result] mark').count(), 0, `late ${kind} response painted a passage on another document`);
    }
    assert.equal(errors.length, 0, errors.join('\n'));
    assert(!requests.some(r => r.method === 'POST' && !r.path.startsWith('/_test/')), 'opening a passage must not dispatch any program or write');
    console.log(JSON.stringify({result:'PASS',scope:'production documents JS with simulated HTTP backend; not a CAS or legal verification',
      checks:['retained proposal click','exact duplicate and UTF-16 passage','escaped text','changed extraction, source and receipt refusal','passage parameter cleanup, reload and history','target hash and stale receipt/document navigation']}));
  } catch (error) {
    console.error(error.stack || error);
    if (page) {
      try { console.error('DOCUMENT CONTENT:', (await page.locator('#ds-content').innerText()).slice(0,5000)); } catch (_) {}
      try { await page.screenshot({path:'/tmp/passage-browser-failure.png'}); console.error('Screenshot: /tmp/passage-browser-failure.png'); } catch (_) {}
    }
    process.exitCode = 1;
  } finally {
    if (pending) { try { json(pending.res, pending.value); } catch (_) {} }
    if (browser) await browser.close();
    server.close();
  }
});
