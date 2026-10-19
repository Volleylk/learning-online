package com.xuecheng.ucenter.service;

import com.xuecheng.ucenter.model.po.XcUser;

/**
 * 微信认证接口
 */
public interface WxAuthService {

    /**
     * 微信扫码认证：用 code 换 access_token，再查用户信息，最后落库
     * @param code 微信回调下发的授权码
     * @return 本项目用户
     */
    XcUser wxAuth(String code);
}
