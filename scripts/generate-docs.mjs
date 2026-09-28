import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { routes, reserved } from '../src/contracts.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'docs', 'demo');
const source = await readFile(path.join(root, '设备维修保养信息化管理系统功能清单-db.html'), 'utf8');
const lines = [];
const example = value => value == null ? '无请求体' : `\n\`\`\`json\n${JSON.stringify(value, null, 2)}\n\`\`\`\n`;
const bullet = values => values.map(value => `- ${value}`).join('\n');
const cell = value => String(value ?? '').replace(/\|/g, '\\|').replace(/\r?\n/g, '<br>');
const clean = value => String(value ?? '').replace(/<[^>]*>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').trim();

lines.push('# Demo API 与预留接口契约', '', '> 本文由 `src/contracts.mjs` 通过 `npm run docs` 生成；修改接口时请先更新注册表，再重新生成。本文件中的预留契约是设计草案，尚不构成稳定 API。', '',
  `已实现路由 ${routes.length} 个；预留能力 ${reserved.length} 个。`, '',
  '## 通用约定', '',
  '- 服务默认仅监听 `127.0.0.1:4173`，API 前缀 `/api`，写入请求采用 `Content-Type: application/json`，请求体上限 512 KiB。',
  '- 演示角色通过 `X-Demo-Actor: admin|operator|technician|inspector` 传入。它是**可伪造的演示选择器**，不是账号认证、授权、企业数据隔离或上线安全机制。写入请求必须传入有效值。',
  '- 成功响应为 `{ "data": ... }`。失败响应为 `{ "error": { "code": "...", "message": "...", "details": null } }`。预留接口固定返回 `501 NOT_IMPLEMENTED`，`details` 还包含能力 ID、输入草案、依赖与验收要求。',
  '- 新增设备、工单、计划返回 HTTP 201；其他已实现成功操作返回 200。未知路由返回 404，状态或版本不匹配返回 409。',
  '- 大部分写操作要求资源当前 `version`；首次读取详情/列表后携带版本提交，成功后使用返回的新版本。过期版本返回 `409 VERSION_CONFLICT`。消息已读操作是例外。',
  '- 日期参数格式 `YYYY-MM-DD`。任务到期和生成截止按 `Asia/Shanghai` 日期判断；时间戳为 ISO UTC 字符串。',
  '- 未实现真实登录与企业数据权限；GET 设备、工单、维保任务会返回所有演示数据，不能放入真实数据。', '',
  '### 常见错误', '',
  '| HTTP | code | 含义 |', '|---|---|---|',
  '| 400 | `VALIDATION_ERROR`, `INVALID_JSON`, `GENERATION_LIMIT`, `SAFETY_CONFIRMATION_REQUIRED` | 字段、JSON、生成数量或安全勾选不符合规则 |',
  '| 401 | `DEMO_ACTOR_REQUIRED` | 写请求缺少有效的演示角色 |',
  '| 403 | `FORBIDDEN`, `ORIGIN_REJECTED` | 当前演示角色不可执行，或写请求跨站来源不符 |',
  '| 404 | `NOT_FOUND` | 资源或路由不存在 |',
  '| 409 | `VERSION_CONFLICT`, `INVALID_STATE`, `DUPLICATE_CODE` | 版本已变化、业务状态不允许，或编码重复 |',
  '| 413 | `PAYLOAD_TOO_LARGE` | JSON 请求体超过 512 KiB |',
  '| 415 | `UNSUPPORTED_MEDIA_TYPE` | 写请求不是 JSON |',
  '| 500 | `INTERNAL_ERROR` | 本地读取/持久化等内部失败；变更未发布 |',
  '| 501 | `NOT_IMPLEMENTED` | 已登记的预留接口尚未实现 |', '',
  '## 已实现路由', '');

for (const item of routes) {
  lines.push(`### ${item.method} \`${item.path}\` — ${item.name}`, '',
    `**输入示例：**${example(item.request)}`, '',
    `**成功返回 data：** ${item.response}`, '',
    `**行为、权限及校验：** ${item.rule}`, '',
    `**调用位置：** \`src/server.mjs\` 路由分派到 \`src/domain.mjs\` 中的 \`${item.handler}\`（GET 为读处理器）。`, '');
}
lines.push('## 预留接口（调用会返回 501）', '',
  '下面的请求体是设计输入示例；服务端对预留接口不执行业务校验和持久化，直接返回 `501 NOT_IMPLEMENTED`。实际开发时需要与业务方和技术负责人确认字段、权限、幂等策略与错误码。', '');
for (const item of reserved) {
  lines.push(`### ${item.id} · ${item.method} \`${item.path}\` — ${item.name}`, '',
    `**范围：** ${item.scope}`, '',
    `**输入草案：**${example(item.request)}`, '',
    `**目标返回（尚未实现）：** ${item.response}`, '',
    '**前置依赖：**', bullet(item.dependencies), '',
    '**验收条件：**', bullet(item.acceptance), '',
    '**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。', '');
}
await mkdir(output, { recursive: true });
await writeFile(path.join(output, 'API.md'), lines.join('\n').trimEnd() + '\n', 'utf8');

const sectionMap = {
  '用户管理': ['AUTH'], '权限管理': ['ACCESS'], '组织架构管理': ['ACCESS'], '系统设置': ['SETTINGS', 'BACKUP', 'SECURITY'], '消息通知': ['PUSH'],
  '驾驶舱与数据可视化（BI Dashboard）': ['REPORT', 'COST', 'IOT'], '设备资产管理（全生命周期）': ['ASSET', 'LABEL', 'COMPLIANCE', 'BOM', 'IOT'],
  '维保计划与点巡检管理': ['SCHEDULE', 'SOP', 'INVENTORY'], '维修工单全流程管理': ['WORKFLOW', 'COST', 'SIGN'],
  '故障管理与知识库': ['KNOWLEDGE', 'AI', 'IOT'], '耗材/备件全流程管理': ['INVENTORY', 'PROCUREMENT'],
  '报表分析与打印配置': ['REPORT'], '系统集成与专属管理功能': ['INTEGRATION', 'WORKFLOW', 'SECURITY', 'MOBILE', 'OUTSOURCE'],
  '全员极简报修通道': ['MOBILE', 'LABEL', 'FILE', 'AI', 'SIGN'], '维修人员轻量作业功能': ['MOBILE', 'WORKFLOW', 'FILE', 'KNOWLEDGE', 'INVENTORY'],
  '巡检/保养人员基础功能': ['MOBILE', 'SCHEDULE', 'SOP'], '管理层移动办公功能': ['MOBILE', 'REPORT', 'WORKFLOW', 'LOCATION'],
  '供应商/外协端协作功能': ['OUTSOURCE', 'PROCUREMENT'], '小程序专属基础功能': ['PUSH', 'KNOWLEDGE', 'LABEL', 'SETTINGS'],
  '工作台与任务中心': ['MOBILE', 'LABEL', 'OFFLINE'], '现场维修深度作业功能': ['OFFLINE', 'SOP', 'FILE', 'AI', 'SIGN'],
  '智能巡检与保养作业': ['LOCATION', 'OCR', 'IOT', 'SOP'], '移动库存与备件管理': ['INVENTORY', 'TOOL'],
  '设备档案与即时协作': ['ASSET', 'IOT', 'COLLAB'], '高级技术支持与管理功能': ['AR3D', 'COST', 'TRAINING'],
  '个人中心与APP专属功能': ['TRAINING', 'LOCATION', 'MOBILE'], '智能预警与消息联动': ['PUSH', 'SCHEDULE', 'INVENTORY'],
  '二维码/NFC标签智能管理': ['LABEL', 'ACCESS'], '无纸化办公与电子签章': ['SIGN'],
  '2026年行业趋势特色功能': ['AI', 'AR3D', 'LOWCODE'], '数据安全与容灾保障': ['SECURITY', 'BACKUP'],
};
const partial = {
  '多角色配置': ['仅内置四个可切换的演示角色及部分操作限制；角色配置、供应商身份和真实认证未实现。', ['AUTH', 'ACCESS']],
  '细粒度权限分配': ['仅在部分写动作验证演示角色；无菜单、字段、行级或企业数据隔离。', ['ACCESS']],
  '多类型消息提醒': ['工单/任务事件生成站内消息；到期自动预警、外部推送和触达回执未实现。', ['PUSH']],
  '消息管理': ['当前演示角色可查自己站内消息并标记已读；移动端偏好、时段及消息渠道未实现。', ['PUSH', 'SETTINGS']],
  '全局核心KPI看板': ['网页仅展示设备数、未关闭工单、待执行/超期任务等基础计数；MTBF/MTTR/OEE 等无数据源，未实现。', ['COST', 'REPORT', 'IOT']],
  '设备台账精细化管理': ['网页可新增、查询设备编码/名称/位置/分类；Excel 导入导出、参数、原值及折旧未实现。', ['ASSET']],
  '设备档案与标识管理': ['设备详情可查关联工单/任务履历；图纸手册附件和真实二维码/RFID 生成打印未实现。', ['ASSET', 'FILE', 'LABEL']],
  '设备状态实时监控': ['页面状态仅从未关闭工单推导“已报修/维修中”；不是设备实时运行状态，自动采集和报废审批未实现。', ['IOT', 'ASSET']],
  '多类型预防性保养计划': ['可建按天数重复的计划；运行时长、产量和 IoT 条件触发未实现。', ['SCHEDULE', 'IOT']],
  '保养计划智能管理': ['管理员手动生成截至当天的任务，支持暂停/恢复计划；自动定时、甘特图和负荷优化未实现。', ['SCHEDULE']],
  '点检标准与计划管理': ['计划保存检查项，任务按周期生成并保留检查项快照；点位方法/判定阈值和路线优化未实现。', ['SCHEDULE', 'SOP', 'LOCATION']],
  '保养/点检记录管理': ['逐项提交正常/异常和文本读数，异常关联一张维修工单；耗材扣减、模板审批、归档报表未实现。', ['INVENTORY', 'REPORT', 'SOP']],
  '工单创建与派单管理': ['可报修并由管理员手动派给固定维修工；批量、技能自动派单及抢单未实现。', ['WORKFLOW']],
  '工单执行监控与SLA管理': ['支持状态查询、拒单和接单前改派；SLA 超时、地图、处理中转单审批未实现。', ['WORKFLOW', 'LOCATION']],
  '维修费用与验收管理': ['记录文字耗材和分钟工时，支持单次验收与退回；费用审核、库存核算及多节点验收未实现。', ['COST', 'INVENTORY', 'SIGN']],
  '工单统计与分析': ['仅基础工单状态和数量统计；复杂故障、费用、绩效分析与导出未实现。', ['REPORT', 'COST']],
  '故障上报与分级分类': ['可填写故障文字和普通/紧急优先级；机械/电气分类、等级规则及 IoT 自动报警未实现。', ['KNOWLEDGE', 'IOT']],
  '全量日志审计': ['网页管理员可查看业务事件；不是完整登录/数据前后值审计，不具备防篡改或导出。', ['SECURITY']],
  '故障便捷描述': ['网页支持文字描述；语音转文字、预设故障字典、地理位置与微信通知未实现。', ['MOBILE', 'AI', 'LOCATION', 'PUSH']],
  '报修进度追踪': ['网页可查看工单状态和流转历史；小程序原生页面、快速复报及微信通知未实现。', ['MOBILE', 'PUSH']],
  '维修结果评价': ['网页可验收或退回并填写说明；星级评分、电子签名及真实小程序未实现。', ['MOBILE', 'SIGN', 'COST']],
  '工单接收与处理': ['网页演示角色可接单/拒单；真实小程序、日历及处理中转单未实现。', ['MOBILE', 'WORKFLOW', 'PUSH']],
  '现场作业记录': ['网页记录文字维修总结、分钟工时和文字耗材；照片、扫码领料及真实小程序未实现。', ['MOBILE', 'FILE', 'INVENTORY']],
  '今日任务查看': ['网页列出待执行任务；真实小程序、日历和推送未实现。', ['MOBILE', 'PUSH']],
  '点检项执行': ['网页逐项提交正常/异常，异常会生成关联工单；真实小程序、阈值和现场证据未实现。', ['MOBILE', 'SOP', 'FILE']],
  '移动端管理看板': ['响应式网页仅展示基础数量；真实小程序及 OEE/资金占用/故障率趋势未实现。', ['MOBILE', 'REPORT', 'COST']],
  '简易查询与统计': ['响应式网页可查设备和工单/任务履历；真实小程序、授权实时位置与专属日报未实现。', ['MOBILE', 'REPORT', 'LOCATION']],
  '消息与公告': ['响应式网页可查站内消息、标已读；真实小程序、公告和服务通知未实现。', ['MOBILE', 'PUSH', 'SETTINGS']],
  '多场景智能提醒': ['工单/任务事件产生站内消息；保养到期、库存、证书及多级超时规则未实现。', ['PUSH', 'SCHEDULE', 'INVENTORY', 'COMPLIANCE']],
  '操作痕迹与版本回溯': ['工单/任务记录事件，部分记录有版本号；无全部数据前后镜像和授权回溯。', ['SECURITY']],
};
const special = {
  '扫码快速报修': ['LABEL', 'MOBILE', 'FILE'],
  '多媒体作业记录': ['FILE', 'MOBILE', 'INVENTORY'],
  '标签生成与自定义': ['LABEL'],
  '身份化扫码跳转': ['LABEL', 'ACCESS'],
  '消息模板与数据管理': ['SETTINGS', 'PUSH', 'BACKUP'],
  '自动备份与容灾': ['BACKUP'],
  '全链路数据加密': ['SECURITY', 'OFFLINE'],
  '设备关系与BOM管理': ['BOM'],
  '特种设备专项管理': ['COMPLIANCE'],
  '备件采购与供应商管理': ['PROCUREMENT'],
  '定位打卡与轨迹': ['LOCATION'],
  '语音交互助手': ['AI'],
  '多语言与国际化': ['LOWCODE'],
  '电子签名与单据同步': ['SIGN'],
  '电子签章与归档': ['SIGN', 'REPORT'],
  'AI预测性维护': ['AI'],
  '数字孪生集成': ['AI', 'AR3D', 'IOT'],
  '巡检任务智能导航': ['LOCATION'],
  '多方式巡检数据采集': ['OCR', 'IOT', 'FILE'],
  '即时通讯与协作': ['COLLAB'],
  'AR辅助维修': ['AR3D', 'COLLAB'],
  '3D模型查看': ['AR3D'],
  '委外与维保商管理': ['OUTSOURCE'],
  '多系统数据接口': ['INTEGRATION'],
};
const reservedIds = new Set(reserved.map(item => item.id));
const cards = [];
let moduleTitle = '';
let sectionTitle = '';
const token = /<div class="module-title">([\s\S]*?)<\/div>|<div class="sub-module-title">([\s\S]*?)<\/div>|<li class="function-item">([\s\S]*?)<\/li>/g;
for (const match of source.matchAll(token)) {
  if (match[1]) moduleTitle = clean(match[1]);
  else if (match[2]) sectionTitle = clean(match[2]).replace(/^\d+\.\s*/, '');
  else if (match[3]) {
    const name = match[3].match(/<div class="function-name">([\s\S]*?)<\/div>/)?.[1];
    const description = match[3].match(/<div class="function-desc">([\s\S]*?)<\/div>/)?.[1];
    if (!name || !description) throw new Error(`功能卡片缺少名称或描述：${cards.length + 1}`);
    cards.push({ number: cards.length + 1, moduleTitle, sectionTitle, name: clean(name), description: clean(description) });
  }
}
if (cards.length !== 103) throw new Error(`期望 103 张功能卡片，实际 ${cards.length}；请核对原 HTML 结构和映射。`);
const titleNodes = [...source.matchAll(/<div class="function-name">/g)].length;
if (titleNodes !== 105) throw new Error(`期望 105 个标题节点，实际 ${titleNodes}；请核对原 HTML。`);
const summary = [
  '# 原清单功能覆盖矩阵', '',
  '> 本文由原 HTML 的 `li.function-item` 自动抽取并结合 `src/contracts.mjs` 生成。每行对应**一张卡片**，不是一项已批准需求或一个开发任务。', '',
  `原清单 ${cards.length} 张功能卡片、${titleNodes} 个功能标题节点；两张卡片内部各重复了一个标题。`, '',
  '“部分演示”表示仅实现该卡片描述的局部业务切片，绝不意味着整张卡片完成。“未实现”表示没有与原需求相符的可用能力；某些网页模拟入口也归在未实现。关联 ID 对应 [API.md](API.md) 的 501 预留接口；正式字段、权限、业务规则仍待评审。', '',
  '| # | 来源区块 / 子模块 | 原功能标题 | 原描述 | 现状与未完成边界 | 关联预留 ID |',
  '|---:|---|---|---|---|---|',
];
for (const card of cards) {
  const info = partial[card.name];
  const ids = info?.[1] ?? special[card.name] ?? sectionMap[card.sectionTitle];
  if (!ids?.length || ids.some(id => !reservedIds.has(id))) throw new Error(`功能映射缺失或引用错误：${card.name}（${card.sectionTitle}）`);
  const current = info ? `部分演示：${info[0]}` : `未实现：当前没有满足该卡片完整需求的可用接口；需实现右侧预留能力，并按本行原描述逐项验收。`;
  summary.push(`| ${String(card.number).padStart(3, '0')} | ${cell(card.moduleTitle)} / ${cell(card.sectionTitle)} | ${cell(card.name)} | ${cell(card.description)} | ${cell(current)} | ${ids.map(id => `\`${id}\``).join('、')} |`);
}
summary.push('', '## 核对说明', '',
  '- `扫码快速报修`在网页中只有选择设备后的模拟入口，尚无二维码、摄像头或真正小程序，故归“未实现”。',
  '- `全链路数据加密`、`自动备份与容灾`等生产能力均按原功能严格判定；本地 JSON 写盘不等于备份或容灾。',
  '- 纯属原清单运行与开发环境的两段文字不属于功能卡片，未计入 103 行；详见 [未完成部分与实施说明.md](未完成部分与实施说明.md)。', '');
await writeFile(path.join(output, '功能覆盖矩阵.md'), summary.join('\n').trimEnd() + '\n', 'utf8');
console.log(`生成 API.md：${routes.length} 个已实现路由，${reserved.length} 个预留接口`);
console.log(`生成功能覆盖矩阵.md：${cards.length} 张卡片，${titleNodes} 个源标题节点`);
