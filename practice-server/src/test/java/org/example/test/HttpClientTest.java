package org.example.test;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;

import javax.swing.text.html.parser.Entity;

@SpringBootTest
@Slf4j
@Disabled("历史接口联调测试：依赖本机 8080 上已删除的旧接口，不属于自动化测试")
public class HttpClientTest {

    /**
     * 测试通过HttpClient发送GET方式的请求
     */
    @Test
    public void testGet() throws Exception{
        // 创建httpClient对象
        CloseableHttpClient httpClient = HttpClients.createDefault();

        // 创建http请求对象
        HttpGet httpGet = new HttpGet("http://localhost:8080/user/shop/status");

        // 发送请求，接收响应结果
        CloseableHttpResponse response = httpClient.execute(httpGet);

        // 获取服务端响应的服务代码和entity
        int statusCode = response.getStatusLine().getStatusCode();
        System.out.println("服务端返回的状态码为：" + statusCode);
        HttpEntity entity = response.getEntity();
        // 解析entity
        String body = EntityUtils.toString(entity);
        System.out.println("服务端返回的数据为：" + body);

        // 关闭资源
        response.close();
        httpClient.close();
    }

    /**
     * 测试通过HttpClient发送POST方式的请求
     */
    @Test
    public void testPost() throws Exception {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("http://localhost:8080/admin/employee/login");

        /**
         * 因为是 POST 请求，所以需要设置请求的 entity参数，我们使用String类型的json格式
         */
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("username", "admin");
        jsonObject.put("password", "123456");
        StringEntity entity = new StringEntity(jsonObject.toString());

        // 指定请求的编码方式
        entity.setContentEncoding("utf-8");
        // 数据格式
        entity.setContentType("application/json");

        httpPost.setEntity(entity);

        // 发送请求
        CloseableHttpResponse response = httpClient.execute(httpPost);
        // 解析返回结果
        int statusCode = response.getStatusLine().getStatusCode();
        System.out.println("服务端返回的状态码为：" + statusCode);
        HttpEntity entity1 = response.getEntity();
        // 解析entity
        String body = EntityUtils.toString(entity1);
        System.out.println("服务端返回的数据为：" + body);

        // 关闭资源
        response.close();
        httpClient.close();
    }


}
