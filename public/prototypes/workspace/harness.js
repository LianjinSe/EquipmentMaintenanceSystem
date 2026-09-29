import tower from './variant-tower.js';
import quiet from './variant-quiet.js';
import field from './variant-field.js';

const variants = [tower, quiet, field];
// All prototype interactions stay local to this page and never write to the Java API.
const stage = document.getElementById('stage');
const picker = document.querySelector('.proto-picker');
const highlight = picker.querySelector('.proto-picker-highlight');
const items = [...picker.querySelectorAll('.proto-picker-item:not(.proto-picker-replay)')];
const replay = picker.querySelector('.proto-picker-replay');
let current = 0;

const snapshot = {
  view: 'dashboard', filter: 'all', search: '', modal: null, toast: '',
  orders: [
    { id: 'WO-2048', title: '排气压力持续偏高', device: '空压机 A2', location: '动力站 · A 区', priority: '紧急', status: '待派单', assignee: '未指派', time: '09:26', age: '18 分钟' },
    { id: 'WO-2047', title: '主轴启动时异常振动', device: '数控加工中心 07', location: '一号车间 · 加工线', priority: '高', status: '维修中', assignee: '顾维修工', time: '08:42', age: '1 小时' },
    { id: 'WO-2046', title: '传送带跑偏', device: '输送线 C3', location: '二号车间 · 装配线', priority: '普通', status: '待验收', assignee: '周维修工', time: '昨天 16:28', age: '16 小时' },
    { id: 'WO-2045', title: '冷却水泵密封检查', device: '循环泵 B1', location: '动力站 · B 区', priority: '普通', status: '已关闭', assignee: '顾维修工', time: '昨天 10:30', age: '已完成' },
  ],
  tasks: [
    { id: 'PM-381', title: '每日开机点检', device: '数控加工中心 07', due: '10:30 前', status: '待执行', owner: '林巡检员', steps: '3 项' },
    { id: 'PM-382', title: '周期润滑保养', device: '空压机 A2', due: '14:00 前', status: '待执行', owner: '顾维修工', steps: '5 项' },
    { id: 'PM-379', title: '输送线安全检查', device: '输送线 C3', due: '已超期 1 天', status: '待执行', owner: '林巡检员', steps: '4 项' },
  ],
  devices: [
    { id: 'EQ-007', name: '数控加工中心 07', code: 'CNC-07', place: '一号车间 · 加工线', health: '检修中', kind: '机加工' },
    { id: 'EQ-014', name: '空压机 A2', code: 'AIR-A2', place: '动力站 · A 区', health: '异常待处理', kind: '动力' },
    { id: 'EQ-029', name: '输送线 C3', code: 'CV-C3', place: '二号车间 · 装配线', health: '待验收', kind: '输送' },
    { id: 'EQ-035', name: '循环泵 B1', code: 'PUMP-B1', place: '动力站 · B 区', health: '运行中', kind: '动力' },
  ],
  notifications: [
    { id: 'n1', title: '空压机 A2 待派单', detail: '排气压力持续偏高 · 09:26', read: false },
    { id: 'n2', title: '输送线安全检查已超期', detail: '计划 PM-379 · 昨天到期', read: false },
    { id: 'n3', title: '输送线 C3 等待验收', detail: '工单 WO-2046 · 昨天 16:28', read: true },
  ],
};

function fixture() { return structuredClone(snapshot); }
let model = fixture();
const h = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
const labels = { dashboard: '工作概览', orders: '维修工单', tasks: '维保任务', devices: '设备台账' };

function orderAction(order) {
  return ({ '待派单': ['指派维修工', '待接单'], '待接单': ['开始维修', '维修中'], '维修中': ['提交完工', '待验收'], '待验收': ['验收关闭', '已关闭'] })[order.status];
}

