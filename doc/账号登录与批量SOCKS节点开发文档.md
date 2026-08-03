# NiuSu Control 账号登录与批量 SOCKS 节点开发文档

## 1. 功能范围

本次实现包含两条完整链路：

1. 管理端从浏览器保存 `X-Control-Token` 改为数据库多账号登录，后端签发 HttpOnly Cookie 会话。
2. 首页增加“节点信息输入”，批量校验上游 SOCKS 数据，并在可用 Node Manager 上生成节点用户及 VLESS、VMess、SOCKS5 连接。

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
IP地址 域名 端口 用户名 密码
198.51.100.10 proxy.example.com 1080 upstream-user upstream-password
```

支持每行带序号的 6 列：

```text
序号 IP地址 域名 端口 用户名 密码
1 198.51.100.10 proxy.example.com 1080 upstream-user upstream-password
```

没有域名时使用 `-`：

```text
2 198.51.100.11 - 1080 upstream-user upstream-password
```

分隔符可以是一个或多个空格或 Tab。粘贴时前端和后端都会清理 BOM（`U+FEFF`）、NBSP（`U+00A0`）和全角空格（`U+3000`），以兼容 WPS/Excel 表格复制内容。空行忽略，单次最多处理 50 个非空行。

### 3.2 校验规则

- IP：当前支持 IPv4，必须为四段十进制且每段为 0–255。
- 域名：可填写 `-`；其他值按 IDN 转 ASCII 后校验域名标签和总长度。
- 端口：数字且范围为 1–65535。
- 用户名、密码：非空、最长 255 个字符，不允许空白字符和控制字符。
- 列数：只能是 5 列或 6 列。
- 用户前缀：字母、数字、点、下划线、连字符，最长 32 个字符。
- 协议：至少选择 VLESS、VMess、SOCKS 中的一种。

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
  "preferredNodeId": null,
  "userPrefix": "socks"
}
```

每个有效行生成稳定用户 ID 和独立远端幂等键。域名存在时 Node Manager 上游 `server` 使用域名，否则使用 IP。控制面把上游 `server`、端口、账号和密码传给 Node Manager 的创建用户接口，Node Manager 将该用户绑定到对应 SOCKS5 出口。

响应包含总数、成功数、失败数和逐行结果。成功行的 `allocation.connection` 可包含 VLESS、VMess、SOCKS5；失败行可没有 `allocation`，但包含脱敏后的 `error`。

SOCKS5 复制格式为标准 URI：

```text
socks5://<URL编码用户名>:<URL编码密码>@<主机>:<端口>
```

## 4. 敏感数据处理

### 4.1 浏览器

- 账号、密码、上游 SOCKS 原文、生成连接均不写入 `localStorage` 或 `sessionStorage`。
- `localStorage` 仅保存不敏感的 `selected-node-id`。
- 登录密码、Node Manager Token、手动 SOCKS 密码和上游密码提交后清空。
- 批量输入提交后立即清空；连接结果仅保存在 Vue 运行内存。
- 完整连接默认遮罩，用户主动选择显示或复制时才使用明文。
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

自动测试覆盖首次账号初始化、BCrypt 哈希、账号创建/停用/重置/删除、旧 Cookie 撤销、Cookie 属性、账号 API 不返回密码哈希、旧 Token 兼容、两种数据格式、WPS/Excel 空白清理、无效行隔离、结果排序、Node Manager 上游参数、AES-GCM 密文和错误脱敏。

## 6. 数据库表边界与历史清理

Control Plane 当前只使用以下 4 张业务表：

- `control_users`：管理端账号与 BCrypt 密码哈希。
- `managed_nodes`：已注册的 Node Manager 节点。
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

## 7. 关键实现文件

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
