# NiuSu Control Plane AI 开发实施指南（分步执行）

> 版本：v1.0（2026-08-05）
> 用途：给 AI/开发者执行的**分步任务清单**。按顺序执行，每步含目标、改动文件、验收标准。
> 代码库：`control-plane/backend`（Spring Boot 3）+ `control-plane/frontend`（Vue 3）
> 前置阅读：《NiuSu_Control-Plane开发文档.md》《账号登录与批量SOCKS节点开发文档.md》《NiuSu_系统优化文档.md》

---

## 阶段 0：环境准备（先做）

**目标**：确认本地可编译、可连接、可测试。

**每一步做什么：**
1. 确认 JDK 17+、Maven、Node 18+ 已安装。
   - 命令：`java -version`、`mvn -v`、`node -v`
2. 确认 `.env.local` 存在且连接阿里云 RDS（`rm-bp1gq2po14z29s0kxuo.mysql.rds.aliyuncs.com:3306/control-plane`）。
   - 不存在则复制 `.env.local.example` 并填写；**不要提交 Git**。
3. 确认 `CONTROL_PLANE_ENCRYPTION_KEY` 与线上一致（否则历史密文无法解密）。
4. 本地启动后端：IDEA 运行 `NodeControlApplication`，或 `mvn spring-boot:run`。
5. 本地启动前端：`cd frontend && npm install && npm run dev`。
6. 验证：`GET /api/control/meta` 返回 `{version, authRequired, passwordLoginEnabled}`。

**验收**：后端无宽泛报错、前端页面可打开、登录能成功。

当前基线：后端 68 个测试通过，前端生产构建通过。测试使用 H2，不会连接生产 RDS。

---

## 阶段 1：接入五种协议（对标 IPVelo）

> 依据《NiuSu_项目开发文档》§7.2 与整合方案 §3。Node Manager 已返回 `protocolsAll`，Control Plane 需接收并透传。

### 步骤 1.1 扩展远端 DTO `RemoteModels.UserConnection`
**目标**：接收 Node Manager 返回的五协议字段。
**改动文件**：`backend/src/main/java/com/example/nodecontrol/dto/RemoteModels.java`
**做什么**：
- 在 `UserConnection` record 中新增字段：`String protocolAllVless`、`String protocolAllVmess`、`String protocolAllSocksAcceleration`、`String protocolAllBitBrowser`（或单个 `Map<String,String> protocolsAll`）。
- 建议：新增 `Map<String, String> protocolsAll`，与现有 `vless/vmess/socks` 并列，保持向后兼容。
- 为旧客户端保留现有多参构造器（构造函数兼容）。

**验收**：`ProvisioningServiceIntegrationTest` 编译通过；`NodeManagerClientTest` 能解析带 `protocolsAll` 的响应。

### 步骤 1.2 扩展 `ResidentialAllocation` 存储
**目标**：持久化五协议连接。
**改动文件**：`domain/ResidentialAllocation.java`
**做什么**：
- 新增 `@Lob String vlessDnsCipher` / `vmessDnsCipher` / `socksAccelerationCipher` / `bitbrowserCipher`（或单个 `@Lob String protocolsAllCipher` 存 JSON）。
- 建议：新增 `@Lob String protocolsAllJsonCipher`，在 `complete()` 中加密整体 JSON，减少字段膨胀。
- 同步补充 getter。
- 若 Hibernate `ddl-auto=update` 自动加列即可；生产需在 `SchemaCompatibilityMigration` 中补幂等 `ALTER TABLE`（参考现有 `proxy_source_port` 写法）。

**验收**：本地启动后表新增列；`mvn test` 通过。

### 步骤 1.3 扩展 `AllocationView`
**目标**：向前端返回五协议。
**改动文件**：`dto/ControlPlaneModels.java`
**做什么**：
- 在 `AllocationView` 新增 `Map<String, String> protocolsAll`（或不含凭据的安全视图字段）。
- 注意：`withoutProxyCredentials` 转账构造器需同步（新增字段要传 `null` 以保持纯上游凭据隐藏）。

**验收**：`mvn test` 通过；`ControlPlaneWebContractTest` 匹配新字段。

### 步骤 1.4 扩展 `ProvisioningService.toView()`
**目标**：解密并组装五协议。
**改动文件**：`service/ProvisioningService.java`
**做什么**：
- 在 `toView(allocation, includeConnection)` 的 ACTIVE 分支，解密 `protocolsAllJsonCipher` 并放入 `AllocationView.protocolsAll`。
- 仅在 `includeConnection=true` 时解密（列表 `listAllocations` 传 `false`，避免泄露与性能损耗）。
- 同步 `validateResidentialAllocation`：若 `protocolsAll` 非空，校验其包含五种协议键。

**验收**：`GET /api/control/allocations/{id}` 返回 `protocolsAll`；`GET /api/control/allocations` 不返回。

