package shop.common.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置（Phase 3.1，Phase 3.3 修正）。
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
 * <p>【Phase 3.3 修正：必须复用 Spring 容器里的 ObjectMapper】3.1 版本用的是
 * {@code new GenericJackson2JsonRedisSerializer()}，它内部 {@code new ObjectMapper()} ——
 * 一个<b>什么都没有注册</b>的干净 mapper。当时缓存的对象里只有 String/double，
 * 所以没暴露问题。3.3 开始缓存 {@code Category}（含 {@code LocalDateTime createdAt}）时立刻炸：
 * <pre>
 *   InvalidDefinitionException: Java 8 date/time type `java.time.LocalDateTime` not supported by default:
 *   add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" ...
 * </pre>
 * 原因：JDK8 时间类型需要 {@code JavaTimeModule}，而 Spring Boot 的 {@code JacksonAutoConfiguration}
 * 早就把它注册进了容器里那个 {@code ObjectMapper}（Web 层能正常返回 ISO 时间就靠它）。
 * 于是这里改成「注入容器里的 ObjectMapper → {@code copy()} → 交给序列化器」：
 * <ul>
 *   <li>时间类型、{@code @JsonFormat}、命名策略等 Jackson 配置与接口层保持一致，
 *       不会出现「接口返回的 JSON 和缓存里的 JSON 长得不一样」；</li>
 *   <li>{@code copy()} 而不是直接改原对象：序列化器还要往里加默认类型信息（{@code activateDefaultTyping}），
 *       那个改动不该污染给 Web 层用的 mapper。</li>
 * </ul>
 *
 * <p>【3.3 第二个坑：传自定义 ObjectMapper 时 {@code @class} 不会自动加上】把 mapper 交给
 * {@code new GenericJackson2JsonRedisSerializer(mapper)} 之后，序列化出来的 JSON 里
 * <b>不再带 {@code @class}</b>（默认类型信息只在无参构造器那条路径上激活）。
 * 后果极其隐蔽：value 能正常写进 Redis、日志一切正常，但读回来是
 * {@code LinkedHashMap} 而不是 {@code Product}，缓存服务里那句 {@code (Product) cached}
 * 会抛 {@code ClassCastException} → 接口 500。
 * 所以这里显式调用 {@code activateDefaultTyping}（见 {@link #jsonValueSerializer}）。
 * 测试 {@code ProductCacheCodecTest} / {@code RedisConfigTest} 就是这条结论的看门人，
 * 且它们与生产代码<b>共用同一个构造方法</b>，避免「测试测的是另一套配置」。
 *
 * <p>注意这里<b>只新增</b> {@code RedisTemplate<String, Object>}，不动既有的
 * {@code StringRedisTemplate} —— 令牌存储（RedisTokenStore）用的是后者，
 * 存的是纯字符串，不需要 JSON 序列化。
 */
@Configuration
public class RedisConfig {

    /**
     * 构造 value 序列化器（生产与测试共用）。
     *
     * @param baseMapper 容器里的 ObjectMapper（含 JavaTimeModule 等配置），内部会 {@code copy()}，
     *                   不会污染 Web 层用的那个实例
     */
    public static GenericJackson2JsonRedisSerializer jsonValueSerializer(ObjectMapper baseMapper) {
        ObjectMapper mapper = baseMapper.copy();
        // 必须显式激活：否则 JSON 里没有 @class，反序列化回来是 LinkedHashMap
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        // 用容器里的 ObjectMapper（已注册 JavaTimeModule，且与 Web 层配置一致）的副本
        GenericJackson2JsonRedisSerializer valueSerializer = jsonValueSerializer(objectMapper);

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
