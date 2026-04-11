package borg.literbike.modelmux

/**
 * Toolbar UI HTML rendering.
 * Ported from literbike/src/modelmux/toolbar_ui.rs.
 *
 * Renders a complete HTML page for the Litebike Toolbar Control Plane.
 */
fun renderToolbarUi(): String = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Litebike Toolbar Control Plane</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 220'%3E%3Cpath d='M12 72C45 68 60 80 66 105V208' fill='none' stroke='%231a1a1a' stroke-width='9' stroke-linecap='round'/%3E%3Cpath d='M188 72C155 68 140 80 134 105V208' fill='none' stroke='%231a1a1a' stroke-width='9' stroke-linecap='round'/%3E%3Ccircle cx='100' cy='99' r='27' fill='white' stroke='%231a1a1a' stroke-width='3.5'/%3E%3Cpath d='M66 130H134' fill='none' stroke='%231a1a1a' stroke-width='3.5' stroke-linecap='round'/%3E%3Cpath d='M66 208A34 44 0 0 1 134 208' fill='none' stroke='%231a1a1a' stroke-width='9' stroke-linecap='round'/%3E%3Cpath d='M66 208A34 28 0 0 1 134 208' fill='none' stroke='%231a1a1a' stroke-width='3.5' stroke-linecap='round'/%3E%3C/svg%3E">
    <style>
        :root {
            --bg: #071019;
            --bg-2: #0b1621;
            --card: rgba(11, 22, 33, 0.9);
            --card-strong: rgba(8, 16, 24, 0.95);
            --line: rgba(125, 211, 252, 0.18);
            --line-strong: rgba(125, 211, 252, 0.38);
            --text: #e8f0f7;
            --muted: #95a8b8;
            --accent: #6ef1b6;
            --accent-2: #8cc7ff;
            --warn: #ffd479;
            --danger: #ff8a8a;
            --shadow: 0 22px 54px rgba(0, 0, 0, 0.4);
            --pill: rgba(13, 30, 43, 0.84);
        }

        * { box-sizing: border-box; }
        body {
            margin: 0;
            min-height: 100vh;
            font-family: "IBM Plex Mono", "SFMono-Regular", Menlo, monospace;
            color: var(--text);
            background:
                radial-gradient(circle at top left, rgba(110, 241, 182, 0.1), transparent 25%),
                radial-gradient(circle at top right, rgba(140, 199, 255, 0.12), transparent 28%),
                linear-gradient(180deg, #050b11 0%, #071019 45%, #04080c 100%);
        }
        .shell {
            width: min(1220px, calc(100vw - 2rem));
            margin: 0 auto;
            padding: 1.5rem 0 2rem;
        }
        .masthead {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
            gap: 1.5rem;
            margin-bottom: 1.4rem;
        }
        .eyebrow,
        .section-kicker {
            display: inline-block;
            margin: 0 0 0.75rem;
            color: var(--warn);
            font-size: 0.72rem;
            letter-spacing: 0.22rem;
            text-transform: uppercase;
        }
        h1, h2, p, ul { margin-top: 0; }
        h1 {
            margin-bottom: 0.45rem;
            font-size: clamp(2.2rem, 4vw, 3.8rem);
            line-height: 0.94;
            letter-spacing: 0.16rem;
            text-transform: uppercase;
        }
        .subtitle, .card-copy, .menu-copy-line, .surface-copy {
            color: var(--muted);
            line-height: 1.55;
        }
        .tag-row, .pill-row, .action-row, .status-bar {
            display: flex;
            gap: 0.65rem;
            flex-wrap: wrap;
        }
        .tag, .pill, .surface-chip {
            padding: 0.36rem 0.68rem;
            border: 1px solid var(--line);
            border-radius: 999px;
            background: var(--pill);
            color: var(--accent-2);
            font-size: 0.72rem;
            letter-spacing: 0.08rem;
            text-transform: uppercase;
        }
        .menu-wrap {
            position: relative;
            width: min(32rem, 100%);
        }
        .menu-trigger {
            display: inline-flex;
            align-items: center;
            gap: 0.95rem;
            width: 100%;
            padding: 0.8rem 1rem;
            border: 1px solid var(--line);
            border-radius: 999px;
            background:
                linear-gradient(135deg, rgba(140, 199, 255, 0.14), rgba(110, 241, 182, 0.08)),
                rgba(7, 13, 20, 0.93);
            color: var(--text);
            box-shadow: var(--shadow);
            cursor: pointer;
        }
        .menu-trigger:hover,
        .menu-trigger:focus-visible {
            outline: none;
            border-color: var(--line-strong);
            transform: translateY(-1px);
        }
        .menu-icon-shell {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 3.3rem;
            height: 3.3rem;
            flex-shrink: 0;
            border-radius: 999px;
            background:
                radial-gradient(circle at 30% 30%, rgba(255, 255, 255, 0.55), rgba(255, 255, 255, 0) 48%),
                radial-gradient(circle at 50% 45%, #f2f0e8, #ddd8c9 68%, #bcb5a0 100%);
            border: 1px solid #b8b29d;
        }
        .menu-icon {
            width: 2.68rem;
            height: 2.68rem;
            display: block;
        }
        .menu-copy {
            display: grid;
            gap: 0.2rem;
            text-align: left;
        }
        .menu-label {
            color: var(--accent);
            font-size: 0.9rem;
            letter-spacing: 0.28rem;
            text-transform: uppercase;
        }
        .menu-panel {
            position: absolute;
            top: calc(100% + 0.85rem);
            right: 0;
            z-index: 10;
            width: min(48rem, calc(100vw - 2rem));
            padding: 1rem;
            border: 1px solid var(--line);
            border-radius: 1.35rem;
            background:
                linear-gradient(180deg, rgba(15, 29, 41, 0.98), rgba(7, 13, 20, 0.98)),
                rgba(7, 13, 20, 0.98);
            box-shadow: var(--shadow);
        }
        .menu-panel[hidden] { display: none; }
        .menu-section + .menu-section {
            margin-top: 1rem;
            padding-top: 1rem;
            border-top: 1px solid rgba(117, 196, 255, 0.12);
        }
        .section-head {
            display: flex;
            align-items: baseline;
            justify-content: space-between;
            gap: 0.75rem;
            margin-bottom: 0.8rem;
        }
        .section-head h2 {
            margin: 0;
            font-size: 1rem;
            letter-spacing: 0.04rem;
        }
        .hero, .card {
            padding: 1rem;
            border: 1px solid rgba(117, 196, 255, 0.14);
            border-radius: 1rem;
            background: var(--card);
        }
        .hero { margin-bottom: 1rem; }
        .grid {
            display: grid;
            grid-template-columns: repeat(3, minmax(0, 1fr));
            gap: 1rem;
        }
        .surface-list, .route-list {
            display: grid;
            gap: 0.8rem;
        }
        .surface-card {
            padding: 0.9rem;
            border: 1px solid rgba(117, 196, 255, 0.12);
            border-radius: 0.95rem;
            background: var(--card-strong);
        }
        .surface-head {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 0.6rem;
            margin-bottom: 0.55rem;
        }
        .surface-head strong {
            font-size: 0.92rem;
            letter-spacing: 0.05rem;
            text-transform: uppercase;
        }
        .ok { color: var(--accent); }
        .warn { color: var(--warn); }
        .danger { color: var(--danger); }
        button.action-pill {
            padding: 0.68rem 0.9rem;
            border-radius: 0.9rem;
            border: 1px solid var(--line);
            background: rgba(10, 18, 27, 0.92);
            color: var(--text);
            font: inherit;
            cursor: pointer;
        }
        button.action-pill:hover,
        button.action-pill:focus-visible {
            outline: none;
            border-color: var(--line-strong);
        }
        code.block {
            display: block;
            padding: 0.75rem 0.82rem;
            border-radius: 0.85rem;
            background: rgba(6, 11, 17, 0.82);
            border: 1px solid rgba(117, 196, 255, 0.12);
            color: var(--text);
            font-size: 0.78rem;
            overflow-wrap: anywhere;
        }
        .status-bar {
            margin-top: 1rem;
            padding: 0.9rem 1rem;
            border: 1px solid rgba(117, 196, 255, 0.12);
            border-radius: 999px;
            background: rgba(7, 13, 20, 0.88);
            align-items: center;
        }
        .dot {
            width: 0.58rem;
            height: 0.58rem;
            border-radius: 999px;
            display: inline-block;
            margin-right: 0.45rem;
        }
        .dot.green { background: var(--accent); }
        .dot.amber { background: var(--warn); }
        .dot.red { background: var(--danger); }
        .mono { font-family: inherit; }
        @media (max-width: 900px) {
            .masthead { flex-direction: column; }
            .grid { grid-template-columns: 1fr; }
            .menu-panel {
                position: static;
                width: 100%;
                margin-top: 0.85rem;
            }
        }
    </style>
</head>
<body>
    <div class="shell">
        <header class="masthead">
            <div>
                <p class="eyebrow">Recovered operator surface</p>
                <h1>litebike menubar control</h1>
                <p class="subtitle">Titlebar-style menu, backed by live toolbar state and control actions, restoring the visible gateway surface instead of another dead icon path.</p>
                <div class="tag-row">
                    <span class="tag">gateway facade</span>
                    <span class="tag">live toolbar state</span>
                    <span class="tag">titlebar menu</span>
                </div>
            </div>
            <div class="menu-wrap">
                <button class="menu-trigger" type="button" aria-expanded="false" aria-controls="toolbar-menu">
                    <span class="menu-icon-shell" aria-hidden="true">
                        <svg class="menu-icon" viewBox="0 0 200 220" aria-hidden="true">
                            <path d="M 12 72 C 45 68 60 80 66 105 L 66 208" fill="none" stroke="#1a1a1a" stroke-width="9" stroke-linecap="round" />
                            <path d="M 188 72 C 155 68 140 80 134 105 L 134 208" fill="none" stroke="#1a1a1a" stroke-width="9" stroke-linecap="round" />
                            <circle cx="100" cy="99" r="27" fill="white" stroke="#1a1a1a" stroke-width="3.5" />
                            <line x1="66" y1="130" x2="134" y2="130" stroke="#1a1a1a" stroke-width="3.5" stroke-linecap="round" />
                            <path d="M 66 208 A 34 44 0 0 1 134 208" fill="none" stroke="#1a1a1a" stroke-width="9" stroke-linecap="round" />
                            <path d="M 66 208 A 34 28 0 0 1 134 208" fill="none" stroke="#1a1a1a" stroke-width="3.5" stroke-linecap="round" />
                        </svg>
                    </span>
                    <span class="menu-copy">
                        <span class="menu-label">Toolbar</span>
                        <span class="menu-copy-line" id="menu-caption">loading live route surface</span>
                    </span>
                </button>
                <section class="menu-panel" id="toolbar-menu" hidden>
                    <div class="menu-section">
                        <div class="section-head">
                            <span class="section-kicker">Launch</span>
                            <h2>Live route summary</h2>
                        </div>
                        <div class="route-list" id="route-summary"></div>
                    </div>
                    <div class="menu-section">
                        <div class="section-head">
                            <span class="section-kicker">Actions</span>
                            <h2>Control shortcuts</h2>
                        </div>
                        <div class="action-row">
                            <button class="action-pill" data-action="rescan_env">Rescan env</button>
                            <button class="action-pill" data-action="reset_runtime">Reset runtime</button>
                            <button class="action-pill" id="toggle-streaming">Toggle streaming</button>
                            <button class="action-pill" id="copy-toolbar-json">Copy toolbar curl</button>
                        </div>
                    </div>
                    <div class="menu-section">
                        <div class="section-head">
                            <span class="section-kicker">Status</span>
                            <h2>Operator checks</h2>
                        </div>
                        <code class="block" id="toolbar-curl">curl -s http://localhost:11434/toolbar/state | jq</code>
                    </div>
                </section>
            </div>
        </header>

        <section class="hero">
            <div class="section-head">
                <span class="section-kicker">Control plane</span>
                <h2>Proxy, route, and env posture</h2>
            </div>
            <p class="card-copy" id="hero-copy">Loading toolbar state from the local gateway.</p>
            <div class="pill-row" id="hero-pills"></div>
        </section>

        <section class="grid">
            <article class="card">
                <div class="section-head">
                    <span class="section-kicker">Runtime</span>
                    <h2>Service</h2>
                </div>
                <div class="surface-list" id="service-panel"></div>
            </article>
            <article class="card">
                <div class="section-head">
                    <span class="section-kicker">Facade</span>
                    <h2>Surfaces</h2>
                </div>
                <div class="surface-list" id="surface-panel"></div>
            </article>
            <article class="card">
                <div class="section-head">
                    <span class="section-kicker">Debt</span>
                    <h2>Backlog and keymux</h2>
                </div>
                <div class="surface-list" id="debt-panel"></div>
            </article>
        </section>

        <footer class="status-bar">
            <span id="status-pill"><span class="dot amber"></span> Loading</span>
            <span id="status-route">route: pending</span>
            <span id="status-env">env: pending</span>
            <span id="status-time">waiting for toolbar state</span>
        </footer>
    </div>

    <script>
        const menuTrigger = document.querySelector('.menu-trigger');
        const menuPanel = document.getElementById('toolbar-menu');
        const menuCaption = document.getElementById('menu-caption');
        const routeSummary = document.getElementById('route-summary');
        const heroCopy = document.getElementById('hero-copy');
        const heroPills = document.getElementById('hero-pills');
        const servicePanel = document.getElementById('service-panel');
        const surfacePanel = document.getElementById('surface-panel');
        const debtPanel = document.getElementById('debt-panel');
        const statusPill = document.getElementById('status-pill');
        const statusRoute = document.getElementById('status-route');
        const statusEnv = document.getElementById('status-env');
        const statusTime = document.getElementById('status-time');
        const toggleStreaming = document.getElementById('toggle-streaming');
        const copyToolbarJson = document.getElementById('copy-toolbar-json');

        let latestState = null;

        menuTrigger.addEventListener('click', () => {
            const open = menuPanel.hasAttribute('hidden') === false;
            if (open) {
                menuPanel.setAttribute('hidden', '');
                menuTrigger.setAttribute('aria-expanded', 'false');
            } else {
                menuPanel.removeAttribute('hidden');
                menuTrigger.setAttribute('aria-expanded', 'true');
            }
        });

        document.addEventListener('click', (event) => {
            if (!menuPanel.contains(event.target) && !menuTrigger.contains(event.target)) {
                menuPanel.setAttribute('hidden', '');
                menuTrigger.setAttribute('aria-expanded', 'false');
            }
        });

        function setStatusTone(status) {
            if (status === 'running') {
                return ['green', 'RUNNING'];
            }
            if (status === 'degraded') {
                return ['amber', 'DEGRADED'];
            }
            return ['red', 'COLD'];
        }

        function cardRow(label, value, tone) {
            const toneClass = tone ? ' ' + tone : '';
            return `<div class="surface-card"><div class="surface-head"><strong>${"label"}</strong><span class="${toneClass.trim()}">${"value"}</span></div></div>`;
        }

        function detailRow(label, value, copy) {
            return `<div class="surface-card"><div class="surface-head"><strong>${"label"}</strong><span>${copy || ''}</span></div><div class="surface-copy">${"value"}</div></div>`;
        }

        function renderState(state) {
            latestState = state;
            const routeLabel = [state.route.provider, state.route.model].filter(Boolean).join(' / ') || state.route.family;
            const [dotTone, statusLabel] = setStatusTone(state.service.status);

            menuCaption.textContent = routeLabel;
            heroCopy.textContent = `Unified port ${state.service.port} on ${state.service.bind_address} with ${state.route.family} routing and ${state.surfaces.length} published surfaces.`;
            heroPills.innerHTML = [
                `<span class="pill">${state.route.family}</span>`,
                `<span class="pill">${state.service.bind_address}:${state.service.port}</span>`,
                `<span class="pill">${state.runtime.streaming_enabled ? 'streaming on' : 'streaming off'}</span>`,
                `<span class="pill">${state.env.confidence} env confidence</span>`
            ].join('');

            routeSummary.innerHTML = [
                detailRow('provider', state.route.provider || 'none pinned', state.route.failover_enabled ? 'failover on' : 'failover off'),
                detailRow('model', state.route.model || 'none pinned', state.route.unified_agent_port ? 'unified 8888' : 'port-specific'),
                detailRow('available actions', state.available_actions.join(', '), `${state.available_actions.length} actions`)
            ].join('');

            servicePanel.innerHTML = [
                detailRow('service status', state.service.status, statusLabel),
                detailRow('listener', `${state.service.bind_address}:${state.service.port}`, state.service.manager),
                detailRow('runtime flags', `streaming=${state.runtime.streaming_enabled}, claude_rewrite=${state.runtime.claude_rewrite_enabled}`, '')
            ].join('');

            surfacePanel.innerHTML = state.surfaces.map((surface) => {
                const tone = surface.available ? 'ok' : 'warn';
                return `<div class="surface-card"><div class="surface-head"><strong>${surface.kind}</strong><span class="${tone}">${surface.available ? 'live' : 'offline'}</span></div><div class="surface-copy">${surface.detail}</div></div>`;
            }).join('');

            debtPanel.innerHTML = [
                detailRow('env keys', `${state.env.recognized_keys} recognized, ${state.env.unknown_keys} unknown`, state.env.confidence),
                detailRow('debt', `${state.debt.open_items} open, ${state.debt.blocked_items} blocked`, state.debt.persistence),
                detailRow('source', state.debt.source_path || 'volatile memory only', '')
            ].join('');

            statusPill.innerHTML = `<span class="dot ${dotTone}"></span> ${statusLabel}`;
            statusRoute.textContent = `route: ${routeLabel}`;
            statusEnv.textContent = `env: ${state.env.confidence} (${state.env.recognized_keys}/${state.env.unknown_keys})`;
            statusTime.textContent = `updated: ${new Date().toLocaleTimeString()}`;
            toggleStreaming.textContent = state.runtime.streaming_enabled ? 'Disable streaming' : 'Enable streaming';
        }

        async function loadState() {
            try {
                const response = await fetch('/toolbar/state');
                if (!response.ok) {
                    throw new Error(`toolbar/state ${response.status}`);
                }
                const state = await response.json();
                renderState(state);
            } catch (error) {
                heroCopy.textContent = `Failed to load toolbar state: ${error.message}`;
                statusPill.innerHTML = '<span class="dot red"></span> OFFLINE';
                statusTime.textContent = 'toolbar endpoint unavailable';
            }
        }

        async function postAction(payload) {
            const response = await fetch('/toolbar/actions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            if (!response.ok) {
                const body = await response.text();
                throw new Error(body || `toolbar/actions ${response.status}`);
            }
            const state = await response.json();
            renderState(state);
        }

        document.querySelectorAll('[data-action]').forEach((button) => {
            button.addEventListener('click', async () => {
                const action = button.getAttribute('data-action');
                try {
                    await postAction({ action });
                } catch (error) {
                    statusTime.textContent = `action failed: ${error.message}`;
                }
            });
        });

        toggleStreaming.addEventListener('click', async () => {
            const enabled = !(latestState && latestState.runtime && latestState.runtime.streaming_enabled);
            try {
                await postAction({ action: 'set_streaming_enabled', enabled });
            } catch (error) {
                statusTime.textContent = `stream toggle failed: ${error.message}`;
            }
        });

        copyToolbarJson.addEventListener('click', async () => {
            const command = 'curl -s http://localhost:11434/toolbar/state | jq';
            try {
                await navigator.clipboard.writeText(command);
                statusTime.textContent = 'copied toolbar curl';
            } catch (error) {
                statusTime.textContent = `copy failed: ${error.message}`;
            }
        });

        loadState();
        window.setInterval(loadState, 5000);
    </script>
</body>
</html>
""".trimIndent()
