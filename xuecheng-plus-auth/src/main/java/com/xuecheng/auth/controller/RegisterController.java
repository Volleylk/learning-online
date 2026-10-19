package com.xuecheng.auth.controller;

import com.xuecheng.ucenter.model.dto.RegisterParamsDto;
import com.xuecheng.ucenter.service.impl.RegisterServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class RegisterController {
    private final RegisterServiceImpl registerService;
    @PostMapping("/api/auth/register")
    public void register(RegisterParamsDto registerParamsDto){
        registerService.register(registerParamsDto);
    }
}
