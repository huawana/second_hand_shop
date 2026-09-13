package shop.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>【面试可讲】这些「插件」在底层都实现了 MyBatis 的 {@code Interceptor} 接口，
 * 通过动态代理拦截 {@code Executor} / {@code StatementHandler} / {@code ParameterHandler} 的调用，
 * 在 SQL 真正执行前后做手脚 —— 分页插件改写 SQL 加 {@code LIMIT}，
 * 乐观锁插件改写 SQL 加 {@code WHERE version = ?}。
 * 换句话说：<b>MyBatis 插件就是 MyBatis 版的 AOP</b>。
 *
 * <p>【顺序有讲究】多个 InnerInterceptor 是按添加顺序串成责任链的。
 * MyBatis-Plus 官方建议的分组顺序是：
 * 多租户 / 动态表名 → 分页 / 乐观锁 → SQL 性能规范（防全表更新删除）。
 * 本项目只用后两组，顺序按官方示例排。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ---- 分页插件 ----
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限：防止前端传 size=100000 把整库捞出来（既是性能保护，也是防刷）
        pagination.setMaxLimit(100L);
        // overflow=false：页码越界时返回空结果，而不是回到第一页 ——
        // 翻页越界静默回到第一页会让前端「看起来还在第一页」，是更难排查的行为
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // ---- 乐观锁插件 ----
        // 实体上标注 @Version 的字段会参与：
        //   update ... set stock = ?, version = version + 1 where id = ? and version = ?
        // 影响行数 = 0 即表示「版本已被别人改过」，由业务决定是重试还是报错（见 ProductService）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }
}
