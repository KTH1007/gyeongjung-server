#!/usr/bin/env bash
# sshd의 강제 명령 진입점이다. 배포 키의 authorized_keys 줄이 이 스크립트만 실행하게 가둔다:
#   command="/opt/gyeongjung/ops/ssh-deploy.sh ghcr.io/<소유자>",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA...
# 호출자가 보낸 명령($SSH_ORIGINAL_COMMAND)은 아래 셋만 허용하고 나머지는 거부한다. 셸로 해석하지 않고 정규식으로만 읽는다.
#   deploy <태그>        ops/deploy.sh <태그>   (한 번에 하나만. 진행 중이면 기다리지 않고 11로 끝난다)
#   preflight [<태그>]   ops/preflight.sh [<태그>]
# 레지스트리는 호출자가 못 바꾸도록 인자로 고정한다. 호출자의 환경은 믿지 않고 정해진 값만 넘긴다.
# 이 스크립트와 ops/는 배포 사용자가 쓸 수 없어야 한다(쓸 수 있으면 이 제한이 의미 없다).
# 기록: $HOME/.gyeongjung/deploy.log (호출마다 줄 단위)
# 종료 코드: 2 설정 오류(레지스트리), 11 다른 배포 진행 중, 126 허용되지 않은 명령, 그 외는 실행한 스크립트의 종료 코드
set -uo pipefail
umask 077
# SSH 연결이 끊겨도 기록과 점검이 이어지게 한다.
trap '' PIPE

here=$(cd "$(dirname "$0")" && pwd)
registry=${1:-}
if ! [[ $registry =~ ^[a-z0-9][a-z0-9.-]*(:[0-9]+)?/[a-z0-9][a-z0-9._/-]*$ ]]; then
    echo "레지스트리 인자가 올바르지 않다: '${registry}'" >&2
    exit 2
fi

state_dir="$HOME/.gyeongjung"
mkdir -p "$state_dir"
log="$state_dir/deploy.log"
lock="$state_dir/deploy.lock"
log_line() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$log"; }

# 배포 스크립트에는 호출자의 환경변수를 넘기지 않는다. SMOKE_COMMAND 같은 시험용 훅이나 DOCKER_HOST가 주입되는 길을 막는다.
child_env=(env -i PATH=/usr/local/bin:/usr/bin:/bin HOME="$HOME" LANG=C.UTF-8 IMAGE_REGISTRY="$registry")

cmd=${SSH_ORIGINAL_COMMAND-}
if [[ $cmd =~ ^deploy\ ([0-9a-f]{7,40})$ ]]; then
    tag=${BASH_REMATCH[1]}
    log_line "요청 deploy $tag"
    python3 "$here/lock-run.py" "$lock" "${child_env[@]}" "$here/deploy.sh" "$tag"
    code=$?
    if [ "$code" -eq 11 ]; then
        log_line "거절 deploy $tag (다른 배포가 진행 중)"
    else
        log_line "종료 deploy $tag 코드 $code"
    fi
    exit "$code"
elif [[ $cmd =~ ^preflight(\ ([0-9a-f]{7,40}))?$ ]]; then
    tag=${BASH_REMATCH[2]:-}
    log_line "요청 preflight ${tag:--}"
    "${child_env[@]}" "$here/preflight.sh" ${tag:+"$tag"}
    code=$?
    log_line "종료 preflight ${tag:--} 코드 $code"
    exit "$code"
fi

# 로그 줄을 위조하지 못하게 제어문자를 지운 사본만 남긴다.
safe=$(printf '%s' "$cmd" | tr -c '[:print:]' '?')
log_line "거부 [${safe:0:200}]"
echo "허용되지 않은 명령이다. 허용: deploy <태그>, preflight [<태그>]" >&2
exit 126
