package com.gyeongjung;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

// Grafana 알림 규칙 파일의 약속을 지킨다. 규칙을 실제로 평가해 발화시키는 확인은 Grafana를 띄워야 해서 따로 한다.
class AlertRulesTest {

    @Test
    void 호스트_메모리_규칙은_가용_메모리_10퍼센트_미만이_5분_이어지면_발화한다() throws IOException {
        // given
        Map<String, Object> rule = ruleByUid("host-memory-low");

        // when
        String query = (String) model(rule, "A").get("expr");
        Map<String, Object> evaluator = evaluator(rule, "C");

        // then
        assertThat(rule).containsEntry("for", "5m");
        assertThat(query).contains("node_memory_MemAvailable_bytes", "node_memory_MemTotal_bytes");
        assertThat(evaluator).containsEntry("type", "lt").containsEntry("params", List.of(10));
    }

    // noDataState가 OK이면 값이 없을 때 알림이 만들어지지 않아 요약을 그릴 일이 없다. 그 외 상태에서
    // 값이 없는데 $values.B.Value를 그대로 쓰면 %!f(<nil>)가 찍힌다 (#34).
    @Test
    void 값이_없을_때도_알리는_규칙의_요약은_값이_있는지_먼저_분기한다() throws IOException {
        // given
        List<Map<String, Object>> rules = rules();

        // when
        List<Object> unguarded =
                rules.stream()
                        .filter(rule -> !"OK".equals(rule.get("noDataState")))
                        .filter(rule -> summary(rule).contains("$values.B.Value"))
                        .filter(rule -> !summary(rule).contains("{{ if $values.B }}"))
                        .map(rule -> rule.get("uid"))
                        .toList();

        // then
        assertThat(unguarded).isEmpty();
    }

    @Test
    void 규칙_uid는_겹치지_않는다() throws IOException {
        // given
        List<Object> uids = rules().stream().map(rule -> rule.get("uid")).toList();

        // when & then
        assertThat(uids).doesNotHaveDuplicates();
    }

    private static Map<String, Object> ruleByUid(String uid) throws IOException {
        return rules().stream()
                .filter(rule -> uid.equals(rule.get("uid")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("규칙이 없다: " + uid));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> model(Map<String, Object> rule, String refId) {
        List<Map<String, Object>> data = (List<Map<String, Object>>) rule.get("data");
        Map<String, Object> query =
                data.stream().filter(d -> refId.equals(d.get("refId"))).findFirst().orElseThrow();
        return (Map<String, Object>) query.get("model");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evaluator(Map<String, Object> rule, String refId) {
        List<Map<String, Object>> conditions =
                (List<Map<String, Object>>) model(rule, refId).get("conditions");
        return (Map<String, Object>) conditions.get(0).get("evaluator");
    }

    @SuppressWarnings("unchecked")
    private static String summary(Map<String, Object> rule) {
        return (String) ((Map<String, Object>) rule.get("annotations")).get("summary");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules() throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> root =
                yaml.load(
                        Files.readString(
                                RepositoryRoot.path().resolve("ops/grafana/alerting/rules.yml")));
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> group : (List<Map<String, Object>>) root.get("groups")) {
            rules.addAll((List<Map<String, Object>>) group.get("rules"));
        }
        return rules;
    }
}
