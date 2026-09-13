package shop.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Redis 序列化配置测试（Phase 3.1）。
 *
 * <p>不开真 Redis：只验证「配的序列化器是不是我们想要的」以及
 * 「值序列化出来是不是人类可读的 JSON」—— 这两件事恰好是 3.1 的全部决策内容，
 * 也正好能锁住「别退回 JDK 序列化」这个决定。
 *
 * <p>{@code javaTimeNeedsTheSpringMapper} / {@code javaTimeBreaksWithBareMapper} 两个用例
 * 是 Phase 3.3 补的：它们把「默认 ObjectMapper 不支持 LocalDateTime」这个坑钉成回归测试 ——
 * 一旦有人把 {@code RedisConfig} 改回 {@code new GenericJackson2JsonRedisSerializer()}，
 * 测试会立刻红，而不是等到线上缓存分类列表时才报 InvalidDefinitionException。
 */
class RedisConfigTest {

    /** 模拟 Spring Boot 容器里的 ObjectMapper：注册 JavaTimeModule + 日期写成 ISO 字符串 */
    private static ObjectMapper springLikeMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Test
    @DisplayName("key 用 String 序列化器：redis-cli 里能直接看懂 key")
    void keySerializerIsString() {
        RedisTemplate<String, Object> template =
                new RedisConfig().redisTemplate(mock(RedisConnectionFactory.class), springLikeMapper());

        assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        // value 必须是 JSON 序列化器，绝不能是 JDK 原生序列化
        assertThat(template.getValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
        assertThat(template.getHashValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
    }

    @Test
    @DisplayName("value 序列化为可读 JSON（而不是 JDK 的二进制字节流）")
    void valueIsReadableJson() {
        RedisSerializer<Object> serializer = RedisConfig.jsonValueSerializer(springLikeMapper());
        Sample sample = new Sample("校园二手书", 12.34);

        byte[] bytes = serializer.serialize(sample);
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\"name\"").contains("校园二手书").contains("12.34");
        // JDK 序列化的字节流以魔数 0xACED 开头，且在字符串里表现为不可读的乱码 ——
        // 这个断言就是「没有退回 JDK 序列化」的守门人
        assertThat((int) bytes[0] & 0xFF).isNotEqualTo(0xAC);
        assertThat(json).doesNotContain("\uFFFD");
    }

    @Test
    @DisplayName("JSON 往返后对象内容一致，且类型信息（@class）确实被写进去了")
    void roundTrip() {
        RedisSerializer<Object> serializer = RedisConfig.jsonValueSerializer(springLikeMapper());
        Sample original = new Sample("键盘", 99.5);

        byte[] bytes = serializer.serialize(original);
        Object restored = serializer.deserialize(bytes);

        // 3.3 的坑：如果这里没有 @class，restored 会是 LinkedHashMap，
        // 缓存服务里的强制类型转换就会在运行时炸掉
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("@class");
        assertThat(restored).isInstanceOf(Sample.class);
        assertThat(((Sample) restored).getName()).isEqualTo("键盘");
        assertThat(((Sample) restored).getPrice()).isEqualTo(99.5);
    }

    @Test
    @DisplayName("配置里的序列化器能缓存 LocalDateTime（分类的 createdAt 就靠它）")
    void javaTimeNeedsTheSpringMapper() {
        RedisSerializer<Object> serializer = valueSerializerOfConfiguredTemplate();

        LocalDateTime now = LocalDateTime.of(2026, 9, 13, 21, 30, 15);
        Object restored = serializer.deserialize(serializer.serialize(new TimeSample(now)));

        assertThat(restored).isInstanceOf(TimeSample.class);
        assertThat(((TimeSample) restored).getCreatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("回归守护：用「裸」ObjectMapper 会直接序列化失败 —— 这就是 3.1 版本埋的坑")
    void javaTimeBreaksWithBareMapper() {
        GenericJackson2JsonRedisSerializer bare = new GenericJackson2JsonRedisSerializer();

        assertThatThrownBy(() -> bare.serialize(new TimeSample(LocalDateTime.now())))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Java 8 date/time");
    }

    @Test
    @DisplayName("JSON 数组往返后仍是 List，元素类型正确（分类列表缓存的形态）")
    void listRoundTrip() {
        RedisSerializer<Object> serializer = valueSerializerOfConfiguredTemplate();

        List<TimeSample> original = List.of(
                new TimeSample(LocalDateTime.of(2026, 1, 1, 0, 0, 0)),
                new TimeSample(LocalDateTime.of(2026, 2, 2, 12, 0, 0)));

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isInstanceOf(List.class);
        List<?> list = (List<?>) restored;
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).isInstanceOf(TimeSample.class);
        assertThat(((TimeSample) list.get(1)).getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 2, 2, 12, 0, 0));
    }

    @Test
    @DisplayName("空值哨兵往返后仍是那个字符串（穿透防护依赖它）")
    void nullSentinelRoundTrip() {
        RedisSerializer<Object> serializer = valueSerializerOfConfiguredTemplate();

        Object restored = serializer.deserialize(
                serializer.serialize(shop.common.cache.CacheKeys.NULL_VALUE));

        assertThat(restored).isEqualTo(shop.common.cache.CacheKeys.NULL_VALUE);
    }

    @Test
    @DisplayName("回归守护：只用无参构造器（不激活类型信息）会导致反序列化成 LinkedHashMap")
    void bareMapperLosesTypeInformation() {
        // 无参构造器会激活默认类型信息，所以这条路径是「对的」——
        // 但一旦改成传自定义 mapper 而忘了 activateDefaultTyping，就会退化成下面断言的样子
        GenericJackson2JsonRedisSerializer withoutTyping =
                new GenericJackson2JsonRedisSerializer(springLikeMapper());

        Object restored = withoutTyping.deserialize(withoutTyping.serialize(new Sample("键盘", 99.5)));

        assertThat(restored).isInstanceOf(java.util.Map.class);
        assertThat(restored).isNotInstanceOf(Sample.class);
    }

    private RedisSerializer<Object> valueSerializerOfConfiguredTemplate() {
        RedisTemplate<String, Object> template =
                new RedisConfig().redisTemplate(mock(RedisConnectionFactory.class), springLikeMapper());
        @SuppressWarnings("unchecked")
        RedisSerializer<Object> serializer = (RedisSerializer<Object>) template.getValueSerializer();
        return serializer;
    }

    /** 测试用的简单 POJO（需要有默认构造器与 getter/setter，Jackson 才能还原） */
    public static class Sample {
        private String name;
        private double price;

        public Sample() {
        }

        public Sample(String name, double price) {
            this.name = name;
            this.price = price;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double price) {
            this.price = price;
        }
    }

    /** 带 JDK8 时间类型的 POJO（对应真实的 Category.createdAt） */
    public static class TimeSample {
        private LocalDateTime createdAt;

        public TimeSample() {
        }

        public TimeSample(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
    }
}
