#!/usr/bin/env bash
# 롤링 교체 시험(진짜 docker). 가짜 api 2개를 nginx 뒤에 띄우고 요청을 계속 보내는 동안 ops/rolling.sh로 api를 교체해서
# 읽기(GET) 실패가 0건인지 본다. 하나라도 실패하면 종료 코드 1이다. KEEP=1이면 끝난 뒤에도 컨테이너를 남긴다.
# [1]은 측정 장치 점검이다. compose가 기본으로 하는 교체(복제본을 한꺼번에 교체)가 요청을 실패시키는 것을 먼저 보여서
# [2]의 "실패 0건"이 아무것도 재지 못하는 통과가 아님을 증명한다.
# 가짜 api는 진짜 앱처럼 뜨는 데 STUB_START_DELAY초(기본 6)가 걸리게 한다. 0.3초 만에 뜨면 옛 컨테이너와 같은 주소를 곧바로
# 다시 받아서 기본 교체도 거의 실패하지 않는다(실제로 0~5건이었다). STUB_START_DELAY=0으로 돌리면 그 빠른 상황을 볼 수 있다.
# ops/nginx/test/verify.sh와 같은 8088 포트를 쓰므로 둘을 동시에 돌리지 않는다.
set -uo pipefail

cd "$(dirname "$0")/../../.." || exit 1

# compose가 파일 전체의 필수 변수를 검사하므로 이 시험에 안 쓰는 값은 더미로 채운다.
export DB_USERNAME=${DB_USERNAME:-test} DB_PASSWORD=${DB_PASSWORD:-test} GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-test}
# 개발 스택과 같은 프로젝트면 끝날 때 down이 개발 환경까지 내리므로 시험 전용 프로젝트를 쓴다. ops/rolling.sh도 이 값을 따른다.
export COMPOSE_PROJECT_NAME=gyeongjung-rolling-test
export COMPOSE_FILE=docker-compose.yml:docker-compose.nginx-test.yml
export COMPOSE_PROFILES=proxy
# 모든 compose 호출에서 같은 값이어야 한다(값이 바뀌면 설정이 달라져 복제본이 교체 대상이 된다).
export STUB_START_DELAY=${STUB_START_DELAY:-6}
URL=http://127.0.0.1:8088
LOG=$(mktemp)
ROLL_OUT=$(mktemp)
STOP=$(mktemp -u)
LOAD_PID=""
failures=0

pass() { echo "  OK   $1"; }
fail() { echo "  FAIL $1"; failures=$((failures + 1)); }
# trap으로 호출된다.
# shellcheck disable=SC2329
cleanup() {
    stop_load
    rm -f "$LOG" "$ROLL_OUT" "$STOP"
    [ "${KEEP:-0}" = 1 ] || docker compose down --remove-orphans > /dev/null 2>&1
}
trap cleanup EXIT

# 0.05초 간격으로 GET을 보내고 "stub=<인스턴스> ...|상태|시간" 한 줄로 남긴다. 연결 실패와 시간 초과는 상태 000이다.
start_load() {
    : > "$LOG"
    rm -f "$STOP"
    (
        while [ ! -e "$STOP" ]; do
            r=$(curl -s -m 10 -w '|%{http_code}|%{time_total}' "$URL/" 2> /dev/null)
            printf '%s\n' "$r" | tr -d '\n' >> "$LOG"
            echo >> "$LOG"
            sleep 0.05
        done
    ) &
    LOAD_PID=$!
}
# 요청 도중에 죽이면 마지막 줄이 잘려 가짜 실패가 생기므로, 신호 파일로 한 바퀴를 마치고 멈추게 한다.
stop_load() {
    [ -n "$LOAD_PID" ] || return 0
    touch "$STOP"
    wait "$LOAD_PID" 2> /dev/null
    LOAD_PID=""
    rm -f "$STOP"
}
report() {
    total=$(wc -l < "$LOG" | tr -d ' ')
    bad=$(awk -F'|' '$2 != "200"' "$LOG" | wc -l | tr -d ' ')
    slowest=$(awk -F'|' '{print $3}' "$LOG" | sort -n | tail -1)
}

