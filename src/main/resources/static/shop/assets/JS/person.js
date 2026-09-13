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

var deleteButtons = document.getElementsByClassName("deleteMyRelease");

// 遍历每个按钮并添加点击事件监听
for (var i = 0; i < deleteButtons.length; i++) {
    deleteButtons[i].addEventListener("click", function (event) {
        event.preventDefault();
        var productContainer = this.parentElement.parentElement;
        var imgPath = productContainer.querySelector("img").getAttribute("src");

        // 保存 this 的引用，以在内部函数中使用
        var currentButton = this;

        fetch("/shop/deleteMyRelease", {
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
                // 【改进】原代码只看 response.ok，后端返回 200 + 业务失败时也会提示「删除成功」。
                if (!isBizOk(result)) {
                    toast(result && result.message ? result.message : '删除失败');
                    return;
                }
                var row = currentButton.parentNode.parentNode;
                // 从 DOM 中移除该行
                row.remove();
                toast('删除成功');
            })
            .catch(function (error) {
                console.error(error);
                toast('请求失败，请稍后重试');
            });
    });
}


function changeSellerStatus(event) {
    event.preventDefault();

    // 通过事件的目标元素（即被点击的链接）来获取其所在的行元素
    var row = event.target.closest('tr');

    // 在行元素中查找img元素，并获取其src属性值
    var imgPath = row.querySelector('img').getAttribute('src');
    var status = row.querySelector('.status');
    var handle = row.querySelector('.handle');
    var statusContent = status.textContent;

    // 【Bug 修复】原为 if(status==="无")：status 是 DOM 元素对象、右边是字符串，
    // 两者永不相等 → 条件恒为 false，这个「已完成的订单不可再操作」的保护形同虚设。
    // 应该比较文本内容 statusContent。
    if (statusContent === "无" || statusContent === "订单已完成") {
        return;
    }
    sendChangeStatus(imgPath, status, handle, statusContent);
}


function changeBuyerStatus(event) {
    event.preventDefault();

    var row = event.target.closest('tr');
    var imgPath = row.querySelector('img').getAttribute('src');
    var status = row.querySelector('.status');
    var handle = row.querySelector('.handle');
    var statusContent = status.textContent;
    var handleContent = handle.textContent;

    if (handleContent === "无") {
        return;
    }
    sendChangeStatus(imgPath, status, handle, statusContent);
}


/**
 * 抽取出来的公共请求逻辑（原来 changeSellerStatus / changeBuyerStatus 里是两份重复代码）。
 */
function sendChangeStatus(imgPath, status, handle, statusContent) {
    fetch("/shop/changeStatus", {
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
            if (!isBizOk(result)) {
                toast(result && result.message ? result.message : '订单进度更新失败');
                return;
            }
            if (statusContent === "等待发货") {
                status.textContent = "已发货";
                handle.textContent = "无";
            } else if (statusContent === "已发货") {
                status.textContent = "订单已完成";
                handle.textContent = "无";
            }
            toast('订单进度更新成功');
        })
        .catch(function (error) {
            console.error(error);
            toast('请求失败，请稍后重试');
        });
}
