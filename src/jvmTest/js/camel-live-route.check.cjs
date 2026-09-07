"use strict";
/**
 * A long-lived camel route, WATCHED — the rendered half of the CamelRuntime claim.
 *
 * CamelRuntimeTest asserts in-process that a `timer:` consumer keeps producing after the call
 * that started it returned. This asserts the half that matters to an operator: that the arrivals
 * reach a page they are looking at, without the page (or this script) asking for them. Every
 * assertion below reads RENDERED TEXT out of the Activity panel rather than Harness.board, so a
 * board that filled while nothing drew it would fail here.
 *
 *   BASE_URL=http://127.0.0.1:8897 ROUTE_ID=smb-intake node camel-live-route.check.cjs
 *
 * Expects a daemon with the route already up (POST /api/lcnc/run with a vm.camel.up node).
 */
const {chromium} = require("playwright");
const assert = require("node:assert/strict"), path = require("node:path");

const base = process.env.BASE_URL || "http://127.0.0.1:8897";
const ROUTE = process.env.ROUTE_ID || "smb-intake";
const KEY = "camel/route/" + ROUTE + "/exchange";
const out = process.env.OUT_DIR || ".";
const launch = process.env.CHROME_PATH ? {executablePath: process.env.CHROME_PATH} : {};

/** The highest revision the Activity panel is currently SHOWING for our key. */
const renderedRevision = page => page.evaluate(key => {
  const text = document.querySelector("#activity")?.innerText || "";
  const revisions = [];
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].includes(key)) continue;
    const stamp = (lines[i + 1] || "").match(/#(\d+)/);
    if (stamp) revisions.push(Number(stamp[1]));
  }
  return revisions.length ? Math.max(...revisions) : null;
}, KEY);

(async () => {
  const browser = await chromium.launch({headless: true, ...launch});
  const page = await browser.newPage({viewport: {width: 1600, height: 1000}});
  const errors = []; page.on("pageerror", e => errors.push(e.message));
  try {
    await page.goto(base + "/harness", {waitUntil: "domcontentloaded"});
    await page.waitForFunction(() => typeof Harness !== "undefined" && Harness.ready, null, {timeout: 60000});
    await page.waitForFunction(() => document.querySelector("#connection")?.textContent === "Live", null, {timeout: 30000});

    // The route's own territory, drawn from the key prefix — camel is a place on the board.
    await page.waitForFunction(() => (document.body.innerText || "").includes("camel"), null, {timeout: 30000});

    // Arrivals, as text on the screen.
    await page.waitForFunction(k => (document.querySelector("#activity")?.innerText || "").includes(k), KEY, {timeout: 30000});
    const first = await renderedRevision(page);
    assert.ok(Number.isFinite(first), "the Activity panel must show a revision for " + KEY);

    // NOTHING is requested here. If the route had died with the run that started it, the panel
    // would stand still and this times out — which is the claim, watched rather than sampled.
    await page.waitForFunction(
      ([key, seen]) => {
        const text = document.querySelector("#activity")?.innerText || "";
        const lines = text.split("\n");
        for (let i = 0; i < lines.length; i++) {
          if (!lines[i].includes(key)) continue;
          const stamp = (lines[i + 1] || "").match(/#(\d+)/);
          if (stamp && Number(stamp[1]) >= seen + 3) return true;
        }
        return false;
      },
      [KEY, first],
      {timeout: 30000},
    );
    const second = await renderedRevision(page);

    // The lifecycle entry says what it is and where it reaches, and carries NO tally — the live
    // count is the exchange key's own sequence, and two surfaces disagreeing is worse than one.
    const lifecycle = await page.evaluate(k => Harness.board[k], "camel/route/" + ROUTE);
    assert.equal(lifecycle.status, "Started", "the route should be up");
    assert.ok(!("exchanges" in lifecycle), "no stale tally in the lifecycle fact: " + JSON.stringify(lifecycle));

    await page.screenshot({path: path.join(out, "camel-live-route.png")});
    console.log(JSON.stringify({
      key: KEY, renderedFirst: first, renderedSecond: second, advancedBy: second - first,
      lifecycle, pageErrors: errors,
    }, null, 2));
    assert.ok(second > first, "the rendered revision must advance on a page that asked for nothing");
    assert.equal(errors.length, 0, "page errors: " + errors.join(" | "));
    console.log("\nPASS — the route kept producing and the harness rendered every arrival.");
  } finally {
    await browser.close();
  }
})().catch(e => { console.error("FAIL:", e.message); process.exit(1); });
