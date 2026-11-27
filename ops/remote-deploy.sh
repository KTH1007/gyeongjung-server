#!/usr/bin/env bash
# CI 러너가 서버에 SSH로 접속해서 사전 점검과 배포를 차례로 실행한다. 서버 쪽은 강제 명령(ops/ssh-deploy.sh)이 이 둘만 허용한다.
# 사용법: DEPLOY_HOST=... DEPLOY_USER=... DEPLOY_SSH_KEY_FILE=... DEPLOY_KNOWN_HOSTS_FILE=... ops/remote-deploy.sh <태그>
# 환경변수: DEPLOY_PORT(기본 22), DEPLOY_SKIP_PREFLIGHT(정확히 1이면 사전 점검을 건너뜀. 점검이 막는 상황의 비상 롤백용),
#           DEPLOY_SSH_BIN(시험용 ssh 대체)
# 호스트 키는 미리 받아 둔 파일(DEPLOY_KNOWN_HOSTS_FILE)로 고정한다. 처음 보는 호스트를 믿지 않는다(중간자 공격 방지).
# 비밀(키 파일 내용)은 읽거나 출력하지 않고 경로만 ssh에 넘긴다. set -x를 쓰지 않는다.
# 종료 코드: 2 입력 오류, 12 사전 점검 실패(배포를 시작하지 않음), 그 외에는 배포의 종료 코드를 그대로
#           (10 배포 전 단계 실패, 11 다른 배포 진행 중, 20 롤백됨, 30 수동 조치 필요, 126 서버가 명령을 거부, 255 SSH 연결 실패)
set -uo pipefail

tag=${1:-}
host=${DEPLOY_HOST:-}
user=${DEPLOY_USER:-}
key=${DEPLOY_SSH_KEY_FILE:-}
known=${DEPLOY_KNOWN_HOSTS_FILE:-}
port=${DEPLOY_PORT:-22}
ssh_bin=${DEPLOY_SSH_BIN:-ssh}

if ! [[ $tag =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "태그는 소문자 16진수 7~40자(커밋 해시)여야 한다: '${tag}'" >&2
    exit 2
fi
if ! [[ $host =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]]; then
    echo "DEPLOY_HOST가 올바르지 않다" >&2
    exit 2
fi
if ! [[ $user =~ ^[a-z_][a-z0-9_-]*$ ]]; then
    echo "DEPLOY_USER가 올바르지 않다" >&2
    exit 2
fi
if ! [[ $port =~ ^[0-9]+$ ]] || [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    echo "DEPLOY_PORT는 1~65535 숫자여야 한다" >&2
    exit 2
fi
if [ ! -f "$key" ]; then
    echo "DEPLOY_SSH_KEY_FILE이 가리키는 파일이 없다" >&2
    exit 2
fi
if [ ! -f "$known" ]; then
    echo "DEPLOY_KNOWN_HOSTS_FILE이 가리키는 파일이 없다. 호스트 키를 고정하지 않고는 접속하지 않는다" >&2
    exit 2
fi

ssh_options=(-i "$key" -p "$port" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes
    -o "UserKnownHostsFile=$known" -o ConnectTimeout=20 -o ServerAliveInterval=30 -o ServerAliveCountMax=10)

# 원격 명령은 항상 한 문자열로 넘긴다. 서버의 강제 명령이 정규식으로 다시 검사한다.
remote() { "$ssh_bin" "${ssh_options[@]}" "$user@$host" "$1"; }

if [ "${DEPLOY_SKIP_PREFLIGHT:-}" = 1 ]; then
    echo "사전 점검을 건너뛴다 (DEPLOY_SKIP_PREFLIGHT=1)"
else
    echo "사전 점검: $tag"
    remote "preflight $tag"
    code=$?
    if [ "$code" -eq 255 ]; then
        echo "SSH 연결에 실패했다. 호스트, 포트, 키, 고정한 호스트 키(known_hosts)를 확인한다" >&2
        exit 255
    fi
    if [ "$code" -ne 0 ]; then
        echo "사전 점검에서 막혀서 배포를 시작하지 않는다 (서버는 바뀌지 않았다)" >&2
        exit 12
    fi
fi

echo "배포: $tag"
remote "deploy $tag"
code=$?
case $code in
    0) echo "배포 성공" ;;
    10) echo "배포 전 단계에서 멈췄다. 아무것도 바꾸지 않았다" >&2 ;;
    11) echo "다른 배포가 진행 중이다. 끝난 뒤에 다시 실행한다" >&2 ;;
    20) echo "새 버전이 확인을 통과하지 못해서 이전 버전으로 되돌렸다. 이번 배포는 실패다" >&2 ;;
    30) echo "되돌림까지 실패했거나 되돌릴 이전 버전이 없다. 수동 조치가 필요하다 (docs/runbook.md)" >&2 ;;
    126) echo "서버가 이 명령을 받아들이지 않는다(허용되지 않은 명령). authorized_keys의 강제 명령 설정을 확인한다" >&2 ;;
    255) echo "SSH 연결에 실패했다. 호스트, 포트, 키, 고정한 호스트 키(known_hosts)를 확인한다" >&2 ;;
    *) echo "배포가 종료 코드 ${code}로 끝났다" >&2 ;;
esac
exit "$code"
