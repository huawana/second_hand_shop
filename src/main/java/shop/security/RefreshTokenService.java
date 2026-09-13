package shop.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * refresh token 的签发、轮换、吊销。
 *
 * <p><b>为什么 refresh token 不用 JWT，而是一串随机 UUID？</b>
 * 因为二者的诉求正好相反：
 * <ul>
 *   <li>access token 要<b>无状态、可横向扩展</b> —— 每个请求都查一次存储会成为瓶颈，
 *       所以用 JWT，服务端不存，靠签名自证；代价是「签发出去就撤不回」，
 *       只能用短寿命 + 黑名单兜住。</li>
 *   <li>refresh token 要<b>可即时吊销</b> —— 它是长期凭证，一旦泄露影响面大，
 *       必须能「立刻失效」。既然一定要服务端存储，那它就没有理由再是 JWT：
 *       一串不透明随机串更简单、更短、也不会有「payload 被解出信息」的顾虑。</li>
 * </ul>
 * 这是很常见的面试追问点：「既然用了 JWT，为什么还要在服务端存东西？」
 * 答案就是：无状态的部分（access）和无状态做不到的部分（refresh 吊销）要分开处理。
 *
 * <p><b>为什么每次刷新都换新令牌（轮换）？</b>
 * 固定不变的 refresh token 一旦泄露，攻击者可以长期冒用且无法察觉。
 * 轮换后每个 refresh token 只能用一次，于是「旧令牌被再次使用」本身就是一个
 * <b>可检测的信号</b>（正常客户端不会再拿它）—— 这就是重放检测。
 * 检出重放时撤销该用户全部会话，属于「宁可让用户重新登录，也不能让攻击者继续用」的取舍。
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RedisTokenStore store;
    private final JwtUtil jwtUtil;

    public RefreshTokenService(RedisTokenStore store, JwtUtil jwtUtil) {
        this.store = store;
        this.jwtUtil = jwtUtil;
    }

    /** 签发结果：给客户端写 Cookie 的令牌值 + 它代表的身份。 */
    public record IssuedRefresh(String token, LoginUser user) {
    }

    /** 登录成功后签发一个新的 refresh token。 */
    public IssuedRefresh issue(LoginUser user) {
        String token = UUID.randomUUID().toString();
        store.saveRefresh(token, toRecord(user), refreshTtl());
        return new IssuedRefresh(token, user);
    }

    /**
     * 用 refresh token 换取新的 access token（顺带轮换 refresh token）。
     *
     * @return 成功返回新令牌与身份；令牌无效 / 已过期 / 判定为重放时返回 {@code Optional.empty()}
     */
    public Optional<IssuedRefresh> rotate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }

        Optional<RedisTokenStore.RefreshRecord> live = store.findRefresh(presentedToken);
        if (live.isPresent()) {
            return Optional.of(doRotate(presentedToken, live.get()));
        }

        // 不在有效集合里：区分「并发刷新的重复提交」与「令牌重放攻击」
        Optional<RedisTokenStore.RefreshRecord> used = store.findUsedRefresh(presentedToken);
        if (used.isEmpty()) {
            // 完全没见过的令牌：可能是伪造的，也可能是上一轮 Redis 抖动丢的。静默拒绝。
            log.debug("refresh token 未找到，拒绝刷新");
            return Optional.empty();
        }

        Optional<String> grace = store.findGrace(presentedToken);
        if (grace.isPresent()) {
            // 宽限期内重复提交：客户端并发刷新（例如页面同时发了多个请求），
            // 这不是攻击 —— 返回同一个新令牌，避免把正常用户踢下线。
            return store.findRefresh(grace.get())
                    .map(rec -> new IssuedRefresh(grace.get(), toLoginUser(rec)));
        }

        // 超出宽限期还在使用已轮换掉的令牌 → 判定泄露，撤销该用户全部 refresh token
        int revoked = store.revokeAllForUser(used.get().uid());
        log.warn("检测到 refresh token 重放（已使用过的令牌被再次提交），已撤销该用户全部会话 uid={} 撤销数={}",
                used.get().uid(), revoked);
        return Optional.empty();
    }

    /** 登出：把这个 refresh token 作废（幂等，找不到也算成功）。 */
    public void revoke(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        Integer uid = store.findRefresh(presentedToken)
                .map(RedisTokenStore.RefreshRecord::uid)
                .orElse(null);
        store.deleteRefresh(presentedToken, uid);
    }

    /** 改密码 / 后台强制下线：撤销该用户的全部 refresh token。 */
    public int revokeAllForUser(Integer uid) {
        return store.revokeAllForUser(uid);
    }

    private IssuedRefresh doRotate(String oldToken, RedisTokenStore.RefreshRecord record) {
        store.deleteRefresh(oldToken, record.uid());
        // 标记「已使用」+ 开一个宽限窗口：这两步是重放检测的全部依据
        store.markRefreshUsed(oldToken, record, refreshTtl());

        LoginUser user = toLoginUser(record);
        String newToken = UUID.randomUUID().toString();
        store.saveRefresh(newToken, record, refreshTtl());
        store.saveGrace(oldToken, newToken);
        log.debug("refresh token 已轮换 uid={}", record.uid());
        return new IssuedRefresh(newToken, user);
    }

    private Duration refreshTtl() {
        return Duration.ofSeconds(jwtUtil.getRefreshExpireSeconds());
    }

    private static RedisTokenStore.RefreshRecord toRecord(LoginUser user) {
        return new RedisTokenStore.RefreshRecord(user.getId(), user.getUsername(), user.getRole());
    }

    private static LoginUser toLoginUser(RedisTokenStore.RefreshRecord record) {
        return new LoginUser(record.uid(), record.username(), record.role());
    }
}
