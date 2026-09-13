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
| 语言 / 构建 | Java 17、Maven |
| 框架 | Spring Boot 2.4.3、Spring MVC、MyBatis 2.2.2 |
| 视图 | Thymeleaf、原生 JS（jQuery） |
| 数据库 | MySQL 8 |
| 连接池 | Druid |
| 日志 | SLF4J + Logback（控制台 + 滚动文件 + 错误单独归档） |
| 校验 | JSR-303 / Hibernate Validator |
| 测试 | JUnit 5、Mockito、AssertJ |

> **依赖声明但尚未接入的组件：** `spring-boot-starter-data-redis`、`spring-boot-starter-websocket`。
> 两者都在路线图中有明确落点（Phase 3 缓存体系、Phase 8 IM 私聊），此前属于「引入了但零引用」。

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

### 4. 启动

```bash
mvn spring-boot:run
```

访问 <http://localhost:8080/shop/login>。

### 5. 运行测试

```bash
mvn test
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
│   └── GlobalExceptionHandler.java  全局异常处理 + HTTP 状态码映射
├── admin/                           后台侧
│   ├── Bean/                        Admin · User · Product · Order · Cart
│   ├── controller/                  Login · Admin · User · Product · Order
│   ├── mapper/                      对应 Mapper 接口
│   └── tools/MD5passEncryption     密码摘要（Phase 1 迁移为 BCrypt）
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
├── application.properties           数据源 / MyBatis / 上传路径（支持环境变量覆盖）
├── logback-spring.xml               日志配置
├── mapper/*.xml                     6 个 SQL 映射
├── templates/shop/ (17 个)          前台页面
├── templates/admin/ (9 个)          后台页面
└── static/                          3665 张商品图 + CSS/JS

docs/
├── schema.sql                       建表脚本
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
| `chat_records` | 私聊消息（**原项目未完成的功能**，Phase 8 补完） |
| `lxy_mate` | 好友关系（**原项目未完成的功能**，Phase 8 补完） |
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
- 数据库变更必须同步更新 `docs/schema.sql`

---

## 已知问题与后续计划

完整路线图、面试考点映射表、各阶段验收标准与实测数据见 **[docs/ROADMAP.md](docs/ROADMAP.md)**。

当前阶段（Phase 0）已完成：14 处缺陷修复、数据库审计与索引补齐、工程化基础设施、
45 个单元测试、2 个水平越权漏洞修复。

优先待办：

1. 密码存储由无盐 MD5 迁移至 BCrypt
2. Spring Boot 2.4.3 升级至 3.2（Spring Cloud / Sentinel / Seata 均要求此版本）
3. 购物车由逗号字符串重构为关联表，并引入库存与乐观锁
4. 下单链路补事务与幂等（当前「先查后改」不是原子操作，存在超卖风险）
