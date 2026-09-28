# Demo API 与预留接口契约

> 本文与 `src/main/resources/capabilities.json` 及 Java Servlet 路由对应。修改接口时须同步更新注册表、实现、本文和测试。预留契约是设计草案，尚不构成稳定 API。

已实现路由 27 个；预留能力 33 个。

## 通用约定

- 演示 WAR 在本机 Tomcat 11 的示例地址为 `http://127.0.0.1:8080/equipment-maintenance-demo/`，应用内 API 前缀 `/api`，写入请求采用 `Content-Type: application/json`，请求体上限 512 KiB。
- 演示角色通过 `X-Demo-Actor: admin|operator|technician|inspector` 传入。它是**可伪造的演示选择器**，不是账号认证、授权、企业数据隔离或上线安全机制。写入请求必须传入有效值。
- 成功响应为 `{ "data": ... }`。失败响应为 `{ "error": { "code": "...", "message": "...", "details": null } }`。预留接口固定返回 `501 NOT_IMPLEMENTED`，`details` 还包含能力 ID、输入草案、依赖与验收要求。
- 新增设备、工单、计划返回 HTTP 201；其他已实现成功操作返回 200。未知路由返回 404，状态或版本不匹配返回 409。
- 大部分写操作要求资源当前 `version`；首次读取详情/列表后携带版本提交，成功后使用返回的新版本。过期版本返回 `409 VERSION_CONFLICT`。消息已读操作是例外。
- 日期参数格式 `YYYY-MM-DD`。任务到期和生成截止按 `Asia/Shanghai` 日期判断；时间戳为 ISO UTC 字符串。
- 未实现真实登录与企业数据权限；GET 设备、工单、维保任务会返回所有演示数据，不能放入真实数据。

### 常见错误

| HTTP | code | 含义 |
|---|---|---|
| 400 | `VALIDATION_ERROR`, `INVALID_JSON`, `GENERATION_LIMIT`, `SAFETY_CONFIRMATION_REQUIRED` | 字段、JSON、生成数量或安全勾选不符合规则 |
| 401 | `DEMO_ACTOR_REQUIRED` | 写请求缺少有效的演示角色 |
| 403 | `FORBIDDEN`, `ORIGIN_REJECTED` | 当前演示角色不可执行，或写请求跨站来源不符 |
| 404 | `NOT_FOUND` | 资源或路由不存在 |
| 409 | `VERSION_CONFLICT`, `INVALID_STATE`, `DUPLICATE_CODE` | 版本已变化、业务状态不允许，或编码重复 |
| 413 | `PAYLOAD_TOO_LARGE` | JSON 请求体超过 512 KiB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 写请求不是 JSON |
| 500 | `INTERNAL_ERROR` | 本地读取/持久化等内部失败；变更未发布 |
| 501 | `NOT_IMPLEMENTED` | 已登记的预留接口尚未实现 |

## 已实现路由

### GET `/api/health` — 服务状态

**输入示例：**无请求体

**成功返回 data：** { mode: "demo", runtime: "java-servlet", version: "0.2.0", persistence: "json-file", today }

**行为、权限及校验：** 只说明本地服务可响应，不代表生产健康探针或容灾可用。

**实现映射：** `capabilities.json` 的 handler `health` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/actors` — 演示身份列表

**输入示例：**无请求体

**成功返回 data：** Actor[]：id/name/role/department

**行为、权限及校验：** 固定四个虚构角色。X-Demo-Actor 只用于流程演示，不能认证身份。

**实现映射：** `capabilities.json` 的 handler `actors` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/state` — 演示页面快照

**输入示例：**无请求体

**成功返回 data：** { actors, devices, orders, plans, tasks, messages, audit, summary }

**行为、权限及校验：** 全量读取演示数据；消息只返回当前角色的消息，审计仅管理员返回。设备和业务记录没有企业数据隔离。

**实现映射：** `capabilities.json` 的 handler `snapshot` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/summary` — 基础统计

**输入示例：**无请求体

**成功返回 data：** { devices, openOrders, pendingReview, pendingTasks, overdueTasks, closedOrders, today }

**行为、权限及校验：** 直接统计当前记录；超期指未完成任务的 scheduledDate 早于上海日期。不计算 MTBF/MTTR/OEE。

**实现映射：** `capabilities.json` 的 handler `summary` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/capabilities` — 接口实现清单

**输入示例：**无请求体

**成功返回 data：** { implemented: Contract[], reserved: Contract[] }

**行为、权限及校验：** 与路由使用同一注册表；每个预留接口都有输入示例、依赖和验收要求。

