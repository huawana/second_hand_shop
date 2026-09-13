# 校园二手交易平台 → 面试级电商平台 改造规划

> **执行方式建议：** 按 Phase 顺序执行，每个 Phase 完成后可独立交付/写进简历。
> 每完成一个 Task 就 commit 一次，保证任何时刻项目可运行。

**目标（校招版 · 2026-09-13 重新规划）：** 把一个 Spring Boot 2.4.3 课程级 CRUD 二手交易项目，
改造成**常规电商闭环 + 主流技术栈都有真实落点**的校招简历项目。

> **定位调整说明：** 原规划是「卷王」规格（分库分表、Seata 分布式事务、SkyWalking、微服务拆分、
> ES + 推荐算法、秒杀 5 层防护 + 5000 并发压测调优）。校招面试不会追到这个深度，
> 且「什么都做了但都很浅」比「该有的都有、每处都能讲清楚」更差。
> 因此本版**砍掉深度、补齐广度**：只做常规电商真正会用到的处理方式，
> 但要求每一处都答得出「为什么用它 / 遇到什么问题 / 怎么解决」。
> 砍掉的项与理由见文末「砍掉了什么」——那张表本身就是面试素材。

**覆盖的技术栈（简历可逐条写出来）：**

| 分类 | 技术 |
|---|---|
| 框架 | Spring Boot 3.3.5、Spring Security 6、MyBatis-Plus、Knife4j |
| 认证 | JWT（HttpOnly Cookie）+ RBAC + BCrypt + CSRF、令牌黑名单与刷新（Redis）|
| 存储 | MySQL 8（索引优化 / 事务 / 乐观锁）、Redis 5（缓存三大问题 / 分布式锁 / `ThreadLocal`）|
| 中间件 | RabbitMQ（异步下单 / 延迟消息 / 死信队列）|
| 工程 | 自定义 Starter、AOP（日志 / 限流注解）、全局异常、参数校验、定时任务 |
| 测试部署 | JUnit 5、Docker Compose、Nginx |

**核心策略：** 保留现有资产（3659 件真实商品、3665 张图、校园二手业务域、地理 / 学校筛选），
补齐常规电商该有而它没有的东西（分类 / 库存 / 购物车关联表 / 订单明细 / 地址 / 评价 / 支付）。

---

## 进度追踪

| Phase | 状态 | 完成日期 | 备注 |
|---|---|---|---|
| Phase 0 修 bug + 工程化地基 | ✅ 已完成 | 2026-09-13 | 见文末「Phase 0 完成报告」 |
| Phase 1 Spring Boot 3 + Security + JWT | ✅ 已完成 | 2026-09-13 | 1.1–1.8 全部完成（含令牌生命周期、自定义 starter），见文末「Phase 1 完成报告」 |
| Phase 2 领域建模 + 常规电商闭环 | ⏳ 待开始 | — | **必做** |
| Phase 3 Redis 缓存 + 分布式锁 | ⏳ 待开始 | — | **必做** |
| Phase 4 MQ + 定时任务（含秒杀基础版）| ⏳ 待开始 | — | **必做** |
| Phase 5 工程化补齐（文档 / AOP / 幂等 / 模拟支付）| ⏳ 待开始 | — | 加分 |
| Phase 6 部署交付（Docker / Nginx / 压测 / 总结）| ⏳ 待开始 | — | 收尾 |

---

## 一、现状盘点（已实测，非推测）

### 1.1 代码规模

| 项 | 数量 |
|---|---|
| Java 类 | 37 个 / 2109 行 |
| Controller | 12 个（前台 7 + 后台 5） |
| Mapper 接口 / XML | 6 / 6 |
| Thymeleaf 模板 | 25 个（shop 17 + admin 8，其中 `admin/product_add.html` 缺失） |
| 前端 JS / CSS | 2208 行 / 1279 行（原生 jQuery，无构建工具） |
| 商品图 | 3665 张 png，src 目录 84MB |
| 接口 | 40 个（GET 34 / POST 15，含 1 个注释掉的） |
| 测试代码 | 0（无 src/test 目录） |
| Git | 无 `.git`，改坏了没法回滚 |
| SQL 脚本 / README | 无 |

### 1.2 数据库（实测 `shop` 库，有真实数据）

```
lxy_user     30 行   username/password(MD5)/email/phone/province/city/area/school/search(text)
lxy_product  3659 行 name/uid/description/price(decimal10,2)/created_at/sold_time/img_store_path/view_count
lxy_order    28 行   product_id/sell_uid/buy_uid/created_at(varchar!)/condition
lxy_cart     29 行   products(text，逗号拼接商品 id)
lxy_admin    1 行    adminuser/adminpass/login_at
```

**僵尸表（代码里完全没用到，但暗示了原始设计意图）：**

| 表 | 结构 | 说明 |
|---|---|---|
| `chat_records` | sender_id/receiver_id/message/timestamp，30 行 | IM 私聊消息，**未完成的社交功能** |
| `lxy_mate` | uid1/uid2，20 行 | 好友关系表，**未完成** |
| `lxy_product_sale` | 商品+买卖双方快照 | 废弃的中间设计 |

> **重要：`pom.xml` 已引入 `spring-boot-starter-websocket` 但全项目零引用** —— 原项目打算做 WebSocket 聊天但没做完。
> 这是天然的功能缺口，补完即成为项目亮点。

### 1.3 数据库问题

- **全库只有主键索引，零二级索引、零外键** → 慢查询素材
- `lxy_order.created_at` 类型是 `varchar(200)` 而非 `datetime` → 建表设计缺陷
- `lxy_order.condition` 用了保留字风格命名（`condition`/`status` 靠反引号硬撑）
- 购物车用逗号拼接字符串存商品 id，**不是关联表** → 无法 JOIN、无法约束、并发写会互相覆盖
- 字符集混用：`utf8mb3` 与 `utf8mb4` 并存 → emoji 会炸
- `lxy_user.search` 把「关键词:权重」序列化进 text 字段 → 无结构、无法查询、无法索引

### 1.4 本机环境（已实测）

| 组件 | 状态 |
|---|---|
| JDK | 22.0.1（项目 target 17） |
| Maven CLI | **未安装**（靠 IntelliJ 内置 Maven 构建，`~/.m2` 有缓存） |
| MySQL | 8.0.37 **运行中**（localhost:3306，root/root） |
| Redis | **运行中**（127.0.0.1:6379，pid 7300） |
| Docker | 27.3.1 + Compose v2.30.3 ✅ |
| Node | v14.16.1（**过旧**，Vue3+Vite 需 18+） |

> Redis 已经在跑了 —— 缓存/分布式锁章节可以立刻上手，不需要新装中间件。

### 1.5 技术债清单（详细 bug 定位见 Phase 0）

| 级别 | 数量 | 代表问题 |
|---|---|---|
| 必崩（运行时报错） | 10 | 后台改用户 MyBatis 参数不匹配、上传路径指向不存在的 E 盘、缺失模板 |
| 逻辑错误 | 8 | 定时任务在循环内更新库、删文件用 URL 相对路径 |
| 安全 | 5 | 无盐 MD5、session 存 "请登录" 字符串当哨兵、明文 DB 密码 |
| 工程化 | 6 | 无 git、无测试、无统一响应、无全局异常、18 处 System.out、4 个依赖引入未用 |

---

## 二、目标架构（改造后）

```
                  ┌────────────────────────────────────┐
  浏览器          │  Nginx（反向代理 + 静态资源）        │
  (Thymeleaf 页面) └────────────────┬───────────────────┘
                                    │
                  ┌─────────────────▼────────────────────┐
                  │   Spring Boot 3.3.5 单体应用            │
                  │   ── 表现层  Thymeleaf + REST(JSON)     │
                  │   ── 安全层  Security6 + JWT/HttpOnly   │
                  │            + RBAC + CSRF                │
                  │   ── 业务层  分类/商品/购物车/订单/支付/评价 │
                  │   ── 切面层  AOP 统一日志 / @RateLimit   │
                  │   ── 接口文档 Knife4j                    │
                  │   ── 自研组件 oss-spring-boot-starter   │
                  └──┬───────────────┬───────────────┬─────┘
                     ▼               ▼               ▼
                MySQL 8          Redis 5         RabbitMQ
            (索引/事务/乐观锁)  (缓存/锁/       (异步下单/延迟消息/
                                 令牌状态)        死信队列)
                     │               │               │
                     └───────────────┴───────────────┘
                        Docker Compose 一键起
```