### 步骤 1.5 前端展示
**改动文件**：`frontend/src/App.vue`
**做什么**：
- 在“生成链接/连接信息”弹窗，读取 `allocation.connection.protocolsAll` 或 `allocation.protocolsAll`，渲染五种协议标签页。
- 保持默认遮罩，点击“复制”才显示明文。
- Toast 仅提示复制成功，不回显内容。

**验收**：批量开通成功卡片按出口模式显示协议；绑定住宅时可查看五种协议，直连时仅显示三种加速协议；关闭弹窗清空前端内存。

协议键名固定为：`socks5`、`bitbrowser`、`vless`、`socksAcceleration`、`vmess`。返回集合按出口模式动态变化：无住宅出口时只要求 `vless`、`socksAcceleration`、`vmess`；绑定住宅出口时才要求五个键。其中住宅出口 IP 是 GeoIP/备注来源，上游 SOCKS 地址和凭据用于 Node Manager 的出站绑定，最终 VLESS/VMess/SOCKS 加速链接必须指向 Node Manager 的入口域名或 IP。

生产环境配置 `control-plane.provisioning.require-complete-protocols-all=true`（环境变量 `CONTROL_PLANE_REQUIRE_COMPLETE_PROTOCOLS_ALL=true`）时，Control Plane 会按出口模式校验 Node Manager：直连用户要求三种加速链接，住宅用户要求五种链接。本地默认值为 `false`，便于兼容旧节点。

---

## 阶段 2：住宅 SOCKS 配置模块（后端校验与安全存储）

> 前端已支持批量输入，本节强调**校验完整性与凭据安全**。

### 步骤 2.1 强化校验（后端）
**改动文件**：`service/ProvisioningService.java`（`parsedRow`、`validateIp`、`validateCredential`）
**做什么**：
- 确认 IP 校验覆盖 IPv4 段范围 + `InetAddress.getByName`。
- 确认端口 `1-65535`。
- 确认凭据非空、≤255、无空白/控制字符。
- `sourceAddress` 支持 IPv4/域名（IDN）；`-` 表示用第一列。
- 确认单次 ≤ 50 行、BOM/NBSP/全角空格清理。已有实现，逐条核验。

**验收**：`ProvisioningServiceIntegrationTest` 覆盖非法 IP/端口/凭据用例通过。

### 步骤 2.2 凭据加密落库
**改动文件**：`service/ProvisioningService.java`（`createOrLoadProxy`）+ `security/SecretCipher.java`
**做什么**：
- 确认上游 `username/password` 用 `secretCipher.encrypt()` 后存 `proxy_username_cipher/proxy_password_cipher`。
- 列表 `listAllocations`、批量响应 `toViewWithoutProxyCredentials` 不返回明文凭据。
- 错误 `sanitizeError` 用 `***` 替换真实账号密码。

**验收**：数据库字段以 `enc:v1:` 开头；`sanitizeError` 测试通过。

---

## 阶段 3：节点控制中心集成（管理界面）

### 步骤 3.1 节点状态监控
**改动文件**：`service/ManagedNodeService.java`、`frontend/App.vue`
**做什么**：
- 确认 `ManagedNodeService` 心跳更新 `cpu/memory/connections/userCount/socks_inbound_port`。
- 确认 `getDashboard()` 聚合在线/降级/用户/连接/流量。
- 前端“全部节点”区展示各节点 CPU/内存/连接/容量/在线状态。
- 新增按“协议类型、IP 区域”分组（可用 `countryCode` 维度）。

**验收**：刷新节点后状态实时更新；`DashboardView` 指标正确。

### 步骤 3.2 查看/编辑/删除代理配置
**改动文件**：`ProvisioningController`、`UserController`、`App.vue`
**做什么**：
- 确认分配列表可查看、详情可看、`retry` 可重试。
- 确认节点用户可删除（`DELETE /users/{userId}`）。
- 前端提供“绑定代理/查看连接/删除”操作入口。

**验收**：增删改查链路可用。

---

## 阶段 4：批量化 + 异步（性能优化）

> 参考《NiuSu_系统优化文档》阶段二。

### 步骤 4.1 列表分页（已完成）
**改动文件**：`ProvisioningController.list`、`ResidentialAllocationRepository`
**做什么**：
- 为 `GET /allocations` 增加 `page/pageSize` 参数，返回分页结构。
- 列表视图 `includeConnection=false`（已如此），避免全量解密。

**当前状态**：`GET /api/control/allocations?page=1&pageSize=20` 返回 `items/page/pageSize/total/totalPages`；未传分页参数时保留旧数组响应，兼容旧客户端；列表视图不解密连接。

**验收**：服务层与 Web 合同测试已覆盖分页边界和响应结构；大数量下继续以数据库索引与压测数据评估 P95 < 500ms。

### 步骤 4.2 批量异步化（可选，需引入队列）
**改动文件**：`ProvisioningService`、新增 `BatchJobService`
**做什么**：
- 将 `provisionProxyBatch` 改为提交后立即返回 `jobId`。
- 后台线程/队列逐行执行，更新进度、支持取消。
- 提供 `GET /allocations/jobs/{jobId}` 查询进度。

**验收**：提交 P95 < 2s；任务后台完成。

---

