package com.xuecheng.ucenter.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.ucenter.mapper.XcUserMapper;
import com.xuecheng.ucenter.mapper.XcUserRoleMapper;
import com.xuecheng.ucenter.model.dto.AuthParamsDto;
import com.xuecheng.ucenter.model.dto.XcUserExt;
import com.xuecheng.ucenter.model.po.XcUser;
import com.xuecheng.ucenter.model.po.XcUserRole;
import com.xuecheng.ucenter.service.AuthService;
import com.xuecheng.ucenter.service.WxAuthService;
import org.springframework.beans.BeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 微信认证实现
 */
@Slf4j
@Service("wx_authservice")
public class WxAuthServiceImpl implements WxAuthService, AuthService {

    @Autowired
    XcUserMapper xcUserMapper;

    @Autowired
    XcUserRoleMapper xcUserRoleMapper;

    @Autowired
    RestTemplate restTemplate;

    @Value("${weixin.appid}")
    String appid;

    @Value("${weixin.secret}")
    String secret;

    @Autowired
    WxAuthServiceImpl currentProxy;

    @Override
    public XcUser wxAuth(String code) {
        // 1. 用 code 换 access_token
        Map<String, String> accessTokenMap = getAccessToken(code);
        if (accessTokenMap == null) {
            return null;
        }
        String accessToken = accessTokenMap.get("access_token");
        String openid = accessTokenMap.get("openid");

        // 2. 用 access_token + openid 查微信用户信息
        Map<String, String> userinfoMap = getUserinfo(accessToken, openid);
        if (userinfoMap == null) {
            return null;
        }

        // 3. 用户不存在则入库
        return currentProxy.addWxUser(userinfoMap);
    }

    @Override
    public XcUserExt execute(AuthParamsDto authParamsDto) {
        // 微信登录时 username 即 unionid（wxLogin 回调时已落库）
        String username = authParamsDto.getUsername();
        XcUser user = xcUserMapper.selectOne(
                new LambdaQueryWrapper<XcUser>().eq(XcUser::getUsername, username));
        if (user == null) {
            throw new RuntimeException("微信用户不存在");
        }
        XcUserExt ext = new XcUserExt();
        BeanUtils.copyProperties(user, ext);
        return ext;
    }

    /**
     * 申请微信 access_token
     */
    private Map<String, String> getAccessToken(String code) {
        String urlTemplate = "https://api.weixin.qq.com/sns/oauth2/access_token?appid=%s&secret=%s&code=%s&grant_type=authorization_code";
        String url = String.format(urlTemplate, appid, secret, code);
        log.info("调用微信申请access_token, url:{}", url);
        ResponseEntity<String> exchange = restTemplate.exchange(url, HttpMethod.POST, null, String.class);
        String result = exchange.getBody();
        log.info("微信返回access_token: {}", result);
        return JSON.parseObject(result, Map.class);
    }

    /**
     * 获取微信用户信息
     */
    private Map<String, String> getUserinfo(String accessToken, String openid) {
        String urlTemplate = "https://api.weixin.qq.com/sns/userinfo?access_token=%s&openid=%s";
        String url = String.format(urlTemplate, accessToken, openid);
        log.info("调用微信获取用户信息, url:{}", url);
        ResponseEntity<String> exchange = restTemplate.exchange(url, HttpMethod.POST, null, String.class);
        // 防止中文昵称乱码转码
        String result = new String(exchange.getBody().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        log.info("微信返回用户信息: {}", result);
        return JSON.parseObject(result, Map.class);
    }

    /**
     * 用户不存在则保存
     */
    @Transactional
    public XcUser addWxUser(Map userInfoMap) {
        String unionid = userInfoMap.get("unionid").toString();
        XcUser exist = xcUserMapper.selectOne(
                new LambdaQueryWrapper<XcUser>().eq(XcUser::getWxUnionid, unionid));
        if (exist != null) {
            return exist;
        }
        String userId = UUID.randomUUID().toString();
        XcUser xcUser = new XcUser();
        xcUser.setId(userId);
        xcUser.setWxUnionid(unionid);
        xcUser.setNickname(userInfoMap.get("nickname").toString());
        xcUser.setUserpic(userInfoMap.get("headimgurl").toString());
        xcUser.setName(userInfoMap.get("nickname").toString());
        xcUser.setUsername(unionid);
        xcUser.setPassword(unionid);
        xcUser.setUtype("101001"); // 学生
        xcUser.setStatus("1");
        xcUser.setCreateTime(LocalDateTime.now());
        xcUserMapper.insert(xcUser);

        XcUserRole role = new XcUserRole();
        role.setId(UUID.randomUUID().toString());
        role.setUserId(userId);
        role.setRoleId("17"); // 学生角色
        xcUserRoleMapper.insert(role);
        return xcUser;
    }
}
