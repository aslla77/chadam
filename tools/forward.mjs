// 폰(LAN) → Mac listenHost:listenPort → targetHost:targetPort(= adb forward, 127.0.0.1) → 에뮬 안 차 앱.
// 바이트만 넘긴다. 내용을 읽거나 저장하지 않는다. 의존성 0(node:net). ADR-0004 PT 1순위 경로, T-10 실측 기반.
// 사용: node forward.mjs [listenHost=0.0.0.0] [listenPort=47100] [targetPort=47001] [targetHost=127.0.0.1]
import net from 'node:net';
import { pathToFileURL } from 'node:url';

const ts = () => new Date().toISOString().slice(11, 23);

export function startForwarder({ listenHost = '0.0.0.0', listenPort, targetHost = '127.0.0.1', targetPort, log = console.log }) {
  const server = net.createServer((client) => {
    const peer = `${client.remoteAddress}:${client.remotePort}`;
    const upstream = net.connect({ host: targetHost, port: targetPort });
    let up = 0;
    let down = 0;
    let closed = false;
    client.setNoDelay(true);
    upstream.setNoDelay(true);
    log(`${ts()} accept ${peer}`);
    client.on('data', (b) => { up += b.length; });
    upstream.on('data', (b) => { down += b.length; });
    client.pipe(upstream);
    upstream.pipe(client);
    const done = (by) => () => {
      if (closed) return;
      closed = true;
      log(`${ts()} close ${peer} by=${by} up=${up} down=${down}`);
      client.destroy();
      upstream.destroy();
    };
    client.on('close', done('client'));
    upstream.on('close', done('upstream'));
    client.on('error', (e) => log(`${ts()} client error ${e.code}`));
    upstream.on('error', (e) => log(`${ts()} upstream error ${e.code}`));
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(listenPort, listenHost, () => {
      server.off('error', reject);
      log(`${ts()} listening ${listenHost}:${server.address().port} -> ${targetHost}:${targetPort}`);
      resolve(server);
    });
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [listenHost = '0.0.0.0', listenPort = '47100', targetPort = '47001', targetHost = '127.0.0.1'] = process.argv.slice(2);
  const server = await startForwarder({ listenHost, listenPort: Number(listenPort), targetHost, targetPort: Number(targetPort) });
  const stop = () => server.close(() => process.exit(0));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}
