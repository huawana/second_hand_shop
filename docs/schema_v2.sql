-- ============================================================================
--  Phase 2 领域建模 —— 表结构升级脚本（schema_v2）
--
--  【设计原则】非破坏性 + 幂等，可反复执行：
--    1. 只新增表和列，不删表、不改列类型、不改表名 —— 旧的 lxy_* 结构与数据完整保留，
--       作为「兼容层」与业务代码并行一段时间（灰度思路），确认新链路稳定后再切主。
--    2. 用 CREATE TABLE IF NOT EXISTS；列与索引通过 information_schema 判断后再 ALTER
--       （MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，所以必须走存储过程动态 SQL）。
--       这一点很重要：迁移脚本要能在任何环境重复跑而不报错，否则回滚/重试时非常痛苦。
--    3. 数据回填一律 INSERT ... SELECT + WHERE NOT EXISTS / ON DUPLICATE KEY，
--       保证「已经迁过的数据不会二次插入」。
--
--  【本阶段新增的表】
--    category    商品分类
--    cart_item   购物车关联表 —— 替换 lxy_cart.products 逗号串（核心重构）
--    order_item  订单明细（快照商品名与价格）
--    address     收货地址（一对多）
--    review      商品评价
--    payment     支付流水
--
--  【本阶段给旧表加的列】（原列一律保留）
--    lxy_product  +category_id +stock +status +version(乐观锁)
--    lxy_order    +order_no(唯一) +status +total_amount +pay_time
--
--  【兼容层说明】lxy_order.condition 与新增的 status 会「双写」一段时间：
--    condition 保留中文旧值供既有页面/查询使用；status 是新状态机的机器可读编码。
--    等所有读路径都切到 status 之后，condition 即可废弃 —— 这样才能做到随时可回退。
-- ============================================================================

USE `shop`;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
--  0. 幂等 DDL 工具过程
-- ---------------------------------------------------------------------------