**实现映射：** `capabilities.json` 的 handler `capabilities` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/devices` — 设备查询

**输入示例：**
```json
{
  "query": "可选 q：按编码、名称、位置包含查询"
}
```


**成功返回 data：** Device[]（含 derivedStatus）

**行为、权限及校验：** derivedStatus 由未关闭工单推导：reported/repairing/active；不代表实时采集到的物理运行状态。

**实现映射：** `capabilities.json` 的 handler `devices` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/devices` — 新增设备

**输入示例：**
```json
{
  "code": "EQ-004",
  "name": "演示设备",
  "location": "一号车间",
  "category": "加工设备"
}
```


**成功返回 data：** Device：id/code/name/location/category/status/version/createdAt

**行为、权限及校验：** 仅管理员。code 1–40、name/location 1–80、category 1–40 字符；编码忽略大小写唯一。

**实现映射：** `capabilities.json` 的 handler `createDevice` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/devices/{id}/history` — 设备履历

**输入示例：**无请求体

**成功返回 data：** { device, orders: Order[], tasks: Task[], events: AuditEvent[] }

**行为、权限及校验：** 只查询该设备关联的工单、任务、计划事件；没有文档版本、调拨或报废履历。

**实现映射：** `capabilities.json` 的 handler `deviceHistory` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/orders` — 维修工单列表

**输入示例：**
```json
{
  "query": "可选 status：工单状态；可选 deviceId：设备 ID"
}
```


**成功返回 data：** Order[]

**行为、权限及校验：** 状态合法值见状态机；列表无分页，仅适合少量演示数据。

**实现映射：** `capabilities.json` 的 handler `orders` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/orders/{id}` — 工单详情

**输入示例：**无请求体

**成功返回 data：** Order（含 completions/reviews/history）

**行为、权限及校验：** id 不存在返回 404；所有完工和验收尝试保留。

**实现映射：** `capabilities.json` 的 handler `order` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/orders` — 报修

**输入示例：**
```json
{
  "deviceId": "<设备 ID>",
  "title": "运行异响",
  "description": "演示故障描述",
  "priority": "normal"
}
```


**成功返回 data：** Order：初始 pending_assignment、version=1

**行为、权限及校验：** 标题 1–100、描述 1–1000 字符；priority=normal/urgent；允许各演示角色报修。重复 POST 会创建新工单，生产请求幂等尚未实现。

**实现映射：** `capabilities.json` 的 handler `createOrder` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/orders/{id}/assign` — 派单/接单前改派

**输入示例：**
```json
{
  "version": 1,
  "assigneeId": "technician"
}
```


**成功返回 data：** Order：pending_acceptance

**行为、权限及校验：** 管理员；只允许 pending_assignment/pending_acceptance，目标须为维修人员。处理中转单另待实现。

**实现映射：** `capabilities.json` 的 handler `assignOrder` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/orders/{id}/accept` — 接单

**输入示例：**
```json
{
  "version": 2
}
```


**成功返回 data：** Order：in_progress

**行为、权限及校验：** 仅被指派人或管理员；须为 pending_acceptance。

**实现映射：** `capabilities.json` 的 handler `acceptOrder` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/orders/{id}/reject` — 拒单

**输入示例：**
```json
{
  "version": 2,
  "reason": "需要重新安排人员"
}
```


**成功返回 data：** Order：pending_assignment，清空 assigneeId

**行为、权限及校验：** 仅被指派人或管理员；须为 pending_acceptance；原因 1–500 字符。

**实现映射：** `capabilities.json` 的 handler `rejectOrder` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/orders/{id}/complete` — 提交完工

**输入示例：**
```json
{
  "version": 3,
  "summary": "检查连接件并记录结果",
  "workMinutes": 30,
  "materials": "密封圈 1 个（仅文字记录）",
  "safetyConfirmed": true
}
```


**成功返回 data：** Order：pending_review；新增 Completion

**行为、权限及校验：** 仅执行人或管理员；须为 in_progress；summary 1–1000、materials 可选 0–500；工时 1–10080 整数分钟；安全勾选不等于安全证据。

**实现映射：** `capabilities.json` 的 handler `completeOrder` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/orders/{id}/review` — 验收/退回

**输入示例：**
```json
{
  "version": 4,
  "decision": "accept",
  "note": "演示验收通过"
}
```


**成功返回 data：** Order：accept→closed；return→in_progress

**行为、权限及校验：** 管理员或原报修人；须为 pending_review；note 1–500；完工和退回记录均保留。未实现多人验收、签名和职责分离。

**实现映射：** `capabilities.json` 的 handler `reviewOrder` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/orders/{id}/cancel` — 取消工单

**输入示例：**
```json
{
  "version": 1,
  "reason": "重复报修"
}
```


**成功返回 data：** Order：cancelled

