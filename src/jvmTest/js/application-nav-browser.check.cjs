'use strict';
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const base = process.env.APPLICATION_NAV_BASE_URL || 'http://127.0.0.1:8888';
const source = process.env.APPLICATION_NAV_SOURCE === '1';
const routes = {
  '/harness': 'harness.html', '/graal': 'graal.html', '/panels': 'panels.html',
  '/documents': 'documents.html', '/kanban': 'kanban.html', '/hermes': 'hermes-xterm.html',
  '/keymux': 'mux.html', '/modelmux': 'mux.html', '/futon': 'futon.html'
};
const labels = ['Board', 'Graal', 'Panels', 'Documents', 'Kanban', 'Hermes', 'KeyMux', 'ModelMux', 'Futon'];

(async () => {
  const output = await fs.mkdtemp(path.join(os.tmpdir(), 'trikeshed-navigation-'));
  const browser = await chromium.launch({headless: true});
  const receipts = [];
  try {
    for (const viewport of [{width: 1440, height: 900}, {width: 390, height: 844}]) {
      const context = await browser.newContext({viewport});
      if (source) await context.route('**/*', async route => {
        const url = new URL(route.request().url());
        if (url.origin !== new URL(base).origin) return route.continue();
        const name = routes[url.pathname] || (/^\/[a-z0-9/_.-]+\.(js|css)$/.test(url.pathname) ? url.pathname.slice(1) : null);
        if (!name || name.includes('..')) return route.continue();
        try {
          const bytes = await fs.readFile(path.join(process.cwd(), 'src/commonMain/resources/web', name));
          await route.fulfill({body: bytes, contentType: name.endsWith('.js') ? 'application/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html'});
        } catch (error) { if (error.code === 'ENOENT') await route.continue(); else throw error; }
      });
      const page = await context.newPage();
      for (const [index, route] of Object.keys(routes).entries()) {
        await page.goto(base + route, {waitUntil: 'domcontentloaded'});
        await page.locator('#application-nav').waitFor();
        assert.deepEqual(await page.locator('#application-links a').allTextContents(), labels);
        assert.equal(await page.locator('#application-links [aria-current=page]').textContent(), labels[index]);
        assert.equal(await page.locator('#application-nav a[target=_blank]').count(), 0);
        const bar = await page.locator('#application-nav').boundingBox();
        assert.equal(bar.y, 0); assert.equal(bar.height, 44); assert.equal(bar.width, viewport.width);
        if (viewport.width < 960) {
          const menu = page.getByRole('button', {name: 'Navigation menu'});
          await menu.click();
          assert.equal(await menu.getAttribute('aria-expanded'), 'true');
          assert.equal(await menu.locator('svg').count(), 1);
          const links = await page.locator('#application-links').boundingBox();
          assert.ok(links.x >= 0 && links.x + links.width <= viewport.width && links.y + links.height <= viewport.height);
          await page.screenshot({path: path.join(output, `${viewport.width}-${route.slice(1)}-menu.png`)});
          await page.keyboard.press('Escape');
          assert.equal(await menu.getAttribute('aria-expanded'), 'false');
        }
        if (route === '/graal' || route === '/harness' || route === '/panels') {
          const tools = await page.locator(route === '/graal' ? '#hud' : '#bar').boundingBox();
          assert.ok(tools.y >= bar.y + bar.height, `${route}: navigation overlaps page tools`);
        }
        if (route === '/harness' && viewport.width <= 600) {
          const canvas = await page.locator('#viewport').boundingBox();
          const activity = await page.locator('#activity').boundingBox();
          assert.ok(activity.y >= canvas.y + canvas.height, 'Board activity overlaps the mobile canvas');
        }
        await page.screenshot({path: path.join(output, `${viewport.width}-${route.slice(1)}.png`)});
        receipts.push({viewport, route, active: labels[index], nav: bar});
      }
      await page.goto(base + '/harness?load=preset-curator#x=10&y=20&z=0.2', {waitUntil: 'domcontentloaded'});
      await page.locator('#application-nav').waitFor();
      const saved = await page.evaluate(() => { history.replaceState(null, '', '/harness?load=preset-curator#x=10&y=20&z=0.2'); window.dispatchEvent(new Event('hashchange')); return location.pathname + location.search + location.hash; });
      if (viewport.width < 960) await page.getByRole('button', {name: 'Navigation menu'}).click();
      await page.locator('#application-links [data-destination=graal]').click();
      await page.waitForURL('**/graal');
      assert.equal(context.pages().length, 1);
      assert.equal(await page.locator('#application-links [data-destination=board]').getAttribute('href'), saved);
      await page.goBack({waitUntil: 'domcontentloaded'});
      assert.equal(new URL(page.url()).pathname, '/harness');
      await page.goto(base + '/graal', {waitUntil: 'domcontentloaded'});
      await page.locator('#application-nav').waitFor();
      await page.evaluate(() => {
        sessionStorage.setItem('trikeshed.application.views.v1', '42');
        window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true}));
      });
      assert.equal(await page.locator('#application-links [data-destination=board]').getAttribute('href'), '/harness');
      await page.evaluate(() => {
        sessionStorage.setItem('trikeshed.application.views.v1', JSON.stringify({board: '/harness?load=preset-shake#x=1&y=2&z=0.5', panels: 'https://example.com/panels'}));
        window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true}));
      });
      assert.equal(await page.locator('#application-links [data-destination=board]').getAttribute('href'), '/harness?load=preset-shake#x=1&y=2&z=0.5');
      assert.equal(await page.locator('#application-nav .application-brand').getAttribute('href'), '/harness?load=preset-shake#x=1&y=2&z=0.5');
      assert.equal(await page.locator('#application-links [data-destination=panels]').getAttribute('href'), '/panels');
      await page.goto(base + '/harness?load=preset-curator', {waitUntil: 'domcontentloaded'});
      await page.waitForFunction(() => typeof Harness !== 'undefined' && Harness.ready && Harness.selected === 'preset-curator');
      await page.evaluate(() => {
        const node = G.nodes.find(node => node._program === Harness.selected);
        if (!node) throw new Error('The loaded Board has no editable node');
        node.x += 12;
        Harness.changed();
        if (!Harness.dirty) throw new Error('The Board did not retain the local edit');
      });
      let warned = false;
      page.once('dialog', async dialog => {
        warned = dialog.type() === 'beforeunload';
        await dialog.dismiss();
      });
      if (viewport.width < 960) await page.getByRole('button', {name: 'Navigation menu'}).click();
      await page.locator('#application-links [data-destination=graal]').click();
      assert.ok(warned, 'Navigation did not warn about the unpublished Board edit');
      assert.equal(new URL(page.url()).pathname, '/harness');
      assert.ok(await page.evaluate(() => Harness.dirty && Harness.drafts.has('preset-curator')));
      await context.close();
    }
    console.log(JSON.stringify({sourceOverlay: source, output, receipts}, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
