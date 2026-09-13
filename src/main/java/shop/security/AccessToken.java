package shop.security;

import java.time.Instant;

/**
 * 解析后的 access token。
 *
 * <p>比只返回 {@link LoginUser} 多带了两个东西，都是「可吊销」这件事的必要条件：
 * <ul>
 *   <li>{@code jti}（JWT ID）—— 令牌的唯一编号。无状态 JWT 本身无法撤销，
 *       但把 jti 写进黑名单就能实现「指定这一个令牌失效」；</li>
 *   <li>{@code expiresAt} —— 计算黑名单 key 的存活时间用得上：
 *       只要把黑名单 TTL 设成「令牌剩余寿命」，令牌自然过期后黑名单条目也会自动清理，
 *       不需要永久堆积（这是黑名单方案能不能长期跑的关键细节）。</li>
 * </ul>
 */
public record AccessToken(LoginUser user, String jti, Instant expiresAt) {
}
