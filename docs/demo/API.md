# Demo API 与本地后端契约

当前 77 条路由。原33个预留入口已有本地处理器；完整原需求仍按局部实现与外部条件区分，不以路由存在代表全功能完成。

`GET /api/health` 额外返回 `backendFingerprint`，由当前Java主类和注册表计算SHA256；一键启动核对它与本次构建相同，避免纯后端更新误用旧服务。

原27个演示接口继续兼容 `X-Demo-Actor`。新功能除登录外必须使用 `Authorization: Bearer <token>`；登录密码只在首次生成的 `<数据文件名>.credentials.json` 中提供，不在webapps或Git中。接口不接收外部账户凭据。

默认上下文 `/equipment-maintenance-demo`。成功 `{data:...}`；错误 `{error:{code,message,details}}`。外部服务无配置时503 `PROVIDER_NOT_CONFIGURED`，details包含本地阻塞任务ID；PDF/NFC等不支持格式422。OCR本地返回202人工确认待办。所有请求限制本机IP和本机Host。

输入ID须先通过 `/api/state`（旧演示接口）及带令牌的 `/api/catalog` 查询。更新携带version；库存requestId/遥测eventId/通知eventId/离线operationId须稳定且同ID同内容。文件上传是申请→PUT原始字节→GET授权下载。

业务校验400；缺少/无效会话401；越权403；资源缺失404；版本/状态/库存冲突409；过期上传/下载/房间410；超大请求413；类型415；不支持本地格式422；外部未配置503。详见[本地后端扩展说明](本地后端扩展说明.md)。

### GET `/api/health` — 服务状态

**实现处理器：** `health`

**输入示例：**
```json
null
```

**返回：** { mode: "demo", runtime: "java-servlet", version: "0.2.0", persistence: "json-file", today }

**本地规则：** 只说明本地服务可响应，不代表生产健康探针或容灾可用。

### GET `/api/actors` — 演示身份列表

**实现处理器：** `actors`

**输入示例：**
```json
null
```

**返回：** Actor[]：id/name/role/department

**本地规则：** 固定四个虚构角色。X-Demo-Actor 只用于流程演示，不能认证身份。

### GET `/api/state` — 演示页面快照

**实现处理器：** `snapshot`

**输入示例：**
```json
null
```

**返回：** { actors, devices, orders, plans, tasks, messages, audit, summary }

**本地规则：** 全量读取演示数据；消息只返回当前角色的消息，审计仅管理员返回。设备和业务记录没有企业数据隔离。

### GET `/api/summary` — 基础统计

**实现处理器：** `summary`

**输入示例：**
```json
null
```

**返回：** { devices, openOrders, pendingReview, pendingTasks, overdueTasks, closedOrders, today }

**本地规则：** 直接统计当前记录；超期指未完成任务的 scheduledDate 早于上海日期。不计算 MTBF/MTTR/OEE。

### GET `/api/capabilities` — 接口实现清单

**实现处理器：** `capabilities`

**输入示例：**
```json
null
```

**返回：** { implemented: Contract[], reserved: Contract[] }

**本地规则：** 与路由使用同一注册表；每个预留接口都有输入示例、依赖和验收要求。

### GET `/api/devices` — 设备查询

**实现处理器：** `devices`

**输入示例：**
```json
{
  "query": "可选 q：按编码、名称、位置包含查询"
}
```

**返回：** Device[]（含 derivedStatus）

**本地规则：** derivedStatus 由未关闭工单推导：reported/repairing/active；不代表实时采集到的物理运行状态。

### POST `/api/devices` — 新增设备

**实现处理器：** `createDevice`

**输入示例：**
```json
{
  "code": "EQ-004",
  "name": "演示设备",
  "location": "一号车间",
  "category": "加工设备"
}
```

**返回：** Device：id/code/name/location/category/status/version/createdAt

**本地规则：** 仅管理员。code 1–40、name/location 1–80、category 1–40 字符；编码忽略大小写唯一。

### GET `/api/devices/{id}/history` — 设备履历

**实现处理器：** `deviceHistory`

**输入示例：**
```json
null
```

**返回：** { device, orders: Order[], tasks: Task[], events: AuditEvent[] }

**本地规则：** 只查询该设备关联的工单、任务、计划事件；没有文档版本、调拨或报废履历。

### GET `/api/orders` — 维修工单列表