**改造阶段划分（校招版：单体 + 中间件，全覆盖、不追深度）：**

```
Phase 0  修复 bug + 工程化地基                          ✅ 已完成
Phase 1  Spring Boot 3 升级 + Security + JWT/RBAC        ✅ 已完成
Phase 2  领域建模 + 常规电商闭环（分类/库存/购物车/订单/地址/评价）  必做
Phase 3  Redis 缓存 + 分布式锁（穿透/击穿/雪崩/一致性）              必做
Phase 4  MQ + 定时任务（异步下单/超时取消/幂等/秒杀基础版）          必做
Phase 5  工程化补齐（Knife4j/AOP 日志与限流/幂等/模拟支付/DTO 分层）  加分
Phase 6  部署交付（Docker Compose/Nginx/压测数字/面试问答）           收尾
```

> 原规划的 Phase 6–11（分库分表、分布式事务、Elasticsearch、IM、可观测性、微服务拆分）
> 已按校招标准砍掉或降级，逐条理由见「砍掉了什么」。

---

## 三、面试考点 → 项目落点 映射表（本规划的核心）

> 这是整个改造的验收标准：**任何一行都必须在项目里能指出具体文件、具体设计、具体数字。**
> 答不出数字的项，说明该 Phase 没做完。

| # | 校招高频考点 | 项目中的落点 | 面试时能讲什么 |
|---|---|---|---|
| 1 | MySQL 索引与优化 | Phase 2 联合索引 + `EXPLAIN` 前后对比 | B+ 树为什么适合磁盘、最左前缀、回表、覆盖索引、`EXPLAIN` 关键列含义 |
| 2 | MySQL 事务与锁 | Phase 2 扣库存（乐观锁 / 悲观锁对比）+ 下单事务 | 隔离级别、MVCC、丢失更新、`@Transactional` **失效的 8 种场景** |
| 3 | Redis 缓存 | Phase 3 商品详情 / 分类缓存 | 5 种数据结构与场景、序列化方式、过期与淘汰策略 |
| 4 | 缓存三大问题 | Phase 3 穿透 / 击穿 / 雪崩各一套方案 | 空值缓存 + 布隆过滤器、互斥锁重建、过期时间打散 |
| 5 | 缓存一致性 | Phase 3 更新商品「先更库再删缓存」+ 延迟双删 | 为什么是删缓存不是更新缓存、为什么延迟双删 |
| 6 | 分布式锁 | Phase 3 Redisson 锁（同一用户重复下单）| `SETNX` 手写锁的 4 个坑、看门狗续期、可重入 |
| 7 | 消息队列 | Phase 4 异步下单 + 订单超时取消 + 死信队列 | 削峰 / 异步 / 解耦三个词各对应一处代码、幂等消费、消息丢失与重试 |
| 8 | 定时任务 | Phase 4 超时订单扫表兜底 + `@Scheduled` | cron 表达式、多实例重复执行怎么解决 |
| 9 | 秒杀（基础版）| Phase 4 Redis + Lua 预减库存 + MQ 异步下单 | 防超卖思路、库存预热、为什么用 Lua 保证原子性 |
| 10 | 认证授权 | Phase 1 ✅ Spring Security + JWT + RBAC | 无状态认证、刷新与黑名单、越权（水平 / 垂直）、CSRF、BCrypt 加盐 |
| 11 | Spring 原理 | Phase 5 AOP 日志 / `@RateLimit` 注解；Phase 2 事务 | IoC 容器、三级缓存解决循环依赖、AOP 动态代理（JDK vs CGLIB）、自调用失效 |
| 12 | Spring Boot 自动配置 | Phase 1 ✅ 自定义 `oss-spring-boot-starter` | `@Conditional` 家族、SPI 机制、`spring.factories` vs `AutoConfiguration.imports` |
| 13 | MyBatis 原理 | Phase 2 MyBatis-Plus 分页 / 乐观锁插件 | `#{}` vs `${}`（SQL 注入）、一级 / 二级缓存、插件 `Interceptor` 原理 |
| 14 | 接口安全与幂等 | Phase 4 幂等消费 + Phase 5 防重提交 / `@RateLimit` | 幂等 6 种实现、限流算法（令牌桶 / 漏桶 / 滑动窗口） |
| 15 | 接口文档与分层 | Phase 5 Knife4j + DTO / VO 分层 | 前后端契约、为什么不把实体直接丢给前端 |
| 16 | 金额与订单号 | Phase 2 金额用 `BigDecimal`、订单号唯一索引 | 浮点精度问题、为什么不把自增 id 暴露给前端 |
| 17 | 容器化与部署 | Phase 6 `Dockerfile` + compose + Nginx | 镜像分层、多阶段构建、反向代理与静态资源 |
| 18 | 测试 | Phase 0 / 1 起步 → Phase 5 补关键路径 | 单测与 mock、关键路径怎么挑 |
| 19 | 项目难点与量化 | Phase 6 汇总 | 「加了索引后 Xms → Yms」「命中缓存 vs 直查库」「QPS / 平均耗时」 |

> **口头准备即可（本项目不做代码，被问到能说清思路就够）：**
> 分库分表、分布式事务（2PC / TCC / Saga / 本地消息表）、微服务（Nacos / Gateway / Sentinel）、
> Elasticsearch 倒排索引与深分页、JVM 内存分区与 GC、ConcurrentHashMap 与 AQS 原理。
> 校招把**做过的**讲透，比把**没做过的**背熟更有说服力。

---

## 四、分期路线图

### 🎯 分档（校招版：广度优先，不追深度）

| 档位 | 包含 | 工期 | 判断标准 |
|---|---|---|---|
| **必做** | Phase 2 → 3 → 4 | 约 2 周 | 做完这三期就是一个「功能闭环 + 主流中间件都用过」的常规电商，简历可以投了 |
| **加分** | Phase 5 | 3-5 天 | 接口文档 / AOP / 幂等 / 模拟支付 —— 便宜、好写、面试常问 |
| **收尾** | Phase 6 | 2-3 天 | Docker 一键起、Nginx、几个压测数字、面试问答文档 |

> **验收标准一句话：** 面试官问到的每个常见组件，你都能说「我用过，当时是这么处理的」。
> 校招不追问源码级，但一定追问「你为什么用它」「遇到什么问题」「怎么解决的」——
> 所以每引入一个技术，必须有一个真实理由和一次真实踩坑，否则不如不引入。

---

## Phase 2 — 领域建模 + 常规电商闭环（必做）

**目标：** 把「课程作业式伪建模」补成标准电商模型，并补齐常规电商该有的功能。
**为什么排第一：** 面试官第一眼看的不是 Redis，是「你的购物车和订单表像不像正经电商」。

### 2.1 表结构补齐（写入 `docs/schema_v2.sql`）

| 表 | 动作 | 说明 |
|---|---|---|
| `lxy_product` | 改 | +`category_id` +`stock` +`status` +`version`（乐观锁）|
| `category` | 新 | 商品分类（二手场景：教材 / 数码 / 生活 / 服饰 / 运动）|
| `cart_item` | 新 | **替换 `lxy_cart.products` 逗号串**：`(id, user_id, product_id, quantity, picked)` |
| `lxy_order` | 改 | +`order_no`(唯一) +`status` +`total_amount` +`pay_time`；`created_at` 由 varchar 改 `datetime` |
| `order_item` | 新 | 订单明细（**快照**商品名与价格，避免商品改价污染历史订单）|
| `address` | 新 | 收货地址（一对多，替换塞在 `lxy_user` 里的 province / city / area）|
| `review` | 新 | 商品评价（确认收货后可评，补上交易闭环）|
| `payment` | 新 | 支付流水（Phase 5 模拟支付用）|

### 2.2 引入 MyBatis-Plus（考点 13）

