// ---------------------------------------------------------------------------
// 统一响应体工具
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

var deleteButtons = document.getElementsByClassName("delete_product");

// 遍历每个删除按钮，为其添加点击事件监听器
for (var i = 0; i < deleteButtons.length; i++) {
    (function () {
        var button = deleteButtons[i];
        button.addEventListener("click", function () {
            // 获取物品的 ID
            var productId = $(this).data('productid');

            // 保存删除链接所在的行元素
            var row = $(this).closest('tr');

            // 执行 AJAX 请求
            // 注意：该接口返回的是 redirect:/shop/cart（页面），不是 JSON，
            // 所以这里不用 isBizOk 判断，只看 HTTP 是否成功。
            $.ajax({
                url: '/shop/deleteProduct',
                method: 'POST',
                data: {productId: productId},
                success: function (response) {
                    // 从 DOM 中移除该行
                    row.remove();
                    toast('删除成功');
                },
                error: function (error) {
                    toast('删除失败，请稍后重试');
                }
            });
        });
    })();
}


// 获取所有的"结算/立即购买"按钮
var buyButtons = document.getElementsByClassName("buy_product");

// 遍历每个按钮并添加点击事件监听
for (var j = 0; j < buyButtons.length; j++) {
    buyButtons[j].addEventListener("click", function (event) {
        event.preventDefault();
        var productContainer = this.parentElement.parentElement;
        var imgPath = productContainer.querySelector("img").getAttribute("src");

        fetch("/shop/buySuccess", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({imgPath: imgPath})
        })
            .then(function (response) {
                return response.json();
            })
            .then(function (result) {
                // 【改进】原代码只看 response.ok 就跳转，业务失败时会跳到下单成功页再被弹回首页，
                // 用户看到的是「莫名其妙回到首页」。现在把失败原因直接展示出来。
                if (!isBizOk(result)) {
                    if (result && result.code === 401) {
                        window.location.href = "/shop/login";
                        return;
                    }
                    toast(result && result.message ? result.message : '下单失败');
                    return;
                }
                window.location.href = "/shop/buySuccess";
            })
            .catch(function (error) {
                console.error(error);
                toast('请求失败，请稍后重试');
            });
    });
}
