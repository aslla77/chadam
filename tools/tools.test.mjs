import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { once } from 'node:events';
import { startForwarder } from './forward.mjs';
import { encodeFrame, frameReader, pingFrame, seqStats } from './hello-client.mjs';

const quiet = () => {};

async function echoServer() {
  const s = net.createServer((c) => c.pipe(c));
  s.listen(0, '127.0.0.1');
  await once(s, 'listening');
  return s;
}

test('forwarder relays bytes both ways and propagates close', async () => {
  const target = await echoServer();
  const fwd = await startForwarder({ listenHost: '127.0.0.1', listenPort: 0, targetPort: target.address().port, log: quiet });
  const c = net.connect({ host: '127.0.0.1', port: fwd.address().port });
  await once(c, 'connect');
  const payload = Buffer.alloc(3200, 7);
  c.write(payload);
  const got = [];
  let n = 0;
  while (n < payload.length) {
    const [chunk] = await once(c, 'data');
    got.push(chunk);
    n += chunk.length;
  }
  assert.deepEqual(Buffer.concat(got), payload);
  c.end();
  await once(c, 'close');
  fwd.close();
  target.close();
});

test('forwarder closes client when target is down', async () => {
  const dead = await echoServer();
  const port = dead.address().port;
  dead.close();
  await once(dead, 'close');
  const fwd = await startForwarder({ listenHost: '127.0.0.1', listenPort: 0, targetPort: port, log: quiet });
  const c = net.connect({ host: '127.0.0.1', port: fwd.address().port });
  c.on('error', () => {});
  await once(c, 'close');
  fwd.close();
});

test('forwarder fails fast when listen port is taken', async () => {
  const busy = await echoServer();
  await assert.rejects(startForwarder({ listenHost: '127.0.0.1', listenPort: busy.address().port, targetPort: 1, log: quiet }));
  busy.close();
});

test('frame encoding matches app format and reader handles split input', () => {
  const f = encodeFrame(1, Buffer.from('{"token":"abc"}'));
  assert.deepEqual([...f.subarray(0, 5)], [1, 0, 0, 0, 15]);
  const seen = [];
  const feed = frameReader((type, body) => seen.push([type, body.toString()]));
  const two = Buffer.concat([encodeFrame(2, Buffer.from('{"t":"welcome"}')), encodeFrame(2, Buffer.from('{"t":"off"}'))]);
  for (const b of two) feed(Buffer.from([b]));
  assert.deepEqual(seen, [['CTRL', '{"t":"welcome"}'], ['CTRL', '{"t":"off"}']]);
});

test('frame reader rejects unknown type and oversized length', () => {
  assert.throws(() => frameReader(() => {})(Buffer.from([9, 0, 0, 0, 0])));
  assert.throws(() => frameReader(() => {})(Buffer.from([3, 0, 1, 0, 1])));
});

test('ping frame is the app heartbeat CTRL', () => {
  const seen = [];
  frameReader((type, body) => seen.push([type, body.toString()]))(pingFrame());
  assert.deepEqual(seen, [['CTRL', '{"t":"ping"}']]);
});

test('seq stats count received chunks and missing sequence numbers', () => {
  const s = seqStats();
  for (const n of [5, 6, 9, 10, 14]) s.add(n);
  assert.deepEqual(s.get(), { count: 5, first: 5, last: 14, missing: 5 });
});
