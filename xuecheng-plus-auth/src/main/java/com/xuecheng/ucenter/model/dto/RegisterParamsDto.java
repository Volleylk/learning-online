package com.xuecheng.ucenter.model.dto;

import lombok.Data;


@Data
public class RegisterParamsDto {
    private String cellphone;//手机号
    private String username; //用户名
    private String email;
    private String nickname;//昵称
    private String password;
    private String confirmpwd;
    private String checkcode;//验证码
    private String checkcodekey;//验证码key
}
