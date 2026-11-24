#!/usr/bin/env bash
# nginx 앞단 시험. 가짜 api 2개를 띄워서 라운드로빈, 장애 대응, 오류 응답, 헤더 전달, 실제 IP 복원,
# 쓰기 요청 속도 제한, 동시 연결 제한, 한도 설정(환경변수)을 확인한다.
# 하나라도 실패하면 종료 코드 1로 끝난다. KEEP=1이면 끝난 뒤에도 컨테이너를 남긴다.
set -uo pipefail

cd "$(dirname "$0")/../../.."

# compose가 파일 전체의 필수 변수를 검사하므로 이 시험에 안 쓰는 값은 더미로 채운다.
export DB_USERNAME=${DB_USERNAME:-test} DB_PASSWORD=${DB_PASSWORD:-test} GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-test}
# 개발 스택(mysql 등)과 같은 프로젝트면 끝날 때 down이 개발 환경까지 내리므로 시험 전용 프로젝트 이름을 쓴다.
DC="docker compose -p gyeongjung-nginx-test -f docker-compose.yml -f docker-compose.nginx-test.yml --profile proxy"
URL=http://127.0.0.1:8088
# 한도는 운영 기본값(쓰기 초당 5건·burst 20, IP당 동시 30개, 전체 400개)보다 작게 줘서 시험이 짧게 끝나게 하고,
# 환경변수로 값이 바뀌는 것까지 함께 확인한다.
export LIMIT_WRITE_RATE=2 LIMIT_WRITE_BURST=5 LIMIT_CONN_PER_IP=3 LIMIT_CONN_TOTAL=5
HOLD_PIDS=""
failures=0

pass() { echo "  OK   $1"; }
fail() { echo "  FAIL $1"; failures=$((failures + 1)); }
cleanup() { release; [ "${KEEP:-0}" = 1 ] || $DC down --remove-orphans >/dev/null 2>&1; }
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
# IP를 정해서 요청한다. 시험 nginx는 사설 대역을 믿으므로 CF-Connecting-IP가 클라이언트 IP가 된다.
req() { curl -s -m 10 -o /dev/null -w '%{http_code}' -X "$2" -H "CF-Connecting-IP: $1" "$URL${3:-/}"; }
# 같은 IP로 연달아 보내고 상태 코드를 한 줄에 하나씩 낸다. 사용: burst IP 메서드 횟수 [경로]
burst() { for _ in $(seq 1 "$3"); do req "$1" "$2" "${4:-/}"; echo; done; }
count() { grep -c "^$1\$" || true; }
# IP의 요청 n개를 /slow로 보내 처리 중인 채로 붙잡는다 (가짜 api가 25초쯤 걸려서 응답한다)
hold() { for _ in $(seq 1 "$2"); do curl -s -m 40 -o /dev/null -H "CF-Connecting-IP: $1" "$URL/slow" & HOLD_PIDS="$HOLD_PIDS $!"; done; }
release() { [ -n "$HOLD_PIDS" ] && kill $HOLD_PIDS 2>/dev/null; wait 2>/dev/null; HOLD_PIDS=""; }
# 응답 헤더 파일($1)과 본문 파일($2)이 problem+json이고 status($3)와 code($4)가 기대와 같은지 본다
is_problem() { header "$1" content-type | grep -q '^application/problem+json' && python3 -c "import json; d=json.load(open('$2')); assert d['status']==$3 and d['code']=='$4'" 2>/dev/null; }
# 상태 코드가 $4가 될 때까지 최대 5초 기다린다. 붙잡은 요청이 자리 잡는 시간을 주려는 것이다
wait_for_status() { local code=000; for _ in $(seq 1 10); do code=$(req "$1" "$2" "$3"); [ "$code" = "$4" ] && break; sleep 0.5; done; echo "$code"; }

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

echo "[9] 쓰기 요청 속도 제한 (IP당 초당 ${LIMIT_WRITE_RATE}건, burst ${LIMIT_WRITE_BURST})"
ip=203.0.113.10
codes=$(burst "$ip" POST 12)
ok=$(echo "$codes" | count 200); limited=$(echo "$codes" | count 429)
# burst 5에 처음 한 건이 더해져 6건이 통과하고, 그 사이 토큰이 최대 한 개 차오른다
if [ "$ok" -ge 5 ] && [ "$ok" -le 8 ] && [ $((ok + limited)) -eq 12 ]; then pass "POST 12번 중 ${ok}번 통과, ${limited}번 429"; else fail "POST 12번의 결과가 이상하다: $(echo "$codes" | sort | uniq -c | tr -s ' \n' ' ')"; fi
hdr=$(mktemp); out=$(mktemp); code=000
for _ in $(seq 1 10); do
    code=$(curl -s -m 10 -D "$hdr" -o "$out" -w '%{http_code}' -X POST -H "CF-Connecting-IP: $ip" "$URL/")
    [ "$code" = 429 ] && break
done
if [ "$code" != 429 ]; then
    fail "한도를 넘긴 POST가 429를 받지 못했다 (마지막 $code)"
else
    if [[ "$(header "$hdr" retry-after)" =~ ^[0-9]+$ ]]; then pass "429에 Retry-After(초)가 있다"; else fail "Retry-After가 없거나 숫자가 아니다: '$(header "$hdr" retry-after)'"; fi
    if is_problem "$hdr" "$out" 429 TOO_MANY_REQUESTS; then pass "429 본문이 problem+json이고 code=TOO_MANY_REQUESTS"; else fail "429 본문 형식이 틀리다: $(cat "$out")"; fi
    if [[ "$(header "$hdr" x-request-id)" =~ ^[0-9a-f]{32}$ ]]; then pass "429 응답에도 X-Request-ID가 있다"; else fail "429 응답에 X-Request-ID가 없다"; fi
