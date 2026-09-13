# 校园二手交易平台 · 项目详解

> 一个面向高校的二手交易系统：用户在校园内发布、搜索、浏览闲置物品，加入购物车、下单、
> 跟踪订单状态；管理员在后台管理用户、商品与订单。
>
> 本文回答三件事：**这个系统实现了什么**、**每一处解决了什么问题**、**是怎么实现的**。
> 所有数字都来自本仓库可复跑的脚本或单元测试；出现的类名、表名、字段名都是代码里真实的。
>
> 相关文档：`README.md`（怎么把项目跑起来）、`docs/ROADMAP.md`（分阶段实施计划与各阶段报告）。

---

## 目录

- [1. 项目概览](#1-项目概览)
- [2. 架构与分层](#2-架构与分层)
- [3. 数据模型](#3-数据模型)
- [4. 功能实现](#4-功能实现)
  - [4.1 账号与密码](#41-账号与密码)
  - [4.2 认证与授权](#42-认证与授权)
  - [4.3 令牌生命周期（可撤销的无状态认证）](#43-令牌生命周期可撤销的无状态认证)
  - [4.4 商品发布与图片上传](#44-商品发布与图片上传)
  - [4.5 个性化搜索（搜索历史权重）](#45-个性化搜索搜索历史权重)
  - [4.6 商品列表与筛选](#46-商品列表与筛选)
  - [4.7 商品详情与浏览量](#47-商品详情与浏览量)
  - [4.8 购物车](#48-购物车)
  - [4.9 下单与库存（并发不超卖）](#49-下单与库存并发不超卖)
  - [4.10 订单（状态机 + 明细快照）](#410-订单状态机--明细快照)
  - [4.11 缓存体系](#411-缓存体系)
  - [4.12 分布式锁](#412-分布式锁)
  - [4.13 后台管理](#413-后台管理)
- [5. 关键技术难点（问题 → 方案 → 实现 → 实测）](#5-关键技术难点)
- [6. 性能：从「加了索引」到数字](#6-性能从加了索引到数字)
- [7. 质量保障：测试与验证体系](#7-质量保障测试与验证体系)
- [8. 工程基建](#8-工程基建)
- [9. 面试问答](#9-面试问答)
- [10. 简历话术（带实测数字）](#10-简历话术)
- [11. 运行与部署](#11-运行与部署)
- [12. 取舍与后续演进](#12-取舍与后续演进)

---

## 1. 项目概览

### 1.1 业务闭环

```
注册/登录 ──► 发布商品(传图) ──► 浏览/搜索(按姓名/城市/学校) ──► 加入购物车 ──► 下单
                                                                          │
                                   订单状态流转：待支付 ► 等待发货 ► 已发货 ► 已完成（可取消）
                                                                          │
                                   个人中心：我发布的 / 我卖出的 / 我买到的 / 已完成的
管理员后台：用户管理 · 商品管理 · 订单管理 · 管理员密码
```

**一件二手商品 = 库存 1**。这个业务事实决定了后面的很多设计：超卖的后果不可接受、
库存扣减必须原子、分布式锁的粒度选「用户 + 商品」而不是「商品」。

### 1.2 规模与现状

| 项 | 数值 |
|---|---|
| 真实数据 | 商品 **3659** 件 / 用户 30 / 订单 28 / 购物车 6 条明细 / 分类 8 / 商品图 **3665** 张 |
| 代码 | Java 类 **86** 个、Controller 15 个、Mapper 接口 10 个（+ 8 个手写 XML）、Thymeleaf 页面 **26** 个 |
| 前端 | 原生 jQuery（2281 行 JS、1630 行 CSS），无构建工具 |
| 接口 | 前台 38 个 + 后台 17 个（共 55 个唯一路径） |
| 测试 | 单元测试 **96**（应用）+ 11（starter）；端到端 **200+** 断言（6 个可复跑脚本） |

### 1.3 技术栈与选型理由

| 层次 | 选型 | 为什么 |
|---|---|---|
| 框架 | Spring Boot 3.3.5 / Java 17 | 用得到 Java 17 的运行时收益；Boot 3 是 `javax`→`jakarta` 的分水岭，早跨比晚跨便宜 |
| 视图 | Thymeleaf + 原生 jQuery | 服务端渲染 + 表单的业务页面；前端不是本项目的重点，重写 25 个页面收益低 |
| 持久层 | MyBatis-Plus 3.5.9 + XML | 单表 CRUD 用 `BaseMapper` 省掉大量模板代码；多表 JOIN 仍手写 XML（混用是正常做法） |
| 数据库 | MySQL 8 | 事务、行锁、执行计划分析都够用；数据量与查询形态不需要分库分表 |
| 认证 | Spring Security 6 + JWT（HttpOnly Cookie）+ RBAC + BCrypt | 前后端不分离 + 页面跳转必须靠 Cookie 携带凭证（见 4.2） |
| 缓存 / 锁 | Redis 5 + Redisson 3.52 | 缓存三大问题治理；`RLock` 的看门狗续期与可重入、`RBloomFilter` 的原子位操作都不必自己写 Lua |
| 工程 | 自定义 Starter（`oss-spring-boot-starter`）| 把「文件存储」收敛成一行 `store/delete` 调用，本地磁盘 ⇄ 对象存储可切换 |

---

## 2. 架构与分层

### 2.1 分层约定

```
Controller（参数校验、视图/JSON 选择、组装 Model）
    ↓
Service（业务规则、事务边界、缓存读写）
    ↓
Mapper（MyBatis-Plus BaseMapper + 手写 XML 的多表 JOIN）
```

- **新代码统一走三层**：事务注解加在 Service 上、业务规则集中在 Service 里，Controller 只做参数与响应。
- **早期的页面型 Controller 仍是 Controller 直连 Mapper**，这是有意的：
  25 个服务端渲染页面的重写收益低于风险，按「新功能走新分层、旧页面在必须改动时顺带迁移」推进。
- **构造器注入 vs 字段注入**：新代码用构造器注入（依赖 `final`、缺依赖启动即失败、
  单测能直接 `new` 出对象）；旧代码里是字段注入 —— 这两处并存，正好可以在面试里对比着讲。

### 2.2 配置与外部依赖

| 配置 | 值 / 默认 | 说明 |
|---|---|---|
| 数据源 | `jdbc:mysql://localhost:3306/shop`，账号 `root/root` | 支持 `${DB_HOST}` 等环境变量覆盖，密码不写死在代码里 |
| Redis | `127.0.0.1:6379` **db 3** + `shop:` 前缀 | 同机可能有别的项目用同一个 Redis，用独立 db + 前缀隔离键空间 |
| 令牌 | access 30 分钟 / refresh 7 天 | 见 4.3 |
| 库存策略 | `shop.stock.strategy=optimistic` | `optimistic` / `pessimistic` 可切换，业务代码零改动 |
| 缓存开关 | `shop.cache.enabled=true` | 关掉即全部直查库（对照实验 / 线上应急） |
| Redisson 开关 | `shop.redis.redisson-enabled=true` | 关掉后锁与布隆过滤器自动降级，应用照常启动 |
| 文件存储 | `oss.type=local` + `oss.local-dir` | 由自定义 Starter 自动配置接管 |

### 2.3 一次请求经过了什么（以商品详情页为例）

```
浏览器 GET /shop/productDetail/1
  → JwtAuthenticationFilter      解析 ACCESS_TOKEN Cookie → 校验签名 → 查 Redis 黑名单
  → SecurityConfig 授权规则      匿名可访问，放行
  → ShopIndexController          SessionCheck 取展示数据（用户名/地区/学校）
  → ProductCacheService.getById  ① 布隆过滤器预检 ② Redis GET
                                  命中 → 直接返回；未命中 → 抢重建锁 → 查库 → 回写缓存
  → ProductMapper.increaseViewCount   浏览数走数据库原子自增（不进缓存）
  → Thymeleaf 渲染 productDetail.html
```

### 2.4 目录地图

```
src/main/java/shop/
├── common/            统一响应体 Result / ErrorCode / BizException / GlobalExceptionHandler
│   ├── OrderStatus            订单状态机（枚举 + 流转表）
│   ├── config/                MybatisPlusConfig · RedisConfig · RedissonConfig · AutoFillMetaObjectHandler
│   ├── cache/                 CacheKeys · CacheTtl · CacheStats · ProductCacheVO · ProductCacheService
│   │                          ProductBloomFilter · CacheInvalidator · DistributedLock
│   └── service/               CategoryService · CartService · OrderService
│                              PurchaseService（重试策略）· PurchaseTxService（事务内）
│                              stock/  StockDeductStrategy + 乐观锁/悲观锁实现 + StockConflictException
├── security/           SecurityConfig · JwtUtil · JwtAuthenticationFilter · JwtCookieSupport
│                       RedisTokenStore · RefreshTokenService · PasswordService
│                       RestAuthenticationEntryPoint · RestAccessDeniedHandler
├── admin/              Bean/ · controller/ · mapper/      （后台管理 + 数据实体）
└── shop/               controller/ · Bean/ · mapper/ · tools/   （前台页面与工具：搜索、定时任务）
oss-spring-boot-starter/   自定义 Starter（文件存储自动配置）
src/test/java/             16 个测试类
.hermes/                   本地工具链（gitignored）：mvn.sh · verify-phaseN.sh · index-report.py · backups/
```

---

## 3. 数据模型

### 3.1 表清单

| 表 | 行数 | 用途 | 关键约束 |
|---|---|---|---|
| `lxy_user` | 30 | 用户（含省市/学校，用于同城与同校筛选；`search` 存个性化搜索权重字典） | `uk_username` 唯一 |
| `lxy_product` | 3659 | 商品（一物一件） | `uk_img_path` 唯一；`version` 乐观锁；`stock`/`status` 库存与状态 |
| `lxy_order` | 28 | 订单 | `uk_order_no` 唯一；`status` 枚举 + `condition` 旧中文文案（兼容期双写） |
| `order_item` | 28 | 订单明细**快照**（下单瞬间的商品名与价格） | 订单是历史凭证，不随商品改名改价而变化 |
| `cart_item` | 6 | 购物车明细（用户 × 商品） | `uk_user_product` 唯一，天然防重复加购 |
| `category` | 8 | 分类（预留 `parent_id` 两级结构 + `sort`） | — |
| `address` / `payment` / `review` | 0 | 收货地址 / 支付流水 / 评价 | 表已按电商闭环建好，功能在后续阶段接入（见第 12 章）|
| `chat_records` / `lxy_mate` / `lxy_product_sale` | 30 / 20 / 0 | 历史遗留结构（私聊、好友、废弃中间设计） | 与电商主线无关，已明确不接入 |

### 3.2 索引设计

| 表 | 索引 | 类型 | 服务的查询 |
|---|---|---|---|
| `lxy_user` | `uk_username(username)` | **唯一** | 每个请求都按用户名查 id（登录态、权限、下单）|
| `lxy_user` | `idx_location(province,city,area)` | 普通 | 同城筛选 |
| `lxy_user` | `idx_school(school)` / `idx_role(role)` | 普通 | 同校筛选 / 按角色查 |
| `lxy_product` | `uk_img_path(img_store_path)` | **唯一** | 加购、下单、改状态都要「按图片路径反查商品」|
| `lxy_product` | `idx_uid_sold(uid,sold_time)` | 普通 | 我发布的 / 列表页「未售出」过滤 |
| `lxy_product` | `idx_category_status_created(category_id,status,created_at)` | 普通 | 分类页按上架时间倒序 |
| `lxy_order` | `uk_order_no(order_no)` | **唯一** | 对外只暴露业务订单号，唯一性由数据库保证 |
| `lxy_order` | `idx_product` / `idx_user_status(buy_uid,status)` / `idx_seller_status(sell_uid,status)` | 普通 | 订单查询与状态推进 |
| `cart_item` | `uk_user_product(user_id,product_id)` | **唯一** | 购物车去重 + 明细查询 |

设计要点：
- 两个**唯一**索引同时承担业务约束（用户名唯一、一件商品一个图片文件），
  把「代码里检查」升级为「数据库保证」。
- 多列索引的列顺序按「等值在前、范围在后」和真实查询形态排（如 `(uid, sold_time)` 服务
  「某人的未售出商品」）。
- 有一条**刻意不加**索引 —— 见第 6 章，那是有实测论证的取舍。

---

## 4. 功能实现

> 每个模块按「解决什么问题 → 怎么实现 → 设计取舍」展开。
> 更硬的技术难点（并发、缓存、事务）集中放在第 5 章。

### 4.1 账号与密码

**要解决的问题**：用户注册登录、密码不能明文/弱摘要存储、管理员与普通用户权限分离。

**实现**
- 注册：JSR-303 校验入参（用户名、邮箱、手机号格式），密码落库前用 `BCryptPasswordEncoder` 编码。
- 登录：`PasswordService.matches()` 校验；登录成功后签发令牌（见 4.2）。
- RBAC：`lxy_user.role`（`ROLE_USER` / `ROLE_ADMIN`），后台接口用 URL 规则 + 类级
  `@PreAuthorize("hasRole('ADMIN')")` 形成**纵深防御**。
- `PasswordService` 内置**摘要格式升级**能力：库里的摘要若不是 BCrypt 格式，
  在用户登录校验通过的那一刻顺手换成 BCrypt。

**取舍**
- 为什么不是"一次性全量改造密码"：摘要单向不可逆，无法离线转换。
  登录时升级（用户无感知、不用重置密码）比强制全员改密码的体验和风险都好得多。
- BCrypt 摘要固定 60 字符，所以 `password` 列必须是 `varchar(100)` ——
  不扩列会在写入时报 `Data too long`（迁移脚本 `docs/migration_phase1.sql` 里有）。

### 4.2 认证与授权

**要解决的问题**：服务端渲染 + 表单架构下，如何做一套既能防 XSS 偷凭证、又能防 CSRF、
还能同时服务浏览器与 App 的认证。

**实现**
- **凭证放 HttpOnly + SameSite=Lax 的 Cookie**（`ACCESS_TOKEN` / `REFRESH_TOKEN`），
  过滤器同时支持 `Authorization: Bearer` 头，同一套认证可服务小程序/App。
- **无状态**：`SessionCreationPolicy.STATELESS`，服务端不保存登录状态，多实例部署不需要 session 共享。
  注意边界：HttpSession 仍被用来承载**展示数据**（用户名、地区、学校）和一次性提示，
  这些不参与任何授权判断 —— **授权看 JWT，展示看 session**。
- **CSRF 双链路**：表单用隐藏域（`th:action` 自动注入 + 写死兜底两种写法都支持），
  ajax 由 `static/shop/assets/JS/csrf.js` 读 `XSRF-TOKEN` Cookie 注入 `X-XSRF-TOKEN` 请求头。
- **401 / 403 分流**：`RestAuthenticationEntryPoint`（未认证）与 `RestAccessDeniedHandler`（无权限）
  分别处理；两者再按「接口 vs 页面」分别返回统一响应体 JSON 或 302 跳登录页。

**取舍**
- 为什么不用 localStorage + `Authorization` 头：页面跳转（`<a href>`）与表单提交由浏览器发起，
  **无法手动附加请求头**，只有 Cookie 会自动携带；要改用请求头方案就得把 25 个服务端渲染页面
  全改成前端路由，收益低风险高。
- 用了 Cookie 就**必须防 CSRF**（浏览器会自动携带凭证，这正是 CSRF 的成立条件）。
  只有「token 放请求头」的方案才天然免疫 —— 这一条要能主动讲出来。

### 4.3 令牌生命周期（可撤销的无状态认证）

**要解决的问题**：无状态 JWT 一旦签发，在有效期内**无法撤回**。
「登出只清 Cookie」是最常见的错误实现 —— 看起来登出了，任何人拿着那个 token 还能继续用。

**实现**

| | access token | refresh token |
|---|---|---|
| 形态 | JWT（签名自证，含 `jti`） | 随机 UUID + Redis 存储 |
| 寿命 | 30 分钟 | 7 天 |
| 为什么这样选 | 每请求校验 → 必须**无状态**，不查存储才能横向扩展 | 长期凭证、泄露影响大 → 必须**可即时吊销** |

- **黑名单** `shop:killed:{jti}`，**TTL = 该令牌的剩余寿命**：
  令牌自然过期，黑名单条目自动消失 —— 不需要清理任务，也不会无限堆积。
- **refresh 轮换 + 重放检测**：每次刷新换新令牌、旧的标记为已用；旧令牌再次出现 ⇒ 说明泄露 ⇒
  撤销该用户**全部** refresh token（`shop:user:{uid}:rts` 集合维护）。
- **60 秒并发宽限**：客户端并发刷新也会"重复提交"，所以轮换时留 60 秒窗口，
  窗口内重复提交返回**同一个**新令牌，不把正常用户误判成攻击者。
- **登出三件事一起做**：作废 refresh、把 access 的 `jti` 写进黑名单、清 Cookie。
- **access 缺失但 refresh 有效时透明续期**：过滤器用 refresh 换新的 access 并写回 Cookie，
  用户无感知。

**取舍**：Redis 故障时全部操作 try-catch 降级（记 WARN、返回安全默认值）。
代价是抖动期间无法提前撤销令牌；收益是不会演变成「Redis 抖动 → 全站 401」。

### 4.4 商品发布与图片上传

**要解决的问题**：用户发布二手商品要传图；图片的存储位置、命名、清理、防路径穿越不能散落在业务代码里。

**实现**
- 独立 Maven 工程 `oss-spring-boot-starter`：`@AutoConfiguration` + Boot 3 的
  `AutoConfiguration.Imports` SPI + `@ConditionalOnProperty` / `@ConditionalOnMissingBean`。
- 业务侧只依赖接口：`fileStorage.store(file, key)` 拿回可访问 URL、`fileStorage.delete(url)` 清理旧图。
  「拼路径、建目录、防穿越、删文件」全在实现类里。
- 换存储介质（本地磁盘 → MinIO/OSS）只改 `oss.type` 与对应参数，**业务代码零改动**。
- 编辑商品时校验归属：只有发布者本人能改，越权直接 403（防水平越权）。
- 发布成功后把新商品 id 告知布隆过滤器（见 4.11，否则新商品会被判成"不存在"）。

**取舍**：starter 本身必须是普通 jar、**不能**被 boot 插件打成 fat jar，
否则消费方拿到 `BOOT-INF` 结构、类加载不到 —— 验证脚本里专门断言了这一点。

### 4.5 个性化搜索（搜索历史权重）

**要解决的问题**：二手平台的搜索如果只是 `like %关键词%`，结果既不分主次也不会随用户习惯变化。

**实现**（`SearchProcess` + `DailyUpdateTask` + `lxy_user.search` 字段）

1. **每个用户的搜索历史是一个权重字典**，以文本形式存在 `lxy_user.search`：
   `关键词:[当前权重, 已衰减天数, 初始权重];关键词2:[...]`，
   由 `stringToDict` / `dictToString` 负责序列化与反序列化。
2. **按权重比例分配结果位**：一次返回 100 个结果，某个关键词分到的名额是
   `100 × 该词权重 / 权重总和`；按权重从高到低逐词查询、追加结果。
3. **去重与排序**：剔除已在购物车里的商品（避免"推给你你已经在看的"），
   最后按浏览量降序排列。
4. **不足则补齐**：名额没填满时，把全量商品列表打乱后顺序补齐，
   保证「冷启动用户（没有搜索历史）」也能拿到满满一屏。
5. **权重按天衰减的定时任务**（`@Scheduled(cron = "0 0 0 * * ?")`）：
   每天凌晨对每个用户的搜索词做一次衰减

   ```
   value[0]（当前权重） += f'(天数+1) × value[2]（初始权重）
   f(x) = e^(-(x/10)^4)   →  f' 是它的中心差分导数
   ```

   天数越大导数越趋近 0，于是**衰减幅度随天数收敛**：近期的搜索词权重掉得快、
   很久以前的词会以很小的幅度长期保留（长尾），权重低于 0.1 才淘汰。
   每个用户处理完只写库一次（不是每个关键词写一次）。

**取舍**：用「搜索历史兴趣」而不是「协同过滤」—— 数据量只有 3659 件商品、30 个用户，
协同过滤无从谈起；而权重衰减的数学形式能讲清楚「为什么这样设计」，
比硬套一个推荐算法更实在。

### 4.6 商品列表与筛选

**要解决的问题**：首页要同时支持「最新」「同城」「同校」三种浏览方式，并且要区分匿名/已登录
（登录后排除自己发布的商品、走个性化排序）。

**实现**：`ShopIndexController` 三个入口 + `ProductMapper` 的手写 JOIN SQL：

```sql
-- 列表：商品 + 卖家（JOIN lxy_user 取 username 作为 sellerName），过滤已售出
select p.id, p.name, u.username as sellerName, p.description, p.price,
       p.created_at as createdAt, p.img_store_path as imgPath, p.view_count as viewCount
  from lxy_product p join lxy_user u on p.uid = u.id
 where p.sold_time is null and u.username != #{username}
```

- 同城：`u.province = ? and u.city = ? and u.area = ?`；同校：`u.school = ?`（都走索引，见 6 章）。
- 首页只取前 100 条（`subList(0, Math.min(size, 100))`）—— 数据量小的场合够用，
  真要分页应该用 `order by id desc limit N` 走主键。

### 4.7 商品详情与浏览量

**要解决的问题**：商品详情是最热的读路径（带 JOIN 的查询 + 浏览量更新），
既要快，又要保证浏览量在并发下不丢。

**实现**
- 商品信息走缓存（`ProductCacheService.getById`，Cache-Aside，详见 4.11）。
- 浏览量走数据库的**原子自增**，一条语句完成：

  ```sql
  update lxy_product set view_count = view_count + 1 where id = #{id}
  ```

**取舍**：浏览量**不进缓存**。它每次访问都变，放进缓存就得每次删缓存，缓存等于自毁；
所以缓存只放低频变更的商品信息，页面展示「缓存值 + 1」，库里是精确值。
若要求绝对精确，正确做法是 Redis `INCR` 计数 + 定时回写数据库（后续阶段可以做）。
原来「查出来 +1 再写回」是读-改-写，并发下两个请求都读到 100、都写回 101，
**两次浏览只记一次**（丢失更新），所以这里必须用数据库端的原子自增。

### 4.8 购物车

**要解决的问题**：购物车要能增删、结算时只清理自己的、下架/售出的商品要从所有人的购物车里消失。

**实现**
- `cart_item` 关联表（`user_id` + `product_id` + `quantity` + `picked`），
  `uk_user_product` 唯一索引天然防止重复加购。
- `CartService` 提供 `addToCart` / `removeFromCart(userId, productId)` /
  `removeProductFromAllCarts(productId)` 三个语义清晰的方法。
- 结算（`POST /shop/buy`）：只从**当前用户**的购物车移除商品，不创建订单。
- 下单成功（`POST /shop/buySuccess`）：商品已售出 → 调用 `removeProductFromAllCarts`，
  把所有用户购物车里的这件商品清掉（一件二手商品只有一个买家）。

**取舍**：早期的购物车是「一个字段里用逗号拼接商品 id」的写法 ——
装不下数量、没法建索引、每次操作全表扫描、并发写直接丢失更新。
改成关联表后这些都不存在了；兼容期保留旧字段，读路径一条条切换，切完再谈弃用。

### 4.9 下单与库存（并发不超卖）

**要解决的问题**：两个人同时买同一件商品，不能两个人都成功。这是本项目最核心的正确性问题。

**实现**：把「检查」与「扣减」压进**一条**带条件的 UPDATE

```sql
-- 乐观锁（默认）：version 条件由 MyBatis-Plus 乐观锁插件追加
update lxy_product set stock = stock - 1, version = version + 1
 where id = ? and version = ? and stock >= 1     -- 影响行数 0 = 被别人抢先了
```

- **两种策略可切换**（策略模式 + `@ConditionalOnProperty`）：
  `optimistic`（读版本 → 条件更新 → 冲突重试）/ `pessimistic`（`SELECT ... FOR UPDATE` 锁行后操作）。
  业务代码零改动，而且**同一套并发断言在两种策略下都跑**。
- **重试必须在事务外面**：外层 `PurchaseService` 不加事务、负责重试循环；
  内层 `PurchaseTxService` 加 `@Transactional` 干活，冲突时抛 `StockConflictException` 强制回滚。
  两者是**不同的 Bean**，因为同类内部调用不走代理、`@Transactional` 会失效。
- **扣库存 + 建订单 + 标记售出在同一个事务里**，避免出现「订单建了但商品还在售」的半完成状态。
- **抢购失败返回 409**：这是「资源状态已变更」的预期内失败，不该混进服务端错误率。

**为什么重试必须放在事务外**（面试高频追问）：MySQL 默认 REPEATABLE READ，
事务内第一次快照读之后，后续普通 SELECT 复用同一份快照。
「读版本 → 条件更新失败 → 再读版本」若全在一个事务里，第二次读到的还是旧版本号，必然再次失败 ——
重试纯属空转。所以每轮重试必须是一个新事务（拿到新快照）。
（顺带解释：UPDATE 是当前读、能看见最新已提交版本；普通 SELECT 是快照读。这点常被追问。）

### 4.10 订单（状态机 + 明细快照）

**要解决的问题**：订单状态流转要合法、并发下不能被重复推进、历史订单不能被商品后续改名改价污染。

**实现**
- **状态机 = 枚举 + 显式流转表**：

  ```
  PENDING_PAY(待支付) ─► PAID(等待发货) ─► SHIPPED(已发货) ─► COMPLETED(订单已完成)
          │                   │
          └───────────────────┴──► CANCELLED(已取消)          COMPLETED / CANCELLED 为终态
  ```

  枚举直接携带旧的中文文案（`PAID("等待发货")`），兼容是**投影**而不是第二份真相；
  合法流转用 `Map<Status, Set<Status>>` 显式声明；推进时用条件 UPDATE：

  ```sql
  update lxy_order set status = ?, `condition` = ? where id = ? and status = ?
  ```

  把校验放进 `WHERE`，**一条语句同时得到「流转合法性」和「并发保护」**，影响行数为 0 = 你来晚了。
- **明细快照**：`order_item` 在下单瞬间复制商品名与价格。金额用 `BigDecimal` + `decimal(10,2)`，
  不用 `double`。
- **对外只暴露业务订单号**：`SO + yyyyMMddHHmmssSSS + 4 位随机`，
  不暴露自增 id（自增 id 会泄漏业务量、便于被遍历探测），唯一性由 `uk_order_no` 兜底。
- **状态推进做归属校验**：只有该订单的买家或卖家能推进，其他人 403。

**取舍**：`Order` 实体里有 `productName` / `sellerName` 这类 JOIN 出来的展示字段，
让 ORM 拿它去写库会试图持久化不存在的列。所以拆成写模型 `OrderPO`、
展示模型 `VO`、入参 `DTO` —— 这也正好是面试的标准词汇（PO/DTO/VO）。

### 4.11 缓存体系

**要解决的问题**：商品详情与分类是典型的热点读，每次都打库；而缓存要面对穿透、击穿、雪崩、
以及"缓存和数据库谁对"这四个问题。

**实现**（`shop/common/cache/` 下 8 个类）

| 组件 | 职责 |
|---|---|
| `CacheKeys` | key 规范：`shop:cache:product:{id}`、`shop:cache:category:enabled`、`shop:bloom:product`、`shop:lock:*` |
| `CacheTtl` | TTL 策略：商品 30 分钟 + 随机 0~5 分钟；分类 2 小时 + 随机 0~10 分钟；空值 60 秒；重建锁 3 秒 |
| `ProductCacheVO` | 缓存专用值对象（为什么不直接缓存实体见第 5 章） |
| `ProductCacheService` | Cache-Aside 主流程：布隆预检 → 查缓存 → 未命中抢锁重建 → 回写 |
| `ProductBloomFilter` | 商品 id 布隆过滤器（预期 2 万元素 / 1% 误判率），启动全量装载 + 新增时告知 |
| `CacheInvalidator` | 一致性：先更库再删缓存 + 500ms 后延迟双删 |
| `DistributedLock` | Redisson `RLock` 封装（看门狗续期 + Redis 不可用时降级） |
| `CacheStats` | 命中/未命中/空值命中/查库次数 + 耗时累计，对外由 `/shop/api/cache/stats` 暴露 |

**读缓存的完整顺序**（Cache-Aside）

```
① 布隆过滤器：说「一定不存在」→ 直接返回 404，不碰 Redis、不碰数据库
② Redis GET：命中 → 返回；命中空值哨兵 __NULL__ → 返回 404
③ 未命中 → setIfAbsent 抢重建锁（SET NX PX 3000）
     抢到 → 再查一次缓存（双重检查）→ 查库 → 回写缓存
     没抢到 → 短暂等待（20ms 起、上限 400ms）→ 读缓存；超时则降级直接查库
```

**写链路的顺序**（一致性）

```
update DB → delete cache → (+500ms) delete cache again
```

**为什么先更库再删缓存、而且删两次**：详见第 5 章第 4 条，这里只给结论 ——
先删缓存会在「缓存已空 + 库还是旧值」的窗口里让读请求把旧值回填；
延迟双删是为了清掉「读请求在删缓存前读到旧值、删缓存后才写回」的那份脏数据。

**对外接口**（`ShopProductApiController`）

| 接口 | 说明 |
|---|---|
| `GET /shop/api/products/{id}` | 商品详情 JSON（走缓存） |
| `GET /shop/api/products/{id}?bypassCache=true` | 同上去缓存直查库（对照实验 / 缓存故障时应急） |
| `GET /shop/api/cache/stats` | 命中率、平均耗时、真正查库次数、布隆过滤器是否可用 |

**实测**：命中率 **98.1%**；命中缓存 **778µs** vs 直查库 **1816µs**；
单 key 冷启动 + 20 并发只查 **1 次**库；10 次无效 id 请求 **0 次**查库。

### 4.12 分布式锁

**要解决的问题**：用户在"确认购买"上连点两下、浏览器重发请求、网络重试 ——
同一个用户对同一件商品会同时进来两个写请求。

**实现**（`DistributedLock` + `PurchaseService`）

```java
distributedLock.runWithLock(CacheKeys.userOrderLock(buyerId, productId),
        0L,    // 等待 0：抢不到立刻返回 409，不排队
        -1L,   // 租期 -1 → 交给看门狗自动续期
        () -> doPurchaseWithRetry(productId, sellerId, buyerId));
```

- **粒度是「用户 + 商品」**：不同买家之间不需要排队（那是数据库条件更新该干的活），
  只有同一个人的重复提交需要被挡住。
- **等待时间 0**：排队会让两次请求都成功，正好制造重复订单；直接返回 409 才是想要的行为。
- **租期 -1（看门狗）**：这次购买包含「重试 + 建单 + 标记售出」，耗时不可预测；
  写死租期会在业务没跑完时锁就过期，第二个请求趁虚而入。
- **锁必须在事务外面**：拿锁 → 开事务 → 提交 → 释放锁，中间隔着完整重试循环。

**为什么已经有条件更新还要锁**：两者管的事不同 —— 条件更新保证**数据正确**（不超卖），
锁保证**同一个人的重复请求不进写链路**（否则第二个请求会一路跑到扣减失败，
返回一个用户自己造成的莫名失败）。

**实测**：同一用户 8 个并发下单 → 成功 1 笔、其余 409、订单数 1。

### 4.13 后台管理

**要解决的问题**：管理员要能管用户、商品、订单，且后台入口本身要受保护。

**实现**：`/admin/**` 需要 `ROLE_ADMIN`（`@PreAuthorize` + URL 规则），
页面包括控制台、用户管理（增删改）、商品管理（列表/新增/删除）、订单管理、改管理员密码。
后台的写操作同样走统一响应体与全局异常处理。

**边界**：后台的**分类管理**界面还没做（表与实体已就位），
所以分类缓存目前只靠 TTL 兜底、没有主动失效入口 —— 一旦加了分类写接口，
必须在写完库之后删除 `shop:cache:category:enabled`。

---

## 5. 关键技术难点

> 这一章是可以直接拿来讲的"硬难点"：问题 → 方案 → 实现 → 为什么 → 实测。

### 难点 1：并发超卖 —— 检查与扣减必须是同一条语句

**问题**：`if (商品未售出) { 标记售出 }` 是两次读，中间有窗口，两个人同时通过检查 → 两笔订单。

**方案与实现**：条件 UPDATE，把版本与库存条件写进 `WHERE`，用**影响行数**判断输赢。

```sql
update lxy_product set stock = stock - 1, version = version + 1
 where id = ? and version = ? and stock >= 1
```

**为什么**：一条语句由数据库保证原子性；乐观锁不阻塞、冲突时重试；提供悲观锁版本做对照。
**实测**：两种策略各跑 8 并发（各阶段共四轮），每次都是「成功 1 单、订单数 1、库存 0、状态 SOLD」。

### 难点 2：乐观锁重试必须放在事务外（否则是空转）

**问题**：重试写了却一直失败，日志里同一个版本号反复出现。

**方案与实现**：拆两个 Bean —— 外层不加事务、拥有重试循环；内层 `@Transactional` 干活，
冲突时抛异常强制回滚，让下一次重试拿到新事务、新快照。

**为什么**：REPEATABLE READ 下事务内普通 SELECT 复用同一份快照，读到的永远是旧版本号。
**顺带的价值**：这也是解释「同类内部调用不走代理，`@Transactional` 失效」的最佳现场。

### 难点 3：无状态 JWT 的撤销（黑名单 TTL + refresh 轮换 + 重放检测）

**问题**：JWT 有效期内在服务端收不回来；登出只清 Cookie 是假登出。

**方案与实现**：access 短寿命（30 分钟、无状态）+ refresh 短期存储（随机串、可即时吊销）；
登出写黑名单（TTL = 令牌剩余寿命）+ 作废 refresh + 清 Cookie；
refresh 一次性轮换 + 重放检测 + 60 秒并发宽限。

**实测**：登出后重放登出前的 access token → 401；超宽限期的 refresh 重放 →
该用户全部 refresh token 被撤销 + WARN 告警；access 缺失时透明换发新令牌后请求仍 200。

### 难点 4：缓存一致性 —— 先更库再删缓存 + 延迟双删

**问题**：数据库改了，缓存里还是旧值；或者更糟：旧值被读请求回填进缓存。

**方案与实现**

```
写：update DB → delete cache → (+500ms) delete cache again
```

- **为什么删缓存而不是更新缓存**：更新要在业务里算出"新值"，并发写还会乱序
  （A 后写库、B 先写缓存 ⇒ 缓存留下旧值）；删除是**幂等**的，删错了最多让下一次读查一次库。
- **为什么必须先更库再删缓存**：反过来会有一段「缓存已空 + 库还是旧值」的窗口，
  期间的读请求会把**旧值**回填进缓存，并一直留到 TTL 到期。
- **延迟双删挡的竞态**：
  ```
  T1 读：查缓存未命中 → 查库（拿到旧值）         ← 库还没改
  T2 写：更新库 → 删除缓存                      ← 缓存被清掉
  T1 读：把刚读到的旧值写回缓存                 ← 旧值回来了
  ```
  第二次延迟删除把这份脏数据清掉，延迟略大于一次「查库 + 回写」的耗时。
- **说清它治不好什么**：双删只把不一致窗口从「TTL 那么长」压到毫秒级，
  并不消灭并发写乱序；强一致要靠订阅 binlog（Canal）或给缓存值加版本号。
- **调用时机是硬约束**：必须在数据库**提交之后**调用，否则读请求会把「旧库 + 空缓存」的旧值回填。

### 难点 5：缓存的三种失效场景，各自的解法与代价

| 场景 | 现象 | 实现 | 代价 / 注意事项 |
|---|---|---|---|
| **穿透** | 反复查不存在的 id，每次都打库；恶意遍历还会用垃圾 key 撑爆 Redis | ① 布隆过滤器（说"一定没有"时不碰 Redis 也不碰库）② 空值哨兵 `__NULL__`，TTL 只有 60 秒 | 过滤器**不能删元素**；外部直接写库会让它产生假阴性 → 真数据 404。所以：应用内新增必须 `add`、启动全量重装载、写入路径必须可控 |
| **击穿** | 热点 key 过期的瞬间，N 个并发同时查库 | `setIfAbsent` 抢重建锁（`SET NX PX` 一条命令完成）+ 拿锁后**双重检查**；没抢到的等待 20ms→80ms 退避，超时 400ms 降级查库 | 重新查缓存不能省：否则排队的线程会依次拿到锁、依次查库，锁退化成串行排队。等待有上限：目标是"把 N 次查库压成 1 次"，不是"保证谁都不查库" |
| **雪崩** | 大批 key 同时过期，峰值全砸到库 | TTL = 基础值 + 随机抖动（30~35 分钟），把过期时刻摊开 | 抖动只加不减，保证"最短存活时间"可预期 |

**实测**：不存在的 id → 404 且不查库、不写缓存；被删商品（布隆说可能有）→ 404 且写入 60 秒空值哨兵，
之后 10 次请求 0 次查库；20 并发打冷 key → 只查 1 次库。

### 难点 6：缓存该缓存什么 —— 序列化的三个坑与"缓存专用 VO"

**问题**：一开始把实体直接丢进缓存，结果写成二进制乱码 / 读回来类型不对 / 时间悄悄变歪。

**方案与实现**：key 用 `StringRedisSerializer`、value 用 Jackson JSON，且缓存**专用 VO**（`ProductCacheVO`）。

三个坑（都是写完往返测试才发现的）：

| 坑 | 现象 | 解法 |
|---|---|---|
| ① 默认 mapper 不支持 `java.time` | 第一次缓存含 `LocalDateTime` 的对象直接抛 `InvalidDefinitionException` | 复用容器里的 `ObjectMapper`（已注册 `JavaTimeModule`）的 `copy()` |
| ② 传自定义 mapper 会丢掉 `@class` | 值写进去了、读回来是 `LinkedHashMap`，`(Product) cached` 直接 `ClassCastException` | 自己 `activateDefaultTyping`；并让生产与测试**共用同一个构造方法**，避免"测试测的是另一套配置" |
| ③ **直接缓存实体是有损的** | 实体的 `java.sql.Date` 经 Jackson 往返后**时分秒丢失**，且时刻被重解释（写入按 JVM 时区取日期、读回按 UTC 解析，UTC+8 上偏 8 小时）| 缓存专用 VO，日期用字符串 `"2024-05-06"` 承载，两侧完全对称 |

③ 最值得讲：它**不报错**，只是页面上的时间慢慢变歪 —— 属于最难查的一类 bug。
用专用 VO 还有额外好处：实体改名/换包不影响已写入的缓存，且缓存体积更小（只放读路径要渲染的字段）。

### 难点 7：缓存层不能成为可用性的单点（降级设计）

**问题**：引入缓存/锁之后，Redis 一挂是不是整站就不可用？

**方案与实现**

| 场景 | 行为 |
|---|---|
| Redis 不可达 | 应用**照常启动**；缓存读写退化为查库，接口可用 |
| Redisson 初始化失败 | `@Bean` 方法捕获异常返回 `null`，依赖方 `ObjectProvider` 拿到 null 后跳过锁与布隆过滤器，下单靠数据库条件更新兜底 |
| 抢缓存重建锁失败 / Redis 异常 | 按"没抢到"处理 → 等待 → 超时查库，不会把请求卡死 |
| 删缓存失败 | 只记 WARN，商品更新照常成功（等 TTL 自愈） |

**实测**：用 `--spring.data.redis.port=<死端口>` 与 `--shop.redis.redisson-enabled=false`
各起一次应用，断言「仍能启动 + 接口 200 + 诊断接口如实上报降级」。

### 难点 8：把"加了索引"变成数字，并敢于不加

**问题**：索引很容易变成"凭感觉多加几个"。数据量 3659 行时全表扫描也不慢，
真正的问题是某些查询是**每个请求都执行**的。

**方案与实现**：`index-report.py` 从代码里盘点高频 SQL（不是猜），采样真实参数值，
跑 `EXPLAIN FORMAT=JSON` + `EXPLAIN ANALYZE`，对比 `access_type` / `key` / `rows_examined`。

**实测**（详见第 6 章）：`img_store_path` 反查从**全表扫描 3628 行 → const 1 行**；
按用户名查 id 从扫 30 行 → `const`；同城/同校从两层全表扫描 → 两个索引的 `ref`。
**首页那条刻意不加**：`sold_time is null` 在 3628 行里命中 3626 行（选择性 0.06%），
加上索引优化器也不会选它，实测估算代价反而从 514 涨到 641，已撤掉。

### 难点 9：定位过的隐蔽缺陷（每一个都是真实排错过程）

| 现象 | 根因 | 怎么定位 / 怎么解决 |
|---|---|---|
| **同一页面上连续第二次写操作必然 403** | `CsrfConfigurer` 默认**追加** `CsrfAuthenticationStrategy`，无状态模式下每个请求都被判定为"新认证" → 每请求清掉并轮换 CSRF cookie，而页面上的隐藏域还是旧值 | 单次操作永远成功、只有连着做两次才复现。在 csrf 配置里把 `sessionAuthenticationStrategy` 置空；验证脚本里加"连续两次 addToCart 都必须 200"的断言锁死它 |
| session id 每个请求都在轮换 | 只配 `STATELESS` 不够，`SessionManagementFilter` 仍在链上，会话固定攻击防护每请求执行一次 | 用 `--logging.level.org.springframework.security=DEBUG` 看到 `ChangeSessionIdAuthenticationStrategy` 后定位；显式置空会话认证策略 |
| `th:action` 不注入 `_csrf` 隐藏域 | 非 Xor 的 `CsrfTokenRequestAttributeHandler` 默认不写 request 属性，`RequestDataValueProcessor` 取不到 | 显式 `setCsrfRequestAttributeName("_csrf")`，并在表单里写死隐藏域兜底 |
| 打包成 jar 后**所有页面 500**，IDE 里却全绿 | 视图名带前导斜杠 → `classpath:/templates//shop/index.html`（双斜杠）；展开目录会归一化 `//`，ZIP 条目匹配不会 | 命名规范 + 开发约定：**页面相关改动必须用打包后的 jar 验证**，并写进验证脚本 |
| MyBatis-Plus 分页插件"找不到符号" | 3.5.9 起依赖 JSqlParser 的功能（分页插件）被拆包 | 额外引入 `mybatis-plus-jsqlparser`；同时注意配置前缀从 `mybatis.*` 改为 `mybatis-plus.*`（写错**静默失效**）|
| 搜索补位偶发死循环 / 抛异常 | `while + random.nextInt(0)` 不收敛；候选项全已存在时名额永不递减 | 改为打乱后顺序补齐，循环次数有确定上界 |
| 定时任务的衰减任务写库次数过多、且语义不对 | 写库语句在 while 循环体内（N 个关键词写 N 次）；关键词全部淘汰时循环体不执行 → 空串永远不写回 | 移到循环外统一写回一次 |
| 异常被兜底吞成 500 | catch-all `@ExceptionHandler(Exception.class)` 会把"方法不支持""路径不存在"也当 500 | 显式处理 405 / 404 并放在兜底之前 —— 否则服务端错误率指标会被客户端错误灌满 |

---

## 6. 性能：从「加了索引」到数字

### 6.1 索引优化（`EXPLAIN` 前后对比，脚本可复跑）

| 查询 | 场景 | 优化前 | 优化后 |
|---|---|---|---|
| `lxy_user where username=?` | **每个请求**都按用户名查 id | 全表扫描 30 行 | **const**，`uk_username` |
| `lxy_product where img_store_path=?` | 加购 / 下单 / 改状态反查商品 | 全表扫描 **3628** 行 | **const**，`uk_img_path` |
| 同城筛选 | 同城列表 | 两层全表扫描 (30 × 3628) | `idx_location`(1) × `idx_uid_sold`(62) |
| 同校筛选 | 同校列表 | 两层全表扫描 (30 × 3628) | `idx_school`(1) × `idx_uid_sold`(62) |
| `lxy_order where product_id=?` | 订单页 / 状态推进 | 全表扫描 28 行 | **ref**，`idx_product` |
| 首页商品列表 | 首页 | 全表扫描 3628 行 | **刻意不加**：选择性 0.06%，实测估算代价 514 → 641 更贵 |

结论：**索引的价值来自选择性**。唯一索引还顺带承担了业务约束（用户名唯一、一件商品一个图片文件）。

### 6.2 缓存效果（实测）

| 指标 | 实测 | 说明 |
|---|---|---|
| 命中率 | **98.1%**（命中 105 / 未命中 2）| 应用侧累计 |
| 命中缓存 vs 直查库 | **778 µs vs 1816 µs（2.3 倍）** | 这条 SQL 本身是主键 JOIN（1.8ms），缓存省掉的是"一次点查"，倍数不会夸张 —— **能解释清楚为什么只有 2.3 倍**比刷一个好看的倍数更有说服力 |
| 单 key 冷启动 + 20 并发 | 只查 **1 次**库（命中 19 / 未命中 1）| 互斥重建生效 |
| 无效 id 重复请求 | 10 次 **0 次**查库 | 空值哨兵挡住 |
| 布隆过滤器装载 | 3659 个 id / 1.3 秒 | 假阴性窗口由"启动全量重装载"闭合 |

### 6.3 并发正确性（实测）

| 场景 | 结果 |
|---|---|
| 8 并发买同一件商品（乐观锁）| 成功 1、其余 409、订单数 1、库存 0 |
| 8 并发买同一件商品（悲观锁）| 同上 |
| 同一用户 8 并发下单（分布式锁）| 成功 1、其余 409（"请勿重复提交"）、订单数 1 |
| 20 并发打同一个冷缓存 key | 200 × 20、只查库 1 次 |

---

## 7. 质量保障：测试与验证体系

### 7.1 单元测试（96 个，应用 + 11 个 starter）

覆盖的都是**纯逻辑与边界**：状态流转合法性、TTL 抖动区间、缓存编解码往返、
统计口径、锁配置、异常映射、参数校验、JWT 解析（篡改/过期/弱密钥）、搜索工具类。

两个测试设计上的讲究：

- **编解码测试与生产共用同一个构造方法**（`RedisConfig.jsonValueSerializer`），
  避免"测试测的是另一套配置"——这恰恰是坑 ② 能漏过去的原因。
- **回归守护式断言**：`ProductCacheCodecTest` 里有一条用例专门断言
  「直接缓存实体时时间会偏移」，把"为什么必须有 VO"这个设计决策钉成可执行文档。

### 7.2 端到端验证脚本（`.hermes/verify-*.sh`，200+ 断言）

不是"跑一遍 curl"，而是一套有纪律的套件：

| 纪律 | 做法 |
|---|---|
| 自己管生命周期 | 脚本自己起 jar、用不冲突端口、等启动完成日志，跑完自己停（含端口兜底清理） |
| 造数据可逆 | 测试商品用 SQL 造（名字带 `VERIFY_*_TMP`）；要删真实行时先 `create table` 备份再删，跑完还原 |
| **断言数据回到基线** | 结束前断言商品/订单行数与开始时一致、无遗留测试商品、无孤儿明细 |
| 断言而非打印 | `chk/ok/bad/inrange/gt` 计数 PASS/FAIL，退出码非 0 即失败 |
| 并发要真并发 | N 个独立 curl 进程，每个写**自己的**输出文件再合并（并发追加同一文件会丢行） |
| 可中断可续跑 | 脚本开头清理上一轮失败留下的测试数据，保证基线可复现 |

```
Phase 0  缺陷回归 + 工程化      56 项
Phase 1  Security / JWT / RBAC  32 项
Phase 1  令牌生命周期            38 项
Phase 1  自定义 Starter          12 项
Phase 2  建模 / 迁移 / 并发 / 错误码  一套（含两种库存策略各一轮并发）
Phase 3  缓存 / 锁 / 降级 / 数据回归   76 项
```

### 7.3 开发中养成的两条硬约定

1. **页面相关改动必须用打包后的 jar 验证** —— 展开目录与 ZIP 条目对资源路径的处理不同，
   视图名前导斜杠只在 jar 里炸（见难点 9）。
2. **验证脚本先自证清白**：Phase 3 第一次跑出 28 个 FAIL，绝大多数是脚本自身的问题
   （Windows 路径、断言写错、统计口径）。先把脚手架问题清干净，剩下的才是真 bug。

---

## 8. 工程基建

| 组件 | 作用 | 一个容易被追问的点 |
|---|---|---|
| `Result<T>` | 统一响应体 `{code,message,data,success}` | 前端只需一套处理逻辑 |
| `ErrorCode` | 业务错误码枚举（2xx / 4xxx / 5xxx 分段） | 与 HTTP 状态码映射，避免"只看 HTTP 200 导致监控失真" |
| `BizException` | 预期内失败（带错误码） | 与系统异常**分级记日志**：预期内不打堆栈，系统异常才打 |
| `GlobalExceptionHandler` | 全局收敛 + 状态码映射 | 必须显式处理 405/404，否则被兜底吞成 500 |
| JSR-303 校验 | 入参校验统一到注解 | 校验失败返回 400 + 可读信息 |
| Logback | 控制台 + 滚动文件 + 错误单独归档 | 本项目 DEBUG、框架 INFO |
| `MybatisPlusConfig` | 分页插件 + 乐观锁插件 | 分页插件 3.5.9 起需要额外依赖 `mybatis-plus-jsqlparser` |
| `AutoFillMetaObjectHandler` | `created_at` / `updated_at` 自动填充 | 业务代码不再手写时间字段 |
| `oss-spring-boot-starter` | 文件存储自动配置 | 自定义 Starter 的三个注解 + SPI 文件，是"会写自动配置"的证明 |

---

## 9. 面试问答

### 9.1 项目与架构

**Q1 介绍一下这个项目。** 校园二手交易平台：用户发布/搜索/浏览闲置物品，加入购物车、下单、
订单状态流转，管理员后台管理用户/商品/订单。技术上 Service 端渲染（Thymeleaf）+ Spring Boot 3.3.5 +
MyBatis-Plus + MySQL + Redis + Redisson。数据量是 3659 件真实商品、30 个用户。

**Q2 为什么前后端不分离？** 这是服务端渲染 + 表单的业务系统，页面跳转由浏览器发起；
前端重写 25 个页面收益低。反过来这个选择也决定了认证方案必须用 Cookie（见 Q5）。

**Q3 为什么用 MyBatis-Plus？** 单表 CRUD 不再写 XML 是真实理由；
既有 XML 一行不改继续用（MP 是 MyBatis 超集）；多表 JOIN 仍手写 SQL —— 混用是正常做法。
坑：不能和 MyBatis starter 共存、配置前缀 `mybatis.*` → `mybatis-plus.*` 写错静默失效、
3.5.9 起分页插件拆包需要 `mybatis-plus-jsqlparser`。

**Q4 你的分层是怎么做的？** 新代码统一 Controller → Service → Mapper，
事务加在 Service；早期页面型 Controller 仍直连 Mapper（页面重写收益低于风险，按需迁移）。
新代码用构造器注入（依赖 `final`、缺依赖启动即失败、单测能直接 new），旧代码是字段注入。

### 9.2 安全

**Q5 为什么 JWT 放 Cookie 而不是 localStorage？** 页面跳转与表单提交无法附加请求头，
只有 Cookie 会自动携带；Cookie 设 `HttpOnly`（防 XSS 偷）、`SameSite=Lax`（跨站不带）、
`Secure`（生产 HTTPS）。同时过滤器支持 `Authorization` 头，兼顾 App。

**Q6 为什么用了 JWT 还要防 CSRF？** 因为凭证在 Cookie 里，浏览器会自动携带 —— 这正是 CSRF 的成立条件。
只有"token 放请求头"的方案才天然免疫。

**Q7 JWT 撤不回来怎么办？** 短寿命 access（30 分钟，无状态）+ Redis 里的 refresh（随机串，可即时吊销）；
登出三步走；黑名单 TTL = 令牌剩余寿命，所以不需要清理任务。

**Q8 重放检测会不会误伤正常用户？** 会 —— 客户端并发刷新也重复提交。所以留 60 秒宽限窗口，
窗口内返回同一个新令牌。「检测攻击」与「别把用户踢下线」必须同时考虑。

**Q9 密码怎么存的？管理员和普通用户怎么区分？** BCrypt（自带随机盐 + 慢哈希），
`PasswordService` 支持把非 BCrypt 的历史摘要**在用户登录通过时顺手升级**（摘要单向不可逆，
无法离线批量转换）；角色用 `role` 字段 + RBAC，后台接口 URL 规则与 `@PreAuthorize` 双重把关。

### 9.3 并发与数据库

**Q10 怎么防止超卖？** 条件 UPDATE（版本 + 库存条件写进 `WHERE`），用影响行数判断输赢；
扣库存 + 建单 + 标记售出在同一事务；失败返回 409。乐观锁/悲观锁两套策略可切换。
**Q11（追问）那重试呢？** 必须放在事务外 —— REPEATABLE READ 下事务内复用同一份快照，
事务内重试读到的还是旧版本号，纯空转。所以外层 Bean 管循环、内层 Bean 管事务。
**Q12 为什么需要分布式锁，数据库条件更新不够吗？** 两者职责不同：条件更新保证不超卖；
锁保证**同一个用户的重复请求不进写链路**（否则用户收到一个自己造成的莫名失败）。
粒度选"用户 + 商品"，等待时间 0（不排队，否则两次都成功），租期交给 Redisson 看门狗续期，
且加在事务外面。
**Q13 订单状态怎么保证合法流转和并发安全？** 枚举 + 显式流转表 +
`update ... where id = ? and status = ?`，一条语句同时拿到校验与并发保护，影响行数 0 = 你来晚了。
**Q14 订单为什么要有明细表？** 订单是历史凭证，明细在下单时**快照**商品名与价格，
否则卖家改价改名会污染历史。金额用 `decimal(10,2)` + `BigDecimal`。

### 9.4 缓存

**Q15 穿透/击穿/雪崩分别怎么解决？** 见难点 5，注意把每种的**代价**也说出来
（布隆不能删元素、外部写库会假阴性；击穿重查缓存不能省、等待要有上限；抖动只加不减）。
**Q16 缓存和数据库怎么保持一致？** 先更库再删缓存 + 延迟双删；说清"为什么删不是更新"、
"为什么必须先更库"、以及"双删只把窗口压到毫秒级，强一致要靠 binlog 订阅"。
**Q17 为什么缓存 VO 而不是实体？** 三个坑：`java.time` 需要 `JavaTimeModule`、
自定义 mapper 会丢 `@class`（读回来变 `LinkedHashMap`）、**实体里的 `java.sql.Date` 往返会丢时分秒且时区偏移**。
**Q18 你的命中率怎么统计的？** 应用侧埋点（命中/空值命中/未命中/查库次数 + 耗时累计）+
Redis `INFO stats` 双视角；对外暴露只读诊断接口。**顺带讲一个坑**：
"先记未命中、重建后再记命中"会让一个请求同时进分子和分母（20 个请求 19 命中，报表显示 48%），
所以改成"一次请求只记一种最终结果"，并在脚本里断言 `命中 + 未命中 == 请求数`。
**Q19 为什么浏览量不做缓存？** 每次访问都变，放进缓存就得每次删缓存 —— 缓存等于自毁。
库里用原子自增，页面展示允许偏小；要精确就用 Redis `INCR` + 定时回写。
**Q20 Redis 挂了会怎样？** 应用照常启动，缓存退化为查库，锁与布隆过滤器跳过、
下单靠数据库兜底；删缓存失败只记 WARN。实测覆盖。

### 9.5 搜索与业务

**Q21 搜索是怎么实现的？** 每个用户有一份搜索词权重字典（关键词:[权重, 衰减天数, 初始权重]），
按权重比例分配 100 个结果位，逐词 `like` 查询、排除购物车里的商品、按浏览量排序、
不足则打乱补齐；每天凌晨定时任务按 `e^(-(x/10)^4)` 的导数做权重衰减，
久远的词衰减得越来越慢（长尾），低于 0.1 淘汰。
**Q22 为什么不做推荐算法/ES？** 3659 件商品、30 个用户的数据量，协同过滤和 ES 都是过度设计；
权重衰减这套能讲清设计动因，比"套了个算法"更实在。ES 只做口头准备（倒排索引、深分页）。

### 9.6 工程与质量

**Q23 你怎么保证改动没把别的功能改坏？** 单测覆盖纯逻辑；每个阶段一个端到端脚本，
且跑之前所有阶段的脚本；断言数据回到基线；页面/打包相关必须用 jar 验证。
**Q24 你项目里最难定位的 bug 是哪个？** 见难点 9：
「同一页面连续第二次写操作 403」（CSRF 策略每请求轮换 token，单次操作永远成功）、
「打成 jar 后所有页面 500」（视图名前导斜杠，IDE 里全绿）—— 两个都改变了我的验证习惯。
**Q25 如果要你把这个项目扩到 10 倍数据量，你会先做什么？**
先给列表页做分页（现在首页取前 100 条是硬编码）；商品列表/搜索接入缓存或搜索引擎；
浏览量改用 Redis 计数 + 定时回写；上传图片外置到对象存储；订单与状态推进做读写分离或分片键设计。

---

## 10. 简历话术

> **校园二手交易平台（个人项目）** — Java 17 / Spring Boot 3.3.5 / Spring Security 6 / MyBatis-Plus / MySQL 8 / Redis / Redisson
>
> - 独立实现校园二手交易全链路：发布（图片上传）→ 个性化搜索（搜索词权重 + 每日衰减）→
>   购物车 → 下单 → 订单状态机（枚举 + 流转表 + 明细快照）→ 个人中心 → 后台管理；
>   支撑 3659 件商品 / 26 个页面 / 55 个接口。
> - 认证授权：Spring Security 6 + JWT（HttpOnly Cookie）+ RBAC + BCrypt + CSRF 双链路；
>   实现令牌生命周期（30 分钟 access + Redis refresh 轮换 / 重放检测 / 60 秒并发宽限 / 登出即撤销），
>   并支持历史密码摘要**登录时透明升级**为 BCrypt。
> - 并发正确性：用**版本条件更新**把"检查 + 扣减"压成一条 SQL 解决超卖，
>   重试放在事务外（规避 REPEATABLE READ 快照导致的重试空转），提供乐观锁/悲观锁双策略切换；
>   抢购失败返回 409；8 并发下稳定"成功 1 单"。
> - 缓存体系：Redis Cache-Aside（**专用缓存 VO** 规避 `java.sql.Date` 序列化失真）+ 三大问题治理
>   —— 穿透（布隆过滤器 + 60 秒空值哨兵）、击穿（互斥锁重建 + 双重检查 + 超时降级）、
>   雪崩（TTL 随机打散）；一致性用"先更库再删缓存 + 延迟双删"。命中率 **98.1%**、
>   命中缓存 778µs vs 直查库 1816µs；Redis 不可用时应用**照常启动**（降级路径有实测）。
> - 用 **Redisson 分布式锁**解决重复下单（8 并发仅 1 笔订单，看门狗自动续期）；
>   索引优化使"按图片路径反查商品"从全表扫描 **3628 行降到 const 1 行**，
>   并对一条低选择性索引给出了**刻意不加**的实测论证。
> - 交付 **96 个单元测试 + 6 个可复跑端到端脚本（200+ 断言）**：
>   脚本自己起停服务、造可逆测试数据、跑完断言数据回到基线。

---

## 11. 运行与部署

```bash
# 环境：JDK 17+、MySQL 8（库 shop）、Redis 5；本机若没装 Maven CLI 用封装脚本
bash .hermes/mvn.sh test                    # 单元测试 96 个
bash .hermes/build-all.sh                   # 先装 starter，再打包应用
java -jar target/springboot3-1.0-SNAPSHOT.jar --server.port=18080

# 初始化数据库
mysql -uroot -proot shop < docs/schema.sql          # 基线结构
mysql -uroot -proot shop < docs/schema_v2.sql       # 领域建模升级（可重复执行）
mysql -uroot -proot shop < docs/migration_phase1.sql # RBAC / 密码列扩容

# 端到端验证（每个阶段一个，可反复复跑）
bash .hermes/verify-phase0.sh   # 56 项
bash .hermes/verify-phase1.sh   # 32 项
bash .hermes/verify-token-lifecycle.sh  # 38 项
bash .hermes/verify-starter.sh  # 12 项
bash .hermes/verify-phase2.sh   # 建模/迁移/并发/错误码
bash .hermes/verify-phase3.sh   # 76 项
```

账号：普通用户 `lisi/123456`；管理员 `admin/123`。
Redis key 都在 db 3 且带 `shop:` 前缀，可直接观察：

```bash
"/c/Program Files/Redis/redis-cli.exe" -n 3 keys 'shop:cache:*'
"/c/Program Files/Redis/redis-cli.exe" -n 3 --raw get shop:cache:product:1   # 可读 JSON
```

---

## 12. 取舍与后续演进

### 12.1 明确没有做、以及为什么（被问到时的标准答法）

| 没做 | 答法 |
|---|---|
| 分库分表 | 「3659 条数据不需要分表。我知道分片键怎么选、跨片分页怎么处理，但没有为了简历去分」|
| 分布式事务（Seata）| 「能讲清 2PC / TCC / Saga / 本地消息表的代价；本项目是单体 + 本地事务，够用」|
| 微服务拆分 | 「为了微服务而微服务是减分项。单体 + 中间件才是这个体量的正确答案」|
| Elasticsearch | 「数据量不需要，搜索能力我用自己的权重算法实现；ES 只做口头准备」|
| 缓存商品列表 / 搜索 / 购物车 | 「它们带用户与关键词维度，命中率低；一旦缓存还要处理『A 看到 B 的购物车』这类越权。缓存粒度按『是否与用户相关 + 变更频率』切，不是读多就缓存」|
| 缓存重建锁的「值比对 + Lua 解锁」 | 「我接受了妥协：最坏后果是多查一次库（性能问题，不是正确性）。但下单锁不允许妥协 —— 说清哪里能妥协比到处套同一个锁更值钱」|
| JVM 调优实验 | 「知道堆分区、有哪些 GC、怎么用 jstat 看即可，项目里不做实验」|

### 12.2 后续演进路线（按性价比排序）

1. **订单超时与异步下单**：RabbitMQ 延迟消息 + `@Scheduled` 扫表兜底（两条都写，讲清各自的失效场景），
   消费幂等由唯一索引兜底。
2. **秒杀能力**：Redis + Lua 原子预减库存（「一人一单」判重放在同一个脚本里保证原子性）、
   异步下单、数据库唯一索引兜底。
3. **接口文档与可观测性**：Knife4j、AOP 统一日志（记录请求参数与耗时）、
   `@RateLimit` 注解（AOP + Redis 滑动窗口）。
4. **支付与售后**：接入 `payment` / `review` / `address` 表，做模拟支付回调（验签 + 幂等）、评价、收货地址。
5. **部署交付**：多阶段构建的 `Dockerfile` + `docker-compose`（MySQL / Redis / RabbitMQ / 应用一键起）、
   Nginx 反向代理、3 个接口的 QPS 与耗时数字。上传目录外置到对象存储。

### 12.3 已知遗留（被问到要能答）

| 项 | 说明 |
|---|---|
| 首页商品列表取前 100 条 | 数据量小时够用；正解是分页（`order by id desc limit N` 走主键）|
| 上传目录在 `src/main/resources` 下 | 运行时写源码目录会被重新编译覆盖，应外置（Starter 支持改配置，业务零改动）|
| 文件名用「当前最大 id + 1」生成 | 典型的 ID 生成竞态，应换雪花算法或 UUID |
| `spring-boot-starter-websocket` 零引用 | 原为 IM 功能预留，已明确不接入，可移除（**依赖审计**本身是值得讲的习惯）|
| 单实例验证 | 分布式锁与击穿锁在单实例下测的，多实例竞争行为未实测 |
| 未做压测 | 现有性能数字是应用侧埋点平均值；QPS / P99 需要压测工具（后续阶段）|
