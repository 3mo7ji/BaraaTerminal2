'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const Module = require('module');

const projectDir = process.argv[2];
const logFile = process.argv[3];
if (!projectDir || !logFile) throw new Error('Missing project or log path argument.');

function writeLog(level, values) {
  const message = values.map((value) => {
    if (typeof value === 'string') return value;
    if (value && value.stack) return value.stack;
    try { return JSON.stringify(value); } catch (_) { return String(value); }
  }).join(' ');
  try {
    fs.appendFileSync(logFile, `[NODE ${level}] ${message}\n`, 'utf8');
  } catch (_) { /* Keep the runtime alive if the log file is temporarily unavailable. */ }
}

console.log = (...args) => writeLog('INFO', args);
console.info = (...args) => writeLog('INFO', args);
console.warn = (...args) => writeLog('WARN', args);
console.error = (...args) => writeLog('ERROR', args);
process.on('uncaughtException', (error) => writeLog('FATAL', [error]));
process.on('unhandledRejection', (error) => writeLog('REJECTION', [error]));

process.chdir(projectDir);
process.env.PORT = '3000';
process.env.BOTDOCK_HOST = '127.0.0.1';
process.env.NODE_PATH = path.join(__dirname, 'node_modules');
Module._initPaths();

const serverFile = path.join(projectDir, 'server.js');
if (!fs.existsSync(serverFile)) throw new Error(`BotDock server.js was not found: ${serverFile}`);
let source = fs.readFileSync(serverFile, 'utf8');

const childProcessImport = "const { spawn, execFileSync } = require('child_process');";
if (!source.includes(childProcessImport)) {
  throw new Error('نسخة server.js غير متوقعة؛ لم أستطع تهيئتها لمحرك Android.');
}
source = source.replace(childProcessImport, `
const spawn = () => { throw new Error('تشغيل العمليات الفرعية غير مدعوم داخل محرك Node.js المدمج في Android.'); };
const execFileSync = () => { throw new Error('تنفيذ أوامر النظام غير مدعوم داخل محرك Android المدمج.'); };
`);

source = source.replace('function scanNodeBinaries() {', `function scanNodeBinaries() {
  if (process.versions.mobile) {
    const version = process.versions.node || '18.20.4';
    return { current: version, versions: [{ major: Number(version.split('.')[0]), version, path: 'embedded', system: true, label: 'Node.js مدمج — لوحة التحكم فقط' }] };
  }
`);

source = source.replace("  const line = `${new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Riyadh' }).replace(' ', ' ')} [${level.toUpperCase()}] ${text}`;", "  const line = `${new Date().toISOString()} [${level.toUpperCase()}] ${text}`;");

source = source.replace('function startBot(id) {', `function startBot(id) {
  if (process.versions.mobile) {
    throw Object.assign(new Error('تشغيل بوتات Discord كعمليات مستقلة غير مدعوم في محرك Node.js المدمج على Android. يمكنك استخدام لوحة الإدارة والملفات والسجلات، لكن تشغيل البوتات يحتاج بيئة Node.js تدعم child_process مثل Termux أو خادم Linux.'), { status: 501 });
  }
`);

const tailMarker = 'const server = app.listen(PORT, HOST, () => {';
const tailIndex = source.indexOf(tailMarker);
if (tailIndex < 0) throw new Error('تعذر العثور على نقطة بدء خادم BotDock في server.js.');
source = source.slice(0, tailIndex) + `
let mobileServer = null;
let mobileServerStarting = false;
function startMobileHost() {
  if (mobileServer && mobileServer.listening) return Promise.resolve();
  if (mobileServerStarting) return Promise.resolve();
  mobileServerStarting = true;
  return new Promise((resolve, reject) => {
    const nextServer = app.listen(PORT, HOST, () => {
      mobileServer = nextServer;
      mobileServerStarting = false;
      console.log('BotDock Host ready');
      console.log('لوحة التحكم: http://' + HOST + ':' + PORT);
      console.log('رمز الدخول (8 أرقام): ' + adminConfig.pin);
      console.log('وضع Android: لوحة الإدارة تعمل؛ تشغيل البوتات كعمليات مستقلة غير مدعوم.');
      resolve();
    });
    nextServer.once('error', (error) => {
      mobileServerStarting = false;
      reject(error);
    });
  });
}
function stopMobileHost() {
  return new Promise((resolve) => {
    if (!mobileServer || !mobileServer.listening) {
      mobileServer = null;
      resolve();
      return;
    }
    const oldServer = mobileServer;
    mobileServer = null;
    oldServer.close(() => resolve());
  });
}
globalThis.__BOTDOCK_MOBILE__ = {
  start: startMobileHost,
  stop: stopMobileHost,
  isRunning: () => Boolean(mobileServer && mobileServer.listening),
  isStarting: () => mobileServerStarting
};
startMobileHost().catch((error) => console.error('BotDock start failed:', error));
`;

fs.writeFileSync(serverFile, source, 'utf8');
require(serverFile);

const controller = http.createServer(async (request, response) => {
  const send = (status, data) => {
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(data));
  };
  try {
    if (request.method === 'GET' && request.url === '/status') {
      return send(200, {
        engine: true,
        running: Boolean(globalThis.__BOTDOCK_MOBILE__?.isRunning()),
        starting: Boolean(globalThis.__BOTDOCK_MOBILE__?.isStarting())
      });
    }
    if (request.method === 'POST' && request.url === '/start') {
      await globalThis.__BOTDOCK_MOBILE__.start();
      writeLog('INFO', ['طلب تشغيل لوحة BotDock من التطبيق.']);
      return send(200, { ok: true, running: true });
    }
    if (request.method === 'POST' && request.url === '/stop') {
      await globalThis.__BOTDOCK_MOBILE__.stop();
      writeLog('INFO', ['طلب إيقاف لوحة BotDock من التطبيق.']);
      return send(200, { ok: true, running: false });
    }
    return send(404, { error: 'Not found' });
  } catch (error) {
    writeLog('ERROR', [error]);
    return send(500, { error: String(error.message || error) });
  }
});
controller.listen(3001, '127.0.0.1', () => writeLog('INFO', ['وحدة التحكم الداخلية جاهزة على المنفذ 3001.']));
controller.on('error', (error) => writeLog('ERROR', ['Control server error:', error]));
writeLog('INFO', [`Node.js ${process.version} started for ${projectDir}`]);
