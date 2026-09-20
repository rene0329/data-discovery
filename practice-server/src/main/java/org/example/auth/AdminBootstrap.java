package org.example.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final AdminIdentityService service;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminBootstrap(AdminIdentityService service,
                          @Value("${app.auth.bootstrap-admin.username:}") String username,
                          @Value("${app.auth.bootstrap-admin.password:}") String password,
                          @Value("${app.auth.bootstrap-admin.display-name:System Administrator}") String displayName) {
        this.service = service; this.username = username; this.password = password; this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.bootstrapAdmin(username, password, displayName);
    }
}
