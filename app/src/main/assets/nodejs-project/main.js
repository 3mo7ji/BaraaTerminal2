'use strict';
const fs = require('fs');
const path = require('path');
const http = require('http');
const Module = require('module');
const projectDir = process.argv[2];
const logFile = process.argv[3];
if (!projectDir || !logFile) throw new Error('Missing R7Host project or log path.');
function write(level, values) {
  const message = values.map(value => typeof value === 'string' ? value : value?.stack || (() => { try { return JSON.stringify(value); } catch { return String(value); } })()).join(' ');
  try { fs.appendFileSync(logFile, `[${new Date().toISOString()}] [${level}] ${message}\n`); } catch (_) {}
}
console.log = (...args) => write('INFO', args);
console.info = (...args) => write('INFO', args);
console.warn = (...args) => write('WARN', args);
console.error = (...args) => write('ERROR', args);
process.on('uncaughtException', error => write('FATAL', [error]));
process.on('unhandledRejection', error => write('REJECTION', [error]));
process.env.PORT = '3000';
process.env.BOTDOCK_HOST = '127.0.0.1';
process.env.R7HOST_ANDROID = '1';
process.env.R7HOST_SHARED_NODE_MODULES = path.join(__dirname, 'node_modules');
process.env.NODE_PATH = process.env.R7HOST_SHARED_NODE_MODULES;
process.env.R7HOST_DATA_ROOT = projectDir;
Module._initPaths();
const serverFile = path.join(projectDir, 'server.js');
if (!fs.existsSync(serverFile)) throw new Error(`R7Host server.js missing: ${serverFile}`);
require(serverFile);
const control = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify({ ok: true, app: 'R7Host', port: 3000, node: process.version }));
});
control.listen(3001, '127.0.0.1', () => write('INFO', ['R7Host control endpoint ready.']));
control.on('error', error => write('ERROR', ['Control endpoint error', error]));
write('INFO', [`R7Host ${process.version} initialized from ${projectDir}`]);
