import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = fileURLToPath(new URL('./prepare-release.mjs', import.meta.url));
const sourceFile = 'fdk-aac-2.0.3-source.zip';
const sourcePath = `android/app/build/generated/fdkSource/${sourceFile}`;
const noticePaths = ['LICENSE', 'third_party/NOTICE.md', 'third_party/AirSonic-LICENSE',
  'third_party/NanoHTTPD-LICENSE', 'third_party/dd-plist-LICENSE',
  'third_party/fdk-aac/NOTICE', 'third_party/fdk-aac-PROVENANCE.md'];
const apkName = 'huawei-screen-cast-1.6.0-beta.3.apk';

async function fixture(t, { omit } = {}) {
  const directory = await mkdtemp(path.join(tmpdir(), 'huaweicast-release-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const files = {
    'android/app/build/outputs/apk/release/output-metadata.json': JSON.stringify({
      elements: [{ versionName: '1.6.0-beta.3', outputFile: 'app-release.apk' }],
    }),
    'android/app/build/outputs/apk/release/app-release.apk': Buffer.from('fixture apk bytes'),
    [sourcePath]: Buffer.from('fixture source archive bytes'),
  };
  for (const notice of noticePaths) files[notice] = `Complete fixture notice: ${notice}\n`;
  for (const [file, content] of Object.entries(files)) {
    if (file === omit) continue;
    const destination = path.join(directory, file);
    await mkdir(path.dirname(destination), { recursive: true });
    await writeFile(destination, content);
  }
  return directory;
}

function prepare(directory, tag = 'v1.6.0-beta.3') {
  return spawnSync(process.execPath, [script, tag], { cwd: directory, encoding: 'utf8' });
}

test('release includes every license, standalone FDK source, and matching checksums', async t => {
  const directory = await fixture(t);
  const result = prepare(directory);
  assert.equal(result.status, 0, result.stderr);
  const output = path.join(directory, 'dist');
  assert.deepEqual((await readdir(output)).sort(),
    [apkName, sourceFile, 'THIRD-PARTY-NOTICES.txt', 'SHA256SUMS.txt'].sort());
  assert.deepEqual(await readFile(path.join(output, sourceFile)), await readFile(path.join(directory, sourcePath)));
  const notices = await readFile(path.join(output, 'THIRD-PARTY-NOTICES.txt'), 'utf8');
  for (const notice of noticePaths) {
    assert.ok(notices.includes(`${notice}\n\nComplete fixture notice: ${notice}\n`), notice);
  }
  const checksums = (await readFile(path.join(output, 'SHA256SUMS.txt'), 'utf8')).trim().split('\n');
  assert.equal(checksums.length, 3);
  for (const line of checksums) {
    const [actualHash, filename] = line.split('  ');
    const expectedHash = createHash('sha256').update(await readFile(path.join(output, filename))).digest('hex');
    assert.equal(actualHash, expectedHash);
  }
  assert.deepEqual(checksums.map(line => line.split('  ')[1]).sort(),
    [apkName, sourceFile, 'THIRD-PARTY-NOTICES.txt'].sort());
});

test('missing FDK source fails before creating release output', async t => {
  const directory = await fixture(t, { omit: sourcePath });
  const result = prepare(directory);
  assert.notEqual(result.status, 0);
  assert.ok(result.stderr.includes(sourceFile));
  await assert.rejects(readdir(path.join(directory, 'dist')), { code: 'ENOENT' });
});

test('missing required license fails without modifying existing release assets', async t => {
  const directory = await fixture(t, { omit: 'third_party/dd-plist-LICENSE' });
  await mkdir(path.join(directory, 'dist'));
  await writeFile(path.join(directory, 'dist', apkName), 'existing release bytes');
  const result = prepare(directory);
  assert.notEqual(result.status, 0);
  assert.ok(result.stderr.includes('dd-plist-LICENSE'));
  assert.deepEqual(await readdir(path.join(directory, 'dist')), [apkName]);
  assert.equal(await readFile(path.join(directory, 'dist', apkName), 'utf8'), 'existing release bytes');
});

test('version mismatch still prevents release output', async t => {
  const directory = await fixture(t);
  const result = prepare(directory, 'v1.6.0');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Tag must match versionName/);
  await assert.rejects(readdir(path.join(directory, 'dist')), { code: 'ENOENT' });
});
