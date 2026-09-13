# 校园二手交易平台

一个面向高校的二手物品交易平台。用户在校园内发布、浏览、搜索闲置物品并完成交易，
平台提供购物车、下单、订单流转、个人中心与后台管理能力。

> **项目定位：** 从课程级 CRUD 项目起步，正在按 [docs/ROADMAP.md](docs/ROADMAP.md)
> 改造为覆盖「高并发 / 缓存 / 消息队列 / 分布式 / 搜索 / 微服务」全链路的生产级电商平台。
> 各阶段进度见 ROADMAP 顶部进度表。
>
> **想快速了解「遇到了什么问题、我做了什么、面试怎么答」**（含 Phase 0–3 的全部实测数字）：
> 直接看 **[docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md)** —— 说明书 + 面试问答二合一，
> 含 30 问总库与简历话术。

---

## 技术栈

| 层次 | 技术 |
|---|---|
| 语言 / 构建 | Java 17（源码级别）、Maven |
| 框架 | Spring Boot 3.3.5、Spring MVC、MyBatis-Plus 3.5.9（Phase 2 起取代原生 MyBatis）|
| 认证授权 | Spring Security 6 + JWT（jjwt 0.12.6，HttpOnly Cookie）、BCrypt、RBAC |
| 视图 | Thymeleaf、原生 JS（jQuery） |
| 数据库 | MySQL 8 |
| 缓存 / 会话存储 | Redis 5 + Redisson 3.52（Phase 3：商品/分类缓存、穿透/击穿/雪崩治理、`RLock` 分布式锁、`RBloomFilter`；db 3 + `shop:` 前缀） |
| 自研组件 | `oss-spring-boot-starter`（自定义 Starter，自动配置文件存储，本地磁盘 ⇄ 对象存储可切换） |
| 持久层能力 | MyBatis-Plus：`BaseMapper` 通用 CRUD、分页插件、乐观锁插件（`@Version`）、公共字段自动填充 |
| 并发控制 | 库存扣减双策略（策略模式，`shop.stock.strategy=optimistic\|pessimistic`）：乐观锁+重试 / 悲观锁 `SELECT ... FOR UPDATE` |
| 连接池 | HikariCP（Spring Boot 默认；原声明的 Druid 全项目零引用，Phase 1 已移除） |
| 日志 | SLF4J + Logback（控制台 + 滚动文件 + 错误单独归档） |
| 校验 | JSR-303 / Hibernate Validator |
| 测试 | JUnit 5、Mockito、AssertJ |

> **依赖声明但尚未接入的组件：** `spring-boot-starter-websocket`。
> 它原本留给「IM 私聊」功能，但 2026-09-13 重规划时**已明确砍掉**（与电商主线无关、性价比低），
> 因此这个依赖属于「引入了但零引用」，可在后续清理时移除 —— **这本身是个可以讲的细节**：
> 接手项目时先做依赖审计，把「声明了但没人用」的依赖揪出来，而不是继续堆。
>
> `spring-boot-starter-data-redis` 已在 Phase 1 收尾真正接入（令牌黑名单 / refresh 存储），不再是空声明。

---

## 快速开始

### 1. 环境要求

- JDK 17+（本项目在 JDK 22 上验证通过）
- Maven 3.6+（或使用 IntelliJ 内置 Maven）
- MySQL 8.0+

### 2. 初始化数据库

```bash
mysql -uroot -p < docs/schema.sql
```

脚本会创建 `shop` 库及 8 张表，并附带索引与设计说明注释。

### 3. 配置数据源

默认配置可直接跑通本地环境（`localhost:3306`，`root/root`）。
**数据库账号密码支持环境变量覆盖**，生产部署无需改代码：

```bash
# Windows
set DB_PASSWORD=your_password && mvn spring-boot:run
# Linux / macOS
export DB_PASSWORD=your_password && mvn spring-boot:run
```

