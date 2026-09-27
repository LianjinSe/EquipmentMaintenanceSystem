import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, readFile, mkdir, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createApp } from '../src/server.mjs';
import { routes, reserved } from '../src/contracts.mjs';

const fixedNow = () => new Date('2026-09-27T02:00:00.000Z');
async function launch(dataFile) {
  const app = await createApp({ dataFile, now: fixedNow });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const request = async (route, method = 'GET', input, actor = 'admin') => {
    const response = await fetch(base + route, { method, headers: { ...(actor ? { 'X-Demo-Actor': actor } : {}), ...(input !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(input !== undefined ? { body: JSON.stringify(input) } : {}) });
    return { status: response.status, body: await response.json() };
  };
  return { ...app, base, request, close: () => new Promise(resolve => { app.server.close(resolve); app.server.closeAllConnections(); }) };
}
async function setup(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ems-demo-test-'));
  const dataFile = path.join(directory, 'demo.json');
  const app = await launch(dataFile);
  t.after(async () => {
    await app.close();
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    assert.ok(path.basename(directory).startsWith('ems-demo-test-'));
    await rm(directory, { recursive: true, force: true });
  });
  return app;
}
const data = result => { assert.ok(result.status < 300, JSON.stringify(result.body)); return result.body.data; };

test('repair happy path, return/rework history and terminal-state guard', async t => {
  const app = await setup(t);
  const state = data(await app.request('/api/state'));
  const order = data(await app.request('/api/orders', 'POST', { deviceId: state.devices[0].id, title: '测试故障', description: '记录故障现象', priority: 'normal' }, 'operator'));
  let current = data(await app.request(`/api/orders/${order.id}/assign`, 'POST', { version: order.version, assigneeId: 'technician' }));
  const beforeAccept = await app.request(`/api/orders/${order.id}/complete`, 'POST', { version: current.version, summary: '不能跳过接单', workMinutes: 10, safetyConfirmed: true }, 'technician');
  assert.equal(beforeAccept.status, 409);
  current = data(await app.request(`/api/orders/${order.id}/accept`, 'POST', { version: current.version }, 'technician'));
  current = data(await app.request(`/api/orders/${order.id}/complete`, 'POST', { version: current.version, summary: '首次完工', workMinutes: 10, materials: '测试耗材', safetyConfirmed: true }, 'technician'));
  current = data(await app.request(`/api/orders/${order.id}/review`, 'POST', { version: current.version, decision: 'return', note: '仍有异常，请复查' }, 'operator'));
  assert.equal(current.status, 'in_progress');
  current = data(await app.request(`/api/orders/${order.id}/complete`, 'POST', { version: current.version, summary: '复查完成', workMinutes: 20, safetyConfirmed: true }, 'technician'));
  current = data(await app.request(`/api/orders/${order.id}/review`, 'POST', { version: current.version, decision: 'accept', note: '确认修复' }, 'operator'));
  assert.equal(current.status, 'closed');
  assert.equal(current.completions.length, 2);
  assert.equal(current.reviews.length, 2);
  assert.ok(current.closedAt);
  assert.equal((await app.request(`/api/orders/${order.id}/cancel`, 'POST', { version: current.version, reason: '不能取消已关闭单' })).status, 409);
});

test('role/action restrictions, reject/reassign and version conflicts', async t => {
  const app = await setup(t);
  const order = data(await app.request('/api/orders'))[0];
  assert.equal((await app.request(`/api/orders/${order.id}/assign`, 'POST', { version: order.version, assigneeId: 'technician' }, 'operator')).status, 403);
  let current = data(await app.request(`/api/orders/${order.id}/assign`, 'POST', { version: order.version, assigneeId: 'technician' }));
  assert.equal((await app.request(`/api/orders/${order.id}/accept`, 'POST', { version: current.version }, 'inspector')).status, 403);
  current = data(await app.request(`/api/orders/${order.id}/reject`, 'POST', { version: current.version, reason: '重新安排' }, 'technician'));
  assert.equal(current.assigneeId, null);
  const conflicts = await Promise.all([
    app.request(`/api/orders/${order.id}/assign`, 'POST', { version: current.version, assigneeId: 'technician' }),
    app.request(`/api/orders/${order.id}/assign`, 'POST', { version: current.version, assigneeId: 'technician' }),
  ]);
  assert.deepEqual(conflicts.map(result => result.status).sort(), [200, 409]);
  assert.equal(conflicts.find(result => result.status === 409).body.error.code, 'VERSION_CONFLICT');
});

test('plan generation is repeatable, paused plans retain old tasks, invalid inputs leave no changes', async t => {
  const app = await setup(t);
  const before = data(await app.request('/api/state'));
  assert.equal(data(await app.request('/api/plans/generate', 'POST', { throughDate: '2026-09-27' })).count, 0);
  assert.equal((await app.request('/api/plans', 'POST', { deviceId: before.devices[0].id, name: '非法日期', type: 'inspection', intervalDays: 1, nextDueDate: '2026-02-30', assigneeId: 'inspector', checklist: ['油位'] })).status, 400);
  let plan = data(await app.request('/api/plans', 'POST', { deviceId: before.devices[0].id, name: '额外计划', type: 'inspection', intervalDays: 2, nextDueDate: '2026-09-25', assigneeId: 'inspector', checklist: ['油位'] }));
  const generated = data(await app.request('/api/plans/generate', 'POST', { throughDate: '2026-09-27' }));
  assert.equal(generated.count, 2);
  assert.deepEqual(generated.generated.map(task => task.scheduledDate), ['2026-09-25', '2026-09-27']);
  assert.equal(data(await app.request('/api/plans/generate', 'POST', { throughDate: '2026-09-27' })).count, 0);
  plan = data(await app.request('/api/plans')).find(item => item.id === plan.id);
  data(await app.request(`/api/plans/${plan.id}`, 'PATCH', { version: plan.version, active: false }));
  assert.equal(data(await app.request('/api/tasks')).filter(item => item.planId === plan.id).length, 2);
  assert.equal((await app.request('/api/plans/generate', 'POST', { throughDate: '2026-09-28' })).status, 400);
  const excessive = data(await app.request('/api/plans', 'POST', { deviceId: before.devices[0].id, name: '超限回补', type: 'inspection', intervalDays: 1, nextDueDate: '2026-01-01', assigneeId: 'inspector', checklist: ['项目'] }));
  const tasksBefore = data(await app.request('/api/tasks')).length;
  assert.equal((await app.request('/api/plans/generate', 'POST', { throughDate: '2026-09-27' })).body.error.code, 'GENERATION_LIMIT');
  assert.equal(data(await app.request('/api/tasks')).length, tasksBefore);
  assert.equal(data(await app.request('/api/plans')).find(item => item.id === excessive.id).nextDueDate, '2026-01-01');
});

test('checklist snapshot, invalid abnormality rollback and atomic single linked order', async t => {
  const app = await setup(t);
  const state = data(await app.request('/api/state'));
  const task = state.tasks.find(item => item.type === 'inspection');
  const results = task.checklist.map((item, index) => ({ itemId: item.id, verdict: index === 0 ? 'abnormal' : 'normal', reading: '', remark: '' }));
  assert.equal((await app.request(`/api/tasks/${task.id}/complete`, 'POST', { version: task.version, results, note: '' }, 'inspector')).status, 400);
  assert.equal(data(await app.request('/api/orders')).length, state.orders.length);
  assert.equal(data(await app.request(`/api/tasks/${task.id}`)).status, 'pending');
  results[0].remark = '油位异常';
  const completed = data(await app.request(`/api/tasks/${task.id}/complete`, 'POST', { version: task.version, results, note: '测试现场记录' }, 'inspector'));
  assert.equal(completed.status, 'completed');
  assert.ok(completed.linkedOrderId);
  const linked = data(await app.request(`/api/orders/${completed.linkedOrderId}`));
  assert.equal(linked.sourceTaskId, task.id);
  assert.match(linked.description, /油位异常/);
  assert.equal((await app.request(`/api/tasks/${task.id}/complete`, 'POST', { version: task.version, results, note: '' }, 'inspector')).status, 409);
  assert.equal(data(await app.request('/api/orders')).length, state.orders.length + 1);
});

test('data survives restart and failed persistence does not publish a mutation', async t => {
  const app = await setup(t);
  const before = data(await app.request('/api/devices')).length;
  const added = data(await app.request('/api/devices', 'POST', { code: 'NEW-001', name: '测试设备', location: '测试位置', category: '测试' }));
  const reopened = await launch(app.dataFile);
  try {
    assert.ok(data(await reopened.request('/api/devices')).some(item => item.id === added.id));
  } finally { await reopened.close(); }
  await mkdir(`${app.dataFile}.tmp`);
  const failed = await app.request('/api/devices', 'POST', { code: 'FAILED', name: '不应保存', location: '测试位置', category: '测试' });
  assert.equal(failed.status, 500);
  assert.equal(data(await app.request('/api/devices')).length, before + 1);
  assert.ok(!JSON.parse(await readFile(app.dataFile, 'utf8')).devices.some(item => item.code === 'FAILED'));
});

test('all reserved endpoints explicitly return 501, unknown route returns 404', async t => {
  const app = await setup(t);
  const capabilities = data(await app.request('/api/capabilities'));
  assert.equal(capabilities.implemented.length, routes.length);
  assert.equal(capabilities.reserved.length, reserved.length);
  for (const contract of reserved) {
    const response = await app.request(contract.path.replace('{id}', 'demo'), contract.method, contract.method === 'GET' ? undefined : contract.request);
    assert.equal(response.status, 501, contract.id);
    assert.equal(response.body.error.code, 'NOT_IMPLEMENTED');
    assert.equal(response.body.error.details.capabilityId, contract.id);
  }
  assert.equal((await app.request('/api/unknown')).status, 404);
});

test('message ownership, validation, cross-site writes and static file boundaries', async t => {
  const app = await setup(t);
  const message = data(await app.request('/api/messages'))[0];
  assert.equal((await app.request(`/api/messages/${message.id}/read`, 'PATCH', {}, 'operator')).status, 403);
  assert.equal(data(await app.request(`/api/messages/${message.id}/read`, 'PATCH', {})).read, true);
  assert.equal((await app.request('/api/audit', 'GET', undefined, 'operator')).status, 403);
  assert.equal((await app.request('/api/devices', 'POST', { code: 'EMPTY' }, null)).status, 401);
  assert.equal((await app.request('/api/devices', 'POST', { code: 'EMPTY', name: 'x', location: 'x', category: 'x', status: 'active' })).status, 400);
  const crossSite = await fetch(app.base + '/api/orders', { method: 'POST', headers: { Origin: 'https://unrelated.example', 'X-Demo-Actor': 'admin', 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal(crossSite.status, 403);
  const invalidJSON = await fetch(app.base + '/api/orders', { method: 'POST', headers: { 'X-Demo-Actor': 'admin', 'Content-Type': 'application/json' }, body: '{' });
  assert.equal(invalidJSON.status, 400);
  assert.equal((await fetch(app.base + '/data/demo.json')).status, 404);
  assert.equal((await fetch(app.base + '/.git/config')).status, 404);
  const index = await fetch(app.base + '/');
  assert.equal(index.status, 200);
  assert.match(index.headers.get('content-security-policy'), /default-src 'self'/);
});

test('corrupt existing data is preserved and startup fails instead of silently reseeding', async t => {
  const app = await setup(t);
  await writeFile(app.dataFile, '{invalid', 'utf8');
  await assert.rejects(createApp({ dataFile: app.dataFile }), SyntaxError);
  assert.equal(await readFile(app.dataFile, 'utf8'), '{invalid');
});
