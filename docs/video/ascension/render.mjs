// Builds the film: the score from the film's own clock, then every frame rendered in headless Chromium and piped
// in order into ffmpeg with the score as its soundtrack.
//   node render.mjs out.mp4 [fps=60] [workers=4] [width=1920]   the film, and score.m4a for the player page
//   node render.mjs --stills dir t1 t2 …                        PNG stills for review
//   node render.mjs --meta out.json                             score data alone (events, ascent curve)
import { createRequire } from 'node:module';
import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { once } from 'node:events';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); } catch { ({ chromium } = require(join(execFileSync('npm', ['root', '-g']).toString().trim(), 'playwright'))); }

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const width = args[0] === '--stills' || args[0] === '--meta' ? 1920 : Number(args[3] ?? 1920);
const url = pathToFileURL(join(here, 'index.html')).href + `?capture&w=${width}`;

async function page(browser) {
  const pg = await browser.newPage({ viewport: { width, height: Math.round(width * 9 / 16) } });
  pg.on('pageerror', e => console.error('page error:', e.message));
  await pg.goto(url);
  await pg.evaluate(() => Promise.all([
    document.fonts.load('20px KaTeXMath', 'αλ'), document.fonts.load('20px KaTeXMain', 'a⋈∘→·≠'), document.fonts.load('20px KaTeXAMS', '↻⇄'),
    document.fonts.load("20px 'JetBrains Mono'", 'a'),
  ]).then(() => document.fonts.ready));
  return pg;
}
const grab = (pg, t, type = 'image/jpeg') => pg.evaluate(([t, type]) => { renderAt(t); return document.getElementById('cv').toDataURL(type, 0.94).split(',')[1]; }, [t, type]).then(b => Buffer.from(b, 'base64'));
const meta = pg => pg.evaluate(() => ({ duration: Film.duration, events: Film.events, curve: Film.curve(100), levels: Film.levels }));

const browser = await chromium.launch();
if (args[0] === '--stills') {
  const dir = resolve(args[1]); mkdirSync(dir, { recursive: true });
  const pg = await page(browser);
  for (const t of args.slice(2)) writeFileSync(join(dir, `a-${t}.png`), await grab(pg, Number(t), 'image/png'));
} else if (args[0] === '--meta') {
  writeFileSync(resolve(args[1]), JSON.stringify(await meta(await page(browser))));
} else {
  const out = resolve(args[0] ?? 'ascension.mp4'), fps = Number(args[1] ?? 60), workers = Number(args[2] ?? 4);
  const first = await page(browser), tmp = mkdtempSync(join(tmpdir(), 'ascension-'));
  const film = await meta(first), wav = join(tmp, 'score.wav'), total = Math.round(film.duration * fps);
  writeFileSync(join(tmp, 'meta.json'), JSON.stringify(film));
  execFileSync('python3', [join(here, 'score.py'), join(tmp, 'meta.json'), wav], { stdio: 'inherit' });
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', wav, '-c:a', 'aac', '-b:a', '160k', join(here, 'score.m4a')]);
  const pages = [first, ...await Promise.all(Array.from({ length: workers - 1 }, () => page(browser)))];
  const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-', '-i', wav,
    '-map', '0:v', '-map', '1:a', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '17', '-preset', 'medium', '-c:a', 'aac', '-b:a', '192k',
    '-shortest', '-movflags', '+faststart', out], { stdio: ['pipe', 'inherit', 'inherit'] });
  const pending = new Map(); let next = 0, written = 0, writing = false;
  const flush = async () => {
    if (writing) return; writing = true;
    while (pending.has(written)) { const b = pending.get(written); pending.delete(written); written++; if (!ff.stdin.write(b)) await once(ff.stdin, 'drain'); }
    writing = false;
  };
  const t0 = Date.now();
  await Promise.all(pages.map(async pg => {
    for (let f; (f = next++) < total;) {
      while (f - written > 240) await new Promise(r => setTimeout(r, 20));
      pending.set(f, await grab(pg, f / fps));
      await flush();
      if (f % 600 === 0) console.log(`${f}/${total} ${((Date.now() - t0) / 1000).toFixed(0)}s`);
    }
  }));
  await flush(); ff.stdin.end(); await once(ff, 'close');
  rmSync(tmp, { recursive: true, force: true });
  console.log(`wrote ${out} (${total} frames, ${film.duration.toFixed(2)}s)`);
}
await browser.close();