## 阶段 5：安全与稳定性（P0）

### 步骤 5.1 引入 Flyway/Liquibase 迁移
**改动文件**：`pom.xml` + 新增 `src/main/resources/db/migration/`
**做什么**：
- 加入 Flyway 依赖，配置 `spring.flyway.*`。
- 将现有表结构整理为基线迁移脚本（V1__baseline.sql）。
- 将 `SchemaCompatibilityMigration` 的幂等 `ALTER TABLE` 收敛进迁移脚本。
- 生产切换 `spring.jpa.hibernate.ddl-auto=validate`。

**验收**：全新库可一键迁移；旧库升级不丢数据（先备份）。

### 步骤 5.2 RBAC 权限（可选）
**改动文件**：`ControlTokenFilter`、`ControlUser`
**做什么**：
- `control_users` 增加 `role` 字段（ADMIN/NODE_OPS/PROVISIONER/READONLY）。
- 在 `ControlTokenFilter` 判定角色，映射到各接口所需权限。

**验收**：只读账号无法触发开通/删除。

### 步骤 5.3 审计日志
**改动文件**：各 Service + 新增 `AuditLog` 实体
**做什么**：
- 记录登录/开通/删除/重试的操作者与分配 ID，**不含密码与完整连接**。
- 提供审计查询接口（仅管理员）。

**验收**：关键操作可追溯；审计不含敏感值。

---

## 阶段 6：收尾与回归

**做什么：**
1. `cd backend && mvn.cmd test` 全绿。
2. `cd backend && mvn.cmd clean package` 打包成功。
3. `cd frontend && npm run build` 构建成功。
4. 用浏览器走一遍：登录 → 批量输入 → 开通 → 查看五种协议 → 删除。
5. 确认无敏感信息入日志、`Cache-Control: no-store` 生效。

**验收**：三项构建通过 + 端到端主流程可用。

---

## 附：文档索引（同目录）
- `NiuSu_Control-Plane开发文档.md`：权威规格与 API 参考。
- `账号登录与批量SOCKS节点开发文档.md`：登录/批量/安全细节。
- `后续优化清单.md`：后续优化 backlog。
- `NiuSu_系统优化文档.md`：瓶颈分析与 KPI。
- `NiuSu_数据库资产清册.md`：数据库连接与元数据。

## 本轮实施状态（2026-08-06）

- 已落地四角色权限矩阵：`ADMIN` 全部权限；`NODE_OPS` 负责节点注册、刷新、维护和一键安装；`PROVISIONER` 负责节点用户、代理绑定和批量开通；`READONLY` 只读总览、节点和分配摘要。
- 已增加业务审计：节点注册/更新/刷新/删除、节点用户创建/删除、代理绑定、批量开通、重试、账号登录/变更，以及 `NODE_INSTALL_COMMAND_ISSUED`。审计摘要不写密码、Token、SOCKS 凭据、密文或完整链接。
- 控制器兼容旧的 `X-Control-Token` 与节点安装令牌调用路径；只有账号密码会话才写入具体操作者 UUID，避免破坏旧客户端和安装脚本。
- 已补充 MockMvc 权限边界测试；当前 Control Plane 后端测试为 **73 个全部通过**。
- 前端已按角色隐藏高风险菜单和操作按钮：只读账号不能看到创建、删除、代理明文和连接明文操作；节点运维账号保留节点刷新、维护和一键安装；节点开通账号保留节点用户/代理/批量开通；管理员可查看新增的“审计日志”页面。
- 前端已接入 `/api/control/audit-logs` 分页查询，页面只显示事件、操作者、目标和脱敏摘要，不显示 Token、密码、密文或完整链接。
- 本轮构建验证：`mvn.cmd clean package -DskipTests`、`npm run build` 均成功；Node Manager `python -m unittest discover -s tests -v` 为 **47 个全部通过**。

## 生产迁移前置条件

1. 先备份 MySQL `control-plane` 数据库。
2. 执行同目录 `production-schema-migration.sql`，确认 `control_users.role`、`control_audit_logs`、住宅端口字段和节点 SOCKS 入口字段存在。
3. 生产使用 `ddl-auto=validate` 时，迁移脚本必须先于 JAR 替换执行；不要把真实数据库密码、登录密码或加密密钥提交到仓库。
4. 升级后检查 `/api/control/audit-logs` 仅管理员可访问，并确认一键安装命令响应使用 `Cache-Control: no-store`。
# 本轮落地记录（2026-08-06）

- 已恢复 `ControlPlaneModels.java` 的 UTF-8 编码和中文校验消息，保留五协议、分页分配和代理字段。
- 账号增加 `ADMIN`、`NODE_OPS`、`PROVISIONER`、`READONLY` 角色；角色变更会使旧会话失效。
- 增加 `control_audit_logs` 审计表和 `/api/control/audit-logs` 查询接口；审计摘要禁止写入密码、Token、密文和完整链接。
- 生产环境使用 `doc/production-schema-migration.sql`，执行后再替换 JAR；`ddl-auto=validate` 不会自动修改生产库。
