export class GalleryRenderer {
    static renderHtml(catalogData, snapshot) {
        let html = '<div class="gallery-root" style="padding:16px;">';
        html += '<style>';
        html += `
            .gallery-root { font-family: Inter, system-ui, sans-serif; color: #dbe7f3; background: #090d13; }
            .gallery-section { margin-bottom: 24px; }
            .gallery-section h3 { margin: 0 0 12px; color: #7dcfff; font-size: 13px; text-transform: uppercase; letter-spacing: 0.1em; }
            .gallery-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
            .gallery-card { padding: 16px; border: 1px solid #1b2635; border-radius: 12px; background: linear-gradient(180deg, rgba(18,24,36,.97), rgba(12,18,28,.95)); transition: border-color 120ms, box-shadow 120ms; }
            .gallery-card:hover { border-color: rgba(122,162,247,.45); box-shadow: 0 0 0 1px rgba(122,162,247,.12); }
            .gallery-card .name { font-weight: 700; font-size: 15px; margin-bottom: 6px; color: #dbe7f3; }
            .gallery-card .synopsis { font-size: 12px; color: #7e8da0; margin-bottom: 8px; }
            .gallery-card .id { font-size: 11px; color: #7aa2f7; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; margin-bottom: 8px; }
            .gallery-card .meta { font-size: 10px; color: #7e8da0; line-height: 1.5; }
            .gallery-card .meta .targets { color: #e0af68; }
            .gallery-card .meta .preview { color: #7dcfff; }
        `;
        html += '</style>';

        if (snapshot) {
            html += '<section class="gallery-section">';
            html += '<h3>LineCas RTS Snapshot</h3>';
            html += '<div style="margin-bottom: 12px; font-size: 12px; color: #7e8da0;">';
            html += `Aperture: <strong>${snapshot.apertureName}</strong><br/>`;
            html += 'Legend: CANDIDATE | PROVISIONAL | CONFIRMED';
            html += '</div>';
            html += '<div class="gallery-list">';
            for (const bucket of snapshot.topKBuckets) {
                const count = snapshot.counts[bucket] || 0;
                html += '<article class="gallery-card">';
                html += `<div class="name">${bucket}</div>`;
                html += `<div class="meta">Count: ${count}</div>`;
                html += '</article>';
            }
            html += '</div>';
            html += '</section>';
        }

        const widgets = catalogData.widgets || [];
        const sections = {};
        for (const widget of widgets) {
            const sec = widget.section || 'Uncategorized';
            if (!sections[sec]) sections[sec] = [];
            sections[sec].push(widget);
        }

        for (const sectionName of Object.keys(sections).sort()) {
            html += '<section class="gallery-section">';
            html += `<h3>${sectionName}</h3>`;
            html += '<div class="gallery-list">';
            for (const widget of sections[sectionName]) {
                html += '<article class="gallery-card">';
                html += `<div class="name">${widget.name}</div>`;
                html += `<div class="synopsis">${widget.synopsis || ''}</div>`;
                html += `<div class="id">${widget.id}</div>`;
                html += '<div class="meta">';
                html += `<span class="targets">Targets: ${(widget.supportTargets || []).join(', ')}</span><br/>`;
                html += `<span class="preview">Preview: ${widget.previewToken}</span>`;
                if (widget.apiSignature) {
                    html += `<br/><code style="font-size:9px; color:#7e8da0;">${widget.apiSignature}</code>`;
                }
                html += '</div>';
                html += '</article>';
            }
            html += '</div>';
            html += '</section>';
        }

        html += '</div>';
        return html;
    }
}
