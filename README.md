# 设备维修保养系统 · Java/Tomcat 最小 Demo

本项目展示设备台账、维修工单、按日期的保养/点检计划、异常转维修、站内消息和设备履历。前端为原生 **HTML + CSS + JavaScript**，后端为 **Java 17 + Jakarta Servlet**，通过 **Tomcat 11** 运行。仓库根目录的 `pom.xml` 可直接由 IntelliJ IDEA 打开。

正式页面采用“工作日”界面：概览、维修工单、维保任务、设备台账、周期计划和接口进展都从同一套 Java API 获取演示数据。`public/quiet-view.js` 负责页面渲染，`public/app.js` 保留表单、角色切换和业务请求。

> 当前是虚构数据的单机流程演示。页面上的“演示角色”可自行切换，不能作为真实登录或企业数据权限使用。不要输入真实生产资料，也不要把此应用直接开放到公网。

## 本机环境和入口

已在本机确认：JDK 17、Tomcat 11.0.25 和 IntelliJ IDEA 自带 Maven。双击 [启动演示.cmd](启动演示.cmd) 会执行 Maven 构建，将 WAR 部署到本机 Tomcat 的独立应用路径，等待健康接口就绪后打开浏览器：

**http://127.0.0.1:8080/equipment-maintenance-demo/**

此 WAR 的访问过滤器仅接受本机回环地址，并要求请求域名为 `localhost`、`127.0.0.1` 或 `[::1]`；即使共享 Tomcat 的 8080 端口监听其他网卡，也不能从其他机器或借助其他域名操作这个未认证的演示应用。静态页面不缓存，以免浏览器继续展示旧版。过滤器只作用于本应用上下文，不影响 Tomcat 的其他应用。

双击 [停止演示.cmd](停止演示.cmd) 只会关闭**由该启动入口启动**的 Tomcat。如果 Tomcat 原本由 IDEA、Windows 服务或你自己启动，停止入口不会关闭它。Tomcat 是同一台机器上的共享应用服务器；若由启动入口启动，停止 Tomcat 也会停止这台实例承载的其他应用。WAR 部署在 `E:\Program Files\Apache Software Foundation\Tomcat 11.0\webapps\equipment-maintenance-demo.war`，不会覆盖现有的 `ROOT` 应用。

首次构建需要 Maven 能取得 `pom.xml` 中声明的依赖。`target/` 是构建产物，不纳入 Git。

## 在 IntelliJ IDEA 中打开

1. 用 IDEA 的 **Open** 选择本仓库目录或根目录 [pom.xml](pom.xml)，让 Maven 导入项目，Project SDK 设为 JDK 17。
2. 在 Maven 工具窗口执行 `test` 与 `package`。生成的 WAR 位于 `target/equipment-maintenance-demo.war`。
3. 在 IDEA 配置本机 Tomcat 11，部署 WAR 或 WAR exploded，应用上下文设为 `/equipment-maintenance-demo`；也可直接运行 `启动演示.cmd` 完成构建和部署。
4. 打开上面的应用地址。API 在同一上下文下的 `/api/*`，例如 `/equipment-maintenance-demo/api/health`。

若 IDEA 的现有运行配置把应用上下文设为 `/`，它会把页面部署到 `http://127.0.0.1:8080/`；这与一键入口使用的 `/equipment-maintenance-demo/` 是两个地址。建议在 **Run/Debug Configurations → Tomcat → Deployment → Application context** 统一设为 `/equipment-maintenance-demo`。一键入口在 8080 已被其他应用占用且未提供此上下文时会明确报错；若 Tomcat 正在运行，它会等新 WAR 的四份前端文件实际可用后再打开页面。

在终端使用 IDEA 内置 Maven 的命令示例：

```powershell
& 'D:/Program Files/JetBrains/IntelliJ IDEA 2024.3.2.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' test
& 'D:/Program Files/JetBrains/IntelliJ IDEA 2024.3.2.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd' package
```

若 `mvn` 已在 PATH 中，也可直接用 `mvn test`、`mvn package`。JUnit 测试使用临时文件，不会修改 Tomcat 中的演示数据。源码位于标准 Maven 目录，Servlet API 由 Tomcat 提供。

## 演示数据与恢复边界

首次部署会生成虚构的三台设备、一张待派工单和两项周期计划。默认 JSON 文件位于 **Tomcat 的 `data/equipment-maintenance-demo/demo.json`**，不在应用的 webapps 公开目录，也不会被 Git 提交。重启 Tomcat 后可继续使用同一演示数据；如果该文件损坏，应用会拒绝启动并保留原文件。

可以通过 JVM 系统属性 `equipment.demo.data` 或环境变量 `EQUIPMENT_DEMO_DATA` 指定另一个数据文件路径，便于 IDEA 调试或使用全新演示数据。当前 JSON 文件只服务于**单个应用实例**，不要让 IDEA 的 `/` 与脚本的命名上下文同时写同一数据文件；没有数据库事务或多进程锁。新增本机备份接口可恢复数据与附件，尚不属于异地灾备。

## 本地后端扩展与接口验收

原 33 个预留入口已增加本地 Java 处理器，加上辅助路由共 **77 条路由**。库存流水、采购审批收货、资产审批、BOM、文件上传下载、真实 QR SVG、工具借还、文字协作、培训、SOP、CSV 报表和本机备份恢复可以通过 API 操作。每项局部能力及剩余范围见 [本地后端扩展说明](docs/demo/本地后端扩展说明.md)。

