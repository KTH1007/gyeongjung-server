package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FlywayMigrationIT extends AbstractMySqlIT {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 마이그레이션이_세_테이블을_만든다() {
        // given
        String sql =
                "select table_name from information_schema.tables where table_schema = database()";

        // when
        List<String> tables = jdbcTemplate.queryForList(sql, String.class);

        // then
        assertThat(tables).contains("raw_position_event", "collection_gap", "api_call_ledger");
    }
}