fi
rm -f "$hdr" "$out"
if [ -z "$(curl -sI -m 3 "$URL/healthz" | tr -d '\r' | grep -i '^retry-after')" ]; then pass "정상 응답에는 Retry-After가 없다"; else fail "정상 응답에 Retry-After가 붙는다"; fi
if [ "$(req 203.0.113.11 POST /)" = 200 ]; then pass "다른 IP는 영향받지 않는다"; else fail "한도를 넘긴 IP 때문에 다른 IP도 막혔다"; fi
if [ "$(burst "$ip" GET 60 / | count 200)" -eq 60 ]; then pass "한도를 넘긴 IP도 읽기 요청 60번은 모두 200"; else fail "읽기 요청이 제한됐다"; fi
if [ "$(burst "$ip" GET 30 /healthz | count 200)" -eq 30 ]; then pass "한도를 넘긴 IP도 /healthz 30번은 모두 200"; else fail "/healthz가 제한됐다"; fi
n=0
for method in PUT DELETE PATCH; do
    n=$((n + 1))
    limited=$(burst "203.0.113.2$n" "$method" 12 | count 429)
    if [ "$limited" -ge 3 ]; then pass "${method}도 제한된다 (12번 중 ${limited}번 429)"; else fail "${method}가 제한되지 않는다 (429 ${limited}번)"; fi
done
sleep 3
if [ "$(req "$ip" POST /)" = 200 ]; then pass "3초 뒤 같은 IP의 쓰기가 다시 통과한다"; else fail "3초가 지나도 쓰기가 계속 막힌다"; fi

echo "[10] 동시 연결 제한 (IP당 ${LIMIT_CONN_PER_IP}개, 서버 전체 ${LIMIT_CONN_TOTAL}개)"
hold 203.0.113.40 3
if [ "$(wait_for_status 203.0.113.40 GET / 503)" = 503 ]; then pass "IP당 한도(${LIMIT_CONN_PER_IP})를 채운 IP의 다음 요청은 503"; else fail "처리 중 요청이 한도만큼 쌓여도 503이 나지 않는다"; fi
hdr=$(mktemp); out=$(mktemp)
curl -s -m 10 -D "$hdr" -o "$out" -X GET -H "CF-Connecting-IP: 203.0.113.40" "$URL/" >/dev/null
if is_problem "$hdr" "$out" 503 SERVICE_UNAVAILABLE; then pass "503 본문이 problem+json이고 code=SERVICE_UNAVAILABLE"; else fail "503 본문 형식이 틀리다: $(cat "$out")"; fi
rm -f "$hdr" "$out"
if [ "$(req 203.0.113.41 GET /)" = 200 ]; then pass "다른 IP는 영향받지 않는다"; else fail "한 IP의 연결 때문에 다른 IP도 막혔다"; fi
if [ "$(req 203.0.113.40 GET /healthz)" = 200 ]; then pass "한도를 채운 IP도 /healthz는 200"; else fail "/healthz가 동시 연결 제한에 걸린다"; fi
release
if [ "$(wait_for_status 203.0.113.40 GET / 200)" = 200 ]; then pass "붙잡던 요청이 끝나면 다시 통과한다"; else fail "연결을 닫았는데도 계속 막힌다"; fi
# 각 IP는 IP당 한도(3) 이하인데 합계가 전체 한도(5)에 닿는 경우다. 새 IP의 요청이 막히면 전체 한도가 작동한 것이다
hold 203.0.113.50 2; hold 203.0.113.51 2; hold 203.0.113.52 1
if [ "$(wait_for_status 203.0.113.53 GET / 503)" = 503 ]; then pass "전체 한도(${LIMIT_CONN_TOTAL})가 차면 연결이 하나도 없는 새 IP도 503"; else fail "전체 동시 연결 한도가 작동하지 않는다"; fi
release
if [ "$(wait_for_status 203.0.113.53 GET / 200)" = 200 ]; then pass "연결이 풀리면 새 IP도 통과한다"; else fail "연결을 닫았는데 전체 한도가 풀리지 않는다"; fi

echo "[11] 한도 설정 (환경변수)"
defaults=$(env -u LIMIT_WRITE_RATE -u LIMIT_WRITE_BURST -u LIMIT_CONN_PER_IP -u LIMIT_CONN_TOTAL $DC config 2>/dev/null)
for pair in LIMIT_WRITE_RATE=5 LIMIT_WRITE_BURST=20 LIMIT_CONN_PER_IP=30 LIMIT_CONN_TOTAL=400; do
    if echo "$defaults" | grep -Eq "^ +${pair%%=*}: \"?${pair#*=}\"?\$"; then pass "환경변수가 없으면 ${pair%%=*}는 ${pair#*=}"; else fail "${pair%%=*}의 기본값이 기대(${pair#*=})와 다르다"; fi
done
# 값이 틀렸을 때 한도 없이 조용히 뜨면 과부하 방어가 꺼진 줄 모르고 운영한다
if $DC run --rm -T -e LIMIT_WRITE_RATE=abc nginx nginx -t >/dev/null 2>&1; then fail "잘못된 값(abc)인데 nginx가 설정을 받아들였다"; else pass "잘못된 값이면 nginx 설정 검사가 실패한다"; fi

if [ "$failures" -eq 0 ]; then
    echo "nginx 시험 통과"
else
    echo "nginx 시험 실패 ($failures)"
    exit 1
fi
