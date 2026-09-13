package shop.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * 令牌状态存储（Redis）—— 让「无状态的 JWT」获得「可撤销」的能力。
 *
 * <p>存三类东西：
 * <ol>
 *   <li><b>access token 黑名单</b> {@code shop:killed:{jti}} —— 登出/强制下线时写入，
 *       TTL = 该令牌的剩余寿命（令牌自然过期后条目自动消失，不会无限堆积）；</li>
 *   <li><b>refresh token</b> {@code shop:rt:{uuid}} —— 值是该令牌对应的身份信息；</li>
 *   <li><b>refresh token 的「已使用」与「宽限期」标记</b> —— 支撑<b>重放检测</b>：
 *       轮换后旧令牌若再次出现，说明它被泄露并被别人使用，此时撤销该用户全部 refresh token。
 *       宽限期内（默认 60s）重复提交视为「客户端并发刷新」，返回同一个新令牌而不误判为攻击。</li>
 * </ol>
 *
 * <p><b>降级策略（重要）</b>：所有 Redis 操作都包了 try-catch。Redis 不可用时不抛异常，
 * 而是记录 WARN 并返回「安全的默认值」（未命中黑名单 / 没有 refresh token）。
 * 取舍：Redis 挂掉期间无法提前撤销令牌、也无法刷新，但<b>用户不会因为缓存故障被锁在门外</b>。
 * 反过来（把异常抛出去）会造成「Redis 抖动 → 全站 401」，是更糟的故障模式。
 *
 * <p>Key 全部带 {@code shop:} 前缀，并使用独立 database（配置里 {@code spring.data.redis.database=3}），
 * 避免与同机其他项目串键。
 */
@Component
public class RedisTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenStore.class);

    private static final String KILLED = "shop:killed:";
    private static final String REFRESH = "shop:rt:";
    private static final String REFRESH_USED = "shop:rt:used:";
    private static final String REFRESH_GRACE = "shop:rt:grace:";
    private static final String USER_REFRESH_SET = "shop:user:";
    private static final String USER_REFRESH_SET_SUFFIX = ":rts";

    /** 并发刷新宽限期：同一旧令牌在此窗口内重复提交，返回同一个新令牌而不是判为重放 */
    public static final Duration REFRESH_GRACE_WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** refresh token 对应的身份快照（值对象，序列化成 JSON 存 Redis，便于 redis-cli 直接查看）。 */
    public record RefreshRecord(Integer uid, String username, String role) {
    }

    // ---------------------------------------------------------------- access 黑名单

    public void blacklistAccess(String jti, Duration ttl) {
        if (jti == null || ttl.isNegative() || ttl.isZero()) {
            // 已过期的令牌没必要进黑名单：它本来就通不过签名校验
            return;
        }
        try {
            redis.opsForValue().set(KILLED + jti, "1", ttl);
        } catch (Exception e) {
            log.warn("写入 access 黑名单失败（Redis 异常，按未拉黑处理） jti={}", jti, e);
        }
    }

    public boolean isAccessBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KILLED + jti));
        } catch (Exception e) {
            log.warn("查询 access 黑名单失败（Redis 异常，按未拉黑处理） jti={}", jti, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- refresh token

    public void saveRefresh(String jti, RefreshRecord record, Duration ttl) {
        try {
            redis.opsForValue().set(REFRESH + jti, toJson(record), ttl);
            // 维护「该用户有哪些 refresh token」，用于一键全部下线（改密码、检测到盗用）
            String setKey = USER_REFRESH_SET + record.uid() + USER_REFRESH_SET_SUFFIX;
            redis.opsForSet().add(setKey, jti);
            redis.expire(setKey, ttl);
        } catch (Exception e) {
            log.warn("保存 refresh token 失败（Redis 异常） uid={}", record.uid(), e);
        }
    }

    public Optional<RefreshRecord> findRefresh(String jti) {
        return readRecord(REFRESH + jti);
    }

    public void deleteRefresh(String jti, Integer uid) {
        try {
            redis.delete(REFRESH + jti);
            if (uid != null) {
                redis.opsForSet().remove(USER_REFRESH_SET + uid + USER_REFRESH_SET_SUFFIX, jti);
            }
        } catch (Exception e) {
            log.warn("删除 refresh token 失败（Redis 异常） jti={}", jti, e);
        }
    }

    /** 轮换后把旧令牌标记为「已使用」，用于后续的重放检测。 */
    public void markRefreshUsed(String jti, RefreshRecord record, Duration ttl) {
        try {
            redis.opsForValue().set(REFRESH_USED + jti, toJson(record), ttl);
        } catch (Exception e) {
            log.warn("标记 refresh token 已使用失败（Redis 异常） jti={}", jti, e);
        }
    }

    public Optional<RefreshRecord> findUsedRefresh(String jti) {
        return readRecord(REFRESH_USED + jti);
    }

    public void saveGrace(String oldJti, String newJti) {
        try {
            redis.opsForValue().set(REFRESH_GRACE + oldJti, newJti, REFRESH_GRACE_WINDOW);
        } catch (Exception e) {
            log.warn("写入 refresh 宽限期标记失败（Redis 异常） jti={}", oldJti, e);
        }
    }

    public Optional<String> findGrace(String oldJti) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(REFRESH_GRACE + oldJti));
        } catch (Exception e) {
            log.warn("查询 refresh 宽限期标记失败（Redis 异常） jti={}", oldJti, e);
            return Optional.empty();
        }
    }

    /**
     * 撤销某个用户的全部 refresh token（改密码 / 检出重放 / 后台强制下线时使用）。
     *
     * @return 被撤销的令牌数量
     */
    public int revokeAllForUser(Integer uid) {
        if (uid == null) {
            return 0;
        }
        try {
            String setKey = USER_REFRESH_SET + uid + USER_REFRESH_SET_SUFFIX;
            Set<String> jtis = redis.opsForSet().members(setKey);
            int count = 0;
            if (jtis != null) {
                for (String jti : jtis) {
                    redis.delete(REFRESH + jti);
                    count++;
                }
            }
            redis.delete(setKey);
            return count;
        } catch (Exception e) {
            log.warn("撤销用户全部 refresh token 失败（Redis 异常） uid={}", uid, e);
            return 0;
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private Optional<RefreshRecord> readRecord(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, RefreshRecord.class));
        } catch (JsonProcessingException e) {
            // 值损坏（例如人工改过 key）：当作不存在，避免脏数据把登录链路打挂
            log.warn("refresh token 记录解析失败，按不存在处理 key={}", key, e);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("读取 refresh token 失败（Redis 异常） key={}", key, e);
            return Optional.empty();
        }
    }

    private String toJson(RefreshRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("refresh token 记录序列化失败", e);
        }
    }
}