- 实体改 `@TableName` / `@TableId(IdType.AUTO)`
- **分页插件**：替换手写 `limit` 分页
- **乐观锁插件** `@Version`：扣库存用它
- **自动填充** `MetaObjectHandler`：`created_at` / `updated_at` 自动写入
- 面试可讲：MP 插件底层就是 MyBatis 的 `Interceptor` 拦截 `StatementHandler`

### 2.3 库存与并发（考点 2）

两条路都实现，README 记录对比结论：
- **乐观锁**：`update product set stock = stock - 1, version = version + 1 where id = ? and version = ? and stock >= 1`
- **悲观锁**：`select ... for update`

必须讲清楚：为什么不直接 `stock - 1`（丢失更新）、乐观锁失败怎么重试、重试次数怎么定。

### 2.4 订单状态机

状态：待支付 → 已支付 → 待发货 → 已发货 → 已完成 / 已取消
- 枚举 + **状态流转校验**（非法流转直接拒绝，不能让 `status` 想怎么改就怎么改）
- 下单在**一个事务**里：扣库存 + 建订单 + 建明细 + 清购物车（`@Transactional`，并讲失效场景）

### 2.5 金额与订单号（校招易踩的细节，考点 16）

- 金额一律 `BigDecimal`，禁用 `double`（讲浮点精度）
- 订单号：时间戳 + 用户 id 后缀 + 随机数（讲「为什么不把自增 id 暴露给前端」）

### 2.6 索引优化专项（考点 1 —— 简历上最好写的量化数字）

- 给 `product(category_id, status, created_at)`、`order(user_id, status)`、`order_item(order_id)` 建索引
- 用 `EXPLAIN` 做**前后对比**（`type` / `rows` / `Extra`），把表格写进 README
- 顺带讲最左前缀、回表、覆盖索引

**验收：** 分类 → 商品 → 购物车 → 下单 → 支付 → 评价 全链路走通；
README 有 `EXPLAIN` 前后对比表、乐观锁与悲观锁的对比结论。

---

## Phase 3 — Redis 缓存 + 分布式锁（必做）

**目标：** 简历上「熟悉 Redis」这句话必须有落点。

- **3.1** `RedisTemplate` 配置：key 用 String、value 用 Jackson（默认 JDK 序列化进 Redis 是乱码，讲清为什么换）
- **3.2** 商品详情缓存（Cache-Aside）：查缓存 → 未命中查库 → 回写缓存
- **3.3** 分类列表缓存（读多写少，最典型的缓存场景）
- **3.4** 缓存穿透：先做**空值缓存**（简单版），再补**布隆过滤器**（Redisson `RBloomFilter`）← 考点 4
- **3.5** 缓存击穿：**互斥锁重建**（`setIfAbsent` 加锁 + 双重检查）← 考点 4
- **3.6** 缓存雪崩：过期时间加随机值打散 ← 考点 4
- **3.7** 缓存一致性：更新商品时「先更新库、再删缓存」+ 延迟双删；讲清为什么不是「更新缓存」← 考点 5
- **3.8** 分布式锁：Redisson 锁住「同一用户重复下单」（校招只要这一个真实场景就够）← 考点 6
- **3.9** 缓存命中率：`INFO stats` 的 `keyspace_hits/misses`，算出来写进简历 ← 考点 19

**验收：** 商品详情接口给出「命中缓存 vs 直查库」的耗时对比（循环 100 次取平均即可，不必上 JMeter）。

---

## Phase 4 — MQ + 定时任务（必做，含秒杀基础版）

**目标：** 简历上「了解消息队列」要能说出削峰 / 异步 / 解耦三个词分别对应哪三处代码。

**选型：RabbitMQ**（校招最常见、Docker 一条命令、延迟消息用 `rabbitmq_delayed_message_exchange` 插件）。
> 备选 RocketMQ（国内大厂用得多，但 Docker 起 NameServer + Broker 更麻烦）。
> **只选一个**，不要简历上写两个都没深入用过的。

- **4.1** Docker 起 RabbitMQ（含管理台）
- **4.2** 声明交换机 / 队列 / 死信队列，配置 Jackson 消息转换器
- **4.3** **异步下单**：请求 → 发消息 → 立即返回「处理中」→ 消费者真正落库（讲「为什么要异步」）
- **4.4** **订单超时自动取消**：延迟消息 + **定时任务扫表兜底**，两条都写，
  讲清各自的问题（延迟消息可能丢；扫表量大、要分片）← 考点 8
- **4.5** **消费幂等**：消息带业务唯一 id + 数据库唯一索引兜底；
  讲清「为什么不用 Redis 判重」（重启 / 过期即失效）← 考点 14
- **4.6** 死信队列 + 消费失败重试（讲清重试次数与死信的关系）← 考点 7
- **4.7** **秒杀基础版**（只做一条主链路，不做多层防护）← 考点 9
  - 活动商品库存预热进 Redis
  - **Redis + Lua 原子预减库存**（「一人一单」判重放在同一个脚本里，保证原子性）
  - 扣减成功 → 发 MQ → 消费者异步创建订单
  - 数据库唯一索引 `(user_id, activity_id)` 兜底防重
  - **不做**：Sentinel 限流、验证码、风控、5000 并发压测调优
- **4.8** 定时任务：`@Scheduled` 写「超时订单扫描」，
  顺带讲清「多实例会重复执行」及解决方式（Redis 锁 / xxl-job）← 考点 8

**验收：** 能演示「下单 → 发消息 → 落库」；能演示「订单超时自动取消并回补库存」；
重复投递同一条消息不会重复下单。

---

## Phase 5 — 工程化补齐（加分，便宜好写）

- **5.1** **Knife4j / Swagger 接口文档**（校招常见，半天的活）← 考点 15
- **5.2** **AOP 统一日志**：切 Controller 记请求参数、耗时、异常（讲 AOP 原理与自调用失效）← 考点 11
- **5.3** **自定义 `@RateLimit` 注解**：AOP + Redis 滑动窗口限流（面试很喜欢让手写）← 考点 14
- **5.4** **幂等 / 防重提交**：前端带 token + Redis 校验（讲 6 种幂等实现）← 考点 14
- **5.5** **模拟支付**：`payment` 流水表 + 支付回调接口（不必真接支付宝，讲清回调为何要验签 + 幂等）
- **5.6** **DTO / VO 分层**：请求体用 DTO 校验、返回用 VO，别把实体直接丢给前端 ← 考点 15
- **5.7** **统一用户上下文**：`ThreadLocal` + 拦截器存当前用户（替换散落各处的 `session.getAttribute`）
- **5.8** 关键路径补单测（下单、扣库存、状态流转）← 考点 18
- **5.9** 时间富余再加（可选）：优惠券、商品收藏、浏览历史

---

## Phase 6 — 部署交付（收尾）

- **6.1** `Dockerfile`：多阶段构建（讲清为什么能瘦身）、非 root 用户 ← 考点 17
- **6.2** `docker-compose.yml`：一键起 MySQL / Redis / RabbitMQ / 应用（现场演示很加分）
- **6.3** Nginx 反向代理 + 静态资源（讲清改了静态资源为什么要 reload）
- **6.4** 简单压测：挑 3 个接口（商品详情 / 搜索 / 下单）出 **QPS 与平均耗时**数字 ← 考点 19
- **6.5** README：架构图、技术选型理由表、每个技术「为什么用它」、踩坑记录
- **6.6** 整理「项目难点 5 问 5 答」（面试前复习）

---

## 🚫 砍掉了什么，以及为什么（被问到时的标准答法）

| 原规划 | 决定 | 理由 / 被问到时怎么答 |
|---|---|---|
| 分库分表（ShardingSphere）| **砍** | 「3659 条数据不需要分表。我知道分片键怎么选、跨片分页怎么处理，但没有为了简历去分」——**这样答是加分项** |
| Seata / 分布式事务 | **降级** | 能讲清 2PC / TCC / Saga / 本地消息表 的代价即可；本项目单体事务够用 |
| 微服务拆分 | **砍** | 面试官反感「为了微服务而微服务」。单体 + 中间件才是这个体量的正确答案 |
| Elasticsearch + 推荐 | **降级** | 保留原项目的搜索词权重算法作亮点；ES 只做口头准备（倒排索引、深分页）|
| IM 私聊（WebSocket）| **砍** | 与电商主线无关，性价比低 |
| 可观测性全家桶 | **降级** | 保留 Actuator + 日志 TraceId；Prometheus / Grafana / SkyWalking 口头准备 |
| 秒杀 5 层防护 + 5000 并发调优 | **降级** | 保留「Redis 预减库存 + MQ 异步下单」主链路；限流 / 风控 / 压测调优不做 |
| JVM 调优 / Arthas 实验 | **降级** | 知道堆分区、有哪些 GC、怎么用 jstat 看即可，项目里不做实验 |