function modalMarkup(className) {
  if (!model.modal) return '';
  let content = '';
  let title = '';
  if (model.modal.type === 'order') {
    const order = model.orders.find(item => item.id === model.modal.id);
    if (!order) return '';
    title = `${order.id} · ${order.title}`;
    const next = orderAction(order);
    content = `<p class="proto-modal-lead">${h(order.device)}　·　${h(order.location)}</p>
      <div class="proto-modal-grid"><div><small>优先级</small><strong>${h(order.priority)}</strong></div><div><small>当前状态</small><strong>${h(order.status)}</strong></div><div><small>执行人</small><strong>${h(order.assignee)}</strong></div><div><small>报修时间</small><strong>${h(order.time)}</strong></div></div>
      <p class="proto-modal-note">演示场景：工单状态可逐步流转，用于比较不同方案的详情阅读与操作体验。</p>
      <div class="proto-modal-actions">${next ? `<button type="button" data-action="advance-order" data-id="${h(order.id)}">${h(next[0])}</button>` : '<span>此工单已完成闭环</span>'}</div>`;
  } else if (model.modal.type === 'report') {
    title = '发起设备报修';
    content = `<p class="proto-modal-lead">选择设备并填写故障，提交后会出现在当前方案的工单列表。</p>
      <form id="prototype-report"><label>关联设备<select name="device" required>${model.devices.map(item => `<option value="${h(item.name)}" ${item.name === model.modal.device ? 'selected' : ''}>${h(item.name)}</option>`).join('')}</select></label>
      <label>故障现象<input name="title" required maxlength="80" placeholder="例如：运行时出现异响"></label>
      <label>处理优先级<select name="priority"><option>普通</option><option>高</option><option>紧急</option></select></label>
      <div class="proto-modal-actions"><button type="submit">提交报修</button></div></form>`;
  } else if (model.modal.type === 'notifications') {
    title = '消息中心';
    content = `<div class="proto-notices">${model.notifications.map(item => `<article class="${item.read ? 'is-read' : ''}"><div><strong>${h(item.title)}</strong><span>${h(item.detail)}</span></div>${item.read ? '<small>已读</small>' : `<button type="button" data-action="read-notice" data-id="${h(item.id)}">标记已读</button>`}</article>`).join('')}</div>`;
  } else if (model.modal.type === 'device') {
    const device = model.devices.find(item => item.id === model.modal.id);
    if (!device) return '';
    title = device.name;
    content = `<p class="proto-modal-lead">${h(device.code)}　·　${h(device.place)}</p>
      <div class="proto-modal-grid"><div><small>业务状态</small><strong>${h(device.health)}</strong></div><div><small>设备分类</small><strong>${h(device.kind)}</strong></div></div>
      <div class="proto-modal-actions"><button type="button" data-action="new-report" data-device="${h(device.name)}">为此设备报修</button></div>`;
  }
  return `<div class="proto-modal-backdrop" data-action="close-modal"><section class="proto-modal" role="dialog" aria-modal="true" aria-label="${h(title)}"><button class="proto-modal-close" type="button" data-action="close-modal" aria-label="关闭">×</button><small class="proto-modal-kicker">设备维保 · 交互原型</small><h2>${h(title)}</h2>${content}</section></div>`;
}

function viewData() {
  return {
    ...model,
    labels,
    unread: model.notifications.filter(item => !item.read).length,
    activeOrders: model.orders.filter(item => item.status !== '已关闭').length,
    pendingTasks: model.tasks.filter(item => item.status === '待执行').length,
    urgentOrders: model.orders.filter(item => item.priority === '紧急' && item.status !== '已关闭').length,
    ordersInView: model.orders.filter(item => model.filter === 'all' || item.status === model.filter),
  };
}

function moveHighlight() {
  const el = items[current];
  highlight.style.width = el.offsetWidth + 'px';
  highlight.style.transform = `translateX(${el.offsetLeft}px)`;
}

function mount(i) {
  stage.innerHTML = '';
  requestAnimationFrame(() => {
    const active = variants[i];
    const wrapper = `<div class="${active.className}">${active.render(viewData(), h)}${modalMarkup(active.className)}${model.toast ? `<div class="proto-toast" role="status">${h(model.toast)}</div>` : ''}</div>`;
    stage.innerHTML = wrapper;
  });
}