**行为、权限及校验：** 报修人仅能取消 pending_assignment；管理员还可取消 pending_acceptance/in_progress；待验收及终态不能取消。

**实现映射：** `capabilities.json` 的 handler `cancelOrder` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/plans` — 维保计划列表

**输入示例：**无请求体

**成功返回 data：** Plan[]

**行为、权限及校验：** 保存周期和检查项；暂停只影响之后的生成，不撤销既有任务。

**实现映射：** `capabilities.json` 的 handler `plans` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/plans` — 新增周期计划

**输入示例：**
```json
{
  "deviceId": "<设备 ID>",
  "name": "每日点检",
  "type": "inspection",
  "intervalDays": 1,
  "nextDueDate": "2026-09-27",
  "assigneeId": "inspector",
  "checklist": [
    "油位",
    "运行声音"
  ]
}
```


**成功返回 data：** Plan：active=true、version=1

**行为、权限及校验：** 仅管理员；type=inspection/maintenance；周期 1–3650 天；日期有效且为 2000–2100；检查项 1–20 个、各 1–100 字且不重复；执行人为 technician/inspector。

**实现映射：** `capabilities.json` 的 handler `createPlan` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### PATCH `/api/plans/{id}` — 暂停/恢复计划

**输入示例：**
```json
{
  "version": 1,
  "active": false
}
```


**成功返回 data：** Plan

**行为、权限及校验：** 仅管理员；只更新 active；模板、周期、指派及日期变更尚未实现。

**实现映射：** `capabilities.json` 的 handler `togglePlan` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/plans/generate` — 生成到期任务

**输入示例：**
```json
{
  "throughDate": "2026-09-27"
}
```


**成功返回 data：** { generated: Task[], count, throughDate }

**行为、权限及校验：** 仅管理员；截止日不得晚于上海今天；按计划原到期日逐周期补齐，每次最多 100 条；计划+日期去重；推进下次日期；无后台定时器。

**实现映射：** `capabilities.json` 的 handler `generateTasks` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/tasks` — 维保任务列表

**输入示例：**
```json
{
  "query": "可选 status：pending/completed"
}
```


**成功返回 data：** Task[]

**行为、权限及校验：** 任务携带生成时检查项快照；同时列出保养和点检。

**实现映射：** `capabilities.json` 的 handler `tasks` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/tasks/{id}` — 任务详情

**输入示例：**无请求体

**成功返回 data：** Task（含 checklist/results/history/linkedOrderId）

**行为、权限及校验：** 只提供两态 pending/completed；未实现接单、复核或撤销。

**实现映射：** `capabilities.json` 的 handler `task` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### POST `/api/tasks/{id}/complete` — 执行维保/异常转维修

**输入示例：**
```json
{
  "version": 1,
  "results": [
    {
      "itemId": "<task.checklist 中的 ID>",
      "verdict": "abnormal",
      "reading": "演示读数",
      "remark": "演示异常：有异响"
    }
  ],
  "note": "作业备注"
}
```


**成功返回 data：** Task：completed；异常时 linkedOrderId 指向新 Order

**行为、权限及校验：** 仅指派人或管理员；必须提交所有项目且不重复；verdict=normal/abnormal；异常 remark 必填；读数为文字，无阈值判定；所有异常汇总为一个工单，与任务同次原子保存。

**实现映射：** `capabilities.json` 的 handler `completeTask` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/messages` — 站内消息

**输入示例：**无请求体

**成功返回 data：** Message[]

**行为、权限及校验：** 仅当前演示角色的消息；非真实短信、微信或 APP 推送。

**实现映射：** `capabilities.json` 的 handler `messages` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### PATCH `/api/messages/{id}/read` — 消息已读

**输入示例：**
```json
{}
```


**成功返回 data：** Message：read=true

**行为、权限及校验：** 只可修改自己的消息；重复标记已读无副作用，不使用 version。

**实现映射：** `capabilities.json` 的 handler `readMessage` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

### GET `/api/audit` — 演示业务事件

**输入示例：**无请求体

**成功返回 data：** AuditEvent[]

**行为、权限及校验：** 仅管理员；记录业务动作而非所有读写、登录或修改前后镜像；未防篡改。

**实现映射：** `capabilities.json` 的 handler `audit` 由 `ApiServlet.java` 分派；业务规则位于 `DemoDomain.java`（能力清单由 Servlet 直接返回）。

## 预留接口（调用会返回 501）

下面的请求体是设计输入示例；服务端对预留接口不执行业务校验和持久化，直接返回 `501 NOT_IMPLEMENTED`。实际开发时需要与业务方和技术负责人确认字段、权限、幂等策略与错误码。