可覆盖的变量：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`。

### 4. 构建（注意：需要两步）

本项目依赖一个**自定义 Spring Boot Starter**（`oss-spring-boot-starter`，见下文「自定义 Starter」），
它是独立的 Maven 工程、不在本项目的 reactor 里，因此**首次构建必须先把它装进本地仓库**：

```bash
bash .hermes/build-all.sh          # = 先 install starter，再 clean package 应用
```

也可以手工两步：

```bash
.hermes/mvn.sh -f oss-spring-boot-starter/pom.xml install   # 1) 安装 starter
.hermes/mvn.sh clean package                                # 2) 构建应用
```

> 本机没有装 Maven CLI，`.hermes/mvn.sh` 封装了「调用 IntelliJ 自带 Maven」这件事。
> 若你本地有 mvn，把 `.hermes/mvn.sh` 换成 `mvn` 即可。

### 5. 启动

```bash
java -jar target/springboot3-1.0-SNAPSHOT.jar
# 或开发期：.hermes/mvn.sh spring-boot:run
```

访问 <http://localhost:8080/shop/login>。

### 6. 运行测试

```bash
.hermes/mvn.sh test                          # 应用：96 个用例
.hermes/mvn.sh -f oss-spring-boot-starter/pom.xml test   # starter：11 个用例
```

端到端验证脚本（每个 Phase 一个，可反复复跑；会自己起 jar、造测试数据、跑完清理）：

```bash
bash .hermes/verify-phase0.sh    # 56 项：缺陷修复回归 + 工程化
bash .hermes/verify-phase1.sh    # 32 项：Security/JWT/RBAC/CSRF
bash .hermes/verify-token-lifecycle.sh   # 38 项：黑名单 / refresh / 重放检测
bash .hermes/verify-starter.sh   # 12 项：自定义 Starter 两种实现
bash .hermes/verify-phase2.sh    # 领域建模 / 迁移 / 并发不超卖 / 错误码
bash .hermes/verify-phase3.sh    # 76 项：缓存命中率、三大问题、锁、降级、数据回归
```

---

## 目录结构

```
src/main/java/shop/
├── Application.java                 启动类（@EnableScheduling 开启定时任务）
├── common/                          工程化基础设施
│   ├── Result.java                  统一响应体 {code,message,data,success}
│   ├── ErrorCode.java               业务错误码枚举
│   ├── BizException.java            业务异常
│   ├── GlobalExceptionHandler.java  全局异常处理 + HTTP 状态码映射
│   │                                （含 400 参数 / 401 未登录 / 403 无权限 / 404 资源
│   │                                 / 405 方法不支持 / 500 兜底的语义分派）
│   ├── config/
│   │   ├── MybatisPlusConfig        MP 插件：分页 + 乐观锁（含顺序说明）
│   │   ├── RedisConfig              RedisTemplate 序列化（key=String / value=Jackson JSON）
│   │   ├── RedissonConfig           Redisson 客户端（锁 + 布隆过滤器；Redis 不可用时降级为 null）
│   │   └── AutoFillMetaObjectHandler 公共字段 created_at/updated_at 自动填充
│   ├── cache/                       Redis 缓存（Phase 3）
│   │   ├── CacheKeys                key 规范（shop:cache: / shop:lock: / shop:bloom:）
│   │   ├── CacheTtl                 TTL 策略（基础值 + 随机抖动 = 雪崩防护）
│   │   ├── CacheStats               命中/未命中/查库 计数 + 耗时累计（诊断接口数据源）
│   │   ├── ProductCacheVO           缓存专用值对象（为什么不用实体见类注释）
│   │   ├── ProductCacheService      Cache-Aside + 空值哨兵 + 互斥锁重建（穿透/击穿）
│   │   ├── ProductBloomFilter       RBloomFilter，启动全量装载 + 新增时告知
│   │   ├── CacheInvalidator         先更库再删缓存 + 延迟双删（一致性）
│   │   └── DistributedLock          Redisson RLock 封装（看门狗续期 + Redis 不可用降级）
│   └── service/CategoryService      分类业务层（Phase 3.3 起带缓存）
├── security/                        认证授权（Phase 1）
│   ├── SecurityConfig               SecurityFilterChain / BCrypt / URL 规则 / @EnableMethodSecurity
│   ├── JwtUtil · AccessToken        access token 签发与解析（含 jti）
│   ├── JwtAuthenticationFilter      认证入口：查黑名单 + 用 refresh 透明续期
│   ├── JwtCookieSupport             双 Cookie 读写（ACCESS_TOKEN / REFRESH_TOKEN）
│   ├── RedisTokenStore              令牌状态：黑名单 / refresh / 已用与宽限标记
│   ├── RefreshTokenService          签发 / 轮换 / 吊销 / 重放检测
│   ├── PasswordService              BCrypt + 存量 MD5 登录时透明升级
│   ├── CurrentUser                  业务侧读取当前登录者
│   ├── LoginUser · JwtProperties    认证主体与配置
│   ├── RequestTypeUtils             接口请求识别（决定 401/403 返回 JSON 还是跳转）
│   └── RestAuthenticationEntryPoint · RestAccessDeniedHandler  401/403 分流
├── admin/                           后台侧
│   ├── Bean/                        Admin · User · Product · Order · Cart
│   ├── controller/                  Login · Admin · User · Product · Order
│   ├── mapper/                      对应 Mapper 接口
│   └── tools/MD5passEncryption      【过渡期保留】仅用于校验存量 MD5 摘要，写库一律 BCrypt
└── shop/                            前台侧
    ├── Bean/CartItem                前端请求体（含参数校验）
    ├── controller/                  Index · Login · Search · Sale · Cart · Buy · Personal · Transaction
    ├── mapper/SearchMapper
    └── tools/
        ├── SessionCheck             登录态与省市/学校会话校验
        ├── UserProcess              购物车与商品列表组装
        ├── SearchProcess            搜索历史序列化 + 类目配额分配
        ├── MathTools                正态函数与中心差分导数（权重衰减用）
        ├── DailyUpdateTask          每日搜索词权重衰减定时任务
        ├── ProductsSortedByTime     商品按时间排序比较器
        └── StringToList             逗号串 ↔ List<Integer> 互转

