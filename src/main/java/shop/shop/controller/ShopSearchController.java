package shop.shop.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import shop.admin.Bean.Product;
import shop.admin.mapper.ProductMapper;
import shop.admin.mapper.UserMapper;
import shop.shop.mapper.SearchMapper;
import shop.shop.tools.SearchProcess;
import shop.shop.tools.SessionCheck;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;

@Controller
public class ShopSearchController {

    @Autowired
    ProductMapper productMapper;
    @Autowired
    UserMapper userMapper;
    @Autowired
    SearchMapper searchMapper;

    @PostMapping("/shop/search")
    public String search(String query, HttpServletRequest request, Model m){
        HttpSession session = request.getSession();
        String username = (String) session.getAttribute("shopusername");
        List<Product> productList = productMapper.getProductBySearch(username,"%"+query+"%");
        m.addAttribute("products",productList);
        m.addAttribute("shopusername", session.getAttribute("shopusername"));
        SessionCheck.checkSessionPosition(session,m);
        SessionCheck.checkSessionSchool(session,m);
        if(!SessionCheck.checkSessionName(session)){
            String search = searchMapper.getSearchByUserName(username);
            if(search==null||search.length()==0){
                String text = query+":[1,0,1]";
                searchMapper.updateSearchByUserName(username,text);
            }else{
                HashMap<String, double[]> searchList = SearchProcess.stringToDict(search);
                if(searchList.containsKey(query)){
                    double[] value = searchList.get(query);
                    value[0]+=1;
                    value[1]=0;
                    value[2]=value[0];
                }else{
                    double[] value = {1,0,1};
                    searchList.put(query,value);
                }
                String newSearch = SearchProcess.dictToString(searchList);
                searchMapper.updateSearchByUserName(username,newSearch);
            }
        }
        return "shop/index";
    }
}
