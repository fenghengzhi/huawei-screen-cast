import { readFile, copyFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';

const tag = process.argv[2];
if (!/^v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(tag || '')) throw new Error('Expected a version tag, for example v1.3.0');
const directory = 'android/app/build/outputs/apk/release';
const metadata = JSON.parse(await readFile(path.join(directory, 'output-metadata.json'), 'utf8'));
if (metadata.elements.length !== 1) throw new Error('Expected one universal release APK');
const apk = metadata.elements[0];
if (`v${apk.versionName}` !== tag) throw new Error('Tag must match versionName in android/app/build.gradle');
if (path.basename(apk.outputFile) !== apk.outputFile || apk.outputFile.includes('unsigned')) throw new Error('Expected a signed release APK');
await mkdir('dist', { recursive: true });
const filename = `huawei-screen-cast-${apk.versionName}.apk`;
await copyFile(path.join(directory, apk.outputFile), path.join('dist', filename));
const notices = [];
for (const file of ['LICENSE', 'third_party/NOTICE.md', 'third_party/AirSonic-LICENSE', 'third_party/NanoHTTPD-LICENSE']) {
  notices.push(`${file}\n\n${await readFile(file, 'utf8')}`);
}
await writeFile('dist/THIRD-PARTY-NOTICES.txt', notices.join('\n\n-----\n\n'));
const checksums = [];
for (const file of [filename, 'THIRD-PARTY-NOTICES.txt']) {
  const hash = createHash('sha256').update(await readFile(path.join('dist', file))).digest('hex');
  checksums.push(`${hash}  ${file}`);
}
await writeFile('dist/SHA256SUMS.txt', checksums.join('\n') + '\n');
console.log(`Prepared ${filename}, notices and checksums for ${tag}`);
