# 校园二手交易平台 → 面试级电商平台 改造规划

> **执行方式建议：** 按 Phase 顺序执行，每个 Phase 完成后可独立交付/写进简历。
> 每完成一个 Task 就 commit 一次，保证任何时刻项目可运行。

**目标：** 把一个 Spring Boot 2.4.3 课程级 CRUD 二手交易项目，改造为覆盖「高并发 / 分布式 / 缓存 / MQ / 搜索 / 微服务 / 可观测性」全链路的电商平台，
使面试官从 Java 基础问到分布式架构，都能在这个项目里找到可落地的实现和可量化的数据。

**核心策略：** 保留现有资产（3659 件真实商品、3665 张图、校园二手业务域、自研搜索权重算法、地理/学校筛选），重建内部架构。

---

## 进度追踪

| Phase | 状态 | 完成日期 | 备注 |
|---|---|---|---|
| Phase 0 修 bug + 工程化地基 | ✅ 已完成 | 2026-09-13 | 见文末「Phase 0 完成报告」 |
| Phase 1 Spring Boot 3 + Security + JWT | ⏳ 待开始 | — | |
| Phase 2 领域建模重构 | ⏳ 待开始 | — | |
| Phase 3 Redis 缓存体系 | ⏳ 待开始 | — | |
| Phase 4 高并发秒杀 | ⏳ 待开始 | — | |
| Phase 5 消息队列 | ⏳ 待开始 | — | |
| Phase 6 分布式能力 | ⏳ 待开始 | — | |
| Phase 7 Elasticsearch + 推荐 | ⏳ 待开始 | — | |
| Phase 8 IM 私聊 | ⏳ 待开始 | — | |
| Phase 9 可观测性 | ⏳ 待开始 | — | |
| Phase 10 部署与压测交付 | ⏳ 待开始 | — | |
| Phase 11 微服务拆分（可选） | ⏳ 待开始 | — | |

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
                    ┌─────────────────────────────┐
   Vue3 + Vite  →   │  Nginx (负载均衡 + 静态资源)  │
   (可选替换)        └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │  Gateway (Spring Cloud)     │  ← Phase 11 可选
                    └──────────────┬──────────────┘
        ┌───────────────┬──────────┼──────────┬───────────────┐
        ▼               ▼          ▼          ▼               ▼
   user-service  product-service order-service seckill-service search-service
   (认证/JWT)     (缓存/ES)      (MQ/分布式事务) (Lua/MQ/限流)   (Elasticsearch)
        │               │          │          │               │
        └───────────────┴──────────┴──────────┴───────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
   MySQL 8 (主从+分片)      Redis (缓存/锁/限流)         RocketMQ (异步/延迟)
   ShardingSphere           Redisson                    事务消息/死信
        │                          │                          │
        └──────────────────────────┼──────────────────────────┘
                                   ▼
              Prometheus + Grafana + SkyWalking + ELK (可观测性)