> 这张表本身就是面试素材：**能说清「为什么不做」，比「什么都做了但都很浅」更强。**

---

## 五、已决策事项与风险

### ✅ 已拍板（原「需要你决策的 3 个问题」，均已落地）

| # | 问题 | 决定 | 落地情况 |
|---|---|---|---|
| 1 | 是否升级 Spring Boot 3？| **升级**（实际升到 3.3.5 + JDK 17）| ✅ Phase 1 完成，`javax`→`jakarta` 全量迁移 |
| 2 | 前端怎么办？| **保留 Thymeleaf** | ✅ 后端才是面试重点，前端重写收益低 |
| 3 | 是否拆微服务？| **不拆**（单体 + 中间件）| ✅ 已从路线图中移除（2026-09-13 重规划）|

### ⚠️ 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 工期远超预期 | 原「全量 3-6 个月」已砍到 2-3 周，仍可能拖 | 严格按「必做」档做完三期就先投简历，加分项有余力再补 |
| 本机 Maven CLI 缺失 | 只能靠 IntelliJ 自带 Maven | 已用 `.hermes/mvn.sh` 封装；starter 的两步构建见 `.hermes/build-all.sh` |
| 本机 Redis 服务可能被别的项目共用 | Key 冲突 | 已固定用 **db 3** + `shop:` 前缀 |
| 3665 张图 + 84MB | 仓库膨胀、Docker 镜像大 | Phase 6 可把上传目录外置（starter 支持改配置，业务代码零改动）|
| 数据是 2024 年的 | 秒杀/订单时间对不上 | 写数据生成脚本批量造测试数据 |
| 学不完 | 技术栈多，变成「什么都听过什么都没做过」 | **每个技术必须有真实理由 + 一次真实踩坑**，否则不引入 |

---

## 六、下一步（当前状态 · 2026-09-13）

```
✅ 已完成  Phase 0  17 处缺陷修复 + 工程化地基
✅ 已完成  Phase 1  Boot 3 升级 + Security6/JWT/RBAC/BCrypt/CSRF
                    + 令牌生命周期（黑名单/refresh/重放检测）
                    + 自定义 starter（考点 16）
          验证    单测 66 个（应用 55 + starter 11）
                   端到端 138 项断言（Phase0 56 / Phase1 32 / 令牌 38 / starter 12）全绿
📦 数据备份  .hermes/backups/shop-phase1-<时间戳>.sql（8 表全量，已校验）

▶ 下一步  Phase 2（必做，约 1 周）
  2.1 设计 docs/schema_v2.sql（新表 + 迁移脚本）
  2.2 引入 MyBatis-Plus（分页 / 乐观锁 / 自动填充插件）
  2.3 购物车改 cart_item 关联表（保留 lxy_cart 作兼容层，非破坏性切换）
  2.4 库存 + 乐观锁/悲观锁两条实现（README 记对比结论）
  2.5 订单状态机 + order_item 明细 + 下单事务
  2.6 索引优化 + EXPLAIN 前后对比表

完成标志：分类→商品→购物车→下单→支付→评价 全链路走通
         + README 有 EXPLAIN 前后对比表
```

---

## 七、验收总表（简历可写的话术）

每完成一个 Phase，填这里。**没有数字的条目不要写进简历。**

| Phase | 简历话术模板 | 实测数字（待填） |
|---|---|---|
| 0 | 接手遗留项目，修复 14 处核心缺陷，完成工程化改造（统一响应/全局异常/参数校验/单元测试），建立建表脚本与版本基线 | — |
| 1 | 将 Spring Boot 2.4.3 升级至 3.2，完成 `javax`→`jakarta` 全量迁移；基于 Spring Security 6 + JWT + RBAC 重构认证授权，密码由无盐 MD5 升级为 BCrypt | — |
| 2 | 重构领域模型（分类 / 库存 / 购物车关联表 / 订单明细 / 地址 / 评价），逗号串购物车改为关联表；用乐观锁解决超卖；索引优化使列表查询 ___ms → ___ms | — |
| 3 | 基于 Redis 实现商品缓存与「穿透 / 击穿 / 雪崩」治理，命中率 ___%；Redisson 分布式锁解决重复下单 | 命中缓存 ___ms vs 直查库 ___ms |
| 4 | 引入 RabbitMQ 实现下单异步化与订单超时自动取消，消费幂等由唯一索引保证；秒杀用 Redis + Lua 原子预减库存，零超卖 | — |
| 5 | 补齐工程化：Knife4j 接口文档、AOP 统一日志与 `@RateLimit` 限流注解、模拟支付回调（验签 + 幂等）、DTO/VO 分层 | — |
| 6 | Docker Compose 一键起 MySQL / Redis / RabbitMQ / 应用，Nginx 反向代理；产出 3 个接口的 QPS 与耗时 | QPS ___ / 耗时 ___ms |

---

# Phase 0 完成报告（2026-09-13）

## 交付内容

| Task | 内容 | 状态 |
|---|---|---|
| 0.1 | `.gitignore` + `git init` + baseline tag（3782 文件 / 52MB） | ✅ |
| 0.2 | `docs/schema.sql`：修正 `created_at` 类型、统一 utf8mb4、补齐 15 个二级索引 | ✅ |
| 0.3 | 修复 10 个会导致运行时崩溃的缺陷 | ✅ |
| 0.4 | 修复 4 个逻辑缺陷（`!= "null"`、定时任务循环内写库、图片删除路径、imgPath 不一致） | ✅ |
| 0.5 | 工程化地基（统一响应体 / 全局异常 / 参数校验 / 日志规范 / 配置外置 / 45 个单测） | ✅ |
| 0.6 | 编译 + 启动 + 打包产物端到端验证（54 项断言全绿） | ✅ |

## 修复清单（17 项：计划内 14 + 打包验证阶段新发现 3）

**必崩类（10）**

| # | 位置 | 问题 | 根因 |
|---|---|---|---|
| 1 | `UserMapper.xml` | 后台编辑用户必崩 | SQL 引用不存在的 `#{name}`/`#{age}` → BindingException |
| 2 | `UserController:99` | 兜底分支二次崩溃 | session 存 int，强转 String → ClassCastException |
| 3 | `ProductController:37` | 点"添加商品"必 500 | `templates/admin/product_add.html` 不存在（已补页面 + POST 处理器 + 列表入口） |
| 4 | `ShopSaleController:76` | 改商品信息页 404 | `redirect:/shop/changeProductInformation` 缺 `{id}`（死代码，已删） |
| 5 | `ShopCartController:52` | 删购物车商品后页面空 | 返回视图名而非重定向，Model 未装配数据 |
| 6 | `application.properties:12` | 上传商品必崩 | `upload.path` 指向不存在的 `E:/java/...` |
| 7 | `ShopIndexController:40` | 商品不足 100 条时越界 | `subList(0,100)` 写死，缺 `Math.min` |
| 8 | `AdminController:29` | 改管理员密码 NPE | `getAdmin()` 返回 null 未判空 |
| 9 | `ShopBuyController`、`ShopPersonalController` | 未登录 NPE | 直接对 null session 属性调 `.equals()` |
| 10 | `SearchProcess:130` | 搜索补位死循环 / 异常 | `while + random.nextInt(0)` 不收敛（已改洗牌顺序补齐） |

**逻辑类（4）**

