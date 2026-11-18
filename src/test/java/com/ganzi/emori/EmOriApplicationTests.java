package com.ganzi.emori;

import com.ganzi.emori.asmr.AsmrProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=${EMORI_CONTEXT_TEST_DB_URL:jdbc:h2:mem:context_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
class EmOriApplicationTests {
    @Autowired AsmrProvider asmrProvider;

    @Test
    void contextLoads() {
        assertThat(asmrProvider.available()).isFalse();
    }

}