```

**改造阶段划分（单体优先，微服务最后）：**

```
Phase 0  修复 bug + 工程化地基        ← 立刻可做，1-2 天
Phase 1  Spring Boot 3 升级 + Security + JWT + RBAC
Phase 2  领域建模重构（SPU/SKU/库存/订单/优惠券/地址）
Phase 3  Redis 缓存体系（多级缓存 + 三兄弟 + 一致性）
Phase 4  高并发秒杀（Lua 预扣减 + 防超卖 + 限流 + 压测）
Phase 5  消息队列（订单超时 / 异步解耦 / 幂等）
Phase 6  分布式（Redisson 锁 / 雪花 ID / Seata 事务）
Phase 7  Elasticsearch 搜索 + 原算法转个性化推荐
Phase 8  IM 私聊 + 好友（补完 chat_records / lxy_mate）
Phase 9  可观测性（Actuator / Prometheus / SkyWalking / ELK）
Phase 10 部署（Docker / Nginx / 压测报告 / 简历数据）
Phase 11 微服务拆分（可选，卷王阶段）
```

---

## 三、面试考点 → 项目落点 映射表（本规划的核心）

> 这是整个改造的验收标准：**任何一行都必须在项目里能指出具体文件、具体设计、具体数字。**
> 答不出数字的项，说明该 Phase 没做完。

| # | 面试高频考点 | 项目中的落点 | 面试时能讲什么 |
|---|---|---|---|
| 1 | Java 并发 / JUC | Phase 4 秒杀库存扣减、Phase 9 线程池聚合订单详情 | 线程池 7 参数怎么定、拒绝策略选型、`synchronized` vs `ReentrantLock`、AQS 原理、CAS 与 ABA、`ConcurrentHashMap` 1.7/1.8 差异、`ThreadLocal` 内存泄漏 |
| 2 | JVM 调优 | Phase 9 压测时用 Arthas/jstat/jmap 定位 GC 与 OOM | 堆内存分区、GC 日志分析、CMS vs G1 vs ZGC、内存泄漏定位流程、`-XX` 参数 |
| 3 | MySQL 索引与优化 | Phase 2 建联合索引、Phase 9 慢查询日志 + EXPLAIN | B+ 树为什么适合磁盘、最左前缀、回表、覆盖索引、索引下推、`EXPLAIN` 各列含义 |
| 4 | MySQL 事务与锁 | Phase 4 扣库存（悲观锁 vs 乐观锁对比实验） | 四种隔离级别、MVCC + undo log + ReadView、间隙锁/临键锁、死锁排查（`show engine innodb status`） |
| 5 | 分库分表 | Phase 6 订单表按 `user_id` 分片（ShardingSphere） | 分片键选择、跨片查询与聚合、全局 ID、扩容方案、分页深翻页问题 |
| 6 | Redis 缓存 | Phase 3 商品详情多级缓存（Caffeine + Redis） | 5 种数据结构与场景、RDB/AOF 取舍、过期与淘汰策略、BigKey/热 Key 治理 |
| 7 | 缓存三大问题 | Phase 3 缓存穿透/击穿/雪崩 三套独立方案 | 空值缓存 vs 布隆过滤器、逻辑过期 vs 互斥锁、过期时间打散 |
| 8 | 缓存一致性 | Phase 3 商品更新 → 延迟双删 / Canal 订阅 binlog | 先删缓存还是先更新库、为什么延迟双删、最终一致 vs 强一致 |
| 9 | 分布式锁 | Phase 4 秒杀用 Redisson 锁 + 看门狗续期 | `SETNX` 手写锁的 4 个坑、锁续期、可重入、RedLock 争议 |
| 10 | 秒杀 / 高并发 | Phase 4 完整秒杀链路 | 活动预热、Redis + Lua 原子扣减、MQ 异步下单、防超卖多层保障、防刷（限流+风控）、削峰填谷 |
| 11 | 消息队列 | Phase 5 订单超时取消（延迟消息）+ 扣库存异步化 + IM 消息投递 | 顺序消息、幂等消费、重复消费、消息堆积处理、事务消息、死信队列、`ack` 机制 |
| 12 | 分布式事务 | Phase 6 下单跨服务（订单 + 库存 + 优惠券） | 2PC/TCC/Saga/本地消息表 各自代价、Seata AT 模式原理、为什么不用 XA |
| 13 | 分布式 ID | Phase 6 订单号生成 | 雪花算法位分配、时钟回拨处理、为什么不用 UUID、Leaf/UidGenerator |
| 14 | 认证授权 | Phase 1 Spring Security + JWT + RBAC | 无状态认证、Token 刷新与黑名单、越权（水平/垂直）防护、CSRF、密码加盐 BCrypt |
| 15 | Spring 原理 | Phase 0 统一响应/AOP 注解；Phase 4 自定义 `@RateLimit` 注解 | IoC 容器、三级缓存解决循环依赖、AOP 动态代理（JDK vs CGLIB）、**事务失效的 8 种场景** |
| 16 | Spring Boot 自动配置 | Phase 1 自定义 `oss-spring-boot-starter` | `@Conditional` 家族、SPI 机制、`spring.factories` / `AutoConfiguration.imports`、启动流程 |
| 17 | MyBatis 原理 | Phase 2 换 MyBatis-Plus + 自定义分页/乐观锁插件 | `#{}` vs `${}`（SQL 注入）、一级/二级缓存、插件 `Interceptor` 原理、延迟加载 |
| 18 | 接口安全与幂等 | Phase 4 幂等 Token + 防重放；Phase 0 参数校验 | 幂等 6 种实现、防重放（nonce + 时间戳）、限流算法（令牌桶/漏桶/滑动窗口） |
| 19 | 微服务 | Phase 11 拆 user/product/order/seckill/search | Nacos 注册与配置、Gateway 断言与过滤器、OpenFeign 与负载均衡、Sentinel 熔断降级、分布式链路追踪 |
| 20 | 容器化与运维 | Phase 10 Dockerfile + docker-compose + Nginx | 镜像分层与瘦身、多阶段构建、健康检查、优雅停机、滚动发布 |
| 21 | 测试与压测 | Phase 0 JUnit5 起步 → Phase 4 JMeter 压测 | 单测/mock、Testcontainers、**QPS/TP99/错误率 具体数字** |
| 22 | 搜索引擎 | Phase 7 Elasticsearch 替换 `LIKE '%x%'` | 倒排索引、分词器、相关性打分、`from+size` 深分页问题、`search_after` |
| 23 | 设计模式 | 全项目：策略（支付/优惠）、模板方法（下单）、工厂、责任链（风控）、观察者（MQ）、单例（配置） | 每个模式指向一个真实类 |
| 24 | 网络与 OS | Node/Nginx 部署、TCP 参数 | HTTP/1.1 vs 2、三次握手四次挥手、TIME_WAIT、零拷贝（MQ 用 sendfile） |
| 25 | 项目难点与量化 | Phase 10 汇总 | 「QPS 从 X 提升到 Y」「P99 从 A 降到 B」「缓存命中率 C%」 |

---

## 四、分期路线图

### 🎯 优先级分档（按投入产出比）

| 档位 | 包含 Phase | 工期估算 | 覆盖面试考点 |
|---|---|---|---|
| **简历 MVP** | 0 → 1 → 3 → 4 → 5 | 3-4 周 | 1,3,6,7,8,9,10,11,14,15,18,21（**覆盖 70% 高频题**） |
| **进阶** | + 2 → 6 → 7 → 9 | 再 4-6 周 | +2,4,5,12,13,16,17,22,23 |
| **卷王** | + 8 → 10 → 11 | 再 4 周 | +19,20,24,25 全量 |

> 建议：**先按「简历 MVP」做完并压测出数字**，再决定是否继续。
> 一个跑得起来、有 QPS 数据的 MVP，比一个半成品的"微服务大杂烩"面试效果好得多。

---

## Phase 0 — 修复 bug + 工程化地基（立即执行）

**目标：** 项目能干净跑起来 + 有版本控制 + 有建表脚本 + 有最小工程规范。
**工期：** 1-2 天。**产出：** 能写进简历的第一条 ——「接手遗留项目，完成核心链路缺陷修复与工程化改造」。

### Task 0.1 建立 Git 仓库与基线

**Files:** 创建 `.gitignore`、`README.md`

```bash
# 1. 先建 .gitignore（排除 target / .idea / 上传的图片产物）
# 2. 提交现有代码作为"重构前基线"
git init
git add -A
git commit -m "chore: 重构前基线（原始课程项目）"
git tag baseline
```

