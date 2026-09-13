package shop.shop.Bean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link shop.shop.Bean.CartItemRequest} 参数校验单元测试（JSR-303）。
 *
 * <p>项目原本靠一堆手写 if 判断参数合法性，现在改为声明式校验：
 * 注解放在字段上，Controller 加 {@code @Valid}，失败由 GlobalExceptionHandler 统一转成 400。
 * 这里直接对 Validator 断言，不启动 Spring 容器，毫秒级完成。
 */
class CartItemRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("imgPath 为 null 时应校验失败，且提示文案明确")
    void nullImgPath_isInvalid() {
        Set<ConstraintViolation<CartItemRequest>> violations = validator.validate(new CartItemRequest(null));

        assertFalse(violations.isEmpty(), "imgPath 是必填项，null 必须被拦下");
        assertEquals(1, violations.size());
        assertEquals("商品标识(imgPath)不能为空", violations.iterator().next().getMessage());
    }

    @Test
    @DisplayName("imgPath 为空串 / 空白串 也应校验失败（@NotBlank 会 trim）")
    void blankImgPath_isInvalid() {
        assertFalse(validator.validate(new CartItemRequest("")).isEmpty());
        assertFalse(validator.validate(new CartItemRequest("   ")).isEmpty());
    }

    @Test
    @DisplayName("正常 imgPath 应通过校验")
    void validImgPath_passes() {
        Set<ConstraintViolation<CartItemRequest>> violations =
                validator.validate(new CartItemRequest("/shop/assets/product-img/1.png"));

        assertTrue(violations.isEmpty());
    }
}
