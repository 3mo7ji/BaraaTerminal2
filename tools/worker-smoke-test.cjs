'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Worker } = require('node:worker_threads');

async function main() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'r7host-worker-test-'));
  const botFile = path.join(root, 'smoke-bot.cjs');
  const outputFile = path.join(root, 'worker-output.txt');
  fs.writeFileSync(botFile, [
    "console.log('R7HOST_WORKER_SMOKE_OK');",
    "require('node:fs').writeFileSync('worker-output.txt', 'relative-path-ok');",
    'process.exit(0);',
  ].join('\n'));

  const worker = new Worker(path.join(__dirname, '../app/src/main/assets/r7host/runtime/bot-worker.js'), {
    workerData: { id: 'smoke-test', scriptPath: botFile, cwd: root, args: [] },
    stdout: true,
    stderr: true,
    resourceLimits: { maxOldGenerationSizeMb: 64, maxYoungGenerationSizeMb: 16 },
  });
  let stdout = '';
  let exitRequest = null;
  worker.stdout.on('data', chunk => { stdout += chunk.toString(); });
  const result = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      worker.terminate().finally(() => reject(new Error('Worker smoke test timed out.')));
    }, 5000);
    worker.on('message', message => {
      if (message?.type === 'exit-request') {
        exitRequest = message;
        worker.terminate().catch(() => {});
      }
    });
    worker.once('error', error => { clearTimeout(timeout); reject(error); });
    worker.once('exit', code => { clearTimeout(timeout); resolve(code); });
  });

  assert.equal(exitRequest?.code, 0, 'Worker must report process.exit(0).');
  assert.equal(fs.readFileSync(outputFile, 'utf8'), 'relative-path-ok');
  assert.match(stdout, /R7HOST_WORKER_SMOKE_OK/);
  console.log(`Worker smoke test passed (bot requested exit code ${exitRequest.code}; worker thread closed with ${result}).`);
  fs.rmSync(root, { recursive: true, force: true });
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