> **为什么重要：** 现在没有 `.git`，任何改动都可能不可逆。面试问「你怎么保证重构不把项目改坏」时，答「先打 baseline tag，每个 Task 一次 commit，随时可回滚」。

**`.gitignore` 内容：**

```gitignore
target/
out/
.idea/
*.iml
*.log
.hermes/
# 商品图体积大（84MB），MVP 阶段先保留，Phase 10 再决定是否外置
# src/main/resources/static/shop/assets/product-img/
```

**注意：** 3665 张图会让仓库变大。两个选择：(a) 直接提交，简单；(b) 图片移到外部目录 + Nginx 托管（Phase 10 做）。MVP 阶段选 (a)。

### Task 0.2 导出数据库建表脚本

**Files:** 创建 `docs/schema.sql`

```bash
mysqldump -uroot -proot --no-data --skip-comments shop > docs/schema.sql
```

然后手工修正 `docs/schema.sql` 中的缺陷（**不要用 mysqldump 覆盖生产库**）：
- `lxy_order.created_at` 由 `varchar(200)` 改为 `datetime`
- 统一字符集为 `utf8mb4_unicode_ci`
- 补二级索引（Phase 2 细化，此处先加最基础的）

**验证：** `mysql -uroot -proot < docs/schema.sql` 在空库上能建出结构。

### Task 0.3 修复「必崩」缺陷（10 个）

> 逐个修，每修 1-2 个 commit 一次。所有定位已实测确认。

---

#### Bug 1 🔴 后台「编辑用户」必崩 — MyBatis 参数名不匹配

**Files:** `src/main/resources/mapper/UserMapper.xml:5`、`src/main/java/shop/admin/mapper/UserMapper.java:21`

**现状：**
```xml
<!-- XML 引用了 name / age，但接口根本没有这两个参数，且 DB 里也没有这两列 -->
<update id="updateUserByUserName">
    update lxy_user set name=#{name}, age=#{age}, email=#{email}, phone=#{phone} where username=#{username}
</update>
```
```java
void updateUserByUserName(@Param("username") String username, @Param("phone") String phone, @Param("email") String email);
```

**报错：** `BindingException: Parameter 'name' not found. Available parameters are [username, phone, email, param1, param2, param3]`

**修复：** XML 改为
```xml
<update id="updateUserByUserName">
    update lxy_user set phone=#{phone}, email=#{email} where username=#{username}
</update>
```

---

#### Bug 2 🔴 后台改用户失败时二次崩溃 — ClassCastException

**Files:** `src/main/java/shop/admin/controller/UserController.java:68, 99`

**现状：**
```java
// :68 存进去的是 int（自动装箱成 Integer）
session.setAttribute("userId", id);
// :99 catch 里强转成 String → ClassCastException
String id = (String) session.getAttribute("userId");
return "redirect:/admin/user_edit/" + id;
```

**修复：**
```java
// :99 附近，去掉强转，用 Object
Object uid = session.getAttribute("userId");
return "redirect:/admin/user_edit/" + (uid == null ? "" : uid);
```

---

#### Bug 3 🔴 缺失模板 — 点「添加商品」直接 500

**Files:** `src/main/java/shop/admin/controller/ProductController.java:37` 返回 `/admin/product_add`，
但 `src/main/resources/templates/admin/` 下**没有 `product_add.html`**。

**修复（二选一）：**
- (a) 新建 `templates/admin/product_add.html`（可复制 `user_add.html` 改造，字段：name / price / description / 图片）
- (b) 若后台不打算支持新增商品，删掉 `product_add()` 方法

**建议选 (a)** —— 后台商品管理是电商项目的标配功能。

---

#### Bug 4 🔴 改商品信息页 404 — redirect 缺路径变量

**Files:** `src/main/java/shop/shop/controller/ShopSaleController.java:66-90`

**现状：** `changeProductInformation(@PathVariable int id, ...)` 里有一段从 `Sale()` 复制来的死代码：
```java
// :73-77 这段判断是复制 /shop/sale 的逻辑，changeProduct 根本不会设置 saleError，永远不会进来
if(session.getAttribute("saleError")=="商品价格必须大于0"){
    m.addAttribute("saleError","商品价格必须大于0");
    session.removeAttribute("saleError");
    return "redirect:/shop/changeProductInformation";   // ← 少了 {id}，映射不存在 → 404
}
```

**修复：** 直接删除 :73-77 这段死代码（`saleError` 只在 `uploadProduct` 设置，属于 `/shop/sale` 流程）。

---

#### Bug 5 🔴 删除购物车商品后页面空/异常

**Files:** `src/main/java/shop/shop/controller/ShopCartController.java:52`

**现状：** `return "/shop/cart";`（转发）但没往 Model 放 `cartProduct` → 模板拿不到数据。

**修复：**
```java
return "redirect:/shop/cart";
```
> 顺带修掉同方法 `:46` 的 `if (num == productId)` —— `num` 是 `Integer`、`productId` 是 `int`，虽然靠拆箱能跑，但要写成 `num.equals(productId)` 或 `num.intValue() == productId` 才严谨。面试可能追问「Integer 缓存 -128~127 的坑」。

---

#### Bug 6 🔴 上传商品必崩 — 路径指向不存在的 E 盘

**Files:** `src/main/resources/application.properties:12`

**现状：** `upload.path=E:/java/springboot3/second_hand_shop/src/main/resources/static/shop/assets/product-img/`
实测该目录不存在 → `ProductPictureProcess.saveFile` 抛 `RuntimeException("保存文件时发生错误")`。

**修复（近期）：**
```properties
upload.path=./src/main/resources/static/shop/assets/product-img/
```
**修复（Phase 1 正式方案）：** 改为外部目录 + 资源映射，避免运行时写 `src/`：
```properties
upload.path=${user.home}/second-hand-shop/upload/
```
配合 `WebMvcConfig` 注册 `/shop/assets/product-img/**` → `file:${upload.path}`。

