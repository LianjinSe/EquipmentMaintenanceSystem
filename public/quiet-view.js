const statusNames = {
  pending_assignment: '待派单', pending_acceptance: '待接单', in_progress: '维修中',
  pending_review: '待验收', closed: '已关闭', cancelled: '已取消',
  pending: '待执行', completed: '已完成', active: '正常',
  reported: '已报修', repairing: '维修中', urgent: '紧急', normal: '普通',
};

const statusTone = status => ({
  pending_assignment: 'needs', pending_acceptance: 'needs', reported: 'needs',
  in_progress: 'progress', repairing: 'progress', pending_review: 'review',
  closed: 'done', completed: 'done', active: 'done', cancelled: 'muted',
})[status] || 'muted';

const badge = (status, e) => `<span class="quiet-state ${statusTone(status)}">${e(statusNames[status] || status)}</span>`;
const asset = (snapshot, id) => snapshot.devices.find(item => item.id === id);
const person = (snapshot, id) => snapshot.actors.find(item => item.id === id)?.name || '未指派';
const button = (label, action, id = '', primary = false, e = value => value) =>
  `<button class="button ${primary ? 'primary' : ''}" type="button" data-action="${action}" data-id="${e(id)}">${label}</button>`;

export function renderOrderRows(snapshot, orders, e) {
  if (!orders.length) return '<div class="empty">没有符合条件的维修工单</div>';
  return `<div class="quiet-work-list">${orders.map((order, index) => `<article class="quiet-work-item">
    <span class="quiet-index">${String(index + 1).padStart(2, '0')}</span>
    <div class="quiet-work-copy"><small>${e(asset(snapshot, order.deviceId)?.name || '设备待确认')} <i>·</i> ${e(order.number)}</small>
      <strong>${e(order.title)}</strong><span>${e(person(snapshot, order.assigneeId))} · ${e(new Date(order.createdAt).toLocaleString('zh-CN', { hour12: false }))}</span></div>
    ${badge(order.status, e)}<button class="text-button" type="button" data-action="order" data-id="${e(order.id)}">打开 ↗</button>
  </article>`).join('')}</div>`;
}

export function renderTaskRows(snapshot, tasks, e) {
  if (!tasks.length) return '<div class="empty">这里还没有维保任务</div>';
  return `<div class="quiet-task-list">${tasks.map(task => `<article class="quiet-task">
    <span class="quiet-task-icon" aria-hidden="true">${task.status === 'completed' ? '✓' : '◌'}</span>
    <div class="quiet-task-copy"><strong>${e(task.name)}</strong><span>${e(asset(snapshot, task.deviceId)?.name || '设备待确认')} · ${e(person(snapshot, task.assigneeId))} · ${task.checklist.length} 项</span></div>
    <small class="${task.status === 'pending' && task.scheduledDate < snapshot.summary.today ? 'is-late' : ''}">${e(task.scheduledDate)}${task.status === 'pending' && task.scheduledDate < snapshot.summary.today ? ' · 已超期' : ''}</small>
    <button class="text-button" type="button" data-action="task" data-id="${e(task.id)}">查看任务 ↗</button>
  </article>`).join('')}</div>`;
}

export function renderDeviceCards(snapshot, search, e) {
  const q = search.trim().toLowerCase();
  const devices = snapshot.devices.filter(item => `${item.code} ${item.name} ${item.location}`.toLowerCase().includes(q));
  if (!devices.length) return '<div class="empty">没有找到设备，试试名称、编码或位置</div>';
  return `<div class="quiet-device-grid">${devices.map(device => `<article class="quiet-device">
    <div><small>${e(device.code)} / ${e(device.category)}</small><h3>${e(device.name)}</h3><p>${e(device.location)}</p></div>
    ${badge(device.derivedStatus, e)}<div class="quiet-device-actions">
      <button class="text-button" type="button" data-action="device" data-id="${e(device.id)}">查看履历 ↗</button>
      <button class="text-button" type="button" data-action="scan" data-id="${e(device.id)}">模拟扫码报修 ↗</button>
    </div></article>`).join('')}</div>`;
}

function pageHeader(kicker, title, description, action = '') {
  return `<section class="quiet-page-head"><span class="quiet-overline">${kicker}</span><h1>${title}</h1><p>${description}</p>${action}</section>`;
}

function activity(snapshot, e, when) {
  const events = snapshot.audit.length ? snapshot.audit : snapshot.orders.flatMap(item => item.history).sort((a, b) => b.at.localeCompare(a.at));
  if (!events.length) return '<p class="empty">暂无业务动态</p>';
  return events.slice(0, 4).map(item => `<div class="quiet-event"><span class="quiet-event-dot"></span><div><strong>${e(item.detail)}</strong><small>${e(item.actorName)} · ${e(when(item.at))}</small></div></div>`).join('');
}

