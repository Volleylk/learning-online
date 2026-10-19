package com.xuecheng.content.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2WebMvc;

/**
 * @author Mr.M
 * @version 1.0
 * @description Knife4j 接口文档配置（替换原 com.spring4all swagger-spring-boot-starter 的 yml 配置方式）
 * @date 2023/2/11 16:00
 */
@Configuration
@EnableSwagger2WebMvc
public class Knife4jConfig {

    @Bean
    public Docket docket() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                // 扫描接口所在的包
                .apis(RequestHandlerSelectors.basePackage("com.xuecheng.content"))
                .paths(PathSelectors.any())
                .build();
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("学成在线内容管理系统")
                .description("内容系统管理系统对课程相关信息进行管理")
                .version("1.0.0")
                .build();
    }
}
