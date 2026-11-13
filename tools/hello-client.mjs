// 점검용 클라이언트: 차 앱(직접 또는 forward.mjs 경유)에 HELLO를 보내고 받은 CTRL을 한 줄씩 출력한다. 의존성 0.
// 사용: node hello-client.mjs <host> <port> token|key <값> [대기ms=3000] [mode=ping|silent|stall]
//   ping   : 폰처럼 2초마다 {"t":"ping"}을 보낸다(살아 있는 폰)
//   silent : 아무것도 보내지 않는다 → 차가 약 6초 뒤 끊어야 한다(유령 연결)
//   stall  : ping은 보내지만 환영 뒤 읽기를 멈춘다 → 느린 폰. 차는 이 폰 몫의 오래된 소리를 버리고, 쓰기가 막히면 끊는다
// 프레임 = 종류(1B) + 길이(4B BE) + 본문 (ADR-0004). 앱 쪽 FrameCodec과 같은 형식이다. ping·CAR_PCM은 줄마다 찍지 않고 끝에 합계로 낸다.
import net from 'node:net';
import { pathToFileURL } from 'node:url';

export const TYPES = { 1: 'HELLO', 2: 'CTRL', 3: 'CAR_PCM', 4: 'PHONE_PCM' };
export const PING_MS = 2000;
export const MODES = ['ping', 'silent', 'stall'];

export function encodeFrame(type, body) {
  const head = Buffer.alloc(5);
  head[0] = type;
  head.writeUInt32BE(body.length, 1);
  return Buffer.concat([head, body]);
}

export const pingFrame = () => encodeFrame(2, Buffer.from(JSON.stringify({ t: 'ping' })));

export function frameReader(onFrame, maxBody = 64 * 1024) {
  let buf = Buffer.alloc(0);
  return (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 5) {
      const len = buf.readUInt32BE(1);
      if (!TYPES[buf[0]] || len > maxBody) throw new Error(`bad frame type=${buf[0]} len=${len}`);
      if (buf.length < 5 + len) return;
      onFrame(TYPES[buf[0]], buf.subarray(5, 5 + len));
      buf = buf.subarray(5 + len);
    }
  };
}

/** CAR_PCM 순번 기록: 받은 수, 처음·마지막 순번, 빈 곳(빠진 순번 수). */
export function seqStats() {
  const s = { count: 0, first: null, last: null, missing: 0 };
  return {
    add(seq) {
      if (s.last !== null && seq > s.last + 1) s.missing += seq - s.last - 1;
      if (s.first === null) s.first = seq;
      s.last = seq;
      s.count++;
    },
    get: () => ({ ...s }),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [host, port, kind, value, waitMs = '3000', mode = 'ping'] = process.argv.slice(2);
  if (!host || !port || !['token', 'key'].includes(kind) || !value || !MODES.includes(mode)) {
    console.error('usage: node hello-client.mjs <host> <port> token|key <value> [waitMs] [ping|silent|stall]');
    process.exit(2);
  }
  const t0 = Date.now();
  const el = () => `${Date.now() - t0}ms`;
  const stats = seqStats();
  let pings = 0;
  let timer = null;
  const summary = (why) => {
    const s = stats.get();
    console.log(`summary ${why} at=${el()} mode=${mode} pcm=${s.count} firstSeq=${s.first} lastSeq=${s.last} missing=${s.missing} pingsIn=${pings}`);
  };
  const s = net.connect({ host, port: Number(port) }, () => {
    console.log(`connected ${el()}`);
    s.write(encodeFrame(1, Buffer.from(JSON.stringify({ [kind]: value }))));
  });
  s.on('data', frameReader((type, body) => {
    if (type === 'CAR_PCM') return stats.add(body.readUInt32BE(0));
    if (type !== 'CTRL') return console.log(`${type} ${body.length}B`);
    const text = body.toString('utf8');
    if (text === '{"t":"ping"}') return void pings++;
    console.log(`CTRL ${el()} ${text}`);
    if (text.startsWith('{"t":"welcome"')) {
      if (mode !== 'silent') timer = setInterval(() => s.write(pingFrame()), PING_MS);
      if (mode === 'stall') {
        s.pause();
        console.log(`stalled reading at ${el()}`);
      }
    }
  }));
  s.on('error', (e) => console.log(`error ${e.code} at ${el()}`));
  s.on('close', () => { clearInterval(timer); summary('closed by peer'); process.exit(0); });
  setTimeout(() => { clearInterval(timer); summary(`still open after ${waitMs}ms`); s.destroy(); process.exit(0); }, Number(waitMs));
}
