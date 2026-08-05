# NiuSu Control 账号登录与批量 SOCKS 节点开发文档

## 1. 功能范围

当前实现包含三条完整链路：

1. 管理端从浏览器保存 `X-Control-Token` 改为数据库多账号登录，后端签发 HttpOnly Cookie 会话。
2. 首页增加“节点信息输入”，批量校验原生住宅 SOCKS 数据，并在可用 Node Manager 上固定生成 VLESS、VMess、SOCKS5 三协议入口；三种入口共同路由到该行住宅 SOCKS 出口。
3. 页面生成短时一次性安装命令，在 VPS 上自动安装 Node Manager 并注册到当前 Control Plane。

原有 VPS 直出、手动创建用户、绑定住宅出口和旧 `X-Control-Token` API 兼容能力继续保留。

## 2. 管理端账号密码登录

### 2.1 初始化配置

在控制面环境文件或进程环境中配置：

```dotenv
CONTROL_PLANE_LOGIN_USERNAME=admin
CONTROL_PLANE_LOGIN_PASSWORD=replace-with-a-strong-password
CONTROL_PLANE_SESSION_TTL_SECONDS=43200
CONTROL_PLANE_ENCRYPTION_KEY=replace-with-a-long-random-encryption-key
```

首次启动时，只有 `control_users` 表为空且账号、密码两项都非空，系统才使用这些配置创建第一个管理账号。密码通过 BCrypt 哈希后写入数据库，环境变量只承担首次初始化职责。账号表已有数据时，修改环境变量或重启不会覆盖数据库账号。

管理端不再要求输入控制 Token，也不会把账号、密码或会话令牌写入浏览器持久化存储。所有启用账号当前拥有相同的 Control Plane 操作权限。

若仅配置 `CONTROL_PLANE_ADMIN_TOKEN` 而没有账号密码，旧 API 客户端仍可使用 `X-Control-Token`，但管理界面会提示先配置账号密码。若账号密码和管理 Token 都为空，后端控制 API 按兼容模式不启用鉴权；生产环境禁止这样部署。

