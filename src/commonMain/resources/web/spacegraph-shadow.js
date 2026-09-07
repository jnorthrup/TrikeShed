/* The LCNC editor owns its document and all effects. This presenter consumes the commonMain projection. */
(() => {
  "use strict";
  let open = false, backend = "canvas", response = null, request = null, timer = 0, serial = 0;
  let panel, surface, details, status, button;

  function canvasFrame(frame) {
    const canvas = document.createElement("canvas");
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(frame.width * ratio); canvas.height = Math.round(frame.height * ratio);
    canvas.style.width = "100%"; canvas.style.height = "100%";
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("Canvas 2D is unavailable");
    ctx.scale(ratio, ratio); ctx.fillStyle = frame.background; ctx.fillRect(0, 0, frame.width, frame.height);
    for (const item of frame.items) {
      ctx.save();
      if (item.clip) { ctx.beginPath(); ctx.rect(...item.clip); ctx.clip(); }
      if (item.type === "path") {
        ctx.beginPath();
        for (const [op, ...args] of item.path) {
          if (op === "M") ctx.moveTo(...args);
          else if (op === "L") ctx.lineTo(...args);
          else if (op === "Q") ctx.quadraticCurveTo(...args);
          else if (op === "C") ctx.bezierCurveTo(...args);
          else if (op === "Z") ctx.closePath();
        }
        if (item.fill) { ctx.fillStyle = item.fill; ctx.fill(); }
        if (item.stroke) {
          ctx.strokeStyle = item.stroke; ctx.lineWidth = item.width;
          ctx.lineCap = "round"; ctx.lineJoin = "round"; ctx.setLineDash(item.dash); ctx.lineDashOffset = item.dashOffset;
          ctx.stroke();
        }
      } else if (item.type === "text") {
        ctx.font = `${item.size}px ${item.font}`; ctx.fillStyle = item.color;
        ctx.fillText(item.text, item.position[0], item.position[1]);
      }
      ctx.restore();
    }
    canvas.addEventListener("click", event => {
      const r = canvas.getBoundingClientRect(); const x = (event.clientX - r.left) * frame.width / r.width;
      const y = (event.clientY - r.top) * frame.height / r.height;
      for (const item of [...frame.items].reverse()) {
        if (item.type === "text" && item.clip && x >= item.clip[0] && x <= item.clip[0] + item.clip[2] && y >= item.clip[1] && y <= item.clip[1] + item.clip[3]) {
          inspect(item.entityId); break;
        }
      }
    });
    return canvas;
  }

  function inspect(id) {
    details.replaceChildren();
    const row = response?.alignment?.nodes?.find(node => node.id === id);
    const heading = document.createElement("strong"); heading.textContent = row ? `${id} · ${row.type}` : id;
    details.append(heading);
    const values = [
      ["Ports", (response?.ports || []).filter(port => port.nodeId === id).map(port => `${port.input ? "in" : "out"} ${port.name}: ${port.kind || "generic"}`)],
      ["Rete", row?.watchedBy || []], ["Causal Rules", row?.causalRules || []], ["KIF", row?.facts || []],
    ];
    for (const [label, entries] of values) {
      const section = document.createElement("div"); const title = document.createElement("b"); title.textContent = label;
      const content = document.createElement("pre"); content.textContent = entries.length ? entries.join("\n") : "None";
      section.append(title, content); details.append(section);
    }
    const focus = document.createElement("button"); focus.textContent = "Focus in LCNC";
    focus.onclick = () => {
      if (typeof G === "undefined") return;
      const node = G.nodes.find(n => n.id === id);
      node?.el?.scrollIntoView({ block: "center", inline: "center", behavior: "smooth" });
      node?.el?.animate([{ outline: "3px solid #0891b2" }, { outline: "0 solid transparent" }], { duration: 1200 });
    };
    details.append(focus);
  }

  function draw() {
    if (!response || !open) return;
    surface.replaceChildren();
    if (backend === "svg") {
      const doc = new DOMParser().parseFromString(response.svg, "image/svg+xml");
      if (doc.querySelector("parsererror")) throw new Error("Invalid SVG projection");
      const svg = document.importNode(doc.documentElement, true);
      svg.style.width = "100%"; svg.style.height = "100%";
      svg.addEventListener("click", e => { const id = e.target.closest("[data-entity]")?.getAttribute("data-entity"); if (id) inspect(id); });
      surface.append(svg);
    } else surface.append(canvasFrame(response.frame));
    const issues = response.issues?.length || 0;
    status.textContent = `${backend === "svg" ? "SVG" : "Canvas 2D"} · ${response.alignment.nodes.length} nodes · ${response.ports.length} ports${issues ? ` · ${issues} issues` : ""}`;
    status.title = response.epistemicStatus;
  }

  async function refresh() {
    if (!open || typeof serialize !== "function") return;
    request?.abort(); request = new AbortController(); const current = ++serial;
    const width = Math.max(1, Math.round(surface.clientWidth)); const height = Math.max(1, Math.round(surface.clientHeight));
    status.textContent = "Projecting";
    try {
      const doc = serialize();
      const reply = await fetch(`/api/lcnc/spacegraph?width=${width}&height=${height}`, {
        method: "POST", headers: { "Content-Type": "application/json" }, signal: request.signal,
        body: JSON.stringify({ ...doc, name: typeof BOARD !== "undefined" ? BOARD.name || "canvas" : "canvas" }),
      });
      const body = await reply.json();
      if (!reply.ok) throw new Error(body.error || `Projection failed (${reply.status})`);
      if (current !== serial || !open) return;
      response = body; draw();
    } catch (error) { if (error.name !== "AbortError") status.textContent = error.message; }
  }
  function schedule() { if (!open) return; clearTimeout(timer); timer = setTimeout(refresh, 120); }

  addEventListener("DOMContentLoaded", () => {
    button = document.getElementById("spacegraphBtn"); if (!button) return;
    panel = document.createElement("aside"); panel.id = "spacegraph-shadow"; panel.hidden = true;
    const bar = document.createElement("header"); const title = document.createElement("strong"); title.textContent = "SpaceGraph";
    const select = document.createElement("select"); select.setAttribute("aria-label", "Rendering provider");
    for (const [value, label] of [["canvas", "Canvas"], ["svg", "SVG"]]) {
      const option = document.createElement("option"); option.value = value; option.textContent = label; select.append(option);
    }
    select.onchange = () => { backend = select.value; draw(); };
    const close = document.createElement("button"); close.textContent = "×"; close.title = "Close SpaceGraph shadow";
    close.setAttribute("aria-label", "Close SpaceGraph shadow"); close.onclick = () => button.click();
    bar.append(title, select, close); status = document.createElement("div"); status.className = "sg-status"; status.setAttribute("role", "status");
    surface = document.createElement("div"); surface.className = "sg-surface";
    details = document.createElement("div"); details.className = "sg-details";
    panel.append(bar, status, surface, details); document.body.append(panel);
    button.onclick = () => {
      open = !open; panel.hidden = !open; button.setAttribute("aria-pressed", String(open));
      document.body.classList.toggle("sg-shadow-open", open);
      if (open) schedule(); else { request?.abort(); clearTimeout(timer); }
      dispatchEvent(new Event("resize"));
    };
    new ResizeObserver(schedule).observe(surface);
    if (new URLSearchParams(location.search).get("spacegraph") === "1") button.click();
  });
  addEventListener("lcnc:shadow-update", schedule);
})();
