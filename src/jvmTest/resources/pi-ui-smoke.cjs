// Opt-in browser check for the Pi page against a simulated Pi HTTP host.
// PAGES_BUNDLE=/absolute/path/to/pages.js node src/jvmTest/resources/pi-ui-smoke.cjs
const http = require('http'), fs = require('fs'), path = require('path'), assert = require('assert');
const { chromium } = require('playwright');
const root = path.resolve('src/commonMain/resources/web');
const bundle = process.env.PAGES_BUNDLE;
if (!bundle || !fs.existsSync(bundle)) throw Error('Set PAGES_BUNDLE to the built pages.js');
const calls = [];
let phase = 'closed', hasTurn = false;
const server = http.createServer((req, res) => {
  let chunks = [];
  req.on('data', b => chunks.push(b));
  req.on('end', () => {
    const url = new URL(req.url, 'http://localhost');
    let status = 200, type = 'application/json', body = '{}';
    if (url.pathname.startsWith('/api/pi/')) {
      const payload = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : null;
      calls.push({ path: url.pathname, auth: req.headers.authorization, payload });
      if (req.headers.authorization !== 'Bearer test-token') { status = 401; body = JSON.stringify({code:'authentication_required'}); }
      else if (url.pathname === '/api/pi/models') body = JSON.stringify({available:true,models:[{id:'fixture-model'}]});
      else if (url.pathname === '/api/pi/session/start') { phase = 'starting'; body = JSON.stringify({sessionId:'session-1',state:phase}); }
      else if (url.pathname === '/api/pi/session/prompt') { phase = 'running'; hasTurn = true; body = JSON.stringify({sessionId:'session-1',turnId:'turn-1',state:phase}); }
      else if (url.pathname === '/api/pi/session/close') { if (phase !== 'closed') phase = 'closing'; body = JSON.stringify({sessionId:'session-1',state:phase}); }
      else if (url.pathname === '/api/pi/session/status') {
        if (phase === 'starting') phase = 'ready';
        else if (phase === 'closing') phase = 'closed';
        else if (phase === 'running') phase = 'ready';
        body = JSON.stringify({sessionId:'session-1',state:phase,turnId:hasTurn?'turn-1':null,result:hasTurn?{buffered:true,messages:[
          {role:'user',content:[{type:'text',text:'Test message'}]},
          {role:'assistant',content:[{type:'text',text:'<img src=x onerror=alert(1)>'}],stopReason:'stop'}
        ]}:null});
      } else { status = 404; body = '{}'; }
    } else if (url.pathname === '/pi') { type = 'text/html'; body = fs.readFileSync(path.join(root, 'pi.html')); }
    else if (url.pathname === '/kotlin/pages/pages.js') { type = 'application/javascript'; body = fs.readFileSync(bundle); }
    else if (url.pathname === '/application-nav.css') { type = 'text/css'; body = fs.readFileSync(path.join(root, 'application-nav.css')); }
    else { status = 404; body = '{}'; }
    res.writeHead(status, {'Content-Type': type, 'Cache-Control':'no-store'}); res.end(body);
  });
});
server.listen(0, '127.0.0.1', async () => {
  let browser;
  try {
    browser = await chromium.launch({headless:true, executablePath:'/usr/bin/chromium', args:['--no-sandbox']});
    const page = await browser.newPage({viewport:{width:1500,height:900}}), errors=[];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/pi?project=matter`, {waitUntil:'domcontentloaded'});
    await page.getByText('Project navigation context: matter.', {exact:false}).waitFor();
    assert.equal(calls.length, 0, 'opening a page must not start a model or send an empty token');
    assert(await page.getByRole('button',{name:'Send to Pi'}).isDisabled());
    assert((await page.locator('a[data-destination="hermes"]').getAttribute('href')).includes('/hermes?project=matter'));
    await page.getByLabel('Pi access token').fill('test-token');
    await page.getByRole('button',{name:'Refresh models'}).click();
    await page.getByLabel('Pi model').selectOption('fixture-model');
    await page.getByRole('button',{name:'Start Pi session'}).click();
    await page.getByText('Pi session · ready').waitFor();
    await page.locator('#pi-prompt').fill('Test message');
    await page.getByRole('button',{name:'Send to Pi'}).click();
    await page.getByText('<img src=x onerror=alert(1)>', {exact:true}).waitFor();
    assert.equal(await page.locator('#pi-transcript img').count(), 0);
    const values = await page.evaluate(() => JSON.stringify({local:localStorage, session:sessionStorage}));
    assert(!values.includes('test-token'), 'the credential must not be persisted');
    assert(calls.every(c => c.auth === 'Bearer test-token'));
    assert(calls.every(c => !c.path.startsWith('/api/hermes/')));
    assert.deepStrictEqual(calls.find(c => c.path.endsWith('/prompt')).payload, {sessionId:'session-1', text:'Test message'});
    await page.getByRole('button',{name:'Close session'}).click();
    await page.getByText('Pi session · closed').waitFor();
    await page.waitForFunction(() => sessionStorage.getItem('trikeshed.pi.matter.session') === null);
    assert.equal(calls.filter(c => c.path.endsWith('/close')).length, 2, 'a drained session is released');
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log(JSON.stringify({result:'PASS',scope:'Pi browser UI with mocked Pi host, not runtime validation',requests:calls.map(c=>c.path)}));
  } catch (e) { console.error(e.stack || e); process.exitCode=1; }
  finally { if (browser) await browser.close(); server.close(); }
});
