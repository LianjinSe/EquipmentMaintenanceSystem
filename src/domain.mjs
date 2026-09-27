import { randomUUID } from 'node:crypto';

export class DomainError extends Error {
  constructor(status, code, message, details = null) {
    super(message);
    Object.assign(this, { status, code, details });
  }
}
export const fail = (status, code, message, details) => { throw new DomainError(status, code, message, details); };
export const ORDER_STATES = ['pending_assignment', 'pending_acceptance', 'in_progress', 'pending_review', 'closed', 'cancelled'];
export const ACTORS = [
  { id: 'admin', name: '陈管理员', role: 'admin', department: '设备部' },
  { id: 'operator', name: '周操作工', role: 'operator', department: '生产一部' },
  { id: 'technician', name: '顾维修工', role: 'technician', department: '设备部' },
  { id: 'inspector', name: '林巡检员', role: 'inspector', department: '设备部' },
];
export function today(now = new Date()) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}
export function date(value, name = '日期') {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) fail(400, 'VALIDATION_ERROR', `${name}必须为 YYYY-MM-DD`);
  const parsed = new Date(`${value}T00:00:00Z`);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value || value < '2000-01-01' || value > '2100-12-31') fail(400, 'VALIDATION_ERROR', `${name}不是有效日期（2000–2100）`);
  return value;
}
export function addDays(value, days) {
  const parsed = new Date(`${value}T00:00:00Z`);
  parsed.setUTCDate(parsed.getUTCDate() + days);
  return parsed.toISOString().slice(0, 10);
}
function text(value, name, max = 500, optional = false) {
  if (optional && (value === undefined || value === '')) return '';
  if (typeof value !== 'string' || !value.trim() || value.trim().length > max) fail(400, 'VALIDATION_ERROR', `${name}须为 1–${max} 字符`);
  return value.trim();
}
function integer(value, name, min, max) {
  if (!Number.isInteger(value) || value < min || value > max) fail(400, 'VALIDATION_ERROR', `${name}须为 ${min}–${max} 的整数`);
  return value;
}
function oneOf(value, options, name) {
  if (!options.includes(value)) fail(400, 'VALIDATION_ERROR', `${name}须为 ${options.join(' / ')}`);
  return value;
}
function object(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(400, 'VALIDATION_ERROR', '请求体必须为 JSON 对象');
}
function fields(value, names) {
  object(value);
  const unknown = Object.keys(value).filter(key => !names.includes(key));
  if (unknown.length) fail(400, 'VALIDATION_ERROR', `不支持的字段：${unknown.join('、')}`);
}
function find(list, id, name) {
  const item = list.find(value => value.id === id);
  if (!item) fail(404, 'NOT_FOUND', `${name}不存在`);
  return item;
}
function version(item, expected) {
  integer(expected, 'version', 1, Number.MAX_SAFE_INTEGER);
  if (item.version !== expected) fail(409, 'VERSION_CONFLICT', '记录已更新，请刷新后重试', { currentVersion: item.version });
}
function admin(actor) { if (actor.role !== 'admin') fail(403, 'FORBIDDEN', '当前演示角色没有管理权限'); }
function executor(actor, assignedTo) {
  if (actor.role !== 'admin' && actor.id !== assignedTo) fail(403, 'FORBIDDEN', '只有指派的执行人或管理员可执行');
}
function state(item, allowed) {
  if (!allowed.includes(item.status)) fail(409, 'INVALID_STATE', '当前状态不允许该操作', { status: item.status, allowed });
}
function record(store, actor, entityType, entity, action, detail, at, notify = []) {
  const event = { id: randomUUID(), actorId: actor.id, actorName: actor.name, entityType, entityId: entity.id, action, detail, at };
  store.audit.unshift(event);
  if (entity.history) entity.history.push(event);
  for (const recipient of new Set(notify)) {
    store.messages.unshift({ id: randomUUID(), recipient, title: detail, entityType, entityId: entity.id, createdAt: at, read: false });
  }
}
function newOrder(store, input, actor, at, sourceTaskId = null) {
  const order = {
    id: randomUUID(), number: `WO-${String(++store.counters.order).padStart(4, '0')}`,
    deviceId: input.deviceId, title: input.title, description: input.description,
    priority: input.priority, reporterId: actor.id, assigneeId: null,
    status: 'pending_assignment', sourceTaskId, createdAt: at, updatedAt: at,
    closedAt: null, version: 1, completions: [], reviews: [], history: [],
  };
  store.orders.unshift(order);
  record(store, actor, 'order', order, 'created', `${order.number}：${order.title}`, at, ['admin']);
  return order;
}