### 2.2 登录接口和 Cookie

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/control/auth/login` | 校验账号密码并设置 `NIUSU_CONTROL_SESSION` |
| `GET /api/control/auth/session` | 查询 Cookie 是否仍有效 |
| `POST /api/control/auth/logout` | 返回过期 Cookie，结束会话 |

会话 Cookie 属性：

- `HttpOnly`：前端 JavaScript 无法读取。
- `SameSite=Strict`：降低跨站请求携带 Cookie 的风险。
- `Path=/`：供同源控制 API 使用。
- HTTPS 请求时设置 `Secure`。
- 有效期由 `CONTROL_PLANE_SESSION_TTL_SECONDS` 控制，最小 300 秒。

会话使用 v2 结构，包含账号 ID、账号 `sessionVersion`、到期时间和 HMAC-SHA256 签名。签名密钥由 `CONTROL_PLANE_ENCRYPTION_KEY` 派生。重置密码、停用账号时会递增 `sessionVersion`；删除账号后也无法再查询到会话所属账号，因此这些操作都会立即撤销已有 Cookie。

### 2.3 多账号管理

登录后通过右上角“账号管理”完成：

- 创建账号：账号为 3–64 位字母、数字、点、下划线或短横线；密码为 10–128 位。
- 启用/停用：停用后该账号不能登录，已有 Cookie 立即失效。
- 重置密码：只返回操作结果，不返回密码哈希；该账号已有 Cookie 立即失效。
- 删除账号：删除后账号及其会话失效。
- 安全约束：不能停用或删除当前登录账号，至少保留一个启用账号，账号名忽略大小写且不能重复。

账号接口如下，均要求账号密码 Cookie 会话；仅携带兼容 `X-Control-Token` 不能管理账号：

| 方法与路径 | 作用 |
| --- | --- |
| `GET /api/control/accounts` | 查询账号列表和当前账号标记 |
| `POST /api/control/accounts` | 创建同权限账号 |
| `PATCH /api/control/accounts/{id}` | 启用、停用或重置密码 |
| `DELETE /api/control/accounts/{id}` | 删除账号 |

不要把真实密码提交到 Git。忘记全部数据库账号密码时，单纯修改初始化环境变量不会生效，需要受控的离线恢复流程；后续应补充专用恢复 CLI。

## 3. 批量 SOCKS 输入

### 3.1 数据格式

支持每行 5 列：

```text
住宅出口IP SOCKS接入地址 端口 用户名 密码
198.51.100.10 203.0.113.10 1080 upstream-user upstream-password
```

支持每行带序号的 6 列：

```text
序号 住宅出口IP SOCKS接入地址 端口 用户名 密码
1 198.51.100.10 proxy.example.com 1080 upstream-user upstream-password
```

第二列 SOCKS 接入地址允许填写 IPv4 或域名。没有独立接入地址、需要直接连接第一列住宅 IP 时使用 `-`：

```text
2 198.51.100.11 - 1080 upstream-user upstream-password
```

分隔符可以是一个或多个空格或 Tab。粘贴时前端和后端都会清理 BOM（`U+FEFF`）、NBSP（`U+00A0`）和全角空格（`U+3000`），以兼容 WPS/Excel 表格复制内容。空行忽略，单次最多处理 50 个非空行。

### 3.2 校验规则

- IP：当前支持 IPv4，必须为四段十进制且每段为 0–255。
- SOCKS 接入地址：允许 IPv4 或域名；可填写 `-`，此时实际接入地址使用第一列住宅出口 IP。域名按 IDN 转 ASCII 后校验标签和总长度。
- 端口：数字且范围为 1–65535。
- 用户名、密码：非空、最长 255 个字符，不允许空白字符和控制字符。
- 列数：只能是 5 列或 6 列。
- 协议：批量住宅节点固定生成 VLESS、VMess、SOCKS5 三种协议，前端不可取消，后端不信任客户端传入的协议列表并强制使用三协议。

解析结果保留文本中的真实行号。某行错误只在该行返回 `error`，其他有效行继续选择 Node Manager 并开通；响应结果按原始行号排序。

### 3.3 生成逻辑

前端调用：

```http
POST /api/control/allocations/proxy-provisions
Idempotency-Key: <每次操作的唯一值>
Content-Type: application/json
```

请求结构：

```json
{
  "input": "批量输入原文",
  "protocols": ["vless", "vmess", "socks"],
  "preferredNodeId": null
}
```

每个有效行以该行 SOCKS 用户名作为节点用户 ID，并生成独立远端幂等键。第一列作为住宅出口 IP 保存和展示，第二列作为 Node Manager 实际连接的上游 SOCKS `server`；第二列为 `-` 时才使用第一列。控制面把上游 `server`、端口、账号和密码传给 Node Manager 的创建用户接口，Node Manager 为该用户创建专属 SOCKS5 outbound，并让 VLESS、VMess、SOCKS5 三种入口通过 `auth_user` 路由到同一个住宅出口。

响应包含总数、成功数、失败数和逐行结果。逐行结果分别返回 `sourceIp`（住宅出口 IP）、`sourceAddress`（实际 SOCKS 接入地址）、`sourcePort`、`countryName` 和 `countryCode`。批量行只有在 Node Manager 返回 `proxyBound=true`、协议列表包含三协议且三种连接均非空时才计为成功；否则该行进入失败/可重试状态，不能误显示为原生住宅节点。成功卡片明确展示“原生住宅 · 三协议路由已绑定”、住宅出口 IP、国家、代码、SOCKS 接入、节点用户和三条连接；失败行包含脱敏后的 `error`。

住宅出口国家由 Control Plane 后端请求 GeoJS 获取，默认接口为 `https://get.geojs.io/v1/ip/geo/{ip}.json`。请求只包含第一列住宅出口 IP，不包含 SOCKS 账号、密码或生成连接。结果在内存中缓存 24 小时；GeoJS 超时、不可访问或返回异常时不影响节点创建，国家和代码降级为 `未知 / ZZ`。可使用 `CONTROL_PLANE_GEOIP_ENABLED`、`CONTROL_PLANE_GEOIP_BASE_URL`、连接/读取超时和缓存时长环境变量覆盖默认配置。

