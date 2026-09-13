// ---------------------------------------------------------------------------
// 统一响应体工具
// 后端所有 JSON 接口统一返回 {code, message, success, data}，
// 这里集中判定，避免每个调用点重复写 result.code === 200。
// ---------------------------------------------------------------------------
function isBizOk(result) {
    return !!result && result.success === true;
}

function toast(message) {
    var popup = document.createElement('div');
    popup.className = 'overlay';
    var modal = document.createElement('div');
    modal.className = 'modal';
    var content = document.createElement('div');
    content.innerHTML = '<p>' + message + '</p>';
    modal.appendChild(content);
    popup.appendChild(modal);
    document.body.appendChild(popup);
    setTimeout(function () {
        document.body.removeChild(popup);
    }, 1000);
}

// 获取所有的"加入购物车"按钮
var addToCartButtons = document.getElementsByClassName("add_to_cart");

// 遍历每个按钮并添加点击事件监听
for (var i = 0; i < addToCartButtons.length; i++) {
    addToCartButtons[i].addEventListener("click", function () {
        var currentButton = this;

        // 先探测登录态，未登录直接去登录页
        fetch('/shop/checkSession', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        })
            .then(function (response) {
                return response.json();
            })
            .then(function (result) {
                // 【修复】后端返回结构已从裸 Boolean 改为统一响应体 Result，
                // 原来的 result === true 在改造后永远不成立 —— 会导致「点一下加入购物车就跳登录页」。
                // 正确取法：result.success（业务是否成功）与 result.data（业务数据）。
                if (!isBizOk(result) || result.data !== true) {
                    window.location.href = "/shop/login";
                    return;
                }

                var productContainer = currentButton.parentElement;
                var imgPath = productContainer.querySelector("img").getAttribute("src");

                return fetch("/shop/addToCart", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    body: JSON.stringify({
                        imgPath: imgPath
                    })
                })
                    .then(function (response) {
                        return response.json();
                    })
                    .then(function (cartResult) {
                        // 【改进】原代码不看响应体，无论后端成功与否都弹「已加入购物车」，
                        // 失败时用户完全无感知。现在按 success 判断并展示后端返回的原因。
                        if (!isBizOk(cartResult)) {
                            toast(cartResult && cartResult.message ? cartResult.message : '加入购物车失败');
                            return;
                        }
                        var row = currentButton.parentNode;
                        // 从 DOM 中移除该行
                        row.remove();
                        toast('已加入购物车');
                        // 【修复】原为 window.location.href = "redirect:/shop/index" ——
                        // 这是把 Spring 的视图前缀当成了 URL，浏览器会去请求名为
                        // "redirect:" 的相对路径，必然 404，且完全没有必要跳转。
                        window.location.href = "/shop/index";
                    });
            })
            .catch(function (error) {
                console.error(error);
                toast('请求失败，请稍后重试');
            });
    });
}