新增接口除登录外需要 `Authorization: Bearer <token>`。首次加载扩展时，在数据文件旁生成 `<数据文件名>.credentials.json`，含四个本地账号的随机初始密码；API 数据只保存 PBKDF2 密码哈希与令牌摘要。登录调用 `POST /api/auth/login`，输入 `account`、`credential`、`provider=password`。改密/注销/停用及恢复备份会撤销对应会话。初始凭据文件不会随改密自动更新。

原演示页面仍兼容 `X-Demo-Actor`，所以目前不是全站企业认证系统。ERP、微信、视频、预测模型等没有接入配置时返回 **503 PROVIDER_NOT_CONFIGURED**；OCR 返回实际图片关联的人工确认待办，不伪造识别值；PDF/NFC 等未支持格式返回 422。新增能力没有全部配置前端操作页，主要通过 API 验收。

标准 `mvn test` 执行 24 项单元/契约测试，真实 Tomcat 验收需要单独启用。先使用全新隔离数据文件启动 Tomcat，再运行下面的命令（以 IDEA 的 Maven 或 PATH 中的 `mvn` 执行）：

```powershell
$env:EQUIPMENT_DEMO_DATA = 'E:/ProgramData/IDEA/EquipmentMaintenanceSystem/target/backend-http/state.json'
powershell -NoProfile -File scripts/start-demo.ps1 -NoBrowser
mvn '-Dtest=LocalBackendHttpTest' '-Ddemo.test.baseUrl=http://127.0.0.1:8080/equipment-maintenance-demo/' '-Ddemo.test.credentials=E:/ProgramData/IDEA/EquipmentMaintenanceSystem/target/backend-http/state.json.credentials.json' test
powershell -NoProfile -File scripts/stop-demo.ps1
```

HTTP 验收会创建数据和执行恢复，只用于**全新隔离文件**；再次验收请换一个文件路径。凭据、签名密钥、文件和备份放在 Git 忽略的目录或 Tomcat 私有数据目录，不提交仓库。

## 建议体验顺序

1. 在工作概览查看虚构设备、待办与基础计数。
2. 管理员进入“维修工单”派单；切换到维修工接单并提交完工；切回管理员或原报修人，验收通过。也可先退回，再完成二次维修和验收。
3. 在“维保任务”用指派的巡检员逐项录入结果；任一项填写异常和说明后，任务与自动创建的维修工单关联。
4. 管理员在“周期计划”新建计划、手动生成到期任务，重复生成不会为同一计划日期重复派发；暂停计划只影响后续生成。
5. 在“设备台账”新增、搜索设备并查看业务履历；消息菜单可查看和标记站内消息已读。
6. “接口与进展”列出 77 条路由与 33 项扩展的本地范围、外部依赖和原目标验收条件。

## 技术结构

```text
pom.xml                                      IDEA/Maven WAR 构建入口
src/main/java/com/equipmentmaintenance/demo/
  ApiServlet.java                            Tomcat HTTP 路由与响应
  LocalOnlyFilter.java                        演示应用仅允许本机访问
  DemoDomain.java                            设备、工单、维保状态与规则
  DemoStore.java                             单机 JSON 保存与读取
  FeatureDomain.java                         扩展路由、配置与主数据
  FeatureBusiness.java                       资产、库存、遥测、排程与报表
  FeatureWork.java                           SOP、协作、工具、培训与外部边界
  FeatureAuth.java / FileVault.java           本地密码会话、文件与备份
  DemoException.java                         业务错误及 HTTP 状态
src/main/resources/capabilities.json        已实现/预留接口注册表
src/main/webapp/WEB-INF/web.xml              Servlet 与欢迎页映射
src/test/java/.../DemoStoreTest.java         JUnit 业务与持久化测试
public/                                      HTML、CSS、JavaScript 页面
docs/demo/                                   API、功能覆盖与未完成说明
scripts/start-demo.ps1                       本机 Maven/Tomcat 启动入口
scripts/stop-demo.ps1                        只停止启动入口拥有的 Tomcat
```

API 成功响应为 `{ "data": ... }`，错误响应为 `{ "error": { "code": "...", "message": "...", "details": null } }`。资源版本冲突返回 409；外部服务未配置返回 503。完整输入、返回、规则和依赖见 [API 文档](docs/demo/API.md)。

## 边界与文档

- 旧页面角色切换只用于演示；扩展API有本地账号认证和局部权限，但没有全站企业数据隔离。
- “模拟扫码报修”只是选择设备，不调用摄像头或生成真实二维码。
- 旧完工表单耗材只保存文字，实际库存通过新增领退料接口处理；安全勾选及本地SOP模板不是经批准的现场安全证明。
- 首页状态从工单推导，不是传感器采集的设备运行状态。
- 响应式网页并非微信小程序或原生 APP。

更多材料：[未完成部分与实施说明](docs/demo/未完成部分与实施说明.md)、[原清单 103 项覆盖矩阵](docs/demo/功能覆盖矩阵.md)、[验证记录](docs/demo/验证记录.md)及[项目规划建议](docs/设备维保系统项目规划建议.md)。
