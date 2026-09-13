package shop.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置（Phase 3.1）。
 *
 * <p>【为什么必须自己配一遍】Spring Boot 自动装配的 {@code RedisTemplate<Object, Object>}
 * 默认用 {@code JdkSerializationRedisSerializer}，也就是把对象用 Java 原生序列化写成字节：
 * <pre>
 * redis-cli&gt; get shop:cache:product:1
 * "\xac\xed\x00\x05sr\x00\x15shop.admin.Bean.Product..."   ← 完全不可读
 * </pre>
 * 三个具体问题：
 * <ol>
 *   <li><b>不可读</b>：没法用 redis-cli 直接看内容，线上排查基本靠猜；</li>
 *   <li><b>不通用</b>：只有 Java 能解，Python/Go 的服务读不了这个 key，
 *       缓存被绑死在一种语言上；</li>
 *   <li><b>脆弱</b>：序列化里带着完整的类名与 serialVersionUID，
 *       实体加个字段、改个包名，旧缓存反序列化就抛异常
 *       （而且这份「脏缓存」还在 Redis 里，得手工清）。</li>
 * </ol>
 * 所以 key 用 {@link StringRedisSerializer}（在 redis-cli 里直接可读、便于按前缀扫）、
 * value 用 {@link GenericJackson2JsonRedisSerializer}（人类可读、跨语言、字段增删的兼容性更好）。
 *
 * <p>【关于 {@code @class} 字段】GenericJackson2JsonRedisSerializer 默认会往 JSON 里塞一个
 * {@code @class} 类型信息，反序列化时才知道该还原成哪个类。这带来一个面试常问的坑：
 * <b>缓存的类一旦改名/换包，带 {@code @class} 的旧 JSON 就反序列化失败</b>。
 * 更稳的做法是缓存专用的 VO 并指定固定类型（见 Phase 5 的 DTO/VO 分层），
 * 或者干脆用「不带类型信息」的序列化器 + 读取时指定类型。
 * 本项目暂时保留默认行为（省事，且能在 redis-cli 里看清类型），并在文档里记录这个权衡。
 *
 * <p>注意这里<b>只新增</b> {@code RedisTemplate<String, Object>}，不动既有的
 * {@code StringRedisTemplate} —— 令牌存储（RedisTokenStore）用的是后者，
 * 存的是纯字符串，不需要 JSON 序列化。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        // afterPropertiesSet() 会校验 connectionFactory、序列化器等是否就绪；
        // 手工 new 出来的 RedisTemplate 不调用它，字段可能没初始化完
        template.afterPropertiesSet();
        return template;
    }
}
