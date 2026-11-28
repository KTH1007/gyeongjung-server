#!/usr/bin/env bash
# compose 서비스의 복제본을 하나씩 교체한다(롤링). compose의 기본 교체는 복제본을 한꺼번에 내려서 새 복제본이 뜰 때까지 서비스가 끊긴다.
# 새 복제본을 먼저 추가하고, healthy가 되면 nginx가 새 주소를 찾을 시간을 준 뒤 옛 복제본 하나를 내리는 일을 반복한다.
# 옛 복제본은 컨테이너의 config-hash 라벨이 지금 compose 설정의 해시와 다른 것이다. 롤백은 이전 설정으로 이 스크립트를 다시 실행하면 된다.
# 사용법: ops/rolling.sh <서비스>   (compose 파일은 COMPOSE_FILE 환경변수를 따른다)
# 환경변수: COMPOSE_PROJECT_NAME(기본 gyeongjung), ROLLING_SETTLE_SECONDS(기본 12), ROLLING_HEALTH_ATTEMPTS(기본 36),
#           ROLLING_POLL_SECONDS(기본 5), ROLLING_STOP_TIMEOUT(기본 40)
# 종료 코드: 0 성공(이미 최신이어도 0), 2 입력 오류, 10 교체를 끝내지 못함(새 복제본이 healthy가 되지 않았거나 옛 복제본을 내리지 못함)
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

service=${1:-}
if ! [[ $service =~ ^[a-z][a-z0-9_-]*$ ]]; then
    echo "서비스 이름 형식이 올바르지 않다: '${service}'" >&2
    exit 2
fi

project=${COMPOSE_PROJECT_NAME:-gyeongjung}
# nginx는 api 주소를 10초마다 다시 찾는다. 새 복제본의 주소를 nginx가 알기 전에 옛 복제본을 모두 내리면 갈 곳이 없다.
# Docker가 내린 복제본의 IP를 새 복제본에 다시 주면 대기 0초로도 읽기 실패가 없었다(5회 시험). 그 재사용은 보장이 아니라서 안전 여유로 둔다.
settle=${ROLLING_SETTLE_SECONDS:-12}
attempts=${ROLLING_HEALTH_ATTEMPTS:-36}
poll=${ROLLING_POLL_SECONDS:-5}
# 앱의 graceful shutdown(30초)이 처리 중인 요청을 마무리할 시간보다 길게 준다.
stop_timeout=${ROLLING_STOP_TIMEOUT:-40}

compose() { docker compose -p "$project" "$@"; }
container_hash() { docker inspect -f '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$1"; }
health_of() { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1"; }

want_hash=$(compose config --hash "$service" 2> /dev/null | awk '{print $2}')
if [ -z "$want_hash" ]; then
    echo "compose 설정에 없는 서비스다: $service" >&2
    exit 2
fi

read -r replicas has_healthcheck < <(compose config --format json 2> /dev/null | python3 -c '
import json, sys
service = json.load(sys.stdin)["services"][sys.argv[1]]
check = service.get("healthcheck") or {}
print(service.get("deploy", {}).get("replicas", 1), 0 if not check or check.get("disable") else 1)
' "$service" 2> /dev/null)
if ! [[ ${replicas:-} =~ ^[0-9]+$ ]]; then
    echo "compose 설정을 읽지 못했다 (python3와 docker compose config --format json이 필요하다)" >&2
    exit 2
fi
if [ "$has_healthcheck" != 1 ]; then
    echo "${service}에 헬스체크가 없다. 새 복제본이 준비됐는지 알 수 없으면 롤링할 수 없다" >&2
    exit 2
fi

# 지금 설정과 해시가 같은 것이 새 복제본(fresh), 다른 것이 옛 복제본(stale)이다.
classify() {
    stale=""
    fresh=""
    local id
    for id in $(compose ps -aq "$service"); do
        if [ "$(container_hash "$id")" = "$want_hash" ]; then fresh="$fresh $id"; else stale="$stale $id"; fi
    done
}

# 새 복제본이 모두 healthy가 될 때까지 기다린다. unhealthy이면 바로, 시간 안에 되지 않아도 실패다.
# 옛 복제본은 기다릴 이유가 없다(롤백 때는 고장 난 복제본이 옛 복제본이 된다).
wait_fresh_healthy() {
    local attempt=1 id all
    while [ "$attempt" -le "$attempts" ]; do
        classify
        all=1
        # 새 복제본이 하나도 안 보이는 것은 확인할 것이 없다는 뜻이 아니라 아직 못 찾았다는 뜻이다. 성공으로 치면 옛 복제본을 내려서 서비스가 끊긴다.
        [ -n "$fresh" ] || all=0
        for id in $fresh; do
            case "$(health_of "$id")" in
                healthy) ;;
                unhealthy)
                    echo "새 복제본이 unhealthy다: $id" >&2
                    return 1
                    ;;
                *) all=0 ;;
            esac
        done
        [ "$all" -eq 1 ] && return 0
        sleep "$poll"
        attempt=$((attempt + 1))
    done
    echo "새 복제본이 보이지 않거나 시간 안에 healthy가 되지 않았다" >&2
    return 1
}

fail_unhealthy() {
    echo "새 복제본이 healthy가 되지 않아 교체를 멈췄다. 옛 복제본은 그대로 서비스 중이다." >&2
    echo "원인: docker compose -p $project logs $service. 이전 설정으로 이 스크립트를 다시 실행하면 새 복제본이 정리된다" >&2
    exit 10
}

classify
if [ -z "$stale$fresh" ]; then
    echo "$service 복제본이 없어서 새로 올린다"
    compose up -d --no-build --no-deps "$service" || exit 10
    wait_fresh_healthy || fail_unhealthy
    echo "교체 완료: $service"
    exit 0
fi
if [ -z "$stale" ]; then
    echo "이미 최신이다: $service"
    exit 0
fi

for old in $stale; do
    echo "옛 복제본 교체: $old"
    # 새 복제본을 먼저 추가한다. 이미 충분히 있으면(중단된 교체를 이어받는 경우) 더 만들지 않는다.
    compose up -d --no-build --no-deps --no-recreate --scale "$service=$((replicas + 1))" "$service" || exit 10
    wait_fresh_healthy || fail_unhealthy
    [ "$settle" -gt 0 ] && sleep "$settle"
    docker stop -t "$stop_timeout" "$old" > /dev/null || { echo "옛 복제본을 내리지 못했다: $old" >&2; exit 10; }
    docker rm "$old" > /dev/null || { echo "옛 복제본을 지우지 못했다: $old" >&2; exit 10; }
done
echo "교체 완료: $service"
