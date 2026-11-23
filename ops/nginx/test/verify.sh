#!/usr/bin/env bash
# nginx 앞단 시험. 가짜 api 2개를 띄워서 라운드로빈, 장애 대응, 오류 응답, 헤더 전달, 실제 IP 복원을 확인한다.
# 하나라도 실패하면 종료 코드 1로 끝난다. KEEP=1이면 끝난 뒤에도 컨테이너를 남긴다.
set -uo pipefail

cd "$(dirname "$0")/../../.."

# compose가 파일 전체의 필수 변수를 검사하므로 이 시험에 안 쓰는 값은 더미로 채운다.
export DB_USERNAME=${DB_USERNAME:-test} DB_PASSWORD=${DB_PASSWORD:-test} GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-test}
# 개발 스택(mysql 등)과 같은 프로젝트면 끝날 때 down이 개발 환경까지 내리므로 시험 전용 프로젝트 이름을 쓴다.
DC="docker compose -p gyeongjung-nginx-test -f docker-compose.yml -f docker-compose.nginx-test.yml --profile proxy"
URL=http://127.0.0.1:8088
failures=0

pass() { echo "  OK   $1"; }
fail() { echo "  FAIL $1"; failures=$((failures + 1)); }
cleanup() { [ "${KEEP:-0}" = 1 ] || $DC down --remove-orphans >/dev/null 2>&1; }
trap cleanup EXIT

http_status() { curl -s -m 10 -o /dev/null -w '%{http_code}' "$@" "$URL/"; }
stub_names() { for _ in $(seq 1 "$1"); do curl -s -m 10 "$URL/" | sed -n 's/^stub=\([^ ]*\).*/\1/p'; done | sort | uniq -c; }
# 접근 로그가 stdout으로 넘어오는 데 시간이 걸릴 수 있어서 잠깐 기다리며 찾는다.
log_line() {
    for _ in $(seq 1 10); do
        l=$($DC logs --no-log-prefix --since 120s nginx 2>/dev/null | grep "rid=$1" | tail -1)
        [ -n "$l" ] && { echo "$l"; return; }
        sleep 0.5
    done
}
header() { tr -d '\r' < "$1" | awk -F': ' -v k="$2" 'tolower($1)==k{print $2}'; }

echo "[0] 기동"
$DC up -d nginx api >/dev/null 2>&1 || fail "compose가 뜨지 않는다"
for _ in $(seq 1 30); do
    [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "$URL/healthz")" = 200 ] && break
    sleep 1
done
# nginx는 시작할 때 api를 한 번 찾고 이후 10초마다 다시 찾으므로, 늦게 뜬 인스턴스까지 보일 때까지 기다린다.
discovered=0
start=$(date +%s)
for _ in $(seq 1 40); do
    if [ "$(stub_names 6 | grep -c .)" -eq 2 ]; then discovered=1; break; fi
    sleep 1
done
if [ "$discovered" -eq 1 ]; then pass "api 인스턴스 2개를 모두 찾았다 ($(( $(date +%s) - start ))초 걸림)"; else fail "40초 안에 api 인스턴스 2개를 찾지 못했다"; fi

echo "[1] 설정 문법과 기본"
if $DC exec -T nginx nginx -t >/dev/null 2>&1; then pass "nginx -t 통과"; else fail "nginx -t 실패"; fi
if [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$URL/healthz")" = 200 ]; then pass "/healthz 200"; else fail "/healthz가 200이 아니다"; fi
if curl -sI -m 3 "$URL/healthz" | tr -d '\r' | grep -qix 'server: nginx'; then pass "Server 헤더에 버전이 없다"; else fail "Server 헤더에 버전이 노출된다"; fi

echo "[2] 라운드로빈 (요청 20번)"
counts=$(stub_names 20)
kinds=$(echo "$counts" | grep -c .)
fewest=$(echo "$counts" | awk 'NR==1||$1<m{m=$1} END{print m+0}')
total=$(echo "$counts" | awk '{s+=$1} END{print s+0}')
if [ "$kinds" -eq 2 ] && [ "$fewest" -ge 8 ] && [ "$total" -eq 20 ]; then pass "두 인스턴스에 번갈아 분배 ($(echo "$counts" | tr -s ' \n' ' '))"; else fail "분배가 이상하다 (응답 ${total}/20): $(echo "$counts" | tr -s ' \n' ' ')"; fi

