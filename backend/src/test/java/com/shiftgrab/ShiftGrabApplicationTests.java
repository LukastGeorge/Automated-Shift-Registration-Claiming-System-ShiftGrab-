package com.shiftgrab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Boots the full application against real PostgreSQL: Flyway migrates, Hibernate validates the schema. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShiftGrabApplicationTests {

    @Test
    void contextLoads() {
    }
}
