from playwright.sync_api import sync_playwright

def run_cuj(page):
    import os

    with open('src/commonMain/resources/web/index.html', 'r') as f:
        html_content = f.read()

    with open('src/commonMain/resources/web/styles.css', 'r') as f:
        css_content = f.read()

    html_content = html_content.replace('{{STYLES}}', css_content)

    # Let's inject a dummy table to see the behavior
    table_html = """
    <table class="sheet" style="margin: 100px;">
        <tr>
            <td tabindex="0" id="test-cell-1">Click me</td>
            <td tabindex="0" id="test-cell-2">Focus me</td>
        </tr>
    </table>
    """
    html_content = html_content.replace('<div class="app">', table_html + '<div class="app">')

    with open('temp_test.html', 'w') as f:
        f.write(html_content)

    file_url = f"file://{os.path.abspath('temp_test.html')}"
    page.goto(file_url)
    page.wait_for_timeout(500)

    # 1. Click to see the jarring focus outline
    page.locator("#test-cell-1").click()
    page.wait_for_timeout(500)
    page.screenshot(path="cell_mouse_click.png")

    # 2. Keyboard focus
    page.locator("#test-cell-2").focus()
    page.wait_for_timeout(500)
    page.screenshot(path="cell_keyboard_focus.png")

if __name__ == "__main__":
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context()
        page = context.new_page()
        try:
            run_cuj(page)
        finally:
            context.close()
            browser.close()