**实现处理器：** `orders`

**输入示例：**
```json
{
  "query": "可选 status：工单状态；可选 deviceId：设备 ID"
}
```

**返回：** Order[]

**本地规则：** 状态合法值见状态机；列表无分页，仅适合少量演示数据。

### GET `/api/orders/{id}` — 工单详情

**实现处理器：** `order`

**输入示例：**
```json
null
```

**返回：** Order（含 completions/reviews/history）

**本地规则：** id 不存在返回 404；所有完工和验收尝试保留。

### POST `/api/orders` — 报修

**实现处理器：** `createOrder`

**输入示例：**
```json
{
  "deviceId": "<设备 ID>",
  "title": "运行异响",
  "description": "演示故障描述",
  "priority": "normal"
}
```

**返回：** Order：初始 pending_assignment、version=1

**本地规则：** 标题 1–100、描述 1–1000 字符；priority=normal/urgent；允许各演示角色报修。重复 POST 会创建新工单，生产请求幂等尚未实现。

### POST `/api/orders/{id}/assign` — 派单/接单前改派

**实现处理器：** `assignOrder`

**输入示例：**
```json
{
  "version": 1,
  "assigneeId": "technician"
}
```

**返回：** Order：pending_acceptance

**本地规则：** 管理员；只允许 pending_assignment/pending_acceptance，目标须为维修人员。处理中转单另待实现。

### POST `/api/orders/{id}/accept` — 接单

**实现处理器：** `acceptOrder`

**输入示例：**
```json
{
  "version": 2
}
```

**返回：** Order：in_progress

**本地规则：** 仅被指派人或管理员；须为 pending_acceptance。

### POST `/api/orders/{id}/reject` — 拒单

**实现处理器：** `rejectOrder`

**输入示例：**
```json
{
  "version": 2,
  "reason": "需要重新安排人员"
}
```

**返回：** Order：pending_assignment，清空 assigneeId

**本地规则：** 仅被指派人或管理员；须为 pending_acceptance；原因 1–500 字符。

### POST `/api/orders/{id}/complete` — 提交完工

**实现处理器：** `completeOrder`

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

**返回：** Order：pending_review；新增 Completion

**本地规则：** 仅执行人或管理员；须为 in_progress；summary 1–1000、materials 可选 0–500；工时 1–10080 整数分钟；安全勾选不等于安全证据。

### POST `/api/orders/{id}/review` — 验收/退回

**实现处理器：** `reviewOrder`

**输入示例：**
```json
{
  "version": 4,
  "decision": "accept",
  "note": "演示验收通过"
}
```

**返回：** Order：accept→closed；return→in_progress

**本地规则：** 管理员或原报修人；须为 pending_review；note 1–500；完工和退回记录均保留。未实现多人验收、签名和职责分离。

### POST `/api/orders/{id}/cancel` — 取消工单

**实现处理器：** `cancelOrder`

**输入示例：**
```json
{
  "version": 1,
  "reason": "重复报修"
}
```

**返回：** Order：cancelled

**本地规则：** 报修人仅能取消 pending_assignment；管理员还可取消 pending_acceptance/in_progress；待验收及终态不能取消。

### GET `/api/plans` — 维保计划列表

**实现处理器：** `plans`

**输入示例：**
```json
null
```

**返回：** Plan[]

**本地规则：** 保存周期和检查项；暂停只影响之后的生成，不撤销既有任务。

### POST `/api/plans` — 新增周期计划

**实现处理器：** `createPlan`

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

**返回：** Plan：active=true、version=1

**本地规则：** 仅管理员；type=inspection/maintenance；周期 1–3650 天；日期有效且为 2000–2100；检查项 1–20 个、各 1–100 字且不重复；执行人为 technician/inspector。

### PATCH `/api/plans/{id}` — 暂停/恢复计划

**实现处理器：** `togglePlan`

**输入示例：**
```json
{
  "version": 1,
  "active": false
}
```

**返回：** Plan

**本地规则：** 仅管理员；只更新 active；模板、周期、指派及日期变更尚未实现。

### POST `/api/plans/generate` — 生成到期任务

**实现处理器：** `generateTasks`

**输入示例：**
```json
{
  "throughDate": "2026-09-27"
}
```

**返回：** { generated: Task[], count, throughDate }