src/main/resources/
├── application.properties           数据源 / MyBatis / JWT / Redis / oss.* 上传（支持环境变量覆盖）
├── logback-spring.xml               日志配置
├── mapper/*.xml                     6 个 SQL 映射
├── templates/shop/ (17 个)          前台页面
├── templates/admin/ (9 个)          后台页面
└── static/                          3665 张商品图 + CSS/JS（含 csrf.js）

oss-spring-boot-starter/               自定义 Starter（独立 Maven 工程）
├── src/main/java/com/example/oss/
│   ├── OssAutoConfiguration         自动配置（@AutoConfiguration + @ConditionalOn*）
│   ├── OssProperties                oss.* 配置绑定与规范化
│   ├── FileStorage                  对外契约：store / delete（以 URL 为交互单位）
│   └── LocalFileStorage             本地实现（含路径穿越防护）
└── src/main/resources/META-INF/spring/
    └── ...AutoConfiguration.imports Boot 3 的自动配置注册文件（SPI）

docs/
├── schema.sql                       建表脚本（全新安装用）
├── migration_phase1.sql             Phase 1 迁移脚本（密码扩列 + role/status）
└── ROADMAP.md                       改造路线图 + 各阶段进度与验收
```

---

## 数据模型

| 表 | 说明 |
|---|---|
| `lxy_user` | 用户（含省市区、学校，用于按地理/学校筛选） |
| `lxy_product` | 商品（`sold_time IS NULL` 表示在售） |
| `lxy_order` | 订单（`condition` 字段记录状态） |
| `cart_item` | **购物车明细（Phase 2 起为唯一事实来源）**：`(user_id, product_id, quantity, picked)`，唯一键 `uk_user_product` 保证不重复 |
| `lxy_cart` | 【兼容层】旧的逗号串购物车表。写路径已切到 `cart_item`，本表由新表**单向投影**维持一致，供尚未迁移的旧读路径使用 |
| `category` | 商品分类（8 类，Phase 2 新增） |
| `order_item` | 订单明细（快照商品名与价格，避免商品改价污染历史订单） |
| `address` | 收货地址（一对多，一对多的替换原来塞在 user 表的省市区） |
| `review` | 商品评价（唯一键保证一单一件只能评一次） |
| `payment` | 支付流水（唯一键 `uk_order_no` 是回调幂等的基石） |
| `lxy_admin` | 管理员 |
| `chat_records` | 私聊消息（**原项目未完成的功能**；重规划后决定不补，与电商主线无关） |
| `lxy_mate` | 好友关系（**原项目未完成的功能**；同上，保留表结构不动） |
| `lxy_product_sale` | 废弃表，无代码引用 |

---

## 接口清单

### 前台

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/shop/index` | 首页商品列表（登录后走个性化排序） |
| GET | `/shop/index/sortedByTime` | 按发布时间排序 |
| GET | `/shop/index/chosenByLocation` | 按省市/区筛选 |
| GET | `/shop/index/chosenBySchool` | 按学校筛选 |
| GET/POST | `/shop/login` | 登录 |
| GET/POST | `/shop/signup` | 注册 |
| GET | `/shop/exit` | 退出 |
| POST | `/shop/search` | 搜索（并记录搜索历史） |
| GET | `/shop/productDetail/{id}` | 商品详情（自增浏览量） |
| GET/POST | `/shop/cart` | 购物车 |
| POST | `/shop/addToCart` | 加入购物车 → `Result<Boolean>` |
| POST | `/shop/deleteProduct` | 从购物车移除 |
| POST | `/shop/buy` | 结算（清理购物车）→ `Result<Boolean>` |
| POST | `/shop/buySuccess` | 创建订单 → `Result<Void>` |
| GET | `/shop/buySuccess` | 下单成功页 |
| POST | `/shop/changeStatus` | 推进订单状态 → `Result<Void>` |
| POST | `/shop/uploadProduct` | 发布商品（multipart） |
| GET/POST | `/shop/changeProductInformation/{id}` | 编辑商品 |
| POST | `/shop/changeProduct` | 提交商品修改 |
| POST | `/shop/deleteMyRelease` | 下架自己的商品 → `Result<Boolean>` |
| GET | `/shop/person` 等 | 个人中心（发布/卖出/买入/已完成） |
| GET | `/shop/checkSession` | 登录态探测 → `Result<Boolean>` |

### 前台（Phase 3 缓存相关，JSON）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/shop/api/products/{id}` | 商品详情（走 Redis 缓存，Cache-Aside）|
| GET | `/shop/api/products/{id}?bypassCache=true` | 同上但**绕过缓存直查库**（对照实验 / 缓存故障时应急）|
| GET | `/shop/api/cache/stats` | 缓存运行统计：命中率、平均耗时、真正查库次数、布隆过滤器是否可用 |

> `/shop/api/products/**` 是**只读**接口，所以放在公开路径里；下单（`/shop/buy`、`/shop/buySuccess`）
> 仍然需要登录 —— 放行范围按「最小可用」逐条列，不使用 `/shop/api/**` 这种宽通配符。

### 后台

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/admin/login`、`/admin/loginResult`、`/admin/logout` | 管理员登录/登出 |
| GET | `/admin/admin`、`/admin/admin_edit` | 控制台、修改管理员密码 |
| GET | `/admin/user`、`/admin/user_add`、`/admin/user_edit/{id}`、`/admin/user_delete/{id}` | 用户管理 |
| POST | `/admin/result`、`/admin/userEdit` | 新增/编辑用户 |
| GET | `/admin/product`、`/admin/product_add`、`/admin/product_delete/{id}` | 商品管理 |
| POST | `/admin/product_add` | 新增商品（multipart） |
| GET | `/admin/order`、`/admin/order_delete/{id}` | 订单管理 |

---

## 接口返回约定

所有 JSON 接口统一返回：

```json
{ "code": 200, "message": "操作成功", "data": true, "success": true }
```

- `code`：业务状态码，见 `shop.common.ErrorCode`
- `success`：`code == 200` 的便捷判定，前端只判断这一个字段
- 失败时 **HTTP 状态码同步映射**（400/401/403/404/500），便于网关、监控、浏览器正确处理

前端消费方式：

```js
fetch('/shop/addToCart', {...})
  .then(r => r.json())
  .then(result => {
    if (!result.success) { /* result.message 可直接展示 */ }
  });
