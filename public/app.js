import { renderView, renderOrderRows, renderTaskRows, renderDeviceCards } from './quiet-view.js';

const $ = selector => document.querySelector(selector);
const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const e = escapeHTML;
const stateLabels = { pending_assignment: '待派单', pending_acceptance: '待接单', in_progress: '维修中', pending_review: '待验收', closed: '已关闭', cancelled: '已取消', pending: '待执行', completed: '已完成', active: '正常', reported: '已报修', repairing: '维修中', urgent: '紧急', normal: '普通', reserved: '预留' };
const views = { dashboard: ['工作概览', 'WORKSPACE OVERVIEW'], devices: ['设备台账', 'EQUIPMENT REGISTER'], orders: ['维修工单', 'REPAIR WORK ORDERS'], tasks: ['保养与点检', 'PREVENTIVE MAINTENANCE'], plans: ['周期计划', 'RECURRING PLANS'], capabilities: ['接口与进展', 'CAPABILITIES & CONTRACTS'] };
let snapshot;
let capabilities;
let actorId = localStorage.getItem('demo-actor') || 'admin';
if (!['admin', 'operator', 'technician', 'inspector'].includes(actorId)) actorId = 'admin';
let currentView = 'dashboard';
let deviceSearch = '';
let orderSearch = '';
let orderFilter = '';
let taskFilter = 'pending';
let toastTimer;
function badge(status) { return `<span class="badge ${e(status)}">${e(stateLabels[status] || status)}</span>`; }
function device(id) { return snapshot.devices.find(item => item.id === id); }
function person(id) { return snapshot.actors.find(item => item.id === id)?.name || '未指派'; }
function actor() { return snapshot.actors.find(item => item.id === actorId); }
function admin() { return actor()?.role === 'admin'; }
function when(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function toast(message, error = false) {
  clearTimeout(toastTimer);
  $('#toast').textContent = message;
  $('#toast').classList.toggle('error', error);
  $('#toast').hidden = false;
  toastTimer = setTimeout(() => { $('#toast').hidden = true; }, error ? 7000 : 3500);
}
async function api(path, method = 'GET', data) {
  const url = new URL(path.replace(/^\//, ''), document.baseURI);
  const response = await fetch(url, { method, headers: { 'X-Demo-Actor': actorId, ...(data !== undefined ? { 'Content-Type': 'application/json' } : {}) }, ...(data !== undefined ? { body: JSON.stringify(data) } : {}) });
  const body = await response.json();
  if (!response.ok) {
    const error = new Error(body.error?.message || `请求失败 ${response.status}`);
    error.code = body.error?.code;
    throw error;
  }
  return body.data;
}
async function load() {
  snapshot = await api('/api/state');
  $('#actor').innerHTML = snapshot.actors.map(item => `<option value="${e(item.id)}" ${item.id === actorId ? 'selected' : ''}>${e(item.name)}</option>`).join('');
  $('#unread-count').textContent = snapshot.messages.filter(item => !item.read).length;
  $('#content').setAttribute('aria-busy', 'false');
}
function button(label, action, id = '', primary = false) {
  return `<button class="button ${primary ? 'primary' : ''}" type="button" data-action="${action}" data-id="${e(id)}">${label}</button>`;
}
function ordersTable(orders) { return renderOrderRows(snapshot, orders, e); }
function taskLines(tasks) { return renderTaskRows(snapshot, tasks, e); }
function render() {
  if (!snapshot) return;
  for (const link of document.querySelectorAll('[data-view]')) {
    link.classList.toggle('active', link.dataset.view === currentView);
    if (link.dataset.view === currentView) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
  $('#content').innerHTML = renderView({
    snapshot, capabilities, currentView, admin: admin(),
    orderFilter, taskFilter, deviceSearch, orderSearch, e, when,
  });
}
function deviceTable() { return renderDeviceCards(snapshot, deviceSearch, e); }
function openDialog(title, html) {
  $('#dialog-title').textContent = title;
  $('#dialog-body').innerHTML = html;
  if (!$('#dialog').open) $('#dialog').showModal();
}
function field(label, name, input = '', type = 'text', extra = '') { return `<label class="field">${label}<input name="${name}" type="${type}" value="${e(input)}" required ${extra}></label>`; }
function options(list, chosen = '') { return list.map(([value, label]) => `<option value="${e(value)}" ${value === chosen ? 'selected' : ''}>${e(label)}</option>`).join(''); }
function deviceOptions(chosen) { return options(snapshot.devices.map(item => [item.id, `${item.code} · ${item.name}`]), chosen); }
function formFooter(label) { return `<div class="form-error" role="alert" hidden></div><div class="form-actions"><button class="button" type="button" data-action="close">取消</button><button class="button primary" type="submit">${label}</button></div>`; }
function bindForm(handler) {
  const form = $('#dialog-body form');
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const submit = form.querySelector('[type="submit"]');
    const errorBox = form.querySelector('.form-error');
    submit.disabled = true;
    errorBox.hidden = true;
    try {
      const result = await handler(new FormData(form));
      await load();
      render();
      $('#dialog').close();
      toast(result || '已保存');
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.hidden = false;
      if (error.code === 'VERSION_CONFLICT') { await load(); render(); errorBox.textContent += '。关闭弹窗后重新打开记录。'; }
    } finally { submit.disabled = false; }
  });
}
function newOrderForm(deviceId = '') {
  openDialog(deviceId ? '模拟扫码 · 发起报修' : '发起报修', `<p class="dialog-description">${deviceId ? '此入口模拟已识别设备的扫码结果；尚未调用摄像头或生成真实二维码。' : '选择设备并记录故障，提交后由管理员派单。'}</p><form><label class="field">设备<select name="deviceId" required>${deviceOptions(deviceId)}</select></label>${field('故障标题', 'title', '', 'text', 'maxlength="100"')}<label class="field">故障描述<textarea name="description" required maxlength="1000" placeholder="记录故障现象和发生位置"></textarea></label><label class="field">优先级<select name="priority">${options([['normal', '普通'], ['urgent', '紧急']])}</select></label>${formFooter('提交报修')}</form>`);
  bindForm(async form => { const order = await api('/api/orders', 'POST', Object.fromEntries(form)); return `${order.number} 已提交，等待派单`; });
}
function orderDetail(id) {
  const order = snapshot.orders.find(item => item.id === id);
  const canExecute = admin() || order.assigneeId === actorId;
  const canReview = admin() || order.reporterId === actorId;
  let actions = '';
  if (admin() && ['pending_assignment', 'pending_acceptance'].includes(order.status)) actions += button('派给顾维修工', 'assign', id, true);
  if (canExecute && order.status === 'pending_acceptance') actions += button('接单并开始维修', 'accept', id, true) + button('拒单', 'reject', id);
  if (canExecute && order.status === 'in_progress') actions += button('填写完工记录', 'complete-order', id, true);
  if (canReview && order.status === 'pending_review') actions += button('验收通过', 'review-accept', id, true) + button('退回维修', 'review-return', id);
  if ((admin() && ['pending_assignment', 'pending_acceptance', 'in_progress'].includes(order.status)) || (order.reporterId === actorId && order.status === 'pending_assignment')) actions += button('取消工单', 'cancel-order', id);
  const progression = ['pending_assignment', 'pending_acceptance', 'in_progress', 'pending_review', 'closed'];
  openDialog(order.number, `<div>${badge(order.status)} ${badge(order.priority)}</div><ol class="progress">${progression.map(status => `<li class="${status === order.status ? 'current' : ''}">${stateLabels[status]}</li>`).join('')}</ol><h3>${e(order.title)}</h3><div class="detail-grid"><div><div class="detail-label">设备</div><div class="detail-value">${e(device(order.deviceId)?.name)}</div></div><div><div class="detail-label">执行人 / 报修人</div><div class="detail-value">${e(person(order.assigneeId))} / ${e(person(order.reporterId))}</div></div><div><div class="detail-label">故障描述</div><div class="detail-value">${e(order.description)}</div></div><div><div class="detail-label">创建时间</div><div class="detail-value">${e(when(order.createdAt))}</div></div></div>${order.sourceTaskId ? '<p class="dialog-description">由维保异常生成，来源任务保留关联。</p>' : ''}<div class="detail-actions">${actions}</div>${order.completions.map((item, index) => `<section class="detail-block"><h3>完工记录 ${index + 1}</h3><p>${e(item.summary)}\n工时：${item.workMinutes} 分钟\n耗材：${e(item.materials || '未记录')}（未扣减库存）</p></section>`).join('')}<section class="detail-block"><h3>流转记录</h3><div class="timeline">${order.history.map(item => `<div class="event-line">${e(item.detail)}<div class="event-time">${e(item.actorName)} · ${e(when(item.at))}</div></div>`).join('')}</div></section>`);
}
function orderActionForm(id, mode) {
  const order = snapshot.orders.find(item => item.id === id);
  if (mode === 'complete-order') {
    openDialog('提交完工 · ' + order.number, `<form><label class="field">维修结果<textarea name="summary" required maxlength="1000"></textarea></label>${field('实际工时（分钟）', 'workMinutes', '30', 'number', 'min="1" max="10080" step="1"')}<label class="field">耗材记录（可选，仅文字，不扣库存）<textarea name="materials" maxlength="500"></textarea></label><label class="check-label"><input name="safetyConfirmed" type="checkbox" required>确认演示中的现场安全检查已完成。正式安全 SOP 与证据上传待实现。</label>${formFooter('提交并等待验收')}</form>`);
    bindForm(async form => { await api(`/api/orders/${id}/complete`, 'POST', { version: order.version, summary: form.get('summary'), workMinutes: Number(form.get('workMinutes')), materials: form.get('materials'), safetyConfirmed: form.get('safetyConfirmed') === 'on' }); return '已提交完工，等待验收'; });
  } else {
    const review = mode.startsWith('review-');
    const label = { reject: '拒单原因', 'cancel-order': '取消原因', 'review-accept': '验收说明', 'review-return': '退回原因' }[mode];
    openDialog(label + ' · ' + order.number, `<form><label class="field">${label}<textarea name="note" required maxlength="500"></textarea></label>${formFooter('确认提交')}</form>`);
    bindForm(async form => { await api(`/api/orders/${id}/${review ? 'review' : mode === 'reject' ? 'reject' : 'cancel'}`, 'POST', review ? { version: order.version, decision: mode === 'review-accept' ? 'accept' : 'return', note: form.get('note') } : { version: order.version, reason: form.get('note') }); return '工单已更新'; });
  }
}
function taskDetail(id) {
  const task = snapshot.tasks.find(item => item.id === id);
  const canExecute = admin() || task.assigneeId === actorId;
  const description = `<p class="dialog-description">${e(device(task.deviceId)?.name)} · ${e(task.scheduledDate)} · ${e(person(task.assigneeId))}<br>每项须填写结果；异常说明必填，提交后汇总生成一张维修工单。</p>`;
  if (task.status === 'pending' && canExecute) {
    openDialog(task.number + ' · ' + task.name, `${description}<form>${task.checklist.map((item, index) => `<section class="check-item"><h3>${index + 1}. ${e(item.label)}</h3><div class="field-grid"><label class="field">结果<select name="verdict-${index}" required><option value="">选择结果</option><option value="normal">正常</option><option value="abnormal">异常</option></select></label><label class="field">读数（可选）<input name="reading-${index}" maxlength="100"></label></div><label class="field">说明（异常时必填）<input name="remark-${index}" maxlength="500"></label></section>`).join('')}<label class="field">作业备注<textarea name="note" maxlength="1000"></textarea></label>${formFooter('完成任务')}</form>`);
    bindForm(async form => { const result = await api(`/api/tasks/${id}/complete`, 'POST', { version: task.version, results: task.checklist.map((item, index) => ({ itemId: item.id, verdict: form.get(`verdict-${index}`), reading: form.get(`reading-${index}`), remark: form.get(`remark-${index}`) })), note: form.get('note') }); return result.linkedOrderId ? '任务已完成，异常已生成维修工单' : '任务已完成'; });
  } else {
    openDialog(task.number + ' · ' + task.name, description + badge(task.status) + `<div class="detail-block">${task.status === 'completed' ? task.results.map(item => `<p><strong>${e(item.label)}</strong> · ${item.verdict === 'normal' ? '正常' : '异常'}<br>${e(item.reading)} ${e(item.remark)}</p>`).join('') + `<p>作业备注：${e(task.note || '无')}</p>` : '<p>请切换到指派的执行人或管理员后操作。</p>'}</div>${task.linkedOrderId ? button('查看关联维修工单', 'order', task.linkedOrderId, true) : ''}`);
  }
}
async function action(name, id, buttonElement) {
  if (name === 'close') return $('#dialog').close();
  if (name === 'filter-order') { orderFilter = id; orderSearch = ''; render(); return; }
  if (name === 'filter-task') { taskFilter = id; render(); return; }
  if (name === 'new-order' || name === 'scan') return newOrderForm(id);
  if (name === 'order') return orderDetail(id);
  if (name === 'task') return taskDetail(id);
  if (['complete-order', 'reject', 'cancel-order', 'review-accept', 'review-return'].includes(name)) return orderActionForm(id, name);
  if (name === 'new-device') {
    openDialog('新增设备', `<form>${field('设备编码', 'code', '', 'text', 'maxlength="40"')}${field('设备名称', 'name', '', 'text', 'maxlength="80"')}${field('设备位置', 'location', '', 'text', 'maxlength="80"')}${field('设备分类', 'category', '', 'text', 'maxlength="40"')}${formFooter('建立台账')}</form>`);
    return bindForm(async form => { await api('/api/devices', 'POST', Object.fromEntries(form)); return '设备已建档'; });
  }
  if (name === 'new-plan') {
    openDialog('新建周期计划', `<form>${field('计划名称', 'name', '', 'text', 'maxlength="100"')}<label class="field">设备<select name="deviceId">${deviceOptions()}</select></label><div class="field-grid"><label class="field">类型<select name="type">${options([['inspection', '点检'], ['maintenance', '保养']])}</select></label>${field('周期（天）', 'intervalDays', '7', 'number', 'min="1" max="3650" step="1"')}</div>${field('首次到期日期', 'nextDueDate', snapshot.summary.today, 'date')}<label class="field">执行人<select name="assigneeId">${options(snapshot.actors.filter(item => ['technician', 'inspector'].includes(item.role)).map(item => [item.id, item.name]))}</select></label><label class="field">检查项（每行一项）<textarea name="checklist" required placeholder="润滑油位&#10;运行声音"></textarea><span class="field-hint">1–20 项，名称不重复；已生成任务保留生成时的标准。</span></label>${formFooter('建立计划')}</form>`);
    return bindForm(async form => { const input = Object.fromEntries(form); input.intervalDays = Number(input.intervalDays); input.checklist = input.checklist.split(/\r?\n/).map(item => item.trim()).filter(Boolean); await api('/api/plans', 'POST', input); return '计划已建立，可生成到期任务'; });
  }
  if (name === 'device') {
    const info = await api(`/api/devices/${id}/history`);
    return openDialog(info.device.code + ' · 设备履历', `<p class="dialog-description">${e(info.device.name)} · ${e(info.device.location)}</p><section class="detail-block"><h3>维修记录</h3>${ordersTable(info.orders, true)}</section><section class="detail-block"><h3>维保记录</h3>${taskLines(info.tasks)}</section>`);
  }
  if (name === 'try-reserved') {
    const item = capabilities.reserved.find(value => value.id === id);
    try { await api(item.path.replace('{id}', 'demo'), item.method, item.method === 'GET' ? undefined : item.request); }
    catch (error) { return toast(`${item.id} · ${error.code}：${error.message}`, true); }
    throw new Error('预留接口意外返回成功，请检查路由');
  }
  buttonElement.disabled = true;
  try {
    if (name === 'assign' || name === 'accept') {
      const order = snapshot.orders.find(item => item.id === id);
      await api(`/api/orders/${id}/${name}`, 'POST', { version: order.version, ...(name === 'assign' ? { assigneeId: 'technician' } : {}) });
      await load(); render(); orderDetail(id); toast(name === 'assign' ? '已派单' : '已接单');
    } else if (name === 'generate') {
      const result = await api('/api/plans/generate', 'POST', { throughDate: snapshot.summary.today });
      await load(); render(); toast(`生成 ${result.count} 项到期任务；重复生成不会重复派发`);
    } else if (name === 'toggle-plan') {
      const plan = snapshot.plans.find(item => item.id === id);
      await api(`/api/plans/${id}`, 'PATCH', { version: plan.version, active: !plan.active });
      await load(); render(); toast('计划状态已更新；既有任务保留');
    } else if (name === 'read-message') {
      await api(`/api/messages/${id}/read`, 'PATCH', {});
      await load(); render(); showMessages();
    }
  } finally { buttonElement.disabled = false; }
}
function showMessages() {
  openDialog('站内消息', '<p class="dialog-description">消息只展示当前演示角色收到的记录，尚未接入外部推送。</p>' + (snapshot.messages.length ? snapshot.messages.map(item => `<div class="message-item ${item.read ? 'read' : ''}"><div>${e(item.title)}<small>${e(when(item.createdAt))} · ${item.read ? '已读' : '未读'}</small></div>${!item.read ? button('标记已读', 'read-message', item.id) : ''}</div>`).join('') : '<div class="empty">暂无消息</div>'));
}
function navigate() {
  const requested = location.hash.slice(1);
  currentView = Object.hasOwn(views, requested) ? requested : 'dashboard';
  render();
}
document.addEventListener('click', event => {
  const target = event.target.closest('[data-action]');
  if (target) action(target.dataset.action, target.dataset.id, target).catch(async error => { toast(error.message, true); if (error.code === 'VERSION_CONFLICT') { await load(); render(); } });
});
document.addEventListener('input', event => {
  if (event.target.id === 'device-search') { deviceSearch = event.target.value; $('#device-table').innerHTML = deviceTable(); }
  if (event.target.id === 'order-search') {
    orderSearch = event.target.value;
    const q = orderSearch.trim().toLowerCase();
    const results = snapshot.orders.filter(item => (!orderFilter || item.status === orderFilter) &&
      `${item.number} ${item.title} ${device(item.deviceId)?.name || ''}`.toLowerCase().includes(q));
    $('#order-results').innerHTML = renderOrderRows(snapshot, results, e);
  }
});
$('#close-dialog').addEventListener('click', () => $('#dialog').close());
$('#messages-button').addEventListener('click', showMessages);
$('#actor').addEventListener('change', async event => {
  actorId = event.target.value;
  localStorage.setItem('demo-actor', actorId);
  if ($('#dialog').open) $('#dialog').close();
  try { await load(); render(); toast(`已切换为${actor().name}，仅用于演示`); } catch (error) { toast(error.message, true); }
});
window.addEventListener('hashchange', navigate);
async function start() {
  try {
    const actors = await api('/api/actors');
    if (!actors.some(item => item.id === actorId)) { actorId = 'admin'; localStorage.setItem('demo-actor', actorId); }
    [capabilities] = await Promise.all([api('/api/capabilities'), load()]);
    navigate();
  } catch (error) {
    $('#content').setAttribute('aria-busy', 'false');
    $('#content').innerHTML = `<section class="error-panel"><h1>演示服务未就绪</h1><p>${e(error.message)}</p><p>请确认 Tomcat 11 已部署并启动当前 WAR，然后刷新页面。</p></section>`;
  }
}
start();
