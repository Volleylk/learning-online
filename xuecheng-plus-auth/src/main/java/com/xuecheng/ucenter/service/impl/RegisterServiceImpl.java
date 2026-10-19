package com.xuecheng.ucenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.ucenter.mapper.XcUserMapper;
import com.xuecheng.ucenter.mapper.XcUserRoleMapper;
import com.xuecheng.ucenter.model.dto.RegisterParamsDto;
import com.xuecheng.ucenter.model.po.XcUser;
import com.xuecheng.ucenter.model.po.XcUserRole;
import com.xuecheng.ucenter.service.RegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RegisterServiceImpl implements RegisterService {
    private final XcUserMapper xcUserMapper;
    private final XcUserRoleMapper xcUserRoleMapper;
    private final PasswordEncoder passwordEncoder;   // 注入 BCryptPasswordEncoder Bean

    @Override
    @Transactional
    public void register(RegisterParamsDto registerParamsDto) {
        // 校验验证码
        // 校验密码一致
        if (!registerParamsDto.getPassword().equals(registerParamsDto.getConfirmpwd())) {
            throw new RuntimeException("密码不一致");
        }
        // 校验用户是否存在
        XcUser exist = xcUserMapper.selectOne(
                new LambdaQueryWrapper<XcUser>()
                        .eq(XcUser::getUsername, registerParamsDto.getUsername()));
        if (exist != null) {
            throw new RuntimeException("用户已存在");
        }
        // 插入用户表
        XcUser user = new XcUser();
        BeanUtils.copyProperties(registerParamsDto, user);
        user.setPassword(passwordEncoder.encode(registerParamsDto.getPassword()));
        user.setName(registerParamsDto.getNickname());
        user.setUtype("101001");
        user.setStatus("1");
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        xcUserMapper.insert(user);
        // 插入用户角色表（学生）
        XcUserRole xcUserRole = new XcUserRole();
        xcUserRole.setUserId(user.getId());
        xcUserRole.setRoleId("17");
        xcUserRole.setCreateTime(LocalDateTime.now());
        xcUserRoleMapper.insert(xcUserRole);
    }
}
