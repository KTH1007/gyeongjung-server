#!/usr/bin/env bash
# CI의 ARM 러너에서 우리 이미지 두 개(서버, 백업)를 빌드해서 레지스트리에 올린다. 서버가 aarch64라서 linux/arm64로 만든다.
# 둘 다 빌드에 성공한 뒤에 올린다(한쪽만 올라간 상태를 만들지 않으려는 것이다). 태그는 커밋 해시이고 latest는 만들지 않는다.
# 사용법: ops/build-push.sh <레지스트리/소유자> <태그>     예) ops/build-push.sh ghcr.io/kth1007 3f2a9c1...
# 레지스트리 로그인(docker login)은 호출하는 쪽이 한다. 종료 코드: 0 성공, 2 입력 오류, 10 빌드 실패, 11 푸시 실패
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

registry=${1:-}
tag=${2:-}
if ! [[ $registry =~ ^[a-z0-9][a-z0-9.-]*(:[0-9]+)?/[a-z0-9][a-z0-9._/-]*$ ]]; then
    echo "레지스트리는 호스트/소유자 형식의 소문자여야 한다: '${registry}'" >&2
    exit 2
fi
if ! [[ $tag =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "태그는 소문자 16진수 7~40자(커밋 해시)여야 한다: '${tag}'" >&2
    exit 2
fi

server="$registry/gyeongjung-server:$tag"
backup="$registry/gyeongjung-backup:$tag"

docker build --platform linux/arm64 -t "$server" . || { echo "서버 이미지 빌드에 실패했다" >&2; exit 10; }
docker build --platform linux/arm64 -t "$backup" ops/backup || { echo "백업 이미지 빌드에 실패했다" >&2; exit 10; }
docker push "$server" || { echo "서버 이미지를 올리지 못했다" >&2; exit 11; }
docker push "$backup" || { echo "백업 이미지를 올리지 못했다" >&2; exit 11; }
echo "올림: $server, $backup"