DROP PROCEDURE IF EXISTS `add_column_if_missing`;
DELIMITER $$
CREATE PROCEDURE `add_column_if_missing`(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = p_table
                     AND COLUMN_NAME = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
        SELECT CONCAT('  + 已添加列 ', p_table, '.', p_column) AS step;
    ELSE
        SELECT CONCAT('  = 列已存在，跳过 ', p_table, '.', p_column) AS step;
    END IF;
END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS `add_index_if_missing`;
DELIMITER $$
CREATE PROCEDURE `add_index_if_missing`(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = p_table
                     AND INDEX_NAME = p_index) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
        PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
        SELECT CONCAT('  + 已创建索引 ', p_table, '.', p_index) AS step;
    ELSE
        SELECT CONCAT('  = 索引已存在，跳过 ', p_table, '.', p_index) AS step;
    END IF;
END$$
DELIMITER ;

-- ---------------------------------------------------------------------------
--  1. 新表：商品分类
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `category` (
    `id`         int unsigned NOT NULL AUTO_INCREMENT,
    `name`       varchar(50)  NOT NULL DEFAULT '' COMMENT '分类名',
    `parent_id`  int unsigned NOT NULL DEFAULT 0  COMMENT '父分类 id，0 = 一级分类（预留树形结构）',
    `sort`       int          NOT NULL DEFAULT 0  COMMENT '排序，越小越靠前',
    `status`     tinyint      NOT NULL DEFAULT 1  COMMENT '1 启用 / 0 停用',
    `created_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_parent_sort` (`parent_id`, `sort`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '商品分类';

-- ---------------------------------------------------------------------------
--  2. 新表：购物车明细（替换 lxy_cart.products 逗号串）
--
--  【为什么必须重构】（面试可直接讲）
--    旧设计 `products = "2,1"` 的四个硬伤：
--      a) 无数量字段 —— 二手平台虽然默认 1 件，但表结构上无法表达数量
--      b) 无法 JOIN商品表 —— 每次展示都要把 id 串拆开再逐个查库（N+1）
--      c) 并发写丢失更新 —— 两个请求同时「读串→改串→写回」，后写的覆盖先写的
--      d) 无唯一约束 —— 同一商品可重复加入，只能靠代码 contains 去重
--    改成关联表后：JOIN 一次取完、`uk_user_product` 由数据库保证不重复、
--    并发改不同商品互不干扰（改的是不同行，不是同一行的同一个字符串）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `cart_item` (
    `id`         bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id`    int unsigned    NOT NULL COMMENT '用户 id',
    `product_id` int unsigned    NOT NULL COMMENT '商品 id',
    `quantity`   int             NOT NULL DEFAULT 1 COMMENT '数量（二手商品一般为 1）',
    `picked`     tinyint         NOT NULL DEFAULT 1 COMMENT '是否勾选结算：1 是 / 0 否',
    `created_at` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 由数据库保证「同一用户同一商品只有一条」，代码不必再去重
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
    KEY `idx_product` (`product_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '购物车明细';

-- ---------------------------------------------------------------------------
--  3. 新表：订单明细（商品名与价格做快照）
--
--  【为什么要快照】订单是「历史凭证」：卖家之后改了商品名/价格，
--  历史订单必须仍然显示成交当时的信息。lxy_order 目前直接引用 product_id，
--  一旦商品下架或改名，历史订单展示就跟着变了 —— 这是电商建模的经典错误。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_item` (
    `id`            bigint unsigned   NOT NULL AUTO_INCREMENT,
    `order_id`      int unsigned      NOT NULL COMMENT '所属订单 id',
    `product_id`    int unsigned      NOT NULL COMMENT '商品 id',
    `product_name`  varchar(200)      NOT NULL DEFAULT '' COMMENT '下单时的商品名（快照）',
    `product_price` decimal(10, 2) unsigned NOT NULL DEFAULT 0.00 COMMENT '下单时的单价（快照）',
    `quantity`      int               NOT NULL DEFAULT 1,
    `seller_uid`    int unsigned      NOT NULL COMMENT '卖家 id（快照，便于卖家侧查询）',
    `created_at`    datetime          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_product` (`order_id`, `product_id`),
    KEY `idx_order` (`order_id`),
    KEY `idx_product` (`product_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '订单明细';

-- ---------------------------------------------------------------------------
--  4. 新表：收货地址（把塞在 lxy_user.province/city/area 的地址独立出来）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `address` (
    `id`         bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id`    int unsigned    NOT NULL,
    `receiver`   varchar(50)     NOT NULL DEFAULT '' COMMENT '收货人',
    `phone`      varchar(20)     NOT NULL DEFAULT '',
    `province`   varchar(100)    DEFAULT NULL,
    `city`       varchar(100)    DEFAULT NULL,
    `area`       varchar(100)    DEFAULT NULL,
    `detail`     varchar(200)    NOT NULL DEFAULT '' COMMENT '详细地址',
    `is_default` tinyint         NOT NULL DEFAULT 0 COMMENT '1 = 默认地址',
    `created_at` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_default` (`user_id`, `is_default`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '收货地址';

-- ---------------------------------------------------------------------------
--  5. 新表：商品评价（补上交易闭环）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `review` (
    `id`         bigint unsigned NOT NULL AUTO_INCREMENT,
    `product_id` int unsigned    NOT NULL,
    `order_id`   int unsigned    NOT NULL,
    `user_id`    int unsigned    NOT NULL COMMENT '评价人（买家）',
    `rating`     tinyint         NOT NULL DEFAULT 5 COMMENT '1-5 星',
    `content`    varchar(500)    DEFAULT NULL,
    `created_at` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 同一个订单里的同一商品只能评一次（防重复评价）
    UNIQUE KEY `uk_order_product_user` (`order_id`, `product_id`, `user_id`),
    KEY `idx_product` (`product_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '商品评价';

-- ---------------------------------------------------------------------------
--  6. 新表：支付流水（Phase 5 模拟支付用，此处先建表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `payment` (
    `id`         bigint unsigned   NOT NULL AUTO_INCREMENT,
    `order_no`   varchar(32)       NOT NULL COMMENT '业务订单号',
    `user_id`    int unsigned      NOT NULL,
    `amount`     decimal(10, 2) unsigned NOT NULL DEFAULT 0.00,
    `channel`    varchar(20)       NOT NULL DEFAULT 'MOCK' COMMENT '支付渠道，Phase 5 用 MOCK',
    `status`     varchar(20)       NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SUCCESS / FAILED / REFUNDED',
    `trade_no`   varchar(64)       DEFAULT NULL COMMENT '第三方流水号（回调幂等用）',
    `pay_time`   datetime          DEFAULT NULL,
    `created_at` datetime          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 幂等基石：同一订单号只能有一条支付流水，重复回调插不进来
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT '支付流水';

-- ---------------------------------------------------------------------------
--  7. 给旧表补列（原有列全部保留，不动）
-- ---------------------------------------------------------------------------
SELECT '--- 7. lxy_product 补列 ---' AS section;
CALL add_column_if_missing('lxy_product', 'category_id',
    '`category_id` int unsigned DEFAULT NULL COMMENT ''分类 id，NULL = 未分类'' AFTER `uid`');
CALL add_column_if_missing('lxy_product', 'stock',
    '`stock` int NOT NULL DEFAULT 1 COMMENT ''库存。二手商品一对一，默认 1 件'' AFTER `img_store_path`');
CALL add_column_if_missing('lxy_product', 'status',
    '`status` varchar(20) NOT NULL DEFAULT ''ON_SALE'' COMMENT ''ON_SALE 在售 / SOLD 已售出 / OFF_SHELF 已下架'' AFTER `stock`');
CALL add_column_if_missing('lxy_product', 'version',
    '`version` int NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号'' AFTER `status`');

SELECT '--- 7. lxy_order 补列 ---' AS section;
CALL add_column_if_missing('lxy_order', 'order_no',
    '`order_no` varchar(32) DEFAULT NULL COMMENT ''业务订单号（对外展示，不暴露自增 id）'' AFTER `id`');
CALL add_column_if_missing('lxy_order', 'status',
    '`status` varchar(20) DEFAULT NULL COMMENT ''状态机编码：PENDING_PAY/PAID/SHIPPED/COMPLETED/CANCELLED'' AFTER `condition`');
CALL add_column_if_missing('lxy_order', 'total_amount',
    '`total_amount` decimal(10, 2) unsigned NOT NULL DEFAULT 0.00 COMMENT ''订单总额'' AFTER `status`');
CALL add_column_if_missing('lxy_order', 'pay_time',
    '`pay_time` datetime DEFAULT NULL COMMENT ''支付时间'' AFTER `total_amount`');

-- ---------------------------------------------------------------------------
--  8. 索引补齐（Phase 2.6 会用 EXPLAIN 做前后对比）
-- ---------------------------------------------------------------------------
SELECT '--- 8. 索引 ---' AS section;
-- 商品列表的核心查询：WHERE category_id=? AND status='ON_SALE' ORDER BY created_at DESC
CALL add_index_if_missing('lxy_product', 'idx_category_status_created',
    'KEY `idx_category_status_created` (`category_id`, `status`, `created_at`)');
-- 订单号对外唯一（业务主键），必须由数据库兜底
CALL add_index_if_missing('lxy_order', 'uk_order_no',      'UNIQUE KEY `uk_order_no` (`order_no`)');
-- 「我买到的」按买家+状态查、「我卖出的」按卖家+状态查
CALL add_index_if_missing('lxy_order', 'idx_user_status',     'KEY `idx_user_status` (`buy_uid`, `status`)');
CALL add_index_if_missing('lxy_order', 'idx_seller_status',   'KEY `idx_seller_status` (`sell_uid`, `status`)');
-- 注意：cart_item 不再单独建 idx_user —— 已有的 uk_user_product(user_id, product_id)
-- 的最左前缀就是 user_id，再建一个纯 user_id 索引属于冗余索引（写入变慢、优化器还要多选一次）。

-- ---------------------------------------------------------------------------
--  9. 基础数据：分类种子（二手车场景）
-- ---------------------------------------------------------------------------
-- 【为什么是这 8 类】先把 3659 条商品名做了关键词词频统计，发现这是**通用商超品类**数据
-- （食品饮料占比最大），不是「校园二手物品」。所以分类按数据实际情况定，
-- 而不是照搬「教材/数码/生活」这种想当然的划分 —— 分类脱离数据就是摆设。
INSERT INTO `category` (`id`, `name`, `parent_id`, `sort`) VALUES
    (1, '食品饮料', 0, 10),
    (2, '日用百货', 0, 20),
    (3, '数码家电', 0, 30),
    (4, '服饰鞋包', 0, 40),
    (5, '图书文具', 0, 50),
    (6, '美妆个护', 0, 60),
    (7, '母婴玩具', 0, 70),
    (8, '运动户外', 0, 80)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `sort` = VALUES(`sort`);

-- ---------------------------------------------------------------------------
--  10. 数据回填
-- ---------------------------------------------------------------------------
SELECT '--- 10. 数据回填 ---' AS section;

-- 10.1 lxy_cart.products（逗号串）→ cart_item（关联表）
--      用「数字辅助表」把一行拆成多行：SUBSTRING_INDEX 取第 n 段。
--      这是 MySQL 里拆分字符串的标准手法（MySQL 8 没有 split 函数）。
INSERT INTO `cart_item` (`user_id`, `product_id`, `quantity`, `picked`)
SELECT c.id,
       CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(c.products, ',', n.n), ',', -1) AS UNSIGNED),
       1,
       1
FROM `lxy_cart` c
JOIN (SELECT 1 AS n UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
      UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10
      UNION SELECT 11 UNION SELECT 12 UNION SELECT 13 UNION SELECT 14 UNION SELECT 15
      UNION SELECT 16 UNION SELECT 17 UNION SELECT 18 UNION SELECT 19 UNION SELECT 20
      UNION SELECT 21 UNION SELECT 22 UNION SELECT 23 UNION SELECT 24 UNION SELECT 25
      UNION SELECT 26 UNION SELECT 27 UNION SELECT 28 UNION SELECT 29 UNION SELECT 30
      UNION SELECT 31 UNION SELECT 32 UNION SELECT 33 UNION SELECT 34 UNION SELECT 35
      UNION SELECT 36 UNION SELECT 37 UNION SELECT 38 UNION SELECT 39 UNION SELECT 40
      UNION SELECT 41 UNION SELECT 42 UNION SELECT 43 UNION SELECT 44 UNION SELECT 45
      UNION SELECT 46 UNION SELECT 47 UNION SELECT 48 UNION SELECT 49 UNION SELECT 50) n
     ON n.n <= 1 + LENGTH(c.products) - LENGTH(REPLACE(c.products, ',', ''))
WHERE c.products IS NOT NULL
  AND c.products <> ''
  AND SUBSTRING_INDEX(SUBSTRING_INDEX(c.products, ',', n.n), ',', -1) <> ''
  -- 只迁真实存在的商品，避免把脏数据带进来
  AND EXISTS (SELECT 1 FROM `lxy_product` p
              WHERE p.id = CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(c.products, ',', n.n), ',', -1) AS UNSIGNED))