### AUTH · POST `/api/auth/login` — 真实账号与认证

**范围：** 账号注册、密码找回、验证码、第三方登录、账号停用、实名认证、登录日志。

**输入草案：**
```json
{
  "account": "<账号>",
  "credential": "<密码或授权票据>",
  "provider": "password"
}
```


**目标返回（尚未实现）：** { token, expiresAt, user }

**前置依赖：**
- 企业身份源及开放注册政策
- 令牌与会话方案、密码和验证码策略

**验收条件：**
- 错误凭据及过期会话被拒绝
- 停用账号不能继续调用接口，登录行为可审计

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### ACCESS · PUT `/api/access/roles/{id}` — 组织与权限配置

**范围：** 部门/班组维护、组织树、角色自定义、菜单/操作/字段/行级权限、角色复制及权限审计。

**输入草案：**
```json
{
  "version": 1,
  "name": "<角色>",
  "menus": [],
  "operations": [],
  "dataScope": {
    "departmentIds": []
  },
  "fields": []
}
```


**目标返回（尚未实现）：** { role, version }

**前置依赖：**
- 组织主数据及一人一责的授权规则
- 真实认证和企业/外协数据边界

**验收条件：**
- 扫码、导出、附件、分享都服从权限
- 不同部门/企业/外协越权请求返回 403，变更可追溯

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### SETTINGS · PUT `/api/settings` — 业务参数与移动表单

**范围：** 故障字典、周期规则、公告、移动字段及布局、消息偏好设置；配置升级与兼容。

**输入草案：**
```json
{
  "version": 1,
  "dictionaries": {},
  "messageTemplates": [],
  "mobileForms": []
}
```


**目标返回（尚未实现）：** { settings, version }

**前置依赖：**
- 参数归属、编辑权限及版本策略
- 表单字段类型、校验及既有记录兼容规则

**验收条件：**
- 配置修改不破坏历史记录
- 非法字段/规则被拒绝，配置有版本和审批记录

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### ASSET · POST `/api/devices/{id}/lifecycle` — 资产档案与生命周期

**范围：** 设备编辑、Excel 导入导出、附件、标签打印、原值折旧、验收调拨借用封存报废。

**输入草案：**
```json
{
  "version": 1,
  "action": "transfer",
  "targetLocation": "<位置>",
  "reason": "<原因>",
  "attachments": []
}
```


**目标返回（尚未实现）：** { requestId, status, deviceVersion }

**前置依赖：**
- 资产数据与财务口径、审批人
- 文件服务、标签规格、设备状态流转

**验收条件：**
- 导入有错误行报告和回滚边界
- 审批前后状态正确，履历保留前后值，报废设备不生成新任务

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### COMPLIANCE · POST `/api/compliance/inspections` — 特种设备与证书

**范围：** 检定校验、安全检查、人员资质与到期提醒。

**输入草案：**
```json
{
  "deviceId": "<ID>",
  "dueDate": "<日期>",
  "certificateId": "<ID>",
  "standardVersion": "<适用规则版本>"
}
```


**目标返回（尚未实现）：** { inspectionId, reminders }

**前置依赖：**
- 业务及安全负责人确认适用规则
- 合格机构、证书数据源、提前提醒规则

**验收条件：**
- 适用期限及资质限制正确
- 过期/不合格的处置流程和证据可追溯

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### BOM · PUT `/api/devices/{id}/bom` — 设备关系与 BOM

**范围：** 整机部件零件、父子/替代关系、备件关联。

**输入草案：**
```json
{
  "version": 1,
  "children": [
    {
      "deviceId": "<ID>",
      "quantity": 1
    }
  ],
  "sparePartIds": []
}
```


**目标返回（尚未实现）：** { tree, version }

**前置依赖：**
- 设备及备件编码体系
- 版本、替代件及数量单位规则

**验收条件：**
- 环路与重复关系被拒绝
- 变更保留版本，领料可按适用 BOM 查件

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### IOT · POST `/api/iot/telemetry` — 设备数据与 IoT 告警

**范围：** MQTT/OPC UA/Modbus 等网关接入、运行状态、时序数据、异常阈值及自动报修。

**输入草案：**
```json
{
  "deviceId": "<ID>",
  "timestamp": "<ISO 时间>",
  "metrics": {
    "temperature": 0
  },
  "eventId": "<源唯一事件>"
}
```


**目标返回（尚未实现）：** { accepted, duplicate, alarmIds }

**前置依赖：**
- 网关及点位表、采样频率、单位
- 时钟、断连、重复/乱序与设备映射规则

**验收条件：**
- 重复与乱序数据处理可验证
- 告警抑制/恢复准确，自动工单不重复，物理状态与业务状态区分

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### SCHEDULE · POST `/api/scheduling/preview` — 高级计划与排程