---

#### Bug 7 🔴 首页商品不足 100 条时数组越界

**Files:** `src/main/java/shop/shop/controller/ShopIndexController.java:40`

**现状：**
```java
// 未登录分支：写死 100，商品不足 100 条直接 IndexOutOfBounds
m.addAttribute("products", productList.subList(0,100));
// 已登录分支（:49）反而写对了
m.addAttribute("products", productList.subList(0,Math.min(productList.size(),100)));
```

**修复：** 统一为
```java
m.addAttribute("products", productList.subList(0, Math.min(productList.size(), 100)));
```

---

#### Bug 8 🔴 改管理员密码时 NPE

**Files:** `src/main/java/shop/admin/controller/AdminController.java:29-30`

**现状：** `Admin admin = adminMapper.getAdmin(adminuser);` 后直接 `admin.getAdminpass()`，用户不存在则为 null → NPE。

**修复：**
```java
Admin admin = adminMapper.getAdmin(adminuser);
if (admin == null) {
    session.setAttribute("loginError", "管理员不存在");
    return "redirect:/admin/admin_edit";
}
```

---

#### Bug 9 🔴 登录校验不统一，多处 NPE

**Files:**
- `src/main/java/shop/shop/controller/ShopBuyController.java:34`（`buy`）、`:44`（`buySuccess` POST）
- `src/main/java/shop/shop/controller/ShopPersonalController.java:43`（`city`）

**现状：** 直接 `session.getAttribute("shopusername").equals(...)`，未登录时属性为 null → NPE。

**修复：** 方法开头统一加
```java
if (SessionCheck.checkSessionName(session)) {
    return false;   // @ResponseBody 方法返回 false
    // 或 return "redirect:/shop/login";  （页面方法）
}
```

---

#### Bug 10 🔴 搜索补位死循环 / `nextInt(0)` 异常

**Files:** `src/main/java/shop/shop/tools/SearchProcess.java:130-140`

**现状：**
```java
List<Product> productAllList = productMapper.getProducts(username);
Random random = new Random();
while (number-nowValue > 0) {                       // ← 无界循环
    int index = random.nextInt(productAllList.size());  // ← 空列表直接抛异常
    Product product = productAllList.get(index);
    if (!productList.contains(product)) {            // ← 若候选全在列表里，永远不自增 → 死循环
        productList.add(product);
        number -= 1;
    }
}
```

**修复：**
```java
List<Product> productAllList = productMapper.getProducts(username);
UserProcess.removeCartElementFromProducts(productAllList, cartList);
// 打乱后顺序补齐，避免 while + random 的不收敛问题
Collections.shuffle(productAllList);
for (Product p : productAllList) {
    if (number - nowValue <= 0) break;
    if (!productList.contains(p)) {
        productList.add(p);
        nowValue += 1;
    }
}
```

---

### Task 0.4 修复「非致命但会被追问」的缺陷

#### Fix A: 字符串用 `!=` 比较（永远为 true）

**Files:** `src/main/java/shop/shop/tools/SessionCheck.java:19, 28`

```java
// 现状：和字面量 "null" 比较引用，永远 true → else 分支是死代码
if (session.getAttribute("school") != "null") { ... } else { ... }
```
**修复：** 改为 `if (session.getAttribute("school") != null) { ... }`

> **面试价值：** 这是「Java 中 `==` 与 `equals` 的区别」的完美真实案例，可以主动讲。

#### Fix B: 定时任务在循环内更新数据库

**Files:** `src/main/java/shop/shop/tools/DailyUpdateTask.java:36-54`

**问题：**
1. `searchMapper.updateSearchByUserName` 写在 `while (iterator.hasNext())` **循环体内** → 每个搜索词更新全字段一次（N 次无效写库）
2. `iter` 中被 `remove()` 后仍继续用 `dictToString(SearchDict)` → 语义混乱
3. 所有条目都被 remove 时，`newSearch` 永远不会写回

**修复：** 把 `dictToString` + `updateSearchByUserName` 移到 while **循环之后**，只执行一次。

#### Fix C: 删除商品图片用了 URL 相对路径

**Files:** `src/main/java/shop/shop/controller/ShopSaleController.java:107-109`

```java
String imgPath = product.getImgPath();   // "/shop/assets/product-img/123.png" —— 这是 URL，不是文件路径
File file = new File(imgPath);           // 相对 CWD，删不到真实文件
file.delete();
```
**修复：** 从 imgPath 提取文件名，拼上 `uploadPath`：
```java
String fileName = imgPath.substring(imgPath.lastIndexOf('/') + 1);
File file = new File(uploadPath + fileName);
if (file.exists()) file.delete();
```

#### Fix D: `changeProduct` 改名/改图后 URL 不更新

**Files:** `src/main/java/shop/shop/controller/ShopSaleController.java:113-115`

```java
String filePath = uploadPath + id + ".png";       // 存成 id.png
ProductPictureProcess.saveFile(username,image,filePath);
productMapper.updateProduct(name,description,price,imgPath);   // ← 用的还是旧 imgPath
```
**修复：** 统一使用同一个变量，确保文件名与库中 `img_store_path` 一致。

### Task 0.5 工程化地基（最小集）

| # | 事项 | 文件 | 说明 |
|---|---|---|---|
| 1 | 统一响应体 `Result<T>` | 新建 `common/Result.java` | 面试必问「接口返回格式统一吗」 |
| 2 | 全局异常处理 | 新建 `common/GlobalExceptionHandler.java` | `@RestControllerAdvice` + 自定义 `BizException` + 错误码枚举 |
| 3 | 参数校验 | 引入 `spring-boot-starter-validation`，DTO 加 `@NotBlank/@Min` | JSR-303，替代手写 if |
| 4 | 日志规范 | 全项目 18 处 `System.out.println` → `@Slf4j` | 面试可能追问日志级别与异步日志 |
| 5 | 接口文档 | 引入 Knife4j / springdoc-openapi | 简历写「提供 40+ 接口的在线文档」 |
| 6 | 配置外置 | DB 账号密码移到 `application-local.properties` 并 gitignore | 避免明文入库 |
| 7 | 单元测试 | 新建 `src/test/java`，为 Phase 0 修完的工具类写测试 | 项目现在 0 测试，这是硬伤 |

