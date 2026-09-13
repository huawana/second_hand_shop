package shop.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置（Phase 3.4 / 3.8）。
 *
 * <p>【为什么引入 Redisson】本阶段要用它两样东西，都是「自己手写容易写出 bug」的那类：
 * <ol>
 *   <li>{@code RLock} 分布式锁 —— 手写 {@code SETNX + EXPIRE} 有四个经典坑：加锁与设过期不是原子操作、
 *       过期时间到了业务没跑完锁就失效、误删别人的锁、不可重入；Redisson 用 Lua 脚本保证原子性，
 *       还带<b>看门狗自动续期</b>与可重入计数。</li>
 *   <li>{@code RBloomFilter} 布隆过滤器 —— 手写需要自己维护位数组、处理散列函数个数与扩容，
 *       而且要在 Redis 侧保证「多个位一起设置」的原子性。</li>
 * </ol>
 *
 * <p>【只用核心包 {@code org.redisson:redisson}，不用 redisson-spring-boot-starter 的原因】
 * starter 会<b>替换</b> Spring Boot 自动配置的 {@code RedisConnectionFactory}（它自带一套连接工厂，
 * 顺带把 Lettuce 换掉）。而本项目 Phase 1 的令牌存储用的是 {@code StringRedisTemplate}、
 * Phase 3.1 的缓存用的是 {@code RedisTemplate<String,Object>}，都建立在 Boot 自动配置的
 * Lettuce 连接工厂之上。引 starter 等于把这两条已经在跑的链路一起换掉，风险与收益不成比例。
 * 核心包模式下两套客户端并存、各管一段（RedisTemplate 走 Lettuce 连接池，锁/布隆过滤器走 Redisson 连接池），
 * 互相不影响 —— 代价是多一份连接池，对这个体量的项目可以忽略。
 *
 * <p>【Redis 不可用时不能让应用起不来】Redisson 在 {@code create()} 时会尝试建连，Redis 没起会抛异常。
 * 本项目 Phase 1 定下的基调是「Redis 故障降级、不阻断业务」（见 {@code RedisTokenStore} 的说明），
 * 所以这里捕获异常并<b>返回 null</b>：Spring 会把该 bean 记为「空 bean」，
 * 依赖方通过 {@code ObjectProvider} 拿到 null 后走降级分支（拿不到分布式锁就直接执行、
 * 布隆过滤器不可用就放行查库），应用照常启动。
 * 这是有意为之的取舍：宁可少一层保护，也不要一个缓存组件把整站拖下线。
 */
@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    /**
     * Redisson 客户端。
     *
     * <p>属性直接复用 Spring Boot 的 {@code spring.data.redis.*}，避免同一个 Redis 地址要在两处配置里对不上。
     * 置 {@code shop.redis.redisson-enabled=false} 可完全关掉它（验证脚本用它来验证降级路径）。
     *
     * @return 连接成功返回客户端；Redis 不可用返回 {@code null}（应用继续启动，相关功能降级）
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "shop.redis", name = "redisson-enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${shop.redis.lock-watchdog-millis:30000}") long lockWatchdogMillis) {

        Config config = new Config();
        config.setLockWatchdogTimeout(lockWatchdogMillis);
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                // 空字符串要转成 null：Redisson 会把 "" 当成真密码去 AUTH，未设密码的 Redis 会报错
                .setPassword(password == null || password.isBlank() ? null : password)
                .setConnectTimeout(2000)
                .setTimeout(2000)
                // 【连接池取舍】Redisson 与 Lettuce 不同：它是「一个命令一条连接」的模型，
                // 必须配池。锁与布隆过滤器都不是高频操作，最小空闲 1 / 上限 8 足够。
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setRetryAttempts(2);

        try {
            long t0 = System.currentTimeMillis();
            RedissonClient client = Redisson.create(config);
            log.info("Redisson 客户端初始化成功：redis://{}:{}/db{} 耗时 {}ms", host, port, database,
                    System.currentTimeMillis() - t0);
            return client;
        } catch (Exception e) {
            log.error("Redisson 初始化失败（Redis 不可用？）→ 分布式锁与布隆过滤器降级为不可用，"
                    + "应用继续启动。address=redis://{}:{}/db{}", host, port, database, e);
            return null;
        }
    }
}
