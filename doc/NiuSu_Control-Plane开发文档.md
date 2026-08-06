# NiuSu Control Plane 开发文档

> 文档版本：v1.0（2026-08-05）
> 适用范围：Control Plane（Spring Boot 3 + Vue 3）
> 代码路径：`control-plane/backend`（Java）+ `control-plane/frontend`（Vue）
> 关联文档：《NiuSu_项目开发文档》《账号登录与批量SOCKS节点开发文档》《NiuSu_系统优化文档》
> 安全约定：本文件不记录生产密码、Token、加密密钥明文。

---

## 1. 概述

Control Plane（控制面）是 NiuSu 住宅代理平台的中枢，负责：

- **账号与认证**：管理端多账号登录（HttpOnly Cookie 会话），兼容旧 `X-Control-Token`。
- **节点管理**：Node Manager 注册、心跳、在线/离线、容量与移出。
- **用户与协议**：在节点上创建/删除节点用户，生成 VLESS / VMess / SOCKS5 连接。
- **批量 SOCKS 开通**：解析住宅 SOCKS 输入，逐行选择节点并绑定上游出口。
- **一键安装**：生成一次性安装码，供 VPS 拉取 GitHub 脚本注册 Node Manager。
- **安全存储**：Node Manager Token、上游 SOCKS 凭据、连接密文使用 AES-GCM 加密落库。

## 2. 技术栈与结构

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 后端 | Spring Boot 3 + Spring MVC + Spring Data JPA | Java 17+，Maven 构建 |
| 数据库 | MySQL（生产）/ H2（测试） | `ddl-auto=update`（待迁移 Flyway） |
| HTTP客户端 | Spring `RestClient` | 调用 Node Manager，封装信封与错误 |
| 前端 | Vue 3 + 原生 fetch | 单页面，同源 Cookie |
| 安全 | BCrypt + AES-GCM + Cookie 会话 | 见 §7 |

```
control-plane/
├── backend/
│   └── src/main/java/com/example/nodecontrol/
│       ├── NodeControlApplication.java      # @EnableScheduling
│       ├── config/                          # 属性、兼容迁移、密码配置
│       ├── domain/                          # JPA 实体与 Repository
│       ├── dto/                             # ControlPlaneModels / RemoteModels
│       ├── service/                         # 业务服务
│       ├── client/                          # NodeManagerClient + RemoteNodeException
│       ├── security/                        # SecretCipher / ControlSessionService / ControlTokenFilter
│       └── web/                             # 控制器 + 全局异常
└── frontend/src/
    ├── App.vue                              # 登录、批量输入、结果、敏感信息生命周期
    ├── api.js                               # 同源请求封装 + 幂等键
    └── main.js
```

## 3. 模块与职责

| 模块 | 入口 | 核心类 | 职责 |
| --- | --- | --- | --- |
| 认证 | `AuthenticationController` | `ControlSessionService` | 登录/会话/登出，签发 Cookie |
| 账号 | `ControlAccountController` | `ControlAccountService` | 多账号 CRUD、BCrypt、会话撤销 |
| 节点 | `NodeController` | `ManagedNodeService` | 注册/刷新/更新/删除/仪表盘 |
| 用户 | `UserController` | `NodeUserService` | 节点用户增删查、连接、代理、流量、绑定 |
| 批量开通 | `ProvisioningController` | `ProvisioningService` | 单条/批量 SOCKS 开通与重试 |
| 安装 | `NodeInstallationController` | `NodeInstallationService` | 一次性安装码 |
| 代理注册 | `AgentRegistrationController` | `ManagedNodeService` | 安装码/长令牌双通道注册 |
| 元信息 | `SystemController` | — | `/api/control/meta` |
| 异常 | `ApiExceptionHandler` | — | 全局错误映射与中文文案 |

## 4. 完整 API 接口

鉴权策略：除 `meta`、`auth/*`、`agent/register` 外，均需管理 Cookie 会话或以 `X-Control-Token` 兼容访问。

### 4.1 认证与账号
| 方法与路径 | 说明 | 关键参数/响应 |
| --- | --- | --- |
| `GET /api/control/meta` | 版本与登录能力 | `{version, authRequired, passwordLoginEnabled}` |
| `POST /api/control/auth/login` | 登录，`Set-Cookie: NIUSU_CONTROL_SESSION` | `{username,password}` → `{authenticated,username}` |
| `GET /api/control/auth/session` | 查询会话 | `{authenticated,username}` |
| `POST /api/control/auth/logout` | 登出（过期 Cookie） | `{authenticated:false}` |
| `GET /api/control/accounts` | 账号列表 | `[{id,username,enabled,current,...}]` |
| `POST /api/control/accounts` | 创建账号 | `{username,password}` |
| `PATCH /api/control/accounts/{id}` | 启用/停用/重置密码 | `{enabled?,password?}` |
| `DELETE /api/control/accounts/{id}` | 删除账号 | — |

