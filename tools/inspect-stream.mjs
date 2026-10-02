import http from 'node:http';

const [url, seconds = '15'] = process.argv.slice(2);
if (!url) throw new Error('Usage: node tools/inspect-stream.mjs http://phone/live/.../screen.ts [seconds]');
let pending = Buffer.alloc(0), bytes = 0, packets = 0, discontinuities = 0;
let firstPts, lastPts, firstFrameAt, lastFrameAt, profile, streamType;
const nalTypes = new Set();
const ptsSteps = [], arrivalSteps = [], counters = new Map();
const started = Date.now();
function parse(packet) {
  packets++;
  if (packet[0] !== 0x47) throw new Error('TS sync lost');
  const pid = ((packet[1] & 31) << 8) | packet[2];
  const control = (packet[3] >> 4) & 3;
  if (!(control & 1)) return;
  const cc = packet[3] & 15;
  if (counters.has(pid) && cc !== (counters.get(pid) + 1) % 16) discontinuities++;
  counters.set(pid, cc);
  let offset = control & 2 ? 5 + packet[4] : 4;
  if (pid === 0x1000 && (packet[1] & 64) && offset + 17 < 188) {
    const section = offset + 1 + packet[offset];
    const programInfoLength = ((packet[section+10] & 15) << 8) | packet[section+11];
    streamType = packet[section + 12 + programInfoLength];
  }
  if (!(packet[1] & 64) || offset + 14 > 188) return;
  if (packet.readUIntBE(offset, 3) !== 1 || packet[offset + 3] !== 0xe0 || !(packet[offset + 7] & 128)) return;
  const p = packet.subarray(offset + 9, offset + 14);
  const pts = (p[0] & 14) * 536870912 + p[1] * 4194304 + (p[2] & 254) * 16384 + p[3] * 128 + (p[4] >> 1);
  const now = Date.now();
  if (firstPts === undefined) { firstPts = pts; firstFrameAt = now; }
  else { ptsSteps.push((pts - lastPts) / 90); arrivalSteps.push(now - lastFrameAt); }
  lastPts = pts; lastFrameAt = now;
  offset += 9 + packet[offset + 8];
  const sps = packet.indexOf(Buffer.from([0, 0, 0, 1, 0x67]), offset);
  if (streamType === 0x1b && sps >= 0 && sps + 5 < 188) profile = packet[sps + 5];
  for (let i=offset; i+5<188; i++) if (packet[i] === 0 && packet[i+1] === 0 && packet[i+2] === 0 && packet[i+3] === 1) nalTypes.add(streamType === 0x24 ? (packet[i+4] & 0x7e) >> 1 : packet[i+4] & 31);
}
function summary() {
  const percentile = (values, fraction) => values.length ? [...values].sort((a,b) => a-b)[Math.floor((values.length - 1) * fraction)] : null;
  console.log(JSON.stringify({ seconds: (Date.now()-started)/1000, bytes, packets, discontinuities, codec: streamType === 0x24 ? 'H.265' : streamType === 0x1b ? 'H.264' : 'unknown', nalTypes: [...nalTypes], profile, frames: ptsSteps.length + (firstPts === undefined ? 0 : 1), mediaSeconds: (lastPts-firstPts)/90000, ptsStepMs: {p50:percentile(ptsSteps,.5),p95:percentile(ptsSteps,.95),max:percentile(ptsSteps,1)}, arrivalGapMs: {p50:percentile(arrivalSteps,.5),p95:percentile(arrivalSteps,.95),max:percentile(arrivalSteps,1)} }, null, 2));
}
const request = http.get(url, response => {
  if (response.statusCode !== 200) throw new Error(`HTTP ${response.statusCode}`);
  response.on('data', chunk => {
    bytes += chunk.length; pending = Buffer.concat([pending, chunk]);
    while (pending.length >= 188) { parse(pending.subarray(0,188)); pending = pending.subarray(188); }
  });
});
request.on('error', error => { console.error(error.message); process.exitCode = 1; });
setTimeout(() => { summary(); request.destroy(); }, Number(seconds) * 1000);