批量结果中的 SOCKS 通用链接由后端使用输入行中的上游 SOCKS 凭据生成，直接指向该行上游 SOCKS 接入地址。认证部分为 UTF-8 `用户名:密码` 的标准 Base64，备注使用国家代码和住宅出口 IP；不能使用 Node Manager 为本地 SOCKS 入口生成的账号密码代替上游凭据：

```text
socks://<Base64(用户名:密码)>@<SOCKS接入地址>:<端口>#<国家代码>-<住宅出口IP>
```

例如：`socks://<Base64认证>@198.13.46.231:5001#US-38.30.216.149`。该完整链接仅随本次批量结果返回并保存在 Vue 运行内存中，不写入浏览器持久化存储或日志。普通节点连接弹窗仍保留 `socks5://用户名:密码@节点地址:端口` 格式，两种链接的目标语义不同，不能混用。

## 4. 敏感数据处理

### 4.1 浏览器

- 账号、密码、上游 SOCKS 原文、生成连接均不写入 `localStorage` 或 `sessionStorage`。
- `localStorage` 仅保存不敏感的 `selected-node-id`。
- 登录密码、Node Manager Token、手动 SOCKS 密码和上游密码提交后清空。
- 批量输入提交后立即清空；连接结果仅保存在 Vue 运行内存。
- 完整连接默认遮罩，用户主动选择显示或复制时才使用明文。
- SOCKS 通用链接中的 Base64 只是客户端链接编码，不是加密；它与其他完整连接一样只保存在当前页面内存并默认遮罩。
- 退出、401 会话失效、关闭连接弹窗或页面卸载时清理内存敏感信息。
- Toast 只提示复制成功，不回显复制内容。
- 账号管理弹窗中的新密码只保存在 Vue 运行内存，提交成功或关闭弹窗时清空。

### 4.2 后端和数据库

- Node Manager Token、上游账号密码、VLESS、VMess、本地 SOCKS 凭据使用 AES-GCM 随机 IV 加密，数据库值以 `enc:v1:` 开头。
- 管理账号密码使用 BCrypt 单向哈希保存，账号 API 永不返回 `passwordHash`。
- `CONTROL_PLANE_ENCRYPTION_KEY` 是解密历史密文的根密钥。更换它会使已有密文不可解密，必须建立受控轮换/迁移流程后再修改。
- 批量开通捕获远端错误时，会将当前行的上游账号和密码替换为 `***` 后才返回和保存。
- 分配列表不返回连接密钥；单条详情和批量连接响应使用 `Cache-Control: no-store`。
- 代码不记录请求体、登录密码、上游凭据或完整连接 URI。

### 4.3 部署要求

- 生产环境必须使用 HTTPS 反向代理。
- `.env.local`、systemd EnvironmentFile 和数据库备份需限制文件权限和访问范围。
- Spring 配置按本地和生产拆分。服务器 EnvironmentFile 必须配置 `SPRING_PROFILES_ACTIVE=prod`；本地 IDEA 直接运行 `NodeControlApplication`，无需设置 Profile。
- `application.yml` 是被 Git 忽略的本地 IDEA 配置；生产配置位于可提交但不包含真实秘密的 `application-prod.yml`。
- 登录成功和管理页面业务数据加载分开处理：登录接口成功后立即关闭登录弹窗；节点或分配记录等后续接口失败时显示“部分数据加载失败”，不能把已成功的认证误报成账号密码错误或继续锁定登录弹窗。
- `residential_allocations.proxy_username_cipher` 使用可空 `TEXT` 保存加密后的上游 SOCKS 用户名，避免历史表中大量 `VARCHAR` 导致 InnoDB 单行大小超过 65535 字节。
- `CONTROL_PLANE_REGISTRATION_TOKEN` 只用于 Node Manager 注册，不能与管理密码或旧管理 Token 复用。
- `CONTROL_PLANE_ADMIN_TOKEN` 仅用于兼容受信 API 客户端；不需要时应留空并迁移调用方到更细粒度认证。

## 5. 构建与验证

```powershell
cd frontend
npm.cmd run build

cd ..\backend
mvn.cmd test
mvn.cmd clean package
```

Node Manager 回归：

```powershell
cd "..\..\Node Manager"
python -m unittest discover -s tests -v
```

### 5.1 数据库字段兼容修复

