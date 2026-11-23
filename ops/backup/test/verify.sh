#!/usr/bin/env bash
# 백업과 복구 시험. 시험 전용 compose 프로젝트에서 DB와 backup 서비스를 띄워 백업, 보관, 실패 알림, 서버 밖 복사,
# 일정 계산, 복구 리허설(Flyway 포함)을 확인한다. 하나라도 실패하면 종료 코드 1로 끝난다. KEEP=1이면 끝난 뒤에도 남긴다.
set -uo pipefail

cd "$(dirname "$0")/../../.."

# compose가 파일 전체의 필수 변수를 검사하므로 안 쓰는 값은 더미로 채운다. 비밀번호가 로그로 새는지 보려고 눈에 띄는 값을 쓴다.
export DB_USERNAME=${DB_USERNAME:-test} DB_PASSWORD=${DB_PASSWORD:-pw-backup-test-7f3a} GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-test}
DC="docker compose -p gyeongjung-backup-test -f docker-compose.yml -f docker-compose.backup-test.yml --profile backup --profile tools"
TMP=$(mktemp -d)
HOOKS="$TMP/hooks.log"
touch "$HOOKS"
SEED_SQL_COLUMNS="train_no, station_id, station_name, train_status, direction, destination_id, destination_name, express, last_car, received_at, polled_at"
failures=0

pass() { echo "  OK   $1"; }
fail() { echo "  FAIL $1"; failures=$((failures + 1)); }
cleanup() {
    [ -n "${RECEIVER:-}" ] && kill "$RECEIVER" 2>/dev/null
    [ "${KEEP:-0}" = 1 ] || $DC down -v --remove-orphans >/dev/null 2>&1
    rm -rf "${TMP:?}"
}
trap cleanup EXIT

sql() { $DC exec -T -e MYSQL_PWD="$DB_PASSWORD" mysql mysql -u"$DB_USERNAME" gyeongjung -N -e "$1" 2>/dev/null | tr -d '\r'; }
box() { $DC exec -T backup "$@"; }
hooks() { grep -c . "$HOOKS" 2>/dev/null || true; }
# raw_position_event에 i가 $1부터 $2까지인 행을 넣는다
seed() {
    sql "INSERT INTO raw_position_event (${SEED_SQL_COLUMNS}) WITH RECURSIVE n(i) AS (SELECT $1 UNION ALL SELECT i + 1 FROM n WHERE i < $2) SELECT CONCAT('K', i), '1001', 'station', 1, 0, '1002', 'terminal', 0, 0, TIMESTAMP('2026-11-24 00:00:00.000') + INTERVAL i SECOND, NOW(3) FROM n"
}
newest_backup() { box sh -c 'ls -1t /backups/gyeongjung-*.sql.gz 2>/dev/null | head -1' | tr -d '\r'; }
metric_ts() { box sh -c "grep '^backup_last_success_timestamp_seconds ' /metrics/backup.prom 2>/dev/null | awk '{print \$2}'" | tr -d '\r'; }

echo "[0] 기동과 준비"
python3 ops/backup/test/webhook_receiver.py "$HOOKS" 18099 >/dev/null 2>&1 &
RECEIVER=$!
$DC up -d --wait mysql >/dev/null 2>&1 || fail "시험 DB가 뜨지 않는다"
# 처음 인증하는 사용자는 SSL 없이는 서버 공개키를 받아도 된다는 옵션이 있어야 Flyway CLI가 접속한다.
if seed_out=$($DC run --rm -T flyway -connectRetries=10 -url="jdbc:mysql://mysql:3306/gyeongjung?allowPublicKeyRetrieval=true" -user="$DB_USERNAME" -password="$DB_PASSWORD" -locations=filesystem:/flyway/sql migrate 2>&1); then
    pass "시험 DB에 현재 마이그레이션 적용"
else
    fail "시험 DB에 마이그레이션을 적용하지 못했다: $(echo "$seed_out" | tail -3)"
fi
seed 1 500
$DC up -d --build backup >/dev/null 2>&1 || fail "backup 서비스가 뜨지 않는다"

