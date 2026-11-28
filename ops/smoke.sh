#!/usr/bin/env bash
# 배포 직후 스모크 검사. 하나라도 실패하면 종료 코드 1로 끝난다.
set -uo pipefail

cd "$(dirname "$0")/.."

# 서버에서는 같은 호스트의 다른 서비스가 9090을 쓸 수 있어서 우리 compose가 실제로 연 포트를 물어본다.
# 서비스가 없거나 포트를 공개하지 않았으면 빈 값이나 "invalid IP:0"이 나와서 기본 포트를 쓴다.
prometheus_address=$(docker compose port prometheus 9090 2>/dev/null)
address_pattern='^(\[[0-9a-fA-F:]+\]|[0-9.]+):[1-9][0-9]*$'
[[ $prometheus_address =~ $address_pattern ]] || prometheus_address=localhost:9090
PROMETHEUS_URL=${PROMETHEUS_URL:-http://$prometheus_address}
EXPECTED_PROFILE=${EXPECTED_PROFILE:-}
GRAFANA_URL=${GRAFANA_URL:-http://localhost:3000}
EXPECTED_ALERT_RULES=${EXPECTED_ALERT_RULES:-0}
failures=0

pass() { echo "  OK   $1"; }
fail() { echo "  FAIL $1"; failures=$((failures + 1)); }

# 연결만 받고 응답하지 않는 worker에서 cat이 영원히 기다리면 이후 검사와 실패 집계가 실행되지 않는다.
# exec cat으로 bash를 대체해야 timeout이 cat의 부모가 되어 시간 초과 뒤 좀비가 PID 1(JVM)에 남지 않는다.
worker_get() {
    docker compose --profile app exec -T worker timeout 5 bash -c \
        "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET $1 HTTP/1.0\r\n\r\n' >&3 && exec cat <&3"
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
    # compose는 .env를 읽어도 부모 셸의 환경변수로 내보내지 않아서, 없으면 실행 중인 grafana가 쓰는 값을 가져온다.
    password=${GRAFANA_ADMIN_PASSWORD:-$(docker compose exec -T grafana printenv GF_SECURITY_ADMIN_PASSWORD 2>/dev/null)}
    if [ -z "$password" ]; then
        fail "Grafana 관리자 비밀번호를 얻지 못했다 (GRAFANA_ADMIN_PASSWORD를 설정하거나 grafana 컨테이너를 띄운다)"
    elif ! response=$(curl -fsS -m 5 -u "admin:${password}" "$GRAFANA_URL/api/v1/provisioning/alert-rules" 2>/dev/null); then
        fail "Grafana API 호출에 실패했다 (주소나 비밀번호를 확인한다)"
    else
        count=$(printf '%s' "$response" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
        if [ "$count" -ge "$EXPECTED_ALERT_RULES" ]; then
            pass "알림 규칙 ${count}개"
        else
            fail "알림 규칙이 ${count}개다 (기대 ${EXPECTED_ALERT_RULES}개 이상)"
        fi
    fi
fi

if [ "$failures" -eq 0 ]; then
    echo "스모크 통과"
else
    echo "스모크 실패 ($failures)"
    exit 1
fi
