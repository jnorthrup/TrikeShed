#!/usr/bin/env python3
"""Drive the opt-in VisualSparqlBrowserTest against its printed loopback URL.

Run the JVM fixture with TRIKESHED_SPARQL_VISUAL_UI=1 and kotlinJsApps=spacegraph,
then pass the emitted LCNC_SIMULATION_UI URL as the only argument. This driver
uses real browser gestures and the fixture's real /sparql/visual JVM route.
"""

import json
import sys
import time
import traceback
import urllib.parse
import urllib.request

from playwright.sync_api import sync_playwright


def post(base, path, value):
    request = urllib.request.Request(
        base + path,
        data=json.dumps(value).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read()


def run(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost") or parsed.path != "/panels":
        raise ValueError("Expected the fixture's loopback /panels URL")
    base = f"{parsed.scheme}://{parsed.netloc}"
    failures = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(
                executable_path="/usr/bin/chromium",
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage", "--enable-unsafe-swiftshader"],
            )
            context = browser.new_context(viewport={"width": 1500, "height": 1000})
            page = context.new_page()
            page.on("pageerror", lambda error: failures.append(str(error)))
            page.goto(url, wait_until="domcontentloaded", timeout=30000)
            page.get_by_role("button", name="SPARQL query", exact=True).wait_for(timeout=30000)
            page.wait_for_function("() => window.G && window.G.nodes.some(n => n.type === 'sparql.select')", timeout=30000)
            assert page.locator("#runBtn").is_disabled(), "fixture SPARQL must remain inspection-only"

            initial_scratch = page.evaluate("() => JSON.stringify(window.serialize())")
            initial_graph = page.evaluate("() => { const d = window.serialize(); return JSON.stringify([d.nodes, d.wires, d.controls]); }")
            page.evaluate("s => localStorage.setItem('trikeshed.panels.v3', s)", initial_scratch)
            sql = page.locator('dialog[aria-label="Visual SPARQL query"]')
            result = sql.locator('[aria-live="polite"]')
            button = sql.get_by_role("button", name="Query", exact=True)
            dataset = sql.get_by_label("Dataset")
            page.get_by_role("button", name="SPARQL query", exact=True).click()
            button.click()
            assert "Choose" in result.inner_text(), "dataset must be explicit"
            assert page.request.get(base + "/_test/sparql/state").json()["queries"] == 0
            dataset.select_option("quad-store")
            button.click()
            page.wait_for_function("() => document.querySelector('dialog[aria-label=\"Visual SPARQL query\"] pre').textContent.includes('urn:a')", timeout=30000)
            answer = result.inner_text()
            assert "Dataset: quad-store" in answer and "SELECT" in answer, answer
            assert "urn:a" in answer and "urn:b" in answer and "second" in answer, answer
            assert "<img src=x onerror=alert(1)>" in answer, answer
            assert result.locator("img").count() == 0, "source text must not become HTML"

            with page.expect_popup() as popup:
                sql.get_by_role("link", name="Open an editable SPARQL example in a new tab").click()
            starter = popup.value
            starter.on("pageerror", lambda error: failures.append(str(error)))
            starter.wait_for_load_state("domcontentloaded")
            starter.get_by_role("button", name="SPARQL query", exact=True).wait_for(timeout=30000)
            starter.wait_for_function("() => window.G && window.G.nodes.some(n => n.type === 'sparql.select')", timeout=30000)
            assert starter.evaluate("() => window.G.controls.inspectionOnly === true")
            assert starter.locator("#runBtn").is_disabled()
            column = starter.locator('.node[data-type="sparql.variable"] .params input').first
            column.wait_for(timeout=10000)
            assert column.input_value() == "?subject"
            column.fill("?edited")
            assert page.evaluate("() => localStorage.getItem('trikeshed.panels.v3')") == initial_scratch
            assert page.evaluate("() => { const d = window.serialize(); return JSON.stringify([d.nodes, d.wires, d.controls]); }") == initial_graph
            assert page.request.get(base + "/_test/sparql/state").json()["queries"] == 1, "starter must not run a query"
            starter.close()

            page.keyboard.press("Escape")
            sql.wait_for(state="hidden", timeout=10000)
            page.get_by_role("button", name="SPARQL query", exact=True).click()
            dataset.select_option("quad-store")
            button.click()
            deadline = time.monotonic() + 20
            while not page.request.get(base + "/_test/sparql/state").json()["held"]:
                if time.monotonic() > deadline:
                    raise TimeoutError("The second production SPARQL response was not held")
                time.sleep(0.05)
            sql.get_by_role("button", name="Close", exact=True).click()
            page.get_by_role("button", name="SPARQL query", exact=True).click()
            assert result.inner_text() == ""
            with page.expect_response(lambda response: response.url.endswith("/sparql/visual") and response.request.method == "POST", timeout=30000) as delayed:
                page.request.post(base + "/_test/sparql/release")
            delayed.value.finished()
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
            assert result.inner_text() == "", "a stale response must not populate a reopened dialog"

            dataset.select_option("quad-store+rete")
            sql.get_by_label("Projected proposal-fact variable").fill("?subject")
            button.click()
            links = result.locator('a[href^="/documents#curation/"]')
            links.first.wait_for(timeout=30000)
            assert links.count() == 2, "both conflicting reported CIDs must remain visible"
            assert {link.get_attribute("href") for link in links.all()} == {
                "/documents#curation/sha256%3A" + "a" * 64,
                "/documents#curation/sha256%3A" + "b" * 64,
            }
            assert all(link.get_attribute("target") == "_blank" for link in links.all())
            assert "(unverified)" in result.inner_text()
            assert "not query witnesses" in result.inner_text()
            assert result.locator('a[href^="javascript:"]').count() == 0
            assert page.request.get(base + "/_test/sparql/state").json()["queries"] == 3
            assert not failures, failures
            post(base, "/_test/sparql/report", {"verified": True, "subjects": 2, "reportedReferences": 2, "staleSuppressed": True, "scratchPreserved": True})
            context.close()
            browser.close()
    except Exception:
        detail = traceback.format_exc()
        post(base, "/_test/sparql/failure", {"error": detail})
        raise
    finally:
        post(base, "/_test/finish", {})


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: visual-sparql-browser.py http://127.0.0.1:PORT/panels?load=retained-check")
    run(sys.argv[1])