### 4.2 节点管理
| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/control/dashboard` | 仪表盘聚合指标 |
| `GET /api/control/nodes` | 节点列表 |
| `POST /api/control/nodes` | 手动注册节点 `{name,baseUrl,token,maxUsers?}` |
| `POST /api/control/nodes/{nodeId}/refresh` | 刷新节点状态 |
| `PATCH /api/control/nodes/{nodeId}` | 更新 `{enabled?,maintenance?,maxUsers?}` |
| `POST /api/control/nodes/{nodeId}/reload` | 触发 sing-box reload |
| `DELETE /api/control/nodes/{nodeId}` | 移出节点 |

### 4.3 节点用户
| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/control/nodes/{nodeId}/users?page=&pageSize=&keyword=` | 用户分页列表 |
| `POST /api/control/nodes/{nodeId}/users` | 创建用户（Idempotency-Key） |
| `GET /api/control/nodes/{nodeId}/users/{userId}/connections` | 连接（no-store） |
| `GET /api/control/nodes/{nodeId}/users/{userId}/proxy` | 代理详情（no-store，含凭据） |
| `GET /api/control/nodes/{nodeId}/users/{userId}/traffic` | 流量 |
| `POST /api/control/nodes/{nodeId}/users/bind-proxy` | 绑定上游代理 |
| `DELETE /api/control/nodes/{nodeId}/users/{userId}` | 删除用户 |

### 4.4 批量开通（核心）
| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/control/allocations` | 单条开通 `{userId?,protocols,preferredNodeId?}` + `Idempotency-Key` |
| `POST /api/control/allocations/proxy-provisions` | 批量 SOCKS 开通 `{input,protocols?,preferredNodeId?}` + `Idempotency-Key` |
| `GET /api/control/allocations` | 分配列表（不含凭据）；可选 `page` 与 `pageSize` 分页（页码从 1 开始，每页 1-100 条） |
| `GET /api/control/allocations/{id}` | 单条详情（含解密连接/凭据） |
| `POST /api/control/allocations/{id}/retry` | 重试开通 |

### 4.5 安装与代理注册
| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/control/node-installation` | 生成一次性安装命令 `{command,expiresAt,expiresInSeconds}` |
| `POST /api/control/agent/register` | 节点注册（`X-Install-Token` 或 `X-Registration-Token`） |

### 4.6 错误响应格式（统一）
```json
{
  "message": "中文可读信息",
  "status": 400,
  "timestamp": "2026-08-05T00:00:00Z",
  "fields": { "proxy.port": "代理端口数值超出允许范围" }
}
```

## 5. 数据结构

### 5.1 数据库表（residential 相关）
`residential_allocations` 关键字段：
- `id`(UUID)、`request_key`(幂等唯一)、`request_hash`(请求摘要)
- `control_user_id`(节点用户 ID，节点内唯一)、`remote_idempotency_key`
- `protocols`、`state`(PENDING/PROVISIONING/ACTIVE/RETRYABLE/FAILED)
- `provisioning_mode`(DIRECT/UPSTREAM_SOCKS)
- 上游 SOCKS：`proxy_source_ip`、`proxy_source_domain`、`proxy_source_port`、`proxy_server`、`proxy_port`、`proxy_username_cipher`、`proxy_password_cipher`
- 连接（密文）：`vless_cipher`、`vmess_cipher`、`socks_host`、`socks_port`、`socks_username_cipher`、`socks_password_cipher`
- `proxy_bound`、`last_error`、`created_at`、`updated_at`、`completed_at`

### 5.2 状态机
```
PENDING → PROVISIONING → ACTIVE
              ↘ RETRYABLE → (重试) PROVISIONING
              ↘ FAILED（释放节点）
```

### 5.3 批量输入格式
- 4 列（四列简写，需指定节点）：`IP 端口 账号 密码`
- 5 列：`住宅IP 上游SOCKS地址 端口 账号 密码`
- 6 列（带序号）：`序号 住宅IP 上游SOCKS地址 端口 账号 密码`
- 第二列为 `-` 时使用第一列；分隔符空格/Tab；清理 BOM/NBSP/全角空格；单次 ≤ 50 行。

