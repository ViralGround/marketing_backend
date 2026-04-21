package com.viralground.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=test-secret-key-at-least-32-characters-long",
        "resend.api-key=test",
        "app.url=http://localhost:3000",
        "app.admin-emails=",
        "cors.allowed-origins=http://localhost:3000"
})
class MarketingBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
