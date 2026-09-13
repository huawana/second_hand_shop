package shop.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import shop.admin.Bean.Category;
import shop.admin.Bean.Product;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存内容编解码测试（Phase 3.2 / 3.3）。
 *
 * <p>【为什么值得单独写测试】缓存里放的是「对象」，序列化一旦出问题，
 * 表现往往是「接口 500」或更糟的「反序列化失败被 try-catch 吞掉、命中率永远为 0」——
 * 后者完全不报错，只是缓存白配了。这里的用例全部来自真实踩到的坑：
 * <ul>
 *   <li>{@code cachingTheEntityIsLossy}：<b>直接缓存实体是有损的</b>。
 *       实体的 {@code createdAt} 是 {@code java.sql.Date}，Jackson 往返后时分秒丢失、
 *       而且 epoch 会漂移 8 小时（写入按本地时区渲染、读回按 UTC 解析）。
 *       这条用例把这个事实钉住，解释为什么要有 {@link ProductCacheVO}。</li>
 *   <li>{@code cacheVORoundTrip}：换成 VO（时间用字符串）之后往返无损 —— 这才是真正的修复。</li>
 *   <li>{@code categoryListRoundTrip}：{@code LocalDateTime} 需要 JavaTimeModule，
 *       这是 Phase 3.3 第一次缓存分类时才暴露的坑（默认 mapper 直接抛 InvalidDefinitionException）。</li>
 * </ul>
 */
class ProductCacheCodecTest {

