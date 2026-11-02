// Opt-in local Chromium proof. The review endpoints are the actual JVM wire and bounded file CAS.
const assert = require('assert');
const { chromium } = require('playwright');
const base = process.env.STRICT_REVIEW_BASE;
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base || '')) throw Error('Loopback-only server required');
(async () => {
  let browser;
  try {
    browser = await chromium.launch({headless:true, executablePath:process.env.TRIKESHED_CHROMIUM || '/usr/bin/chromium', args:['--no-sandbox']});
    const page = await browser.newPage({viewport:{width:1600,height:1100}}), errors=[];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + '/panels?load=strict-loopback', {waitUntil:'domcontentloaded'});
    const fixture = await page.evaluate(async () => (await fetch('/_proof/bootstrap')).json());
    const call = (path, body, token=fixture.token) => page.evaluate(async ({path,body,token}) => {
      const response = await fetch('/api/documents/review' + path, {method:'POST', headers:{'Content-Type':'application/json',...(token ? {Authorization:'Bearer '+token} : {})},body:JSON.stringify(body)});
      return {status:response.status,body:await response.json()};
    }, {path,body,token});
    const start = fixture.text.indexOf('if funding arrives');
    const span = {textCid:fixture.textCid, begin:start, end:start+'if funding arrives'.length, quote:'if funding arrives'};
    const row = {selectionId:fixture.selectionId,state:'conditional',spans:[span]};
    const assess = {snapshotCid:fixture.snapshotCid,expectedRevision:fixture.revision,assessments:[row]};
    assert.equal((await call('/assess',assess,null)).status,401,'assessment needs reviewer');
    const corrupted = structuredClone(assess); corrupted.assessments[0].spans[0].quote='fabricated qualifier';
    assert.equal((await call('/assess',corrupted)).status,409,'fabricated quote must be rejected');
    await page.waitForFunction(() => window.G && window.G.nodes && window.G.nodes.length === 1, {timeout:25000});
    await page.locator('#runBtn').click();
    if (await page.getByRole('button',{name:'Close facets'}).count()) await page.getByRole('button',{name:'Close facets'}).click();
    if (await page.locator('#palette').evaluate(el=>el.classList.contains('open'))) await page.locator('#paletteBtn').click();
    const pane=page.locator('.document-review').first(); await pane.waitFor({timeout:25000});
    await pane.getByLabel('Reviewer credential').fill(fixture.token);
    await pane.getByRole('button',{name:'Authenticate reviewer'}).click();
    await pane.getByText('Synthetic validation reviewer: synthetic:loopback-reviewer').waitFor();
    assert(await pane.getByRole('button',{name:'Approve exact selection'}).isDisabled());
    const selected=pane.locator('[data-condition-selection]').first();
    assert.equal(await selected.locator('[data-condition-quote]').textContent(), fixture.text);
    await selected.locator('[data-condition-state]').selectOption('conditional');
    await selected.locator('[data-condition-quote]').evaluate((el,span) => {
      const range=document.createRange();range.setStart(el.firstChild,span.begin);range.setEnd(el.firstChild,span.end);
      const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
    }, span);
    await selected.locator('[data-condition-add-selection]').click();
    await selected.getByText('Qualifier · '+span.begin+'–'+span.end+' · '+span.quote).waitFor();
    const [assessResponse] = await Promise.all([
      page.waitForResponse(r=>r.url().endsWith('/api/documents/review/assess') && r.request().method()==='POST'),
      pane.getByRole('button',{name:'Save condition assessment'}).click()
    ]);
    assert.equal(assessResponse.status(),200,await assessResponse.text());
    const draft=await assessResponse.json();
    await pane.getByText('Authenticated saved assessment · complete',{exact:false}).waitFor();
    const saved=await call('/assessment',{snapshotCid:fixture.snapshotCid,expectedRevision:draft.revision});
    assert.equal(saved.status,200,JSON.stringify(saved.body));
    assert.deepStrictEqual(saved.body.assessments,[row]);
    assert.equal((await call('/assess',assess)).status,409,'old revision must be rejected');
    const [decisionResponse] = await Promise.all([
      page.waitForResponse(r=>r.url().endsWith('/api/documents/review/decision') && r.request().method()==='POST'),
      pane.getByRole('button',{name:'Approve exact selection'}).click()
    ]);
    assert.equal(decisionResponse.status(),200,await decisionResponse.text());
    const approved=await decisionResponse.json();
    assert.equal(approved.state,'approve');
    assert.equal(approved.conditionReviewState,'conditional-nonexecutable');
    await pane.getByText('Conditional assessment cannot be exported as an executable relationship.').waitFor();
    assert.equal(await pane.getByRole('button',{name:'Copy approval reference'}).count(),0);
    const release={snapshotCid:fixture.snapshotCid,approvalCid:approved.decisionCid,expectedRevision:approved.revision};
    const denied=await call('/release',release);assert.equal(denied.status,409,JSON.stringify(denied.body));
    assert.equal((await call('/release',{...release,expectedRevision:draft.revision})).status,409);
    assert.deepStrictEqual(errors,[]);
    console.log('STRICT_BOOK_LOOPBACK_PASS '+JSON.stringify({scope:'development browser; synthetic canvas bootstrap; real JVM DocumentReviewWire and file-backed bounded CAS', checks:['authentication required','fabricated qualifier rejected','source span retained','stale assessment refused','conditional approval','conditional release refused'],errors}));
  } catch (e) { console.error(e.stack || e);process.exitCode=1; }
  finally { if (browser) await browser.close(); }
})();