// All mutations operate on a cloned store. The HTTP layer persists first, then publishes
// the clone, so validation or disk-write failure never exposes a partial business change.
export function mutate(store, action, id, input, actorId, at = new Date().toISOString()) {
  const actor = find(ACTORS, actorId, '演示角色');
  const currentDate = today(new Date(at));
  let result;
  if (action === 'createDevice') {
    admin(actor);
    fields(input, ['code', 'name', 'location', 'category']);
    const code = text(input.code, '设备编码', 40);
    if (store.devices.some(device => device.code.toLowerCase() === code.toLowerCase())) fail(409, 'DUPLICATE_CODE', '设备编码已存在');
    result = { id: randomUUID(), code, name: text(input.name, '设备名称', 80), location: text(input.location, '位置', 80), category: text(input.category, '分类', 40), status: 'active', version: 1, createdAt: at };
    store.devices.unshift(result);
    record(store, actor, 'device', result, 'created', `建立设备 ${result.code}`, at);
  } else if (action === 'createOrder') {
    fields(input, ['deviceId', 'title', 'description', 'priority']);
    find(store.devices, input.deviceId, '设备');
    result = newOrder(store, { deviceId: input.deviceId, title: text(input.title, '故障标题', 100), description: text(input.description, '故障描述', 1000), priority: oneOf(input.priority, ['normal', 'urgent'], '优先级') }, actor, at);
  } else if (['assignOrder', 'acceptOrder', 'rejectOrder', 'completeOrder', 'reviewOrder', 'cancelOrder'].includes(action)) {
    result = find(store.orders, id, '工单');
    const allowedFields = {
      assignOrder: ['version', 'assigneeId'], acceptOrder: ['version'],
      rejectOrder: ['version', 'reason'], completeOrder: ['version', 'summary', 'workMinutes', 'materials', 'safetyConfirmed'],
      reviewOrder: ['version', 'decision', 'note'], cancelOrder: ['version', 'reason'],
    };
    fields(input, allowedFields[action]);
    version(result, input.version);
    if (action === 'assignOrder') {
      admin(actor);
      state(result, ['pending_assignment', 'pending_acceptance']);
      const assignee = find(ACTORS, input.assigneeId, '维修人员');
      if (assignee.role !== 'technician') fail(400, 'VALIDATION_ERROR', '工单必须指派给维修人员');
      result.assigneeId = assignee.id;
      result.status = 'pending_acceptance';
      record(store, actor, 'order', result, 'assigned', `${result.number} 已指派给 ${assignee.name}`, at, [assignee.id]);
    } else if (action === 'acceptOrder') {
      executor(actor, result.assigneeId);
      state(result, ['pending_acceptance']);
      result.status = 'in_progress';
      record(store, actor, 'order', result, 'accepted', `${result.number} 已接单，开始维修`, at, [result.reporterId]);
    } else if (action === 'rejectOrder') {
      executor(actor, result.assigneeId);
      state(result, ['pending_acceptance']);
      const reason = text(input.reason, '拒单原因');
      result.status = 'pending_assignment';
      result.assigneeId = null;
      record(store, actor, 'order', result, 'rejected', `${result.number} 拒单：${reason}`, at, ['admin']);
    } else if (action === 'completeOrder') {
      executor(actor, result.assigneeId);
      state(result, ['in_progress']);
      if (input.safetyConfirmed !== true) fail(400, 'SAFETY_CONFIRMATION_REQUIRED', '请确认已完成演示中的现场安全检查');
      const completion = { summary: text(input.summary, '维修结果', 1000), workMinutes: integer(input.workMinutes, '工时分钟', 1, 10080), materials: text(input.materials, '耗材记录', 500, true), actorId: actor.id, at };
      result.completions.push(completion);
      result.status = 'pending_review';
      record(store, actor, 'order', result, 'completed', `${result.number} 已提交完工，等待验收`, at, ['admin', result.reporterId]);
    } else if (action === 'reviewOrder') {
      if (actor.role !== 'admin' && actor.id !== result.reporterId) fail(403, 'FORBIDDEN', '只有报修人或管理员可验收');
      state(result, ['pending_review']);
      const decision = oneOf(input.decision, ['accept', 'return'], '验收结果');
      const note = text(input.note, '验收说明', 500);
      result.reviews.push({ decision, note, actorId: actor.id, at });
      result.status = decision === 'accept' ? 'closed' : 'in_progress';
      if (decision === 'accept') result.closedAt = at;
      record(store, actor, 'order', result, 'reviewed', `${result.number} ${decision === 'accept' ? '验收通过' : '退回维修'}：${note}`, at, [result.assigneeId, result.reporterId]);
    } else {
      if (actor.role !== 'admin' && actor.id !== result.reporterId) fail(403, 'FORBIDDEN', '只有报修人或管理员可取消');
      state(result, actor.role === 'admin' ? ['pending_assignment', 'pending_acceptance', 'in_progress'] : ['pending_assignment']);
      const reason = text(input.reason, '取消原因');
      result.status = 'cancelled';
      record(store, actor, 'order', result, 'cancelled', `${result.number} 已取消：${reason}`, at, [result.reporterId, ...(result.assigneeId ? [result.assigneeId] : [])]);
    }
    result.version += 1;
    result.updatedAt = at;
  } else if (action === 'createPlan') {
    admin(actor);
    fields(input, ['deviceId', 'name', 'type', 'intervalDays', 'nextDueDate', 'assigneeId', 'checklist']);
    find(store.devices, input.deviceId, '设备');
    const assignee = find(ACTORS, input.assigneeId, '执行人员');
    if (!['technician', 'inspector'].includes(assignee.role)) fail(400, 'VALIDATION_ERROR', '计划必须指派给维修工或巡检员');
    if (!Array.isArray(input.checklist) || input.checklist.length < 1 || input.checklist.length > 20) fail(400, 'VALIDATION_ERROR', '检查项须为 1–20 项');
    const checklist = input.checklist.map(item => text(item, '检查项', 100));
    if (new Set(checklist).size !== checklist.length) fail(400, 'VALIDATION_ERROR', '检查项名称不能重复');
    result = { id: randomUUID(), deviceId: input.deviceId, name: text(input.name, '计划名称', 100), type: oneOf(input.type, ['maintenance', 'inspection'], '计划类型'), intervalDays: integer(input.intervalDays, '周期天数', 1, 3650), nextDueDate: date(input.nextDueDate, '首次到期日'), assigneeId: assignee.id, checklist, active: true, version: 1, createdAt: at };
    store.plans.unshift(result);
    record(store, actor, 'plan', result, 'created', `建立计划 ${result.name}`, at);
  } else if (action === 'togglePlan') {
    admin(actor);
    fields(input, ['version', 'active']);
    result = find(store.plans, id, '计划');
    version(result, input.version);
    if (typeof input.active !== 'boolean') fail(400, 'VALIDATION_ERROR', 'active 必须为布尔值');
    result.active = input.active;
    result.version += 1;
    record(store, actor, 'plan', result, 'updated', `${result.name} 已${result.active ? '启用' : '暂停'}`, at);
  } else if (action === 'generateTasks') {
    admin(actor);
    fields(input, ['throughDate']);
    const through = date(input.throughDate, '生成截止日期');
    if (through > currentDate) fail(400, 'VALIDATION_ERROR', '演示仅生成截至今天的到期任务');
    const pending = [];
    const nextDates = [];
    for (const plan of store.plans.filter(item => item.active)) {
      let due = plan.nextDueDate;
      while (due <= through) {
        if (!store.tasks.some(task => task.planId === plan.id && task.scheduledDate === due)) pending.push({ plan, due });
        if (pending.length > 100) fail(400, 'GENERATION_LIMIT', '单次生成最多 100 项，请缩小日期范围');
        due = addDays(due, plan.intervalDays);
      }
      nextDates.push([plan, due]);
    }
    const generated = pending.map(({ plan, due }) => {
      const taskId = randomUUID();
      const task = { id: taskId, number: `PM-${String(++store.counters.task).padStart(4, '0')}`, planId: plan.id, deviceId: plan.deviceId, name: plan.name, type: plan.type, scheduledDate: due, assigneeId: plan.assigneeId, checklist: plan.checklist.map((label, index) => ({ id: `${taskId}-${index}`, label })), results: [], status: 'pending', version: 1, createdAt: at, completedAt: null, linkedOrderId: null, note: '', history: [] };
      store.tasks.unshift(task);
      record(store, actor, 'task', task, 'generated', `${task.number}：${task.name} 到期 ${due}`, at, [task.assigneeId]);
      return task;
    });
    for (const [plan, nextDueDate] of nextDates) if (plan.nextDueDate !== nextDueDate) {
      plan.nextDueDate = nextDueDate;
      plan.version += 1;
      record(store, actor, 'plan', plan, 'advanced', `${plan.name} 下次到期 ${nextDueDate}`, at);
    }
    result = { generated, count: generated.length, throughDate: through };
  } else if (action === 'completeTask') {
    fields(input, ['version', 'results', 'note']);
    result = find(store.tasks, id, '维保任务');
    executor(actor, result.assigneeId);
    version(result, input.version);
    state(result, ['pending']);
    if (!Array.isArray(input.results) || input.results.length !== result.checklist.length) fail(400, 'VALIDATION_ERROR', '须提交全部检查项');
    const uniqueIds = new Set(input.results.map(item => item?.itemId));
    if (uniqueIds.size !== result.checklist.length) fail(400, 'VALIDATION_ERROR', '检查项不能重复');
    const results = result.checklist.map(item => {
      const answer = input.results.find(value => value?.itemId === item.id);
      if (!answer) fail(400, 'VALIDATION_ERROR', `缺少检查项：${item.label}`);
      fields(answer, ['itemId', 'verdict', 'reading', 'remark']);
      const verdict = oneOf(answer.verdict, ['normal', 'abnormal'], '检查结果');
      return { itemId: item.id, label: item.label, verdict, reading: text(answer.reading, '读数', 100, true), remark: text(answer.remark, '说明', 500, verdict === 'normal') };
    });
    const abnormalities = results.filter(item => item.verdict === 'abnormal');
    result.note = text(input.note, '作业备注', 1000, true);
    if (abnormalities.length) {
      const order = newOrder(store, { deviceId: result.deviceId, title: `${result.name}发现异常`.slice(0, 100), description: abnormalities.map(item => `${item.label}：${item.remark}${item.reading ? `（读数 ${item.reading}）` : ''}`).join('\n'), priority: 'normal' }, actor, at, result.id);
      result.linkedOrderId = order.id;
    }
    result.results = results;
    result.status = 'completed';
    result.completedAt = at;
    result.version += 1;
    record(store, actor, 'task', result, 'completed', `${result.number} 已完成${abnormalities.length ? '，异常已关联维修工单' : ''}`, at, ['admin']);
  } else if (action === 'readMessage') {
    fields(input, []);
    result = find(store.messages, id, '消息');
    if (result.recipient !== actor.id) fail(403, 'FORBIDDEN', '只能修改自己的消息');
    result.read = true;
  } else fail(404, 'NOT_FOUND', '未知操作');
  return result;
}