```

---

## 开发约定

- **每次改动后必须跑 `mvn test`**，测试全绿再提交
- 日志一律用 `@Slf4j`，禁止 `System.out.println` / `printStackTrace`
- 参数校验用注解（`@NotBlank` 等）+ `@Valid`，不要手写 if 链
- 业务失败抛 `BizException(ErrorCode.XXX)`，由全局处理器统一转换，不要在 Controller 里自己拼返回结构
- **不要提交 `target/`、`logs/`、`.idea/`**（已在 `.gitignore`）
- 数据库变更必须同步更新 `docs/schema.sql`，并提供可执行的迁移脚本（如 `docs/migration_phase1.sql`）

### 🧠 Redis 缓存与分布式锁（Phase 3 起）

**实测数字（`bash .hermes/verify-phase3.sh`，76 项断言全绿）：**

| 指标 | 结果 |
|---|---|
| 缓存命中率（应用侧累计） | **98.1%**（命中 105 / 未命中 2）|
| 命中缓存平均耗时 | **778 µs/次** |
| 直查库平均耗时（同一条 SQL） | **1816 µs/次** → 缓存快 **2.3 倍** |
| 单 key 冷启动 + 20 并发 | 只有 **1 次**查库（其余 19 个走缓存）|
| 空值缓存挡住重复的无效 id | 10 次请求 **0 次**查库 |
| 同一用户 8 并发下单 | 成功 **1** 笔（其余 409），订单数 **1** |

> 2.3 倍看起来不夸张，原因是商品详情这条 SQL 本身已经是**主键 JOIN**（1.8ms）——
> 缓存省掉的是这一次点查，收益有限。列表/多表统计类查询才是缓存真正的主战场，
> 但那种查询带用户维度，命中率与越权风险都不划算（见下面「为什么不缓存列表」）。
> 面试时讲这个数字**要说清楚它为什么不大**，比刷一个好看的倍数更有说服力。

**Key 布局**（全部在 `shop:` 前缀 + `db 3`，与同机其他项目隔离；`redis-cli -n 3 keys 'shop:cache:*'` 可读）：

| Key | 说明 | TTL |
|---|---|---|
| `shop:cache:product:{id}` | 商品详情（JSON，值类型 `ProductCacheVO`）| 30 分钟 + 随机 0~5 分钟 |
| `shop:cache:category:enabled` | 启用中的分类列表 | 2 小时 + 随机 0~10 分钟 |
| `shop:bloom:product` | 商品 id 布隆过滤器（`RBloomFilter`）| 无（随写入增长）|
| `shop:lock:cache:product:{id}` | 缓存重建互斥锁（`SET NX PX 3000`）| 3 秒 |
| `shop:lock:order:{uid}:{pid}` | 下单分布式锁（Redisson `RLock`）| 看门狗自动续期 |

**缓存三大问题，各自落在哪一行代码：**

| 问题 | 现象 | 本项目的做法 | 代码位置 |
|---|---|---|---|
| **穿透** | 反复查不存在的 id，每次都打到库 | ① 布隆过滤器（说「一定没有」时不碰 Redis 也不碰库）② 空值哨兵 `__NULL__`（短 TTL 60 秒，兜住假阳性与已删商品）| `ProductBloomFilter` / `ProductCacheService.put` |
| **击穿** | 热点 key 过期的瞬间，N 个并发同时查库 | `setIfAbsent` 抢重建锁 + 拿锁后**双重检查**；没抢到的短暂等待后读缓存，超时（400ms）则降级查库 | `ProductCacheService.rebuild` |
| **雪崩** | 大批 key 同时过期，峰值全砸到库 | TTL = 基础值 + 随机抖动（`CacheTtl.withJitter`），过期时刻被摊开 | `CacheTtl` |

**缓存一致性（Phase 3.7）：先更新库、再删缓存 + 延迟双删**

```
写请求：update DB → delete cache → (+500ms) delete cache again
```

- **为什么是删缓存而不是更新缓存**：更新缓存要在业务代码里算出「新值」，并发写还会乱序
  （A 后写库、B 先写缓存 ⇒ 缓存里留下旧值）；删除是幂等的，删错了最多让下一个读请求查一次库。
- **延迟双删挡的是这个竞态**：读请求在「写请求删缓存之前」读到旧值，又在「删缓存之后」把旧值写回缓存。
  第二次延迟删除把这份脏数据清掉。延迟必须大于「一次读的查库+回写」耗时，这里是 500ms。
- **它治不好什么（要能主动说出来）**：双删只是把不一致窗口从「TTL 那么长」压到毫秒级，
  并不消灭并发写乱序。强一致要靠 binlog 订阅（Canal）或版本号，本项目不做。
- 调用点：`ShopSaleController.changeProduct`（改商品）、`admin/ProductController`（删商品）；
  **必须在数据库提交之后调用**，否则读请求会把「旧的库+空的缓存」的旧值回填进去。

**分布式锁（Phase 3.8）：Redisson `RLock` 解决「同一用户重复提交」**

```java
distributedLock.runWithLock(CacheKeys.userOrderLock(buyerId, productId),
        0L,      // 等待时间 0：抢不到立刻返回 409，不排队（排队会让两次都成功）
        -1L,     // 租期交给看门狗自动续期，业务跑多久锁活多久
        () -> doPurchase(...));