| # | 位置 | 问题 |
|---|---|---|
| 11 | `SessionCheck:19,28` | `!= "null"` 比引用恒为 true，else 分支是死代码 |
| 12 | `DailyUpdateTask:52` | 写库语句在 while 循环体内（N 次无效写）；全部淘汰时不写回 |
| 13 | `ShopSaleController:107` | 用 URL 当磁盘路径删文件，永远删不掉 |
| 14 | `ShopSaleController:113` | 存图用 `{id}.png` 但写回库用旧 `imgPath`，可能造成坏数据 |

**打包验证阶段新发现（3 项，均为「IDE 能跑、jar 跑不起来」类问题）**

| # | 位置 | 问题 | 根因 |
|---|---|---|---|
| 15 | 10 个文件 / 34 处视图名 | 打成 jar 后**所有页面 500** | `return "/shop/index"` 带前导斜杠 → `classpath:/templates//shop/index.html`（双斜杠）。展开目录会归一化 `//`，ZIP 条目匹配不会 → `Error resolving template` |
| 16 | `Application:11` | 所有命令行参数静默失效 | `SpringApplication.run(Application.class)` 丢了 `args`，`--server.port`/`--spring.profiles.active` 全不生效，破坏配置外置能力 |
| 17 | `ShopCartController`（自引入） | 我在批量修视图名时误把 `return "redirect:/shop/cart"` 改成 `return "shop/cart"`，回退了 Bug 5 的重定向修复 | 补丁的模糊匹配命中了注释里的同名文本；已按「重读文件再改」的原则修正 |

> **教训（已写入 README 开发约定）：** 任何页面改动都必须用**打包后的 jar** 验证一遍，
> 只跑 `spring-boot:run` 会漏掉整类问题。缺陷 15 影响全部 25 个模板，若留到 Phase 10
> 做 Docker 时才发现，排查成本会高得多。

## 顺带修复的安全问题（原本不在清单里）

| 问题 | 说明 | 修复 |
|---|---|---|
| **水平越权 / IDOR（下架商品）** | 原代码只凭 `imgPath` 就物理删除商品，任何人可删他人商品 | 校验 `username == product.sellerName`，越权返回 403 |
| **水平越权（推进订单状态）** | 任何人可推进任意订单状态 | 校验操作者必须是该订单买家或卖家 |
| **异常信息泄露** | `printStackTrace` + 把内部信息返回前端 | 系统异常对外仅返回兜底文案，堆栈只入日志 |
| `getNowId()` 空表 NPE | `int` 接收可能为 null 的查询结果 | Mapper 返回类型改 `Integer` |

## 新增工程化设施

| 文件 | 作用 |
|---|---|
| `shop/common/Result.java` | 统一响应体 `{code,message,data,success}` |
| `shop/common/ErrorCode.java` | 业务错误码枚举（分段约定：2xx/4xxx/5xxx） |
| `shop/common/BizException.java` | 业务异常（预期内失败，带错误码） |
| `shop/common/GlobalExceptionHandler.java` | 全局异常收敛 + HTTP 状态码映射 + 预期内/外分级日志 |
| `src/main/resources/logback-spring.xml` | 控制台 + 滚动文件 + 错误单独归档；本项目 DEBUG，框架 INFO |
| `src/test/java/**` | 8 个测试类 / 45 个用例 |
| `docs/schema.sql` | 可执行的建表脚本（含设计缺陷说明注释） |
| `src/main/resources/templates/admin/product_add.html` | 补齐缺失模板 |

## 验证证据（实测）

**单元测试**
```
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**打包产物端到端验证（`mvn clean package` + `java -jar`，端口 18080）**

```
STEP 1/3  干净构建 + 单元测试          Tests run: 45, Failures: 0 → BUILD SUCCESS
          可执行 jar                  target/springboot3-1.0-SNAPSHOT.jar (116M)
