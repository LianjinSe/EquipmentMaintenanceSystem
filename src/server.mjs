import http from 'node:http';
import { readFile, writeFile, rename, mkdir, access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ACTORS, DomainError, fail, mutate, seed, summary, deviceStatus, ORDER_STATES, today } from './domain.mjs';
import { matchContract, routes, reserved } from './contracts.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const staticFiles = {
  '/': ['public/index.html', 'text/html; charset=utf-8'],
  '/app.js': ['public/app.js', 'text/javascript; charset=utf-8'],
  '/styles.css': ['public/styles.css', 'text/css; charset=utf-8'],
  '/docs/api': ['docs/demo/API.md', 'text/plain; charset=utf-8'],
  '/docs/unfinished': ['docs/demo/未完成部分与实施说明.md', 'text/plain; charset=utf-8'],
  '/docs/coverage': ['docs/demo/功能覆盖矩阵.md', 'text/plain; charset=utf-8'],
};
function send(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(value));
}
async function readBody(req) {
  if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] ?? '')) fail(415, 'UNSUPPORTED_MEDIA_TYPE', '写入请求须使用 application/json');
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 512 * 1024) fail(413, 'PAYLOAD_TOO_LARGE', '请求体超过 512 KiB');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { fail(400, 'INVALID_JSON', 'JSON 请求体无法解析'); }
}
function actorFor(req, write = false) {
  const id = req.headers['x-demo-actor'] || (write ? null : 'admin');
  if (!ACTORS.some(actor => actor.id === id)) fail(401, 'DEMO_ACTOR_REQUIRED', '请通过 X-Demo-Actor 提供有效演示角色');
  return ACTORS.find(actor => actor.id === id);
}
function validateStore(store) {
  if (store?.schemaVersion !== 1 || !store.counters || !['devices', 'orders', 'plans', 'tasks', 'messages', 'audit'].every(key => Array.isArray(store[key])) || !['order', 'task'].every(key => Number.isInteger(store.counters[key]) && store.counters[key] >= 0)) throw new Error('演示数据结构损坏或版本不受支持。原文件已保留；请检查数据或按 README 使用新数据目录。');
  for (const key of ['devices', 'orders', 'plans', 'tasks', 'messages', 'audit']) {
    if (store[key].some(item => !item || typeof item.id !== 'string') || new Set(store[key].map(item => item.id)).size !== store[key].length) throw new Error(`演示数据 ${key} 含非法或重复 ID，原文件已保留。`);
  }
  if (store.orders.some(item => !ORDER_STATES.includes(item.status) || !Array.isArray(item.history) || !Array.isArray(item.completions) || !Array.isArray(item.reviews) || !Number.isInteger(item.version)) || store.plans.some(item => !Array.isArray(item.checklist) || !Number.isInteger(item.intervalDays) || item.intervalDays < 1 || !/^\d{4}-\d{2}-\d{2}$/.test(item.nextDueDate)) || store.tasks.some(item => !['pending', 'completed'].includes(item.status) || !Array.isArray(item.checklist) || !Array.isArray(item.history))) throw new Error('演示业务数据无效，原文件已保留。');
}
export async function createApp({ dataFile = path.join(root, 'data/demo.json'), now = () => new Date() } = {}) {
  const target = path.resolve(dataFile);
  await mkdir(path.dirname(target), { recursive: true });
  async function persist(next) {
    await writeFile(`${target}.tmp`, JSON.stringify(next, null, 2), 'utf8');
    await rename(`${target}.tmp`, target);
  }
  let store;
  try { await access(target); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    store = seed(now());
    await persist(store);
  }
  if (!store) store = JSON.parse(await readFile(target, 'utf8'));
  validateStore(store);
  let queue = Promise.resolve();
  function transaction(handler) {
    const pending = queue.then(async () => {
      const next = structuredClone(store);
      const result = handler(next);
      await persist(next);
      store = next;
      return result;
    });
    queue = pending.catch(() => {});
    return pending;
  }
  function visibleDevices(state) { return state.devices.map(device => ({ ...device, derivedStatus: deviceStatus(state, device.id) })); }
  function entity(list, id, name) {
    const item = list.find(value => value.id === id);
    if (!item) fail(404, 'NOT_FOUND', `${name}不存在`);
    return item;
  }
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      const match = matchContract(req.method, url.pathname);
      if (url.pathname.startsWith('/api/')) {
        if (!match) fail(404, 'NOT_FOUND', '接口不存在；请查看 /api/capabilities');
        const { contract, id } = match;
        if (!contract.implemented) fail(501, 'NOT_IMPLEMENTED', `${contract.name}尚未实现`, { capabilityId: contract.id, method: contract.method, path: contract.path, proposal: contract.request, dependencies: contract.dependencies, acceptance: contract.acceptance, documentation: '/docs/api' });
        const write = !['GET', 'HEAD'].includes(req.method);
        const actor = actorFor(req, write);
        if (write && req.headers.origin && req.headers.origin !== `http://${req.headers.host}`) fail(403, 'ORIGIN_REJECTED', '不接受跨站写入');
        let data;
        if (write) {
          const input = await readBody(req);
          data = await transaction(next => mutate(next, contract.handler, id, input, actor.id, now().toISOString()));
        } else {
          const current = store;
          const onDate = today(now());
          const handlers = {
            health: () => ({ mode: 'demo', persistence: 'json-file', today: onDate }),
            actors: () => ACTORS,
            summary: () => summary(current, onDate),
            capabilities: () => ({ implemented: routes, reserved }),
            snapshot: () => ({ actors: ACTORS, devices: visibleDevices(current), orders: current.orders, plans: current.plans, tasks: current.tasks, messages: current.messages.filter(item => item.recipient === actor.id), audit: actor.role === 'admin' ? current.audit : [], summary: summary(current, onDate) }),
            devices: () => { const q = (url.searchParams.get('q') || '').toLowerCase(); return visibleDevices(current).filter(item => `${item.code} ${item.name} ${item.location}`.toLowerCase().includes(q)); },
            deviceHistory: () => {
              const device = entity(visibleDevices(current), id, '设备');
              const orders = current.orders.filter(item => item.deviceId === id);
              const tasks = current.tasks.filter(item => item.deviceId === id);
              const planIds = current.plans.filter(item => item.deviceId === id).map(item => item.id);
              const ids = new Set([id, ...orders.map(item => item.id), ...tasks.map(item => item.id), ...planIds]);
              return { device, orders, tasks, events: current.audit.filter(item => ids.has(item.entityId)) };
            },
            orders: () => {
              const status = url.searchParams.get('status');
              if (status && !ORDER_STATES.includes(status)) fail(400, 'VALIDATION_ERROR', '工单状态无效');
              return current.orders.filter(item => (!status || item.status === status) && (!url.searchParams.get('deviceId') || item.deviceId === url.searchParams.get('deviceId')));
            },
            order: () => entity(current.orders, id, '工单'),
            plans: () => current.plans,
            tasks: () => {
              const status = url.searchParams.get('status');
              if (status && !['pending', 'completed'].includes(status)) fail(400, 'VALIDATION_ERROR', '任务状态无效');
              return current.tasks.filter(item => !status || item.status === status);
            },
            task: () => entity(current.tasks, id, '任务'),
            messages: () => current.messages.filter(item => item.recipient === actor.id),
            audit: () => { if (actor.role !== 'admin') fail(403, 'FORBIDDEN', '只有管理员可查看事件'); return current.audit; },
          };
          data = handlers[contract.handler]();
        }
        send(res, req.method === 'POST' && ['createDevice', 'createOrder', 'createPlan'].includes(contract.handler) ? 201 : 200, { data });
        return;
      }
      const resource = staticFiles[url.pathname];
      if (!resource || !['GET', 'HEAD'].includes(req.method)) fail(404, 'NOT_FOUND', '页面不存在');
      const content = await readFile(path.join(root, resource[0]));
      res.writeHead(200, { 'Content-Type': resource[1], 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff', 'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" });
      res.end(req.method === 'HEAD' ? undefined : content);
    } catch (error) {
      if (!(error instanceof DomainError)) console.error('Request failed:', error.message);
      send(res, error instanceof DomainError ? error.status : 500, { error: { code: error instanceof DomainError ? error.code : 'INTERNAL_ERROR', message: error instanceof DomainError ? error.message : '本地保存或服务异常；操作未提交，请查看服务端日志', details: error.details ?? null } });
    }
  });
  return { server, dataFile: target };
}
if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    const port = Number(process.env.PORT || 4173);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT 必须为 1–65535 的整数');
    const app = await createApp(process.env.DATA_FILE ? { dataFile: process.env.DATA_FILE } : {});
    app.server.on('error', error => { console.error(`启动失败：${error.message}`); process.exitCode = 1; });
    app.server.listen(port, '127.0.0.1', () => {
      console.log(`设备维保 Demo：http://127.0.0.1:${port}`);
      console.log(`本地数据：${app.dataFile}（演示角色不等于真实认证）`);
    });
  } catch (error) { console.error(`启动失败：${error.message}`); process.exitCode = 1; }
}
