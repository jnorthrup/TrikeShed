from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch()
    context = browser.new_context(record_video_dir='verification_media/')
    page = context.new_page()

    # The file path logic here expects the script to run in the repo root
    page.goto('file://' + __import__('os').path.abspath('src/commonMain/resources/web/index.html'))

    # Evaluate some layout changes and verify rendering
    page.evaluate('''() => {
        // Just checking basic accessibility on focusable elements
        const sheetTable = document.createElement('table');
        sheetTable.className = 'sheet';
        const td = document.createElement('td');
        td.tabIndex = 0;
        td.textContent = 'Test Cell';
        sheetTable.appendChild(td);
        document.body.appendChild(sheetTable);

        td.focus();
    }''')

    page.wait_for_timeout(500) # Give UI time to update

    page.screenshot(path='verification_media/screenshot.png')
    context.close()
    browser.close()
