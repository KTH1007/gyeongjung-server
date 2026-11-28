#!/usr/bin/env bash
# 서버에서 배포 전에 돌리는 읽기 전용 사전 점검. 아무것도 바꾸지 않고, 하나가 실패해도 끝까지 점검해서 모두 보여준다.
# 사용법: IMAGE_REGISTRY=ghcr.io/<소유자> ops/preflight.sh [태그]
# 환경변수: PREFLIGHT_PORTS(기본 "8088 9091 3000"), MIN_FREE_DISK_GB(기본 8), MIN_FREE_MEMORY_MB(기본 4096),
#           NEIGHBOR_HEALTH_URL(같은 서버의 다른 서비스 건강 확인 주소), ENV_FILE(기본 .env), MEMINFO_FILE(기본 /proc/meminfo)
# 종료 코드: 0 모두 통과, 1 하나라도 실패, 2 입력 오류
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

tag=${1:-}
if [ -n "$tag" ] && ! [[ $tag =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "태그는 소문자 16진수 7~40자(커밋 해시)여야 한다: '${tag}'" >&2
    exit 2
fi

ports=${PREFLIGHT_PORTS:-"8088 9091 3000"}
min_disk_gb=${MIN_FREE_DISK_GB:-8}
min_memory_mb=${MIN_FREE_MEMORY_MB:-4096}
env_file=${ENV_FILE:-.env}
meminfo_file=${MEMINFO_FILE:-/proc/meminfo}
failures=0

ok() { echo "OK   $1"; }
skip() { echo "SKIP $1: $2"; }
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }

# 1. Docker. 데몬에 접근하지 못하면 아래 점검도 의미가 줄지만, 모든 문제를 한 번에 보여주려고 계속한다.
if root=$(docker info --format '{{.DockerRootDir}}' 2> /dev/null); then
    ok "Docker"
else
    fail "Docker" "데몬에 접근하지 못한다 (docker info 실패. 사용자가 docker 그룹에 속해 있는지 확인한다)"
    root=/var/lib/docker
fi

# 2. Compose 2.24 이상. prod 오버레이가 !reset을 쓴다. 숫자로 비교한다(문자열이면 2.9가 2.24보다 크게 나온다).
compose_available=""
if version=$(docker compose version --short 2> /dev/null); then
    compose_available=1
    version=${version#v}
    major=${version%%.*}
    rest=${version#*.}
    minor=${rest%%.*}
    if [[ $major =~ ^[0-9]+$ && $minor =~ ^[0-9]+$ ]] && { [ "$major" -gt 2 ] || { [ "$major" -eq 2 ] && [ "$minor" -ge 24 ]; }; }; then
        ok "Compose $version"
    else
        fail "Compose" "2.24 이상이 필요하다 (지금 $version)"
    fi
else
    fail "Compose" "docker compose(v2)가 없다. 배포 사용자의 ~/.docker/cli-plugins에 설치한다"
fi

# 3. python3. 배포 락(lock-run.py)과 롤링(rolling.sh)이 쓴다. 락이 쓰는 fcntl까지 불러와서 실제로 돌 수 있는지 본다.
if python3 -c 'import fcntl' > /dev/null 2>&1; then
    ok "python3"
else
    fail "python3" "python3가 없거나 fcntl을 불러오지 못한다. 배포 락(ops/lock-run.py)과 롤링(ops/rolling.sh)이 쓴다"
fi

# 4. 포트. 우리 스택이 이미 떠 있으면 그 포트는 우리 것이라 건너뛴다. 연결이 되면 누가 쓰고 있다는 뜻이다.
if [ -n "$(docker compose -p gyeongjung ps -q 2> /dev/null)" ]; then
    skip "포트" "이미 배포된 스택이 떠 있어서 우리 컨테이너가 쓰는 포트일 수 있다"
else
    busy=""
    # shellcheck disable=SC2086
    for port in $ports; do
        if (exec 3<> "/dev/tcp/127.0.0.1/$port") 2> /dev/null; then
            busy="$busy $port"
        fi
    done
    if [ -z "$busy" ]; then ok "포트 ($ports)"; else fail "포트" "이미 쓰이고 있다:$busy"; fi
fi

# 5. 디스크. 이미지와 볼륨이 쌓이는 Docker 폴더가 있는 곳을 본다.
available_kb=$(df -Pk "$root" 2> /dev/null | awk 'NR==2 {print $4}')
if [[ $available_kb =~ ^[0-9]+$ ]] && [ "$available_kb" -ge $((min_disk_gb * 1048576)) ]; then
    ok "디스크 여유 $((available_kb / 1048576))GB"
else
    fail "디스크" "여유가 ${min_disk_gb}GB 이상이어야 한다 (지금 ${available_kb:-알 수 없음}KB, $root)"
fi

# 6. 메모리. 우리 컨테이너 한도의 합과 같은 서버의 다른 서비스가 같이 쓴다.
available_mem_kb=$(awk '/^MemAvailable:/ {print $2}' "$meminfo_file" 2> /dev/null)
if [[ $available_mem_kb =~ ^[0-9]+$ ]] && [ "$available_mem_kb" -ge $((min_memory_mb * 1024)) ]; then
    ok "메모리 가용 $((available_mem_kb / 1024))MB"
else
    fail "메모리" "가용 메모리가 ${min_memory_mb}MB 이상이어야 한다 (지금 ${available_mem_kb:-알 수 없음}KB)"
fi

# 7. 서버 .env. 값을 읽기만 한다(source하지 않는다). 로컬용 .env를 복사해 오거나 시험용 오버레이가 남아 있으면 서버가 그 구성으로 뜬다.
check_env_file() {
    if [ ! -f "$env_file" ]; then
        fail ".env" "$env_file 이(가) 없다"
        return
    fi
    local problems="" perm key value compose_file
    # 권한 문자열만 읽는다. stat은 리눅스와 맥의 옵션이 달라서 ls를 쓴다.
    # shellcheck disable=SC2012
    perm=$(ls -ld "$env_file" | cut -c1-10)
    [ "${perm:4:6}" = "------" ] || problems="$problems 권한이 $perm (비밀번호가 담긴 파일이라 600이어야 한다);"
    for key in DB_USERNAME DB_PASSWORD GRAFANA_ADMIN_PASSWORD; do
        value=$(sed -n "s/^${key}=//p" "$env_file" | tail -n 1)
        [ -n "$value" ] || problems="$problems $key 값이 비어 있다;"
    done
    compose_file=$(sed -n 's/^COMPOSE_FILE=//p' "$env_file" | tail -n 1)
    case "$compose_file" in
        *docker-compose.prod.yml*) ;;
        *) problems="$problems COMPOSE_FILE에 docker-compose.prod.yml이 없다;" ;;
    esac
    # *-test.yml은 이름 규칙으로 막는다. 시험용 오버레이가 새로 생겨도 서버 .env에 들어가면 여기서 걸린다.
    case "$compose_file" in
        *docker-compose.local.yml* | *-test.yml*) problems="$problems COMPOSE_FILE에 로컬용이나 시험용 오버레이가 들어 있다 ($compose_file);" ;;
    esac
    if [ -z "$problems" ]; then ok ".env"; else fail ".env" "$problems"; fi
}
check_env_file

