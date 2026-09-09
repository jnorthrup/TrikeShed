(function () {
  'use strict';
  if (document.getElementById('application-nav')) return;
  const destinations = [
    ['board', 'Board', '/harness', ['/blackboard', '/harness.html']],
    ['graal', 'Graal', '/graal', []],
    ['panels', 'Panels', '/panels', ['/panels.html']],
    ['documents', 'Documents', '/documents', ['/documents.html']],
    ['kanban', 'Kanban', '/kanban', ['/kanban.html']],
    ['hermes', 'Hermes', '/hermes', []],
    ['keymux', 'KeyMux', '/keymux', []],
    ['modelmux', 'ModelMux', '/modelmux', ['/mux/sessions', '/mux/stats', '/mux.html']],
    ['futon', 'Futon', '/futon', []]
  ];
  const path = location.pathname.replace(/\/$/, '') || '/';
  const current = destinations.find(([, , href, aliases]) => href === path || aliases.includes(path));
  if (!current) return;
  const storageKey = 'trikeshed.application.views.v1';
  function readViews() {
    try {
      const value = JSON.parse(sessionStorage.getItem(storageKey) || '{}');
      if (value && typeof value === 'object' && !Array.isArray(value)) return value;
    } catch (_) {}
    return {};
  }
  let views = readViews();
  function remember() {
    views = readViews();
    views[current[0]] = location.pathname + location.search + location.hash;
    try { sessionStorage.setItem(storageKey, JSON.stringify(views)); } catch (_) {}
  }
  function destinationHref([id, , href, aliases]) {
    if (typeof views[id] === 'string') {
      try {
        const saved = new URL(views[id], location.origin);
        if (saved.origin === location.origin && [href, ...aliases].includes(saved.pathname.replace(/\/$/, ''))) {
          return saved.pathname + saved.search + saved.hash;
        }
      } catch (_) {}
    }
    return href;
  }
  remember();
  window.addEventListener('pagehide', remember);
  window.addEventListener('hashchange', remember);
  window.addEventListener('beforeunload', event => {
    if (typeof Harness !== 'undefined' && (Harness.dirty || Harness.drafts?.size)) {
      event.preventDefault();
      event.returnValue = '';
    }
  });

  const nav = document.createElement('nav');
  nav.id = 'application-nav';
  nav.setAttribute('aria-label', 'Application');
  const brand = document.createElement('a');
  brand.className = 'application-brand';
  brand.href = destinationHref(destinations[0]);
  brand.textContent = 'TrikeShed';
  const active = document.createElement('span');
  active.className = 'application-current';
  active.textContent = current[1];
  const button = document.createElement('button');
  button.type = 'button';
  button.id = 'application-menu';
  button.title = 'Navigation menu';
  button.setAttribute('aria-label', 'Navigation menu');
  button.setAttribute('aria-expanded', 'false');
  button.setAttribute('aria-controls', 'application-links');
  const icon = document.createElement('i');
  icon.dataset.lucide = 'chevron-right';
  icon.setAttribute('aria-hidden', 'true');
  button.append(icon);
  const links = document.createElement('div');
  links.id = 'application-links';
  for (const destination of destinations) {
    const [id, label] = destination;
    const link = document.createElement('a');
    link.href = destinationHref(destination);
    link.textContent = label;
    link.dataset.destination = id;
    if (id === current[0]) link.setAttribute('aria-current', 'page');
    links.append(link);
  }
  function close(restoreFocus) {
    nav.classList.remove('menu-open');
    button.setAttribute('aria-expanded', 'false');
    if (restoreFocus) button.focus();
  }
  button.addEventListener('click', () => {
    const open = nav.classList.toggle('menu-open');
    button.setAttribute('aria-expanded', String(open));
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && nav.classList.contains('menu-open')) { close(true); event.stopPropagation(); }
    if (nav.contains(event.target)) event.stopPropagation();
  });
  document.addEventListener('pointerdown', event => { if (!nav.contains(event.target)) close(false); });
  nav.addEventListener('focusout', event => { if (!nav.contains(event.relatedTarget)) close(false); });
  nav.append(brand, active, button, links);
  document.body.prepend(nav);
  window.MuxIcons?.();
  document.addEventListener('DOMContentLoaded', () => window.MuxIcons?.(), {once: true});
  window.addEventListener('pageshow', () => {
    remember();
    brand.href = destinationHref(destinations[0]);
    for (const [index, link] of Array.from(links.children).entries()) link.href = destinationHref(destinations[index]);
    close(false);
  });
})();