当前版本的 `residential_allocations` 表包含 `proxy_source_port`，用于保存原始 SOCKS 节点的端口。旧数据库如果没有该列，页面加载自动生成记录时会出现：

```text
Unknown column 'ra1_0.proxy_source_port' in 'field list'
```

Control Plane 启动时会针对 MySQL 检查该表和字段；仅当表已存在且字段缺失时执行一次幂等的 `ALTER TABLE`，不会删除或改写已有数据。非 MySQL 数据库（包括测试用 H2）会跳过该兼容处理。

运行数据库账号需要具备读取元数据和 `ALTER TABLE` 权限。若生产账号没有该权限，可由数据库管理员先手动执行：

```sql
ALTER TABLE residential_allocations
  ADD COLUMN proxy_source_port INT NULL
  AFTER proxy_source_ip;
```

MySQL 不支持 `ADD COLUMN IF NOT EXISTS`；执行前请先确认字段不存在。Control Plane 启动时会先检查元数据，再执行上述标准 MySQL 语句；并发启动时如果另一实例已经添加字段，会自动忽略重复字段错误。

后续仍应引入 Flyway/Liquibase 统一管理正式数据库迁移。

自动测试覆盖首次账号初始化、BCrypt 哈希、账号创建/停用/重置/删除、旧 Cookie 撤销、Cookie 属性、账号 API 不返回密码哈希、旧 Token 兼容、两种数据格式、WPS/Excel 空白清理、住宅出口 IP 与 SOCKS 接入地址分离、无效行隔离、结果排序、强制三协议、Node Manager 上游参数、住宅路由绑定响应校验、AES-GCM 密文和错误脱敏。

## 6. 一键安装 Node Manager

### 6.1 页面流程

管理员在“全部节点”区域点击“一键安装 Node Manager”，前端请求：

```http
POST /api/control/node-installation
```

该接口要求有效的管理 Cookie 或兼容管理 Token，并返回 `Cache-Control: no-store`。响应只包含当前安装命令、失效时间和有效秒数。前端只把命令保存在 Vue 运行内存，关闭弹窗、退出登录、会话失效或页面卸载都会清除。

`CONTROL_PLANE_PUBLIC_URL` 可选；配置后作为命令中的权威公网地址。未配置时，后端根据请求和 Spring 的 forwarded-header 处理结果推导当前 Control Plane 地址。生产反向代理必须正确传递 `X-Forwarded-Proto` 和 `X-Forwarded-Host`。

### 6.2 一次性安装码

- 使用 `SecureRandom` 生成 32 字节随机值，格式为 `niusu_<base64url>`。
- 默认有效期 600 秒，可用 `CONTROL_PLANE_INSTALL_TOKEN_TTL_SECONDS` 调整。
- 数据库 `node_install_tokens` 表只保存 SHA-256 摘要、创建者、创建/过期时间、占用和使用状态，不保存明文。
- 注册接口使用 `X-Install-Token`；旧 `X-Registration-Token` 长期令牌继续兼容。
- 注册前通过原子更新将安装码置为占用状态，防止两个 VPS 并发复用。
- Node Manager 验证或节点保存失败时释放占用，允许脚本按照 0/2/4/8/16 秒退避重试。
- 节点注册成功后记录 `usedAt` 和节点 UUID，该安装码永久失效。
- 卡死占用默认 120 秒后可重新获取，可用 `CONTROL_PLANE_INSTALL_CLAIM_TTL_SECONDS` 调整。

### 6.3 安装脚本行为

页面生成命令把 Control Plane 地址和一次性安装码作为两个参数传给 GitHub `main` 分支的 `install.sh`。脚本自动获取 VPS 公网 IP、复用或生成稳定节点 ID 与 API Token、安装并启动 sing-box 和 Node Manager，健康检查通过后用 `X-Install-Token` 注册。

安装码只写入权限为 `0600` 的临时 Header 文件，注册完成或脚本退出时删除，不写入 `/root/node-manager-info.txt`。命令可能进入 VPS Shell 历史，因此未使用的命令不能公开转发；成功使用或过期后安装码即不可复用。

网络要求：VPS 必须能访问 GitHub 和 Control Plane；Control Plane 必须能回连 VPS 的 Node Manager TCP 8088。云安全组和本机防火墙都必须允许该链路。