**本地规则：** 仅管理员；截止日不得晚于上海今天；按计划原到期日逐周期补齐，每次最多 100 条；计划+日期去重；推进下次日期；无后台定时器。

### GET `/api/tasks` — 维保任务列表

**实现处理器：** `tasks`

**输入示例：**
```json
{
  "query": "可选 status：pending/completed"
}
```

**返回：** Task[]

**本地规则：** 任务携带生成时检查项快照；同时列出保养和点检。

### GET `/api/tasks/{id}` — 任务详情

**实现处理器：** `task`

**输入示例：**
```json
null
```

**返回：** Task（含 checklist/results/history/linkedOrderId）

**本地规则：** 只提供两态 pending/completed；未实现接单、复核或撤销。

### POST `/api/tasks/{id}/complete` — 执行维保/异常转维修

**实现处理器：** `completeTask`

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

**返回：** Task：completed；异常时 linkedOrderId 指向新 Order

**本地规则：** 仅指派人或管理员；必须提交所有项目且不重复；verdict=normal/abnormal；异常 remark 必填；读数为文字，无阈值判定；所有异常汇总为一个工单，与任务同次原子保存。

### GET `/api/messages` — 站内消息

**实现处理器：** `messages`

**输入示例：**
```json
null
```

**返回：** Message[]

**本地规则：** 仅当前演示角色的消息；非真实短信、微信或 APP 推送。

### PATCH `/api/messages/{id}/read` — 消息已读

**实现处理器：** `readMessage`

**输入示例：**
```json
{}
```

**返回：** Message：read=true

**本地规则：** 只可修改自己的消息；重复标记已读无副作用，不使用 version。

### GET `/api/audit` — 演示业务事件

**实现处理器：** `audit`

**输入示例：**
```json
null
```

**返回：** AuditEvent[]

**本地规则：** 仅管理员；记录业务动作而非所有读写、登录或修改前后镜像；未防篡改。

### GET `/api/catalog` — 本地主数据查询

**实现处理器：** `feature:catalog`

**输入示例：**
```json
null
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/catalog/{id}` — 创建本地主数据

**实现处理器：** `feature:catalogCreate`

**输入示例：**
```json
{
  "name": "示例；字段见扩展说明"
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### GET `/api/records/{id}` — 查询本人或管理员功能记录

**实现处理器：** `feature:records`

**输入示例：**
```json
null
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### PUT `/api/auth/accounts/{id}` — 启用停用本地账号

**实现处理器：** `feature:account`

**输入示例：**
```json
{
  "version": 1,
  "active": false
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/auth/password` — 修改本人密码

**实现处理器：** `feature:password`

**输入示例：**
```json
{
  "currentPassword": "<当前密码>",
  "newPassword": "<12字符以上新密码>"
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/auth/logout` — 撤销本人会话

**实现处理器：** `feature:logout`

**输入示例：**
```json
{}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/lifecycle/{id}/decision` — 审批资产申请

**实现处理器：** `feature:lifecycleDecision`

**输入示例：**
```json
{
  "version": 1,
  "decision": "approve",
  "note": "本地批准"
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/procurement/{id}/action` — 采购审批收货

**实现处理器：** `feature:procurementAction`

**输入示例：**
```json
{
  "version": 1,
  "action": "approve"
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### PUT `/api/files/{id}/content` — 实际二进制上传

**实现处理器：** `fileUpload`

**输入示例：**
```json
"<原始文件字节；Content-Type须与申请一致>"
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### GET `/api/files/{id}/content` — 授权下载原始文件

**实现处理器：** `fileDownload`