**范围：** 自动定时生成、运行时长/产量/状态触发、甘特图、负载、批量调整和路线规划。

**输入草案：**
```json
{
  "planIds": [],
  "horizon": {
    "from": "<日期>",
    "to": "<日期>"
  },
  "triggers": [
    "runtime"
  ],
  "resourceIds": []
}
```


**目标返回（尚未实现）：** { proposedTasks, conflicts, explanation }

**前置依赖：**
- 生产日历、运行/产量数据、人员技能
- 计划变更、补任务、停用和调度规则

**验收条件：**
- 跨时区/周期边界与重试不重复
- 排程冲突可解释，暂停/变更对既有任务的影响明确

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### WORKFLOW · POST `/api/workflows/definitions` — 派单、SLA 与流程引擎

**范围：** 自动派单、抢单、处理中转单/挂起、多节点审批、委托、SLA 计时和超时升级。

**输入草案：**
```json
{
  "name": "<流程>",
  "version": 1,
  "nodes": [],
  "rules": {
    "assignment": "skill",
    "timeoutMinutes": 60
  }
}
```


**目标返回（尚未实现）：** { definitionId, version, validation }

**前置依赖：**
- 技能及工作日历、流程节点权限
- 计时暂停、升级责任人、流程版本与在途兼容规则

**验收条件：**
- 并发抢单唯一成功，非法流转失败
- 超时边界及审批委托可验证，流程修改不破坏在途单据

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### COST · POST `/api/analytics/calculate` — 费用、绩效及高级指标

**范围：** 人工耗材外协费用、停机时间、MTBF/MTTR/OEE、趋势/排名、满意度及绩效。

**输入草案：**
```json
{
  "period": {
    "from": "<日期>",
    "to": "<日期>"
  },
  "metrics": [
    "MTBF",
    "MTTR",
    "OEE"
  ],
  "dimensions": [
    "department"
  ]
}
```


**目标返回（尚未实现）：** { metrics, missingInputs, sourceReferences }

**前置依赖：**
- 指标字典、生产时长/产量/良品及故障数据
- 费率、成本归集、统计排除规则和验收人

**验收条件：**
- 指标能逐项回查原始数据
- 缺少运行/生产数据时明确不可计算，不返回伪造的零值

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### KNOWLEDGE · GET `/api/knowledge/search` — 故障知识与智能检索

**范围：** 故障分类、案例、FAQ、应急方案、故障树、帕累托及类似案例推荐。

**输入草案：**
```json
{
  "query": {
    "q": "<故障代码或关键词>",
    "deviceModel": "<型号>"
  }
}
```


**目标返回（尚未实现）：** { items, total, sourceReferences }

**前置依赖：**
- 审核后的知识来源、权限和内容责任人
- 版本、适用设备型号、安全内容审核规则

**验收条件：**
- 检索结果有来源及适用范围
- 受限资料不可泄露；推荐错误或无证据时有明确处理路径

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### INVENTORY · POST `/api/inventory/transactions` — 备件、领退料与盘点

**范围：** 备件分类、多仓库/库位、占用、领退料、以旧换新、盘点盘盈亏、库存预警。

**输入草案：**
```json
{
  "requestId": "<唯一请求>",
  "kind": "issue",
  "warehouseId": "<ID>",
  "orderId": "<ID>",
  "lines": [
    {
      "partId": "<ID>",
      "quantity": 1,
      "unit": "<单位>"
    }
  ]
}
```


**目标返回（尚未实现）：** { transactionId, balances, ledgerReferences }

**前置依赖：**
- 仓库及备件主数据、单位和唯一入账凭据
- 库存/工单耗材的扣减时点、并发锁及成本规则

**验收条件：**
- 同一请求只入账一次，库存与流水可对账
- 不足库存与并发领料正确处理，退料不重复增加库存

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### PROCUREMENT · POST `/api/procurement/requests` — 采购及供应商合同

**范围：** 采购申请审批、供应商合同、价格趋势、交货质量、急采及电子发票。

**输入草案：**
```json
{
  "supplierId": "<ID>",
  "reason": "<原因>",
  "lines": [
    {
      "partId": "<ID>",
      "quantity": 1
    }
  ],
  "contractId": "<ID>"
}
```


**目标返回（尚未实现）：** { requestId, approvalStatus }

**前置依赖：**
- ERP/采购主责、供应商编码、合同规则
- 税务发票接口、验收及结算责任人

**验收条件：**
- 申请、收货、库存、结算可对账
- 供应商仅能查看本方单据，重复同步不重复入账

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### REPORT · POST `/api/reports/export` — 报表、打印和大屏