ON DUPLICATE KEY UPDATE `cart_item`.quantity = 1;

-- 10.2 lxy_order → order_item（对历史订单补明细快照）
INSERT INTO `order_item` (`order_id`, `product_id`, `product_name`, `product_price`, `quantity`, `seller_uid`, `created_at`)
SELECT o.id,
       o.product_id,
       COALESCE(p.name, ''),
       COALESCE(p.price, 0.00),
       1,
       IFNULL(o.sell_uid, 0),
       IFNULL(o.created_at, NOW())
FROM `lxy_order` o
JOIN `lxy_product` p ON p.id = o.product_id
WHERE o.product_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM `order_item` oi
                  WHERE oi.order_id = o.id AND oi.product_id = o.product_id);

-- 10.3 lxy_order 补 order_no / status / total_amount / pay_time
--      order_no 用「SO + 日期 + 补零 id」生成，保证确定性且唯一；
--      真实生产应换成雪花算法（Phase 6 讨论），此处重点是「不暴露自增 id」。
UPDATE `lxy_order`
SET `order_no` = CONCAT('SO', DATE_FORMAT(IFNULL(`created_at`, NOW()), '%Y%m%d'), LPAD(`id`, 6, '0'))
WHERE `order_no` IS NULL OR `order_no` = '';