api_ids() { docker compose ps -aq api | sort; }
healthy_count() { for id in $(docker compose ps -q api); do docker inspect -f '{{.State.Health.Status}}' "$id"; done | grep -c '^healthy$'; }
wait_api_healthy() { for _ in $(seq 1 60); do [ "$(healthy_count)" -eq "$1" ] && return 0; sleep 1; done; return 1; }
stub_names() { for _ in $(seq 1 "$1"); do curl -s -m 10 "$URL/" | sed -n 's/^stub=\([^ ]*\).*/\1/p'; done | sort | uniq -c; }
# nginx는 api 주소를 10초마다 다시 찾으므로, 응답하는 인스턴스가 2개가 될 때까지 기다린다.
wait_discovered() { for _ in $(seq 1 40); do [ "$(stub_names 6 | grep -c .)" -eq 2 ] && return 0; sleep 1; done; return 1; }
version_count() {
    local n=0 id
    for id in $(docker compose ps -aq api); do
        docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$id" | grep -qx "STUB_VERSION=$1" && n=$((n + 1))
    done
    echo "$n"
}
hostnames() { for id in $(docker compose ps -q api); do docker inspect -f '{{.Config.Hostname}}' "$id"; done | sort | tr '\n' ' '; }
answering() { stub_names 10 | awk '{print $2}' | sort | tr '\n' ' '; }

echo "[0] 기동 (STUB_VERSION=1, 가짜 api 2개, 시작에 ${STUB_START_DELAY}초)"
docker compose up -d nginx api > /dev/null 2>&1 || fail "compose가 뜨지 않는다"
if wait_api_healthy 2; then pass "api 2개가 healthy"; else fail "60초 안에 api 2개가 healthy가 되지 않았다"; fi
if wait_discovered; then pass "nginx가 api 2개를 모두 찾았다"; else fail "40초 안에 api 2개를 찾지 못했다"; fi

echo "[1] 측정 장치 점검: compose 기본 교체(복제본을 한꺼번에 교체)는 요청을 실패시켜야 한다"
start_load
STUB_VERSION=2 docker compose up -d api > /dev/null 2>&1
wait_api_healthy 2
wait_discovered
sleep 3
stop_load
report
if [ "$bad" -ge 1 ]; then pass "기본 교체 중 요청 ${total}건 가운데 ${bad}건 실패 (측정 장치가 실패를 잡는다)"; else fail "기본 교체인데 실패가 0건이다. 이 시험은 아무것도 재지 못한다 (요청 ${total}건)"; fi

echo "[2] 롤링 교체 (요청을 계속 보내는 동안 ops/rolling.sh api, STUB_VERSION=3)"
old_ids=$(api_ids)
start_load
STUB_VERSION=3 ops/rolling.sh api > "$ROLL_OUT" 2>&1
roll=$?
# 마지막 교체 뒤에도 nginx가 새 주소를 알 때까지 실패가 생기지 않는지 본다.
sleep 15
stop_load
report
if [ "$roll" -eq 0 ]; then pass "ops/rolling.sh 종료 코드 0"; else fail "ops/rolling.sh 종료 코드 $roll: $(tail -3 "$ROLL_OUT" | tr '\n' ' ')"; fi
if [ "$total" -ge 100 ]; then pass "교체 동안 요청 ${total}건을 보냈다"; else fail "요청이 ${total}건뿐이라 교체 구간을 덮지 못한다"; fi
if [ "$bad" -eq 0 ]; then pass "읽기 실패 0건"; else fail "읽기 ${bad}건 실패 (전체 ${total}건)"; fi
if awk -v s="${slowest:-0}" 'BEGIN { exit !(s < 6) }'; then pass "가장 느린 응답 ${slowest}초 (죽은 인스턴스를 만나 다른 인스턴스로 재시도하는 비용 포함)"; else fail "가장 느린 응답이 ${slowest}초다"; fi
if [ "$(version_count 3)" -eq 2 ] && [ "$(api_ids | wc -l | tr -d ' ')" -eq 2 ]; then pass "api가 정확히 2개이고 모두 새 설정이다"; else fail "새 설정 컨테이너 $(version_count 3)개, 전체 $(api_ids | wc -l | tr -d ' ')개"; fi
if [ -z "$(comm -12 <(echo "$old_ids") <(api_ids))" ]; then pass "옛 컨테이너가 모두 교체됐다"; else fail "옛 컨테이너가 남아 있다"; fi
if [ "$(healthy_count)" -eq 2 ]; then pass "새 컨테이너 2개가 healthy"; else fail "healthy가 $(healthy_count)개다"; fi
if [ "$(answering)" = "$(hostnames)" ]; then pass "응답하는 인스턴스가 새 컨테이너 2개와 같다"; else fail "응답 인스턴스 [$(answering)] 와 컨테이너 [$(hostnames)] 가 다르다"; fi