## 7. 数据库表边界与历史清理

Control Plane 当前只使用以下 5 张业务表：

- `control_users`：管理端账号与 BCrypt 密码哈希。
- `managed_nodes`：已注册的 Node Manager 节点。
- `node_install_tokens`：一次性安装码摘要及消费状态，不保存明文安装码。
- `remote_operations`：节点远程操作记录。
- `residential_allocations`：生成的节点用户、连接和上游 SOCKS 分配信息。

项目根目录的 `mysql/niusuip.sql` 属于 NiuSuIP 商品、订单、钱包和第三方接口业务库，不属于 Control Plane。该脚本不能在 `control-plane` 数据库中执行；需要使用时必须配置独立数据库。

2026-08-02 已清理误建在阿里云 `control-plane` 数据库中的 25 张 NiuSuIP 历史表。删除前把表结构和 121 行数据备份到本地 Git 忽略目录：

```text
backend/data/control-plane-db-backups/legacy-niusuip-before-drop-20260802-205836.sql
```

备份文件 SHA-256：

```text
cc33e47a601cbd7f4a883297049fa2b8e7bf58aa613aad3f9d44cb3c4ad87b40
```

恢复历史表时，应先确认目标库不是正在运行的 Control Plane 生产库，再使用 MySQL 客户端导入该备份。数据库备份可能含业务数据，不得提交 Git、发送到公开渠道或放入前端静态资源。

## 8. 关键实现文件

- `frontend/src/App.vue`：登录、批量输入、结果和敏感信息生命周期。
- `frontend/src/api.js`：同源 Cookie 请求、登录/退出和批量接口。
- `backend/.../domain/ControlUser.java`：数据库管理账号、BCrypt 密码哈希引用和会话版本。
- `backend/.../service/ControlAccountService.java`：首次初始化、认证与账号生命周期约束。
- `backend/.../web/ControlAccountController.java`：账号管理 API 和 no-store 响应。
- `backend/.../security/ControlSessionService.java`：会话签名与 Cookie。
- `backend/.../security/SecretCipher.java`：AES-GCM 加解密。
- `backend/.../service/ProvisioningService.java`：批量解析、逐行生成、幂等和错误脱敏。
- `backend/.../web/ControlTokenFilter.java`：Cookie 会话与旧 Token 兼容鉴权。
- `backend/.../web/ProvisioningController.java`：批量接口和 no-store 响应。
- `backend/.../service/NodeInstallationService.java`：一次性安装码生成、摘要、占用、释放和消费。
- `backend/.../web/NodeInstallationController.java`：生成 no-store 一键安装命令。
- `backend/.../web/AgentRegistrationController.java`：长期注册令牌和一次性安装码双通道注册。
# 本次页面与节点用户规则补充（2026-08-04）

## 页面结构

管理端侧边栏拆分为五个入口：

- 总览：在线节点、自动生成数量、连接和流量指标。
- 受管节点：查看全部 Node Manager，刷新状态、调度设置、移除节点和一键安装。
- 自动生成记录：查看直连或上游 SOCKS 的生成状态、重试和连接。
- 节点用户管理：按当前选中节点搜索、分页、查看连接、绑定代理、删除用户；“刷新”只重新读取当前节点用户列表。
- 节点管理：批量 SOCKS 节点信息输入和生成链接。

自动生成记录不再放在总览页，避免总览过长并减少敏感信息误展示。

## 节点用户 ID 规则

- 批量 SOCKS 输入时，节点用户 ID 直接使用该行的 SOCKS 用户名。
- 用户名必须是 1-64 个字符，并且只允许字母、数字、点、下划线和短横线；非法行单独提示，不影响其他行。
- 已取消“节点用户前缀”配置。旧客户端仍可发送该字段，但服务端会忽略它。
- VPS 直连手动生成时仍可指定用户 ID；未指定时由服务端按请求幂等键生成 `node-<摘要>` 默认 ID。
- Node Manager 内部仍使用 `node-manager:<userId>` 作为 VLESS/VMess 认证名称，以兼容 sing-box 配置；该内部名称不会展示为节点用户 ID。历史配置中的直接用户 ID仍可查询和删除，但新路由规则不会额外添加重复的直接 ID 别名。

