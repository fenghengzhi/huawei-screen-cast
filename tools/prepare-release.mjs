import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';

const tag = process.argv[2];
if (!/^v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(tag || '')) throw new Error('Expected a version tag, for example v1.3.0');
const directory = 'android/app/build/outputs/apk/release';
const metadata = JSON.parse(await readFile(path.join(directory, 'output-metadata.json'), 'utf8'));
if (metadata?.artifactType?.type !== 'APK' || metadata.applicationId !== 'com.local.huaweicast'
  || metadata.variantName !== 'release') {
  throw new Error('Expected release APK metadata for com.local.huaweicast');
}
if (!Array.isArray(metadata.elements) || metadata.elements.length !== 1) {
  throw new Error('Expected one universal release APK');
}
const apk = metadata.elements[0];
if (!apk || !['SINGLE', 'UNIVERSAL'].includes(apk.type)
  || !Array.isArray(apk.filters) || apk.filters.length !== 0) {
  throw new Error('Expected one universal release APK without split filters');
}
if (!Number.isSafeInteger(apk.versionCode) || apk.versionCode <= 0) {
  throw new Error('Expected a positive integer versionCode');
}
if (`v${apk.versionName}` !== tag) throw new Error('Tag must match versionName in android/app/build.gradle');
if (typeof apk.outputFile !== 'string' || path.basename(apk.outputFile) !== apk.outputFile
  || apk.outputFile.includes('\\') || !apk.outputFile.endsWith('.apk')
  || apk.outputFile.toLowerCase().includes('unsigned')) {
  throw new Error('Expected a signed release APK filename without a directory');
}
const filename = `huawei-screen-cast-${apk.versionName}.apk`;
const apkBytes = await readFile(path.join(directory, apk.outputFile));
if (apkBytes.length === 0) throw new Error('Release APK is empty');
const fdkSourceFilename = 'fdk-aac-2.0.3-source.zip';
const fdkSource = await readFile(path.join('android/app/build/generated/fdkSource', fdkSourceFilename));
if (fdkSource.length === 0) throw new Error('FDK source archive is empty');
const notices = [];
for (const file of ['LICENSE', 'third_party/NOTICE.md', 'third_party/AirSonic-LICENSE',
  'third_party/NanoHTTPD-LICENSE', 'third_party/dd-plist-LICENSE', 'third_party/BouncyCastle-LICENSE',
  'third_party/fdk-aac/NOTICE', 'third_party/fdk-aac-PROVENANCE.md']) {
  const notice = await readFile(file, 'utf8');
  if (notice.trim().length === 0) throw new Error(`Required notice is empty: ${file}`);
  notices.push(`${file}\n\n${notice}`);
}
const assets = [
  [filename, apkBytes],
  [fdkSourceFilename, fdkSource],
  ['THIRD-PARTY-NOTICES.txt', Buffer.from(notices.join('\n\n-----\n\n'))],
];
const checksums = assets.map(([file, bytes]) => `${createHash('sha256').update(bytes).digest('hex')}  ${file}`);
assets.push(['SHA256SUMS.txt', Buffer.from(checksums.join('\n') + '\n')]);
// Validate every required input before replacing any existing release asset.
await mkdir('dist', { recursive: true });
for (const [file, bytes] of assets) {
  await writeFile(path.join('dist', file), bytes);
}
console.log(`Prepared ${filename}, FDK source, notices and checksums for ${tag}`);
