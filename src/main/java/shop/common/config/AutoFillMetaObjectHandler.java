package shop.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充（MyBatis-Plus {@link MetaObjectHandler}）。
 *
 * <p>解决的问题：{@code created_at} / {@code updated_at} 这类字段，
 * 每张表每个 insert/update 都手写一遍既啰嗦又容易漏（漏了就是 null，而 DDL 上的默认值
 * 只在「完全不带该列」时才生效）。交给框架统一填，业务代码只关心业务字段。
 *
 * <p>【为什么用 strictInsertFill 而不是 setFieldValByName】
 * {@code strictXxxFill} 会先判断「实体里到底有没有这个字段、有没有标 {@code FieldFill}」，
 * 只有声明过才填 —— 这样对没有审计字段的表（比如 category）不会报错，
 * 也不会把值硬塞进一个语义不相关的同名字段里。
 * 用 {@code setFieldValByName} 则必须自己保证字段存在，很容易在新增实体时炸掉。
 *
 * <p>【注意】自动填充只对「经过 MyBatis-Plus 的 BaseMapper / IService 的写入」生效。
 * 老的手写 XML update 语句不经过这条链路，所以那些 SQL 仍要自己维护时间字段 ——
 * 这也是「渐进式迁移」的代价，全部迁到 MP 之后就不存在了。
 */
@Component
public class AutoFillMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
