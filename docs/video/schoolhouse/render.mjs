// Renders the film frame by frame in headless Chromium and encodes it with ffmpeg.
//   node render.mjs out.mp4 [fps=30] [workers=4]
//   node render.mjs --stills dir t1 t2 …     (PNG stills for review)
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); } catch { ({ chromium } = require(join(execFileSync('npm', ['root', '-g']).toString().trim(), 'playwright'))); }

const here = dirname(fileURLToPath(import.meta.url));
const url = pathToFileURL(join(here, 'index.html')).href + '?capture';

async function page(browser) {
  const pg = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  await pg.goto(url);
  await pg.evaluate(() => Promise.all([document.fonts.load('20px KaTeXMath', 'αλ'), document.fonts.load("20px 'JetBrains Mono'")]).then(() => document.fonts.ready));
  return pg;
}

const args = process.argv.slice(2);
const browser = await chromium.launch();
if (args[0] === '--stills') {
  const dir = resolve(args[1]); mkdirSync(dir, { recursive: true });
  const pg = await page(browser);
  for (const t of args.slice(2)) {
    await pg.evaluate(t => window.renderAt(t), Number(t));
    await pg.screenshot({ path: join(dir, `still-${t}.png`) });
  }
} else {
  const out = resolve(args[0] ?? 'compositional-density.mp4');
  const fps = Number(args[1] ?? 30), workers = Number(args[2] ?? 4);
  const frames = mkdtempSync(join(tmpdir(), 'film-'));
  const first = await page(browser);
  const { duration, scenes } = await first.evaluate(() => ({ duration: Film.duration, scenes: Film.scenes }));
  const total = Math.round(duration * fps);
  const pages = [first, ...await Promise.all(Array.from({ length: workers - 1 }, () => page(browser)))];
  let next = 0, done = 0;
  await Promise.all(pages.map(async pg => {
    for (let f; (f = next++) < total;) {
      await pg.evaluate(t => window.renderAt(t), f / fps);
      await pg.screenshot({ path: join(frames, `f${String(f).padStart(6, '0')}.jpg`), type: 'jpeg', quality: 92 });
      if (++done % 300 === 0) console.log(`${done}/${total}`);
    }
  }));
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(fps), '-i', join(frames, 'f%06d.jpg'),
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-preset', 'medium', '-movflags', '+faststart', out], { stdio: 'inherit' });
  // captions as a sidecar subtitle track (the narration script, timed to the scenes)
  const ts = s => { const ms = Math.round(s * 1000); const p = (n, w = 2) => String(n).padStart(w, '0');
    return `${p(Math.floor(ms / 3600000))}:${p(Math.floor(ms / 60000) % 60)}:${p(Math.floor(ms / 1000) % 60)},${p(ms % 1000, 3)}`; };
  writeFileSync(out.replace(/\.mp4$/, '.srt'), scenes.map((s, i) => `${i + 1}\n${ts(s.t0 + 0.4)} --> ${ts(s.t0 + s.dur - 0.4)}\n${s.say}\n`).join('\n'));
  rmSync(frames, { recursive: true, force: true });
  console.log(`wrote ${out} (${total} frames, ${duration}s)`);
}
await browser.close();
