package shop.shop.tools;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shop.admin.Bean.User;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.mapper.SearchMapper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class DailyUpdateTask {

    private static UserMapper userMapper;
    private static SearchMapper searchMapper;
    @Autowired
    public DailyUpdateTask(UserMapper userMapper,SearchMapper searchMapper) {
        DailyUpdateTask.userMapper = userMapper;
        DailyUpdateTask.searchMapper = searchMapper;
    }

    @Scheduled(cron = "0 0 0 * * ?") // 每天凌晨执行，可根据需求调整定时表达式
    public void performUpdate() {
        List<User> searchList = userMapper.getUsers();
        for (User user : searchList) {
            String search = user.getSearch();
            if (search == null || search.length() == 0) {
                continue;
            }
            HashMap<String, double[]> searchDict = SearchProcess.stringToDict(search);
            Iterator<Map.Entry<String, double[]>> iterator = searchDict.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, double[]> entry = iterator.next();
                double[] value = entry.getValue();

                // 搜索词权重按天数衰减：value = [当前权重, 已衰减天数, 初始权重]
                // derivative() 是 f(x)=e^(-(x/10)^4) 的中心差分导数，天数越大导数越趋近 0，
                // 所以衰减幅度随天数收敛 —— 久远的搜索词权重下降越来越慢（长尾保留）
                value[0] += MathTools.derivative(value[1] + 1, 0.001) * value[2];
                value[1] += 1;

                if (value[0] < 0.1) {
                    // 权重低于阈值，淘汰该关键词
                    iterator.remove();
                }
                // value 是数组引用，原地修改已经生效，原本的 entry.setValue(value) 是多余的
            }
            // 【Bug 修复】原代码把下面这行写在了 while 循环「体内」，有两个缺陷：
            //   1) 每个关键词都会触发一次 UPDATE，一个用户有 N 个关键词就写 N 次库
            //   2) 若所有关键词都被淘汰（remove 干净），循环体一次都不执行 →
            //      新的空串永远不会写回，失效的搜索历史会永久残留在库里
            // 移到循环外统一写回一次。
            searchMapper.updateSearchByUserName(user.getUsername(), SearchProcess.dictToString(searchDict));
        }
    }
}
