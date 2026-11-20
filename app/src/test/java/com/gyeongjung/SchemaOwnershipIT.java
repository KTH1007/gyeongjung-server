package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaOwnershipIT extends AbstractMySqlIT {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 모든_테이블에_소유자가_있다() {
        // given
        Set<String> owned = loadOwners().keySet();
        String sql =
                """
                select table_name from information_schema.tables
                where table_schema = database() and table_name <> 'flyway_schema_history'
                """;

        // when
        List<String> tables = jdbcTemplate.queryForList(sql, String.class);

        // then
        assertThat(tables).isNotEmpty().isSubsetOf(owned);
    }

    @Test
    void 소유자가_다른_테이블_사이에는_외래_키가_없다() {
        // given
        Map<String, String> owners = loadOwners();
        String sql =
                """
                select table_name, referenced_table_name from information_schema.key_column_usage
                where table_schema = database() and referenced_table_name is not null
                """;

        // when
        List<String> crossOwnerKeys =
                jdbcTemplate
                        .query(
                                sql,
                                (rs, rowNum) -> new ForeignKey(rs.getString(1), rs.getString(2)))
                        .stream()
                        .filter(fk ->
                                !Objects.equals(
                                        owners.get(fk.table()), owners.get(fk.referencedTable())))
                        .map(fk -> fk.table() + " -> " + fk.referencedTable())
                        .toList();

        // then
        assertThat(crossOwnerKeys).isEmpty();
    }

    private static Map<String, String> loadOwners() {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new ClassPathResource("table-owners.yml"));
        return Objects.requireNonNull(yaml.getObject()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
    }

    private record ForeignKey(String table, String referencedTable) {}
}
