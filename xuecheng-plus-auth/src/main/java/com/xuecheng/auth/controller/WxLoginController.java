package com.xuecheng.auth.controller;

import com.xuecheng.ucenter.service.WxAuthService;
import com.xuecheng.ucenter.model.po.XcUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 微信扫码登录回调
 */
@Slf4j
@Controller
public class WxLoginController {

    @Autowired
    WxAuthService wxAuthService;

    /**
     * 微信扫码授权后重定向到这里，携带 code、state
     */
    @RequestMapping("/wxLogin")
    public String wxLogin(String code, String state) {
        log.debug("微信扫码回调, code:{}, state:{}", code, state);
        XcUser xcUser = wxAuthService.wxAuth(code);
        if (xcUser == null) {
            return "redirect:http://localhost/error.html";
        }
        String username = xcUser.getUsername();
        // 回到登录页，由前端自动登录
        return "redirect:http://localhost/sign.html?username=" + username + "&authType=wx";
    }
}