UPDATE `lxy_order` o
    JOIN `lxy_product` p ON p.id = o.product_id
SET o.`total_amount` = IFNULL(p.price, 0.00)
WHERE o.`total_amount` = 0.00;

-- 状态映射：旧中文 → 新状态机编码（保留 condition 原值，双写过渡）
UPDATE `lxy_order` SET `status` = 'PAID' WHERE `status` IS NULL AND `condition` = '等待发货';
UPDATE `lxy_order` SET `status` = 'SHIPPED' WHERE `status` IS NULL AND `condition` = '已发货';
UPDATE `lxy_order` SET `status` = 'COMPLETED' WHERE `status` IS NULL AND `condition` = '订单已完成';
-- 支付时间：已付款及之后的状态视为已支付
UPDATE `lxy_order` SET `pay_time` = IFNULL(`created_at`, NOW())
WHERE `pay_time` IS NULL AND `status` IN ('PAID', 'SHIPPED', 'COMPLETED');

-- 10.4 lxy_product 补 status / stock（依据既有的 sold_time 语义）
UPDATE `lxy_product` SET `status` = 'SOLD', `stock` = 0
WHERE `sold_time` IS NOT NULL AND `status` = 'ON_SALE';
UPDATE `lxy_product` SET `status` = 'ON_SALE', `stock` = 1
WHERE `sold_time` IS NULL AND `stock` = 0;