function dashboard(snapshot, e, when) {
  const s = snapshot.summary;
  const open = snapshot.orders.filter(item => !['closed', 'cancelled'].includes(item.status));
  const urgent = open.filter(item => item.priority === 'urgent').length;
  const focus = urgent ? `有 ${urgent} 张紧急工单需要优先处理。` : '目前没有紧急工单，可以按计划处理维保任务。';
  const date = s.today.replaceAll('-', '.');
  return `<section class="quiet-hero"><div class="quiet-hero-text"><span class="quiet-overline">${e(date)}　·　试点工厂</span>
    <h1>把今天的工作，<br><em>安排得清清楚楚。</em></h1>
    <p>维修、点检和设备状态汇在一处。先处理需要响应的事，再完成计划内工作。</p>
    <div class="quiet-hero-actions">${button('发起报修 ↗', 'new-order', '', true, e)}<a class="button outline" href="#tasks">查看今日任务</a></div></div>
    <div class="quiet-hero-number"><small>TODAY'S FOCUS</small><strong>${String(s.openOrders).padStart(2, '0')}</strong><span>件进行中的维修工单</span><div class="quiet-hero-line"></div>
    <p>紧急事项 ${urgent} 件 · 待执行维保 ${s.pendingTasks} 项</p></div></section>
    <section class="quiet-numbers" aria-label="工作概览">
      <div><small>01 / ASSETS</small><strong>${s.devices}</strong><span>已建档设备</span></div>
      <div><small>02 / WORK ORDERS</small><strong>${s.openOrders}</strong><span>待跟进工单</span></div>
      <div><small>03 / MAINTENANCE</small><strong>${s.pendingTasks}</strong><span>待执行任务</span></div>
      <div><small>04 / OVERDUE</small><strong>${s.overdueTasks}</strong><span>超期任务</span></div>
    </section>
    <div class="quiet-content-grid"><section class="quiet-work"><header><div><span>01 / TODAY'S WORK</span><h2>正在推进的维修</h2></div><a href="#orders">查看全部工单 ↗</a></header>
      ${renderOrderRows(snapshot, open.slice(0, 5), e)}</section>
      <aside class="quiet-side"><section class="quiet-side-note"><span>本班提示</span><h2>先处理异常，<br>再回到计划。</h2><p>${focus}维保任务和验收结果都能从这里追踪。</p><a href="#orders">查看工单 ↗</a></section>
      <section class="quiet-side-task"><header><span>02 / UPCOMING</span><h2>接下来的维保</h2></header>
      ${renderTaskRows(snapshot, snapshot.tasks.filter(item => item.status === 'pending').slice(0, 3), e)}<a class="quiet-more" href="#tasks">全部维保任务 ↗</a></section></aside></div>
    <div class="quiet-bottom-grid"><section class="quiet-activity"><header><span>03 / ACTIVITY</span><h2>最近业务动态</h2></header>${activity(snapshot, e, when)}</section>
      <section class="quiet-help"><header><span>04 / REFERENCE</span><h2>演示与开发说明</h2></header>
      <a href="docs/api" target="_blank" rel="noopener">接口契约与错误码 ↗</a><a href="docs/unfinished" target="_blank" rel="noopener">未完成部分与接续条件 ↗</a><a href="docs/coverage" target="_blank" rel="noopener">原清单功能覆盖矩阵 ↗</a></section></div>`;
}

function ordersView(snapshot, orderFilter, orderSearch, e) {
  const byStatus = snapshot.orders.filter(item => !orderFilter || item.status === orderFilter);
  const q = orderSearch.trim().toLowerCase();
  const filtered = byStatus.filter(item => `${item.number} ${item.title} ${asset(snapshot, item.deviceId)?.name || ''}`.toLowerCase().includes(q));
  const tabs = [['', '全部'], ['pending_assignment', '待派单'], ['pending_acceptance', '待接单'], ['in_progress', '维修中'], ['pending_review', '待验收'], ['closed', '已关闭'], ['cancelled', '已取消']];
  return pageHeader('WORK ORDERS / 维修工单', '每一张工单，<em>都有下文。</em>', '状态、设备与处理记录聚在一条清楚的列表里。', button('发起新报修 ↗', 'new-order', '', true, e)) +
    `<section class="quiet-list-wrap"><div class="quiet-list-tools"><div class="quiet-filter-list" role="group" aria-label="筛选工单状态">${tabs.map(([value, label]) =>
      `<button type="button" class="${orderFilter === value ? 'active' : ''}" aria-pressed="${orderFilter === value}" data-action="filter-order" data-id="${value}">${label}</button>`).join('')}</div>
      <input id="order-search" type="search" aria-label="搜索工单" placeholder="搜索编号、设备或故障" value="${e(orderSearch)}"></div>
      <div id="order-results">${renderOrderRows(snapshot, filtered, e)}</div></section>`;
}

function tasksView(snapshot, taskFilter, admin, e) {
  const tasks = snapshot.tasks.filter(item => !taskFilter || item.status === taskFilter);
  return pageHeader('MAINTENANCE / 点检与保养', '把计划落到<em>每一项检查。</em>', '按到期日期执行任务，异常会保留设备上下文并关联维修。',
    admin ? button('生成到期任务 ↗', 'generate', '', true, e) : '') +
    `<section class="quiet-list-wrap quiet-tasks-page"><div class="quiet-list-tools"><div class="quiet-filter-list" role="group" aria-label="筛选维保任务">${[['pending', '待执行'], ['completed', '已完成'], ['', '全部任务']].map(([value, label]) =>
      `<button type="button" class="${taskFilter === value ? 'active' : ''}" aria-pressed="${taskFilter === value}" data-action="filter-task" data-id="${value}">${label}</button>`).join('')}</div>
      <span class="quiet-list-hint">日期按上海时区计算</span></div>${renderTaskRows(snapshot, tasks, e)}</section>`;
}

