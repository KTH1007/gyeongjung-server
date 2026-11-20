package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaConventionIT extends AbstractMySqlIT {

    private static final Set<String> PK_EXCEPTIONS =
            Set.of("flyway_schema_history", "api_call_ledger");
    private static final Set<String> APPEND_ONLY_TABLES =
            Set.of("flyway_schema_history", "raw_position_event");

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 모든_테이블의_PK는_BIGINT_AUTO_INCREMENT다() {
        // given
        String sql =
                """
                select t.table_name from information_schema.tables t
                where t.table_schema = database() and t.table_type = 'BASE TABLE'
                  and not exists (
                    select 1 from information_schema.columns c
                    where c.table_schema = t.table_schema and c.table_name = t.table_name
                      and c.column_key = 'PRI' and c.data_type = 'bigint'
                      and c.extra like '%auto_increment%')
                """;

        // when
        List<String> tablesWithoutBigintPk = jdbcTemplate.queryForList(sql, String.class);

        // then
        assertThat(tablesWithoutBigintPk).isSubsetOf(PK_EXCEPTIONS);
    }

    @Test
    void public_id_컬럼은_BINARY16_NOT_NULL이고_유니크_인덱스가_있다() {
        // given
        String sql =
                """
                select c.table_name from information_schema.columns c
                where c.table_schema = database() and c.column_name = 'public_id'
                  and not (c.column_type = 'binary(16)' and c.is_nullable = 'NO'
                    and exists (
                      select 1 from information_schema.statistics s
                      where s.table_schema = c.table_schema and s.table_name = c.table_name
                        and s.column_name = 'public_id' and s.non_unique = 0))
                """;

        // when
        List<String> violations = jdbcTemplate.queryForList(sql, String.class);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void raw_position_event를_뺀_모든_테이블에_created_at과_updated_at이_있다() {
        // given
        String sql =
                """
                select t.table_name from information_schema.tables t
                where t.table_schema = database() and t.table_type = 'BASE TABLE'
                  and (
                    select count(*) from information_schema.columns c
                    where c.table_schema = t.table_schema and c.table_name = t.table_name
                      and c.column_name in ('created_at', 'updated_at')) < 2
                """;

        // when
        List<String> tablesWithoutAuditColumns = jdbcTemplate.queryForList(sql, String.class);

        // then
        assertThat(tablesWithoutAuditColumns).isSubsetOf(APPEND_ONLY_TABLES);
    }

    @Test
    void 시각_컬럼은_모두_DATETIME_3이다() {
        // given
        String sql =
                """
                select concat(table_name, '.', column_name) from information_schema.columns
                where table_schema = database() and table_name <> 'flyway_schema_history'
                  and data_type in ('datetime', 'timestamp') and column_type <> 'datetime(3)'
                """;

        // when
        List<String> violations = jdbcTemplate.queryForList(sql, String.class);

        // then
        assertThat(violations).isEmpty();
    }
}
