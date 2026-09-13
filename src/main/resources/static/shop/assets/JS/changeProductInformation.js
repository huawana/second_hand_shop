document.getElementById('changeForm').addEventListener('submit', function(event) {
    // 获取价格输入框的值
    var price = parseFloat(document.getElementById('price').value);

    // 检查价格是否小于0
    if (price < 0) {
        event.preventDefault(); // 阻止表单提交

        // 显示错误信息
        var errorLabel = document.querySelector('.error');
        errorLabel.textContent = "商品价格不能小于0";
        errorLabel.style.display = 'block'; // 显示错误信息标签

        // 清空价格输入框，以便用户可以重新输入
        document.getElementById('price').value = '';

        // 焦点移动到价格输入框
        document.getElementById('price').focus();

        // 可以在这里添加其他逻辑，比如动画效果等

        // 注意：不要调用 location.reload()，因为这会丢失所有更改
    }
});