function devicesView(snapshot, deviceSearch, admin, e) {
  return pageHeader('EQUIPMENT / 设备档案', '找到设备，<em>找到来龙去脉。</em>', '按设备追查位置、业务状态和维修保养履历。',
    admin ? button('新增设备 ↗', 'new-device', '', true, e) : '') +
    `<section class="quiet-device-wrap"><div class="quiet-list-tools"><span>已建档 ${snapshot.devices.length} 台</span>
      <input id="device-search" type="search" aria-label="搜索设备" placeholder="搜索设备、编码或位置" value="${e(deviceSearch)}"></div>
      <div id="device-table">${renderDeviceCards(snapshot, deviceSearch, e)}</div></section>`;
}

function plansView(snapshot, admin, e) {
  const actions = admin ? `${button('新建周期计划 ↗', 'new-plan', '', true, e)} ${button('生成到期任务', 'generate', '', false, e)}` : '';
  const rows = snapshot.plans.map(plan => `<article class="quiet-plan"><div class="quiet-plan-date"><strong>${e(plan.nextDueDate.slice(8))}</strong><small>${e(plan.nextDueDate.slice(0, 7))}</small></div>
    <div class="quiet-plan-copy"><small>${plan.type === 'maintenance' ? '保养' : '点检'} / ${plan.intervalDays} 天周期 / ${plan.checklist.length} 项检查</small>
    <strong>${e(plan.name)}</strong><span>${e(asset(snapshot, plan.deviceId)?.name || '设备待确认')} · ${e(person(snapshot, plan.assigneeId))}</span></div>
    ${plan.active ? badge('active', e) : '<span class="quiet-state muted">已暂停</span>'}
    ${admin ? `<button class="text-button" type="button" data-action="toggle-plan" data-id="${e(plan.id)}">${plan.active ? '暂停计划' : '恢复计划'} ↗</button>` : ''}</article>`).join('');
  return pageHeader('RECURRING PLANS / 周期计划', '让每一次保养，<em>按时发生。</em>', '计划按日期生成任务，暂停只影响后续生成。', actions) +
    `<section class="quiet-list-wrap quiet-plans-page">${rows || '<div class="empty">还没有周期计划</div>'}</section>`;
}

function capabilitiesView(capabilities, e) {
  const extensions = capabilities.implemented.filter(item => item.id);
  return pageHeader('INTERFACES / 能力边界', '完成了什么，<em>还有什么。</em>', '新增能力已有本地后端；外部服务未配置时会明确返回失败。每项入口的局部实现和接续条件列在下面。',
    '<a class="button primary" href="docs/api" target="_blank" rel="noopener">完整 API 说明 ↗</a>') +
    `<div class="quiet-capability-count"><strong>${capabilities.implemented.length}</strong><span>个后端路由</span><strong>${extensions.length}</strong><span>个本地扩展能力</span></div>
    <section class="quiet-capabilities"><header><span>接口与进展</span><h2>本地实现与接续条件</h2></header>
    <p class="dialog-description">新增API需要本地账号Bearer令牌；首次凭据位于数据文件旁，具体调用见API说明。现有前端仍使用演示角色。</p>
    ${extensions.map(item => `<details class="contract"><summary><span class="contract-summary-name">${e(item.name)}</span><span class="quiet-state muted">本地切片</span><code>${e(item.method)} ${e(item.path)}</code></summary>
    <div class="contract-body"><h3>当前本地实现</h3><p>${e(item.localScope)}</p><h3>仍需接续</h3><ul>${item.limitations.map(value => `<li>${e(value)}</li>`).join('')}</ul><h3>输入示例</h3><pre>${e(JSON.stringify(item.request, null, 2))}</pre><h3>目标返回</h3><code>${e(item.response)}</code>
    <h3>原需求依赖</h3><ul>${item.dependencies.map(value => `<li>${e(value)}</li>`).join('')}</ul><h3>原目标验收条件</h3><ul>${item.acceptance.map(value => `<li>${e(value)}</li>`).join('')}</ul></div></details>`).join('')}</section>`;
}

export function renderView({ snapshot, capabilities, currentView, admin, orderFilter, taskFilter, deviceSearch, orderSearch, e, when }) {
  if (currentView === 'dashboard') return dashboard(snapshot, e, when);
  if (currentView === 'orders') return ordersView(snapshot, orderFilter, orderSearch, e);
  if (currentView === 'tasks') return tasksView(snapshot, taskFilter, admin, e);
  if (currentView === 'devices') return devicesView(snapshot, deviceSearch, admin, e);
  if (currentView === 'plans') return plansView(snapshot, admin, e);
  return capabilitiesView(capabilities, e);
}
