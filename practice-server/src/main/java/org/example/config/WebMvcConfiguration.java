package org.example.config;

import lombok.extern.slf4j.Slf4j;
//import org.example.interceptor.JwtTokenAdminInterceptor;
//import org.example.interceptor.JwtTokenUserInterceptor;
import org.example.json.JacksonObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

import java.util.List;

/**
 * 配置类，注册web层相关组件
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

//    @Autowired
//    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;
//
//    @Autowired
//    private JwtTokenUserInterceptor jwtTokenUserInterceptor;
//
//    /**
//     * 注册自定义拦截器
//     * @param registry
//     */
//    protected void addInterceptors(InterceptorRegistry registry) {
//        log.info("开始注册自定义拦截器...");
//        // 管理端拦截路径设计
//        registry.addInterceptor(jwtTokenAdminInterceptor)
//                .addPathPatterns("/admin/**")
//                .excludePathPatterns("/admin/employee/login")
//                .excludePathPatterns("/common/**");
////         C端（用户端）拦截路径设计
//        registry.addInterceptor(jwtTokenUserInterceptor)
//                .addPathPatterns("/user/**")
//                .excludePathPatterns("/user/user/login")
//                .excludePathPatterns("/user/shop/status");
//    }

    /**
     * 通过knife4j生成接口文档
     * @return
     */
    @Bean
    public Docket docket1() {
        log.info("准备生成接口文档");
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("练习项目的接口文档")
                .version("1.0")
                .description("练习项目的接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("管理端接口")
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("org.example.controller.admin"))
                .paths(PathSelectors.any())
                .build();
        return docket;
    }

    @Bean
    public Docket docket2() {
        log.info("准备生成接口文档");
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("练习项目的接口文档")
                .version("1.0")
                .description("练习项目的接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("用户端接口")
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("org.example.controller.user"))
                .paths(PathSelectors.any())
                .build();
        return docket;
    }

    /**
     * 设置静态资源映射
     * @param registry
     */
    @Override
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("开始静态资源映射");
        /**
         * /doc.html 是请求的路径模式,当用户在浏览器中访问 http://yourdomain.com/doc.html 时，
         *          Spring 将根据配置将这个请求映射到指定的资源位置。
         * addResourceLocations("classpath:/META-INF/resources/") 是资源的实际位置。它指定了静态资源的存放目录。
         *
         * /doc.html：通常用于 Swagger UI 的文档页面。你在引入 Swagger 或 Knife4j 等依赖时，
         *          doc.html 文件会被放置在 classpath:/META-INF/resources/ 目录下，用于展示 API 文档。
         */
        registry.addResourceHandler("/doc.html")
                .addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * 扩展 Spring MVC 框架的 消息转换器
     * @param converters
     */
    @Override
    protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 创建一个消息转换器对象
        /**
         * MappingJackson2HttpMessageConverter：这是 Spring MVC 中负责将 Java 对象与 JSON 互相转换的消息转换器。
         * 默认情况下，它使用标准的 ObjectMapper 进行转换。
         */
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        /**
         * 需要为消息转换器设置一个对象转换器，对象转换器可以将Java对象序列化为 json 数据
         *   JacksonObjectMapper类 是自定义的
         *   将自定义 JacksonObjectMapper 设置到消息转换器中。
         */
        converter.setObjectMapper(new JacksonObjectMapper());
        // 将自己的消息转换器加入到容器中，并在第一位
        converters.add(0, converter);
    }
}
