package shop.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Redis 序列化配置测试（Phase 3.1）。
 *
 * <p>不开真 Redis：只验证「配的序列化器是不是我们想要的」以及
 * 「值序列化出来是不是人类可读的 JSON」—— 这两件事恰好是 3.1 的全部决策内容，
 * 也正好能锁住「别退回 JDK 序列化」这个决定。
 */
class RedisConfigTest {

    @Test
    @DisplayName("key 用 String 序列化器：redis-cli 里能直接看懂 key")
    void keySerializerIsString() {
        RedisTemplate<String, Object> template =
                new RedisConfig().redisTemplate(mock(RedisConnectionFactory.class));

        assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        // value 必须是 JSON 序列化器，绝不能是 JDK 原生序列化
        assertThat(template.getValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
        assertThat(template.getHashValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
    }

    @Test
    @DisplayName("value 序列化为可读 JSON（而不是 JDK 的二进制字节流）")
    void valueIsReadableJson() {
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
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
    @DisplayName("JSON 往返后对象内容一致")
    void roundTrip() {
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        Sample original = new Sample("键盘", 99.5);

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored).isInstanceOf(Sample.class);
        assertThat(((Sample) restored).getName()).isEqualTo("键盘");
        assertThat(((Sample) restored).getPrice()).isEqualTo(99.5);
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
}
