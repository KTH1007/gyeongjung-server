#!/usr/bin/env bash
# backup 컨테이너의 시작 명령. 시작하자마자 한 번 백업하고, 이후 매일 01:10, 07:10, 13:10, 19:10(UTC)에 백업한다.
# 한국 시간으로는 10:10, 16:10, 22:10, 04:10이다. 인자로 --next <epoch>를 주면 그 다음 실행 시각만 출력하고 끝난다.
set -uo pipefail

BACKUP_DIR=${BACKUP_DIR:-/backups}
# 하루(86400초) 안에서의 실행 시각(초): 01:10, 07:10, 13:10, 19:10
SCHEDULE_OFFSETS="4200 25800 47400 69000"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }

# $1(epoch 초)보다 뒤인 가장 가까운 실행 시각을 출력한다. 정확히 같은 시각이면 다음 실행을 가리킨다.
next_run() {
    now=$1
    day_start=$((now - now % 86400))
    for offset in $SCHEDULE_OFFSETS; do
        candidate=$((day_start + offset))
        if [ "$candidate" -gt "$now" ]; then
            echo "$candidate"
            return
        fi
    done
    echo $((day_start + 86400 + 4200))
}

if [ "${1:-}" = "--next" ]; then
    case "${2:-}" in
        '' | *[!0-9]*)
            echo "사용법: backup-loop.sh --next <epoch 초>" >&2
            exit 2
            ;;
    esac
    next_run "$2"
    exit 0
fi

run_backup() { /ops/backup/backup.sh || log "백업이 실패했다. 다음 일정에 다시 시도한다"; }

sleep_pid=""
# 컨테이너가 멈출 때(SIGTERM) 대기 중이면 바로 끝낸다. 백업 중이면 그 백업이 끝난 뒤 처리된다.
trap '[ -n "$sleep_pid" ] && kill "$sleep_pid" 2>/dev/null; log "종료 신호를 받아 멈춘다"; exit 0' TERM INT

log "백업 서비스 시작: 지금 한 번 백업하고 이후 01:10, 07:10, 13:10, 19:10(UTC)에 백업한다"
# 컨테이너가 중간에 강제 종료됐을 때 남은 반쪽 파일을 치운다.
rm -f "${BACKUP_DIR}"/.gyeongjung-*.partial
run_backup

while true; do
    now=$(date -u +%s)
    target=$(next_run "$now")
    log "다음 백업까지 $((target - now))초 ($(date -u -d "@${target}" '+%Y-%m-%d %H:%M UTC'))"
    sleep $((target - now)) &
    sleep_pid=$!
    wait "$sleep_pid"
    sleep_pid=""
    run_backup
done