echo "[1] 시작하자마자 한 번 백업 (일정을 기다리지 않는다)"
for _ in $(seq 1 90); do [ -n "$(newest_backup)" ] && break; sleep 1; done
if [ -n "$(newest_backup)" ]; then pass "시작 직후 백업 파일이 생겼다"; else fail "시작 직후 백업 파일이 없다"; fi

echo "[2] 백업 내용"
before=$(sql "SELECT COUNT(*) FROM raw_position_event")
box /ops/backup/backup.sh >/dev/null 2>&1; code=$?
normal_code=$code
file=$(newest_backup)
if [ "$code" -eq 0 ]; then pass "backup.sh 종료 코드 0"; else fail "backup.sh 종료 코드 ${code}"; fi
if [[ "$file" =~ ^/backups/gyeongjung-[0-9]{8}-[0-9]{6}\.sql\.gz$ ]]; then pass "파일 이름 형식 (UTC 날짜-시각)"; else fail "파일 이름 형식이 다르다: ${file}"; fi
if box sh -c "[ -s '$file' ] && gzip -t '$file'"; then pass "비어 있지 않고 gzip이 온전하다"; else fail "빈 파일이거나 gzip이 깨졌다"; fi
if [ "$(box sh -c "gunzip -c '$file' | grep -c 'CREATE TABLE .raw_position_event.'" | tr -d '[:space:]')" -ge 1 ] 2>/dev/null; then pass "raw_position_event 테이블 정의가 들어 있다"; else fail "테이블 정의가 없다"; fi
if [ "$(box sh -c "gunzip -c '$file' | grep -c 'flyway_schema_history'" | tr -d '[:space:]')" -ge 1 ] 2>/dev/null; then pass "flyway_schema_history가 들어 있다 (복원 뒤 마이그레이션 이력이 이어진다)"; else fail "flyway_schema_history가 빠졌다"; fi
ts=$(metric_ts)
now=$(box date +%s | tr -d '\r')
if [ -n "$ts" ] && [ $((now - ts)) -ge 0 ] && [ $((now - ts)) -lt 120 ]; then pass "마지막 성공 시각 지표가 방금 시각으로 갱신됐다"; else fail "지표가 없거나 오래됐다 (ts=${ts}, now=${now})"; fi

echo "[3] 복구 리허설 (백업 시점의 행 수로 복원되는지)"
$DC cp "backup:${file}" "$TMP/dump.sql.gz" >/dev/null 2>&1
out=$(./ops/backup/restore-drill.sh "$TMP/dump.sql.gz" 2>&1); code=$?
if [ "$code" -eq 0 ] && echo "$out" | grep -q "raw_position_event 행 수: ${before}\$"; then pass "복원본 행 수가 백업 직전과 같다 (${before})"; else fail "복원 실패 또는 행 수 불일치 (기대 ${before}, 종료 ${code}): $(echo "$out" | tail -3)"; fi
seed 501 510
live=$(sql "SELECT COUNT(*) FROM raw_position_event")
out=$(./ops/backup/restore-drill.sh "$TMP/dump.sql.gz" 2>&1)
if echo "$out" | grep -q "raw_position_event 행 수: ${before}\$" && [ "${live:-0}" -gt "${before:-0}" ] 2>/dev/null; then pass "백업 뒤에 쌓인 행은 복원본에 없다 (현재 ${live}, 복원 ${before})"; else fail "복원본에 백업 뒤 행이 섞였다"; fi