STEP 2/3  应用启动                    Started Application in 3.835 seconds
STEP 3/3  接口行为验证                 RESULT: PASS=54  FAIL=0
```

覆盖：统一响应体契约、异常→HTTP 状态码映射（400/401/403/404）、
13 个页面渲染（含此前 500 的全部模板）、登录与会话保持、后台 7 个页面、
multipart 上传落盘、2 个越权场景、中文编码链路（HTTP→JDBC→MySQL）、
运行期异常扫描。数据完整性核对后与基线一致
（商品 3659 / 用户 30 / 订单 28 / 购物车 29，图片 3665 张）。

**交付时的一条硬性要求：** 页面相关改动必须用打包后的 jar 验证，
不能只跑 `spring-boot:run`（原因见「新发现 #15」，已在 README 里写成开发约定）。

**端到端冒烟（应用启动于 8080，Maven 3.9.9 / JDK 22）**

| 场景 | 期望 | 实测 |
|---|---|---|
| `GET /shop/index` 未登录 | 200 + 商品列表 | 200，100 个商品卡片 |
| `GET /shop/checkSession` | 统一响应体 | `{"code":200,"message":"操作成功","data":false,"success":true}` |
| `POST /shop/addToCart` 未登录 | 401 | 401 `{"code":401,...,"success":false}` |
| `POST /shop/addToCart` imgPath 空 | 400 校验失败 | 400 `商品标识(imgPath)不能为空` |
| `POST /shop/login` lisi/123456 | 302 → /shop/index | 302 ✅ |
| 登录后 6 个个人中心页面 | 200 | 全部 200 |
| `GET /admin/product_add` 未登录 | 302 → /admin/login | 302 ✅ |
| 管理员登录后 `GET /admin/product_add` | 200（原为 500） | 200，含卖家下拉框 |
| 后台 4 个管理页面 | 200 | 全部 200 |
| 中文搜索 `书`（UTF-8 编码） | 有结果 | 91 条命中 |
| **越权下架他人商品** | 403 且商品不消失 | **403 `只能下架自己发布的商品`，商品仍在** |
| 下架自己的商品 | 200 且商品消失 | 200，商品已删 |
| 未登录下架 | 401 | 401 ✅ |
| **越权推进他人订单状态** | 403 | **403 `无权操作该订单`** |
| 上传商品（multipart） | 200 + 文件落盘 | 200，`3668.png` 确实写入磁盘 |
| 运行期异常扫描 | 无意外异常 | 日志仅含测试故意触发的 401/403/404/400 |

**数据完整性（测试后核对，与初始一致）**

```
商品 3659 / 用户 30 / 订单 28 / 购物车 29    ← 全部还原
```

## 面试可用话术（Phase 0 部分）

> 「接手这个遗留项目后我先做了三件事：**打 Git 基线**保证可回滚、**导出建表脚本并做数据库审计**、
> **梳理全链路缺陷**。审计中发现 14 处会导致运行时崩溃或数据错误的缺陷，
> 其中最有代表性的是：MyBatis 的 XML 引用了接口上不存在的 `@Param`（BindingException）、
> 用 `!= "null"` 比较字符串导致 else 分支成为死代码、以及
> **一个水平越权漏洞 —— 原代码只凭图片路径就能删除任意用户的商品**。
>
> 修复之外我补了工程化地基：统一响应体与全局异常处理（并把业务错误码映射到 HTTP 状态码，
> 因为只看 HTTP 200 会让监控失真）、JSR-303 参数校验、SLF4J + Logback 日志规范，
> 以及项目此前**完全没有的单元测试（45 个用例）**。
> 整个过程每个 Task 一次 commit，出问题随时能回到基线。」

## 已知遗留（留给后续 Phase，已在代码注释中标注）

| 遗留项 | 处理阶段 |
|---|---|
| 密码无盐 MD5 | Phase 1（BCrypt） |
| `lxy_order.condition` 保留字命名、状态流转硬编码 | Phase 2（状态机） |
| 购物车逗号字符串、无数量字段、并发丢失更新 | Phase 2（cart_item 表） |
| 下单无事务 / 无幂等 / 非原子（超卖） | Phase 4（秒杀章节的引入案例） |
| `cleanCart` 全表扫描购物车，N+1 查询 | Phase 2 |
| 用「最大 id + 1」生成文件名（ID 竞态） | Phase 6（雪花算法） |
| 删除订单后未回滚商品 `sold_time`，商品被永久锁死 | Phase 2 |
| `logs/` 目录未纳入 .gitignore 的目录级排除（`*.log` 已覆盖） | 无需处理 |

---

# Phase 1 完成报告（2026-09-13，主体完成）

## 交付内容

| Task | 内容 | 状态 |
|---|---|---|
| 1.1 | `pom.xml`：Boot 2.4.3 → 3.3.5，引入 `spring-boot-starter-security` + jjwt 0.12.6 | ✅ |
| 1.2 | `javax.servlet` / `javax.validation` → `jakarta.*` 全量迁移（18 个文件） | ✅ |
| 1.3 | `SecurityConfig`：`SecurityFilterChain`、`BCryptPasswordEncoder`、BCrypt、URL 放行规则 | ✅ |
| 1.4 | `JwtUtil` + `JwtAuthenticationFilter`（Cookie 与 `Authorization: Bearer` 双通道） | ✅ |
| 1.5 | 用户表 `role` / `status` 字段 + RBAC（`hasRole("ADMIN")` + `@PreAuthorize`） | ✅ |
| 1.6 | 登录返回 JWT 写 HttpOnly Cookie；登出清 Cookie；401/403 按「接口 vs 页面」分流 | ✅ |
| 1.7 | 存量 30 个用户 + 1 个管理员的 MD5 密码「登录时透明升级」为 BCrypt | ✅ |
| 1.8 | 自定义 `oss-spring-boot-starter` | ⏳ 待做（考点 16） |
| 追加 | CSRF 防护（表单隐藏域 + `csrf.js` 自动注入请求头两条链路） | ✅ |
| 追加 | 移除 Controller 里用 HttpSession 判断「是否登录/是否管理员」的重复鉴权 | ✅ |

## 关键设计决策（面试可讲）

### 1. 为什么 JWT 放 HttpOnly Cookie，而不是 localStorage + Authorization 头

页面跳转（`<a href>`）与表单提交由浏览器发起，**无法手动附加请求头**，只有 Cookie 会被自动携带。
若强行用请求头方案，就得把 25 个服务端渲染模板全部改成前端路由 —— 收益低、风险高。
Cookie 同时设了三个安全属性：`HttpOnly`（XSS 偷不走）、`SameSite=Lax`（跨站不带）、
`Secure`（生产开，仅 HTTPS 发送）。过滤器仍额外支持 `Authorization: Bearer`，
让同一套认证能服务浏览器与小程序/App 两类客户端。

### 2. 存量 MD5 密码怎么迁移

MD5 是单向的，写脚本「批量转换」根本做不到（反推不出明文）。采用**登录时透明升级**：
用户下次登录、校验通过的那一刻顺手把库里的 MD5 换成 BCrypt。用户无感知、不用重置密码，
存量摘要随真实流量逐步收敛。这本质是灰度迁移思路 —— 新旧格式在过渡期共存。
（同时必须把 `password` 列从 `varchar(50)` 扩到 `varchar(100)`：BCrypt 摘要固定 60 字符，
不扩列会在写入时报 `Data too long`。见 `docs/migration_phase1.sql`。）

### 3. 401 与 403 必须分流，而且接口与页面要分别处理

- `401 = 我不知道你是谁`，`403 = 我知道你是谁但你没权限`，混用会让前端做错决策
  （收到 403 去跳登录页，用户重新登录后依然 403，体验死循环）。
- 接口请求（`Accept: application/json` / `X-Requested-With` / 端点白名单）→ 返回统一响应体 JSON；
  页面请求 → 302 跳对应端登录页。给浏览器返回裸 JSON 或给 fetch 返回 302（会被自动跟随、
  最终拿到登录页 HTML）都是踩过的坑。

### 4. 无状态 JWT 与 HttpSession 的边界

`SessionCreationPolicy.STATELESS` 只表示「认证状态不落 session」。
本项目仍用 HttpSession 承载**视图展示数据**（用户名/地区/学校）与一次性提示（`saleError` 等），
这些是纯 UI 状态，不参与任何授权判断。**授权看 JWT，展示看 session，职责分离。**
后台 Controller 里原本用 `session.getAttribute("adminuser")` 做鉴权，已全部删除 ——
它与安全层的 JWT 判断构成「两份真相」，session 一失效就会出现「认证通过却被自己代码踢出去」。

## 踩坑记录（含两个不查源码很难定位的问题）

**坑 1：session id 每个请求都在轮换。**
只配 `sessionCreationPolicy(STATELESS)` 不够：`SessionManagementFilter` 仍在链上，
而「无状态模式下每次请求的认证都是新的」，于是会话固定攻击防护
（`ChangeSessionIdAuthenticationStrategy`）**每个请求都执行一次**，表现为每次响应都下发新的
`JSESSIONID`。任何缓存了 Cookie 的客户端（浏览器预取、压测工具、脚本）下一个请求就丢会话。
解法：显式 `.sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())`。
（定位方式：`--logging.level.org.springframework.security=DEBUG`，直接看到
`ChangeSessionIdAuthenticationStrategy - Changed session id from ...`。）

**坑 2：Thymeleaf 的 `th:action` 没有自动注入 `_csrf` 隐藏域。**
`RequestDataValueProcessor` 需要从 request 属性里取到 `CsrfToken`，而**非 Xor 的**
`CsrfTokenRequestAttributeHandler` 默认不写这个属性 —— 现象是「表单渲染正常、就是没有
`_csrf`」，提交必 403。解法：显式 `setCsrfRequestAttributeName("_csrf")`，
同时在表单里写死隐藏域（不依赖框架的隐式行为，可预测性优先）。
另外还修了一个连带 bug：CSRF 失败抛的是 `AccessDeniedException` 的子类，
会被「权限不足 → 跳首页」的分支吞掉，表现成「提交后莫名回首页」，真正原因完全看不到 ——
现在在 `RestAccessDeniedHandler` 里单独识别 CSRF 异常并返回明确的 403。

**坑 3：CookieCsrfTokenRepository 的校验是「请求里的 token 与 Cookie 里的 token 比对」。**
所以 JSON 请求只发 `X-XSRF-TOKEN` 头、不带同一份 Cookie，一样 403。
且 token 每次响应都会轮换，脚本必须**发请求前现取**，用早先抓到的旧值必 403。

## 验证证据（实测）

**单元测试**
```
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0    BUILD SUCCESS
```

**Phase 1 端到端验证（打包 jar，端口 18080，32 项断言）**
```
PASS=32  FAIL=0
```
覆盖：匿名可访问性、CSRF 隐藏域注入与缺失即 403、页面请求 302 跳登录 vs 接口请求 401 JSON、
登录成功与 Cookie 三属性（HttpOnly / SameSite=Lax / Max-Age）、携带 Cookie 的接口调用、
缺 CSRF 头即 403、`ROLE_USER` 访问后台被拒、管理员登录后可访问后台、
登出清 Cookie 后受保护资源不可访问、库中密码已是 BCrypt、`role` 字段就位。

**Phase 0 回归（确认 Phase 1 没打破原有能力，56 项断言）**
```
PASS=56  FAIL=0
数据完整性：商品 3659 / 用户 30 / 订单 28 / 购物车 29（与基线一致，无污染）
```

## 面试可用话术（Phase 1 部分）

> 「我把技术栈从 Spring Boot 2.4.3 升到 3.3.5，`javax`→`jakarta` 全量迁移，
> 然后把认证授权从『session + 无盐 MD5』改造成 Spring Security 6 + JWT 的无状态方案。
>
> 密码这块有个现实约束：库里 30 个用户的摘要都是 MD5，而 MD5 单向、没法批量转换，
> 我用了**登录时透明升级** —— 用户下次登录校验通过时顺手把摘要换成 BCrypt，
> 用户完全无感知，存量摘要随流量自然收敛。
>
> 凭证我放在 HttpOnly + SameSite=Lax 的 Cookie 里而不是 localStorage：
> 我们的页面是服务端渲染的，页面跳转根本没法带 `Authorization` 头；
> 而且 HttpOnly 能防住 XSS 偷 token。因为用了 Cookie，**CSRF 就必须防** ——
> 表单用隐藏域、ajax 由 `csrf.js` 统一注入请求头，令牌走 BREACH 无关的非 Xor 处理器
> 以保证 Cookie / 隐藏域 / 请求头三处取值一致。
>
> 401 和 403 我做了分流，而且接口返回 JSON、页面 302 跳登录 —— 混在一起的话
> 前端会把『没权限』当成『没登录』，出现重新登录后依然 403 的死循环。
>
> 过程中踩了两个比较隐蔽的坑：一是只配 STATELESS 不够，会话固定攻击防护会在每个请求
> 触发 session id 轮换，导致缓存 Cookie 的客户端丢会话；二是 Thymeleaf 的 `th:action`
> 在我们这套 CSRF 配置下不会自动注入隐藏域，得显式指定属性名 —— 这两个都是靠
> Spring Security 的 DEBUG 日志和逐请求对比响应头定位出来的。」

## 已知遗留（明确留给后续）

| 遗留项 | 说明 | 处理阶段 |
|---|---|---|
| 自定义 `oss-spring-boot-starter` | 考点 16：`@ConditionalOnProperty` 切换本地/OSS/MinIO | Phase 1 收尾（最后一项） |
| `shopusername` 哨兵值「请登录」 | 保留兼容（仅展示层），授权已不依赖它；后续可用统一拦截器彻底替换 | Phase 2 |
| `role` 字段未在后台界面暴露 | 数据库/实体已就位，管理界面尚未提供改角色入口 | Phase 2 |
| 上传目录仍在 `src/main/resources` | 运行时写源码目录会被重新编译覆盖 | Phase 1 收尾 / Phase 10 |
| 验证脚本在 `.hermes/`（未入库） | 沿用 Phase 0 约定（`.hermes/` 已 gitignore），属本地工具链 | — |

---

# Phase 1 收尾 — 令牌生命周期（2026-09-13）

**要解决的问题：** 无状态 JWT 一旦签发，在有效期内<b>无法撤回</b>。
「登出只清 Cookie」是最常见也最危险的错误实现 —— 看起来登出了，
但任何人拿着那个 token 还能继续用；密码泄露后改密码也拦不住攻击者手里的旧凭证。

## 交付内容

| 类 | 职责 |
|---|---|
| `AccessToken`（record） | 解析结果：身份 + `jti` + 过期时间（后两者是「可撤销」的必要条件） |
| `JwtUtil`（改） | access token 写入 `jti`；`parseAccess` 返回含 jti/expiry 的完整信息 |
| `RedisTokenStore` | Redis 上的令牌状态：access 黑名单、refresh 存储、已用/宽限标记、按用户批量撤销；**所有操作带降级** |
| `RefreshTokenService` | refresh 的签发 / 轮换 / 吊销 / 重放检测 |
| `JwtCookieSupport`（改） | 双 Cookie（ACCESS_TOKEN + REFRESH_TOKEN）读写与清理 |
| `JwtAuthenticationFilter`（改） | 先查黑名单；access 不可用时用 refresh <b>透明续期</b>并写回新 Cookie |
| 登录/登出/改密码（改） | 登录下发双令牌；登出「作废 refresh + 拉黑 access + 清 Cookie」；改密码撤销该用户全部 refresh |

## 关键设计决策（面试可讲）

### 1. 为什么 access 用 JWT，refresh 却用「一串随机 UUID」

两者诉求正好相反，必须分开处理：

| | access token | refresh token |
|---|---|---|
| 校验频率 | 每个请求 | 每 30 分钟一次 |
| 因此需要 | <b>无状态</b>（不查存储，可横向扩展） | <b>可即时吊销</b>（长期凭证，泄露影响大） |
| 选型 | JWT（签名自证） | 随机串 + Redis 存储 |
| 代价 | 签发后撤不回 → 用短寿命 + 黑名单兜 | 每 30 分钟查一次 Redis（可接受） |

「既然用了 JWT，为什么服务端还要存东西？」——答案就是：
**能无状态的部分无状态，做不到无状态的部分（撤销）显式存起来**，
而不是硬用 JWT 一条路走到黑。

### 2. 黑名单 TTL = 令牌剩余寿命（这是黑名单能不膨胀的关键）

黑名单用 `shop:killed:{jti}`，TTL 精确设为该令牌距离自然过期还剩多久。
于是「令牌一过期，黑名单条目也自动消失」，**不需要任何清理任务**，也不会无限堆积。
如果不设 TTL 或设成固定值，要么条目永久残留，要么提前失效放行 —— 两头都是坑。

### 3. refresh 轮换 + 重放检测 + 并发宽限

- **轮换**：每次刷新都换新 refresh token，旧的一次性作废。
- **重放检测**：旧令牌被再次使用时，正常客户端不会这么做 ⇒ 说明它泄露了 ⇒
  撤销该用户<b>全部</b> refresh token（宁可让用户重新登录，也不能让攻击者继续用）。
- **并发宽限（60s）**：但「重复提交」也可能是客户端并发刷新（页面同时发多个请求）。
  所以轮换时留一个 60 秒宽限窗口，窗口内重复提交返回<b>同一个</b>新令牌，
  不会把正常用户误判成攻击者踢下线 —— 少了这一步，重放检测会变成「偶发登出」的 bug 源。

### 4. Redis 故障时的降级：宁可撤销失效，不可把用户锁在门外

`RedisTokenStore` 所有方法都 try-catch，异常时记 WARN 并返回「安全默认值」
（未命中黑名单 / 没有 refresh token）。取舍很明确：
- 抛异常 ⇒ 「Redis 抖动 → 全站 401」，故障被放大；
- 降级 ⇒ 抖动期间无法提前撤销令牌、无法续期，用户可能要在 30 分钟后重新登录。

后者是可接受的降级，前者是事故。

## 验证证据（实测）

**单元测试**
```
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0    BUILD SUCCESS
（较上一阶段 +10：新增 JwtUtilTest，覆盖 jti 唯一性、篡改拒绝、密钥轮换失效、
  过期拒绝、弱密钥启动即失败、有效期换算等边界）