**范围：** 标准报表、可视化图表、大屏、自定义报表、BI 钻取、定时生成、PDF/Excel/打印模板。

**输入草案：**
```json
{
  "reportType": "<类型>",
  "filters": {},
  "format": "pdf",
  "templateId": "<ID>"
}
```


**目标返回（尚未实现）：** { jobId, status, downloadUrl, expiresAt }

**前置依赖：**
- 数据口径及字段权限
- 文件生成器、模板、性能容量和下载有效期

**验收条件：**
- 导出与页面查询口径一致且无越权字段
- 大数据异步导出可取消，模板及打印结果人工验收

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### INTEGRATION · POST `/api/integrations/jobs` — ERP/OA/MES/HR 接口

**范围：** 跨系统主数据、单据同步、接口配置及同步任务。

**输入草案：**
```json
{
  "system": "ERP",
  "direction": "import",
  "entity": "equipment",
  "cursor": "<游标>",
  "requestId": "<唯一请求>"
}
```


**目标返回（尚未实现）：** { jobId, accepted, reconciliationUrl }

**前置依赖：**
- 逐系统接口文档、沙箱、姓名及交付承诺
- 数据主责、编码映射、游标、错误补偿规则

**验收条件：**
- 失败重试及重复请求不重复建账
- 双边可对账，失败记录可定位并按授权补偿

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### OUTSOURCE · POST `/api/outsource/orders` — 委外与维保商协作

**范围：** 取件维修送回、报价、外委费用、验收评分、合同和结算。

**输入草案：**
```json
{
  "orderId": "<ID>",
  "supplierId": "<ID>",
  "contractId": "<ID>",
  "expectedReturnDate": "<日期>"
}
```


**目标返回（尚未实现）：** { externalOrderId, status }

**前置依赖：**
- 外协身份、数据隔离和服务合同
- 内部与外部状态映射、付款与验收权责

**验收条件：**
- 外协仅操作所属任务
- 内外工单状态一致且报价、发票、验收记录可关联

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### FILE · POST `/api/files/uploads` — 照片、视频与技术资料

**范围：** 作业前后照片、视频、语音、图纸手册、标注、水印、上传及下载。

**输入草案：**
```json
{
  "entityType": "order",
  "entityId": "<ID>",
  "filename": "<名称>",
  "contentType": "image/jpeg",
  "size": 1
}
```


**目标返回（尚未实现）：** { uploadId, uploadUrl, expiresAt }

**前置依赖：**
- 文件存储与访问鉴权、大小和类型限制
- 保留期、扫描、失败重传及附件备份

**验收条件：**
- 越权上传/下载失败
- 伪造类型、大文件、重复与中断上传可验证，附件随数据恢复

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### LABEL · POST `/api/labels/generate` — 二维码/NFC/RFID 与分享

**范围：** 唯一标签、打印样式、扫码身份跳转、NFC/RFID 及微信分享。

**输入草案：**
```json
{
  "entityType": "device",
  "entityIds": [],
  "format": "qr",
  "templateId": "<ID>"
}
```


**目标返回（尚未实现）：** { labels, printJobId }

**前置依赖：**
- 标签编码/打印机和端硬件能力
- 链接有效期、登录跳转及共享权限

**验收条件：**
- 标签可实物扫码并找到正确设备
- 分享/标签不能绕过身份与数据权限，作废标签失效

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### MOBILE · POST `/api/mobile/session` — 微信/支付宝与原生 APP

**范围：** 真实小程序、原生 APP、专属工作台、快捷入口、版本更新、平板适配。

**输入草案：**
```json
{
  "platform": "wechat",
  "authorizationCode": "<授权码>",
  "appVersion": "<版本>"
}
```


**目标返回（尚未实现）：** { session, permittedFeatures, updatePolicy }

**前置依赖：**
- 平台主体/账号/域名、真机和发布流程
- 登录、通知权限、应用版本及兼容矩阵

**验收条件：**
- 在批准的机型、系统、平台版本验收
- 平台授权和深链打开正常，低版本及权限拒绝有处理路径

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### OFFLINE · POST `/api/offline/sync` — 离线同步

**范围：** 离线任务包、加密存储、附件同步、重复/冲突和账号权限变化。当前用户确认在线作业，后续按必要性立项。

**输入草案：**
```json
{
  "clientId": "<ID>",
  "baseVersion": 1,
  "operations": [
    {
      "operationId": "<唯一ID>",
      "entityId": "<ID>",
      "payload": {}
    }
  ]
}
```


**目标返回（尚未实现）：** { acknowledged, conflicts, serverVersion }