echo "[3] 요청 ID와 헤더 전달 (클라이언트가 보낸 값은 덮어써야 한다)"
hdr=$(mktemp)
resp=$(curl -s -m 10 -D "$hdr" -H "X-Request-ID: forged-by-client" -H "X-Forwarded-For: 6.6.6.6" "$URL/")
rid=$(header "$hdr" x-request-id)
stub_rid=$(echo "$resp" | sed -n 's/.*request_id=\([^ ]*\).*/\1/p')
if [[ "$rid" =~ ^[0-9a-f]{32}$ ]] && [ "$rid" = "$stub_rid" ]; then pass "응답 헤더와 api가 받은 X-Request-ID가 같고 nginx가 만든 값이다"; else fail "요청 ID 불일치 (헤더=$rid, api=$stub_rid)"; fi
line=$(log_line "$rid")
if [ -n "$line" ]; then pass "접근 로그에 같은 ID가 있다"; else fail "접근 로그에서 ${rid}를 못 찾음"; fi
if echo "$resp" | grep -q 'xff=[0-9a-f:.]' && ! echo "$resp" | grep -q 'xff=6.6.6.6'; then pass "위조된 X-Forwarded-For를 넘기지 않는다"; else fail "X-Forwarded-For가 없거나 위조값이 그대로 넘어간다: $resp"; fi
if echo "$resp" | grep -q 'xfp=http host=127.0.0.1:8088'; then pass "X-Forwarded-Proto와 Host 전달"; else fail "X-Forwarded-Proto 또는 Host가 다르다: $resp"; fi
rm -f "$hdr"

echo "[4] 실제 IP 복원"
hdr=$(mktemp)
resp=$(curl -s -m 10 -D "$hdr" -H "CF-Connecting-IP: 203.0.113.7" "$URL/")
rid=$(header "$hdr" x-request-id)
line=$(log_line "$rid")
if echo "$line" | grep -q 'ip=203.0.113.7 '; then pass "접근 로그에 CF-Connecting-IP 값이 남는다"; else fail "접근 로그에 복원된 IP가 없다: $line"; fi
if echo "$resp" | grep -q 'xff=203.0.113.7 '; then pass "api에는 복원된 IP가 X-Forwarded-For로 간다"; else fail "api가 받은 X-Forwarded-For가 다르다: $resp"; fi
if echo "$line" | grep -Eq 'rid=[0-9a-f]{32} .* 200 rt=[0-9.]+ urt=[0-9.]+'; then pass "접근 로그에 상태, 처리 시간, 업스트림 응답 시간이 있다"; else fail "접근 로그 형식이 다르다: $line"; fi
rm -f "$hdr"
# 시험에서는 사설 대역을 더 믿게 했으므로, 운영 설정이 Cloudflare 대역만 믿는지는 파일을 목록과 정확히 비교해서 확인한다.
# 대역을 갱신할 때는 nginx.conf와 이 목록을 같이 고친다. 출처: https://www.cloudflare.com/ips-v4/ , https://www.cloudflare.com/ips-v6/
expected_trust="173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22 141.101.64.0/18 108.162.192.0/18 190.93.240.0/20 188.114.96.0/20 197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13 104.24.0.0/14 172.64.0.0/13 131.0.72.0/22 2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32 2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32"
actual_sorted=$(grep -E '^\s*set_real_ip_from' ops/nginx/nginx.conf 2>/dev/null | awk '{print $2}' | tr -d ';' | sort)
expected_sorted=$(printf '%s\n' $expected_trust | sort)
extra=$(comm -13 <(echo "$expected_sorted") <(echo "$actual_sorted") | tr '\n' ' ')
missing=$(comm -23 <(echo "$expected_sorted") <(echo "$actual_sorted") | tr '\n' ' ')
if [ -z "$actual_sorted" ]; then
    fail "운영 nginx.conf에 set_real_ip_from이 없다"
elif [ -n "$extra" ] || [ -n "$missing" ]; then
    fail "운영 nginx.conf의 신뢰 대역이 Cloudflare 목록과 다르다 (추가: ${extra:-없음}/ 누락: ${missing:-없음})"
else
    pass "운영 nginx.conf는 Cloudflare 대역 $(echo "$actual_sorted" | grep -c .)개만 정확히 신뢰한다"