function setActive(i) {
  if (i < 0 || i >= variants.length) return;
  if (i !== current) model = fixture();
  current = i;
  items.forEach((el, j) => {
    el.toggleAttribute('data-active', j === i);
    if (j === i) el.setAttribute('aria-current', 'true');
    else el.removeAttribute('aria-current');
  });
  picker.toggleAttribute('data-position', false);
  if (i === 2) picker.setAttribute('data-position', 'top');
  moveHighlight();
  const url = new URL(location);
  url.searchParams.set('v', i + 1);
  history.replaceState(null, '', url);
  mount(i);
}

items.forEach((el, i) => el.addEventListener('click', () => setActive(i)));
replay?.addEventListener('click', () => mount(current));
window.addEventListener('resize', moveHighlight);

stage.addEventListener('click', event => {
  const button = event.target.closest('[data-action]');
  if (!button) return;
  const action = button.dataset.action;
  if (action === 'close-modal' && event.target !== button && !button.matches('.proto-modal-close')) return;
  model.toast = '';
  if (action === 'nav') { model.view = button.dataset.view; model.filter = 'all'; model.search = ''; model.modal = null; }
  else if (action === 'filter') { model.filter = button.dataset.filter; }
  else if (action === 'open-order') { model.modal = { type: 'order', id: button.dataset.id }; }
  else if (action === 'open-device') { model.modal = { type: 'device', id: button.dataset.id }; }
  else if (action === 'new-report') { model.modal = { type: 'report', device: button.dataset.device || '' }; }
  else if (action === 'notifications') { model.modal = { type: 'notifications' }; }
  else if (action === 'close-modal') { model.modal = null; }
  else if (action === 'advance-order') {
    const order = model.orders.find(item => item.id === button.dataset.id);
    const next = order && orderAction(order);
    if (next) {
      order.status = next[1];
      if (next[1] === '待接单') order.assignee = '顾维修工';
      model.toast = `${order.id} 已流转至${next[1]}`;
      model.modal = { type: 'order', id: order.id };
    }
  } else if (action === 'complete-task') {
    const task = model.tasks.find(item => item.id === button.dataset.id);
    if (task) { task.status = '已完成'; model.toast = `${task.id} 已完成`; }
  } else if (action === 'read-notice') {
    const notice = model.notifications.find(item => item.id === button.dataset.id);
    if (notice) { notice.read = true; model.toast = '已标记为已读'; }
  }
  mount(current);
});

stage.addEventListener('submit', event => {
  if (event.target.id !== 'prototype-report') return;
  event.preventDefault();
  const values = new FormData(event.target);
  const title = String(values.get('title') || '').trim();
  if (!title) return;
  const device = String(values.get('device'));
  const reference = model.devices.find(item => item.name === device);
  const number = 2049 + model.orders.filter(item => Number(item.id.slice(3)) >= 2049).length;
  model.orders.unshift({ id: `WO-${number}`, title, device, location: reference?.place || '待确认位置', priority: String(values.get('priority')), status: '待派单', assignee: '未指派', time: '刚刚', age: '新报修' });
  model.view = 'orders';
  model.filter = 'all';
  model.modal = null;
  model.toast = `WO-${number} 已加入演示工单`;
  mount(current);
});

stage.addEventListener('input', event => {
  if (!event.target.matches('[data-search-input]')) return;
  model.search = event.target.value;
  const query = model.search.trim().toLowerCase();
  stage.querySelectorAll('[data-searchable]').forEach(item => {
    item.hidden = !item.dataset.searchable.toLowerCase().includes(query);
  });
});

document.addEventListener('keydown', (e) => {
  if (/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) || e.target.isContentEditable) return;
  if (e.metaKey || e.ctrlKey || e.altKey) return;
  const num = parseInt(e.key, 10);
  if (num >= 1 && num <= variants.length) setActive(num - 1);
  else if (e.key === 'ArrowRight') setActive((current + 1) % variants.length);
  else if (e.key === 'ArrowLeft') setActive((current - 1 + variants.length) % variants.length);
  else if (e.key === 'r' || e.key === 'R') mount(current);
});

setActive((parseInt(new URLSearchParams(location.search).get('v'), 10) || 1) - 1);
requestAnimationFrame(() => requestAnimationFrame(() => picker.setAttribute('data-ready', '')));
