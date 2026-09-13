package shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单状态机单元测试。
 *
 * <p>状态机是<b>纯逻辑</b>，最适合做单元测试的形态：不起 Spring 容器、不碰数据库，
 * 毫秒级跑完，却能把「哪些流转合法」这条业务规则完整锁住。
 * 这也是把规则从 Controller 的 if/else 挪进枚举的直接收益 ——
 * 散在流程代码里的规则没法单独测，只能靠跑整个链路碰运气。
 */
class OrderStatusTest {

    @Test
    @DisplayName("主流程链路：待支付 → 已支付 → 已发货 → 已完成，每一步都允许")
    void mainFlowIsAllowed() {
        assertThat(OrderStatus.PENDING_PAY.canTransferTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAID.canTransferTo(OrderStatus.SHIPPED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransferTo(OrderStatus.COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("跳步与倒流都不允许：已支付不能直接到已完成，已发货不能回到已支付")
    void skipAndRevertAreRejected() {
        assertThat(OrderStatus.PAID.canTransferTo(OrderStatus.COMPLETED)).isFalse();
        assertThat(OrderStatus.SHIPPED.canTransferTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.COMPLETED.canTransferTo(OrderStatus.SHIPPED)).isFalse();
    }

    @Test
    @DisplayName("已取消是终态：不能复活成任何状态（要交易就重新下单）")
    void cancelledIsTerminal() {
        assertThat(OrderStatus.CANCELLED.nextStatuses()).isEmpty();
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(OrderStatus.CANCELLED.canTransferTo(s))
                    .as("已取消 → %s 应当被拒绝", s)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("已完成是终态")
    void completedIsTerminal() {
        assertThat(OrderStatus.COMPLETED.nextStatuses()).isEmpty();
    }

    @Test
    @DisplayName("未支付与已支付都可以取消（超时未支付 / 买家主动取消）")
    void cancellableBeforeShipping() {
        assertThat(OrderStatus.PENDING_PAY.canTransferTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.PAID.canTransferTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.SHIPPED.canTransferTo(OrderStatus.CANCELLED))
                .as("已发货后不可取消（货已经在路上）")
                .isFalse();
    }

    @Test
    @DisplayName("nextOnMainFlow 给出界面「一键推进」的目标；终态返回 null")
    void nextOnMainFlow() {
        assertThat(OrderStatus.PENDING_PAY.nextOnMainFlow()).isEqualTo(OrderStatus.PAID);
        assertThat(OrderStatus.PAID.nextOnMainFlow()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(OrderStatus.SHIPPED.nextOnMainFlow()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(OrderStatus.COMPLETED.nextOnMainFlow()).isNull();
        assertThat(OrderStatus.CANCELLED.nextOnMainFlow()).isNull();
    }

    @Test
    @DisplayName("中文文案与状态码一一对应（兼容层双写依赖这个映射）")
    void legacyLabelsMatchExistingData() {
        assertThat(OrderStatus.PAID.legacyLabel()).isEqualTo("等待发货");
        assertThat(OrderStatus.SHIPPED.legacyLabel()).isEqualTo("已发货");
        assertThat(OrderStatus.COMPLETED.legacyLabel()).isEqualTo("订单已完成");
        // 反查：数据库里的旧中文值能解析回枚举
        assertThat(OrderStatus.fromLegacyLabel("等待发货")).isEqualTo(OrderStatus.PAID);
        assertThat(OrderStatus.fromLegacyLabel("已发货")).isEqualTo(OrderStatus.SHIPPED);
        assertThat(OrderStatus.fromLegacyLabel("订单已完成")).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("of(): 解析合法编码（含大小写与空格容错），非法值抛业务异常而不是返回 null")
    void parseCode() {
        assertThat(OrderStatus.of("SHIPPED")).isEqualTo(OrderStatus.SHIPPED);
        assertThat(OrderStatus.of(" shipped ")).isEqualTo(OrderStatus.SHIPPED);
        assertThatThrownBy(() -> OrderStatus.of("跑路中"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的订单状态");
        assertThatThrownBy(() -> OrderStatus.of(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("未知的旧文案反查同样抛业务异常（避免脏数据被静默当成某个状态）")
    void unknownLegacyLabelRejected() {
        assertThatThrownBy(() -> OrderStatus.fromLegacyLabel("已签收"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知的订单状态文案");
    }

    @Test
    @DisplayName("流转表只包含已定义的状态，且 noneOfKeys 不会返回 null")
    void transitionTableIsWellFormed() {
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(s.nextStatuses()).as("%s 的 nextStatuses 不应为 null", s).isNotNull();
            s.nextStatuses().forEach(t -> assertThat(t).isNotNull());
        }
    }
}