**输入示例：**
```json
null
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### PUT `/api/devices/{id}/position` — 配置设备位置范围

**实现处理器：** `feature:devicePosition`

**输入示例：**
```json
{
  "version": 1,
  "latitude": 31.2,
  "longitude": 121.4,
  "radiusMeters": 50
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/ocr/{id}/confirm` — 确认人工读数

**实现处理器：** `feature:ocrConfirm`

**输入示例：**
```json
{
  "version": 1,
  "value": 1.2,
  "unit": "bar"
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/tools/loans/{id}/return` — 归还工具

**实现处理器：** `feature:toolReturn`

**输入示例：**
```json
{
  "version": 1,
  "note": "已归还"
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/collaboration/rooms/{id}/messages` — 发送本地协作文字

**实现处理器：** `feature:roomMessage`

**输入示例：**
```json
{
  "message": "本地消息"
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### GET `/api/collaboration/rooms/{id}/messages` — 查询房间消息

**实现处理器：** `feature:roomMessages`

**输入示例：**
```json
null
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/training/enrollments/{id}/complete` — 提交本地课程考试

**实现处理器：** `feature:trainingComplete`

**输入示例：**
```json
{
  "version": 1,
  "answers": {
    "q1": "yes"
  }
}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/labels/{id}/revoke` — 作废标签

**实现处理器：** `feature:labelRevoke`

**输入示例：**
```json
{}
```

**返回：** 业务记录/文件，见扩展说明

**本地规则：** 需要Bearer令牌；管理写入还需要管理员身份

### POST `/api/auth/login` — 真实账号与认证

**实现处理器：** `feature:AUTH`

**输入示例：**
```json
{
  "account": "admin",
  "credential": "<读取本机首次凭据文件>",
  "provider": "password"
}
```

**返回：** { token, expiresAt, user }

**本地规则：** 本地密码登录、8小时令牌、账号停用、密码修改与会话撤销；SSO、验证码、注册、实名认证及登录设备/IP管理尚未接入；旧界面的 X-Demo-Actor 仍为演示兼容入口，不等于真实身份认证。

**原能力 ID：** `AUTH`

**仍需接续：** SSO、验证码、注册、实名认证及登录设备/IP管理尚未接入；旧界面的 X-Demo-Actor 仍为演示兼容入口，不等于真实身份认证。

### PUT `/api/access/roles/{id}` — 组织与权限配置

**实现处理器：** `feature:ACCESS`

**输入示例：**
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

**返回：** { role, version }

**本地规则：** 固定本地账号的功能权限和设备部门范围配置、版本与审计；菜单与字段配置仅保存元数据；没有供应商/企业租户隔离，旧演示路由未迁移到新权限模型。

**原能力 ID：** `ACCESS`

**仍需接续：** 菜单与字段配置仅保存元数据；没有供应商/企业租户隔离，旧演示路由未迁移到新权限模型。

### PUT `/api/settings` — 业务参数与移动表单

**实现处理器：** `feature:SETTINGS`

**输入示例：**
```json
{
  "version": 1,
  "dictionaries": {},
  "messageTemplates": [],
  "mobileForms": []
}
```

**返回：** { settings, version }

**本地规则：** 版本化字典、消息模板及安全类型的表单元数据保存；配置审批、动态前端表单和规则表达式执行未实现。

**原能力 ID：** `SETTINGS`

**仍需接续：** 配置审批、动态前端表单和规则表达式执行未实现。

### POST `/api/devices/{id}/lifecycle` — 资产档案与生命周期

**实现处理器：** `feature:ASSET`

**输入示例：**
```json
{
  "version": 1,
  "action": "transfer",
  "targetLocation": "<位置>",
  "reason": "<原因>",
  "attachments": []
}
```

**返回：** { requestId, status, deviceVersion }

**本地规则：** 调拨、停用、恢复、报废申请及审批；版本冲突、前后值、停用计划；财务折旧、Excel导入、借用和复杂审批链未实现；报废前必须结清未完成业务。

**原能力 ID：** `ASSET`

**仍需接续：** 财务折旧、Excel导入、借用和复杂审批链未实现；报废前必须结清未完成业务。

### POST `/api/compliance/inspections` — 特种设备与证书

**实现处理器：** `feature:COMPLIANCE`

**输入示例：**
```json
{
  "deviceId": "<ID>",
  "dueDate": "<日期>",
  "certificateId": "<ID>",
  "standardVersion": "<适用规则版本>"
}
```

**返回：** { inspectionId, reminders }

**本地规则：** 本地证书/批准标准关联、有效期校验、检查计划和站内提醒记录；不推断法规，不替代合格机构检验；提前提醒记录尚无后台定时发送器。

**原能力 ID：** `COMPLIANCE`

**仍需接续：** 不推断法规，不替代合格机构检验；提前提醒记录尚无后台定时发送器。

### PUT `/api/devices/{id}/bom` — 设备关系与 BOM

**实现处理器：** `feature:BOM`

**输入示例：**
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

**返回：** { tree, version }

**本地规则：** 版本化父子设备与备件关系；拒绝重复、自引用和跨设备环路；替代件生效规则、复杂单位换算及前端树图未实现。

**原能力 ID：** `BOM`

**仍需接续：** 替代件生效规则、复杂单位换算及前端树图未实现。

### POST `/api/iot/telemetry` — 设备数据与 IoT 告警

**实现处理器：** `feature:IOT`

**输入示例：**
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

**返回：** { accepted, duplicate, alarmIds }

**本地规则：** 遥测去重、乱序识别、温度/振动阈值、告警抑制/恢复和关联工单；通过HTTP输入本地点位；没有网关接入、数据源签名或传感器可信采集。

**原能力 ID：** `IOT`

**仍需接续：** 通过HTTP输入本地点位；没有网关接入、数据源签名或传感器可信采集。

### POST `/api/scheduling/preview` — 高级计划与排程

**实现处理器：** `feature:SCHEDULE`

**输入示例：**
```json
{
  "planIds": [],
  "horizon": {
    "from": "<日期>",
    "to": "<日期>"
  },
  "triggers": [
    "calendar"
  ],
  "resourceIds": []
}
```

**返回：** { proposedTasks, conflicts, explanation }

**本地规则：** 90天内日期计划预览、资源筛选与同日冲突解释；运行时长、产量触发没有数据时仅报告缺项；不执行自动调度或甘特图。

**原能力 ID：** `SCHEDULE`

**仍需接续：** 运行时长、产量触发没有数据时仅报告缺项；不执行自动调度或甘特图。

### POST `/api/workflows/definitions` — 派单、SLA 与流程引擎

**实现处理器：** `feature:WORKFLOW`

**输入示例：**
```json
{
  "name": "本地流程定义",
  "version": 1,
  "nodes": [
    {
      "id": "start",
      "kind": "start",
      "next": [
        "end"
      ]
    },
    {
      "id": "end",
      "kind": "end",
      "next": []
    }
  ],
  "rules": {
    "assignment": "manual",
    "timeoutMinutes": 60
  }
}
```

**返回：** { definitionId, version, validation }

**本地规则：** 有向无环流程定义校验和不可变版本存储；定义只保存，不替代维修状态机；技能自动派单、抢单和SLA计时未运行。

**原能力 ID：** `WORKFLOW`

**仍需接续：** 定义只保存，不替代维修状态机；技能自动派单、抢单和SLA计时未运行。

### POST `/api/analytics/calculate` — 费用、绩效及高级指标

**实现处理器：** `feature:COST`

**输入示例：**
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

**返回：** { metrics, missingInputs, sourceReferences }

**本地规则：** 从完工记录计算分钟MTTR；有输入才计算MTBF/OEE，缺项返回null；不是正式费用/财务/绩效口径；MTTR以完工尝试为单位，维度聚合尚未实现。

**原能力 ID：** `COST`

**仍需接续：** 不是正式费用/财务/绩效口径；MTTR以完工尝试为单位，维度聚合尚未实现。

### GET `/api/knowledge/search` — 故障知识与智能检索

**实现处理器：** `feature:KNOWLEDGE`

**输入示例：**
```json
{
  "query": {
    "q": "<故障代码或关键词>",
    "deviceModel": "<型号>"
  }
}
```

**返回：** { items, total, sourceReferences }

**本地规则：** 审核知识的关键词和设备型号检索；保留来源；没有向量搜索、自动AI推荐或企业知识权限分级。

**原能力 ID：** `KNOWLEDGE`

**仍需接续：** 没有向量搜索、自动AI推荐或企业知识权限分级。

### POST `/api/inventory/transactions` — 备件、领退料与盘点

**实现处理器：** `feature:INVENTORY`

**输入示例：**
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

**返回：** { transactionId, balances, ledgerReferences }

**本地规则：** 小数数量库存、领料/退料/收货、幂等请求、单位及不足库存校验；单进程JSON事务；不支持多Tomcat实例并发、成本计价或完整盘点。

**原能力 ID：** `INVENTORY`

**仍需接续：** 单进程JSON事务；不支持多Tomcat实例并发、成本计价或完整盘点。

### POST `/api/procurement/requests` — 采购及供应商合同

**实现处理器：** `feature:PROCUREMENT`

**输入示例：**
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

**返回：** { requestId, approvalStatus }

**本地规则：** 本地供应商/有效合同校验、申请、批准、收货与库存流水；没有ERP、发票、付款、税务或外部供应商账号。

**原能力 ID：** `PROCUREMENT`

**仍需接续：** 没有ERP、发票、付款、税务或外部供应商账号。

### POST `/api/reports/export` — 报表、打印和大屏

**实现处理器：** `feature:REPORT`

**输入示例：**
```json
{
  "reportType": "orders",
  "filters": {},
  "format": "csv",
  "templateId": "local"
}
```

**返回：** { jobId, status, downloadUrl, expiresAt }

**本地规则：** 管理员按过滤条件生成实际CSV文件并通过授权接口下载；PDF、打印模板和大数据异步取消未配置，请求PDF返回422。

**原能力 ID：** `REPORT`

**仍需接续：** PDF、打印模板和大数据异步取消未配置，请求PDF返回422。

### POST `/api/integrations/jobs` — ERP/OA/MES/HR 接口

**实现处理器：** `feature:INTEGRATION`

**输入示例：**
```json
{
  "system": "ERP",
  "direction": "import",
  "entity": "equipment",
  "cursor": "<游标>",
  "requestId": "<唯一请求>"
}
```

**返回：** { jobId, accepted, reconciliationUrl }

**本地规则：** local/equipment/import 批量原子导入及请求去重；外部任务明确阻塞；ERP/OA/MES/HR未配置，返回503 PROVIDER_NOT_CONFIGURED，没有同步成功声明。

**原能力 ID：** `INTEGRATION`

**仍需接续：** ERP/OA/MES/HR未配置，返回503 PROVIDER_NOT_CONFIGURED，没有同步成功声明。

### POST `/api/outsource/orders` — 委外与维保商协作

**实现处理器：** `feature:OUTSOURCE`

**输入示例：**
```json
{
  "orderId": "<ID>",
  "supplierId": "<ID>",
  "contractId": "<ID>",
  "expectedReturnDate": "<日期>"
}
```

**返回：** { externalOrderId, status }

**本地规则：** 有效合同和工单的本地委外登记，拒绝重复活动登记；外部派送、供应商身份、报价付款和内外状态回传未配置。

**原能力 ID：** `OUTSOURCE`

**仍需接续：** 外部派送、供应商身份、报价付款和内外状态回传未配置。

### POST `/api/files/uploads` — 照片、视频与技术资料

**实现处理器：** `feature:FILE`

**输入示例：**
```json
{
  "entityType": "order",
  "entityId": "<ID>",
  "filename": "<名称>",
  "contentType": "image/jpeg",
  "size": 1
}
```

**返回：** { uploadId, uploadUrl, expiresAt }

**本地规则：** 两步实际字节上传、大小/类型/图片内容校验、摘要与授权下载；文件为本机存储；没有断点续传、病毒扫描和企业保留期策略。

**原能力 ID：** `FILE`

**仍需接续：** 文件为本机存储；没有断点续传、病毒扫描和企业保留期策略。

### POST `/api/labels/generate` — 二维码/NFC/RFID 与分享

**实现处理器：** `feature:LABEL`

**输入示例：**
```json
{
  "entityType": "device",
  "entityIds": [],
  "format": "qr",
  "templateId": "<ID>"
}
```

**返回：** { labels, printJobId }

**本地规则：** ZXing生成真实QR SVG、标签撤销、设备关联和授权下载；QR载荷用于本地设备/标签识别；没有移动端扫码页面、打印机、NFC/RFID写入器。

**原能力 ID：** `LABEL`

**仍需接续：** QR载荷用于本地设备/标签识别；没有移动端扫码页面、打印机、NFC/RFID写入器。

### POST `/api/mobile/session` — 微信/支付宝与原生 APP

**实现处理器：** `feature:MOBILE`

**输入示例：**
```json
{
  "platform": "wechat",
  "authorizationCode": "<授权码>",
  "appVersion": "<版本>"
}
```

**返回：** { session, permittedFeatures, updatePolicy }

**本地规则：** 已认证web端会话与角色能力返回；平台任务明确阻塞；微信/支付宝/native授权服务未配置，返回503；无小程序或原生应用。

**原能力 ID：** `MOBILE`

**仍需接续：** 微信/支付宝/native授权服务未配置，返回503；无小程序或原生应用。

### POST `/api/offline/sync` — 离线同步

**实现处理器：** `feature:OFFLINE`

**输入示例：**
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

**返回：** { acknowledged, conflicts, serverVersion }

**本地规则：** 限定业务操作的幂等同步、账号绑定和版本冲突列表；没有离线客户端、附件队列、端侧加密或断网实测；本项目现场无需离线。

**原能力 ID：** `OFFLINE`

**仍需接续：** 没有离线客户端、附件队列、端侧加密或断网实测；本项目现场无需离线。

### POST `/api/sop/executions` — 标准化安全作业

**实现处理器：** `feature:SOP`

**输入示例：**
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

**返回：** { executionId, nextStep, status }

**本地规则：** 按模板顺序、证据要求保存检查步骤；未完成SOP阻止任务完工；本地模板为示例，尚未经业务/安全负责人批准，不能当现场安全证明。

**原能力 ID：** `SOP`

**仍需接续：** 本地模板为示例，尚未经业务/安全负责人批准，不能当现场安全证明。

### POST `/api/signatures` — 电子签名、签章与归档

**实现处理器：** `feature:SIGN`

**输入示例：**
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

**返回：** { signatureId, documentHash, archivedUrl }

**本地规则：** 本地账号对明确单据版本留痕、SHA256摘要/HMAC与不可变附件；仅本地留痕，不是有法律效力的第三方电子签章；外部provider返回503。

**原能力 ID：** `SIGN`

**仍需接续：** 仅本地留痕，不是有法律效力的第三方电子签章；外部provider返回503。

### POST `/api/location/checkins` — 定位、导航与轨迹

**实现处理器：** `feature:LOCATION`

**输入示例：**
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

**返回：** { checkinId, matched, accuracy }

**本地规则：** 设备坐标/范围配置、任务/标签匹配和距离+精度计算；坐标为客户端自报，physicalLocationVerified=false；无硬件防伪或实地到位证明。

**原能力 ID：** `LOCATION`

**仍需接续：** 坐标为客户端自报，physicalLocationVerified=false；无硬件防伪或实地到位证明。

### POST `/api/ocr/readings` — 仪表 OCR 与采集

**实现处理器：** `feature:OCR`

**输入示例：**
```json
{
  "taskId": "<ID>",
  "itemId": "<ID>",
  "fileId": "<ID>",
  "meterType": "<型号>"
}
```

**返回：** { value, unit, confidence, requiresConfirmation }

**本地规则：** 图片与检查项关联、人工确认待办、可审计人工读数确认；没有OCR引擎；初次返回202 manual_required、value/confidence=null，绝不伪造识别结果。

**原能力 ID：** `OCR`

**仍需接续：** 没有OCR引擎；初次返回202 manual_required、value/confidence=null，绝不伪造识别结果。

### POST `/api/tools/loans` — 工具借还与校准

**实现处理器：** `feature:TOOL`

**输入示例：**
```json
{
  "toolId": "<ID>",
  "borrowerId": "<ID>",
  "dueAt": "<时间>",
  "taskId": "<ID>"
}
```

**返回：** { loanId, status, calibrationValid }

**本地规则：** 工具校准有效期、唯一借出、本人/管理员归还和记录；没有智能柜、失效工具实物控制、自动逾期推送。

**原能力 ID：** `TOOL`

**仍需接续：** 没有智能柜、失效工具实物控制、自动逾期推送。

### POST `/api/collaboration/rooms` — 通讯与专家协作

**实现处理器：** `feature:COLLAB`

**输入示例：**
```json
{
  "orderId": "<ID>",
  "participantIds": [],
  "mode": "text"
}
```

**返回：** { roomId, joinUrl, expiresAt }

**本地规则：** 有成员边界和到期时间的本地文字协作房间/消息；视频服务未配置，video请求503；尚无聊天前端、实时WebSocket及录制。

**原能力 ID：** `COLLAB`

**仍需接续：** 视频服务未配置，video请求503；尚无聊天前端、实时WebSocket及录制。

### GET `/api/models/{id}` — AR 与 3D 模型

**实现处理器：** `feature:AR3D`

**输入示例：**
```json
{
  "query": {
    "deviceId": "<ID>",
    "variant": "exploded"
  }
}
```

**返回：** { modelUrl, version, parts, instructions }

**本地规则：** 已上传内嵌glTF2.0资产与设备/部件/说明的授权查询；需要用户上传合法模型；无AR渲染、硬件定位或实物版本验收。

**原能力 ID：** `AR3D`

**仍需接续：** 需要用户上传合法模型；无AR渲染、硬件定位或实物版本验收。

### POST `/api/training/enrollments` — 培训、证书与个人绩效

**实现处理器：** `feature:TRAINING`

**输入示例：**
```json
{
  "courseId": "<ID>",
  "employeeId": "<ID>",
  "certificationId": "<ID>"
}
```

**返回：** { enrollmentId, progress, qualificationStatus }

**本地规则：** 报名、题目答案校验、成绩和本地课程有效期证书；仅本地培训记录；无HR、工资、奖惩或外部资质核验。

**原能力 ID：** `TRAINING`

**仍需接续：** 仅本地培训记录；无HR、工资、奖惩或外部资质核验。

### POST `/api/intelligence/jobs` — 预测维护、孪生与语音

**实现处理器：** `feature:AI`

**输入示例：**
```json
{
  "kind": "prediction",
  "deviceId": "<ID>",
  "datasetVersion": "<版本>",
  "modelVersion": "<版本>",
  "inputs": {}
}
```

**返回：** { jobId, result, evidence, uncertainty }

**本地规则：** 确定性阈值规则评估并返回输入证据；外部模型任务明确阻塞；规则不是预测模型。prediction/twin/voice未配置时503，未经授权不执行关键动作。

**原能力 ID：** `AI`

**仍需接续：** 规则不是预测模型。prediction/twin/voice未配置时503，未经授权不执行关键动作。

### POST `/api/customizations` — 低代码与国际化

**实现处理器：** `feature:LOWCODE`

**输入示例：**
```json
{
  "version": 1,
  "forms": [],
  "workflows": [],
  "reports": [],
  "locale": "zh-CN"
}
```

**返回：** { customizationId, validation, publishedVersion }

**本地规则：** 安全字段类型的表单/流程引用/语言元数据版本发布；仅元数据；没有动态脚本执行、前端低代码渲染、完整发布回滚或翻译系统。

**原能力 ID：** `LOWCODE`

**仍需接续：** 仅元数据；没有动态脚本执行、前端低代码渲染、完整发布回滚或翻译系统。

### POST `/api/security/audit-exports` — 生产安全与审计回溯

**实现处理器：** `feature:SECURITY`

**输入示例：**
```json
{
  "from": "<时间>",
  "to": "<时间>",
  "entityType": "<类型>",
  "reason": "<原因>"
}
```

**返回：** { jobId, signedDigest, archiveUrl }

**本地规则：** 按时间/实体导出真实审计记录及本地HMAC摘要；只能证明导出内容未改，不能证明导出前本机管理员未改历史；无第三方存证。

**原能力 ID：** `SECURITY`

**仍需接续：** 只能证明导出内容未改，不能证明导出前本机管理员未改历史；无第三方存证。

### POST `/api/backups/jobs` — 备份恢复与部署

**实现处理器：** `feature:BACKUP`

**输入示例：**
```json
{
  "kind": "backup",
  "scope": "database-and-files",
  "restorePointId": null
}
```

**返回：** { jobId, status, manifest, verification }

**本地规则：** 实际数据+附件ZIP清单、摘要/HMAC校验及显式确认恢复，撤销会话；本机备份可恢复但不是异地灾备；恢复前须备份当前数据，不包含本机签名密钥迁移。

**原能力 ID：** `BACKUP`

**仍需接续：** 本机备份可恢复但不是异地灾备；恢复前须备份当前数据，不包含本机签名密钥迁移。

### POST `/api/notifications/deliveries` — 多渠道消息与告警升级

**实现处理器：** `feature:PUSH`

**输入示例：**
```json
{
  "eventId": "<唯一事件>",
  "recipientIds": [],
  "channels": [
    "in_app"
  ],
  "templateId": "<ID>",
  "variables": {}
}
```

**返回：** { deliveryId, channelResults, retryAt }

**本地规则：** 站内消息事件去重、多个收件人和外部渠道逐项结果；微信/短信/邮件未配置不发送；无自动重试器或到达回执，外部渠道返回明确失败。

**原能力 ID：** `PUSH`

**仍需接续：** 微信/短信/邮件未配置不发送；无自动重试器或到达回执，外部渠道返回明确失败。