echo "[3] 이미 최신이면 아무것도 바꾸지 않는다 (같은 설정으로 다시 실행)"
before=$(api_ids)
STUB_VERSION=3 ops/rolling.sh api > "$ROLL_OUT" 2>&1
roll=$?
if [ "$roll" -eq 0 ]; then pass "종료 코드 0"; else fail "종료 코드 $roll: $(tail -3 "$ROLL_OUT" | tr '\n' ' ')"; fi
if [ "$(api_ids)" = "$before" ]; then pass "컨테이너가 그대로다"; else fail "이미 최신인데 컨테이너가 바뀌었다"; fi

echo "[4] 고장 난 새 버전 (헬스체크가 실패하는 STUB_VERSION=4)"
start_load
STUB_VERSION=4 STUB_HEALTH=bad ROLLING_HEALTH_ATTEMPTS=20 ROLLING_POLL_SECONDS=1 ops/rolling.sh api > "$ROLL_OUT" 2>&1
roll=$?
sleep 5
stop_load
report
if [ "$roll" -eq 10 ]; then pass "종료 코드 10 (새 복제본이 healthy가 되지 않음)"; else fail "종료 코드가 10이 아니다: $roll ($(tail -3 "$ROLL_OUT" | tr '\n' ' '))"; fi
intact=1
for id in $before; do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$id" 2> /dev/null)" = healthy ] || intact=0
done
if [ "$intact" -eq 1 ]; then pass "옛 복제본 2개가 그대로 healthy로 서비스 중이다"; else fail "옛 복제본이 사라졌거나 healthy가 아니다"; fi
if [ "$bad" -eq 0 ]; then pass "실패한 배포 동안에도 읽기 실패 0건 (요청 ${total}건)"; else fail "실패한 배포 동안 읽기 ${bad}건 실패"; fi

echo "[5] 롤백: 같은 스크립트를 이전 설정(STUB_VERSION=3)으로 다시 실행"
STUB_VERSION=3 ops/rolling.sh api > "$ROLL_OUT" 2>&1
roll=$?
if [ "$roll" -eq 0 ]; then pass "종료 코드 0"; else fail "종료 코드 $roll: $(tail -3 "$ROLL_OUT" | tr '\n' ' ')"; fi
if [ "$(api_ids)" = "$before" ]; then pass "원래 복제본 2개가 그대로이고 고장 난 새 복제본만 사라졌다"; else fail "컨테이너가 원래와 다르다: [$(api_ids | tr '\n' ' ')]"; fi
if [ "$(version_count 4)" -eq 0 ]; then pass "고장 난 버전(4) 컨테이너가 없다"; else fail "고장 난 버전 컨테이너가 남아 있다"; fi

echo
if [ "$failures" -eq 0 ]; then echo "롤링 시험 통과"; exit 0; fi
echo "롤링 시험 실패 (${failures}건)"
exit 1
