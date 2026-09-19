package org.example.security.access;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.dataset-access")
public class NodeAccessTokenProperties {
    private String hmacSecret = "topic4-acceptance-hmac-secret-change-before-production";

    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
}
