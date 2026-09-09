#!/usr/bin/env node
'use strict';

const {spawn, spawnSync} = require('node:child_process');
const milliseconds = Number(process.argv[2]);
const command = process.argv.slice(3);
if (!Number.isInteger(milliseconds) || milliseconds < 1000 || !command.length) {
  throw new Error('Usage: process-deadline.cjs MILLISECONDS COMMAND [ARGS...]');
}

const started = performance.now();
const owned = new Map();
let exitCode = null;
let stopping = false;
let cleanupAt = Infinity;
let result = 0;

function processes() {
  const snapshot = spawnSync('ps', ['-axo', 'pid=,ppid=,lstart='], {encoding: 'utf8', timeout: 1000});
  if (snapshot.status !== 0) throw new Error('Cannot inspect owned build processes');
  return snapshot.stdout.trim().split('\n').map(line => {
    const [, pid, parent, identity] = line.match(/^\s*(\d+)\s+(\d+)\s+(.+?)\s*$/) || [];
    return {pid: Number(pid), parent: Number(parent), identity};
  }).filter(row => row.pid);
}

// Gradle's single-use daemon can create a new process group. Track descendants
// by PID and start time so a deadline covers that daemon without touching others.
function descendants() {
  const rows = processes();
  const live = new Map(rows.map(row => [row.pid, row]));
  for (const [pid, identity] of owned) {
    if (live.get(pid)?.identity !== identity) owned.delete(pid);
  }
  let added;
  do {
    added = false;
    for (const row of rows) {
      if ((row.pid === child.pid && exitCode === null) || owned.has(row.parent)) {
        if (!owned.has(row.pid)) { owned.set(row.pid, row.identity); added = true; }
      }
    }
  } while (added);
  return [...owned.keys()].reverse();
}

function signal(pids, name) {
  for (const pid of pids) {
    try { process.kill(pid, name); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  }
}

function stop(code) {
  if (stopping) return;
  stopping = true;
  result = code;
  cleanupAt = Math.min(started + milliseconds, performance.now() + 4000);
  signal(descendants(), 'SIGTERM');
}

const child = spawn(command[0], command.slice(1), {stdio: 'inherit'});
child.on('error', error => { console.error(error.message); exitCode = 127; });
child.on('exit', (code, name) => { exitCode = code ?? (name === 'SIGINT' ? 130 : 143); });
process.on('SIGINT', () => stop(130));
process.on('SIGTERM', () => stop(143));

const grace = Math.min(5000, milliseconds / 4);
const timer = setInterval(() => {
  const pids = descendants();
  const elapsed = performance.now() - started;
  if (!stopping && elapsed >= milliseconds - grace) {
    console.error(`Build deadline: ${milliseconds}ms including process cleanup`);
    stop(124);
  }
  if (exitCode !== null && !stopping && pids.length) stop(exitCode);
  if (stopping && performance.now() >= cleanupAt) signal(pids, 'SIGKILL');
  if ((exitCode !== null && pids.length === 0) || elapsed >= milliseconds) {
    if (pids.length) signal(pids, 'SIGKILL');
    clearInterval(timer);
    console.error(`Build elapsed: ${(elapsed / 1000).toFixed(1)}s`);
    process.exitCode = stopping ? result : exitCode;
  }
}, 100);
