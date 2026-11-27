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
- **조치**: 우리 이미지의 오래된 태그를 지운다(`./ops/prune-images.sh --dry-run`으로 목록을 먼저 보고 `./ops/prune-images.sh`로 지운다. 최신 2개와 실행 중인 이미지는 남는다). 서버 전체를 정리하는 prune 명령은 같은 서버의 다른 서비스 이미지와 볼륨까지 지울 수 있어서 쓰지 않는다. 로그와 백업은 이미 한도가 있다(Docker 로그 10MB×3, 앱 로그 1GB, 백업 14일, Prometheus 15일과 1GB). 그래도 부족하면 디스크를 늘린다.

### 호스트 가용 메모리 10% 미만 (`host-memory-low`, 5분 지속)
- **의미**: 서버 전체의 가용 메모리가 10% 아래다. 가득 차면 리눅스가 프로세스를 강제로 죽인다(OOM). 컨테이너마다 거는 `mem_limit`과 달리 서버 전체를 본다.
- **먼저 볼 것**: `docker stats --no-stream`으로 어느 컨테이너가 많이 쓰는지, 대시보드의 "호스트 메모리". 서버(리눅스)에서는 `free -h`, `dmesg | grep -i "out of memory"`로 이미 죽은 프로세스가 있는지 본다 (이 두 명령은 리눅스 전용이라 첫 배포(#14)에서 확인한다).
- **조치**: 가장 많이 쓰는 서비스를 찾아 원인을 본다(보통 JVM 힙이 늘었거나 MySQL). 급하면 그 서비스를 `docker compose restart <서비스>`로 푼다. 반복되면 컨테이너 한도의 합이 서버 메모리를 넘는지 본다. 서버 메모리를 늘리는 것이 마지막 수단이다.
- **알림 제목이 `DatasourceNoData`이고 요약이 "측정할 수 없습니다"면** 메모리가 부족한 것이 아니라 측정이 안 되는 것이다. `docker compose ps node-exporter`와 `docker compose logs --tail 100 node-exporter`를 보고, 죽었으면 `docker compose up -d node-exporter`.

### 사용자 제보: 429나 503 응답
알림은 없고 사용자나 로그에서 알게 되는 경우다. nginx가 과부하 방어로 요청을 거절한 것이다.
- **의미**: `429`는 한 IP가 쓰기 요청(POST, PUT, PATCH, DELETE)을 너무 빨리 보냈을 때다(읽기 요청은 속도 제한을 받지 않는다). `503`은 동시에 처리 중인 요청이 IP당 또는 서버 전체 한도를 넘었을 때이고 읽기 요청도 센다. `/healthz`는 둘 다 받지 않는다. 다만 `503`은 api가 직접 줄 수도 있어서 상태만으로는 한도 때문인지 알 수 없다.
- **먼저 볼 것**: `docker compose logs --tail 500 nginx | grep -E '" (429|503) '`. 로그의 `ip=`로 어느 IP가 거절됐는지 본다. **503은 같은 줄의 `upstream=`으로 출처를 가른다.** `upstream=-`이면 nginx가 api를 부르지 않고 거절한 것(한도)이고, `upstream=`에 주소가 있으면 api가 503을 준 것이다. 한도 거절이 한 IP에 몰리면 남용이나 오작동 클라이언트이고, 여러 IP에 퍼져 있으면 실제 사용 증가나 서버 지연이다. 한도 거절 503이 많으면 api가 느려져 요청이 쌓인 것은 아닌지 `docker stats --no-stream`과 api 로그를 본다.
- **조치**: `upstream=-`로 한도 거절이 확인되고 정상 사용자가 막히는 것이면 서버 `.env`의 한도(`LIMIT_WRITE_RATE`, `LIMIT_WRITE_BURST`, `LIMIT_CONN_PER_IP`, `LIMIT_CONN_TOTAL`)를 올리고 `docker compose up -d nginx`로 컨테이너를 다시 만든다(`nginx -s reload`로는 반영되지 않는다). 남용이면 Cloudflare에서 그 IP를 막는다. `upstream=`에 주소가 있는 503은 한도를 올려도 풀리지 않으니 api 로그에서 원인을 찾는다. 수치의 근거와 기본값은 `docs/overload-protection.md`.

### 백업이 12시간 넘게 성공하지 않음 (`backup-stale`, 5분 지속)
- **의미**: 마지막 성공한 백업이 12시간을 넘었거나(정기 백업이 두 번 연속 실패), 지표가 아예 없다(backup 컨테이너가 죽었거나 한 번도 안 돌았다). 요약 문구가 둘을 구분한다.
- **먼저 볼 것**: `docker compose ps backup`, `docker compose logs --tail 100 backup`, `docker compose exec backup ls -lt /backups`
- **조치**: 컨테이너가 죽었으면 `docker compose up -d backup`. 로그의 원인(DB 비밀번호, 디스크 가득, mysql 다운)을 고친 뒤 `docker compose exec backup /ops/backup/backup.sh`로 수동 백업한다. 성공하면 알림이 몇 분 안에 해소된다.

### 디스코드 메시지 `[환경] 백업 실패: …`
`backup.sh`가 실패했다. 위 "백업이 12시간 넘게 성공하지 않음"과 같은 순서로 로그에서 원인을 찾는다. 실패한 시도는 파일도 지표도 남기지 않는다.

### 디스코드 메시지 `[환경] 서버 밖 복사 실패: …`
서버 안 백업은 저장됐고 서버 밖으로 복사만 실패했다. `OFFSITE_URL`과 저장소 키, 네트워크를 확인한다. 고친 뒤 `docker compose exec backup /ops/backup/backup.sh`로 새 백업을 만들면 함께 복사된다. 서버가 사라질 때를 대비한 사본이므로 오래 방치하지 않는다.

## 배포 실패 대응
배포는 GitHub의 deploy 워크플로로 하고(`docs/deploy.md`), 실패하면 종료 코드로 어디서 멈췄는지 알 수 있다. 서버에서 볼 기록은 배포 사용자의 `~/.gyeongjung/deploy.log`(호출마다 줄 단위)와 `docker compose -p gyeongjung logs --tail 200 <서비스>`다.

| 종료 코드 | 의미 | 서버 상태 | 조치 |
|---|---|---|---|
| 12 | 사전 점검이 막아서 배포를 시작하지 않음 | 바뀌지 않음 | 로그의 FAIL 항목(디스크, 포트, `.env`, 이웃 서비스 상태 등)을 고친다. 롤백처럼 급하면 `skip_preflight`를 켜서 다시 실행한다 |
| 10 | 배포 전 단계에서 멈춤(이미지 받기, 배포 직전 백업, compose 설정) | 바뀌지 않음 | 로그의 이유를 고치고 다시 실행한다. 백업 실패라면 `backup` 서비스와 디스크부터 본다 |
| 11 | 다른 배포가 진행 중 | 영향 없음 | 끝난 뒤에 다시 실행한다. 오래 걸리면 `deploy.log`와 `docker compose -p gyeongjung ps`로 멈춘 것은 아닌지 본다 |
| 20 | 새 버전이 확인을 통과하지 못해 **이전 버전으로 자동 롤백**됨 | 이전 버전 | 새 버전의 원인을 본다(`logs worker`). DB 스키마가 비호환이면 아래 복구 절차를 따른다 |
| 30 | 롤백도 실패했거나 되돌릴 이전 버전이 없음(첫 배포 실패) | **그대로 둠**(조사용) | 컨테이너를 지우지 말고 `docker compose -p gyeongjung ps`와 로그로 원인을 먼저 기록한다. 필요하면 아래 복구 절차를 따른다 |
| 126 | 서버가 명령을 거부함 | 영향 없음 | 배포 사용자의 `authorized_keys` 강제 명령 줄이 `docs/deploy.md`와 같은지 본다 |
| 255 | SSH 연결 실패(호스트 키 불일치 포함) | 영향 없음 | 호스트, 키, `DEPLOY_KNOWN_HOSTS`를 확인한다. 호스트 키가 바뀐 경고라면 서버가 다시 설치됐거나 중간에 누가 끼어든 것이므로 이유를 확인하기 전에는 값을 바꾸지 않는다 |

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
8. **다시 시작한다.**
   - 데이터만 복구했다면 현재 이미지 그대로: `docker compose start worker`
   - 비호환 마이그레이션을 되돌렸다면 **이전 이미지로** 시작한다. 현재 이미지를 시작하면 복원한 DB에 문제의 마이그레이션을 다시 적용한다. 이전 태그는 서버의 `docker image ls --filter label=project=gyeongjung`에 남아 있는 것(현재와 직전)이거나 GitHub의 이전 deploy 실행 기록에서 찾는다. 그 태그로 deploy 워크플로를 `tag` 입력과 함께 실행하거나(`docs/deploy.md`의 "롤백하기"), GitHub를 쓸 수 없으면 서버에서 `IMAGE_REGISTRY=ghcr.io/<소유자> ./ops/deploy.sh <이전 태그>`를 실행한다. 이 스크립트는 배포 직전 백업을 한 번 더 만들고 이미지를 받아 교체한 뒤 확인한다. 3번에서 worker를 멈춰 둔 상태라 실패해도 되돌릴 이전 버전이 없다(종료 코드 30)는 점에 유의한다.

   시작한 뒤 `./ops/smoke.sh`로 상태를 확인한다.
9. **꺼낸 파일을 지운다.** `rm /tmp/restore.sql.gz` (실제 데이터가 들어 있다)

## 정기 점검 (월 1회)
- `docker compose exec backup ls -lt /backups | head`로 정기 백업이 6시간 간격으로 쌓이는지 본다.
- 가장 최근 백업으로 `restore-drill.sh`를 돌려서 복원과 마이그레이션 적용이 되는지 본다.
- `OFFSITE_URL`이 설정돼 있다면 서버 밖 저장소에도 최근 파일이 있는지 본다.