# 8. compose 설정. 필수 변수 누락, 문법 오류를 실제 서버의 .env와 합쳐서 잡는다.
if [ -z "$compose_available" ]; then
    # Compose가 없으면 모든 compose 명령이 같은 이유로 실패한다. 실패를 둘로 보여주면 진짜 원인을 찾기 어렵다.
    skip "compose 설정" "Compose가 없어서 점검할 수 없다 (위의 Compose 실패를 먼저 고친다)"
elif config_output=$(docker compose -p gyeongjung config -q 2>&1); then
    ok "compose 설정"
else
    fail "compose 설정" "$(echo "$config_output" | head -n 3 | tr '\n' ' ')"
fi

# 9. 레지스트리. 인증 전에는 401이 정상 응답이다.
if [ -z "${IMAGE_REGISTRY:-}" ]; then
    fail "레지스트리" "IMAGE_REGISTRY가 필요하다 (예: ghcr.io/소유자)"
else
    host=${IMAGE_REGISTRY%%/*}
    code=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "https://${host}/v2/") || code=000
    case "$code" in
        200 | 401) ok "레지스트리 ($host, HTTP $code)" ;;
        *) fail "레지스트리" "$host 에 닿지 못했거나 응답이 이상하다 (HTTP $code)" ;;
    esac
fi

# 10. 이미지 태그. 받지 않고 레지스트리에 있는지만 본다. 앱과 백업 이미지가 둘 다 있어야 한다.
if [ -z "$tag" ]; then
    skip "이미지 태그" "태그를 주지 않았다"
elif [ -z "${IMAGE_REGISTRY:-}" ]; then
    fail "이미지 태그" "IMAGE_REGISTRY가 필요하다"
else
    missing=""
    for image in gyeongjung-server gyeongjung-backup; do
        docker manifest inspect "$IMAGE_REGISTRY/$image:$tag" > /dev/null 2>&1 || missing="$missing $IMAGE_REGISTRY/$image:$tag"
    done
    if [ -z "$missing" ]; then ok "이미지 태그 ($tag)"; else fail "이미지 태그" "레지스트리에서 찾지 못했다:$missing"; fi
fi

# 11. 이웃 서비스. 같은 서버의 다른 서비스가 정상일 때만 배포한다. 이름을 저장소에 적지 않으려고 주소를 환경변수로 받는다.
if [ -z "${NEIGHBOR_HEALTH_URL:-}" ]; then
    skip "이웃 서비스" "NEIGHBOR_HEALTH_URL을 주지 않았다"
else
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$NEIGHBOR_HEALTH_URL") || code=000
    if [ "$code" = "200" ]; then
        ok "이웃 서비스 (HTTP 200)"
    else
        fail "이웃 서비스" "건강하지 않다 (HTTP $code). 같은 서버의 다른 서비스가 정상일 때만 배포한다"
    fi
fi

echo
if [ "$failures" -eq 0 ]; then
    echo "사전 점검 통과"
    exit 0
fi
echo "사전 점검 실패 (${failures}개)"
exit 1