export function deviceStatus(store, deviceId) {
  const orders = store.orders.filter(order => order.deviceId === deviceId && !['closed', 'cancelled'].includes(order.status));
  if (orders.some(order => ['in_progress', 'pending_review'].includes(order.status))) return 'repairing';
  return orders.length ? 'reported' : 'active';
}
export function summary(store, onDate = today()) {
  return {
    devices: store.devices.length,
    openOrders: store.orders.filter(item => !['closed', 'cancelled'].includes(item.status)).length,
    pendingReview: store.orders.filter(item => item.status === 'pending_review').length,
    pendingTasks: store.tasks.filter(item => item.status === 'pending').length,
    overdueTasks: store.tasks.filter(item => item.status === 'pending' && item.scheduledDate < onDate).length,
    closedOrders: store.orders.filter(item => item.status === 'closed').length,
    today: onDate,
  };
}
export function seed(now = new Date()) {
  const onDate = today(now);
  const at = now.toISOString();
  const store = { schemaVersion: 1, counters: { order: 0, task: 0 }, devices: [], orders: [], plans: [], tasks: [], messages: [], audit: [] };
  const examples = [
    ['EQ-001', '数控加工中心', '一号车间 · 机加工线', '加工设备'],
    ['EQ-002', '空压机', '动力站 · A 区', '动力设备'],
    ['EQ-003', '输送机', '二号车间 · 装配线', '输送设备'],
  ];
  for (const [code, name, location, category] of examples) mutate(store, 'createDevice', null, { code, name, location, category }, 'admin', at);
  const deviceId = store.devices.find(device => device.code === 'EQ-001').id;
  mutate(store, 'createOrder', null, { deviceId, title: '主轴运行时出现异响', description: '演示故障：加工过程中主轴存在间歇性异响，请检查。', priority: 'urgent' }, 'operator', at);
  mutate(store, 'createPlan', null, { deviceId, name: '加工中心每日点检', type: 'inspection', intervalDays: 1, nextDueDate: addDays(onDate, -1), assigneeId: 'inspector', checklist: ['润滑油位', '主轴声音', '防护门状态'] }, 'admin', at);
  const compressor = store.devices.find(device => device.code === 'EQ-002').id;
  mutate(store, 'createPlan', null, { deviceId: compressor, name: '空压机周期保养', type: 'maintenance', intervalDays: 7, nextDueDate: onDate, assigneeId: 'technician', checklist: ['滤芯检查', '连接件检查'] }, 'admin', at);
  mutate(store, 'generateTasks', null, { throughDate: onDate }, 'admin', at);
  return store;
}
