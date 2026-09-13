# 校园二手交易平台

一个面向高校的二手物品交易平台。用户在校园内发布、浏览、搜索闲置物品并完成交易，
平台提供购物车、下单、订单流转、个人中心与后台管理能力。

> **项目定位：** 从课程级 CRUD 项目起步，正在按 [docs/ROADMAP.md](docs/ROADMAP.md)
> 改造为覆盖「高并发 / 缓存 / 消息队列 / 分布式 / 搜索 / 微服务」全链路的生产级电商平台。
> 各阶段进度见 ROADMAP 顶部进度表。

---

## 技术栈

| 层次 | 技术 |
|---|---|
| 语言 / 构建 | Java 17（源码级别）、Maven |
| 框架 | Spring Boot 3.3.5、Spring MVC、MyBatis-Plus 3.5.9（Phase 2 起取代原生 MyBatis）|
| 认证授权 | Spring Security 6 + JWT（jjwt 0.12.6，HttpOnly Cookie）、BCrypt、RBAC |
| 视图 | Thymeleaf、原生 JS（jQuery） |
| 数据库 | MySQL 8 |
| 缓存 / 会话存储 | Redis 5（Phase 1 收尾接入：access token 黑名单 + refresh token 存储，db 3 + `shop:` 前缀） |
| 自研组件 | `oss-spring-boot-starter`（自定义 Starter，自动配置文件存储，本地磁盘 ⇄ 对象存储可切换） |
| 持久层能力 | MyBatis-Plus：`BaseMapper` 通用 CRUD、分页插件、乐观锁插件（`@Version`）、公共字段自动填充 |
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
.hermes/mvn.sh test                          # 应用：66 个用例
.hermes/mvn.sh -f oss-spring-boot-starter/pom.xml test   # starter：11 个用例
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
│   │   └── AutoFillMetaObjectHandler 公共字段 created_at/updated_at 自动填充
│   └── service/CategoryService      分类业务层（新代码统一走 Controller→Service→Mapper）
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
| `lxy_cart` | 购物车（商品 id 逗号拼接，Phase 2 重构为关联表） |
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

### 🔐 认证与授权（Phase 1 起）

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
`AutoConfiguration.imports` SPI + `@ConditionalOnProperty` / `@ConditionalOnMissingBean`，
把「文件存储」抽成可切换的实现；业务侧只注入 `FileStorage` 拿 URL，
上传/删除代码从「自己拼路径、自己建目录、自己删文件」变成一行调用。

**Phase 1 已全部完成。** 后续路线图已按**校招标准**重规划（2026-09-13）：
砍掉分库分表 / 分布式事务 / 微服务 / ES / IM / 可观测性全家桶，补齐常规电商缺口。
详见 `docs/ROADMAP.md`。

1. **Phase 2（必做）**：领域建模 + 常规电商闭环
   分类 / 库存（乐观锁）/ 购物车关联表 / 订单状态机 + 明细 / 地址 / 评价；
   引入 MyBatis-Plus（分页、乐观锁、自动填充插件）；索引优化 + `EXPLAIN` 前后对比
2. **Phase 3（必做）**：Redis 缓存（穿透 / 击穿 / 雪崩 / 一致性）+ Redisson 分布式锁
3. **Phase 4（必做）**：RabbitMQ 异步下单 + 订单超时取消 + 消费幂等 + 秒杀基础版
4. **Phase 5（加分）**：Knife4j、AOP 日志与 `@RateLimit`、模拟支付、DTO/VO 分层
5. **Phase 6（收尾）**：Docker Compose + Nginx + 压测数字 + 面试问答

> 两个待处理的遗留（不急，但被问到要能答）：
> 上传目录仍在 `src/main/resources`（应外置；starter 已支持改配置，业务代码零改动）；
> `spring-boot-starter-websocket` 属零引用依赖，可移除。