**Phase 0 验收清单：**
- [ ] `git log` 有 baseline tag + 修复过程 commit
- [ ] `docs/schema.sql` 能在空库执行成功
- [ ] 10 个必崩 bug 全部修复，项目能启动并走通「注册→登录→发布商品→加购物车→下单→后台管理」全链路
- [ ] `mvn test` 能跑（至少 5 个用例通过）
- [ ] 全项目 `grep System.out.println` 返回 0

---

## Phase 1 — Spring Boot 3 升级 + Security + JWT + RBAC

**目标：** 技术栈对齐 2026 年主流，认证授权从「session + 无盐 MD5」升级为工业级方案。

### 为什么必须升级（面试话术）

> 「原项目用的是 Spring Boot 2.4.3（2021 年版本，2021-08 已停止开源维护），`javax.servlet` 命名空间。
> 我把它升级到 Spring Boot 3.2.x + JDK 17，`javax.*` → `jakarta.*`，
> 顺带接入了 Spring Security 6 —— 因为 Spring Cloud 2023 和 Spring Security 6 都要求 Boot 3。」

### 主要工作

| 项 | 现状 | 目标 |
|---|---|---|
| Spring Boot | 2.4.3 | 3.2.x |
| JDK | target 17（但 Boot 2.4 官方只支持到 15/16，**编译能过、运行不保证**） | 17（LTS） |
| 命名空间 | `javax.servlet.*`（12 个文件） | `jakarta.servlet.*` |
| Lombok | 1.18.30 | 1.18.30+（Boot 3 需新版兼容 JDK 17） |
| MyBatis starter | 2.2.2 | 3.0.3+ |
| 密码 | 无盐 MD5 | BCrypt（`PasswordEncoder`） |
| 认证 | session + `"请登录"` 字符串哨兵 | Spring Security 6 + JWT（无状态） |
| 授权 | 无（谁都能调 `/admin/**`... 事实上后台有 session 判断但不严谨） | RBAC：`ROLE_USER` / `ROLE_ADMIN` + `@PreAuthorize` |
| 未使用依赖 | redis / websocket / druid（零引用） | Phase 3/8 真正接入，或先移除 |

### 任务拆分

- **1.1** 升级 `pom.xml`：parent → 3.2.x，引入 `spring-boot-starter-security`、`jjwt`
- **1.2** 全局替换 `javax.servlet` → `jakarta.servlet`（12 个文件，`Application` 与全部 Controller / tools）
- **1.3** 配置 `SecurityConfig`：`SecurityFilterChain`、`BCryptPasswordEncoder`、放行静态资源与登录注册
- **1.4** 实现 `JwtAuthenticationFilter` + `JwtUtil`（签发/校验/刷新）
- **1.5** 用户表加 `role` / `status` 字段，实现 RBAC 与 `@PreAuthorize("hasRole('ADMIN')")`
- **1.6** 重写 `ShopLoginController` / `LoginController`（admin）→ 返回 Token；前端 ajax 统一带 `Authorization` 头
- **1.7** 存量密码迁移：把 30 个用户的 MD5 密码平滑升级为 BCrypt（登录时校验旧格式则重写）
- **1.8** 自定义 starter `oss-spring-boot-starter`（封装文件上传到本地/OSS/MinIO，用 `@ConditionalOnProperty` 切换）← **考点 16**

**验收：** 未带 Token 访问 `/shop/cart` 返回 401/403；带 USER Token 访问 `/admin/user` 返回 403；BCrypt 密码在库里是 `$2a$...`；`@PreAuthorize` 生效。

---

## Phase 2 — 领域建模重构

**目标：** 从「字符串拼接式伪建模」升级为标准电商领域模型。这是**后面所有高并发/分库分表的前提**。

### 现状痛点（面试可直接当"重构理由"讲）

| 现状 | 问题 |
|---|---|
| 购物车 = `lxy_cart.products` 逗号字符串 | 无法 JOIN、无数量字段、并发写丢失更新 |
| 无库存字段 | 无法讨论超卖 |
| 无 SKU/规格 | 不能讲 SPU-SKU 模型 |
| 无地址表（地址塞在 user 的 province/city/area） | 一个用户只能一个地址 |
| 无优惠券/促销 | 无法讲营销与分布式事务 |
| 无评价 | 电商闭环不完整 |
| `lxy_order` 无状态机、`created_at` 是 varchar | 无法讲订单流转与索引优化 |
| 搜索历史序列化进 `lxy_user.search` | 无法查询/索引 |

### 目标表结构（新增/重构）

```
user            重构：+role +status +password_hash(BCrypt) 独立表
address         【新】收货地址（一对多）
category        【新】商品分类（树形，parent_id）
product         重构：+category_id +stock +status +version(乐观锁) +detail
product_sku     【新】规格与独立库存
cart_item       重构：cart → cart_item(id,user_id,sku_id,quantity,picked)  ← 干掉逗号字符串
order           重构：+order_no +status(状态机) +total_amount +pay_amount +pay_time
order_item      【新】订单明细
payment         【新】支付流水（对接支付宝沙箱或模拟支付）
coupon          【新】优惠券模板
user_coupon     【新】用户券
seckill_activity【新】秒杀活动   ← Phase 4 用
seckill_order   【新】秒杀订单    ← Phase 4 防重
review          【新】商品评价
search_history  【新】用户搜索历史（结构化，替换 lxy_user.search）
```