-- 10.5 category_id 粗分（关键词启发式，让分类功能有数据可演示）
--      【如实说明】这不是真实分类，是按商品名关键词做的粗略归类，生产环境应由运营维护或算法分类。
--      【两条工程化处理】
--        a) 规则「从具体到宽泛」按优先级顺序执行，且每条都带 `category_id IS NULL`：
--           先匹配到的赢，后面的不再覆盖。这样重复执行结果稳定（幂等），
--           也不会覆盖以后人工维护的分类。
--        b) 关键词刻意避开高频歧义词：不用「包」（会命中"包装"）、不用「水」（会命中"水果/水杯"）、
--           不用「美」（会命中品牌"美的"）。分类质量差的主因往往就是这种宽词。
-- 数码家电（优先：先拦下「笔记本」，否则会被文具规则抢走）
UPDATE `lxy_product` SET `category_id` = 3 WHERE `category_id` IS NULL AND (
    `name` LIKE '%手机%' OR `name` LIKE '%电脑%' OR `name` LIKE '%笔记本%' OR `name` LIKE '%耳机%'
    OR `name` LIKE '%键盘%' OR `name` LIKE '%鼠标%' OR `name` LIKE '%充电%' OR `name` LIKE '%数据线%'
    OR `name` LIKE '%显示器%' OR `name` LIKE '%平板%' OR `name` LIKE '%相机%' OR `name` LIKE '%音响%'
    OR `name` LIKE '%蓝牙%' OR `name` LIKE '%电池%' OR `name` LIKE '%电源%' OR `name` LIKE '%硬盘%'
    OR `name` LIKE '%U盘%' OR `name` LIKE '%路由器%' OR `name` LIKE '%吸尘器%' OR `name` LIKE '%风扇%'
    OR `name` LIKE '%加湿器%' OR `name` LIKE '%电饭%' OR `name` LIKE '%烤箱%' OR `name` LIKE '%微波%'
    OR `name` LIKE '%吹风%' OR `name` LIKE '%剃须%' OR `name` LIKE '%美的%');
-- 食品饮料
UPDATE `lxy_product` SET `category_id` = 1 WHERE `category_id` IS NULL AND (
    `name` LIKE '%零食%' OR `name` LIKE '%食品%' OR `name` LIKE '%饼干%' OR `name` LIKE '%干果%'
    OR `name` LIKE '%坚果%' OR `name` LIKE '%果干%' OR `name` LIKE '%巧克力%' OR `name` LIKE '%糖%'
    OR `name` LIKE '%糕%' OR `name` LIKE '%面包%' OR `name` LIKE '%牛奶%' OR `name` LIKE '%酸奶%'
    OR `name` LIKE '%咖啡%' OR `name` LIKE '%茶%' OR `name` LIKE '%果汁%' OR `name` LIKE '%饮料%'
    OR `name` LIKE '%矿泉水%' OR `name` LIKE '%纯净水%' OR `name` LIKE '%饮用水%' OR `name` LIKE '%酱油%'
    OR `name` LIKE '%醋%' OR `name` LIKE '%酱%' OR `name` LIKE '%辣椒%' OR `name` LIKE '%调味%'
    OR `name` LIKE '%大米%' OR `name` LIKE '%面粉%' OR `name` LIKE '%食用油%' OR `name` LIKE '%方便%'
    OR `name` LIKE '%火腿%' OR `name` LIKE '%香肠%' OR `name` LIKE '%海鲜%' OR `name` LIKE '%虾%'
    OR `name` LIKE '%蛋%' OR `name` LIKE '%蜂蜜%' OR `name` LIKE '%麦片%' OR `name` LIKE '%代餐%'
    OR `name` LIKE '%白酒%' OR `name` LIKE '%啤酒%' OR `name` LIKE '%红酒%' OR `name` LIKE '%鱼%');