## 6. 关键流程

### 6.1 批量 SOCKS 开通
1. 解析输入 → 逐行校验（IP/端口/凭据/格式）。
2. `createOrLoadProxy`：按行幂等键查/建 `residential_allocations`（密文存上游凭据）。
3. `prepare`：锁定分配 → 选节点（容量 + 代理回环检测）→ 校验用户唯一（服务器IP+用户名）→ `assignNode`。
4. `client.createUser`：调用 Node Manager 创建用户并绑定上游 SOCKS。
5. `complete`：加密保存 vless/vmess/socks 连接，置 ACTIVE。
6. 409 冲突时对账 `getConnections` 恢复；失败行脱敏后进入 RETRYABLE/FAILED。

### 6.2 用户唯一性规则
唯一键 = **节点服务器 IP + 节点用户名**。判定顺序：心跳 `host`(IP) → 登记 `baseUrl` 主机(IP)；遍历同一服务器全部节点登记做远端预检；远端用户缺失则释放本地历史。

### 6.3 代理回环防护
若上游 SOCKS 指向本节点服务器 IP **且端口与节点 SOCKS 入站端口相同** → 拒绝并选择其他节点（`socks_inbound_port` 由心跳上报）。

## 7. 安全设计

| 项 | 方案 |
| --- | --- |
| 登录密码 | BCrypt 单向哈希，账号 API 不返回 `passwordHash` |
| 会话 | HttpOnly + SameSite=Strict Cookie，HMAC-SHA256 签名，`sessionVersion` 撤销 |
| 敏感数据 | AES-GCM（随机 IV，`enc:v1:` 前缀），`SecretCipher` |
| 响应 | 连接/凭据接口 `Cache-Control: no-store`；列表不返回凭据 |
| 日志 | 不记录请求体、密码、Token、完整连接 |
| 错误 | 脱敏 `***`、截断 1000 字符、加密密钥不一致提示 |
| 兼容 | `X-Control-Token` 仅兼容受信客户端，密码登录优先 |

## 8. 配置项（control-plane.*）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `control-plane.heartbeat.interval-ms` | 15000 | 心跳间隔 |
| `control-plane.heartbeat.offline-after-ms` | 90000 | 离线判定阈值 |
| `control-plane.security.encryption-key` | 必填 | 敏感数据加密根密钥 |
| `control-plane.security.session-ttl-seconds` | 43200 | 会话有效期 |
| `control-plane.provisioning.default-max-users` | 500 | 默认最大用户数 |
| `control-plane.provisioning.operation-stale-after-ms` | 120000 | 开通任务卡死判定 |
| `control-plane.installation.token-ttl-seconds` | 600 | 安装码有效期 |
| `control-plane.installation.claim-ttl-seconds` | 120 | 安装码占用超时 |
| `control-plane.geo-ip.base-url` | geojs.io | 国家解析接口 |
| `control-plane.public-url` | — | 一键安装命令公网地址（可选） |

## 9. 测试

```powershell
cd control-plane/backend
mvn.cmd test
mvn.cmd clean package
cd ../frontend
npm.cmd run build
```

已覆盖：首次账号初始化、BCrypt、账号 CRUD/撤销、Cookie 属性、批量解析/校验/排序、幂等、强制三协议、Node Manager 返回校验、AES-GCM、错误脱敏、代理回环、用户唯一性、兼容迁移。

分页补充：`GET /api/control/allocations?page=1&pageSize=20` 返回分页对象；省略分页参数仍返回旧版数组，保证已有前端兼容。分页列表不会解密连接或上游凭据，详情接口才按需解密。

## 10. 开发与部署

- 本地：IDEA 直接运行 `NodeControlApplication`，无需 Profile；`.env.local`（Git 忽略）连接阿里云 RDS。
- 生产：`application-prod.yml`（不含真实秘密），`SPRING_PROFILES_ACTIVE=prod`；HTTPS 反向代理。
- 数据库：生产使用 `ddl-auto=validate`，并关闭启动时兼容迁移。已有库升级前执行 [production-schema-migration.sql](production-schema-migration.sql)，脚本幂等补齐 `proxy_source_port`、`protocols_all_cipher`、`socks_inbound_port`，并仅删除旧版本在 `control_user_id` 上的单列全局唯一索引；不删除业务数据。升级顺序和验证命令见 README。

## 11. 优化落地映射（对接《系统优化文档》）

