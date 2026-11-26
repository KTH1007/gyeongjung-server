#!/usr/bin/env bash
# 이미지 태그(커밋 해시)를 받아 서버에 배포한다. 새 버전이 확인을 통과하지 못하면 이전 이미지로 되돌린다.
# 사용법: IMAGE_REGISTRY=ghcr.io/<소유자> ops/deploy.sh <태그>
# 종료 코드: 0 성공, 2 입력 오류, 10 배포 전 단계 실패(아무것도 바꾸지 않음),
#            20 새 버전이 실패해서 이전 이미지로 되돌림, 30 되돌림도 실패했거나 되돌릴 이전 버전이 없음(수동 조치 필요)
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

tag=${1:-}
if ! [[ $tag =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "태그는 소문자 16진수 7~40자(커밋 해시)여야 한다: '${tag}'" >&2
    exit 2
fi
if [ -z "${IMAGE_REGISTRY:-}" ]; then
    echo "IMAGE_REGISTRY가 필요하다 (예: ghcr.io/소유자)" >&2
    exit 2
fi

# 스모크처럼 프로젝트를 고정하지 않는 하위 스크립트의 compose 호출도 같은 프로젝트를 보게 한다.
export COMPOSE_PROJECT_NAME=gyeongjung

poll=${DEPLOY_POLL_SECONDS:-5}
health_attempts=${HEALTH_ATTEMPTS:-36}
smoke_attempts=${SMOKE_ATTEMPTS:-6}
smoke_command=${SMOKE_COMMAND:-ops/smoke.sh}
prune_command=${PRUNE_COMMAND:-ops/prune-images.sh}

new_app="$IMAGE_REGISTRY/gyeongjung-server:$tag"
new_backup="$IMAGE_REGISTRY/gyeongjung-backup:$tag"

compose() { docker compose -p gyeongjung "$@"; }

# 서버에서 이미지를 빌드하면 같은 서버의 다른 서비스의 CPU와 메모리를 빼앗는다. 이미지는 CI에서 만들어 받아 온다.
compose_up() { APP_IMAGE=$1 BACKUP_IMAGE=$2 docker compose -p gyeongjung up -d --no-build; }

# 실행 중인 컨테이너가 쓰는 이미지가 이전 버전이다. 별도 상태 파일을 두지 않아서 현실과 어긋날 일이 없다.
running_image() { # 서비스 이름
    local cid
    cid=$(compose ps -q "$1" 2>/dev/null)
    [ -n "$cid" ] && docker inspect -f '{{.Config.Image}}' "$cid"
}

# worker가 healthy가 될 때까지 기다린다. unhealthy이면 바로, 시간 안에 준비되지 않아도 실패한다.
wait_healthy() {
    local cid status attempt=1
    while [ "$attempt" -le "$health_attempts" ]; do
        cid=$(compose ps -q worker 2>/dev/null)
        status=$([ -n "$cid" ] && docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null)
        case "$status" in
            healthy) return 0 ;;
            unhealthy)
                echo "worker가 unhealthy다" >&2
                return 1
                ;;
        esac
        sleep "$poll"
        attempt=$((attempt + 1))
    done
    echo "worker가 시간 안에 healthy가 되지 않았다" >&2
    return 1
}

# Prometheus가 새 worker를 다시 긁기까지 몇 초 걸려서 스모크는 처음 몇 번 실패할 수 있다.
run_smoke() {
    local attempt=1
    while true; do
        echo "스모크 (${attempt}/${smoke_attempts})"
        "$smoke_command" && return 0
        [ "$attempt" -ge "$smoke_attempts" ] && return 1
        sleep "$poll"
        attempt=$((attempt + 1))
    done
}

# 실패한 이미지 하나를 지운다. 지금 쓰는 이미지(같은 태그를 다시 배포한 경우)는 지우지 않고, 실패해도 무시한다.
remove_failed_image() { # 지울 이미지, 지금 쓰는 이미지
    [ "$1" != "$2" ] && docker rmi "$1" > /dev/null
    return 0
}

# 지정한 이미지로 교체하고 준비될 때까지 기다린 뒤 스모크로 확인한다.
release() { # 앱 이미지, 백업 이미지
    compose_up "$1" "$2" && wait_healthy && run_smoke
}

previous_app=$(running_image worker)
previous_backup=$(running_image backup)

# 1. 이미지를 먼저 받는다. 받지 못하면 백업도 하지 않고, 실행 중인 컨테이너를 건드리지 않고 멈춘다.
for image in "$new_app" "$new_backup"; do
    echo "이미지 받기: $image"
    if ! docker pull "$image"; then
        echo "이미지를 받지 못해서 배포하지 않는다: $image" >&2
        exit 10
    fi
done

# 2. 배포 직전 백업. 이미지를 받는 동안 쌓인 변경이 빠지지 않게 교체 직전에 한다.
#    mysql이 아직 없는 첫 배포는 백업할 것이 없다. 실패하면 아무것도 바꾸지 않고 멈춘다.
if [ -n "$(compose ps -q mysql 2>/dev/null)" ]; then
    echo "배포 직전 백업"
    if ! compose exec -T backup /ops/backup/backup.sh; then
        echo "배포 직전 백업에 실패해서 배포하지 않는다" >&2
        exit 10
    fi
else
    echo "mysql이 아직 없는 첫 배포라 백업을 건너뛴다"
fi

# 3. 교체하고 확인한다.
echo "배포: $new_app"
if release "$new_app" "$new_backup"; then
    echo "배포 성공"
    # 정리는 덤이다. 실패해도 배포는 성공이다.
    "$prune_command" || echo "이미지 정리에 실패했지만 배포는 성공했다" >&2
    exit 0
fi

# 4. 확인에 실패하면 이전 이미지로 되돌린다. DB 마이그레이션이 호환되지 않으면 이미지만으로는 부족하다.
echo "배포 후 확인에 실패했다" >&2
if [ -z "$previous_app" ]; then
    echo "되돌릴 이전 버전이 없다. 원인을 조사할 수 있게 컨테이너를 그대로 둔다 (docker compose -p gyeongjung logs worker)" >&2
    exit 30
fi

echo "이전 버전으로 되돌린다: $previous_app"
rollback_backup=${previous_backup:-$new_backup}
if release "$previous_app" "$rollback_backup"; then
    # 실패한 이미지가 "남길 2개" 자리를 차지해서 마지막 정상 이미지가 정리되지 않게 지운다.
    remove_failed_image "$new_app" "$previous_app"
    remove_failed_image "$new_backup" "$rollback_backup"
    echo "이전 버전으로 되돌렸다. 이번 배포는 실패했다. 스키마가 호환되지 않으면 docs/runbook.md의 복구 절차로 배포 직전 백업을 복원한다" >&2
    exit 20
fi
echo "되돌림도 실패했다. 수동 조치가 필요하다 (docs/runbook.md)" >&2
exit 30
