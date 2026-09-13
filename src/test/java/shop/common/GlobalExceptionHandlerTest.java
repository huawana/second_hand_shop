package shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalExceptionHandler} 单元测试。
 *
 * <p>重点验证两件事：
 * <ol>
 *   <li>业务错误码能正确映射到 HTTP 状态码（网关/监控/浏览器都依赖这个）</li>
 *   <li><b>系统异常不泄露内部信息</b> —— 这是安全红线，必须用测试锁住</li>
 * </ol>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest requestUri(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    @DisplayName("业务异常：HTTP 状态码与业务错误码应同步映射（401）")
    void bizException_mapsTo401() {
        ResponseEntity<Result<Void>> response = handler.handleBizException(
                new BizException(ErrorCode.UNAUTHORIZED), requestUri("/shop/addToCart"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().getCode());
        assertEquals("未登录或登录状态已过期", response.getBody().getMessage());
    }

    @Test
    @DisplayName("业务异常：403 / 404 / 500 均应正确映射")
    void bizException_mapsOtherCodes() {
        assertEquals(HttpStatus.FORBIDDEN, handler.handleBizException(
                new BizException(ErrorCode.FORBIDDEN), requestUri("/x")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, handler.handleBizException(
                new BizException(ErrorCode.NOT_FOUND), requestUri("/x")).getStatusCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, handler.handleBizException(
                new BizException(ErrorCode.BIZ_ERROR), requestUri("/x")).getStatusCode());
    }

    @Test
    @DisplayName("自定义业务文案应原样返回给前端")
    void bizException_keepsCustomMessage() {
        ResponseEntity<Result<Void>> response = handler.handleBizException(
                new BizException(ErrorCode.FORBIDDEN, "只能下架自己发布的商品"),
                requestUri("/shop/deleteMyRelease"));

        assertEquals("只能下架自己发布的商品", response.getBody().getMessage());
    }

    @Test
    @DisplayName("系统异常：返回 500 且【不泄露】内部细节（表名/路径/堆栈）")
    void systemException_doesNotLeakInternals() {
        IllegalStateException internal = new IllegalStateException(
                "Table 'shop.lxy_user' doesn't exist, SQL: select * from lxy_user, path=C:\\secret\\app");

        ResponseEntity<Result<Void>> response =
                handler.handleException(internal, requestUri("/shop/index"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        String message = response.getBody().getMessage();

        assertEquals(ErrorCode.SYSTEM_ERROR.getMessage(), message);
        assertFalse(message.contains("lxy_user"), "不能把表名返回给客户端");
        assertFalse(message.contains("C:\\secret"), "不能把服务器路径返回给客户端");
        assertFalse(message.contains("SQL"), "不能把 SQL 返回给客户端");
    }

    @Test
    @DisplayName("系统异常：对外只给兜底文案，但 data 必须为 null（不夹带异常对象）")
    void systemException_bodyIsClean() {
        ResponseEntity<Result<Void>> response =
                handler.handleException(new RuntimeException("boom"), requestUri("/x"));

        assertFalse(response.getBody().isSuccess());
        assertEquals(null, response.getBody().getData());
    }

    @Test
    @DisplayName("兜底处理器必须能接住任意 Exception（含 Error 之外的受检异常）")
    void systemException_catchesCheckedException() {
        ResponseEntity<Result<Void>> response =
                handler.handleException(new java.io.IOException("disk full"), requestUri("/x"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(response.getBody().getMessage().equals(ErrorCode.SYSTEM_ERROR.getMessage()));
    }
}