## 代理凭据查看

自动生成记录列表只返回代理服务器和端口，不返回 SOCKS 用户名/密码。点击“上游 SOCKS”后，前端请求单条详情接口，服务端临时解密并返回账号密码；弹窗关闭、退出登录或会话失效时清理前端内存。代理密码不会进入日志、浏览器持久化存储或普通列表响应。

接口链路如下：

```http
GET /api/control/nodes/{nodeId}/users/{userId}/proxy
```

Control Plane 通过节点访问令牌调用 Node Manager：

```http
GET /api/user/{userId}/proxy
Authorization: Bearer <node-manager-token>
```

未绑定代理时响应为 `proxyBound: false`，凭据字段为空；已绑定时响应字段为：

```json
{
  "userId": "节点用户 ID",
  "proxyBound": true,
  "server": "代理服务器",
  "port": 1080,
  "username": "按需返回",
  "password": "按需返回"
}
```

两个接口均设置 `Cache-Control: no-store`。批量生成记录的列表接口不会返回 `username`、`password`；详情接口才会解密返回。批量输入记录中的 `sourcePort` 表示原始 SOCKS 接入端口，`proxyPort` 表示上游代理服务器端口，二者不混用。

## 后续优化计划

1. 增加批量用户 ID 冲突预检和可下载的逐行错误报告。
2. 增加节点用户管理的标签、批量删除和按出口模式过滤。
3. 为代理详情增加短时授权确认和审计事件（只记录操作者与分配 ID，不记录密码）。
4. 将自动生成记录改为服务端分页，降低大规模节点池的首屏响应时间。

## 节点用户判重规则（2026-08-05）

节点用户的唯一性不是 Control Plane 全局唯一，而是以下组合唯一：

```text
节点服务器 IP（或可识别的服务器主机） + 节点用户名
```

判定顺序：

1. 优先使用 Node Manager 最近一次心跳上报的 `host`。当该字段是 IPv4/IPv6 时，按规范化后的 IP 判定。
2. 如果心跳 `host` 不是 IP，则回退到节点登记 `baseUrl` 的主机部分；只有该主机部分本身是 IPv4/IPv6 时才接受。这样同一 VPS 使用不同端口或重复登记 API 地址时仍能识别为同一服务器。
3. 心跳和 `baseUrl` 都没有可确认的 IP 时，拒绝新增并提示先刷新节点心跳，避免把可变化的域名别名误当作服务器身份。
4. 远端用户预检会遍历同一服务器 IP 下的全部节点登记，防止同一 VPS 通过不同端口、域名或重复登记绕过判重。

新增直连用户、批量 SOCKS 用户和手动创建用户都调用同一套判重逻辑：

- 同一服务器 IP + 同一用户名：拒绝新增。
- 不同服务器 IP + 同一用户名：允许新增。
- 本地历史分配记录处于 `FAILED`、已解除节点关联，或对应 Node Manager 用户已经被删除：释放历史记录，不再阻止重新创建。
- 在写入新分配前，还会查询目标 Node Manager 的 `/api/users?keyword=<用户名>`；即使 Control Plane 没有历史记录，只要远端当前仍存在该用户，也会拒绝新增。
- 删除节点登记前会解除历史分配与节点的关联，避免删除节点后残留记录继续造成误判。

实现位置：

- `backend/src/main/java/com/example/nodecontrol/service/ProvisioningService.java`：服务器身份规范化、远端用户预检、本地活动分配判重。
- `backend/src/main/java/com/example/nodecontrol/service/NodeUserService.java`：手动创建和删除用户复用判重/释放逻辑。
- `backend/src/main/java/com/example/nodecontrol/service/ManagedNodeService.java`：删除节点时解除历史分配关联。
- `backend/src/main/java/com/example/nodecontrol/config/SchemaCompatibilityMigration.java`：移除历史 `control_user_id` 全局唯一索引，保留按服务器逻辑判重。

上线时请确认数据库不存在旧的全局唯一索引；应用启动会尝试自动迁移，但仍建议检查：

```sql
SHOW INDEX FROM residential_allocations;
SHOW COLUMNS FROM residential_allocations LIKE 'proxy_source_port';
```
