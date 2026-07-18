package borg.trikeshed.forge

fun forgePersistenceScript(): String = """
(() => {
  const STORAGE_KEY = 'forge.workspace.v4';
  const CACHE_NAME = 'forge-webcache-v1';
  const SNAPSHOT_CACHE_URL = './forge-workspace.snapshot.json';
  const DB_NAME = 'forge-local-first-v1';
  const DB_VERSION = 1;
  const SNAPSHOT_STORE = 'workspaceSnapshots';
  const EVENT_STORE = 'reactorEvents';
  const REACTOR_LOG_LIMIT = 24;
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const seed = JSON.parse(document.getElementById('forge-seed').textContent);
  let state = loadState();
  let camera = normalizeCamera(state.spatial.camera);
  let dragCamera = null;
  let cameraAnimationFrame = null;
  let cameraFrameTimestamp = 0;
  let cameraSavePending = false;
  let persistenceDbPromise = null;
  let persistenceWritePromise = Promise.resolve();
  let persistenceRotationSnapshot = null;

  registerPwaShell();

  const navRoot = document.getElementById('nav-root');
  const usecaseRoot = document.getElementById('usecase-root');
  const docRoot = document.getElementById('doc-root');
  const boardRoot = document.getElementById('board-root');
  const reactorRoot = document.getElementById('reactor-root');
  const spatialShell = document.getElementById('spatial-shell');
  const spatialRoot = document.getElementById('spatial-root');
  const zoomSlider = document.getElementById('zoom-slider');
  const zoomLabel = document.getElementById('zoom-label');

  zoomSlider.value = String(camera.zoom);

  document.getElementById('add-item-top').addEventListener('click', () => {
    addItem();
    render();
  });
  document.getElementById('reset-workspace').addEventListener('click', async () => {
    await clearPersistedWorkspace();
    state = normalizeState(structuredClone(seed));
    camera = normalizeCamera(state.spatial.camera);
    stopCameraAnimation();
    ensureSelection();
    recordReactor('CacheStored', 'workspace reset', 'pwa');
    saveState();
    render();
  });
  document.getElementById('focus-board').addEventListener('click', () => {
    state.spatial.focusMode = 'board';
    camera.zoom = clamp(0.82, camera.minZoom, camera.maxZoom);
    clearCameraMomentum();
    centerBoard();
    saveState();
    renderSpatial();
  });
  document.getElementById('focus-selected').addEventListener('click', () => {
    state.spatial.focusMode = 'selected';
    camera.zoom = clamp(Math.max(camera.zoom, 1.9), camera.minZoom, camera.maxZoom);
    clearCameraMomentum();
    focusSelected();
    saveState();
    renderSpatial();
  });
  zoomSlider.addEventListener('input', (event) => {
    camera.zoom = clamp(Number(event.target.value) || 0.82, camera.minZoom, camera.maxZoom);
    camera.vz = 0;
    syncSpatialFromCamera();
    state.spatial.focusMode = 'manual';
    renderSpatial();
  });
  zoomSlider.addEventListener('change', () => saveState());
  bindSpatialGestures();

  const graphFitBtn = document.getElementById('btn-graph-fit');
  const graphCenterBtn = document.getElementById('btn-graph-center');
  const graphSeedBtn = document.getElementById('btn-graph-seed');
  const graphZoomSlider = document.getElementById('graph-zoom-slider');
  const graphSpatialShell = document.getElementById('graph-spatial-shell');
  const graphSpatialRoot = document.getElementById('graph-spatial-root');
  const graphStatNodes = document.getElementById('graph-stat-nodes');
  const graphStatLinks = document.getElementById('graph-stat-links');

  // Graph transform state
  let graphNodes = [];
  let graphLinks = [];
  let graphTransform = { x: 0, y: 0, k: 1 };
  let graphDragCamera = null;

  // Seed demo graph from causalGraph in seed
  if (graphSeedBtn) {
    graphSeedBtn.addEventListener('click', () => {
      seedGraphFromCausalData();
      renderGraph();
    });
  }
  if (graphFitBtn) {
    graphFitBtn.addEventListener('click', () => { fitGraph(); renderGraph(); });
  }
  if (graphCenterBtn) {
    graphCenterBtn.addEventListener('click', () => { centerGraph(); renderGraph(); });
  }
  if (graphZoomSlider) {
    graphZoomSlider.addEventListener('input', (event) => {
      graphTransform.k = clamp(Number(event.target.value) || 1, 0.2, 3);
      renderGraph();
    });
  }
  if (graphSpatialShell) bindGraphGestures();

  // Auto-seed graph on init so it appears on load
  if (graphSeedBtn && seed.causalNodes && seed.causalNodes.length) {
    seedGraphFromCausalData();
    setTimeout(() => { fitGraph(); renderGraph(); }, 50);
  }

  // Drag-and-drop attachment support — stores blobs in IndexedDB as Confix-shaped records
  bindAttachmentDropZone();

  hydratePersistence();

  function loadState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return normalizeState(structuredClone(seed));
      const persisted = normalizePersistenceSnapshot(raw);
      if (!persisted) return normalizeState(JSON.parse(raw));
      return normalizeState(persisted.snapshot);
    } catch (_) {
      return normalizeState(structuredClone(seed));
    }
  }

  function normalizeState(raw) {
    const base = structuredClone(seed);
    const next = Object.assign(base, raw || {});
    next.title = typeof next.title === 'string' && next.title.trim() ? next.title : base.title;
    next.pageNotes = typeof next.pageNotes === 'string' ? next.pageNotes : base.pageNotes;
    next.columns = Array.isArray(raw && raw.columns) && raw.columns.length ? raw.columns : base.columns;
    next.items = Array.isArray(raw && raw.items) ? raw.items.map(normalizeItem) : base.items;
    next.useCases = Array.isArray(raw && raw.useCases) && raw.useCases.length ? raw.useCases : base.useCases;
    next.reactor = Object.assign({}, base.reactor, raw && raw.reactor ? raw.reactor : {});
    next.reactor.taxonomyNodeCount = Number(next.reactor.taxonomyNodeCount) || 0;
    next.reactor.signalFacetCount = Number(next.reactor.signalFacetCount) || 0;
    next.reactor.cacheStoredCount = Number(next.reactor.cacheStoredCount) || 0;
    next.reactor.lastEventKind = typeof next.reactor.lastEventKind === 'string' ? next.reactor.lastEventKind : 'INIT';
    next.reactor.lastEventTimestampMs = Number(next.reactor.lastEventTimestampMs) || 0;
    next.reactor.recentTaxonomyNodes = Array.isArray(next.reactor.recentTaxonomyNodes) ? next.reactor.recentTaxonomyNodes.slice(-6) : [];
    next.reactor.recentSignals = Array.isArray(next.reactor.recentSignals) ? next.reactor.recentSignals.slice(-8) : [];
    next.reactorLog = normalizeReactorLog(raw && raw.reactorLog);
    next.cache = normalizeCacheState(raw && raw.cache);
    next.spatial = Object.assign({}, base.spatial, raw && raw.spatial ? raw.spatial : {});
    next.spatial.zoom = clamp(Number(next.spatial.zoom) || Number(base.spatial.zoom) || 0.82, 0.45, 3.2);
    next.spatial.offsetX = Number(next.spatial.offsetX) || 0;
    next.spatial.offsetY = Number(next.spatial.offsetY) || 0;
    next.spatial.focusMode = typeof next.spatial.focusMode === 'string' ? next.spatial.focusMode : 'board';
    const rawCamera = raw && raw.spatial && raw.spatial.camera ? raw.spatial.camera : null;
    const sourceSpatial = raw && raw.spatial ? raw.spatial : null;
    const legacyCamera = rawCamera ? null : sourceSpatial;
    next.spatial.camera = normalizeCamera(rawCamera, legacyCamera);
    next.spatial.zoom = next.spatial.camera.zoom;
    next.spatial.offsetX = next.spatial.camera.x;
    next.spatial.offsetY = next.spatial.camera.y;
    next.causalNodes = Array.isArray(raw && raw.causalNodes) && raw.causalNodes.length ? raw.causalNodes : base.causalNodes;
    next.lcncEntities = Array.isArray(raw && raw.lcncEntities) && raw.lcncEntities.length ? raw.lcncEntities : base.lcncEntities;
    next.cascadeGrid = Array.isArray(raw && raw.cascadeGrid) && raw.cascadeGrid.length ? raw.cascadeGrid : base.cascadeGrid;
    ensureSelection(next);
    return next;
  }

  function normalizeCamera(raw, legacySpatial) {
    const seeded = seed.blackboard && seed.blackboard.camera ? seed.blackboard.camera : {};
    const legacy = legacySpatial || {};
    const source = Object.assign({}, seeded, raw || {});
    const hasLegacyX = legacy.offsetX !== undefined && legacy.offsetX !== null && Number.isFinite(Number(legacy.offsetX));
    const hasLegacyY = legacy.offsetY !== undefined && legacy.offsetY !== null && Number.isFinite(Number(legacy.offsetY));
    const hasLegacyZoom = legacy.zoom !== undefined && legacy.zoom !== null && Number.isFinite(Number(legacy.zoom));
    const hasRawX = raw && Number.isFinite(Number(raw.x));
    const hasRawY = raw && Number.isFinite(Number(raw.y));
    const hasRawZoom = raw && Number.isFinite(Number(raw.zoom));
    const minZoom = Number(source.minZoom) || 0.45;
    const maxZoom = Number(source.maxZoom) || 3.2;
    return {
      x: hasRawX ? Number(raw.x) : (hasLegacyX ? Number(legacy.offsetX) : (Number(source.x) || 0)),
      y: hasRawY ? Number(raw.y) : (hasLegacyY ? Number(legacy.offsetY) : (Number(source.y) || 0)),
      zoom: clamp(hasRawZoom ? Number(raw.zoom) : (hasLegacyZoom ? Number(legacy.zoom) : (Number(source.zoom) || 1)), minZoom, maxZoom),
      tilt: Number(source.tilt) || 0,
      vx: Number(source.vx) || 0,
      vy: Number(source.vy) || 0,
      vz: Number(source.vz) || 0,
      minZoom,
      maxZoom,
    };
  }

  function syncSpatialFromCamera() {
    state.spatial.camera = { ...camera };
    state.spatial.zoom = camera.zoom;
    state.spatial.offsetX = camera.x;
    state.spatial.offsetY = camera.y;
  }

  function normalizeCacheState(raw) {
    const next = Object.assign({
      hydrationSource: 'seed',
      hydrationTimestampMs: 0,
      snapshotTimestampMs: 0,
      lastLocalSaveMs: 0,
      lastPersistTarget: 'localStorage',
      status: 'local-only',
      persistenceMode: 'Dual',
      warning: '',
      wal: [],
      walLength: 0,
      rotationPending: false,
      rotationAtMs: 0,
      rotationSnapshot: null,
      snapshot: null,
    }, raw || {});
    next.hydrationSource = typeof next.hydrationSource === 'string' ? next.hydrationSource : 'seed';
    next.lastPersistTarget = typeof next.lastPersistTarget === 'string' ? next.lastPersistTarget : 'localStorage';
    next.status = typeof next.status === 'string' ? next.status : 'local-only';
    next.persistenceMode = next.persistenceMode === 'LocalStorageOnly' ? 'LocalStorageOnly' : 'Dual';
    next.warning = typeof next.warning === 'string' ? next.warning : '';
    next.hydrationTimestampMs = Number(next.hydrationTimestampMs) || 0;
    next.snapshotTimestampMs = Number(next.snapshotTimestampMs) || 0;
    next.lastLocalSaveMs = Number(next.lastLocalSaveMs) || 0;
    next.wal = Array.isArray(next.wal) ? next.wal.slice(-100) : [];
    next.walLength = next.wal.length;
    next.rotationPending = !!next.rotationPending;
    next.rotationAtMs = Number(next.rotationAtMs) || 0;
    next.rotationSnapshot = next.rotationSnapshot || null;
    next.snapshot = next.snapshot || null;
    return next;
  }

  function normalizeReactorLog(entries) {
    return Array.isArray(entries)
      ? entries
          .filter((entry) => entry && typeof entry.kind === 'string')
          .map((entry) => ({
            id: entry.id || nextId('evt'),
            kind: entry.kind,
            label: typeof entry.label === 'string' ? entry.label : '',
            source: typeof entry.source === 'string' ? entry.source : 'forge',
            timestampMs: Number(entry.timestampMs) || 0,
          }))
          .slice(-REACTOR_LOG_LIMIT)
      : [];
  }

  async function hydratePersistence() {
    const persisted = await loadPersistedSnapshot();
    if (persisted) {
      state = normalizeState(persisted.snapshot);
      camera = normalizeCamera(state.spatial.camera);
      state.cache.hydrationSource = persisted.source;
      state.cache.hydrationTimestampMs = Date.now();
      state.cache.status = 'hydrated';
      state.cache.lastPersistTarget = persisted.source;
      if (persisted.warning) {
        state.cache.warning = persisted.warning;
        state.cache.persistenceMode = 'LocalStorageOnly';
      }
    } else {
      state = normalizeState(state);
      camera = normalizeCamera(state.spatial.camera);
      state.cache.hydrationSource = 'localStorage';
      state.cache.hydrationTimestampMs = Date.now();
      state.cache.status = 'local-only';
    }
    const reactorLog = await loadPersistedReactorEvents();
    if (reactorLog.length) {
      state.reactorLog = normalizeReactorLog(reactorLog);
    }
    render();
  }

  function normalizeItem(item) {
    return {
      id: item && item.id ? item.id : nextId('item'),
      title: typeof item.title === 'string' ? item.title : 'Untitled work item',
      notes: typeof item.notes === 'string' ? item.notes : '',
      status: typeof item.status === 'string' ? item.status : 'col-backlog',
      priority: typeof item.priority === 'string' ? item.priority : 'medium',
      checklist: Array.isArray(item && item.checklist)
        ? item.checklist.map((check) => ({
            id: check && check.id ? check.id : nextId('check'),
            text: typeof check.text === 'string' ? check.text : '',
            checked: !!check.checked,
          }))
        : [],
    };
  }

  function saveState(mutation) {
    syncSpatialFromCamera();
    recordMutation(state, mutation || { kind: 'state-change', label: 'saveState', source: 'forge' });
  }

  function recordMutation(nextState, mutation) {
    const snapshot = stripPersistenceFields(nextState || state);
    const entry = normalizeMutationRecord(snapshot, mutation);
    state.cache = normalizeCacheState(state.cache);
    state.cache.lastLocalSaveMs = Date.now();
    const persisted = normalizePersistenceSnapshot(localStorage.getItem(STORAGE_KEY));
    const envelope = persisted || {
      version: 2,
      source: 'localStorage',
      snapshot: snapshot,
      wal: [],
      warning: '',
      lastPersistedAtMs: 0,
    };
    const wal = Array.isArray(envelope.wal) ? envelope.wal.slice() : [];
    wal.push(entry);
    let checkpoint = envelope.snapshot || snapshot;
    if (wal.length > 100) {
      checkpoint = wal[99].snapshot || snapshot;
      state.cache.rotationPending = true;
      state.cache.rotationAtMs = entry.timestampMs;
      state.cache.rotationSnapshot = checkpoint;
      state.cache.snapshotTimestampMs = entry.timestampMs;
      persistenceRotationSnapshot = checkpoint;
      state.cache.snapshot = checkpoint;
      state.cache.warning = state.cache.warning || '';
      state.cache.persistenceMode = 'Dual';
      state.cache.lastPersistTarget = 'localStorage';
      state.cache.status = 'local-only';
      state.cache.wal = wal.slice(100);
      state.cache.walLength = state.cache.wal.length;
      persistenceWritePromise = persistenceWritePromise
        .then(() => persistWorkspaceSnapshot())
        .catch((error) => console.warn('Forge snapshot persistence failed', error));
    } else {
      state.cache.rotationPending = false;
      state.cache.rotationSnapshot = null;
      state.cache.snapshot = checkpoint;
      state.cache.wal = wal;
      state.cache.walLength = wal.length;
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(encodePersistenceEnvelope(state.cache.snapshot || snapshot, state.cache, wal)));
  }

  function normalizeMutationRecord(snapshot, mutation) {
    const now = Date.now();
    const base = mutation || {};
    return {
      id: base.id || nextId('mut'),
      kind: typeof base.kind === 'string' ? base.kind : 'state-change',
      label: typeof base.label === 'string' ? base.label : '',
      source: typeof base.source === 'string' ? base.source : 'forge',
      timestampMs: Number(base.timestampMs) || now,
      snapshot: stripPersistenceFields(snapshot),
    };
  }

  function encodePersistenceEnvelope(snapshot, cacheState, walOverride) {
    return {
      version: 2,
      source: 'localStorage',
      snapshot: stripPersistenceFields(snapshot),
      wal: Array.isArray(walOverride) ? walOverride : (Array.isArray(cacheState && cacheState.wal) ? cacheState.wal : []),
      warning: cacheState && typeof cacheState.warning === 'string' ? cacheState.warning : '',
      lastPersistedAtMs: Number(cacheState && cacheState.snapshotTimestampMs) || 0,
    };
  }

  function stripPersistenceFields(value) {
    const snapshot = JSON.parse(JSON.stringify(value || {}));
    if (snapshot.cache) {
      delete snapshot.cache.wal;
      delete snapshot.cache.walLength;
      delete snapshot.cache.rotationPending;
      delete snapshot.cache.rotationAtMs;
      delete snapshot.cache.rotationSnapshot;
      delete snapshot.cache.snapshot;
      delete snapshot.cache.warning;
      delete snapshot.cache.persistenceMode;
    }
    return snapshot;
  }

  async function persistWorkspaceSnapshot() {
    const snapshot = persistenceRotationSnapshot || stripPersistenceFields(state);
    const now = Date.now();
    const envelope = normalizePersistenceSnapshot(localStorage.getItem(STORAGE_KEY)) || {
      version: 2,
      source: 'localStorage',
      snapshot,
      wal: [],
      warning: '',
      lastPersistedAtMs: 0,
    };
    const nextEnvelope = {
      version: envelope.version || 2,
      source: 'localStorage',
      snapshot: snapshot,
      wal: Array.isArray(state.cache.wal) ? state.cache.wal : [],
      warning: state.cache.warning || envelope.warning || '',
      lastPersistedAtMs: now,
    };
    state.cache.snapshotTimestampMs = now;
    state.cache.lastPersistTarget = 'indexeddb+cache';
    state.cache.status = 'persisted';
    state.cache.persistenceMode = 'Dual';
    state.cache.warning = '';
    persistenceRotationSnapshot = null;
    state.cache.rotationPending = false;
    state.cache.rotationSnapshot = null;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(nextEnvelope));
    try {
      await Promise.all([
        writeSnapshotToIndexedDb(snapshot),
        writeSnapshotToCache(snapshot),
      ]);
    } catch (error) {
      console.warn('Forge snapshot persistence failed', error);
      state.cache.persistenceMode = 'LocalStorageOnly';
      state.cache.warning = 'IndexedDB unavailable; falling back to localStorage';
      state.cache.lastPersistTarget = 'localStorage';
      state.cache.status = 'local-only';
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...nextEnvelope, warning: state.cache.warning }));
    }
  }

  function normalizePersistenceSnapshot(raw) {
    if (!raw) return null;
    try {
      const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
      if (!parsed || typeof parsed !== 'object') return null;
      if (parsed.snapshot) {
        const wal = Array.isArray(parsed.wal) ? parsed.wal.filter((entry) => entry && entry.snapshot).slice(-100) : [];
        return {
          version: Number(parsed.version) || 2,
          source: typeof parsed.source === 'string' ? parsed.source : 'localStorage',
          snapshot: parsed.snapshot,
          wal,
          warning: typeof parsed.warning === 'string' ? parsed.warning : '',
          lastPersistedAtMs: Number(parsed.lastPersistedAtMs) || 0,
        };
      }
      return {
        version: 1,
        source: 'localStorage',
        snapshot: parsed,
        wal: [],
        warning: '',
        lastPersistedAtMs: 0,
      };
    } catch (_) {
      return null;
    }
  }

  async function loadPersistedSnapshot() {
    const indexedDbSnapshot = await readSnapshotFromIndexedDb();
    if (indexedDbSnapshot) {
      const local = normalizePersistenceSnapshot(localStorage.getItem(STORAGE_KEY));
      const wal = local && Array.isArray(local.wal) ? local.wal : [];
      const snapshot = wal.length ? (wal[wal.length - 1].snapshot || indexedDbSnapshot) : indexedDbSnapshot;
      return { source: 'indexeddb', snapshot: normalizeState(snapshot), warning: local && local.warning ? local.warning : '' };
    }
    const local = normalizePersistenceSnapshot(localStorage.getItem(STORAGE_KEY));
    if (local) {
      const snapshot = local.wal.length ? (local.wal[local.wal.length - 1].snapshot || local.snapshot) : local.snapshot;
      return { source: 'localStorage', snapshot: normalizeState(snapshot), warning: local.warning || '' };
    }
    const cachedSnapshot = await readSnapshotFromCache();
    if (cachedSnapshot) return { source: 'cache-storage', snapshot: normalizeState(cachedSnapshot), warning: '' };
    return null;
  }

  function openPersistenceDb() {
    if (!("indexedDB" in window)) return Promise.resolve(null);
    if (persistenceDbPromise) return persistenceDbPromise;
    persistenceDbPromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(SNAPSHOT_STORE)) {
          db.createObjectStore(SNAPSHOT_STORE, { keyPath: 'id' });
        }
        if (!db.objectStoreNames.contains(EVENT_STORE)) {
          const store = db.createObjectStore(EVENT_STORE, { keyPath: 'id' });
          store.createIndex('timestampMs', 'timestampMs', { unique: false });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error('Forge persistence DB open failed'));
    });
    return persistenceDbPromise;
  }

  async function writeSnapshotToIndexedDb(snapshot) {
    const db = await openPersistenceDb();
    if (!db) return;
    await new Promise((resolve, reject) => {
      const tx = db.transaction(SNAPSHOT_STORE, 'readwrite');
      tx.objectStore(SNAPSHOT_STORE).put({
        id: 'workspace',
        updatedAt: snapshot.cache.snapshotTimestampMs || Date.now(),
        payload: snapshot,
      });
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error('Forge snapshot IndexedDB write failed'));
      tx.onabort = () => reject(tx.error || new Error('Forge snapshot IndexedDB transaction aborted'));
    });
  }

  async function writeSnapshotToCache(snapshot) {
    if (!("caches" in window)) return;
    const cache = await caches.open(CACHE_NAME);
    await cache.put(
      SNAPSHOT_CACHE_URL,
      new Response(JSON.stringify(snapshot), {
        headers: {
          'content-type': 'application/json',
          'cache-control': 'no-store',
        },
      }),
    );
  }

  async function readSnapshotFromIndexedDb() {
    const db = await openPersistenceDb();
    if (!db) return null;
    return new Promise((resolve, reject) => {
      const tx = db.transaction(SNAPSHOT_STORE, 'readonly');
      const request = tx.objectStore(SNAPSHOT_STORE).get('workspace');
      request.onsuccess = () => resolve(request.result && request.result.payload ? request.result.payload : null);
      request.onerror = () => reject(request.error || new Error('Forge snapshot IndexedDB read failed'));
    }).catch((error) => {
      console.warn('Forge snapshot IndexedDB read failed', error);
      return null;
    });
  }

  async function readSnapshotFromCache() {
    if (!("caches" in window)) return null;
    try {
      const cache = await caches.open(CACHE_NAME);
      const response = await cache.match(SNAPSHOT_CACHE_URL);
      if (!response) return null;
      return await response.json();
    } catch (error) {
      console.warn('Forge snapshot cache read failed', error);
      return null;
    }
  }

  async function clearPersistedWorkspace() {
    localStorage.removeItem(STORAGE_KEY);
    persistenceRotationSnapshot = null;
    state.cache = normalizeCacheState(state.cache);
    state.cache.wal = [];
    state.cache.walLength = 0;
    state.cache.rotationPending = false;
    state.cache.rotationSnapshot = null;
    state.cache.warning = '';
    if ("caches" in window) {
      const cache = await caches.open(CACHE_NAME);
      await cache.delete(SNAPSHOT_CACHE_URL);
    }
    const db = await openPersistenceDb();
    if (!db) return;
    await Promise.all([
      new Promise((resolve, reject) => {
        const tx = db.transaction(SNAPSHOT_STORE, 'readwrite');
        tx.objectStore(SNAPSHOT_STORE).delete('workspace');
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error || new Error('Forge snapshot delete failed'));
      }),
      new Promise((resolve, reject) => {
        const tx = db.transaction(EVENT_STORE, 'readwrite');
        tx.objectStore(EVENT_STORE).clear();
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error || new Error('Forge event log clear failed'));
      }),
    ]);
  }

  async function persistReactorEvent(entry) {
    const db = await openPersistenceDb();
    if (!db) return;
    await new Promise((resolve, reject) => {
      const tx = db.transaction(EVENT_STORE, 'readwrite');
      tx.objectStore(EVENT_STORE).put(entry);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error('Forge reactor event write failed'));
      tx.onabort = () => reject(tx.error || new Error('Forge reactor event transaction aborted'));
    }).catch((error) => {
      console.warn('Forge reactor event persistence failed', error);
    });
  }

  async function loadPersistedReactorEvents() {
    const db = await openPersistenceDb();
    if (!db) return [];
    return new Promise((resolve, reject) => {
      const tx = db.transaction(EVENT_STORE, 'readonly');
      const request = tx.objectStore(EVENT_STORE).getAll();
      request.onsuccess = () => resolve(normalizeReactorLog(request.result || []));
      request.onerror = () => reject(request.error || new Error('Forge reactor event read failed'));
    }).catch((error) => {
      console.warn('Forge reactor event load failed', error);
      return [];
    });
  }

  function registerPwaShell() {
    if (!("serviceWorker" in navigator)) return;
    window.addEventListener('appinstalled', () => {
      recordReactor('CacheStored', 'PWA installed');
      saveState();
      renderReactor();
    });
    navigator.serviceWorker.register('./sw.js').then((registration) => {
      const scope = registration.scope || 'local';
      recordReactor('CacheStored', 'PWA shell ' + scope);
      saveState();
      renderReactor();
    }).catch((error) => {
      console.warn('Forge PWA registration failed', error);
    });
  }

  function ensureSelection(target) {
    const ref = target || state;
    if (!ref.items.length) {
      ref.selectedItemId = null;
      return;
    }
    if (!ref.selectedItemId || !ref.items.some((item) => item.id === ref.selectedItemId)) {
      ref.selectedItemId = ref.items[0].id;
    }
  }

  function sortedColumns() {
    return [...state.columns].sort((a, b) => a.order - b.order);
  }

  function itemById(itemId) {
    return state.items.find((item) => item.id === itemId) || null;
  }

  function nextId(prefix) {
    return prefix + '-' + Math.random().toString(36).slice(2, 10);
  }

  function addItem(prefill) {
    const firstColumn = sortedColumns()[0];
    const item = {
      id: nextId('item'),
      title: (prefill && prefill.title) || 'Untitled work item',
      notes: (prefill && prefill.notes) || '',
      status: (prefill && prefill.status) || (firstColumn ? firstColumn.id : 'col-backlog'),
      priority: (prefill && prefill.priority) || 'medium',
      checklist: (prefill && Array.isArray(prefill.checklist)) ? prefill.checklist : [],
    };
    state.items.push(item);
    state.selectedItemId = item.id;
    recordReactor('TaxonomyNodeCreated', item.title);
    saveState();
  }

  function deleteItem(itemId) {
    const doomed = itemById(itemId);
    state.items = state.items.filter((item) => item.id !== itemId);
    ensureSelection();
    recordReactor('SignalFacetReduced', doomed ? 'Deleted ' + doomed.title : 'Deleted item');
    saveState();
    render();
  }

  function moveItem(itemId, step) {
    const item = itemById(itemId);
    if (!item) return;
    const columns = sortedColumns();
    const index = columns.findIndex((col) => col.id === item.status);
    if (index < 0) return;
    const target = columns[index + step];
    if (!target) return;
    item.status = target.id;
    recordReactor('SignalFacetReduced', item.title + ' → ' + target.name);
    saveState();
    renderBoard();
    renderNav();
    renderReactor();
    renderSpatial();
  }

  function addChecklist(itemId) {
    const item = itemById(itemId);
    if (!item) return;
    item.checklist.push({ id: nextId('check'), text: '', checked: false });
    recordReactor('TaxonomyNodeCreated', item.title + ' checklist');
    saveState();
    render();
  }

  function deleteChecklist(itemId, checkId) {
    const item = itemById(itemId);
    if (!item) return;
    item.checklist = item.checklist.filter((check) => check.id !== checkId);
    recordReactor('SignalFacetReduced', item.title + ' checklist trimmed');
    saveState();
    render();
  }

  function recordReactor(kind, label, source) {
    const now = Date.now();
    const origin = source || 'forge';
    state.reactor.lastEventKind = kind;
    state.reactor.lastEventTimestampMs = now;
    if (kind === 'TaxonomyNodeCreated') {
      state.reactor.taxonomyNodeCount += 1;
      if (label) {
        state.reactor.recentTaxonomyNodes = [...state.reactor.recentTaxonomyNodes, label].slice(-6);
      }
    } else if (kind === 'SignalFacetReduced') {
      state.reactor.signalFacetCount += 1;
      if (label) {
        state.reactor.recentSignals = [...state.reactor.recentSignals, label].slice(-8);
      }
    } else if (kind === 'CacheStored') {
      state.reactor.cacheStoredCount += 1;
      if (label) {
        state.reactor.recentSignals = [...state.reactor.recentSignals, 'stored: ' + label].slice(-8);
      }
    }
    const entry = {
      id: nextId('evt'),
      kind,
      label: label || '',
      source: origin,
      timestampMs: now,
    };
    state.reactorLog = normalizeReactorLog([...(state.reactorLog || []), entry]);
    void persistReactorEvent(entry);
  }

  function applyUseCase(useCaseId) {
    const useCase = state.useCases.find((entry) => entry.id === useCaseId);
    if (!useCase) return;
    state.title = useCase.name;
    state.pageNotes = useCase.pageNotes;
    const columns = sortedColumns();
    useCase.itemTitles.forEach((title, index) => {
      if (state.items.some((item) => item.title === title)) return;
      const fallback = columns[Math.min(index, columns.length - 1)] || columns[0];
      addItem({
        title,
        notes: useCase.summary,
        status: fallback ? fallback.id : 'col-backlog',
        priority: index === 0 ? 'high' : 'medium',
        checklist: [
          { id: nextId('check'), text: 'Capture the page context', checked: false },
          { id: nextId('check'), text: 'Move this through the board', checked: false },
        ],
      });
    });
    recordReactor('CacheStored', useCase.name);
    saveState();
    render();
  }

  function renderUseCases() {
    usecaseRoot.innerHTML = '';
    state.useCases.forEach((useCase) => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'usecase-card';
      card.addEventListener('click', () => applyUseCase(useCase.id));
      const name = document.createElement('div');
      name.className = 'usecase-name';
      name.textContent = useCase.name;
      const summary = document.createElement('div');
      summary.className = 'usecase-summary';
      summary.textContent = useCase.summary;
      card.append(name, summary);
      usecaseRoot.appendChild(card);
    });
  }

  function renderNav() {
    navRoot.innerHTML = '';
    if (!state.items.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'No work items yet. Start with New work item.';
      navRoot.appendChild(empty);
      return;
    }
    state.items.forEach((item) => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'nav-card' + (item.id === state.selectedItemId ? ' active' : '');
      card.addEventListener('click', () => {
        state.selectedItemId = item.id;
        state.spatial.focusMode = 'selected';
        focusSelected();
        saveState();
        render();
      });
      const name = document.createElement('div');
      name.className = 'nav-name';
      name.textContent = item.title || 'Untitled work item';
      const meta = document.createElement('div');
      meta.className = 'nav-meta';
      meta.textContent = item.priority + ' · ' + columnName(item.status) + ' · ' + checklistSummary(item);
      card.append(name, meta);
      navRoot.appendChild(card);
    });
  }

  function renderEditor() {
    ensureSelection();
    docRoot.innerHTML = '';

    const pageHead = document.createElement('section');
    pageHead.className = 'page-head';

    const titleInput = document.createElement('input');
    titleInput.className = 'title-input';
    titleInput.value = state.title;
    titleInput.placeholder = 'Workspace title';
    titleInput.addEventListener('input', (event) => {
      state.title = event.target.value;
      recordReactor('CacheStored', 'workspace title');
      saveState();
      renderNav();
      renderReactor();
    });
    pageHead.appendChild(titleInput);

    const notesWrap = document.createElement('div');
    notesWrap.className = 'page-notes-wrap';
    const pageNotes = document.createElement('textarea');
    pageNotes.className = 'page-notes';
    pageNotes.placeholder = 'Page notes';
    pageNotes.value = state.pageNotes || '';
    pageNotes.addEventListener('input', (event) => {
      state.pageNotes = event.target.value;
      recordReactor('CacheStored', 'page notes');
      saveState();
      renderReactor();
      renderSpatial();
    });
    notesWrap.appendChild(pageNotes);
    pageHead.appendChild(notesWrap);

    const actions = document.createElement('div');
    actions.className = 'toolbar compact';
    const addItemBtn = document.createElement('button');
    addItemBtn.className = 'btn primary';
    addItemBtn.textContent = 'Add work item';
    addItemBtn.addEventListener('click', () => {
      addItem();
      render();
    });
    const focusMapBtn = document.createElement('button');
    focusMapBtn.className = 'btn';
    focusMapBtn.textContent = 'Center map';
    focusMapBtn.addEventListener('click', () => {
      state.spatial.focusMode = 'board';
      centerBoard();
      saveState();
      renderSpatial();
    });
    actions.append(addItemBtn, focusMapBtn);
    pageHead.appendChild(actions);
    docRoot.appendChild(pageHead);

    const selected = itemById(state.selectedItemId);
    if (!selected) return;

    const shell = document.createElement('section');
    shell.className = 'dialog-shell';

    const dialog = document.createElement('article');
    dialog.className = 'dialog-card';

    const head = document.createElement('div');
    head.className = 'dialog-head';
    const titleStack = document.createElement('div');
    titleStack.className = 'dialog-title-stack';
    const kicker = document.createElement('div');
    kicker.className = 'dialog-kicker';
    kicker.textContent = 'Selected work item';
    const itemTitle = document.createElement('input');
    itemTitle.className = 'dialog-title';
    itemTitle.value = selected.title;
    itemTitle.placeholder = 'Untitled work item';
    itemTitle.addEventListener('input', (event) => {
      selected.title = event.target.value;
      recordReactor('CacheStored', 'item title');
      saveState();
      renderNav();
      renderBoard();
      renderReactor();
      renderSpatial();
    });
    const metaLine = document.createElement('div');
    metaLine.className = 'dialog-meta-line';
    metaLine.textContent = 'Selected card inspector. Keep the editing surface tight so the field and board stay primary.';
    titleStack.append(kicker, itemTitle, metaLine);

    const chips = document.createElement('div');
    chips.className = 'dialog-chip-row';
    const chipStatus = document.createElement('div');
    chipStatus.className = 'dialog-chip';
    chipStatus.textContent = columnName(selected.status);
    const chipPriority = document.createElement('div');
    chipPriority.className = 'dialog-chip';
    chipPriority.textContent = selected.priority;
    const chipChecklist = document.createElement('div');
    chipChecklist.className = 'dialog-chip';
    chipChecklist.textContent = checklistSummary(selected);
    chips.append(chipStatus, chipPriority, chipChecklist);
    head.append(titleStack, chips);
    dialog.appendChild(head);

    const grid = document.createElement('div');
    grid.className = 'dialog-grid';
    grid.appendChild(selectField('Status', state.columns, selected.status, (value) => {
      selected.status = value;
      recordReactor('SignalFacetReduced', selected.title + ' → ' + columnName(value));
      saveState();
      renderNav();
      renderBoard();
      renderReactor();
      renderSpatial();
      renderEditor();
    }));
    grid.appendChild(selectField('Priority', [
      { id: 'critical', name: 'critical' },
      { id: 'high', name: 'high' },
      { id: 'medium', name: 'medium' },
      { id: 'low', name: 'low' },
    ], selected.priority, (value) => {
      selected.priority = value;
      recordReactor('CacheStored', selected.title + ' priority');
      saveState();
      renderNav();
      renderBoard();
      renderReactor();
      renderSpatial();
      renderEditor();
    }));
    dialog.appendChild(grid);

    const noteField = document.createElement('div');
    noteField.className = 'dialog-field';
    const noteLabel = document.createElement('label');
    noteLabel.textContent = 'Notes';
    const noteArea = document.createElement('textarea');
    noteArea.placeholder = 'Write notes, specs, or next steps here.';
    noteArea.value = selected.notes || '';
    noteArea.addEventListener('input', (event) => {
      selected.notes = event.target.value;
      recordReactor('CacheStored', selected.title + ' notes');
      saveState();
      renderBoard();
      renderReactor();
      renderSpatial();
      renderNav();
    });
    noteField.append(noteLabel, noteArea);
    dialog.appendChild(noteField);

    const dialogActions = document.createElement('div');
    dialogActions.className = 'dialog-actions';
    const addChecklistBtn = document.createElement('button');
    addChecklistBtn.className = 'btn';
    addChecklistBtn.textContent = 'Add checklist line';
    addChecklistBtn.addEventListener('click', () => addChecklist(selected.id));
    const focusSelectedBtn = document.createElement('button');
    focusSelectedBtn.className = 'btn';
    focusSelectedBtn.textContent = 'Zoom to detail';
    focusSelectedBtn.addEventListener('click', () => {
      state.spatial.focusMode = 'selected';
      camera.zoom = clamp(Math.max(camera.zoom, 1.9), camera.minZoom, camera.maxZoom);
      clearCameraMomentum();
      focusSelected();
      saveState();
      renderSpatial();
    });
    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'btn';
    deleteBtn.textContent = 'Delete item';
    deleteBtn.addEventListener('click', () => deleteItem(selected.id));
    dialogActions.append(addChecklistBtn, focusSelectedBtn, deleteBtn);
    dialog.appendChild(dialogActions);

    const checklistSection = document.createElement('section');
    checklistSection.className = 'dialog-section';
    const checklistHead = document.createElement('div');
    checklistHead.className = 'dialog-section-head';
    const checklistTitle = document.createElement('h3');
    checklistTitle.textContent = 'Checklist';
    const checklistHint = document.createElement('div');
    checklistHint.className = 'dialog-hint';
    checklistHint.textContent = 'Closer zoom reveals these lines as orbiting map detail.';
    checklistHead.append(checklistTitle, checklistHint);
    checklistSection.appendChild(checklistHead);

    const checklist = document.createElement('div');
    checklist.className = 'checklist';
    selected.checklist.forEach((check) => {
      const row = document.createElement('div');
      row.className = 'check-row';

      const toggle = document.createElement('input');
      toggle.type = 'checkbox';
      toggle.checked = !!check.checked;
      toggle.addEventListener('change', () => {
        check.checked = toggle.checked;
        recordReactor('SignalFacetReduced', selected.title + ' checklist');
        saveState();
        renderNav();
        renderBoard();
        renderReactor();
        renderSpatial();
        renderEditor();
      });

      const text = document.createElement('textarea');
      text.className = 'check-input';
      text.rows = 1;
      text.placeholder = 'Checklist line';
      text.value = check.text;
      text.addEventListener('input', (event) => {
        check.text = event.target.value;
        autoGrow(text);
        recordReactor('CacheStored', selected.title + ' checklist text');
        saveState();
        renderBoard();
        renderReactor();
        renderSpatial();
        renderNav();
      });
      autoGrow(text);

      const remove = document.createElement('button');
      remove.className = 'status-btn';
      remove.textContent = '×';
      remove.addEventListener('click', () => deleteChecklist(selected.id, check.id));

      row.append(toggle, text, remove);
      checklist.appendChild(row);
    });
    if (!selected.checklist.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'No checklist lines yet. Add one to create near-zoom detail.';
      checklist.appendChild(empty);
    }
    checklistSection.appendChild(checklist);
    dialog.appendChild(checklistSection);

    // Attachments section (CouchDB-style _attachments)
    const attachSection = document.createElement('section');
    attachSection.className = 'dialog-section';
    const attachHead = document.createElement('div');
    attachHead.className = 'dialog-section-head';
    const attachTitle = document.createElement('h3');
    attachTitle.textContent = 'Attachments';
    const attachHint = document.createElement('div');
    attachHint.className = 'dialog-hint';
    attachHint.textContent = 'Drop files anywhere in the editor pane. Stored as CouchDB-style blob records (Confix CBOR shape).';
    attachHead.append(attachTitle, attachHint);
    attachSection.appendChild(attachHead);

    const attachList = document.createElement('div');
    attachList.className = 'checklist';
    const attachments = selected.attachments || {};
    const attachNames = Object.keys(attachments);
    if (attachNames.length) {
      attachNames.forEach((name) => {
        const meta = attachments[name];
        const row = document.createElement('div');
        row.className = 'check-row';
        const icon = document.createElement('span');
        icon.textContent = '📎';
        icon.style.marginRight = '6px';
        const label = document.createElement('a');
        label.textContent = name + ' (' + (meta.content_type || '?') + ' · ' + meta.length + ' B)';
        label.style.cursor = 'pointer';
        label.style.color = 'var(--cyan)';
        label.style.textDecoration = 'none';
        label.addEventListener('click', async () => {
          const blob = await readAttachmentBlob(selected.id, name);
          if (!blob) return;
          const url = URL.createObjectURL(blob);
          window.open(url, '_blank');
          setTimeout(() => URL.revokeObjectURL(url), 30000);
        });
        const remove = document.createElement('button');
        remove.className = 'status-btn';
        remove.textContent = '×';
        remove.addEventListener('click', async () => {
          delete attachments[name];
          const db = await openPersistenceDb();
          if (db && db.objectStoreNames.contains(ATTACHMENT_STORE)) {
            await new Promise((resolve) => {
              const tx = db.transaction(ATTACHMENT_STORE, 'readwrite');
              tx.objectStore(ATTACHMENT_STORE).delete(selected.id + '/' + name);
              tx.oncomplete = () => resolve();
              tx.onerror = () => resolve();
            });
          }
          recordReactor('SignalFacetReduced', 'Attachment removed: ' + name, 'couch');
          saveState();
          render();
        });
        row.append(icon, label, remove);
        attachList.appendChild(row);
      });
    } else {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'No attachments. Drop a file here to store it.';
      attachList.appendChild(empty);
    }
    attachSection.appendChild(attachList);
    dialog.appendChild(attachSection);

    shell.append(dialog);
    docRoot.appendChild(shell);
  }

  function renderBoard() {

    boardRoot.innerHTML = '';
    const activeId = state.selectedItemId;
    sortedColumns().forEach((column) => {
      const cards = state.items.filter((item) => item.status === column.id);
      const section = document.createElement('section');
      section.className = 'board-column';

      const head = document.createElement('div');
      head.className = 'head';
      const name = document.createElement('div');
      name.className = 'name';
      name.textContent = column.name;
      const count = document.createElement('div');
      count.className = 'count';
      count.textContent = cards.length + ' item' + (cards.length === 1 ? '' : 's');
      head.append(name, count);
      section.appendChild(head);

      const list = document.createElement('div');
      list.className = 'board-list';
      if (!cards.length) {
        const empty = document.createElement('div');
        empty.className = 'empty';
        empty.textContent = 'No items in this column';
        list.appendChild(empty);
      } else {
        cards.forEach((item) => {
          const card = document.createElement('article');
          card.className = 'board-card' + (item.id === activeId ? ' active' : '');
          card.addEventListener('click', () => {
            state.selectedItemId = item.id;
            state.spatial.focusMode = 'selected';
            focusSelected();
            saveState();
            render();
          });

          const title = document.createElement('div');
          title.className = 'board-title';
          title.textContent = item.title || 'Untitled work item';
          const meta = document.createElement('div');
          meta.className = 'board-meta';
          meta.textContent = [item.priority, previewText(item.notes), checklistSummary(item)].filter(Boolean).join('\n');
          card.append(title, meta);

          const actions = document.createElement('div');
          actions.className = 'board-actions';
          const left = document.createElement('button');
          left.className = 'status-btn';
          left.textContent = '←';
          left.addEventListener('click', (event) => {
            event.stopPropagation();
            moveItem(item.id, -1);
          });
          const right = document.createElement('button');
          right.className = 'status-btn';
          right.textContent = '→';
          right.addEventListener('click', (event) => {
            event.stopPropagation();
            moveItem(item.id, 1);
          });
          actions.append(left, right);
          card.appendChild(actions);
          list.appendChild(card);
        });
      }
      section.appendChild(list);
      boardRoot.appendChild(section);
    });
  }

  function renderReactor() {
    reactorRoot.innerHTML = '';

    const row = document.createElement('div');
    row.className = 'status-row';
    row.append(
      statusChip('items', String(state.items.length)),
      statusChip('signals', String(Number(state.reactor.signalFacetCount) || 0)),
      statusChip('stored', String(Number(state.reactor.cacheStoredCount) || 0)),
      statusChip('last', state.reactor.lastEventKind || 'INIT'),
      statusChip('cache', state.cache.hydrationSource || 'localStorage'),
    );
    reactorRoot.appendChild(row);

    const trail = document.createElement('div');
    trail.className = 'status-trail';
    const activity = (state.reactorLog || []).slice(0, 3).map(formatReactorEvent);
    if (activity.length) {
      activity.forEach((entry) => {
        const pill = document.createElement('div');
        pill.className = 'status-pill';
        pill.textContent = entry;
        trail.appendChild(pill);
      });
    } else {
      const pill = document.createElement('div');
      pill.className = 'status-pill';
      pill.textContent = 'No recent local activity';
      trail.appendChild(pill);
    }
    reactorRoot.appendChild(trail);

    const note = document.createElement('div');
    note.className = 'status-note';
    note.textContent = 'Local activity only · hydrated from ' + (state.cache.hydrationSource || 'localStorage') + ' · snapshot ' + formatTimestamp(state.cache.snapshotTimestampMs) + ' · last save ' + formatTimestamp(state.cache.lastLocalSaveMs) + (state.cache.warning ? ' · ' + state.cache.warning : '');
    reactorRoot.appendChild(note);
  }

  function statusChip(labelText, valueText) {
    const card = document.createElement('div');
    card.className = 'status-chip';
    const label = document.createElement('div');
    label.className = 'label';
    label.textContent = labelText;
    const value = document.createElement('div');
    value.className = 'value';
    value.textContent = valueText;
    card.append(label, value);
    return card;
  }

  function layoutBoard() {
    const columns = sortedColumns();
    const laneWidth = 290;
    const rowGap = 152;
    const marginX = 120;
    const marginY = 120;
    const basePositions = {};
    const maxCards = Math.max(1, ...columns.map((column) => state.items.filter((item) => item.status === column.id).length));
    const width = Math.max(1200, marginX * 2 + columns.length * laneWidth + 120);
    const height = Math.max(720, marginY * 2 + maxCards * rowGap + 220);

    columns.forEach((column, columnIndex) => {
      const cards = state.items.filter((item) => item.status === column.id);
      cards.forEach((item, rowIndex) => {
        basePositions[item.id] = {
          x: marginX + columnIndex * laneWidth + 140,
          y: marginY + rowIndex * rowGap + 110,
          width: 190,
          height: 92,
          column,
        };
      });
    });

    const graph = buildSpatialGraph(columns, basePositions, width, height);
    return { columns, basePositions, positions: graph.positions, overlayPositions: graph.overlayPositions, edges: graph.edges, width, height, laneWidth, marginX, marginY };
  }

  function buildSpatialGraph(columns, basePositions, width, height) {
    const cardNodes = state.items.map((item) => ({
      id: item.id,
      type: 'card',
      item,
      x: basePositions[item.id] ? basePositions[item.id].x : width / 2,
      y: basePositions[item.id] ? basePositions[item.id].y : height / 2,
      vx: 0,
      vy: 0,
      laneX: basePositions[item.id] ? basePositions[item.id].x : width / 2,
      laneY: basePositions[item.id] ? basePositions[item.id].y : height / 2,
    }));
    const nodesById = Object.fromEntries(cardNodes.map((node) => [node.id, node]));
    const selected = itemById(state.selectedItemId);
    const selectedNode = selected ? nodesById[selected.id] : null;
    const edges = [];
    const overlayNodes = [];

    cardNodes.forEach((node, index) => {
      const columnCards = state.items.filter((item) => item.status === node.item.status);
      const row = columnCards.findIndex((item) => item.id === node.id);
      const previous = row > 0 ? columnCards[row - 1] : null;
      if (previous) {
        edges.push({ from: previous.id, to: node.id, kind: 'lane' });
      }
      if (index > 0) {
        edges.push({ from: cardNodes[index - 1].id, to: node.id, kind: 'flow' });
      }
    });

    if (selectedNode) {
      const selectedChecklist = selected.checklist.length
        ? selected.checklist
        : [{ id: selected.id + '-detail', text: previewText(selected.notes || 'Add detail to open the fractal view.'), checked: false }];
      selectedChecklist.slice(0, 7).forEach((detail, index) => {
        const angle = (-Math.PI / 2) + (index * ((Math.PI * 1.8) / Math.max(1, selectedChecklist.length)));
        const radius = 150 + (index % 2) * 26;
        const overlayId = 'overlay-' + detail.id;
        overlayNodes.push({
          id: overlayId,
          parentId: selected.id,
          kind: 'dag-child',
          label: detail.text || 'detail',
          checked: !!detail.checked,
          x: selectedNode.x + Math.cos(angle) * radius,
          y: selectedNode.y + Math.sin(angle) * radius,
        });
        edges.push({ from: selected.id, to: overlayId, kind: 'dag-child' });
      });

      const related = state.items
        .filter((item) => item.id !== selected.id)
        .filter((item) => item.status === selected.status || item.priority === selected.priority)
        .slice(0, 4);
      related.forEach((item) => {
        edges.push({ from: selected.id, to: item.id, kind: 'facet' });
      });
    }

    for (let step = 0; step < 42; step += 1) {
      cardNodes.forEach((node) => {
        const anchorPullX = (node.laneX - node.x) * 0.05;
        const anchorPullY = (node.laneY - node.y) * 0.045;
        node.vx += anchorPullX;
        node.vy += anchorPullY;
      });

      for (let i = 0; i < cardNodes.length; i += 1) {
        for (let j = i + 1; j < cardNodes.length; j += 1) {
          const a = cardNodes[i];
          const b = cardNodes[j];
          const dx = b.x - a.x;
          const dy = b.y - a.y;
          const distSq = Math.max(2200, dx * dx + dy * dy);
          const dist = Math.sqrt(distSq);
          const force = 3400 / distSq;
          const rx = (dx / dist) * force;
          const ry = (dy / dist) * force;
          a.vx -= rx;
          a.vy -= ry;
          b.vx += rx;
          b.vy += ry;
        }
      }

      edges.forEach((edge) => {
        const from = nodesById[edge.from];
        const to = nodesById[edge.to];
        if (!from || !to) return;
        const dx = to.x - from.x;
        const dy = to.y - from.y;
        const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        const target = edge.kind === 'facet' ? 235 : 170;
        const tension = edge.kind === 'facet' ? 0.012 : 0.022;
        const delta = (dist - target) * tension;
        const fx = (dx / dist) * delta;
        const fy = (dy / dist) * delta;
        from.vx += fx;
        from.vy += fy;
        to.vx -= fx;
        to.vy -= fy;
      });

      cardNodes.forEach((node) => {
        node.vx *= 0.72;
        node.vy *= 0.72;
        node.x = clamp(node.x + node.vx, 100, width - 100);
        node.y = clamp(node.y + node.vy, 96, height - 96);
      });
    }

    const positions = {};
    cardNodes.forEach((node) => {
      positions[node.id] = {
        x: node.x,
        y: node.y,
        width: 190,
        height: 92,
        column: basePositions[node.id] ? basePositions[node.id].column : columns[0],
      };
    });

    const overlayPositions = {};
    overlayNodes.forEach((node, index) => {
      const parent = positions[node.parentId] || { x: width / 2, y: height / 2 };
      const angle = (-Math.PI / 2) + index * 0.8;
      const radius = 150 + (index % 3) * 18;
      overlayPositions[node.id] = {
        x: parent.x + Math.cos(angle) * radius,
        y: parent.y + Math.sin(angle) * radius,
        r: 30,
        label: node.label,
        checked: node.checked,
        parentId: node.parentId,
      };
    });

    return { positions, overlayPositions, edges };
  }

  function renderSpatial() {
    const layout = layoutBoard();
    if (state.spatial.focusMode === 'board' && (!camera.x && !camera.y)) {
      centerBoard();
    }
    clampCamera(layout);
    syncSpatialFromCamera();
    const zoom = camera.zoom;
    const far = zoom < 0.9;
    const mid = zoom >= 0.9 && zoom < 1.45;
    const near = zoom >= 1.45 && zoom < 1.95;
    const intimate = zoom >= 1.95;
    spatialRoot.setAttribute('viewBox', '0 0 ' + layout.width + ' ' + layout.height);
    spatialRoot.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    spatialRoot.innerHTML = '';
    zoomSlider.value = String(zoom);
    zoomLabel.textContent = spatialDepthLabel(zoom) + ' · farther zoom hides fine detail · closer zoom reveals notes + checklist orbit';

    for (let y = 0; y <= layout.height; y += 80) {
      spatialRoot.appendChild(svgElement('line', {
        x1: 0, y1: y, x2: layout.width, y2: y,
        stroke: 'rgba(125,207,255,0.08)', 'stroke-width': 1,
      }));
    }

    layout.columns.forEach((column, index) => {
      const x = layout.marginX + index * layout.laneWidth;
      spatialRoot.appendChild(svgElement('rect', {
        x, y: 70, width: 230, height: layout.height - 140, rx: 28,
        fill: 'rgba(17,24,36,0.78)', stroke: 'rgba(122,162,247,0.22)', 'stroke-width': 2,
      }));
      if (!far) {
        spatialRoot.appendChild(svgElement('text', {
          x: x + 18, y: 108, fill: '#dbe7f3', 'font-size': 20, 'font-weight': 700,
        }, column.name));
      }
    });

    layout.edges.forEach((edge) => {
      const from = layout.positions[edge.from] || layout.overlayPositions[edge.from];
      const to = layout.positions[edge.to] || layout.overlayPositions[edge.to];
      if (!from || !to) return;
      if (far && edge.kind === 'facet') return;
      spatialRoot.appendChild(svgElement('line', {
        x1: from.x,
        y1: from.y,
        x2: to.x,
        y2: to.y,
        stroke: edge.kind === 'facet' ? 'rgba(224,175,104,0.2)' : 'rgba(125,207,255,0.18)',
        'stroke-width': edge.kind === 'facet' ? 2 : (far ? 1.5 : 2),
        'stroke-dasharray': edge.kind === 'facet' ? '8 8' : '0',
      }));
    });

    state.items.forEach((item) => {
      const pos = layout.positions[item.id];
      if (!pos) return;
      const isSelected = item.id === state.selectedItemId;
      if (far) {
        spatialRoot.appendChild(svgElement('circle', {
          cx: pos.x,
          cy: pos.y,
          r: isSelected ? 22 : 14,
          fill: isSelected ? 'rgba(122,162,247,0.28)' : 'rgba(17,24,36,0.95)',
          stroke: isSelected ? '#7aa2f7' : 'rgba(125,207,255,0.28)',
          'stroke-width': isSelected ? 3 : 2,
        }));
        if (isSelected) {
          spatialRoot.appendChild(svgElement('text', {
            x: pos.x,
            y: pos.y - 32,
            fill: '#dbe7f3',
            'font-size': 14,
            'font-weight': 700,
            'text-anchor': 'middle',
          }, trimText(item.title || 'Untitled', 22)));
        }
        return;
      }

      spatialRoot.appendChild(svgElement('rect', {
        x: pos.x - pos.width / 2,
        y: pos.y - pos.height / 2,
        width: pos.width,
        height: pos.height,
        rx: 20,
        fill: isSelected ? 'rgba(122,162,247,0.26)' : 'rgba(15,21,29,0.94)',
        stroke: isSelected ? '#7aa2f7' : 'rgba(125,207,255,0.28)',
        'stroke-width': isSelected ? 3 : 2,
      }));
      spatialRoot.appendChild(svgElement('text', {
        x: pos.x,
        y: pos.y - 8,
        fill: '#dbe7f3',
        'font-size': near || intimate ? 18 : 15,
        'font-weight': 700,
        'text-anchor': 'middle',
      }, trimText(item.title || 'Untitled', mid ? 18 : 28)));
      if (!mid) {
        spatialRoot.appendChild(svgElement('text', {
          x: pos.x,
          y: pos.y + 18,
          fill: '#7e8da0',
          'font-size': 12,
          'text-anchor': 'middle',
        }, columnName(item.status) + ' · ' + item.priority));
      }
      if (intimate && item.notes) {
        spatialRoot.appendChild(svgElement('text', {
          x: pos.x,
          y: pos.y + 36,
          fill: '#7dcfff',
          'font-size': 10,
          'text-anchor': 'middle',
        }, trimText(item.notes, 34)));
      }
    });

    Object.values(layout.overlayPositions).forEach((overlay) => {
      if (!intimate) return;
      spatialRoot.appendChild(svgElement('circle', {
        cx: overlay.x,
        cy: overlay.y,
        r: overlay.r,
        fill: overlay.checked ? 'rgba(158,206,106,0.28)' : 'rgba(224,175,104,0.22)',
        stroke: overlay.checked ? '#9ece6a' : '#e0af68',
        'stroke-width': 2,
      }));
      spatialRoot.appendChild(svgElement('text', {
        x: overlay.x,
        y: overlay.y + 4,
        fill: '#dbe7f3',
        'font-size': 10,
        'text-anchor': 'middle',
      }, trimText(overlay.label || 'detail', 16)));
    });
  }

  function bindSpatialGestures() {
    spatialShell.addEventListener('wheel', (event) => {
      event.preventDefault();
      const layout = layoutBoard();
      const rect = spatialShell.getBoundingClientRect();
      const viewX = (event.clientX - rect.left) * layout.width / Math.max(1, rect.width);
      const viewY = (event.clientY - rect.top) * layout.height / Math.max(1, rect.height);
      const cosTilt = Math.max(0.01, Math.cos(camera.tilt));
      const focusWorldX = camera.x + viewX / camera.zoom;
      const focusWorldY = camera.y + viewY / (camera.zoom * cosTilt);
      const targetZoom = clamp(camera.zoom * (event.deltaY < 0 ? 1.08 : 0.92), camera.minZoom, camera.maxZoom);
      const ratio = camera.zoom / targetZoom;
      camera.x = focusWorldX - (focusWorldX - camera.x) * ratio;
      camera.y = focusWorldY - (focusWorldY - camera.y) * ratio;
      camera.vz += (targetZoom / camera.zoom - 1) * 12;
      camera.zoom = targetZoom;
      state.spatial.focusMode = 'manual';
      renderSpatial();
      cameraSavePending = true;
      startCameraAnimation();
    }, { passive: false });

    spatialShell.addEventListener('pointerdown', (event) => {
      stopCameraAnimation();
      clearCameraMomentum();
      spatialShell.classList.add('dragging');
      dragCamera = {
        x: event.clientX,
        y: event.clientY,
        cameraX: camera.x,
        cameraY: camera.y,
        lastCameraX: camera.x,
        lastCameraY: camera.y,
        lastTimestamp: event.timeStamp,
      };
      spatialShell.setPointerCapture(event.pointerId);
    });

    spatialShell.addEventListener('pointermove', (event) => {
      if (!dragCamera) return;
      const layout = layoutBoard();
      const rect = spatialShell.getBoundingClientRect();
      const screenToViewX = layout.width / Math.max(1, rect.width);
      const screenToViewY = layout.height / Math.max(1, rect.height);
      const cosTilt = Math.max(0.01, Math.cos(camera.tilt));
      camera.x = dragCamera.cameraX - (event.clientX - dragCamera.x) * screenToViewX / camera.zoom;
      camera.y = dragCamera.cameraY - (event.clientY - dragCamera.y) * screenToViewY / (camera.zoom * cosTilt);
      const dtSeconds = Math.max(1 / 240, (event.timeStamp - dragCamera.lastTimestamp) / 1000);
      camera.vx = (camera.x - dragCamera.lastCameraX) / dtSeconds;
      camera.vy = (camera.y - dragCamera.lastCameraY) / dtSeconds;
      dragCamera.lastCameraX = camera.x;
      dragCamera.lastCameraY = camera.y;
      dragCamera.lastTimestamp = event.timeStamp;
      syncSpatialFromCamera();
      state.spatial.focusMode = 'manual';
      renderSpatial();
    });

    const release = (event) => {
      if (!dragCamera) return;
      dragCamera = null;
      spatialShell.classList.remove('dragging');
      if (event && spatialShell.hasPointerCapture(event.pointerId)) {
        spatialShell.releasePointerCapture(event.pointerId);
      }
      cameraSavePending = true;
      startCameraAnimation();
    };

    spatialShell.addEventListener('pointerup', release);
    spatialShell.addEventListener('pointercancel', release);
    spatialShell.addEventListener('pointerleave', () => {
      if (!dragCamera) spatialShell.classList.remove('dragging');
    });
  }

  function startCameraAnimation() {
    if (cameraAnimationFrame !== null) return;
    if (!cameraIsMoving()) {
      if (cameraSavePending) {
        cameraSavePending = false;
        saveState({ kind: 'camera-settled', label: 'camera settled', source: 'blackboard' });
      }
      return;
    }
    cameraFrameTimestamp = 0;
    cameraAnimationFrame = requestAnimationFrame(tickCamera);
  }

  function stopCameraAnimation() {
    if (cameraAnimationFrame !== null) cancelAnimationFrame(cameraAnimationFrame);
    cameraAnimationFrame = null;
    cameraFrameTimestamp = 0;
    cameraSavePending = false;
  }

  function clearCameraMomentum() {
    camera.vx = 0;
    camera.vy = 0;
    camera.vz = 0;
  }

  function cameraSpeed() {
    return Math.sqrt(camera.vx * camera.vx + camera.vy * camera.vy);
  }

  function cameraIsMoving() {
    return cameraSpeed() >= 0.5 || Math.abs(camera.vz) >= 0.001;
  }

  function tickCamera(timestamp) {
    const dtSeconds = cameraFrameTimestamp
      ? clamp((timestamp - cameraFrameTimestamp) / 1000, 1 / 240, 1 / 20)
      : 1 / 60;
    cameraFrameTimestamp = timestamp;
    const friction = Math.pow(0.86, dtSeconds * 60);
    camera.vx *= friction;
    camera.vy *= friction;
    camera.vz *= friction;
    camera.x += camera.vx * dtSeconds;
    camera.y += camera.vy * dtSeconds;
    const unclampedZoom = camera.zoom * (1 + camera.vz * dtSeconds);
    camera.zoom = clamp(unclampedZoom, camera.minZoom, camera.maxZoom);
    if (camera.zoom !== unclampedZoom) camera.vz = 0;
    clampCamera(layoutBoard());
    syncSpatialFromCamera();
    renderSpatial();
    if (cameraIsMoving()) {
      cameraAnimationFrame = requestAnimationFrame(tickCamera);
    } else {
      clearCameraMomentum();
      cameraAnimationFrame = null;
      cameraFrameTimestamp = 0;
      if (cameraSavePending) {
        cameraSavePending = false;
        saveState({ kind: 'camera-settled', label: 'momentum settled', source: 'blackboard' });
      }
    }
  }

  function focusSelected() {
    const selected = itemById(state.selectedItemId);
    if (!selected) return;
    const layout = layoutBoard();
    const pos = layout.positions[selected.id];
    if (!pos) return;
    const viewWidth = layout.width / camera.zoom;
    const viewHeight = layout.height / camera.zoom;
    camera.x = pos.x - viewWidth / 2;
    camera.y = pos.y * Math.cos(camera.tilt) - viewHeight / 2;
    clampCamera(layout);
  }

  function centerBoard() {
    const layout = layoutBoard();
    const viewWidth = layout.width / camera.zoom;
    const viewHeight = layout.height / camera.zoom;
    const projectedHeight = layout.height * Math.cos(camera.tilt);
    camera.x = (layout.width - viewWidth) / 2;
    camera.y = (projectedHeight - viewHeight) / 2;
    clampCamera(layout);
  }

  function clampCamera(layout) {
    const beforeX = camera.x;
    const beforeY = camera.y;
    const viewWidth = layout.width / camera.zoom;
    const viewHeight = layout.height / camera.zoom;
    const projectedHeight = layout.height * Math.cos(camera.tilt);
    if (viewWidth >= layout.width) {
      camera.x = (layout.width - viewWidth) / 2;
    } else {
      const maxX = layout.width - viewWidth;
      camera.x = clamp(camera.x || 0, 0, maxX);
    }
    const clampedX = camera.x !== beforeX;
    if (viewHeight >= projectedHeight) {
      camera.y = (projectedHeight - viewHeight) / 2;
    } else {
      const maxY = projectedHeight - viewHeight;
      camera.y = clamp(camera.y || 0, 0, maxY);
    }
    if (clampedX) camera.vx = 0;
    if (camera.y !== beforeY) camera.vy = 0;
    syncSpatialFromCamera();
  }

  function svgElement(tag, attrs, text) {
    const node = document.createElementNS(SVG_NS, tag);
    const projected = projectSpatialAttributes(tag, attrs || {});
    Object.entries(projected).forEach(([key, value]) => node.setAttribute(key, String(value)));
    if (text) node.textContent = text;
    return node;
  }

  function projectSpatialAttributes(tag, attrs) {
    const next = { ...attrs };
    const depth = Number(attrs['data-depth']) || 0;
    const pointKeys = tag === 'line'
      ? [['x1', 'y1'], ['x2', 'y2']]
      : tag === 'circle'
        ? [['cx', 'cy']]
        : [['x', 'y']];
    pointKeys.forEach(([xKey, yKey]) => {
      if (!(xKey in attrs) || !(yKey in attrs)) return;
      const point = projectCameraPoint(Number(attrs[xKey]), Number(attrs[yKey]), depth);
      next[xKey] = point.x;
      next[yKey] = point.y;
    });
    if (tag === 'rect') {
      const heightScale = camera.zoom * Math.cos(camera.tilt);
      next.width = Number(attrs.width) * camera.zoom;
      next.height = Number(attrs.height) * heightScale;
      if ('rx' in attrs) next.rx = Number(attrs.rx) * camera.zoom;
    } else if (tag === 'circle' && 'r' in attrs) {
      next.r = Number(attrs.r) * camera.zoom;
    }
    delete next['data-depth'];
    return next;
  }

  function projectCameraPoint(worldX, worldY, depth) {
    const lift = depth * (1 - Math.cos(camera.tilt));
    const scaledY = (worldY - lift) * Math.cos(camera.tilt);
    const parallaxY = depth * Math.sin(camera.tilt);
    return {
      x: (worldX - camera.x) * camera.zoom,
      y: ((scaledY - camera.y) * camera.zoom) + parallaxY * camera.zoom,
    };
  }

  function render() {
    ensureSelection();
    saveState();
    renderUseCases();
    renderNav();
    renderEditor();
    renderReactor();
    renderSpatial();
    renderBoard();
    renderCascadeGrid();
    renderGallery();
    renderBlackboardChrome();
  }

  function renderCascadeGrid() {
    const root = document.getElementById('cascade-grid-root');
    if (!root) return;
    root.innerHTML = '';
    const rows = state.cascadeGrid || [];
    if (!rows.length) { root.textContent = 'No cascade data'; return; }
    const table = document.createElement('table');
    const thead = document.createElement('thead');
    const headerRow = document.createElement('tr');
    ['View', 'Metric', 'Sum', 'Avg', 'Min', 'Max', 'Count'].forEach(function (h) {
      const th = document.createElement('th'); th.textContent = h; headerRow.appendChild(th);
    });
    thead.appendChild(headerRow); table.appendChild(thead);
    const tbody = document.createElement('tbody');
    rows.forEach(function (row) {
      const tr = document.createElement('tr');
      [['text', row.viewName], ['text', row.metric], ['num', row.sum], ['num', row.avg], ['num', row.min], ['num', row.max], ['num', row.count]].forEach(function (entry) {
        var td = document.createElement('td');
        td.textContent = String(entry[1]);
        if (entry[0] === 'num') td.className = 'num';
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    root.appendChild(table);
  }

  function renderGallery() {
    const root = document.getElementById('gallery-root');
    if (!root || !seed.gallery) return;
    // Gallery is server-rendered into the seed HTML; client-side re-render not needed
  }

  function renderBlackboardChrome() {
    // 3D blackboard chrome not yet implemented in JS shell
  }

  function selectField(labelText, options, selectedValue, onChange) {
    const field = document.createElement('div');
    field.className = 'field';
    const label = document.createElement('label');
    label.textContent = labelText;
    const select = document.createElement('select');
    options.forEach((option) => {
      const opt = document.createElement('option');
      opt.value = option.id;
      opt.textContent = option.name;
      opt.selected = option.id === selectedValue;
      select.appendChild(opt);
    });
    select.addEventListener('change', (event) => onChange(event.target.value));
    field.append(label, select);
    return field;
  }

  function columnName(columnId) {
    const column = state.columns.find((entry) => entry.id === columnId);
    return column ? column.name : columnId;
  }

  function previewText(text) {
    if (!text) return '';
    return text.length > 120 ? text.slice(0, 117) + '...' : text;
  }

  function checklistSummary(item) {
    if (!item.checklist.length) return '0 checklist';
    const done = item.checklist.filter((check) => check.checked).length;
    return done + '/' + item.checklist.length + ' checklist';
  }

  function autoGrow(textarea) {
    textarea.style.height = 'auto';
    textarea.style.height = Math.max(textarea.scrollHeight, 32) + 'px';
  }

  function formatTimestamp(value) {
    if (!value) return 'not yet';
    try {
      return new Date(value).toLocaleTimeString();
    } catch (_) {
      return String(value);
    }
  }

  function formatReactorEvent(entry) {
    if (!entry) return 'event';
    const label = entry.label ? ' · ' + entry.label : '';
    return '[' + (entry.source || 'forge') + '] ' + entry.kind + label + ' @ ' + formatTimestamp(entry.timestampMs);
  }

  function spatialDepthLabel(zoom) {
    if (zoom < 0.8) return 'workspace shell';
    if (zoom < 1.25) return 'lane geometry';
    if (zoom < 1.85) return 'card topology';
    return 'fractal checklist detail';
  }

  function trimText(text, limit) {
    if (!text) return '';
    return text.length > limit ? text.slice(0, limit - 1) + '…' : text;
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  // ===== GRAPH FUNCTIONS =====

  function seedGraphFromCausalData() {
    const causal = seed.causalNodes || [];
    if (!causal.length) return;
    graphNodes = [];
    graphLinks = [];
    // causal is an array of CausalGraphNodeDTO
    causal.forEach((node) => {
      graphNodes.push({
        id: node.nodeId,
        opId: node.opId,
        opVersion: node.opVersion,
        parentNodeIds: node.parentNodeIds,
        causalKey: node.causalKey,
        topoOrdinal: node.topoOrdinal,
        causalClock: node.causalClock,
        x: 200 + (node.topoOrdinal * 180) + Math.random() * 60,
        y: 360 + Math.random() * 200 - 100,
        vx: 0, vy: 0,
        r: 24,
        color: nodeTypeColor(node.opId)
      });
    });
    // Build links from parentNodeIds
    const nodeById = {};
    graphNodes.forEach(n => { nodeById[n.id] = n; });
    graphNodes.forEach(n => {
      n.parentNodeIds.forEach(pid => {
        if (nodeById[pid]) {
          graphLinks.push({ source: pid, target: n.id });
        }
      });
    });
    updateGraphStats();
  }

  function nodeTypeColor(opId) {
    if (opId.startsWith('signal') || opId.startsWith('PriceFeed')) return '#9ece6a';
    if (opId.startsWith('transform') || opId.startsWith('KalmanFilter') || opId.startsWith('ArchetypeMatch')) return '#7aa2f7';
    if (opId.startsWith('decision') || opId.startsWith('LongEntry') || opId.startsWith('ShortEntry')) return '#7dcfff';
    if (opId.startsWith('sink') || opId.startsWith('OrderRouter') || opId.startsWith('RiskEngine')) return '#f7768e';
    return '#bb9af7';
  }

  function updateGraphStats() {
    if (graphStatNodes) graphStatNodes.textContent = graphNodes.length;
    if (graphStatLinks) graphStatLinks.textContent = graphLinks.length;
  }

  function renderGraph() {
    if (!graphSpatialRoot) return;
    if (!graphNodes || !graphLinks) return;
    const rect = graphSpatialRoot.getBoundingClientRect();
    graphSpatialRoot.setAttribute('viewBox', '0 0 ' + rect.width + ' ' + rect.height);
    graphSpatialRoot.innerHTML = '';

    // Draw links
    graphLinks.forEach(link => {
      const source = graphNodes.find(n => n.id === link.source);
      const target = graphNodes.find(n => n.id === link.target);
      if (!source || !target) return;
      const line = document.createElementNS(SVG_NS, 'line');
      line.setAttribute('x1', source.x);
      line.setAttribute('y1', source.y);
      line.setAttribute('x2', target.x);
      line.setAttribute('y2', target.y);
      line.setAttribute('stroke', 'rgba(122,162,247,0.25)');
      line.setAttribute('stroke-width', '1.5');
      graphSpatialRoot.appendChild(line);
    });

    // Draw nodes
    graphNodes.forEach(node => {
      const circle = document.createElementNS(SVG_NS, 'circle');
      circle.setAttribute('cx', node.x);
      circle.setAttribute('cy', node.y);
      circle.setAttribute('r', node.r);
      circle.setAttribute('fill', node.color);
      circle.setAttribute('stroke', 'rgba(122,162,247,0.4)');
      circle.setAttribute('stroke-width', '1.5');
      graphSpatialRoot.appendChild(circle);

      const text = document.createElementNS(SVG_NS, 'text');
      text.setAttribute('x', node.x);
      text.setAttribute('y', node.y + node.r + 14);
      text.setAttribute('fill', '#dbe7f3');
      text.setAttribute('font-size', '11');
      text.setAttribute('text-anchor', 'middle');
      text.textContent = node.id;
      graphSpatialRoot.appendChild(text);
    });

    updateGraphStats();
  }

  function fitGraph() {
    if (!graphNodes.length) return;
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    graphNodes.forEach(n => {
      minX = Math.min(minX, n.x - n.r);
      maxX = Math.max(maxX, n.x + n.r);
      minY = Math.min(minY, n.y - n.r);
      maxY = Math.max(maxY, n.y + n.r);
    });
    const pad = 40;
    const w = graphSpatialRoot.clientWidth;
    const h = graphSpatialRoot.clientHeight;
    const scaleX = (w - pad * 2) / (maxX - minX);
    const scaleY = (h - pad * 2) / (maxY - minY);
    graphTransform.k = Math.min(scaleX, scaleY, 3);
    graphTransform.x = pad - minX * graphTransform.k;
    graphTransform.y = pad - minY * graphTransform.k;
    if (graphZoomSlider) graphZoomSlider.value = graphTransform.k;
  }

  function centerGraph() {
    if (!graphNodes.length) return;
    const w = graphSpatialRoot.clientWidth;
    const h = graphSpatialRoot.clientHeight;
    const cx = graphNodes.reduce((a, n) => a + n.x, 0) / graphNodes.length;
    const cy = graphNodes.reduce((a, n) => a + n.y, 0) / graphNodes.length;
    graphTransform.x = w / 2 - cx * graphTransform.k;
    graphTransform.y = h / 2 - cy * graphTransform.k;
  }

  function bindGraphGestures() {
    if (!graphSpatialShell) return;
    let isPanning = false, panStart = { x: 0, y: 0 };
    graphSpatialShell.addEventListener('mousedown', e => {
      if (e.button === 1 || e.button === 2) {
        isPanning = true;
        panStart = { x: e.clientX, y: e.clientY };
        graphSpatialShell.style.cursor = 'grabbing';
        e.preventDefault();
      }
    });
    window.addEventListener('mousemove', e => {
      if (isPanning) {
        const dx = e.clientX - panStart.x;
        const dy = e.clientY - panStart.y;
        graphTransform.x += dx;
        graphTransform.y += dy;
        panStart = { x: e.clientX, y: e.clientY };
        renderGraph();
      }
    });
    window.addEventListener('mouseup', () => {
      isPanning = false;
      graphSpatialShell.style.cursor = 'grab';
    });
    graphSpatialShell.addEventListener('wheel', e => {
      e.preventDefault();
      const rect = graphSpatialShell.getBoundingClientRect();
      const mouseX = e.clientX - rect.left;
      const mouseY = e.clientY - rect.top;
      const factor = e.deltaY > 0 ? 0.9 : 1/0.9;
      const newK = clamp(graphTransform.k * factor, 0.2, 3);
      const scale = newK / graphTransform.k;
      graphTransform.x = mouseX - (mouseX - graphTransform.x) * scale;
      graphTransform.y = mouseY - (mouseY - graphTransform.y) * scale;
      graphTransform.k = newK;
      if (graphZoomSlider) graphZoomSlider.value = graphTransform.k;
      renderGraph();
    }, { passive: false });
    graphSpatialShell.addEventListener('dblclick', () => {
      graphTransform = { x: 0, y: 0, k: 1 };
      if (graphZoomSlider) graphZoomSlider.value = 1;
      renderGraph();
    });
  }

  // ===== END GRAPH FUNCTIONS =====

  // ===== ATTACHMENT (CouchDB-style) FUNCTIONS =====
  // PouchDB model: doc._attachments = { name: { content_type, data(base64), digest, length } }
  // Confix mapping: each attachment is a ConfixDoc record { _id, _rev, name, content_type, digest, length, docId }
  // Blob bytes live in ATTACHMENT_STORE; metadata lives in the workspace snapshot under item.attachments.

  const ATTACHMENT_STORE = 'attachments';

  function bindAttachmentDropZone() {
    const editor = document.querySelector('.editor');
    if (!editor) return;
    editor.addEventListener('dragover', (event) => {
      event.preventDefault();
      editor.style.background = 'rgba(122,162,247,0.06)';
    });
    editor.addEventListener('dragleave', () => {
      editor.style.background = '';
    });
    editor.addEventListener('drop', async (event) => {
      event.preventDefault();
      editor.style.background = '';
      const files = Array.from(event.dataTransfer.files || []);
      if (!files.length) return;
      const selected = itemById(state.selectedItemId);
      const docId = selected ? selected.id : 'workspace';
      for (const file of files) {
        await writeAttachmentToIndexedDb(docId, file.name, file);
      }
      recordReactor('CacheStored', files.length + ' attachment(s) → ' + docId, 'couch');
      saveState();
      render();
    });
  }

  async function writeAttachmentToIndexedDb(docId, name, file) {
    const db = await openPersistenceDb();
    if (!db) return;
    const buffer = await file.arrayBuffer();
    const digest = 'md5-' + await computeDigest(buffer);
    const attachmentRecord = {
      id: docId + '/' + name,
      docId: docId,
      name: name,
      content_type: file.type || 'application/octet-stream',
      length: file.size,
      digest: digest,
      data: buffer,
      storedAtMs: Date.now(),
    };
    // Ensure object store exists
    await ensureAttachmentStore(db);
    await new Promise((resolve, reject) => {
      const tx = db.transaction(ATTACHMENT_STORE, 'readwrite');
      tx.objectStore(ATTACHMENT_STORE).put(attachmentRecord);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error || new Error('Attachment write failed'));
    });
    // Link attachment metadata into the item
    const item = itemById(docId);
    if (item) {
      if (!item.attachments) item.attachments = {};
      item.attachments[name] = {
        content_type: attachmentRecord.content_type,
        length: attachmentRecord.length,
        digest: attachmentRecord.digest,
        storedAtMs: attachmentRecord.storedAtMs,
      };
    }
  }

  async function ensureAttachmentStore(db) {
    if (db.objectStoreNames.contains(ATTACHMENT_STORE)) return;
    // Need version bump to add store
    const currentVersion = db.version;
    db.close();
    persistenceDbPromise = null;
    await new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, currentVersion + 1);
      request.onupgradeneeded = () => {
        const upgradedDb = request.result;
        if (!upgradedDb.objectStoreNames.contains(SNAPSHOT_STORE)) {
          upgradedDb.createObjectStore(SNAPSHOT_STORE, { keyPath: 'id' });
        }
        if (!upgradedDb.objectStoreNames.contains(EVENT_STORE)) {
          const store = upgradedDb.createObjectStore(EVENT_STORE, { keyPath: 'id' });
          store.createIndex('timestampMs', 'timestampMs', { unique: false });
        }
        if (!upgradedDb.objectStoreNames.contains(ATTACHMENT_STORE)) {
          upgradedDb.createObjectStore(ATTACHMENT_STORE, { keyPath: 'id' });
        }
      };
      request.onsuccess = () => {
        persistenceDbPromise = Promise.resolve(request.result);
        resolve();
      };
      request.onerror = () => reject(request.error);
    });
  }

  async function computeDigest(buffer) {
    // Simple hash — not cryptographic, but deterministic for dedup
    let hash = 0;
    const view = new Uint8Array(buffer);
    for (let i = 0; i < view.length; i++) {
      hash = ((hash << 5) - hash + view[i]) | 0;
    }
    return Math.abs(hash).toString(16);
  }

  async function readAttachmentBlob(docId, name) {
    const db = await openPersistenceDb();
    if (!db || !db.objectStoreNames.contains(ATTACHMENT_STORE)) return null;
    return new Promise((resolve, reject) => {
      const tx = db.transaction(ATTACHMENT_STORE, 'readonly');
      const request = tx.objectStore(ATTACHMENT_STORE).get(docId + '/' + name);
      request.onsuccess = () => {
        const record = request.result;
        if (!record || !record.data) return resolve(null);
        resolve(new Blob([record.data], { type: record.content_type }));
      };
      request.onerror = () => reject(request.error);
    }).catch(() => null);
  }

  async function listAttachments(docId) {
    const db = await openPersistenceDb();
    if (!db || !db.objectStoreNames.contains(ATTACHMENT_STORE)) return [];
    return new Promise((resolve, reject) => {
      const tx = db.transaction(ATTACHMENT_STORE, 'readonly');
      const request = tx.objectStore(ATTACHMENT_STORE).getAll();
      request.onsuccess = () => {
        const all = request.result || [];
        resolve(all.filter(r => r.docId === docId));
      };
      request.onerror = () => reject(request.error);
    }).catch(() => []);
  }

  // ===== END ATTACHMENT FUNCTIONS =====

  render();
})();
""".trimIndent()
