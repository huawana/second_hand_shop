package shop.shop.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SessionCheck} 单元测试。
 *
 * <p>重点覆盖本轮修复的「{@code != "null"} 与 {@code != null}」问题：
 * 改之前 else 分支是死代码，"空" 占位永远不会被塞进 Model。
 */
class SessionCheckTest {

    @Test
    @DisplayName("未登录时 checkSessionName 应返回 true，并写入『请登录』哨兵值")
    void checkSessionName_notLoggedIn() {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();

        assertEquals(true, SessionCheck.checkSessionName(session));
        assertEquals("请登录", session.getAttribute("shopusername"));
    }

    @Test
    @DisplayName("已登录时 checkSessionName 应返回 false")
    void checkSessionName_loggedIn() {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("shopusername", "alice");

        assertEquals(false, SessionCheck.checkSessionName(session));
    }

    @Test
    @DisplayName("school 为 null 时应放入『空』占位（修复前这段 else 永远走不到）")
    void checkSessionSchool_nullPutsPlaceholder() {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        SessionCheck.checkSessionSchool(session, model);

        assertEquals("空", model.getAttribute("school"));
    }

    @Test
    @DisplayName("school 有值时应原样放入 Model")
    void checkSessionSchool_putsValue() {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("school", "清华大学");
        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        SessionCheck.checkSessionSchool(session, model);

        assertEquals("清华大学", model.getAttribute("school"));
    }

    @Test
    @DisplayName("province 为 null 时省市区三项都应放入『空』占位")
    void checkSessionPosition_nullPutsPlaceholders() {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        SessionCheck.checkSessionPosition(session, model);

        assertEquals("空", model.getAttribute("province"));
        assertEquals("空", model.getAttribute("city"));
        assertEquals("空", model.getAttribute("area"));
    }

    @Test
    @DisplayName("province 有值时省市区应原样放入 Model")
    void checkSessionPosition_putsValues() {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute("province", "北京市");
        session.setAttribute("city", "北京市");
        session.setAttribute("area", "海淀区");
        org.springframework.ui.ExtendedModelMap model = new org.springframework.ui.ExtendedModelMap();

        SessionCheck.checkSessionPosition(session, model);

        assertEquals("北京市", model.getAttribute("province"));
        assertEquals("海淀区", model.getAttribute("area"));
    }
}