**前置依赖：**
- 实体版本、操作幂等和冲突裁决规则
- 端侧密钥、安全擦除及断网验收环境

**验收条件：**
- 重连后数据与附件无重复和遗漏
- 并发冲突、退出登录及撤权后离线数据处理可验证

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### SOP · POST `/api/sop/executions` — 标准化安全作业

**范围：** SOP 顺序、安全确认、断电挂牌、工具核对、润滑五定及强制拍照。

**输入草案：**
```json
{
  "taskId": "<ID>",
  "templateVersion": "<版本>",
  "steps": [
    {
      "stepId": "<ID>",
      "checked": true,
      "evidenceIds": []
    }
  ]
}
```


**目标返回（尚未实现）：** { executionId, nextStep, status }

**前置依赖：**
- 经业务及安全负责人批准的作业标准
- 不可跳步策略、证据要求及异常处置

**验收条件：**
- 未完成必需步骤不能完工
- 标准版本与每步证据保留；软件记录的责任边界确认

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### SIGN · POST `/api/signatures` — 电子签名、签章与归档

**范围：** 手写签名、双签、多节点验收、电子签章、公章 PDF 归档。

**输入草案：**
```json
{
  "entityType": "order",
  "entityId": "<ID>",
  "version": 1,
  "signerId": "<ID>",
  "provider": "<提供方>",
  "evidenceId": "<ID>"
}
```


**目标返回（尚未实现）：** { signatureId, documentHash, archivedUrl }

**前置依赖：**
- 身份确认、签署权限和适用要求
- 签章服务、原文摘要、存证与保留规则

**验收条件：**
- 签署对应的单据版本可证明且不可被后改冒用
- 签署拒绝、撤回、归档及权限有验收记录

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### LOCATION · POST `/api/location/checkins` — 定位、导航与轨迹

**范围：** GPS/NFC 打卡、GIS、蓝牙 Beacon、巡检路线、库位/楼层导航、人员轨迹。

**输入草案：**
```json
{
  "taskId": "<ID>",
  "deviceId": "<ID>",
  "position": {
    "latitude": 0,
    "longitude": 0
  },
  "proof": "<扫码或NFC证据>"
}
```


**目标返回（尚未实现）：** { checkinId, matched, accuracy }

**前置依赖：**
- 地图/室内设施、定位精度和有效区域
- 知情授权、查看权限、保留期及伪造检测

**验收条件：**
- 定位拒绝及低精度有处理路径
- 实地到位判断可验收，轨迹仅授权人员可见

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### OCR · POST `/api/ocr/readings` — 仪表 OCR 与采集

**范围：** 仪表盘识别、人工复核、阈值标红和照片水印。

**输入草案：**
```json
{
  "taskId": "<ID>",
  "itemId": "<ID>",
  "fileId": "<ID>",
  "meterType": "<型号>"
}
```


**目标返回（尚未实现）：** { value, unit, confidence, requiresConfirmation }

**前置依赖：**
- 仪表类型、样本、阈值和允许误差
- 识别服务及人工确认规则

**验收条件：**
- 在批准样本上达到业务确定的误差范围
- 低置信度转人工，原图与最终确认读数关联

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### TOOL · POST `/api/tools/loans` — 工具借还与校准

**范围：** 工具柜、借还、超时提醒、个人工具箱和校准期限。

**输入草案：**
```json
{
  "toolId": "<ID>",
  "borrowerId": "<ID>",
  "dueAt": "<时间>",
  "taskId": "<ID>"
}
```


**目标返回（尚未实现）：** { loanId, status, calibrationValid }

**前置依赖：**
- 工具编码、保管人、柜体接口和证书
- 借还授权、逾期及失效工具限制

**验收条件：**
- 重复借出被拒绝，归还可核对
- 过期校准工具不能按批准规则投入作业

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### COLLAB · POST `/api/collaboration/rooms` — 通讯与专家协作

**范围：** 工单评论/@、临时讨论组、视频/语音对讲、屏幕标注、专家技能库和人员联系。

**输入草案：**
```json
{
  "orderId": "<ID>",
  "participantIds": [],
  "mode": "video"
}
```


**目标返回（尚未实现）：** { roomId, joinUrl, expiresAt }

**前置依赖：**
- 通讯提供方、企业目录及专家技能
- 参与权限、网络能力、录制和保留政策

**验收条件：**
- 非参与者不能加入或读记录
- 网络中断可恢复，协作内容与工单关联

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### AR3D · GET `/api/models/{id}` — AR 与 3D 模型

**范围：** 设备爆炸图、AR 步骤叠加、AR 眼镜远程协作。

**输入草案：**
```json
{
  "query": {
    "deviceId": "<ID>",
    "variant": "exploded"
  }
}
```


