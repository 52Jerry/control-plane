-- 添加 dead_outbound_count 列到 managed_nodes 表
-- 用于存储 node-manager 健康检查上报的失效代理数量
ALTER TABLE managed_nodes ADD COLUMN dead_outbound_count INT NOT NULL DEFAULT 0;
