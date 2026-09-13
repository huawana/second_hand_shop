-- ============================================================================
--  Phase 1 数据库迁移脚本：认证授权改造
--  执行方式：mysql -uroot -proot shop < docs/migration_phase1.sql
--
--  背景：Phase 0 的审计已经指出两个必须在 Phase 1 解决的问题
--    1) 密码用无盐 MD5 存储 → 改为 BCrypt（自带随机盐 + 慢哈希）
--    2) 没有角色概念 → 补 role 字段以支持 RBAC
--
--  【为什么必须扩列】BCrypt 摘要固定 60 个字符（形如 $2a$10$...），
--  而原列是 varchar(50) —— 不扩列会在写入时报 "Data too long for column"。
--  这类「改造到一半才炸」的问题，正是必须在动手前先做数据模型影响分析的原因。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 密码列扩容（MD5 32 字符 → BCrypt 60 字符，留出余量到 100）
-- ---------------------------------------------------------------------------
ALTER TABLE `lxy_user`
    MODIFY COLUMN `password` varchar(100) DEFAULT NULL COMMENT 'BCrypt 摘要（$2a$...）；过渡期可能残留 MD5';

ALTER TABLE `lxy_admin`
    MODIFY COLUMN `adminpass` varchar(100) DEFAULT NULL COMMENT 'BCrypt 摘要（$2a$...）；过渡期可能残留 MD5';

-- ---------------------------------------------------------------------------
-- 2. 角色与状态字段（RBAC 的基础）
--    role   ：USER / ADMIN，决定能访问哪些资源
--    status ：1 正常 / 0 禁用，配合登录校验实现「封号」能力
--    存量 30 个用户默认全部是 USER，避免迁移后权限错配。
-- ---------------------------------------------------------------------------
ALTER TABLE `lxy_user`
    ADD COLUMN `role`   varchar(20) NOT NULL DEFAULT 'USER' COMMENT '角色：USER / ADMIN（RBAC）' AFTER `password`,
    ADD COLUMN `status` tinyint     NOT NULL DEFAULT 1      COMMENT '状态：1 正常 / 0 禁用' AFTER `role`;

-- 按角色查用户（后台用户列表、审计）用得上
ALTER TABLE `lxy_user`
    ADD KEY `idx_role` (`role`);

-- ---------------------------------------------------------------------------
-- 3. 校验（应看到 password/adminpass 为 varchar(100)，且 role/status 已存在）
-- ---------------------------------------------------------------------------
SHOW COLUMNS FROM `lxy_user` LIKE 'password';
SHOW COLUMNS FROM `lxy_user` LIKE 'role';
SHOW COLUMNS FROM `lxy_user` LIKE 'status';
SHOW COLUMNS FROM `lxy_admin` LIKE 'adminpass';