### 任务拆分

- **2.1** 引入 `mybatis-plus-boot-starter`，实体改为 `@TableName`/`@TableId`
- **2.2** 编写 `docs/schema_v2.sql`（新表 + 迁移脚本）
- **2.3** 数据迁移脚本：`lxy_cart.products` 字符串 → `cart_item` 行；`lxy_user.search` → `search_history` 行
- **2.4** 购物车模块改造（含数量、勾选、并发安全）
- **2.5** 库存与乐观锁（`version` 字段 + `update ... where stock >= n`）
- **2.6** 订单状态机（待支付→已支付→待发货→已发货→已完成→已取消），用枚举 + 状态流转校验
- **2.7** 索引优化专项：给 `product(category_id,status,created_at)`、`order(user_id,status)`、`order_item(order_id)` 建索引，用 `EXPLAIN` 前后对比写进 README ← **考点 3**
- **2.8** 保留旧的 `lxy_*` 表作为兼容层，直至全链路切换完成（灰度思路）

**验收：** 全链路使用新模型；README 里有「加索引前 Xms → 加索引后 Yms」的 EXPLAIN 对比。

---

## Phase 3 — Redis 缓存体系

**目标：** 商品读取不再直压 MySQL，且能讲清缓存三大问题与一致性。

### 任务拆分

- **3.1** 引入 `spring-boot-starter-data-redis`（依赖已在，但零引用 → 现在真正接入）；配置 `RedisTemplate` 序列化（String + Jackson，避免默认 JDK 序列化的可读性问题）
- **3.2** 商品详情缓存（Cache-Aside）：`product:detail:{id}`
- **3.3** 多级缓存：Caffeine（本地，防热 Key）+ Redis（分布式）
- **3.4** 缓存穿透方案：布隆过滤器（Redisson `RBloomFilter`）+ 空值缓存
- **3.5** 缓存击穿方案：互斥锁重建 / 逻辑过期（两种都实现，README 对比取舍）
- **3.6** 缓存雪崩方案：过期时间随机打散 + 多级缓存兜底
- **3.7** 缓存一致性：更新商品时「先更新库、再延迟双删」，并用 Canal 订阅 binlog 做最终一致（进阶）
- **3.8** 热 Key / BigKey 治理：商品列表、秒杀商品预热
- **3.9** 缓存命中率监控指标暴露给 Phase 9

**验收：** JMeter 压测商品详情接口，有缓存 QPS vs 无缓存 QPS 的数字对比；`INFO stats` 能看到 `keyspace_hits/misses`。

---

## Phase 4 — 高并发秒杀（本项目最核心的简历亮点）

**目标：** 一个能扛住压测、不超卖、可讲 15 分钟的秒杀模块。

### 核心链路设计

```
① 活动预热：秒杀商品库存 → Redis (activity:stock:{id})
② 用户请求 → Nginx → Gateway → seckill-service
③ 限流：Sentinel / Guava RateLimiter / 自研 @RateLimit 注解（Redis 滑动窗口）
④ 风控：一用户一单（Redis Set 判重）+ 秒杀 URL 隐藏 + 验证码
⑤ Redis + Lua 原子校验与预扣减（库存 + 判重 在一个 Lua 脚本内原子完成）
⑥ 扣减成功 → 发 MQ 消息（削峰）→ 立即返回「排队中」
⑦ 消费者异步创建订单：DB 乐观锁扣库存 → 写 order → 更新秒杀结果
⑧ 客户端轮询/WebSocket 推送秒杀结果
⑨ 未支付超时 → 延迟消息回补库存（Phase 5）
```

### 任务拆分

- **4.1** 秒杀活动与商品数据准备（`seckill_activity` / `seckill_order`）
- **4.2** 活动预热任务（启动时 / 定时把库存灌进 Redis）
- **4.3** 编写 Lua 脚本：库存 `decr` + 用户判重 + 原子返回
- **4.4** 自研 `@RateLimit` 注解 + AOP + Redis Lua 滑动窗口限流 ← **考点 15 + 18**
- **4.5** Redisson 分布式锁处理「同一用户并发请求」← **考点 9**
- **4.6** MQ 异步下单（Phase 5 对接）
- **4.7** 多层防超卖：Redis 预扣减 + DB `stock >= n` + 唯一索引 `(user_id, activity_id)` 兜底
- **4.8** 悲观锁 vs 乐观锁对比实验（两种实现都写，记录 TPS 差异写进 README）← **考点 4**
- **4.9** JMeter 压测脚本 + 报告（并发 1000/5000，记录 QPS / TP99 / 错误率 / 是否超卖）← **考点 21**
- **4.10** 压测期间的 JVM 观测（Arthas / jstat / GC 日志）← **考点 2**

**验收：** 压测 5000 并发，库存 100，最终下单数 = 100 且零超卖；README 有完整压测报告表。

---

## Phase 5 — 消息队列

**目标：** 引入 MQ 解决「削峰 / 异步 / 解耦 / 延迟」四类问题，且答得出幂等与堆积。

**选型建议：** RocketMQ（国内面试最常问，支持事务消息 + 延迟消息 + 顺序消息）。
若考虑学习成本，RabbitMQ 也可（延迟消息靠插件）。**不推荐 Kafka**（它不是为这类业务消息设计的，面试官问「为什么不用 Kafka」要有答案）。

### 任务拆分

- **5.1** Docker 起 RocketMQ（NameServer + Broker）
- **5.2** 秒杀下单异步化（Phase 4.6 对接）
- **5.3** 订单超时自动取消（延迟消息 / 定时扫表 + 分片）← 经典面试题
- **5.4** 消费幂等：`message_id` + Redis Set / 数据库唯一索引
- **5.5** 重复消费与消息堆积处理（消费位点、批量消费、扩容消费者、死信队列）
- **5.6** 事务消息：下单扣本地库存 + 发消息的最终一致
- **5.7** IM 消息投递（Phase 8 对接）
- **5.8** MQ 监控（RocketMQ Console）