```

- 为什么还需要锁，既然已经用了库存条件更新？**两者管的事不同**：条件更新保证「不超卖」（数据正确），
  锁保证「同一个人的重复请求不进写链路」（否则第二个请求会返回一个用户自己造成的莫名失败）。
  所以锁的粒度是「用户 + 商品」——不同买家之间不需要排队。
- 手写 `SETNX` 的四个坑（加锁与过期非原子 / 业务没跑完锁过期 / 误删别人的锁 / 不可重入）都由 Redisson 处理。
- **锁必须在事务外面**：拿锁 → 开事务 → 提交 → 释放锁，中间隔着完整重试循环。

**降级设计（Redis 挂了会怎样）**

| 场景 | 行为 |
|---|---|
| Redis 不可达 | 应用**照常启动**；缓存读写全部退化为「查库」，接口可用（实测断言覆盖）|
| Redisson 初始化失败 | `@Bean` 返回 null，依赖方通过 `ObjectProvider` 拿到 null 后跳过锁与布隆过滤器（实测覆盖）|
| 缓存重建锁获取失败 | 按「未抢到」处理，走等待 → 超时查库，不会把请求卡死 |
| 删缓存失败 | 只记 WARN，商品更新照常成功（等 TTL 自愈）|

> 定这个基调的理由：**缓存层不该成为可用性的单点**。宁可少一层保护，也不要一个缓存组件把整站拖下线。

**两个必须知道的设计取舍**

1. **不直接缓存实体，缓存专用 VO**（`ProductCacheVO`）。测试逼出来的结论：实体的 `createdAt` 是
   `java.sql.Date`，经 Jackson 往返后**时分秒丢失、时刻还被重解释**（写的时候按 JVM 本地时区取日期、
   读回来按 UTC 解析，UTC+8 机器上偏 8 小时）。缓存里存一个和库对不上的时间，是那种不报错、
   只会慢慢变歪的 bug。VO 用字符串承载日期（`2024-05-06`），两侧完全对称。
2. **不缓存商品列表 / 搜索 / 购物车**：它们带用户与关键词维度，命中率低，而且一旦缓存就要处理
   「A 看到 B 的购物车」这类越权问题。缓存粒度按**数据是否与用户相关 + 变更频率**来切，
   而不是「读多就缓存」。

**为什么浏览量不进缓存**：它每次访问都 +1，放进缓存就得每次删缓存 —— 缓存等于自毁。
所以库里用一条 `update ... set view_count = view_count + 1` 原子自增（顺手修掉了原来的
「读-改-写」丢失更新），页面展示「缓存值 + 1」，允许偏小但不会为了一个数字把缓存废掉。

### 🔎 索引与查询优化（Phase 2.6 起）

对 6 条真实高频 SQL 用 `EXPLAIN` 做了前后对比（脚本 `.hermes/index-report.py`，可复跑）：

| 查询 | 场景 | 优化前 | 优化后 |
|---|---|---|---|
| `lxy_user where username=?` | **每个请求**都按用户名查 id | 全表扫描 30 行 | **const**，`uk_username`，1 行 |
| `lxy_product where img_store_path=?` | 加购/下单/改状态反查商品 | 全表扫描 **3628** 行 | **const**，`uk_img_path`，1 行 |
| 同城筛选 | 同城列表 | 两层全表扫描 (30 × 3628) | `idx_location`(1) × `idx_uid_sold`(62) |
| 按学校筛选 | 同校列表 | 两层全表扫描 (30 × 3628) | `idx_school`(1) × `idx_uid_sold`(62) |
| `lxy_order where product_id=?` | 订单页 / 状态推进 | 全表扫描 28 行 | **ref**，`idx_product`，1 行 |

- 其中 `uk_username`、`uk_img_path` 用 **UNIQUE**：它们同时是真实业务约束
  （用户名唯一、一件商品对应一个图片文件），唯一索引把「代码里检查」升级为「数据库保证」。
- **首页那条刻意不优化，并给出了实测论证**：筛选条件 `sold_time is null` 在 3628 件商品里命中 3626 件
  （选择性 0.06%），加 `idx_sold_time` 后优化器不选它、计划反而从扫 3628 行变成 30×62 的循环，
  估算代价 514 → 641 更贵，已撤掉。正确解法是分页（`order by id desc limit N` 走主键）。
  —— 索引的价值来自**选择性**而不是数量。

### 📦 订单状态机与明细（Phase 2.5 起）

订单状态用枚举 + 显式流转表定义，而不是散在流程代码里的 if/else：

```
PENDING_PAY(待支付) ──► PAID(等待发货) ──► SHIPPED(已发货) ──► COMPLETED(订单已完成)
        │                    │
        └────────────────────┴──► CANCELLED(已取消)         COMPLETED / CANCELLED 为终态
