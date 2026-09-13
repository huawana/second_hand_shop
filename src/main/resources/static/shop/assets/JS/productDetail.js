// ---------------------------------------------------------------------------
// 统一响应体工具（同 index.js，各页面各自引入，避免额外依赖一个公共文件）
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
    addToCartButtons[i].addEventListener("click", function (event) {
        event.preventDefault(); // 阻止默认的链接点击行为

        var productContainer = this.closest(".container");
        var imgPath = productContainer.querySelector(".left img").getAttribute("src");

        // 发送Ajax请求
        fetch("/shop/addToCart", {
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
            .then(function (result) {
                // 【改进】原代码不看响应体，未登录/商品不存在时也会弹「已加入购物车」并跳首页。
                // 现在未登录去登录页，其它失败展示后端返回的原因。
                if (!isBizOk(result)) {
                    if (result && result.code === 401) {
                        window.location.href = "/shop/login";
                        return;
                    }
                    toast(result && result.message ? result.message : '加入购物车失败');
                    return;
                }
                toast('已加入购物车');
                window.location.href = "/shop/index";
            })
            .catch(function (error) {
                console.error(error);
                toast('请求失败，请稍后重试');
            });
    });
}