**验收：** 能演示「订单 30 分钟未支付自动取消并回补库存」；故意重复投递消息，订单不会重复创建。

---

## Phase 6 — 分布式能力

**目标：** 补齐分布式 ID、分布式锁、分布式事务、分库分表。

- **6.1** 分布式 ID：雪花算法生成订单号（自研 `SnowflakeIdWorker`，处理时钟回拨）；对比 UUID / 数据库自增 / Redis INCR ← **考点 13**
- **6.2** Redisson 分布式锁完善（看门狗续期、可重入、`tryLock` 参数）← **考点 9**
- **6.3** 分布式事务：下单跨服务（订单 + 库存 + 优惠券 + 积分）← **考点 12**
  - 实现两条路线对比：**本地消息表（最终一致）** vs **Seata AT 模式**
  - README 写清各自的性能代价与适用场景
- **6.4** ShardingSphere 分库分表：`order` 表按 `user_id` 取模分 4 表 ← **考点 5**
  - 处理跨片查询、跨片分页、全局 ID 一致性
- **6.5** MySQL 主从复制 + 读写分离（Docker 起从库，ShardingSphere / 动态数据源路由）

**验收：** 订单能按分片键正确落到不同表；分布式事务能演示「库存不足时订单回滚」。

---

## Phase 7 — Elasticsearch 搜索 + 个性化推荐

**目标：** 替换 `LIKE '%keyword%'` 全表扫描，并把原项目的自研权重算法升级为「个性化推荐」。

**现状痛点（可直接讲）：**
> 原项目搜索是 `where p.name LIKE '%query%'` —— 左模糊无法走索引，3659 行就开始慢；
> 而且原项目自己写了一套「搜索词权重衰减算法」（`SearchProcess` + `MathTools.derivative` 用导数做衰减），
> 思路新颖但存在死循环缺陷、且用 text 字段序列化存储无法查询。

### 任务拆分

- **7.1** Docker 起 Elasticsearch + Kibana；引入 `spring-boot-starter-data-elasticsearch`
- **7.2** 商品索引设计（IK 分词、`name`/`description`/`category` 多字段、`keyword` 子字段做聚合）
- **7.3** 数据同步：全量（启动/手动）+ 增量（Canal 订阅 binlog 或 MQ 双写）
- **7.4** 搜索接口实现：关键词、分类筛选、价格区间、排序（相关性/价格/销量/时间）、高亮
- **7.5** 深分页优化：`from+size` → `search_after` / `scroll` ← **考点 22**
- **7.6** 拼写纠错与同义词（可选加分）
- **7.7** **把原自研权重算法迁移为推荐模块**：结构化落库 + 定时衰减任务（Redis ZSet），用于首页个性化商品排序 ← 保留项目原创性，面试可讲「这是我自己设计的算法」

**验收：** 搜索响应 < 50ms；能演示关键词高亮与多条件筛选。

---

## Phase 8 — IM 私聊 + 好友（补完原项目未完成功能）

**目标：** 把数据库里躺着的 `chat_records` / `lxy_mate` 表和 pom 里没用的 `spring-boot-starter-websocket` 真正用起来。

> **面试价值极高：** 「我发现项目里有两张表和一个 WebSocket 依赖没有任何代码引用，说明原设计有社交功能但没实现，
> 我把它补完了」—— 这句话同时展示了**代码洞察力**和**主动性**。

- **8.1** 好友关系（`lxy_mate` → `user_friend`，双向关系、申请/同意/删除）
- **8.2** WebSocket 私聊（`@ServerEndpoint` 或 Spring WebSocket + STOMP）
- **8.3** 在线状态（Redis Set/Hash + 心跳）
- **8.4** 离线消息（MQ 投递失败 → 落库 → 上线拉取）
- **8.5** 消息已读/未读、历史消息分页
- **8.6** 消息顺序性保证（单会话顺序消费）← **考点 11**

---

## Phase 9 — 可观测性

**目标：** 让前面所有 Phase 的「效果」变得可量化、可演示。

- **9.1** Spring Boot Actuator 暴露健康/指标端点
- **9.2** Micrometer + Prometheus + Grafana 仪表盘（QPS、RT、缓存命中率、线程池、JVM、连接池）
- **9.3** 自定义业务指标（秒杀成功率、订单转化率）
- **9.4** SkyWalking 链路追踪（跨服务 TraceId 透传）← **考点 19**
- **9.5** Logback + MDC 全链路日志 TraceId；ELK 收集（可选）
- **9.6** 慢 SQL 监控（Druid 监控页 —— 依赖已引入，正好用起来）
- **9.7** JVM 调优实验：调 `-Xmx` 与 GC，用 GC 日志对比吞吐 ← **考点 2**

---

## Phase 10 — 部署与压测交付

- **10.1** `Dockerfile`（多阶段构建，镜像瘦身；非 root 用户；健康检查）
- **10.2** `docker-compose.yml` 一键起 MySQL / Redis / RocketMQ / ES / Nacos / 应用
- **10.3** Nginx 反向代理 + 静态资源 + 负载均衡（起 2 个应用实例）
- **10.4** 前端（二选一）：
  - (a) 保留 Thymeleaf，仅做接口改造 —— 省时间
  - (b) 重写为 Vue3 + Vite 前后端分离 —— 需升级 Node 到 18+
- **10.5** JMeter 完整压测报告 + 性能优化前后对比表
- **10.6** README 架构图 + 技术选型理由 + QPS 数据 + 遇到的坑与解决方案
- **10.7** **整理「项目难点 5 问 5 答」文档**（面试前复习用）

