package org.example.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@SpringBootTest
@Disabled("外部 Redis 联调测试：需要真实环境，默认构建不执行")
public class SpringDataRedisTest {
    @Autowired
    private RedisTemplate redisTemplate;

    @Test
    public void testRedisTemplate() {
//        redisTemplate.opsForValue().set("name", "小明");
        System.out.println(redisTemplate);
    }
}