| 优化项 | 落地位置 | 说明 |
| --- | --- | --- |
| 批量异步化 | `ProvisioningService.provisionProxyBatch` | 任务队列 + 进度/取消 |
| 列表分页 | `ProvisioningService.listAllocations` | 已落地：服务端分页 + 按需解密，旧数组响应兼容保留 |
| Flyway 迁移 | `config/` | 替代 `ddl-auto` |
| 网关收口 | `NodeManagerClient` | 节点经网关访问，关闭公网 8088 |
| RBAC | `ControlTokenFilter` | 角色：系统管理员/运维/开通/只读 |
| 五协议扩展 | `ProvisioningService` + `AllocationView` | 接入 `protocolsAll`（见整合方案 §3） |
| 审计 | `ApiExceptionHandler`/Service | 审计落库，不含敏感值 |

## 12. 五协议与版本兼容约定

`protocolsAll` 的键集合来自五种协议：`socks5`、`bitbrowser`、`vless`、`socksAcceleration`、`vmess`。未绑定住宅出口时只返回 `vless`、`socksAcceleration`、`vmess`；绑定住宅出口时才返回五种。列表接口不解密、不返回链接；分配详情或连接详情接口才按需解密并返回。

生产配置建议：

```text
CONTROL_PLANE_REQUIRE_COMPLETE_PROTOCOLS_ALL=true
```

该开关开启后，Control Plane 会按出口模式校验响应：直连用户要求三种加速链接，住宅分配要求五协议响应；缺少对应协议时会失败并提示升级 Node Manager。本地默认关闭，以兼容尚未升级的测试节点。

住宅输入中的第一列是住宅出口 IP，第二列是上游 SOCKS 服务器；Control Plane 将两者分别保存并传给 Node Manager，最终协议链接指向 Node Manager 的实际入口，不直接暴露上游住宅凭据。
# 角色与审计补充

角色说明：`ADMIN` 全部权限；`NODE_OPS` 节点注册、刷新、维护和 Node Manager 安装；`PROVISIONER` 节点用户、批量 SOCKS 和分配；`READONLY` 仅查看总览、节点和分配摘要，不能读取连接/代理明文详情。

账号创建和角色修改只能由管理员完成。角色、启停用、密码修改都会使目标账号已有会话失效。

审计接口：`GET /api/control/audit-logs?page=0&pageSize=50`。日志只保存操作类型、操作者、目标和摘要，不保存任何密码、注册令牌、API Token、SOCKS 凭据、加密密文或完整代理链接。

## 13. 角色权限矩阵（2026-08-06）

| 能力 | ADMIN | NODE_OPS | PROVISIONER | READONLY |
| --- | --- | --- | --- | --- |
| 总览/节点/分配摘要 | ✓ | ✓ | ✓ | ✓ |
| 注册、刷新、更新、删除节点 | ✓ | ✓ | - | - |
| 生成一键安装命令 | ✓ | ✓ | - | - |
| 创建/删除节点用户、绑定代理 | ✓ | - | ✓ | - |
| 批量 SOCKS 开通/重试 | ✓ | ✓ | ✓ | - |
| 查看连接或代理明文 | ✓ | ✓ | ✓ | - |
| 账号管理 | ✓ | - | - | - |
| 审计日志 | ✓ | - | - | - |

旧版 `X-Control-Token` 和 Node Manager 安装令牌仍可用于机器间调用；这些请求没有控制账号操作者 UUID，业务接口保持兼容但不会伪造账号审计身份。

## 14. 一键安装命令审计

`POST /api/control/node-installation` 生成的命令包含一次性安装令牌，响应不缓存，令牌仅保存 SHA-256 摘要。审计事件 `NODE_INSTALL_COMMAND_ISSUED` 只记录操作者、目标类型和过期时间，不记录原始令牌、完整命令或控制中心密钥。
## 2026-08-06 本轮交付补充

- 前端现在根据 `ADMIN`、`NODE_OPS`、`PROVISIONER`、`READONLY` 角色隐藏不允许的菜单和按钮；后端 `ControlTokenFilter` 仍是最终权限边界，前端隐藏仅用于减少误操作。
- 管理员可在“审计日志”页面查看分页操作摘要。摘要只允许事件、操作者、目标和脱敏描述，不保存密码、Node Manager Token、安装一次性令牌、SOCKS 凭据或完整协议链接。
- Control Plane 后端已成功打包为 `backend/target/node-control-plane-0.1.0.jar`；前端已成功生成 `frontend/dist`。生产替换 JAR 前必须先执行 `doc/production-schema-migration.sql` 并确认加密密钥保持不变。