---

## Phase 11 — 微服务拆分（可选，卷王阶段）

> **提醒：** 微服务不是越多越好。面试官反感「为了微服务而微服务」。
> 如果业务量支撑不起拆分，**单体 + 中间件**反而是更好的答案。
> 拆之前先准备一句话回答：「为什么拆 / 拆的边界怎么定 / 拆完带来什么新问题」。

- **11.1** 引入 Nacos（注册中心 + 配置中心）
- **11.2** Spring Cloud Gateway（路由、断言、全局过滤器做鉴权）
- **11.3** 服务拆分：user / product / order / seckill / search / im
- **11.4** OpenFeign + LoadBalancer 服务调用
- **11.5** Sentinel 熔断降级限流 ← **考点 19**
- **11.6** Seata 接入（Phase 6.3 的服务化版本）
- **11.7** 全链路灰度（可选加分）

---

## 五、风险与开放问题（需你决策）

### 🔴 需要你拍板的 3 个问题

| # | 问题 | 选项 | 我的建议 |
|---|---|---|---|
| 1 | **是否升级 Spring Boot 3？** | (a) 升级到 3.2.x + JDK17 <br> (b) 留在 2.4.3 | **选 (a)**。2026 年面试 Spring Boot 3 是标配；不升的话 Spring Cloud / Sentinel / Seata 新版都用不了。代价是 12 个文件的 `javax`→`jakarta` import 改 + Lombok/MyBatis 版本适配，约 1-2 天。 |
| 2 | **前端怎么办？** | (a) 保留 Thymeleaf <br> (b) 重写 Vue3 + Vite | **选 (a)** 直到 Phase 10。后端才是面试重点，前端重写收益低。若你想投前端/全栈岗再选 (b)，但要先升级 Node 18+。 |
| 3 | **是否拆微服务？** | (a) 单体 + 中间件 <br> (b) 拆 Spring Cloud | **选 (a)** 作为主力，Phase 11 只作为"我研究过"的加分项。理由见 Phase 11 提醒。 |

### ⚠️ 其他风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 工期远超预期 | 全量 3-6 个月，容易半途而废 | 严格按「简历 MVP」档位做完再继续，每个 Phase 独立可交付 |
| 本机 Maven CLI 缺失 | 只能靠 IntelliJ 构建 | 先装 Maven 或改用 `mvnw` wrapper（Phase 0 可做） |
| JDK 22 vs target 17 | Boot 3.2 官方支持 17/21，不支持 22 | 装 JDK 17 或改 target 21 |
| 3665 张图 + 84MB | 仓库膨胀、Docker 镜像巨大 | Phase 10 图片外置到对象存储/MinIO |
| 数据是 2024 年的 | 秒杀/订单时间对不上 | 写数据生成脚本，批量造测试数据 |
| Redis 已在跑 | 可能是其他项目在用 | 用 `select` 切库（如 db 3）避免 Key 冲突 |
| 学不完 | 技术栈太多，变成"什么都听过什么都没做过" | **每个 Phase 必须压测出数字**，没数字的不算做完 |

---

## 六、立即可执行的第一步

```
Phase 0（1-2 天）：
  0.1 git init + baseline tag                    ← 15 分钟
  0.2 mysqldump 导出 docs/schema.sql              ← 15 分钟
  0.3 修 10 个必崩 bug                            ← 半天
  0.4 修 4 个逻辑缺陷                             ← 1 小时
  0.5 工程化地基（Result / 全局异常 / 日志 / 测试） ← 半天
  ↓
完成标志：项目能干净启动 + 全链路可走通 + git 有历史 + mvn test 通过
```

---

## 七、验收总表（简历可写的话术）

每完成一个 Phase，填这里。**没有数字的条目不要写进简历。**

| Phase | 简历话术模板 | 实测数字（待填） |
|---|---|---|
| 0 | 接手遗留项目，修复 14 处核心缺陷，完成工程化改造（统一响应/全局异常/参数校验/单元测试），建立建表脚本与版本基线 | — |
| 1 | 将 Spring Boot 2.4.3 升级至 3.2，完成 `javax`→`jakarta` 全量迁移；基于 Spring Security 6 + JWT + RBAC 重构认证授权，密码由无盐 MD5 升级为 BCrypt | — |
| 2 | 重构领域模型（SPU/SKU/库存/地址/优惠券），将字符串拼接式购物车改造为关联表；通过索引优化将商品列表查询从 ___ms 降至 ___ms | — |
| 3 | 构建 Caffeine + Redis 多级缓存，实现穿透/击穿/雪崩三套方案，缓存命中率 ___%，商品详情 QPS 从 ___ 提升至 ___ | — |
| 4 | 实现秒杀模块：Redis+Lua 原子扣减 + MQ 异步下单 + 多层防超卖，压测 5000 并发下 QPS ___、TP99 ___ms、零超卖 | — |
| 5 | 引入 RocketMQ 实现秒杀削峰与订单超时取消，消费幂等通过 ___ 保证 | — |
| 6 | 自研雪花算法订单号；基于 Redisson 实现分布式锁；Seata 解决下单跨服务分布式事务；ShardingSphere 完成订单表分片 | — |
| 7 | 基于 Elasticsearch 重构搜索，替换全表模糊查询，搜索响应 ___ms；将自研权重算法迁移为个性化推荐 | — |
| 8 | 补完项目遗留的社交模块，基于 WebSocket + Redis + MQ 实现实时私聊与离线消息 | — |
| 9 | 基于 Prometheus + Grafana + SkyWalking 搭建全链路监控 | — |
| 10 | Docker Compose 一键部署全套中间件，Nginx 负载均衡双实例，产出完整压测报告 | — |

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
| 0.6 | 编译 + 启动 + 全链路端到端验证 | ✅ |

## 修复清单（14 项）

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