fi

id_list=$($DC ps -q api 2>/dev/null)
id1=$(echo "$id_list" | sed -n 1p)
id2=$(echo "$id_list" | sed -n 2p)
if [ -z "$id1" ] || [ -z "$id2" ]; then fail "api 인스턴스가 2개가 아니다"; fi

echo "[5] 응답이 멈춘 api (연결은 받지만 응답을 안 줄 때)"
# pause하면 프로세스가 얼어서 연결은 커널이 받아 주지만 응답은 오지 않는다. 연결 시간 초과가 아니라 읽기 시간 초과가 걸리는 경우다.
docker pause "$id1" >/dev/null 2>&1
ok=0
slowest=0
for _ in $(seq 1 8); do
    r=$(curl -s -m 12 -o /dev/null -w '%{http_code} %{time_total}' "$URL/")
    [ "${r%% *}" = 200 ] && ok=$((ok + 1))
    slowest=$(echo "${r##* } $slowest" | awk '{print ($1 > $2) ? $1 : $2}')
done
docker unpause "$id1" >/dev/null 2>&1
if [ "$ok" -eq 8 ] && [ "$(echo "$slowest" | awk '{print ($1 < 9) ? 1 : 0}')" -eq 1 ]; then pass "멈춘 인스턴스가 있어도 8번 모두 200 (가장 느린 응답 ${slowest}초)"; else fail "성공 $ok/8, 가장 느린 응답 ${slowest}초"; fi
# 다음 시험이 영향받지 않게 멈췄던 인스턴스가 다시 분배 대상이 될 때까지 기다린다.
for _ in $(seq 1 40); do [ "$(stub_names 6 | grep -c .)" -eq 2 ] && break; sleep 1; done

echo "[6] 한쪽 api가 죽었을 때"
docker stop "$id1" >/dev/null 2>&1
ok=0
for _ in $(seq 1 12); do [ "$(http_status)" = 200 ] && ok=$((ok + 1)); done
kinds=$(stub_names 6 | grep -c .)
if [ "$ok" -eq 12 ] && [ "$kinds" -eq 1 ]; then pass "한쪽이 죽어도 12번 모두 200, 남은 인스턴스가 처리"; else fail "성공 $ok/12, 응답한 인스턴스 ${kinds}개"; fi

echo "[7] 둘 다 죽었을 때"
docker stop "$id2" >/dev/null 2>&1
hdr=$(mktemp); out=$(mktemp)
code=$(curl -s -m 20 -D "$hdr" -o "$out" -w '%{http_code}' "$URL/")
case "$code" in 502|503|504) pass "오류 상태 코드 $code" ;; *) fail "오류 상태 코드가 502/503/504가 아니다: $code" ;; esac
if header "$hdr" content-type | grep -q '^application/problem+json'; then pass "Content-Type: application/problem+json"; else fail "Content-Type이 problem+json이 아니다"; fi
if python3 -c "import json; d=json.load(open('$out')); assert d['status']==$code and d['code']" 2>/dev/null; then pass "본문이 JSON이고 status와 code가 있다"; else fail "본문 형식이 틀리다: $(cat "$out")"; fi
if [[ "$(header "$hdr" x-request-id)" =~ ^[0-9a-f]{32}$ ]]; then pass "오류 응답에도 X-Request-ID가 있다"; else fail "오류 응답에 X-Request-ID가 없다"; fi
if [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' "$URL/healthz")" = 200 ]; then pass "api가 없어도 /healthz는 200"; else fail "api가 없을 때 /healthz가 200이 아니다"; fi
rm -f "$hdr" "$out"

echo "[8] 복구 (다시 뜬 api를 자동으로 찾는지)"
docker start "$id1" "$id2" >/dev/null 2>&1
recovered=0
for _ in $(seq 1 60); do
    if [ "$(stub_names 6 | grep -c .)" -eq 2 ]; then recovered=1; break; fi
    sleep 1
done
if [ "$recovered" -eq 1 ]; then pass "둘 다 복구되어 다시 분배된다"; else fail "60초 안에 복구되지 않았다"; fi

if [ "$failures" -eq 0 ]; then
    echo "nginx 시험 통과"
else
    echo "nginx 시험 실패 ($failures)"
    exit 1
fi
