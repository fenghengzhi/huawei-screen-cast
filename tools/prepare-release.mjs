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
const filename = `huawei-screen-cast-${apk.versionName}.apk`;
const fdkSourceFilename = 'fdk-aac-2.0.3-source.zip';
const fdkSource = await readFile(path.join('android/app/build/generated/fdkSource', fdkSourceFilename));
const notices = [];
for (const file of ['LICENSE', 'third_party/NOTICE.md', 'third_party/AirSonic-LICENSE',
  'third_party/NanoHTTPD-LICENSE', 'third_party/dd-plist-LICENSE',
  'third_party/fdk-aac/NOTICE', 'third_party/fdk-aac-PROVENANCE.md']) {
  notices.push(`${file}\n\n${await readFile(file, 'utf8')}`);
}
await mkdir('dist', { recursive: true });
await copyFile(path.join(directory, apk.outputFile), path.join('dist', filename));
await writeFile(path.join('dist', fdkSourceFilename), fdkSource);
await writeFile('dist/THIRD-PARTY-NOTICES.txt', notices.join('\n\n-----\n\n'));
const checksums = [];
for (const file of [filename, fdkSourceFilename, 'THIRD-PARTY-NOTICES.txt']) {
  const hash = createHash('sha256').update(await readFile(path.join('dist', file))).digest('hex');
  checksums.push(`${hash}  ${file}`);
}
await writeFile('dist/SHA256SUMS.txt', checksums.join('\n') + '\n');
console.log(`Prepared ${filename}, FDK source, notices and checksums for ${tag}`);