echo "[4] 마이그레이션 리허설과 규칙"
mkdir -p "$TMP/mig_new" "$TMP/mig_bad"
cp app/src/main/resources/db/migration/*.sql "$TMP/mig_new/"
cp app/src/main/resources/db/migration/*.sql "$TMP/mig_bad/"
echo "ALTER TABLE api_call_ledger ADD COLUMN note VARCHAR(16) NULL;" > "$TMP/mig_new/V2__rehearsal_column.sql"
out=$(MIGRATIONS_DIR="$TMP/mig_new" ./ops/backup/restore-drill.sh "$TMP/dump.sql.gz" 2>&1); code=$?
if [ "$code" -eq 0 ] && echo "$out" | grep -q "Successfully applied 1 migration"; then pass "새 마이그레이션(V2)이 복원본에 적용된다"; else fail "새 마이그레이션 적용 실패 (종료 ${code}): $(echo "$out" | tail -3)"; fi
# 이미 적용된 V1의 맨 앞에 주석 한 줄만 더해도 체크섬이 바뀐다
{ echo "-- 이미 적용된 파일을 고친 것"; cat app/src/main/resources/db/migration/V1__init.sql; } > "$TMP/mig_bad/V1__init.sql"
out=$(MIGRATIONS_DIR="$TMP/mig_bad" ./ops/backup/restore-drill.sh "$TMP/dump.sql.gz" 2>&1); code=$?
if [ "$code" -ne 0 ] && echo "$out" | grep -qi "checksum mismatch"; then pass "적용된 마이그레이션을 고치면 Flyway 검증이 체크섬 불일치로 실패한다"; else fail "고친 마이그레이션이 통과했다 (종료 ${code})"; fi

echo "[5] 실패하면 알림이 오고 지표는 그대로"
hooks_before=$(hooks)
files_before=$(box sh -c 'ls /backups/gyeongjung-*.sql.gz | wc -l' | tr -d '[:space:]')
ts_before=$(metric_ts)
$DC exec -T -e DB_PASSWORD=wrong backup /ops/backup/backup.sh >/dev/null 2>&1; code=$?
files_after=$(box sh -c 'ls /backups/gyeongjung-*.sql.gz | wc -l' | tr -d '[:space:]')
if [ "$normal_code" -eq 0 ] && [ "$code" -ne 0 ]; then pass "같은 스크립트가 정상 비밀번호로는 성공하고 잘못된 비밀번호로는 실패한다"; else fail "정상 실행 종료 ${normal_code}, 잘못된 비밀번호 실행 종료 ${code}"; fi
if [ "$(hooks)" -gt "$hooks_before" ] && tail -1 "$HOOKS" | python3 -c "import sys,json; d=json.loads(sys.stdin.readline()); c=json.loads(d['body'])['content']; assert '백업 실패' in c and '[test]' in c" 2>/dev/null; then pass "디스코드로 [test] 접두어가 붙은 백업 실패 알림이 간다"; else fail "실패 알림이 없거나 형식이 다르다: $(tail -1 "$HOOKS")"; fi
if [ "${files_before:-0}" -ge 1 ] && [ -n "$ts_before" ] && [ "$files_after" = "$files_before" ] && [ "$(metric_ts)" = "$ts_before" ]; then pass "실패한 백업은 파일도 지표도 바꾸지 않는다 (파일 ${files_before}개, 지표 유지)"; else fail "기존 백업이 없어 비교할 수 없거나, 실패했는데 파일이나 지표가 바뀌었다 (파일 ${files_before}→${files_after})"; fi

echo "[6] 14일 보관"
box sh -c 'touch -d "15 days ago" /backups/gyeongjung-20000101-000000.sql.gz /backups/gyeongjung-19990101-000000.sql.gz; touch -d "13 days ago" /backups/gyeongjung-20000102-000000.sql.gz; touch -d "30 days ago" /backups/notes.txt'
if box test -e /backups/gyeongjung-20000101-000000.sql.gz && box test -e /backups/gyeongjung-19990101-000000.sql.gz && box test -e /backups/notes.txt; then planted=1; else planted=0; fi
box /ops/backup/backup.sh >/dev/null 2>&1
if [ "$planted" -eq 1 ] && ! box test -e /backups/gyeongjung-20000101-000000.sql.gz && ! box test -e /backups/gyeongjung-19990101-000000.sql.gz; then pass "15일 지난 백업은 지워진다"; else fail "시험용 오래된 파일을 만들지 못했거나 15일 지난 백업이 남았다 (planted=${planted})"; fi
if box test -e /backups/gyeongjung-20000102-000000.sql.gz; then pass "13일 된 백업은 남는다"; else fail "13일 된 백업이 지워졌다"; fi
if box test -e /backups/notes.txt; then pass "백업 파일이 아닌 파일은 건드리지 않는다"; else fail "백업과 무관한 파일이 지워졌다"; fi

echo "[7] 서버 밖 복사"
out=$(box /ops/backup/backup.sh 2>&1)
if echo "$out" | grep -q "서버 밖 복사 건너뜀" && [ "$(box sh -c 'ls /offsite | wc -l' | tr -d '[:space:]')" -eq 0 ] 2>/dev/null; then pass "OFFSITE_URL이 비어 있으면 건너뛴다"; else fail "비어 있는데 건너뛰지 않았다: $(echo "$out" | tail -2)"; fi
box sh -c 'echo keep > /offsite/keepme.txt'
$DC exec -T -e OFFSITE_URL=/offsite backup /ops/backup/backup.sh >/dev/null 2>&1; code=$?
file=$(newest_backup)
name=${file##*/}
local_sum=$(box sha256sum "$file" | awk '{print $1}')
off_sum=$(box sha256sum "/offsite/${name}" 2>/dev/null | awk '{print $1}')
if [ "$code" -eq 0 ] && [ -n "$off_sum" ] && [ "$local_sum" = "$off_sum" ]; then pass "OFFSITE_URL이 있으면 같은 내용으로 복사된다"; else fail "서버 밖 복사가 안 됐거나 내용이 다르다 (종료 ${code})"; fi
if box test -e /offsite/keepme.txt; then pass "복사만 하고 기존 파일은 지우지 않는다 (업로드 전용 키로도 동작)"; else fail "서버 밖 저장소의 기존 파일이 지워졌다"; fi
hooks_before=$(hooks)
$DC exec -T -e OFFSITE_URL=/dev/null/unwritable backup /ops/backup/backup.sh >/dev/null 2>&1; code=$?
if [ "$code" -ne 0 ] && [ "$(hooks)" -gt "$hooks_before" ] && tail -1 "$HOOKS" | grep -q "서버 밖 복사 실패"; then pass "서버 밖 복사가 실패하면 알림이 온다"; else fail "서버 밖 복사 실패가 알림으로 안 왔다 (종료 ${code})"; fi

