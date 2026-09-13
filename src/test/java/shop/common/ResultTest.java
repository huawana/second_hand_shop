package shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Result} 与 {@link ErrorCode} 单元测试。
 */
class ResultTest {

    @Test
    @DisplayName("success()：code=200 且 success 为 true")
    void success_withoutData() {
        Result<Void> result = Result.success();

        assertEquals(200, result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertNull(result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("success(data)：数据原样携带")
    void success_withData() {
        Result<Boolean> result = Result.success(true);

        assertTrue(result.isSuccess());
        assertEquals(true, result.getData());
    }

    @Test
    @DisplayName("fail(ErrorCode)：应带上错误码与默认文案，且 success 为 false")
    void fail_withErrorCode() {
        Result<Void> result = Result.fail(ErrorCode.UNAUTHORIZED);

        assertEquals(401, result.getCode());
        assertEquals("未登录或登录状态已过期", result.getMessage());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("fail(ErrorCode, message)：自定义文案应覆盖默认文案")
    void fail_withCustomMessage() {
        Result<Void> result = Result.fail(ErrorCode.NOT_FOUND, "商品不存在");

        assertEquals(404, result.getCode());
        assertEquals("商品不存在", result.getMessage());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("错误码不应重复（防止后续新增时手滑复制）")
    void errorCodes_areUnique() {
        java.util.Set<Integer> codes = new java.util.HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(codes.add(code.getCode()),
                    "错误码重复：" + code + " -> " + code.getCode());
        }
    }
}