**目标返回（尚未实现）：** { modelUrl, version, parts, instructions }

**前置依赖：**
- 合法模型资产、设备部件映射及版本
- 端渲染/AR 硬件、定位精度与现场验证

**验收条件：**
- 模型与实物版本匹配
- 目标机型可加载，步骤位置和部件标识经现场确认

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### TRAINING · POST `/api/training/enrollments` — 培训、证书与个人绩效

**范围：** 维修视频学习、考试认证、技能矩阵、计件工资、奖惩、个人提升。

**输入草案：**
```json
{
  "courseId": "<ID>",
  "employeeId": "<ID>",
  "certificationId": "<ID>"
}
```


**目标返回（尚未实现）：** { enrollmentId, progress, qualificationStatus }

**前置依赖：**
- 培训内容、考试题库、证书及 HR 主责
- 薪酬/奖惩口径、可见范围与批准流程

**验收条件：**
- 学习考试与有效资质关联
- 工资和评价可核对原始规则，敏感信息严格限制访问

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### AI · POST `/api/intelligence/jobs` — 预测维护、孪生与语音

**范围：** 故障概率、预知维修、数字孪生温度/振动映射、维修模拟、语音指令及记录。

**输入草案：**
```json
{
  "kind": "prediction",
  "deviceId": "<ID>",
  "datasetVersion": "<版本>",
  "modelVersion": "<版本>",
  "inputs": {}
}
```


**目标返回（尚未实现）：** { jobId, result, evidence, uncertainty }

**前置依赖：**
- 历史/实时数据和模型资产、业务效果基线
- 专项验证、误报漏报、人工确认与回退策略

**验收条件：**
- 输出附数据/模型来源及不确定性
- 按批准指标验收，语音/AI 不能未经授权自动产生关键业务动作

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### LOWCODE · POST `/api/customizations` — 低代码与国际化

**范围：** 拖拽表单、流程/报表配置、多语言和发布回滚。

**输入草案：**
```json
{
  "version": 1,
  "forms": [],
  "workflows": [],
  "reports": [],
  "locale": "zh-CN"
}
```


**目标返回（尚未实现）：** { customizationId, validation, publishedVersion }

**前置依赖：**
- 字段/流程元模型、权限、插件边界
- 翻译、时区单位、配置迁移及兼容政策

**验收条件：**
- 恶意配置和权限绕过被拒绝
- 历史单据兼容，发布回滚和多语言格式可验证

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### SECURITY · POST `/api/security/audit-exports` — 生产安全与审计回溯

**范围：** HTTPS、端存储加密、全量操作与前后值、版本回溯、防篡改及审计导出。

**输入草案：**
```json
{
  "from": "<时间>",
  "to": "<时间>",
  "entityType": "<类型>",
  "reason": "<原因>"
}
```


**目标返回（尚未实现）：** { jobId, signedDigest, archiveUrl }

**前置依赖：**
- 安全标准、密钥管理、保留期及审计权限
- 日志链路与加密/解密恢复方案

**验收条件：**
- 密钥轮换、越权和审计完整性可验证
- 恢复/回溯保留历史且经过授权，不能通过改本地文件冒充历史

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### BACKUP · POST `/api/backups/jobs` — 备份恢复与部署

**范围：** 每日备份/保留30天、手动恢复、容灾、生产监控、多种云部署。

**输入草案：**
```json
{
  "kind": "backup",
  "scope": "database-and-files",
  "restorePointId": null
}
```


**目标返回（尚未实现）：** { jobId, status, manifest, verification }

**前置依赖：**
- 批准的恢复点/时间目标、存储及运行负责人
- 数据库和附件一致备份、恢复权限及切换方案

**验收条件：**
- 真实执行恢复演练并对账
- 备份失败能告警，批准的恢复目标满足；导出 JSON 不算完整容灾

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。

### PUSH · POST `/api/notifications/deliveries` — 多渠道消息与告警升级

**范围：** 微信/支付宝服务通知、短信、APP Push、偏好时段、多级提醒及库存/证书告警。

**输入草案：**
```json
{
  "eventId": "<唯一事件>",
  "recipientIds": [],
  "channels": [
    "wechat"
  ],
  "templateId": "<ID>",
  "variables": {}
}
```


**目标返回（尚未实现）：** { deliveryId, channelResults, retryAt }

**前置依赖：**
- 平台模板、许可、账号和渠道资费
- 事件去重、重试、回执、静默时段及升级规则

**验收条件：**
- 发送失败及限流可追踪并补发
- 关键待办不依赖单次推送成功，不能承诺无遗漏触达

**当前实际响应：** `501 NOT_IMPLEMENTED`；该接口没有任何业务副作用。
