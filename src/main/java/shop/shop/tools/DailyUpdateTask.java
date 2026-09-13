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
        for (User user:searchList){
            if(user.getSearch() == null||user.getSearch().length() == 0){

            }else{
                HashMap<String, double[]> SearchDict = SearchProcess.stringToDict(user.getSearch());
                Iterator<Map.Entry<String, double[]>> iterator = SearchDict.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, double[]> entry = iterator.next();
                    String key = entry.getKey();
                    double[] value = entry.getValue();

                    // 处理键值对
                    value[0] += MathTools.derivative(value[1]+1,0.001)*value[2];
                    value[1] += 1;

                    if (value[0] < 0.1) {
                        // 删除满足条件的元素
                        iterator.remove();
                    } else {
                        entry.setValue(value);
                    }

                    String newSearch = SearchProcess.dictToString(SearchDict);
                    searchMapper.updateSearchByUserName(user.getUsername(), newSearch);
                }
            }
        }

    }
}
