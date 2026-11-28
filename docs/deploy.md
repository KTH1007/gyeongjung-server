# 배포 문서

경중또늦네를 서버에 올리고 갱신하는 방법과 그렇게 한 이유를 적는다. 서버 쪽 준비(사용자, 키, 설치)는 한 번만 하고, 그 뒤의 배포는 GitHub에서 버튼 한 번이다. 스크립트와 SSH 제한, 롤링, 롤백은 로컬에서 진짜 docker와 임시 sshd로 확인했다. 서버와 GitHub 러너에서 처음 해 보는 것은 첫 배포(#14)에서 확인하고 이 문서에 반영한다.

이 저장소는 공개라서 서버 주소, 같은 서버의 다른 서비스가 쓰는 포트, 서버의 보안 상태는 여기에 적지 않는다. 그런 기록은 저장소 밖에 둔다.

## 한눈에 보기
```
GitHub Actions의 deploy 워크플로 (수동 실행, main 브랜치만)
  ├─ build 잡: ARM 러너에서 이미지 두 개(서버, 백업)를 빌드해 ghcr.io에 올린다      ops/build-push.sh
  └─ deploy 잡: SSH로 서버에 사전 점검과 배포를 요청한다                          ops/remote-deploy.sh
        서버: sshd 강제 명령 → 락 → 배포                                           ops/ssh-deploy.sh → ops/lock-run.py → ops/deploy.sh
              이미지 받기 → 배포 직전 백업 → 교체(worker는 내렸다 올림, api는 롤링) → 확인 → 실패하면 롤백
```
- 서버는 코드를 빌드하지 않는다. 같은 서버의 다른 서비스(모각작)의 CPU와 메모리를 쓰지 않으려고 완성된 이미지를 받기만 한다.
- 이미지 태그는 커밋 해시이고 `latest`는 만들지 않는다. 롤백은 이전 커밋 해시로 같은 배포를 다시 하는 것이다.
- 지금은 사람이 버튼을 눌러 시작한다. 자동 배포는 도메인과 TLS를 붙이는 #39에서 정한다.

## 구성 요소
| 파일 | 어디서 | 하는 일 |
|---|---|---|
| `.github/workflows/deploy.yml` | GitHub | 위 흐름을 잇는다. 로직은 아래 스크립트에 있고 이 파일은 연결만 한다 |
| `ops/build-push.sh` | 러너 | 서버와 백업 이미지를 `linux/arm64`로 **둘 다 빌드한 뒤** 올린다 |
| `ops/remote-deploy.sh` | 러너 | 고정한 호스트 키로 접속해 `preflight`, `deploy`를 차례로 요청하고 종료 코드의 뜻을 알려준다 |
| `ops/ssh-deploy.sh` | 서버 | 배포 키의 강제 명령. `deploy <태그>`와 `preflight [<태그>]`만 받고 나머지는 거부, 호출 기록을 남긴다 |
| `ops/lock-run.py` | 서버 | 배포가 한 번에 하나만 돌게 파일 락을 잡는다 |
| `ops/preflight.sh` | 서버 | 읽기 전용 사전 점검(Docker, Compose 2.24 이상, python3, 포트, 디스크 8GB, 메모리 4GB, `.env`, 레지스트리, 이미지 태그, 이웃 서비스 상태) |
| `ops/deploy.sh` | 서버 | 이미지 받기, 배포 직전 백업, 교체, 확인, 롤백 |
| `ops/rolling.sh` | 서버 | api 복제본을 한 개씩 교체(api가 생기면 `deploy.sh`가 부른다) |
| `ops/smoke.sh` | 서버 | 배포 직후 확인(worker 헬스, Prometheus 타깃) |
| `ops/prune-images.sh` | 서버 | 우리 이미지의 오래된 태그만 정리(최신 2개와 실행 중인 것은 남김) |

`docker-compose.yml`과 `docker-compose.prod.yml`이 서버 구성이고, `docker-compose.alerting.yml`은 알림을 켤 때만 더한다. `docker-compose.local.yml`, `docker-compose.nginx-test.yml`, `docker-compose.backup-test.yml`은 로컬과 시험 전용이라 서버 `.env`의 `COMPOSE_FILE`에 넣지 않는다.

## 같은 서버를 쓰므로 지키는 것
경중은 모각작이 운영 중인 서버에 **추가만** 하는 방식으로 올린다(트레이드오프 3.35).
- 모각작의 컨테이너, 포트, 설정을 건드리지 않는다. 서버 재부팅, Docker 데몬 재시작, 서버 전체 정리 명령은 쓰지 않는다. 이 중 하나라도 모각작을 멈출 수 있다.
- 우리 포트는 호스트의 `127.0.0.1`에만 연다. 외부에서는 SSH 터널로 접근한다.

  | 서비스 | 호스트 포트 | 비고 |
  |---|---|---|
  | nginx | `127.0.0.1:8088` | 호스트의 80과 443은 같은 서버의 다른 서비스가 쓴다. 외부 공개는 #39 |
  | Prometheus | `127.0.0.1:${PROMETHEUS_HOST_PORT}` | 서버 `.env`에서 `9091`로 둔다(기본 9090은 겹칠 수 있다) |
  | Grafana | `127.0.0.1:3000` | |

  배포 전 `preflight`가 이 포트들이 비어 있는지 본다. 같은 서버의 다른 서비스가 쓰는 포트 목록은 저장소 밖의 점검 기록에 있다.
- 컨테이너는 CPU 우선순위(`cpu_shares`)를 낮게, 메모리 부족 때 먼저 죽을 순서(`oom_score_adj`)를 높게 주고, 새 권한을 얻지 못하게(`no-new-privileges`) 했다. 우리 이미지의 컨테이너는 리눅스 권한도 모두 뺐다.
- 우리 이미지에는 `project=gyeongjung` 라벨이 있고, 정리는 이 라벨이 붙은 것만 한다.

## 서버 준비 (한 번만, 관리 계정에서)
서버에 쓰는 모든 단계는 사용자의 승인을 받고 한 번에 하나씩 한다.

### 1. 배포 사용자와 설치 폴더
배포 키가 유출돼도 피해를 줄이려고, 배포 전용 사용자를 따로 만들고 **관리자가 검토한 스크립트만** 실행하게 한다. 배포 사용자는 docker 그룹에 들어가야 하는데 docker 그룹은 사실상 root 권한이라서, 스크립트와 compose 파일을 이 사용자가 고칠 수 없어야 제한이 의미가 있다.

```
sudo adduser --disabled-password --gecos "" gyeongjung-deploy
sudo usermod -aG docker gyeongjung-deploy
sudo mkdir -p /opt/gyeongjung && sudo chown <관리 계정>: /opt/gyeongjung
git clone https://github.com/KTH1007/gyeongjung-server.git /opt/gyeongjung
```
- `/opt/gyeongjung`의 파일은 관리 계정 소유이고 배포 사용자는 읽기와 실행만 한다. `ops/`나 compose 파일이 바뀐 배포 전에는 관리자가 먼저 `git pull`로 갱신한다.
- 배포 사용자가 쓸 수 있는 곳은 자기 홈(`~/.gyeongjung`의 호출 기록과 락)과 서버 `.env` 하나뿐이다.
- 쓸 수 없는지 확인한다. 아래 셋 모두 "쓸 수 없음"이 나와야 한다.
  ```
  for f in /opt/gyeongjung/ops/deploy.sh /opt/gyeongjung/ops /opt/gyeongjung/docker-compose.prod.yml; do
    sudo -u gyeongjung-deploy test -w "$f" && echo "쓸 수 있음(잘못) $f" || echo "쓸 수 없음 $f"
  done
  ```
- 서버에 `python3`와 `curl`이 있어야 한다(`rolling.sh`, `lock-run.py`, `smoke.sh`가 쓴다). `preflight`가 `python3`는 락이 쓰는 `fcntl`까지 불러와서 확인한다.

### 2. Docker Compose v2
서버에 `docker compose`(v2)가 없다. Docker 데몬은 건드리지 않고 **배포 사용자의 홈에만** 설치한다. 2.24 이상이 필요하다(`docker-compose.prod.yml`의 `!reset`).
- Docker Compose 릴리스 페이지에서 ARM64용 바이너리와 체크섬 파일을 받아 **체크섬을 대조한 뒤** `~gyeongjung-deploy/.docker/cli-plugins/docker-compose`에 실행 권한(755)으로 둔다.
- 확인: `sudo -u gyeongjung-deploy docker compose version`

### 3. 서버 `.env` (사용자가 직접 작성, 권한 600, 소유자는 배포 사용자)
```
sudo install -m 600 -o gyeongjung-deploy -g gyeongjung-deploy /dev/null /opt/gyeongjung/.env
```
- 로컬 `.env`를 복사하지 않는다. 복사하면 local 오버레이와 재생용 설정으로 뜬다. `preflight`가 권한 600, 필수 값, prod 오버레이 포함, 로컬용(`docker-compose.local.yml`)과 시험용(`*-test.yml`) 오버레이 제외를 확인한다.
- 담을 것(비밀은 이 파일에만 있고 저장소에 올리지 않는다):
  ```
  COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml:docker-compose.alerting.yml
  DB_USERNAME=gyeongjung
  DB_PASSWORD=<강한 값>
  GRAFANA_ADMIN_PASSWORD=<강한 값>
  ENVIRONMENT_NAME=prod
  DISCORD_WEBHOOK_URL=<prod 웹훅>
  PROMETHEUS_HOST_PORT=9091
  # 서버 밖 백업을 켤 때: OFFSITE_URL과 RCLONE_CONFIG_OFFSITE_* (아래 "서버 밖 백업")
  ```
- prod 웹훅은 서버 `.env`에만 둔다. 시험과 점검에서 prod 디스코드 채널로 테스트 메시지를 보내지 않는다.

### 4. 배포 키와 강제 명령
키 쌍은 믿을 수 있는 내 컴퓨터에서 만든다. CI가 비밀번호 없이 써야 하므로 비밀번호를 걸지 않고, 대신 키가 할 수 있는 일을 서버의 강제 명령으로 가둔다.
```
ssh-keygen -t ed25519 -N "" -C gyeongjung-deploy -f ./gyeongjung-deploy
```
서버의 `~gyeongjung-deploy/.ssh/authorized_keys`에 아래 한 줄을 넣는다(소유자는 root, 권한 644. 배포 사용자가 자기 제한을 고치지 못하게). 공개 키는 `gyeongjung-deploy.pub`이다.
```
command="/opt/gyeongjung/ops/ssh-deploy.sh ghcr.io/<소유자 소문자>",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-user-rc ssh-ed25519 AAAA... gyeongjung-deploy
```
- 레지스트리는 이 줄의 인자로 고정돼 호출자가 바꿀 수 없다. ghcr는 소문자 이름만 받는다(`KTH1007` → `kth1007`).
- 이 키로는 `deploy <태그>`와 `preflight [<태그>]`만 실행된다. 다른 명령, 셸, 포트 포워딩, pty는 거부된다.

### 5. GitHub 설정 (사용자가 직접)
저장소 Settings → Secrets and variables → Actions에 비밀 4개를 등록한다. 등록한 뒤 개인 키 파일은 지운다.

| 이름 | 값 |
|---|---|
| `DEPLOY_HOST` | 서버 호스트 이름이나 IP |
| `DEPLOY_USER` | `gyeongjung-deploy` |
| `DEPLOY_SSH_KEY` | 개인 키 파일 내용 전체 |
| `DEPLOY_KNOWN_HOSTS` | 서버 호스트 키 한 줄(`<호스트> ssh-ed25519 AAAA...`) |

호스트 키는 믿을 수 있는 곳에서 받아 **지문을 서버와 대조**한다. 서버에서 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`로 지문을 보고, 내 컴퓨터에서 `ssh-keyscan -t ed25519 <호스트>`로 받은 키의 지문(`ssh-keygen -lf -`)과 같은지 본다. 워크플로는 이 값으로 호스트 키를 고정하고 처음 보는 호스트나 다른 키는 접속하지 않는다.

ghcr 패키지(`gyeongjung-server`, `gyeongjung-backup`)는 **공개**로 둔다. 서버에 토큰을 저장하지 않으려는 것이고, 코드가 이미 공개이며 이미지에는 비밀이 들어 있지 않다. 첫 푸시 직후에는 비공개로 생기므로 GitHub의 패키지 설정(Package settings → Change visibility)에서 둘 다 공개로 바꾼다.

### 6. 준비가 끝났는지 확인
믿을 수 있는 컴퓨터에서 배포 키로 직접 접속해 본다.
```
ssh -i ./gyeongjung-deploy gyeongjung-deploy@<호스트> preflight     # 사전 점검이 돈다
ssh -i ./gyeongjung-deploy gyeongjung-deploy@<호스트> ls            # 거부(종료 코드 126)
ssh -i ./gyeongjung-deploy gyeongjung-deploy@<호스트>               # 셸도 거부(126)
```
`preflight`의 FAIL 항목을 모두 고친다.

## 배포하기
1. GitHub → Actions → **deploy** → Run workflow. Branch는 `main`이어야 한다(다른 브랜치는 두 잡이 건너뛰어진다).
2. `tag`는 비워 둔다. 비우면 그 커밋을 ARM 러너에서 빌드해 ghcr에 올리고 같은 태그로 배포한다.
3. 서버에서 사전 점검 → 배포가 차례로 돈다. 실행 로그에 각 단계가 나오고, 서버의 `~gyeongjung-deploy/.gyeongjung/deploy.log`에 호출 기록이 남는다.

배포 한 번의 순서(`ops/deploy.sh`):
1. compose 설정을 읽는다(api 서비스가 있는지 본다).
2. 새 이미지 두 개를 받는다. 실패하면 백업도 하지 않고 멈춘다.
3. **배포 직전 백업**. 실패하면 배포하지 않는다(첫 배포는 DB가 아직 없어 건너뛴다).
4. worker 등 나머지를 교체한다. worker는 하나만 돌아야 해서 내렸다가 올리고, DB 마이그레이션(Flyway)은 여기서 먼저 실행된다.
5. worker가 healthy가 될 때까지 기다린다(최대 3분).
6. api가 있으면 한 개씩 롤링으로 교체한다.
7. 스모크로 확인한다(Prometheus가 새 worker를 수집하기까지 몇 초 걸려서 최대 6번 재시도).
8. 성공하면 오래된 이미지를 정리한다. 실패하면 이전 이미지로 같은 순서를 다시 실행해 롤백한다.

결과는 종료 코드로 알려주고 워크플로 로그에 뜻이 적힌다.

| 코드 | 뜻 | 서버 상태 |
|---|---|---|
| 0 | 성공 | 새 버전 |
| 12 | 사전 점검이 막아서 배포를 시작하지 않음 | 바뀌지 않음 |
| 10 | 배포 전 단계에서 멈춤(이미지 받기, 배포 직전 백업, compose 설정) | 바뀌지 않음 |
| 11 | 다른 배포가 진행 중 | 영향 없음 |
| 20 | 새 버전이 확인을 통과하지 못해 **이전 버전으로 되돌림** | 이전 버전 |
| 30 | 롤백도 실패했거나 되돌릴 이전 버전이 없음(첫 배포 실패) | 조사용으로 그대로 둠, 수동 조치 필요 |
| 126 | 서버가 명령을 거부(강제 명령 설정 확인) | 영향 없음 |
| 255 | SSH 연결 실패(호스트 키 불일치 포함) | 영향 없음 |

각 코드의 대응은 `docs/runbook.md`의 "배포 실패 대응"에 있다.

## 롤백하기
| 상황 | 방법 |
|---|---|
| 배포 중 확인이 실패했다 | **자동**으로 이전 이미지로 돌아간다(종료 코드 20). 할 일은 원인 조사뿐이다 |
| 배포는 성공했는데 나중에 문제를 발견했다 | deploy 워크플로를 `tag`에 **이전 커밋 해시**를 넣어 실행한다. 빌드를 건너뛰고 그 이미지로 같은 배포를 한다 |
| 사전 점검이 막는데 롤백이 급하다 | 위와 같이 하고 `skip_preflight`를 켠다 |
| GitHub를 쓸 수 없다 | 서버에서 관리 계정으로 `cd /opt/gyeongjung && IMAGE_REGISTRY=ghcr.io/<소유자> ./ops/deploy.sh <이전 태그>`. 이 경로는 락을 거치지 않으므로 다른 배포가 돌고 있지 않은지 `deploy.log`로 먼저 본다 |
| DB 스키마가 이전 버전과 호환되지 않는다 | 이미지만 되돌려서는 안 된다. `docs/runbook.md`의 복구 절차로 배포 직전 백업을 복원하고 이전 이미지로 시작한다 |

이전 태그는 서버의 `docker image ls --filter label=project=gyeongjung`에 남아 있는 것(현재와 직전)이거나 GitHub의 이전 deploy 실행 기록에서 찾는다.

## 롤링 배포와 호환성
api가 생기면(계획 2) 복제본 2개를 한 개씩 교체한다. 새 복제본을 먼저 추가하고 모두 healthy가 되면 nginx가 새 주소를 찾을 시간을 둔 뒤 옛 복제본 하나를 내리는 일을 반복한다. 롤백은 같은 스크립트를 이전 설정으로 다시 실행하는 것이다.
- 옛 버전과 새 버전이 함께 도는 구간이 있어서 **하위 호환을 지키며 점진적으로** 바꾼다. DB는 expand-contract(`docs/db-migration.md`)이고 `MigrationCompatibilityTest`가 CI에서 파괴적 마이그레이션을 막는다. API는 필드 추가만 하고 제거나 의미 변경은 두 번에 나눈다(이쪽은 자동 검사가 없고 리뷰로 지킨다).
- 헬스체크는 "떴다"만 보증한다. 옛 버전과 섞여야만 나는 오류는 자동으로 못 잡아서 스모크와 알림을 보고 사람이 롤백을 정한다.
- 교체 순간 요청 몇 건이 1~2초 느려질 수 있고(죽은 복제본의 주소로 먼저 연결을 시도), 쓰기 요청(POST) 하나가 실패할 수 있다. 근거와 측정은 트레이드오프 3.36.
- 블루-그린은 인프라 비용이 두 배라서, 카나리는 복잡성이 커서 쓰지 않는다.

## 이미지와 디스크
- 배포가 성공하면 우리 이미지의 오래된 태그를 정리한다. 저장소마다 최신 2개(현재와 롤백용 직전)와 컨테이너가 쓰는 이미지는 남긴다. 롤백에 실패한 이미지는 남긴다(조사용).
- 배포마다 백업이 2~3개 늘어난다(배포 직전 백업, 다시 만든 backup 컨테이너의 시작 백업). 서버 안 보관은 14일이다.
- 디스크가 부족하면 `docs/runbook.md`의 `disk-low`를 따른다.

## 서버 밖 백업 (30일)
호스트가 사라지면 서버 안 백업도 함께 사라지므로 백업마다 서버 밖으로 복사한다(`OFFSITE_URL`).
- 오라클 오브젝트 스토리지에 **새 비공개 버킷**을 만들고, 그 버킷에만 올릴 수 있는 **업로드 전용 키**를 쓴다. 다른 서비스가 쓰는 기존 버킷은 쓰지 않는다.
- 버킷의 수명 주기 규칙으로 **30일이 지난 객체를 자동 삭제**한다(서버 안 14일보다 길게).
- 만들기 전에 콘솔에서 무료 한도(저장 용량, 월 요청 수)를 직접 확인하고 무료 범위 안에서만 쓴다.
- 서버 `.env`에 `OFFSITE_URL`과 `RCLONE_CONFIG_OFFSITE_*`를 채운다(`.env.example`의 주석 참고). 이 값이 비어 있으면 서버 안 백업만 한다. **서울 API 실수집은 서버 밖 백업이 켜진 뒤에** 켠다.
- 월 점검에서 30일보다 오래된 파일이 없는지와 익명 접근이 거부되는지 본다.

## 키 교체와 해지
- 교체: 새 키 쌍을 만들고, 서버의 `authorized_keys`에 새 줄을 추가하고, GitHub 비밀 `DEPLOY_SSH_KEY`를 바꾸고, 배포가 되는지 확인한 뒤 옛 줄을 지운다.
- 의심되면 서버의 `authorized_keys`에서 그 줄을 지우는 것이 가장 빠르다. 이 키는 배포와 점검 말고는 할 수 있는 일이 없지만, 지워서 손해 볼 것이 없다.
- 호스트 키가 바뀌었다는 경고(`REMOTE HOST IDENTIFICATION HAS CHANGED`, 종료 코드 255)가 뜨면 서버가 다시 설치됐거나 중간에 누가 끼어든 것이다. 이유를 확인하기 전에는 `DEPLOY_KNOWN_HOSTS`를 바꾸지 않는다.

## 첫 배포 점검표
첫 배포(PR을 main에 합친 뒤)에서 확인하고 결과를 #14 이슈에 적는다.
1. `preflight`의 모든 항목이 OK다.
2. 배포 5분 안에 worker `/actuator/health`가 UP이고 Prometheus 타깃이 up이다.
3. 엄격한 스모크가 통과한다. 자동 배포의 스모크는 기본 항목만 보므로 서버에서 한 번 직접 돌린다: `EXPECTED_ALERT_RULES=7 EXPECTED_PROFILE=prod ./ops/smoke.sh`
4. `backup` 서비스가 첫 백업을 만들었고 `restore-drill.sh`로 복원이 통과한다.
5. `OFFSITE_URL`을 채우면 백업이 서버 밖에 복사되고, 버킷의 30일 삭제 규칙과 비공개 설정이 맞다.
6. **일부러 실패시켜 확인한다**: 스모크가 실패하면 이전 이미지로 롤백된다, 배포 직전 백업이 실패하면 배포가 시작되지 않는다.
7. 배포 전후로 같은 서버의 다른 서비스의 컨테이너 상태와 열린 포트가 그대로이고 접속이 정상이다(배포 전 기록과 비교). 우리 컨테이너가 그쪽 포트를 쓰지 않는다.
8. 배포를 여러 번 한 뒤에도 우리 이미지 수가 늘지 않고(직전 이미지만 유지) 다른 서비스의 이미지는 그대로다.
9. 배포 키로 허용된 두 명령 외에는 실제로 거부된다.
10. `timedatectl`이 `System clock synchronized: yes`이고 서버 `.env` 권한이 600이다.
11. 첫 배포 뒤 외부 업타임 핑을 등록해서 호스트가 내려간 것을 감지한다.

## 하지 않는 것
- 서버에서 이미지 빌드, 서버 재부팅, Docker 데몬 재시작, 서버 전체 정리 명령, 호스트 전체 하드닝(SSH 설정, 방화벽 변경). 같은 서버의 다른 서비스를 멈추거나 바꿀 수 있어서다.
- 자동 배포(#39 이전). 배포 사용자에게 sudo나 셸 권한을 주는 것.
- 로컬 `.env`나 비밀을 저장소와 이슈에 올리는 것.