    /** 与生产代码<b>共用同一个构造方法</b>（RedisConfig.jsonValueSerializer） */
    private static RedisSerializer<Object> serializer() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return shop.common.config.RedisConfig.jsonValueSerializer(mapper);
    }

    private static Product sampleProduct() {
        Product p = new Product();
        p.setId(3659);
        p.setName("二手数据结构教材");
        p.setSellerName("lisi");
        p.setDescription("考研用书，九成新（含笔记）");
        p.setPrice(23.50);
        p.setCreatedAt(Date.valueOf("2024-05-06"));
        p.setImgPath("/shop/assets/product-img/3659.png");
        p.setViewCount(88);
        return p;
    }

    @Test
    @DisplayName("缓存 VO 往返无损：字段一致，时间字符串原样回来（不经过时区换算）")
    void cacheVORoundTrip() {
        Product original = sampleProduct();
        ProductCacheVO vo = ProductCacheVO.from(original);

        Object restored = serializer().deserialize(serializer().serialize(vo));

        assertThat(restored).isInstanceOf(ProductCacheVO.class);
        ProductCacheVO back = (ProductCacheVO) restored;
        assertThat(back.name()).isEqualTo("二手数据结构教材");
        assertThat(back.sellerName()).isEqualTo("lisi");
        assertThat(back.description()).contains("九成新");
        assertThat(back.price()).isEqualTo(23.50);
        assertThat(back.createdAt()).isEqualTo("2024-05-06");
        assertThat(back.imgPath()).isEqualTo("/shop/assets/product-img/3659.png");
        assertThat(back.viewCount()).isEqualTo(88);

        // 关键：VO → 实体后，日期的 epoch 与原始实体完全一致（缓存不会让时间发生任何漂移）
        Product rebuilt = back.toProduct();
        assertThat(rebuilt.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(rebuilt.getCreatedAt().getTime()).isEqualTo(original.getCreatedAt().getTime());
        assertThat(rebuilt.getId()).isEqualTo(original.getId());
        assertThat(rebuilt.getName()).isEqualTo(original.getName());
        assertThat(rebuilt.getPrice()).isEqualTo(original.getPrice());
    }

    @Test
    @DisplayName("直接缓存实体是有损的（时分秒丢失 + 时刻被重解释）—— 这条钉住「为什么要 VO」")
    void cachingTheEntityIsLossy() {
        // 带时分秒的时间：真实库里 created_at 是 DATETIME，不是纯日期
        Product entity = new Product();
        entity.setId(1);
        entity.setCreatedAt(new Date(java.sql.Timestamp.valueOf("2024-05-06 20:30:00").getTime()));

        String json = new String(serializer().serialize(entity), StandardCharsets.UTF_8);
        Product back = (Product) serializer().deserialize(serializer().serialize(entity));

        // ① 时分秒被吃掉：Jackson 把 java.sql.Date 渲染成 "yyyy-MM-dd"，20:30 直接丢了
        assertThat(json).contains("2024-05-06").doesNotContain("20:30");

        // ② 时刻被重解释：写的时候按 JVM 本地时区取日期部分，读回来按 mapper 的时区（UTC）解析 ——
        //    还原后恒等于「该日期的 UTC 零点」，与原始时刻无关
        //    （注意：java.sql.Date#toInstant() 会抛 UnsupportedOperationException，只能用 getTime()）
        assertThat(java.time.Instant.ofEpochMilli(back.getCreatedAt().getTime()))
                .isEqualTo(java.time.LocalDate.of(2024, 5, 6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        assertThat(back.getCreatedAt().getTime()).isNotEqualTo(entity.getCreatedAt().getTime());
    }

    @Test
    @DisplayName("实体的时区漂移量 = 本机时区偏移（本地零点的日期会被抬到 08:00）")
    void entityRoundTripShiftsByLocalOffset() {
        // 库里 DATE 类型的典型形态：本地零点
        Product entity = new Product();
        entity.setId(1);
        entity.setCreatedAt(Date.valueOf("2024-05-06"));

        Product back = (Product) serializer().deserialize(serializer().serialize(entity));

        long offset = java.util.TimeZone.getDefault().getOffset(entity.getCreatedAt().getTime());
        assertThat(back.getCreatedAt().getTime() - entity.getCreatedAt().getTime()).isEqualTo(offset);
        // 在 UTC+8 的机器上偏移就是 8 小时（28800000ms）—— 缓存里存了一个和库里对不上的时刻。
        // 而 ProductCacheVO 用字符串承载时间，往返完全对称，见 cacheVORoundTrip。
    }

    @Test
    @DisplayName("缓存值是【人类可读】JSON：redis-cli 里能直接看懂")
    void cacheValueIsReadableJson() {
        byte[] bytes = serializer().serialize(ProductCacheVO.from(sampleProduct()));
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(json).contains("\"name\"").contains("二手数据结构教材").contains("2024-05-06");
        // 带类型信息：GenericJackson2JsonRedisSerializer 的反序列化依赖它（丢了就退化成 LinkedHashMap）
        assertThat(json).contains("@class").contains("ProductCacheVO");
        // 不是 JDK 序列化的魔数
        assertThat(json).doesNotContain("\\xac");
        // VO 比实体小：详情页用不到的字段不该出现在缓存里
        assertThat(json).doesNotContain("sellerHandle").doesNotContain("soldTime");
    }

    @Test
    @DisplayName("分类列表往返后仍是 List<Category>，LocalDateTime 不丢精度")
    void categoryListRoundTrip() {
        Category c1 = new Category();
        c1.setId(1);
        c1.setName("教材书籍");
        c1.setParentId(0);
        c1.setSort(1);
        c1.setStatus(1);
        c1.setCreatedAt(LocalDateTime.of(2026, 9, 13, 16, 50, 30));

        Category c2 = new Category();
        c2.setId(2);
        c2.setName("数码电子");
        c2.setStatus(1);
        c2.setCreatedAt(LocalDateTime.of(2026, 9, 13, 16, 50, 31));

        List<Category> original = List.of(c1, c2);
        Object restored = serializer().deserialize(serializer().serialize(original));

        assertThat(restored).isInstanceOf(List.class);
        List<?> list = (List<?>) restored;
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).isInstanceOf(Category.class);
        Category first = (Category) list.get(0);
        assertThat(first.getName()).isEqualTo("教材书籍");
        assertThat(first.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 13, 16, 50, 30));
    }

    @Test
    @DisplayName("空值哨兵往返后仍是原字符串：缓存服务用 equals 判定「已知不存在」")
    void nullSentinelRoundTrip() {
        Object restored = serializer().deserialize(serializer().serialize(CacheKeys.NULL_VALUE));

        assertThat(restored).isInstanceOf(String.class);
        assertThat(CacheKeys.NULL_VALUE.equals(restored)).isTrue();
    }
}