```

- **旧中文文案双写**：数据库里 `condition` 仍被既有页面使用，状态机推进时同步写
  `status`（枚举名）与 `condition`（中文），过渡期结束后只需删掉旧列。
- **非法流转与并发都靠一条 SQL 拦住**：

  ```sql
  update lxy_order set status = ?, `condition` = ?
   where id = ? and status = ?    -- 影响行数 0 表示状态已变，拒绝本次操作
  ```

  把「检查」放进 WHERE 而不是「先查再改」—— 后者在并发下两个请求会同时通过检查。
- **订单明细存快照**：`order_item` 保存下单瞬间的商品名与价格。订单是历史凭证，
  卖家之后改名/改价不能影响历史订单，所以不能展示时再 JOIN 商品表。
- **对外只暴露业务订单号** `SO + yyyyMMddHHmmssSSS + 4 位随机`，不暴露自增 id
  （自增 id 会泄漏业务量、也便于被遍历探测）；唯一性由唯一索引兜底。

### 🧮 库存与并发（Phase 2.4 起）

二手商品一物一件，最典型的并发问题是**超卖**：两个人同时买同一件，
「先查是否售出 → 再标记售出」这种两步写法会让两人都成功。

现在的做法是把「检查」与「扣减」压进一条 SQL：

```sql
-- 乐观锁：version 条件由 MyBatis-Plus 乐观锁插件自动追加，影响行数为 0 即表示被别人抢先改了
update lxy_product set stock = stock - 1, version = version + 1
 where id = ? and version = ? and stock >= 1
