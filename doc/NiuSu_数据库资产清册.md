# NiuSu 数据库资产清册（Control Plane 侧优化规划用）

> 版本：v1.0（2026-08-05）
> 数据来源：授权环境中的 MySQL `information_schema` 元数据查询。
> 安全约定：本文件只记录结构和变量名，不记录数据库主机、账号、密码、加密密钥、令牌或其他连接秘密。

---

## 1. 数据库实例元数据

| 参数 | 值 |
| --- | --- |
| 唯一标识符（实例 ID） | 通过部署环境变量或云控制台查看 |
| 数据库类型 | MySQL（阿里云 RDS） |
| 数据库版本 | **8.0.36**（InnoDB 引擎；`innodb_version=8.0.36`） |
| 部署环境 | 生产（阿里云 RDS） |
| 连接地址 | `${CONTROL_PLANE_DB_HOST}:${CONTROL_PLANE_DB_PORT}` |
| 连接用户 | `${CONTROL_PLANE_DB_USERNAME}` |
| 端口 | `${CONTROL_PLANE_DB_PORT:3306}` |
| max_connections | **1120** |
| 默认字符集/排序 | `utf8mb3_general_ci`（实例级）；业务库为 `utf8mb4` |
| lower_case_table_names | 1（表名大小写不敏感） |
| 状态 | 连接正常、可读 |

> 注：CPU/内存/存储的**精确规格**需在阿里云控制台 RDS 实例详情中查看；MySQL 侧无法直接读取实例规格，仅能确认连接与版本。当前已用集群数据量见下方各库大小。

## 2. 数据库列表（业务库）

| 数据库标识符 | 数据库名称 | 类型 | 版本 | 环境 | 所属项目/业务线 | 状态 | 表数 | 总大小 | 数据大小 | 索引大小 | 评估行数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DB-001 | `control-plane` | MySQL/InnoDB | 8.0.36 | 生产 | Control Plane 控制面 | 运行中 | 5 | 0.20 MB | 0.08 MB | 0.12 MB | 36 |
| DB-002 | `niusuip` | MySQL/InnoDB | 8.0.36 | 生产 | NiuSuIP 电商/订单/钱包 | 运行中 | 29 | 9.84 MB | 8.03 MB | 1.81 MB | 9,257 |
| DB-003 | `nodemanger` | MySQL/InnoDB | 8.0.36 | 生产 | Node Manager（历史/保留） | 运行中（空） | 0 | 0.00 MB | — | — | 0 |
| — | `__recycle_bin__` | MySQL | 8.0.36 | 生产 | 系统回收站 | 空 | 0 | 0.00 MB | — | — | 0 |
| — | `mysql` / `sys` / `performance_schema` / `information_schema` | 系统库 | 8.0.36 | 生产 | MySQL 系统/元数据 | 运行中 | — | — | — | — | — |

**说明**：
- `nodemanger` 与 `__recycle_bin__` 均为空库，无业务表，可纳入清理评估。
- `performance_schema` 112 表、`mysql` 45 表为系统表，不计入业务。

## 3. 业务表清单

### 3.1 control-plane（5 张）
| 表名 | 引擎 | 行数 | 大小 | 创建时间 | 最近更新时间 | 业务用途 |
| --- | --- | --- | --- | --- | --- | --- |
| `control_users` | InnoDB | 2 | 0.03 MB | 2026-08-01 17:56:46 | 2026-08-04 18:36:48 | 管理端账号（BCrypt） |
| `managed_nodes` | InnoDB | 2 | 0.05 MB | 2026-08-05 14:21:56 | 2026-08-04 18:39:48 | 受管 Node Manager 节点 |
| `node_install_tokens` | InnoDB | 17 | 0.03 MB | 2026-08-03 18:15:04 | 2026-08-04 18:57:14 | 一次性安装码摘要 |
| `remote_operations` | InnoDB | 7 | 0.03 MB | 2026-08-01 12:27:34 | 2026-08-04 21:21:32 | 节点远程操作记录 |
| `residential_allocations` | InnoDB | 8 | 0.06 MB | 2026-08-04 21:47:11 | 2026-08-04 21:49:26 | 节点用户/连接/上游SOCKS分配 |

### 3.2 niusuip（29 张，节选主要业务表）
| 表名 | 行数 | 大小 | 业务用途 |
| --- | --- | --- | --- |
| `third_party_api_log` | 3,037 | 5.11 MB | 第三方 API 日志（最大表） |
| `inventory_snapshot` | 2,749 | 1.75 MB | 库存快照 |
| `sync_task_log` | 2,765 | 1.73 MB | 同步任务日志 |
| `product_country_price` | 496 | 0.09 MB | 商品国家价格 |
| `product_sku` | 44 | 0.06 MB | 商品 SKU |
| `user_wallet_record` | 22 | 0.05 MB | 钱包流水 |
| `payment_order` / `purchase_order` / `sales_order` | 各 3 | 各 0.08 MB | 支付/采购/销售订单 |
| `user_ip_resource` | 3 | 0.09 MB | 用户 IP 资源 |
| `product_category` / `region_country` / `region_city` | 8/31/61 | — | 商品类目/地区主数据 |

## 4. 数据库连接配置（开发/运维使用）

| 项 | 值 |
| --- | --- |
| JDBC URL | `${CONTROL_PLANE_DB_URL}`，未设置时由 `CONTROL_PLANE_DB_HOST/PORT/NAME` 组合 |
| 主机 | `${CONTROL_PLANE_DB_HOST}` |
| 端口 | `${CONTROL_PLANE_DB_PORT:3306}` |
| 用户 | `${CONTROL_PLANE_DB_USERNAME}` |
| 密码 | `${CONTROL_PLANE_DB_PASSWORD}`（仅通过服务器环境变量或受保护的密钥文件注入） |
| 加密密钥 | `${CONTROL_PLANE_ENCRYPTION_KEY}`（必须与线上一致，禁止写入文档） |

## 5. 对 Control Plane 优化规划的支撑结论

1. **数据量小、无性能压力**：control-plane 仅 36 行、0.2MB；niusuip 最大表 5MB。当前无索引/分页刚需，**优化重点应放在代码正确性与扩展性**，而非大规模性能。
2. **实例版本 8.0.36**：可平滑引入 Flyway/Liquibase 迁移；`lower_case_table_names=1` 需在迁移脚本中统一小写表名。
3. **业务库分离**：`control-plane` 与 `niusuip` 为独立库，`niusuip.sql` 不得在 `control-plane` 库执行（已确认两者独立）。
4. **空库清理**：`nodemanger`、`__recycle_bin__` 为空，可纳入清理评估；执行删除前必须完成备份和审批。
5. **连接信息敏感**：`.env.local` 连接的是生产 RDS，本地开发需保持 `NODE_MANAGER_BOOTSTRAP_ENABLED=false`，并避免 Hibernate 自动改表。

## 6. 待补充（需运维/云控制台提供）

- 各库所在 RDS 实例的 **CPU / 内存 / 存储规格**与使用率。
- 备份策略、网络白名单、实例所在地域/可用区。
- 各连接账号的最小权限（建议 `root` 替换为最小权限账号）。
