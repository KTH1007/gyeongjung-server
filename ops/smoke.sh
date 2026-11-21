#!/usr/bin/env bash
# 배포 직후 스모크 검사. 하나라도 실패하면 종료 코드 1로 끝난다.
set -uo pipefail

cd "$(dirname "$0")/.."

PROMETHEUS_URL=${PROMETHEUS_URL:-http://localhost:9090}
EXPECTED_PROFILE=${EXPECTED_PROFILE:-}
GRAFANA_URL=${GRAFANA_URL:-http://localhost:3000}
EXPECTED_ALERT_RULES=${EXPECTED_ALERT_RULES:-0}
failures=0

pass() { echo "  OK   $1"; }
fail() { echo "  FAIL $1"; failures=$((failures + 1)); }

worker_get() {
    docker compose --profile app exec -T worker bash -c \
        "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET $1 HTTP/1.0\r\n\r\n' >&3 && cat <&3"
}

echo "[1] worker /actuator/health"
if worker_get /actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    pass "health UP"
else
    fail "health가 UP이 아니다"
fi

echo "[2] Prometheus 타깃"
for job in worker node prometheus; do
    value=$(curl -fsS -m 5 "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=up{job=\"$job\"}" 2>/dev/null \
        | python3 -c 'import sys,json; r=json.load(sys.stdin)["data"]["result"]; print(r[0]["value"][1] if r else "none")' 2>/dev/null)
    if [ "$value" = "1" ]; then
        pass "$job up"
    else
        fail "$job 타깃이 up이 아니다 (${value:-조회 실패})"
    fi
done

if [ -n "$EXPECTED_PROFILE" ]; then
    echo "[3] 활성 프로필"
    if worker_get /actuator/info 2>/dev/null | grep -q "\"profiles\":\[\"$EXPECTED_PROFILE\"\]"; then
        pass "프로필 $EXPECTED_PROFILE"
    else
        fail "프로필이 ${EXPECTED_PROFILE}가 아니다"
    fi
fi

if [ "$EXPECTED_ALERT_RULES" -gt 0 ]; then
    echo "[4] Grafana 알림 규칙"
    count=$(curl -fsS -m 5 -u "admin:${GRAFANA_ADMIN_PASSWORD:-}" "$GRAFANA_URL/api/v1/provisioning/alert-rules" 2>/dev/null \
        | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
    if [ "$count" -ge "$EXPECTED_ALERT_RULES" ]; then
        pass "알림 규칙 ${count}개"
    else
        fail "알림 규칙이 ${count}개다 (기대 ${EXPECTED_ALERT_RULES}개 이상)"
    fi
fi

if [ "$failures" -eq 0 ]; then
    echo "스모크 통과"
else
    echo "스모크 실패 ($failures)"
    exit 1
fi
