/*
 * ===========================================================================
 *  CSRF 客户端配合脚本
 * ===========================================================================
 *
 *  为什么需要它：
 *  Spring Security 的 CSRF 防护要求「非安全方法」（POST/PUT/PATCH/DELETE）必须
 *  回传服务端下发的 token。表单由 Thymeleaf 的 th:action 自动注入 _csrf 隐藏域，
 *  但 fetch / $.ajax 发起的请求没有表单，必须由 JS 主动把 token 放进请求头。
 *
 *  token 从哪来：
 *  SecurityConfig 里用的是 CookieCsrfTokenRepository.withHttpOnlyFalse()，
 *  它会把 token 同时写进一个名为 XSRF-TOKEN 的 Cookie（特意不是 HttpOnly，
 *  就是为了让 JS 能读到）。本脚本读取该 Cookie，回填到 X-XSRF-TOKEN 请求头。
 *
 *  为什么用「包装 fetch」而不是在每个调用点手动加头：
 *  项目里有 7 处 fetch 调用，散落在 4 个 JS 文件里。逐个改不仅啰嗦，
 *  更危险的是「以后新增的调用点很可能会忘」—— 而忘记的后果是那个功能直接 403，
 *  且报错信息（CSRF token 缺失）与业务毫无关系，排查成本高。
 *  在入口处统一包装是一次性投入、永久生效的做法。
 */
(function () {
    'use strict';

    var COOKIE_NAME = 'XSRF-TOKEN';
    var HEADER_NAME = 'X-XSRF-TOKEN';
    // 安全方法不需要 token（它们本来就不应该产生副作用）
    var SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS', 'TRACE'];

    /** 从 document.cookie 里取出 CSRF token（原生 Cookie API 没有按名查询的方法） */
    function readCsrfToken() {
        var prefix = COOKIE_NAME + '=';
        var cookies = document.cookie ? document.cookie.split(';') : [];
        for (var i = 0; i < cookies.length; i++) {
            var item = cookies[i].trim();
            if (item.indexOf(prefix) === 0) {
                return decodeURIComponent(item.substring(prefix.length));
            }
        }
        return null;
    }

    function isUnsafeMethod(method) {
        return method && SAFE_METHODS.indexOf(String(method).toUpperCase()) === -1;
    }

    // -----------------------------------------------------------------------
    // 1) 包装原生 fetch
    //    只处理「字符串 URL + 普通对象 init」这种用法（本项目全部如此）；
    //    传入 Request 对象的场景不拦截，避免破坏其不可变语义。
    // -----------------------------------------------------------------------
    if (typeof window.fetch === 'function') {
        var originalFetch = window.fetch.bind(window);

        window.fetch = function (input, init) {
            if (typeof input === 'string') {
                var method = (init && init.method) || 'GET';
                if (isUnsafeMethod(method)) {
                    var token = readCsrfToken();
                    if (token) {
                        // 复制一份 init，不修改调用方传入的对象（避免难以追踪的副作用）
                        var patched = {};
                        for (var key in init) {
                            if (Object.prototype.hasOwnProperty.call(init, key)) {
                                patched[key] = init[key];
                            }
                        }
                        var headers = new Headers(init.headers || undefined);
                        if (!headers.has(HEADER_NAME)) {
                            headers.set(HEADER_NAME, token);
                        }
                        patched.headers = headers;
                        return originalFetch(input, patched);
                    }
                }
            }
            return originalFetch(input, init);
        };
    }

    // -----------------------------------------------------------------------
    // 2) jQuery $.ajax 全局默认（后端 jQuery 插件的 beforeSend 钩子）
    // -----------------------------------------------------------------------
    if (window.jQuery) {
        window.jQuery.ajaxSetup({
            beforeSend: function (xhr, settings) {
                if (isUnsafeMethod(settings.type)) {
                    var token = readCsrfToken();
                    if (token) {
                        xhr.setRequestHeader(HEADER_NAME, token);
                    }
                }
            }
        });
    }

    // 供调试：控制台里执行 readCsrfToken() 可确认 token 是否已下发
    window.readCsrfToken = readCsrfToken;
})();
