#!/usr/bin/env bash
# 우리 이미지(라벨 project=gyeongjung)의 오래된 태그만 지운다. 같은 서버의 다른 서비스 이미지는 건드리지 않는다.
# 저장소마다 만든 시각이 최신인 이미지 KEEP_IMAGES개(기본 2: 현재와 롤백용 직전)와 컨테이너가 쓰는 이미지는 남긴다.
# 한 이미지에 태그가 여럿이어도 한 개로 센다. 만든 시각이 같은 서로 다른 이미지는 순서를 알 수 없다(실제 빌드는 레이어가 있어 시각이 다르다).
# 사용법: ops/prune-images.sh [--dry-run]   (--dry-run이면 지우지 않고 지울 이미지만 보여준다)
set -uo pipefail

keep=${KEEP_IMAGES:-2}
dry_run=0
[ "${1:-}" = "--dry-run" ] && dry_run=1

# 컨테이너에 붙어 있지 않은 이미지를 한꺼번에 지우는 prune은 롤백용 이미지까지 지워서 쓰지 않고, 태그를 하나씩 지운다.
if ! listing=$(docker image ls --filter "label=project=gyeongjung" \
    --format '{{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.CreatedAt}}'); then
    echo "이미지 목록을 가져오지 못했다" >&2
    exit 1
fi
in_use=$(docker ps -a --format '{{.Image}}')

# 저장소별로 모아서 만든 시각이 최신인 순서로 직접 정렬한다. docker가 주는 순서에 기대지 않는다.
sorted=$(printf '%s\n' "$listing" | sort -t $'\t' -k1,1 -k4,4r)

previous_repo=""
seen_ids=" "
rank=0
removed=0
while IFS=$'\t' read -r repo tag id _; do
    if [ -z "$repo" ] || [ "$repo" = "<none>" ] || [ "$tag" = "<none>" ]; then
        continue
    fi
    if [ "$repo" != "$previous_repo" ]; then
        previous_repo=$repo
        seen_ids=" "
        rank=0
    fi
    # 같은 이미지에 태그가 여럿이면 한 개로 센다. 태그마다 세면 최신 이미지의 태그들이 남길 자리를 다 채워서 롤백용 직전 이미지가 지워진다.
    case "$seen_ids" in
        *" $id "*) ;;
        *)
            seen_ids="$seen_ids$id "
            rank=$((rank + 1))
            ;;
    esac
    [ "$rank" -le "$keep" ] && continue

    ref="$repo:$tag"
    # 컨테이너가 이름이나 ID로 쓰고 있으면 오래되어도 남긴다. -f를 쓰지 않아서 docker도 한 번 더 막는다.
    if printf '%s\n' "$in_use" | grep -qxF -e "$ref" -e "$id"; then
        echo "사용 중이라 남김: $ref"
        continue
    fi
    if [ "$dry_run" = 1 ]; then
        echo "지울 이미지: $ref"
    elif docker rmi "$ref" > /dev/null; then
        echo "지움: $ref"
        removed=$((removed + 1))
    else
        echo "지우지 못함: $ref"
    fi
done <<< "$sorted"

[ "$dry_run" = 1 ] || echo "지운 이미지: ${removed}개"
