# 알림 대응 문서 (runbook)

알림이 왔을 때 무엇을 보고 어떻게 조치하는지 적는다. 명령은 서버의 저장소 폴더에서 실행한다. 여기 적은 명령은 로컬에서 직접 실행해 확인했고, 서버에서 처음 쓰는 것은 첫 배포(#14)에서 한 번 더 확인한다. 알림을 새로 만드는 이슈(#10, #12, #31)는 자기 알림 섹션을 이 문서에 추가한다.

## 먼저 볼 곳
- Grafana(`http://localhost:3000`, 서버에서는 SSH 터널)의 대시보드 **JVM과 호스트 개요**
- 서비스 상태: `docker compose ps`
- 로그: `docker compose logs --tail 200 <서비스>`
- 메모리: `docker stats --no-stream`

## 알림별 대응

### JVM 힙 사용률 85% 초과 (`jvm-heap-high`, 5분 지속)
- **의미**: worker의 JVM 힙이 한도의 85%를 5분 넘게 쓴다. 메모리 누수나 처리량 폭주일 수 있다.
- **먼저 볼 것**: 대시보드의 "힙 사용률", "힙 메모리", "GC 일시정지". `docker stats --no-stream`, `docker compose logs --tail 200 worker`
- **조치**: 급하면 `docker compose restart worker`로 풀 수 있다. 반복되면 로그에서 원인을 찾고 메모리 한도를 조정한다.

### worker 프로세스 재시작 (`process-restarted`)
- **의미**: 최근 10분 안에 worker가 다시 시작했다(배포 또는 크래시).
- **먼저 볼 것**: `docker inspect -f '{{.Name}} OOMKilled={{.State.OOMKilled}} 재시작={{.RestartCount}}' $(docker compose ps -q worker)`, `docker compose logs --tail 200 worker`. 직전에 배포했다면 정상이다.
- **조치**: `OOMKilled=true`면 메모리 한도 문제이고, 예외 로그가 있으면 앱 버그다. 같은 알림이 반복되면 원인을 조사한다.

### 타깃 다운 (`target-down`, 2분 지속)
- **의미**: Prometheus가 알림의 `job`(worker, node, prometheus) 대상에서 지표를 못 가져온다.
- **먼저 볼 것**: 대시보드의 "타깃 상태", `docker compose ps`, `docker compose logs --tail 100 <서비스>`
- **조치**: 죽었으면 `docker compose up -d <서비스>`. worker라면 DB가 살아 있는지도 본다.

### DB 커넥션 풀 90% 초과 (`hikari-pool-high`, 2분 지속)
- **의미**: DB 연결 대부분이 사용 중이다. 느린 쿼리나 연결 누수, 요청 폭주일 수 있다.
- **먼저 볼 것**: 대시보드의 "DB 커넥션 풀", "DB 연결 (사용 중)". 실행 중인 쿼리는
  `docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -e "SHOW FULL PROCESSLIST"'`
- **조치**: 오래 걸리는 쿼리를 찾아 원인을 고친다. 급하면 `docker compose restart worker`로 연결을 새로 맺는다.

### 디스크 여유 15% 미만 (`disk-low`, 5분 지속)
- **의미**: 서버 디스크가 거의 찼다. 가득 차면 DB와 백업이 모두 멈춘다.
- **먼저 볼 것**: `df -h /`, `docker system df`, 대시보드의 "호스트 디스크 여유 (%)"
- **조치**: 안 쓰는 이미지를 지운다(`docker image prune -f`. `-a`는 붙이지 않는다. 롤백용 이전 이미지까지 지워진다). 로그와 백업은 이미 한도가 있다(Docker 로그 10MB×3, 앱 로그 1GB, 백업 14일, Prometheus 15일과 1GB). 그래도 부족하면 디스크를 늘린다.

### 백업이 12시간 넘게 성공하지 않음 (`backup-stale`, 5분 지속)
- **의미**: 마지막 성공한 백업이 12시간을 넘었거나(정기 백업이 두 번 연속 실패), 지표가 아예 없다(backup 컨테이너가 죽었거나 한 번도 안 돌았다). 요약 문구가 둘을 구분한다.
- **먼저 볼 것**: `docker compose ps backup`, `docker compose logs --tail 100 backup`, `docker compose exec backup ls -lt /backups`
- **조치**: 컨테이너가 죽었으면 `docker compose up -d backup`. 로그의 원인(DB 비밀번호, 디스크 가득, mysql 다운)을 고친 뒤 `docker compose exec backup /ops/backup/backup.sh`로 수동 백업한다. 성공하면 알림이 몇 분 안에 해소된다.

### 디스코드 메시지 `[환경] 백업 실패: …`
`backup.sh`가 실패했다. 위 "백업이 12시간 넘게 성공하지 않음"과 같은 순서로 로그에서 원인을 찾는다. 실패한 시도는 파일도 지표도 남기지 않는다.

### 디스코드 메시지 `[환경] 서버 밖 복사 실패: …`
서버 안 백업은 저장됐고 서버 밖으로 복사만 실패했다. `OFFSITE_URL`과 저장소 키, 네트워크를 확인한다. 고친 뒤 `docker compose exec backup /ops/backup/backup.sh`로 새 백업을 만들면 함께 복사된다. 서버가 사라질 때를 대비한 사본이므로 오래 방치하지 않는다.

## 복구 절차
데이터가 지워졌거나 비호환 마이그레이션을 되돌려야 할 때 쓴다. 백업 파일 이름의 시각은 UTC다.

1. **복원할 백업을 고른다.** 마이그레이션을 되돌리는 경우는 배포 직전 백업이다.
   `docker compose exec backup ls -lt /backups`
2. **파일을 꺼내 먼저 리허설한다.** 파일이 멀쩡한지와 행 수를 확인한다.
   ```
   docker compose cp backup:/backups/<파일> /tmp/restore.sql.gz
   ./ops/backup/restore-drill.sh /tmp/restore.sql.gz
   ```
3. **쓰기를 멈춘다.** `docker compose stop worker` (api가 생기면 api도)
4. **지금 상태를 한 번 더 백업해 둔다.** 되돌릴 수 없는 작업이므로 복원 전에 보존한다.
   `docker compose exec backup /ops/backup/backup.sh`
5. **DB를 비우고 복원한다.**
   ```
   docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -e "DROP DATABASE gyeongjung; CREATE DATABASE gyeongjung CHARACTER SET utf8mb4"'
   gunzip -c /tmp/restore.sql.gz | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" gyeongjung'
   ```
6. **확인한다.** 행 수와 Flyway 이력이 백업 시점과 같은지 본다.
   ```
   docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" gyeongjung -N -e "SELECT COUNT(*) FROM raw_position_event; SELECT version, success FROM flyway_schema_history"'
   ```
7. **공백 구간을 기록한다.** 백업 시각부터 복원이 끝난 시각까지는 수집한 이벤트가 없고 서울 API가 과거를 다시 주지 않는다. 시작 시각은 파일 이름의 UTC 시각이다.
   ```
   INSERT INTO collection_gap (started_at, ended_at, reason, created_at, updated_at)
   VALUES ('2026-11-24 01:10:00', NOW(3), 'restore-from-backup', NOW(3), NOW(3));
   ```
8. **다시 시작한다.** `docker compose start worker`. 이어서 `./ops/smoke.sh`로 상태를 확인한다.
9. **꺼낸 파일을 지운다.** `rm /tmp/restore.sql.gz` (실제 데이터가 들어 있다)

## 정기 점검 (월 1회)
- `docker compose exec backup ls -lt /backups | head`로 정기 백업이 6시간 간격으로 쌓이는지 본다.
- 가장 최근 백업으로 `restore-drill.sh`를 돌려서 복원과 마이그레이션 적용이 되는지 본다.
- `OFFSITE_URL`이 설정돼 있다면 서버 밖 저장소에도 최근 파일이 있는지 본다.
