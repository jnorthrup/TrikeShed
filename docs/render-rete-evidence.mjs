import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {evidenceModel} from './rete-evidence-model.mjs';

const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('Usage: node docs/render-rete-evidence.mjs snapshot.json output.html');
const directory = path.dirname(fileURLToPath(import.meta.url));
const data = JSON.parse(fs.readFileSync(input, 'utf8'));
const model = evidenceModel(data);
const template = fs.readFileSync(path.join(directory, 'rete-evidence.template.html'), 'utf8');
const source = fs.readFileSync(path.join(directory, 'rete-evidence-model.mjs'), 'utf8').replaceAll('export function ', 'function ');
const html = template.replace('/* SNAPSHOT_DATA */', () => JSON.stringify(data).replaceAll('<', '\\u003c'))
  .replace('/* EVIDENCE_MODEL */', () => source);
fs.writeFileSync(output, html);
console.log(JSON.stringify({output, nodes:model.nodes.size, directEvidenceEdges:model.edges.length, capture:data.capture?.kind ?? 'unknown'}));