-- 日用百货
UPDATE `lxy_product` SET `category_id` = 2 WHERE `category_id` IS NULL AND (
    `name` LIKE '%纸巾%' OR `name` LIKE '%抽纸%' OR `name` LIKE '%卷纸%' OR `name` LIKE '%湿巾%'
    OR `name` LIKE '%收纳%' OR `name` LIKE '%储物%' OR `name` LIKE '%锅%' OR `name` LIKE '%碗%'
    OR `name` LIKE '%筷%' OR `name` LIKE '%杯%' OR `name` LIKE '%水壶%' OR `name` LIKE '%保温%'
    OR `name` LIKE '%毛巾%' OR `name` LIKE '%牙刷%' OR `name` LIKE '%牙膏%' OR `name` LIKE '%洗发%'
    OR `name` LIKE '%沐浴%' OR `name` LIKE '%洗衣%' OR `name` LIKE '%清洁%' OR `name` LIKE '%拖把%'
    OR `name` LIKE '%扫把%' OR `name` LIKE '%垃圾桶%' OR `name` LIKE '%保鲜%' OR `name` LIKE '%菜板%'
    OR `name` LIKE '%砧板%' OR `name` LIKE '%刀具%' OR `name` LIKE '%衣架%' OR `name` LIKE '%挂钩%'
    OR `name` LIKE '%台灯%' OR `name` LIKE '%桌%' OR `name` LIKE '%椅%' OR `name` LIKE '%床%'
    OR `name` LIKE '%沙发%' OR `name` LIKE '%雨伞%' OR `name` LIKE '%镜子%' OR `name` LIKE '%水桶%');
-- 服饰鞋包（避开「包」单字，它会把「包装」全吸进来）
UPDATE `lxy_product` SET `category_id` = 4 WHERE `category_id` IS NULL AND (
    `name` LIKE '%外套%' OR `name` LIKE '%卫衣%' OR `name` LIKE '%衬衫%' OR `name` LIKE '%T恤%'
    OR `name` LIKE '%连衣裙%' OR `name` LIKE '%裤%' OR `name` LIKE '%鞋%' OR `name` LIKE '%靴%'
    OR `name` LIKE '%拖鞋%' OR `name` LIKE '%帽%' OR `name` LIKE '%围巾%' OR `name` LIKE '%手套%'
    OR `name` LIKE '%袜%' OR `name` LIKE '%内衣%' OR `name` LIKE '%文胸%' OR `name` LIKE '%背包%'
    OR `name` LIKE '%双肩包%' OR `name` LIKE '%手提包%' OR `name` LIKE '%钱包%' OR `name` LIKE '%书包%'
    OR `name` LIKE '%行李箱%' OR `name` LIKE '%腰带%' OR `name` LIKE '%皮带%' OR `name` LIKE '%裙%');
-- 图书文具
UPDATE `lxy_product` SET `category_id` = 5 WHERE `category_id` IS NULL AND (
    `name` LIKE '%图书%' OR `name` LIKE '%书籍%' OR `name` LIKE '%教材%' OR `name` LIKE '%考研%'
    OR `name` LIKE '%笔记%' OR `name` LIKE '%字帖%' OR `name` LIKE '%钢笔%' OR `name` LIKE '%圆珠笔%'
    OR `name` LIKE '%中性笔%' OR `name` LIKE '%铅笔%' OR `name` LIKE '%本子%' OR `name` LIKE '%稿纸%'
    OR `name` LIKE '%打印纸%' OR `name` LIKE '%复印纸%' OR `name` LIKE '%文件夹%' OR `name` LIKE '%订书%'
    OR `name` LIKE '%胶带%' OR `name` LIKE '%剪刀%' OR `name` LIKE '%尺子%' OR `name` LIKE '%文具%'
    OR `name` LIKE '%墨水%' OR `name` LIKE '%马克笔%' OR `name` LIKE '%书%');
