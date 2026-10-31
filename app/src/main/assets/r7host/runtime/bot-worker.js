'use strict';

// One Worker Thread per Discord bot. This is NOT an OS child process and
// intentionally does not expose arbitrary shell execution on Android.
const path = require('path');
const fs = require('fs');
const { parentPort, workerData } = require('worker_threads');
const { pathToFileURL } = require('url');
const Module = require('module');

const root = path.resolve(workerData.cwd || path.dirname(workerData.scriptPath));
let virtualCwd = root;
Object.assign(process.env, workerData.env || {});
process.env.PWD = root;
process.env.BOTDOCK_PROJECT_DIR = root;
process.env.BOTDOCK_SERVER_ID = String(workerData.id || '');
process.argv = [process.execPath, workerData.scriptPath, ...(workerData.args || [])];
process.cwd = () => virtualCwd;
process.chdir = (next) => { virtualCwd = path.resolve(virtualCwd, String(next)); };

function resolvePath(value) {
  if (typeof value === 'string' && value && !path.isAbsolute(value)) return path.resolve(virtualCwd, value);
  if (Buffer.isBuffer(value)) return value;
  if (value instanceof URL) return value;
  return value;
}

// Make common fs calls respect the per-bot virtual working directory.
const syncMethods = [
  'accessSync', 'appendFileSync', 'chmodSync', 'chownSync', 'copyFileSync', 'existsSync',
  'lstatSync', 'mkdirSync', 'mkdtempSync', 'openSync', 'readFileSync', 'readdirSync',
  'readlinkSync', 'realpathSync', 'renameSync', 'rmSync', 'rmdirSync', 'statSync',
  'unlinkSync', 'writeFileSync', 'createReadStream', 'createWriteStream'
];
for (const name of syncMethods) {
  if (typeof fs[name] !== 'function') continue;
  const original = fs[name].bind(fs);
  fs[name] = (first, ...rest) => original(resolvePath(first), ...rest);
}
const asyncMethods = [
  'access', 'appendFile', 'chmod', 'chown', 'copyFile', 'lstat', 'mkdir', 'open',
  'readFile', 'readdir', 'readlink', 'realpath', 'rename', 'rm', 'rmdir', 'stat',
  'unlink', 'writeFile'
];
for (const name of asyncMethods) {
  if (typeof fs[name] !== 'function') continue;
  const original = fs[name].bind(fs);
  fs[name] = (first, ...rest) => original(resolvePath(first), ...rest);
}
for (const name of asyncMethods) {
  if (typeof fs.promises[name] !== 'function') continue;
  const original = fs.promises[name].bind(fs.promises);
  fs.promises[name] = (first, ...rest) => original(resolvePath(first), ...rest);
}

Module._initPaths();
let exitRequested = false;
const originalExit = process.exit.bind(process);
process.exit = (code = 0) => {
  if (!exitRequested) {
    exitRequested = true;
    parentPort.postMessage({ type: 'exit-request', code: Number(code) || 0 });
  }
  throw new Error('__R7HOST_WORKER_EXIT__');
};
process.on('uncaughtException', (error) => {
  if (String(error?.message || '').includes('__R7HOST_WORKER_EXIT__')) return;
  console.error(error?.stack || error);
  parentPort.postMessage({ type: 'fatal' });
});
process.on('unhandledRejection', (reason) => {
  console.error('Unhandled promise rejection:', reason?.stack || reason);
});
parentPort.unref();
parentPort.on('message', (message) => {
  if (message?.type === 'shutdown') {
    try { process.emit('SIGTERM'); } catch (_) {}
    setTimeout(() => parentPort.close(), 250).unref?.();
  }
});

(async () => {
  const scriptPath = path.resolve(workerData.scriptPath);
  try {
    parentPort.postMessage({ type: 'started', scriptPath, cwd: root });
    if (path.extname(scriptPath).toLowerCase() === '.mjs') {
      await import(pathToFileURL(scriptPath).href);
    } else {
      const botModule = new Module(scriptPath, module);
      botModule.filename = scriptPath;
      botModule.paths = Module._nodeModulePaths(path.dirname(scriptPath));
      botModule.require(scriptPath);
    }
  } catch (error) {
    if (!String(error?.message || '').includes('__R7HOST_WORKER_EXIT__')) {
      console.error(error?.stack || error);
      parentPort.postMessage({ type: 'fatal' });
    }
  }
})();
