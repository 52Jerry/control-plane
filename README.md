# Node Control Plane MVP

这是一个独立于 `Node-Manager` agent 的 Spring Boot + Vue 控制面。控制面保存节点注册信息，定时读取心跳，并通过后端代理调用远端 Node Manager API，避免把节点 Bearer Token 暴露到浏览器。

## 已实现

- 节点注册、移除、手动刷新与 15 秒心跳调度
- 在线、降级、离线状态和 CPU、内存、连接、用户、流量汇总
- 节点用户分页、搜索、创建、删除
- VLESS、VMess、SOCKS5 连接信息查看与复制
- 创建用户时绑定或后续绑定住宅 SOCKS5 出口
- sing-box 远程重载
- H2 文件数据库保存节点注册信息
- 可选 `X-Control-Token` 控制面访问保护
- Vue 开发代理与前后端合并打包

## 项目结构

```text
control-plane/
├─ backend/       Spring Boot 3 / Java 21
├─ frontend/      Vue 3 / Vite
├─ scripts/       Windows PowerShell 启动与构建脚本
└─ .env.local     本机环境变量，不提交 Git
```

## 本地启动

首次安装前端依赖：

```powershell
cd D:\project\demoNode\control-plane\frontend
npm.cmd install
```

终端一启动后端：

```powershell
cd D:\project\demoNode\control-plane
.\scripts\dev-backend.ps1
```

终端二启动前端：

```powershell
cd D:\project\demoNode\control-plane
.\scripts\dev-frontend.ps1
```

访问 `http://localhost:5173`。后端健康检查为 `http://localhost:8090/actuator/health`。

## 配置

复制 `.env.example` 为 `.env.local`，至少设置节点 API Token：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NODE_MANAGER_BASE_URL` | `http://198.13.46.231:8088` | 启动时自动注册的 agent 地址 |
| `NODE_MANAGER_TOKEN` | 空 | Node Manager Bearer Token；为空时不自动注册 |
| `NODE_MANAGER_NAME` | `Vultr Node` | 自动注册节点名称 |
| `CONTROL_PLANE_ADMIN_TOKEN` | 空 | 非空时前端必须输入控制面令牌 |
| `CONTROL_PLANE_PORT` | `8090` | Spring Boot 端口 |
| `HEARTBEAT_INTERVAL_MS` | `15000` | 节点心跳轮询间隔 |

## 构建单体包

```powershell
cd D:\project\demoNode\control-plane
.\scripts\build.ps1
.\scripts\run-jar.ps1
```

构建脚本先生成 Vue `dist`，Maven 再将它复制到 Spring Boot 的 `static` 目录。`run-jar.ps1` 会加载 `.env.local` 后启动单体包，访问地址为 `http://localhost:8090`。生产环境建议设置强随机 `CONTROL_PLANE_ADMIN_TOKEN`，并通过 HTTPS 反向代理暴露控制面。

## Node Manager API 映射

| 控制面功能 | Node Manager API |
| --- | --- |
| 注册验证 | `GET /api/agent/info` |
| 心跳 | `GET /api/agent/heartbeat` |
| 用户列表 | `GET /api/users` |
| 创建用户 | `POST /api/user/create` |
| 连接信息 | `GET /api/user/{userId}/connections` |
| 用户流量 | `GET /api/user/{userId}/traffic` |
| 绑定代理 | `POST /api/user/bind-proxy` |
| 删除用户 | `DELETE /api/user/delete/{userId}` |
| 重载 sing-box | `POST /api/singbox/reload` |