-- 美妆个护
UPDATE `lxy_product` SET `category_id` = 6 WHERE `category_id` IS NULL AND (
    `name` LIKE '%面膜%' OR `name` LIKE '%护肤%' OR `name` LIKE '%化妆%' OR `name` LIKE '%口红%'
    OR `name` LIKE '%香水%' OR `name` LIKE '%洗面奶%' OR `name` LIKE '%精华%' OR `name` LIKE '%防晒%'
    OR `name` LIKE '%眼霜%' OR `name` LIKE '%粉底%' OR `name` LIKE '%指甲%');
-- 母婴玩具
UPDATE `lxy_product` SET `category_id` = 7 WHERE `category_id` IS NULL AND (
    `name` LIKE '%婴儿%' OR `name` LIKE '%宝宝%' OR `name` LIKE '%儿童%' OR `name` LIKE '%玩具%'
    OR `name` LIKE '%积木%' OR `name` LIKE '%拼图%' OR `name` LIKE '%奶瓶%' OR `name` LIKE '%尿不湿%'
    OR `name` LIKE '%婴儿车%');
-- 运动户外
UPDATE `lxy_product` SET `category_id` = 8 WHERE `category_id` IS NULL AND (
    `name` LIKE '%篮球%' OR `name` LIKE '%足球%' OR `name` LIKE '%羽毛球%' OR `name` LIKE '%乒乓球%'
    OR `name` LIKE '%网球%' OR `name` LIKE '%球拍%' OR `name` LIKE '%健身%' OR `name` LIKE '%瑜伽%'
    OR `name` LIKE '%哑铃%' OR `name` LIKE '%跳绳%' OR `name` LIKE '%自行车%' OR `name` LIKE '%骑行%'
    OR `name` LIKE '%游泳%' OR `name` LIKE '%泳衣%' OR `name` LIKE '%户外%' OR `name` LIKE '%帐篷%'
    OR `name` LIKE '%登山%' OR `name` LIKE '%跑步%');

-- ---------------------------------------------------------------------------
--  11. 收尾：清理临时过程
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `add_column_if_missing`;
DROP PROCEDURE IF EXISTS `add_index_if_missing`;

-- ---------------------------------------------------------------------------
--  12. 校验（把结果贴进 ROADMAP，作为迁移证据）
-- ---------------------------------------------------------------------------
SELECT '=== 迁移校验 ===' AS `check`;
SELECT '新表行数' AS item, 'category' AS t, COUNT(*) AS cnt FROM `category`
UNION ALL SELECT '新表行数', 'cart_item',  COUNT(*) FROM `cart_item`
UNION ALL SELECT '新表行数', 'order_item', COUNT(*) FROM `order_item`
UNION ALL SELECT '新表行数', 'address',    COUNT(*) FROM `address`
UNION ALL SELECT '新表行数', 'review',     COUNT(*) FROM `review`
UNION ALL SELECT '新表行数', 'payment',    COUNT(*) FROM `payment`;
SELECT '旧→新一致性' AS item, 'lxy_cart 非空行' AS t, COUNT(*) AS cnt
    FROM `lxy_cart` WHERE `products` IS NOT NULL AND `products` <> ''
UNION ALL SELECT '旧→新一致性', 'cart_item 覆盖用户数', COUNT(DISTINCT `user_id`) FROM `cart_item`
UNION ALL SELECT '旧→新一致性', 'lxy_order 总数', COUNT(*) FROM `lxy_order`
UNION ALL SELECT '旧→新一致性', 'order_item 覆盖订单数', COUNT(DISTINCT `order_id`) FROM `order_item`
UNION ALL SELECT '旧→新一致性', 'lxy_order 无 order_no', COUNT(*) FROM `lxy_order` WHERE `order_no` IS NULL OR `order_no` = ''
UNION ALL SELECT '旧→新一致性', 'lxy_order 无 status', COUNT(*) FROM `lxy_order` WHERE `status` IS NULL;
SELECT '商品分类分布' AS item, IFNULL(c.name, '未分类') AS category, COUNT(*) AS cnt
    FROM `lxy_product` p LEFT JOIN `category` c ON c.id = p.category_id
    GROUP BY c.name ORDER BY cnt DESC;
