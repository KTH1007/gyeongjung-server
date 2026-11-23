#!/usr/bin/env bash
# 복구 리허설. 백업 파일을 일회용 MySQL에 복원하고 raw_position_event 행 수를 보여 준 뒤, 현재 코드의 Flyway 마이그레이션을
# 그 데이터에 적용해 본다. 라이브 DB와 볼륨은 건드리지 않는다. 사용: restore-drill.sh <백업 파일.sql.gz>
# MIGRATIONS_DIR로 마이그레이션 폴더를 바꿀 수 있고(기본은 저장소의 폴더), KEEP=1이면 끝난 뒤에도 일회용 DB를 남긴다.
set -uo pipefail

cd "$(dirname "$0")/../.."

dump=${1:-}
if [ -z "$dump" ] || [ ! -f "$dump" ]; then
    echo "사용법: restore-drill.sh <백업 파일.sql.gz>" >&2
    exit 2
fi
gzip -t "$dump" || { echo "백업 파일이 깨졌다: ${dump}" >&2; exit 1; }

# compose가 파일 전체의 필수 변수를 검사하므로 리허설에 안 쓰는 값은 더미로 채운다.
export DB_USERNAME=${DB_USERNAME:-drill} DB_PASSWORD=${DB_PASSWORD:-drill} GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-drill}
export DRILL_DB_PASSWORD=${DRILL_DB_PASSWORD:-drill-throwaway}
MIGRATIONS_DIR=$(cd "${MIGRATIONS_DIR:-app/src/main/resources/db/migration}" && pwd)
export MIGRATIONS_DIR
# 서버의 COMPOSE_FILE(prod, 알림 오버레이)이 섞이지 않게 기본 파일만 명시하고, 라이브 프로젝트와 겹치지 않는 이름을 쓴다.
DC="docker compose -p gyeongjung-drill-$$ -f docker-compose.yml --profile tools"

cleanup() { [ "${KEEP:-0}" = 1 ] || $DC down -v --remove-orphans >/dev/null 2>&1; }
trap cleanup EXIT

echo "[1/3] 일회용 MySQL 시작"
$DC up -d --wait drill-mysql >/dev/null 2>&1 || { echo "일회용 MySQL이 뜨지 않았다" >&2; exit 1; }

echo "[2/3] 복원: $(basename "$dump")"
if ! gunzip -c "$dump" | $DC exec -T -e MYSQL_PWD="$DRILL_DB_PASSWORD" drill-mysql mysql -uroot gyeongjung; then
    echo "복원에 실패했다" >&2
    exit 1
fi
rows=$($DC exec -T -e MYSQL_PWD="$DRILL_DB_PASSWORD" drill-mysql mysql -uroot gyeongjung -N -e "SELECT COUNT(*) FROM raw_position_event" | tr -d '\r')
echo "raw_position_event 행 수: ${rows}"

echo "[3/3] 현재 코드의 마이그레이션을 복원본에 적용 (${MIGRATIONS_DIR})"
# Flyway CLI의 드라이버는 SSL 없이 처음 인증하려면 서버 공개키를 받아도 된다는 옵션이 필요하다. 일회용 DB라서 문제없다.
if ! $DC run --rm -T flyway -connectRetries=10 -url="jdbc:mysql://drill-mysql:3306/gyeongjung?allowPublicKeyRetrieval=true" -user=root -password="$DRILL_DB_PASSWORD" -locations=filesystem:/flyway/sql migrate; then
    echo "마이그레이션 적용에 실패했다 (적용된 파일이 바뀌었거나 새 마이그레이션이 복원본과 맞지 않는다)" >&2
    exit 1
fi
echo "복구 리허설 성공"
