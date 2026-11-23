#!/usr/bin/env bash
# 한 번의 백업. DB를 덤프해 gzip으로 저장하고, 지표를 남기고, 오래된 백업을 지우고, 서버 밖으로 복사한다.
# 어느 단계든 실패하면 디스코드로 알리고 0이 아닌 코드로 끝난다.
set -uo pipefail

DB_HOST=${DB_HOST:-mysql}
DB_NAME=${DB_NAME:-gyeongjung}
BACKUP_DIR=${BACKUP_DIR:-/backups}
METRICS_DIR=${METRICS_DIR:-/metrics}
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
OFFSITE_URL=${OFFSITE_URL:-}
ENVIRONMENT_NAME=${ENVIRONMENT_NAME:-local}
DISCORD_WEBHOOK_URL=${DISCORD_WEBHOOK_URL:-}
: "${DB_USERNAME:?DB_USERNAME이 필요하다}" "${DB_PASSWORD:?DB_PASSWORD가 필요하다}"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }

# 알림 전송이 실패해도 백업 결과는 그대로 두려고 항상 0으로 끝난다. 메시지에는 비밀과 DB 오류 본문을 넣지 않는다.
notify() {
    if [ -z "$DISCORD_WEBHOOK_URL" ]; then
        log "디스코드 웹훅이 없어 알림을 보내지 못했다: $1"
        return 0
    fi
    curl -fsS -m 10 -H 'Content-Type: application/json' \
        -d "{\"content\":\"[${ENVIRONMENT_NAME}] $1\"}" "$DISCORD_WEBHOOK_URL" >/dev/null 2>&1 \
        || log "디스코드 알림 전송에 실패했다"
    return 0
}

stamp=$(date -u +%Y%m%d-%H%M%S)
name="gyeongjung-${stamp}.sql.gz"
final="${BACKUP_DIR}/${name}"
partial="${BACKUP_DIR}/.${name}.partial"
# 중간에 실패해도 반쯤 쓰인 파일이 백업처럼 남지 않게 한다. 점으로 시작해서 목록과 보관 정리에 잡히지 않는다.
trap 'rm -f "$partial"' EXIT

fail() {
    log "백업 실패: $1"
    notify "백업 실패: $1"
    exit 1
}

log "백업 시작: ${name}"
# 비밀번호를 명령줄에 두면 프로세스 목록에 보이므로 환경변수로 넘긴다.
export MYSQL_PWD="$DB_PASSWORD"
# 앱 계정에는 PROCESS 권한이 없어서 --no-tablespaces가 필요하다.
mysqldump -h"$DB_HOST" -u"$DB_USERNAME" --single-transaction --no-tablespaces --routines --triggers --events \
    --set-gtid-purged=OFF --default-character-set=utf8mb4 "$DB_NAME" | gzip -c > "$partial"
codes=("${PIPESTATUS[@]}")
[ "${codes[0]}" -eq 0 ] || fail "mysqldump 종료 코드 ${codes[0]} (원인은 backup 컨테이너 로그에 있다)"
[ "${codes[1]}" -eq 0 ] || fail "gzip 종료 코드 ${codes[1]}"
gzip -t "$partial" || fail "gzip 파일이 깨졌다"
# mysqldump는 끝까지 쓴 덤프의 마지막 줄에 이 표시를 남긴다. 없으면 중간에 끊긴 것이다.
[ "$(gunzip -c "$partial" | tail -n 1 | grep -c 'Dump completed')" -eq 1 ] || fail "덤프가 끝까지 쓰이지 않았다"
mv "$partial" "$final" || fail "백업 파일을 옮기지 못했다"
size=$(stat -c %s "$final")
log "백업 완료: ${name} (${size} bytes)"

# 성공한 뒤에만 지표를 갱신한다. node-exporter가 반쯤 쓰인 파일을 읽지 않게 임시 파일에 쓰고 옮긴다.
metrics_tmp="${METRICS_DIR}/.backup.prom.$$"
{
    echo "# HELP backup_last_success_timestamp_seconds Unix time of the last successful backup."
    echo "# TYPE backup_last_success_timestamp_seconds gauge"
    echo "backup_last_success_timestamp_seconds $(date -u +%s)"
    echo "# HELP backup_last_size_bytes Size of the last backup file in bytes."
    echo "# TYPE backup_last_size_bytes gauge"
    echo "backup_last_size_bytes ${size}"
} > "$metrics_tmp" && mv "$metrics_tmp" "${METRICS_DIR}/backup.prom" || fail "지표를 기록하지 못했다 (백업 파일은 저장됨)"

# 새 백업이 확인된 뒤에만 오래된 것을 지운다. 이름 규칙에 맞는 파일만 지운다.
# 정리가 실패해도 이미 저장한 새 백업과 서버 밖 복사는 그대로 하고, 알린 뒤 마지막에 0이 아닌 코드로 끝낸다.
exit_code=0
deleted=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'gyeongjung-*.sql.gz' -mtime +"$RETENTION_DAYS" -print -delete | wc -l); retention_code=$?
if [ "$retention_code" -eq 0 ]; then
    log "보관 기간(${RETENTION_DAYS}일)이 지난 백업 ${deleted}개 삭제"
else
    exit_code=1
    log "보관 정리 실패: find 종료 코드 ${retention_code}"
    notify "보관 정리 실패: find 종료 코드 ${retention_code}. 오래된 백업이 쌓일 수 있다. 새 백업은 저장됨"
fi

if [ -z "$OFFSITE_URL" ]; then
    log "서버 밖 복사 건너뜀 (OFFSITE_URL이 비어 있음)"
    exit "$exit_code"
fi
# 업로드 전용 키는 버킷을 만들거나 확인할 권한이 없으므로 그 확인을 건너뛴다. copyto는 올리기만 하고 지우지 않는다.
export RCLONE_S3_NO_CHECK_BUCKET=true
if rclone copyto --quiet --config /dev/null "$final" "${OFFSITE_URL%/}/${name}"; then
    log "서버 밖 복사 완료: ${OFFSITE_URL%/}/${name}"
else
    code=$?
    log "서버 밖 복사 실패: rclone 종료 코드 ${code}"
    notify "서버 밖 복사 실패: ${name} (rclone 종료 코드 ${code}). 서버 안 백업은 저장됨"
    exit 1
fi
exit "$exit_code"
