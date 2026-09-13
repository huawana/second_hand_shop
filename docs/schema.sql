-- ============================================================================
--  校园二手交易平台 —— 数据库建表脚本
--  来源：mysqldump 导出现网 shop 库（2026-09-13），并修正以下 4 类设计缺陷
--
--  【修正记录】（面试可讲：我接手后做了数据库审计，发现并修复了这些）
--   1. lxy_order.created_at 原为 varchar(200)  → 改为 datetime（时间用字符串存无法比较/无法建索引）
--   2. 全库字符集混用 utf8mb3 / utf8mb4       → 统一 utf8mb4_unicode_ci（utf8mb3 存不了 emoji）
--   3. 全库零二级索引                          → 按实际查询模式补索引（见各表注释）
--   4. lxy_product.price 为 decimal(10,2) unsigned → 保留（价格不可为负，设计合理）
--
--  【本阶段不做的事】（留给 Phase 2 领域建模重构）
--   - 不加外键约束：现有 deleteProduct 会直接物理删除商品，
--     而 lxy_order 可能引用该商品，加 FK 会破坏既有删除链路。
--     Phase 2 引入逻辑删除（is_deleted）后统一加 FK。
--   - 不改表名（lxy_ 前缀）、不拆表（cart 逗号字符串 → cart_item 表）
--
--  【遗留死表说明】chat_records / lxy_mate 是原项目未完成的好友与私聊功能，
--  代码零引用但数据存在（见 Phase 8 补完计划）；lxy_product_sale 是废弃的中间设计。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `shop`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `shop`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------------
-- 用户表
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `lxy_user`;
CREATE TABLE `lxy_user` (
    `id`          int unsigned NOT NULL AUTO_INCREMENT,
    `username`    varchar(100) NOT NULL DEFAULT '' COMMENT '登录名',
    -- Phase 1：扩容到 100 以容纳 BCrypt 摘要（固定 60 字符）。过渡期可能残留旧的无盐 MD5。
    `password`    varchar(100) DEFAULT NULL COMMENT 'BCrypt 摘要（$2a$...），过渡期可能残留 MD5',
    `role`        varchar(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER / ADMIN（RBAC，Phase 1 新增）',
    `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '状态：1 正常 / 0 禁用（Phase 1 新增）',
    `email`       varchar(100) DEFAULT NULL,
    `phone`       varchar(20)  DEFAULT NULL,
    `created_at`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `province`    varchar(100) DEFAULT NULL,
    `city`        varchar(100) DEFAULT NULL,
    `area`        varchar(100) DEFAULT NULL,
    `school`      varchar(100) DEFAULT NULL,
    `search`      text COMMENT '搜索历史，格式"关键词:[权重,天数,初始权重]"，Phase 2 迁到独立表',
    PRIMARY KEY (`id`),
    -- 登录/注册均按 username 精确查，原库只有主键索引 → 每次登录全表扫描
    UNIQUE KEY `uk_username` (`username`),
    -- 按学校筛选商品的场景会先查 user 表
    KEY `idx_school` (`school`),
    -- Phase 1：按角色查用户（后台用户列表 / 审计）
    KEY `idx_role` (`role`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 58
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '用户';

-- ---------------------------------------------------------------------------
-- 商品表
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `lxy_product`;
CREATE TABLE `lxy_product` (
    `id`             int unsigned  NOT NULL AUTO_INCREMENT,
    `name`           varchar(200)  NOT NULL DEFAULT '',
    `uid`            int           NOT NULL COMMENT '卖家 user.id',
    `description`    text,
    `price`          decimal(10, 2) unsigned NOT NULL DEFAULT '0.00' COMMENT '价格，不可为负',
    `created_at`     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `sold_time`      date          DEFAULT NULL COMMENT 'NULL = 在售',
    `img_store_path` varchar(100)  NOT NULL DEFAULT '' COMMENT 'URL 路径，非磁盘路径',
    `view_count`     int           DEFAULT '0',
    PRIMARY KEY (`id`),
    -- 列表页固定条件 WHERE sold_time IS NULL，配合排序 created_at DESC
    KEY `idx_sold_created` (`sold_time`, `created_at`),
    -- 按卖家查"我发布的商品"
    KEY `idx_uid` (`uid`),
    -- 搜索走 name LIKE '%kw%'（左模糊，索引用不上，Phase 7 换 ES）
    KEY `idx_name` (`name`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 3668
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '商品';

-- ---------------------------------------------------------------------------
-- 订单表
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `lxy_order`;
CREATE TABLE `lxy_order` (
    `id`         int unsigned NOT NULL AUTO_INCREMENT,
    `product_id` int          DEFAULT NULL COMMENT '商品 id',
    `sell_uid`   int          DEFAULT NULL COMMENT '卖家 user.id',
    `buy_uid`    int          DEFAULT NULL COMMENT '买家 user.id',
    -- 【修正】原为 varchar(200)，导致无法做时间范围查询、无法排序、无法建有效索引
    `created_at` datetime     DEFAULT NULL COMMENT '下单时间',
    `condition`  varchar(200) DEFAULT NULL COMMENT '订单状态（保留字风格命名，Phase 2 改为 status）',
    PRIMARY KEY (`id`),
    KEY `idx_product_id` (`product_id`),
    -- "我买到的" / "我卖出的" 两个列表都按 uid 过滤
    KEY `idx_buy_uid` (`buy_uid`),
    KEY `idx_sell_uid` (`sell_uid`),
    -- 后台按状态查订单
    KEY `idx_condition` (`condition`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 32
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '订单';

-- ---------------------------------------------------------------------------
-- 购物车表
-- 【设计缺陷】用 products 字段逗号拼接商品 id，不是关联表：
--   - 无法 JOIN、无数量字段、并发修改会互相覆盖（丢失更新）
--   - 也无 UNIQUE 约束，同一商品可重复加入（靠代码里 contains 兜住）
--   Phase 2 重构为 cart_item(id, user_id, sku_id, quantity, picked)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `lxy_cart`;
CREATE TABLE `lxy_cart` (
    `id`         int unsigned NOT NULL AUTO_INCREMENT COMMENT '与 lxy_user.id 同值，非独立主键',
    `products`   text COMMENT '逗号拼接的商品 id，如 "12,45,78"',
    `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 58
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '购物车（待重构）';

-- ---------------------------------------------------------------------------
-- 管理员表
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `lxy_admin`;
CREATE TABLE `lxy_admin` (
    `id`         int unsigned NOT NULL AUTO_INCREMENT,
    `adminuser`  varchar(50)  NOT NULL DEFAULT '',
    -- Phase 1：同样扩容以容纳 BCrypt。管理员不单独设 role 字段——
    -- 「在这张表里」本身就意味着 ADMIN 角色，用表区分比用字段更简洁。
    `adminpass`  varchar(100) DEFAULT NULL COMMENT 'BCrypt 摘要（$2a$...），过渡期可能残留 MD5',
    `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `login_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_adminuser` (`adminuser`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 2
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '管理员';

-- ---------------------------------------------------------------------------
-- 【遗留】私聊消息 —— 原项目未完成，Phase 8 补完
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `chat_records`;
CREATE TABLE `chat_records` (
    `id`          int       NOT NULL AUTO_INCREMENT,
    `sender_id`   int       DEFAULT NULL COMMENT '发送者 user.id',
    `receiver_id` int       DEFAULT NULL COMMENT '接收者 user.id',
    `message`     text,
    `timestamp`   timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 会话查询：WHERE (sender_id=? AND receiver_id=?) OR 反向，取最近 N 条
    KEY `idx_conversation` (`sender_id`, `receiver_id`, `timestamp`),
    KEY `idx_receiver` (`receiver_id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 31
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '私聊消息（未完成功能）';

-- ---------------------------------------------------------------------------
-- 【遗留】好友关系 —— 原项目未完成，Phase 8 补完
-- 【设计缺陷】无 UNIQUE 约束，同一对好友可重复插入；无方向区分（A-B 与 B-A 两条记录）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `lxy_mate`;
CREATE TABLE `lxy_mate` (
    `id`   bigint NOT NULL AUTO_INCREMENT,
    `uid1` bigint NOT NULL COMMENT '用户 1',
    `uid2` bigint NOT NULL COMMENT '用户 2',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mate` (`uid1`, `uid2`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 21
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '好友关系（未完成功能）';

-- ---------------------------------------------------------------------------
-- 【废弃】商品交易快照表 —— 代码零引用，保留仅为数据考古
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `lxy_product_sale`;
CREATE TABLE `lxy_product_sale` (
    `id`           int          DEFAULT NULL,
    `name`         varchar(255) DEFAULT NULL,
    `description`  text,
    `img_path`     varchar(255) DEFAULT NULL,
    `seller_id`    int          DEFAULT NULL,
    `buyer_id`     int          DEFAULT NULL,
    `created_time` datetime     DEFAULT NULL,
    `selled_time`  datetime     DEFAULT NULL,
    `price`        double       DEFAULT NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '废弃表，无代码引用';

SET FOREIGN_KEY_CHECKS = 1;
