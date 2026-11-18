package com.ganzi.emori;

import com.ganzi.emori.orb.OrbService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="EMORI_STAGE8_UPGRADE_TEST",matches="true")
@ActiveProfiles("postgres")
@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:55432/emori_upgrade",
        "spring.flyway.baseline-on-migrate=true","spring.flyway.baseline-version=6"})
class Stage8PostgresUpgradeTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrbService orbs;
    @Test void legacyOrbAndAiTracksSurviveV7() {
        UUID id=UUID.fromString("22222222-2222-2222-2222-222222222222");
        var orb=orbs.snapshot(id);
        assertThat(orb.emotions()).hasSize(1);
        assertThat(orb.playlist()).isNotNull();
        assertThat(orb.playlist().tracks()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT sound_id FROM orb WHERE id=?",UUID.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orb_color_snapshot WHERE orb_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=true",Integer.class)).isEqualTo(1);
    }
}