echo "[8] 일정 계산 (04:10, 10:10, 16:10, 22:10 KST = 19:10, 01:10, 07:10, 13:10 UTC)"
check_next() {
    now_epoch=$(box date -u -d "$1" +%s | tr -d '\r')
    got=$(box /ops/backup/backup-loop.sh --next "$now_epoch" | tr -d '\r')
    got_text=$(box date -u -d "@${got}" '+%Y-%m-%d %H:%M' 2>/dev/null | tr -d '\r')
    if [ "$got_text" = "$2" ]; then pass "$1 다음 실행은 $2"; else fail "$1 다음 실행이 ${got_text}이다 (기대 $2)"; fi
}
check_next '2026-11-24 00:00:00' '2026-11-24 01:10'
check_next '2026-11-24 01:10:00' '2026-11-24 07:10'
check_next '2026-11-24 13:10:01' '2026-11-24 19:10'
check_next '2026-11-24 19:10:01' '2026-11-25 01:10'
check_next '2026-11-24 23:59:59' '2026-11-25 01:10'

echo "[9] 비밀번호가 로그와 알림에 새지 않는다"
log_lines=$($DC logs backup 2>&1 | grep -c .)
if [ "${log_lines:-0}" -lt 5 ] || [ "$(hooks)" -lt 1 ]; then
    fail "검사할 로그나 알림이 없다 (로그 ${log_lines:-0}줄, 알림 $(hooks)건)"
elif $DC logs backup 2>&1 | grep -qF "$DB_PASSWORD" || grep -qF "$DB_PASSWORD" "$HOOKS"; then
    fail "DB 비밀번호가 로그나 알림에 보인다"
else
    pass "로그 ${log_lines}줄과 알림 $(hooks)건에 DB 비밀번호가 없다"
fi

if [ "$failures" -eq 0 ]; then
    echo "백업 시험 통과"
else
    echo "백업 시험 실패 ($failures)"
    exit 1
fi