```

**令牌生命周期端到端（打包 jar + 真实 Redis，38 项断言）**
```
PASS=38  FAIL=0
```
覆盖：登录下发双令牌（各含 HttpOnly / SameSite / Max-Age）→ Redis 中生成 refresh 记录与用户令牌集合
→ <b>登出后重放登出前的 access token 被 401 拦下</b>（证明黑名单真的生效）
→ 旧 refresh token 无法再续期 → access 缺失时浏览器请求仍 200 并<b>透明换发新 ACCESS_TOKEN</b>
→ refresh 已轮换且旧令牌被标记「已使用」→ 宽限期内重复提交返回同一个新令牌（不误判）
→ 超出宽限期的重放被拒绝且<b>该用户全部 refresh token 被撤销</b>，日志留下可监控的 WARN 告警。

**回归（确认没打破既有能力）**
```
Phase 0 端到端 56 项断言：PASS=56  FAIL=0
Phase 1 安全面 32 项断言：PASS=32  FAIL=0
数据完整性：商品 3659 / 用户 30 / 订单 28 / 购物车 29，图片 3665 —— 与基线一致
```

## 面试可用话术（补充）

> 「无状态 JWT 最大的问题不是性能，是**撤销**：签发出去就收不回来了。
> 我的做法是 access token 做短（30 分钟）保持无状态，另发一个 refresh token 存在 Redis 里 ——
> 它是随机串不是 JWT，因为它必须能被即时吊销。
>
> 登出要三步一起做：作废 refresh、把 access 的 jti 写进黑名单、清 Cookie。
> 只清 Cookie 是最常见的错误实现，token 其实还能用。
> 黑名单的 TTL 我设成『令牌剩余寿命』，所以令牌一过期黑名单条目自动消失，
> 不需要任何清理任务，也不会无限堆积。
>
> refresh 我做了一次性轮换 + 重放检测：旧令牌被再次使用就说明泄露了，
> 撤销该用户全部会话。但这里有个坑 —— 客户端并发刷新也会重复提交，
> 所以留了 60 秒宽限窗口，窗口内返回同一个新令牌，避免把正常用户误判成攻击者。
>
> 另外 Redis 挂掉时我选择降级而不是抛异常：撤销暂时失效，但不会演变成『缓存抖动导致全站 401』。」

## 本阶段踩的坑（记录）

| 坑 | 现象 | 处理 |
|---|---|---|
| `ResponseCookie` 未 `toString()` | 编译期 `ResponseCookie 无法转换为 String` | `addHeader` 需要 String，补 `.toString()`（4 处） |
| patch 的模糊匹配吃掉相邻行 | changePass 丢了 `return`、logout 丢了 `session` 声明与 `removeAttribute("shopusername")` | 改为精确字符串替换 + 断言「关键语句仍在」；编译/回归分别验证 |
| `redis-cli --scan` 无输出（本机 5.0.14.1 Windows） | 计数断言恒为 0，且清理 trap 静默失效、db3 残留 key | 测试脚本一律改用 `keys`；redis-cli 输出带 CRLF，用前必须 `tr -d '\r'` |
| 断言把证据标记算成存活令牌 | `shop:rt:*` 也匹配 `shop:rt:used:*` | 统计存活令牌时排除 `:used:` / `:grace:`，并把剩余 key 打印出来便于核对 |

---

# Phase 1 收尾 — 自定义 Starter（2026-09-13，考点 16）

**交付：** `oss-spring-boot-starter`（独立 Maven 工程，`com.example:oss-spring-boot-starter:1.0.0`）

## 做了什么

把「文件上传」从业务代码里抽出来，做成一个**自动配置的 starter**：
业务侧只注入 `FileStorage`，调用 `store(file, key)` 拿回一个 URL、调用 `delete(url)` 清理旧图。
上传/删除相关的「拼路径、建目录、防穿越、删文件」全部收敛到实现类里。

| 类 | 职责 |
|---|---|
| `OssAutoConfiguration` | `@AutoConfiguration` + `@ConditionalOnProperty` + `@ConditionalOnMissingBean` |
| `OssProperties` | `oss.*` 配置绑定；含 URL 前缀的规范化容错 |
| `FileStorage` | 对外契约：`store` / `delete`，**以 URL 为交互单位**（业务方不需要知道文件在哪） |
| `LocalFileStorage` | 本地实现：路径穿越防护、目录自动创建、异常带根因 |
| `META-INF/spring/...AutoConfiguration.imports` | Boot 3 的自动配置注册（SPI） |

## 关键设计决策（面试可讲）

### 1. 一个 starter 由两部分组成，但包名必须独立

`autoconfigure`（自动配置 + 配置类）+ `starter`（依赖聚合）。本项目体量小，两者做进同一个 jar，
但包名用独立的 `com.example.oss`，**刻意不放进业务包 `shop.*`** ——
starter 是给别的项目复用的，一旦它反过来依赖业务代码就失去了复用性。

### 2. 依赖全部标 `optional`

starter 的职责是「补充」而不是「强加」。它只在消费方已有 Spring Boot / Spring Web 时工作，
不该把 Spring 全家桶再传递一遍 —— 那会引发版本冲突，也是新手写 starter 最容易犯的错。

### 3. 自动配置必须让位给用户配置（`@ConditionalOnMissingBean`）

消费方只要自己声明一个 `FileStorage` Bean，自动配置就不再创建。
「约定优于配置」能被接受的前提就是**框架不夺权**，这是所有官方 starter 的规则。

### 4. Boot 2 与 Boot 3 的注册方式差异（高频考点）

- Boot 2.7 之前：`META-INF/spring.factories` 里写 `EnableAutoConfiguration=...`
- Boot 2.7 起废弃，**Boot 3 完全改用** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

本项目用后者。

### 5. 接口以 URL 为交互单位，而不是文件路径

业务代码要的是「能存库、能渲染进 `img src` 的地址」，介质是磁盘还是对象存储属于实现细节。
`delete(String publicUrl)` 接收 URL 才能让各实现自行解析：本地按前缀截相对路径、
云实现按 objectKey 调 SDK，而**调用方始终只有一行**。

### 6. 不预写没有实现也没有测试的「云存储实现」

ROADMAP 原计划是「本地 / OSS / MinIO 三实现切换」。实际只落地 `local` ——
因为 MinIO/OSS 在此环境没有可运行的服务端，写了也无法验证，
等于把「未验证的代码」混进简历项目。类注释里留了新增实现的完整写法与条件放宽提醒，真要接时再补。

## 验证证据（实测）

**starter 单元测试（11 个）**
```
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0    BUILD SUCCESS
```
覆盖：落盘与 URL 拼接、目录自动创建、子目录与分隔符归一化、**路径穿越被拒**、
空文件/空 key 拒绝、按 URL 删除、删除幂等、不删非本存储 URL、URL 前缀容错（缺首尾斜杠）。

**集成端到端（12 项断言）**
```
PASS=12  FAIL=0
```
用**非默认配置**启动（`--oss.local-dir` 指到临时目录、`--oss.url-prefix=/custom-upload/`），
然后真实上传一张图，断言：

| 断言 | 说明 |
|---|---|
| starter jar 是普通 jar（无 `BOOT-INF`） | 打成 fat jar 会让消费方加载不到类，是 starter 最经典的坑 |
| 含 `AutoConfiguration.imports` | 否则自动配置根本不会被加载 |
| 上传返回 200 | 链路通 |
| DB 里是 `/custom-upload/3668.png` | **配置贯穿到了业务代码**，不是「碰巧没报错」 |
| 文件落在自定义目录 | `@ConfigurationProperties` 真的生效 |
| 默认图片目录未被写入（仍 3665 张） | 确实切走了，不是双写 |
| 无残留测试数据 | 清理干净 |

**回归（确认没影响既有能力）**
```
Phase 0 端到端 56 项断言：PASS=56  FAIL=0（含走 starter 的 multipart 上传）
Phase 1 安全面 32 项断言：PASS=32  FAIL=0
令牌生命周期 38 项断言：PASS=38  FAIL=0
应用单测 55 + starter 单测 11 = 66 个全绿
数据完整性：商品 3659 / 用户 30 / 订单 28 / 购物车 29，图片 3665
```

## 面试可用话术

> 「我把文件上传抽成了一个自定义 starter。消费方往依赖里加一行，
> 就能直接注入 `FileStorage` 用，不需要写任何 `@Configuration`。
>
> 关键是三个注解的配合：`@AutoConfiguration` 配合 Boot 3 的
> `AutoConfiguration.imports` 做注册（Boot 2 用的是 `spring.factories`，3.0 之后改了，
> 这是常被问到的版本差异）；`@ConditionalOnProperty` 决定装哪个实现；
> `@ConditionalOnMissingBean` 保证用户自己声明了 Bean 时框架让位 ——
> 没有这一条，「自动配置」就会变成「框架夺权」，没人敢用。
>
> 接口我设计成以 URL 为交互单位而不是文件路径：业务只关心拿到一个能渲染的地址，
> 所以删除也接收 URL，本地实现截相对路径、云实现调 SDK 删，
> 调用方在两种介质下都是同一行代码。
>
> 另外我把依赖全标了 optional，starter 不该把 Spring 全家桶再传递一遍。
> 还有个容易忽略的验证点：starter 必须是普通 jar，**不能**被 boot 插件打成 fat jar，
> 否则消费方拿到 BOOT-INF 结构，类根本加载不到 —— 我在验证脚本里专门断言了这一条。」

## 本阶段踩的坑（记录）

| 坑 | 现象 | 处理 |
|---|---|---|
| 把 MSYS 路径传给 java | `--oss.local-dir=/c/Users/...` 被 java 解释成 `C:\c\Users\...`，文件写到凭空多出来的目录里 | 传给 java 的路径用 `cygpath -w` 转成 Windows 风格（**curl 相反，必须相对路径**，两个工具要求不同） |
| 抓 CSRF token 时漏了 `-c` | 页面隐藏域里是**轮换后**的新 token，jar 里还是旧值 → 提交 403 | 抓 token 的页面请求必须 `-b jar -c jar`（与既有脚本的 `csrf_of` 保持一致） |
| 断言写在清理之前 | 「无残留」断言读的是清理前的状态，恒失败 | 显式清理 → 再断言 |

> 附带提醒：本机 `/c/c` 是**已存在的用户目录**（含用户自己的文件）。
> 上面第一个坑会在其中创建 `Users/...` 子树，清理时必须只删自己创建的部分，绝不能整个 `rm -rf /c/c`。