```

两种策略用配置切换（`shop.stock.strategy`），业务代码零改动：

| 值 | 实现 | 特点 |
|---|---|---|
| `optimistic`（默认）| 读版本 → 条件更新 → 冲突重试 | 不阻塞；冲突多时重试会放大库压力 |
| `pessimistic` | `SELECT ... FOR UPDATE` 锁行后操作 | 一次成功不重试；持锁期间其他请求排队 |

- **重试必须在事务外层**：REPEATABLE READ 下事务内的普通 SELECT 复用同一份快照，
  事务内重试读到的还是旧版本号，必然再次失败（见 `PurchaseService` 与 `PurchaseTxService` 的分工）。
- **扣库存 + 建订单 + 标记售出在同一个事务内**，避免半完成状态。
- **抢购失败返回 409**（不是 500）：这是「资源状态已变更」的预期内失败，不该混进服务端错误率。

### 🔐 认证与授权（Phase 1 起）

> **⚠️ CSRF 配置里有一行看起来多余、但绝不能删的代码**（Phase 2.3 发现）：
> `csrf(...).sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())`
>
> 不写它，`CsrfConfigurer` 默认追加的 `CsrfAuthenticationStrategy` 会在**每个请求**上触发
> （因为无状态 + 每请求重新认证 ⇒ `SessionManagementFilter` 每次都认为"发生了新认证"），
> 于是每次响应都清掉并轮换 CSRF cookie，导致**同一页面上连续第二次写操作必然 403**。
> 单次操作测不出来，必须连着做两次才会暴露。详见 `docs/ROADMAP.md` 的 Phase 2 报告。


- **身份只认 JWT**：`SecurityContext` 由 `JwtAuthenticationFilter` 从 HttpOnly Cookie 或
  `Authorization: Bearer` 头重建。**不要在 Controller 里用 session 判断「谁登录了」** ——
  业务代码通过 `shop.security.CurrentUser` 取当前用户（`username()` / `id()` / `hasRole()`）。
- **鉴权写在安全层**：URL 规则（`SecurityConfig`）+ 方法注解（`@PreAuthorize`）双保险，
  Controller 内部不要再自行判角色（两份真相会导致「认证通过却被自己的代码踢出去」）。
- **HttpSession 只允许承载展示数据与一次性提示**（用户名、地区、学校、`saleError` 等），
  绝不承载任何授权信息。
- 新增 `/shop/**` 的写接口或页面时，记得在 `SecurityConfig` 的 `PUBLIC_PATHS` /
  `AUTHENTICATED_PATHS` 里归类；接口端点还要同步登记到 `RequestTypeUtils.API_PATHS`
  （否则 401/403 会被当成页面请求返回 302）。

### 🛡 CSRF（新增表单 / ajax 必读）

CSRF 防护处于开启状态（`CookieCsrfTokenRepository` + 非 Xor 的 `CsrfTokenRequestAttributeHandler`）。
**所有非 GET 请求都必须携带 token，否则一律 403**：

| 场景 | 做法 |
|---|---|
| 表单 POST | 用 `th:action` 声明 action，并保留表单内的 `<input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>` |
| fetch / `$.ajax` | 引入 `/shop/assets/JS/csrf.js`，它会自动读取 `XSRF-TOKEN` Cookie 并塞进 `X-XSRF-TOKEN` 请求头 |
| curl 手工验证 | 表单类加 `-d "_csrf=$TOKEN"`；JSON 类加 `-H "X-XSRF-TOKEN: $TOKEN"` 且**同时带上同一份 Cookie** |

token 会随每次响应轮换，所以**脚本里必须在发请求前现取最新值**，不能用早先抓到的旧值
（`.hermes/verify-phase0.sh` 里的 `json_post()` / `csrf_of()` 就是为此封装的）。

> JWT 配置在 `application.properties` 的 `jwt.*`：`secret`（HS256 要求 ≥ 32 字节，
> 生产必须用环境变量 `JWT_SECRET` 覆盖）、`expire-minutes`（access，默认 30）、
> `refresh-expire-days`（默认 7）、`cookie-name` / `refresh-cookie-name`、`cookie-secure`。

### 🔑 令牌语义（改动认证相关代码前必读）

**两个令牌，职责分离，不要混用：**

| | access token | refresh token |
|---|---|---|
| 形态 | JWT（无状态，签名自证） | 随机 UUID（Redis 里存着，可即时吊销） |
| 寿命 | 30 分钟 | 7 天 |
| Cookie | `ACCESS_TOKEN` | `REFRESH_TOKEN` |
| 存在哪 | 只在客户端 | Redis `shop:rt:{uuid}` |

- **续期是全自动的**：access 过期后，`JwtAuthenticationFilter` 会用 refresh 换发新的
  access + refresh 并写回 Cookie，浏览器与前端<b>不需要任何配合</b>。
  所以不要再往 Controller 里加「token 过期了就跳登录页」这类逻辑。
- **登出必须三步**：`refreshTokenService.revoke(...)` + `tokenStore.blacklistAccess(jti, 剩余寿命)`
  + 清两个 Cookie。只清 Cookie 等于没登出（令牌仍在有效期内）。
- **改密码 / 强制下线**：调用 `refreshTokenService.revokeAllForUser(uid)`，
  否则攻击者手里的 refresh token 能把会话一直续下去。
- **Redis 故障会降级而非报错**：`RedisTokenStore` 内部吞掉异常并记 WARN。
  如果发现「登出后 token 还能用」，先查 Redis 是否可用，再怀疑代码。
- **不要在业务代码里直接操作 `shop:*` 这些 key**，统一走 `RedisTokenStore`，
  否则 TTL 与「已用/宽限标记」的约定很容易被破坏（黑名单不设 TTL 会永久堆积）。

### ⚠️ 视图名不要带前导斜杠

```java
return "shop/index";     // ✅ 正确
return "/shop/index";    // ❌ 错误：spring-boot:run 能跑，打成 jar 后必然 500
```

Thymeleaf 的解析前缀是 `classpath:/templates/`。带前导斜杠会拼成
`classpath:/templates//shop/index.html`（双斜杠）：

- `spring-boot:run` 走的是 `target/classes` 展开目录，文件系统会把 `//` 归一化 → 正常
- 打包成 jar 后走 ZIP 条目的精确匹配，`templates//shop/index.html` 查不到 → 报
  `Error resolving template` → 500

**所以任何页面改动都必须用打包后的 jar 验证一遍，不能只验证 `spring-boot:run`。**
（Phase 0 收尾时踩过这个坑：26 个视图名带前导斜杠，IDE 里全绿、jar 里全 500）

### 配置覆盖

`Application.main` 必须把 `args` 传给 `SpringApplication.run(Application.class, args)`，
否则 `--server.port` / `--spring.profiles.active` / `--spring.datasource.url` 等
命令行参数会被静默丢弃（Phase 0 修过这个 bug）。

```bash
# 本地验证打包产物（推荐固定用一个不冲突的端口）
mvn clean package
java -jar target/springboot3-1.0-SNAPSHOT.jar --server.port=18080
```

---

## 已知问题与后续计划

完整路线图、面试考点映射表、各阶段验收标准与实测数据见 **[docs/ROADMAP.md](docs/ROADMAP.md)**。

**Phase 0 已完成：** 14 处缺陷修复 + 打包阶段新发现 3 处、数据库审计与索引补齐、
工程化基础设施（统一响应体 / 全局异常 / 参数校验 / 日志规范）、45 个单元测试、2 个水平越权漏洞修复。

**Phase 1 已完成（本阶段）：**
Spring Boot 2.4.3 → 3.3.5 + `javax`→`jakarta` 全量迁移；Spring Security 6 + JWT（HttpOnly Cookie）
+ RBAC（`ROLE_USER` / `ROLE_ADMIN`）+ BCrypt 密码（存量 MD5 登录时透明升级）；
CSRF 防护开启并打通表单 / ajax 两条链路；移除 Controller 里用 session 鉴权的脆弱写法；
**令牌生命周期补齐**：短寿命 access（30 分钟）+ Redis 存储的 refresh（7 天，可即时吊销）、
登出黑名单、刷新轮换与重放检测（含 60 秒并发宽限）、改密码撤销全部会话。

自定义 Starter `oss-spring-boot-starter`（考点 16）：`@AutoConfiguration` + Boot 3 的
`AutoConfiguration.Imports` SPI + `@ConditionalOnProperty` / `@ConditionalOnMissingBean`，
把「文件存储」抽成可切换的实现；业务侧只注入 `FileStorage` 拿 URL，
上传/删除代码从「自己拼路径、自己建目录、自己删文件」变成一行调用。

**Phase 2 已完成：** 领域建模（`docs/schema_v2.sql`，非破坏性 + 可重复执行，含数据迁移与集合级交叉核对）；
接入 MyBatis-Plus（分页 / 乐观锁 / 自动填充插件）；购物车从逗号串切到 `cart_item` 关联表
（顺带修掉「连续两次写操作必 403」的真实 CSRF 缺陷）；库存扣减双策略 + 并发不超卖（409 语义）；
订单状态机（枚举 + 流转表）+ `order_item` 明细快照 + PO/DTO/VO 分层；
索引优化（6 条高频 SQL 的 `EXPLAIN` 前后对比，含一条「刻意不加索引」的实测论证）。

**Phase 3 已完成：** Redis 缓存 + 分布式锁 —— 商品详情 Cache-Aside（专用缓存 VO）、分类列表缓存、
穿透（布隆过滤器 + 空值哨兵）、击穿（互斥锁重建）、雪崩（TTL 随机打散）、
一致性（先更库再删缓存 + 延迟双删）、Redisson `RLock` 挡重复提交、命中率与耗时实测
（98.1% 命中 / 778µs vs 1816µs），以及「Redis 挂了应用照常启动」的降级路径验证。
详见上面「🧠 Redis 缓存与分布式锁」一节。

**Phase 1–3 已全部完成。** 后续路线图已按**校招标准**重规划（2026-09-13）：
砍掉分库分表 / 分布式事务 / 微服务 / ES / IM / 可观测性全家桶，补齐常规电商缺口。
详见 `docs/ROADMAP.md`。

1. **Phase 4（必做）**：RabbitMQ 异步下单 + 订单超时取消（延迟消息 + 扫表兜底）+ 消费幂等 + 秒杀基础版
2. **Phase 5（加分）**：Knife4j、AOP 日志与 `@RateLimit`、模拟支付、DTO/VO 分层、`ThreadLocal` 用户上下文
3. **Phase 6（收尾）**：Docker Compose + Nginx + 压测数字 + 面试问答

> 两个待处理的遗留（不急，但被问到要能答）：
> 上传目录仍在 `src/main/resources`（应外置；starter 已支持改配置，业务代码零改动）；
> `spring-boot-starter-websocket` 属零引用依赖，可移除。
