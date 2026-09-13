package shop.admin.Bean;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品分类（Phase 2 新增表 {@code category} 对应的实体）。
 *
 * <p>这是本项目第一个 MyBatis-Plus 实体，几个注解的含义：
 * <ul>
 *   <li>{@code @TableName} —— 建立类与表的映射。表名带 {@code lxy_} 前缀的老表也一样能标，
 *       不是「必须改表名才能用 MP」；</li>
 *   <li>{@code @TableId(type = IdType.AUTO)} —— 主键交给数据库自增。
 *       MP 也支持雪花 ID（{@code IdType.ASSIGN_ID}），本项目订单号另有一套生成策略；</li>
 *   <li>{@code @TableField(fill = FieldFill.INSERT)} —— 交给
 *       {@link shop.common.config.AutoFillMetaObjectHandler} 自动填，不写进 insert 语句。</li>
 * </ul>
 *
 * <p>字段用驼峰（{@code parentId}），由配置 {@code map-underscore-to-camel-case=true}
 * 自动对应到 {@code parent_id}，不需要逐个写 {@code @TableField("parent_id")}。
 */
@Data
@TableName("category")
public class Category {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 分类名 */
    private String name;

    /** 父分类 id，0 表示一级分类（预留树形结构） */
    private Integer parentId;

    /** 排序值，越小越靠前 */
    private Integer sort;

    /** 1 启用 / 0 停用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
