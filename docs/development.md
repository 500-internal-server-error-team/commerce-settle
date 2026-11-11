# 로컬 실행과 검증

Java 21, Docker·Compose v2, Python 3, Git이 필요합니다. Gradle은 Wrapper가 내려받습니다. Apple Silicon과 Linux에서 같은 Compose 구성을 사용합니다.

16GB Mac에서 전체 관측 도구와 k6까지 실행할 때는 Docker 메모리 8GiB를 출발점으로 두고 Mac의 메모리 압박·swap과 컨테이너 OOM 여부를 함께 확인합니다. 호스트 RAM과 Docker VM 할당량은 다릅니다. 이 자원 배정이 1천 TPS를 보장하지는 않습니다.

## 처음 실행

```bash
git clone https://github.com/500-internal-server-error-team/commerce-settle.git
cd commerce-settle
git switch develop
python3 scripts/init_env.py
./gradlew --continue clean build
python3 scripts/sync_redis_run_id.py
docker compose up -d --build --wait --wait-timeout 300
python3 -B scripts/prepare_financial_kafka.py
docker compose -f compose.yml -f compose.financial-flow.yml up -d --wait --wait-timeout 300 commerce payment-worker settlement finance-mock
python3 scripts/smoke.py
```

`java -version`이 21인지 먼저 확인합니다. 다른 버전이 기본값인 Mac에서는 설치된 Java 21 경로를 사용합니다.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

`init_env.py`는 `.env`의 빈 비밀번호만 생성하고 기존 값은 보존합니다. 새 변수가 필요하면 다시 실행하며 파일을 공유·커밋하지 않습니다. `ADDRESS_ENCRYPTION_KEY`를 바꾸면 기존 주소를 열 수 없습니다. 내부 자격은 [설정·교체 절차](#내부-호출-자격)를 따릅니다.

`sync_redis_run_id.py`는 Redis를 먼저 띄워 현재 run_id를 승인하고, 바뀌면 인증 세대도 교체해 이전 세션을 무효로 합니다. Redis 재시작·재생성 뒤에는 Compose 기동 전에 실행합니다. [세션 복구](#redis-세션-검증과-복구)를 함께 확인합니다. 포트 충돌은 `.env`에서 조정합니다. Dockerfile은 빌드한 `app.jar`를 사용하므로 최초 실행·Java 변경 뒤에는 Gradle 빌드가 필요합니다.

줄바꿈은 `.gitattributes`로 LF(`*.bat`만 CRLF)에 고정합니다. 이 파일이 추가되기 전에 Windows의 `core.autocrlf=true`로 받은 작업 트리에는 CRLF 파일이 남아 PostgreSQL 초기화 스크립트가 `set: pipefail: invalid option`으로 실패할 수 있습니다. 진행 중인 변경을 커밋하거나 stash한 뒤 한 번 다시 체크아웃합니다. 이 명령은 커밋하지 않은 변경을 지웁니다.

```bash
git rm -r --cached -q .
git reset --hard
```

초기화 스크립트는 PostgreSQL 이미지에 복사되고, 실패한 볼륨은 다시 초기화되지 않습니다. [초기화 명령](#종료재실행초기화)으로 볼륨을 삭제하고 이미지를 다시 빌드한 뒤 시작합니다.

```bash
docker compose build postgres
python3 scripts/sync_redis_run_id.py
docker compose up -d --wait --wait-timeout 300
```

기존 환경의 `COMPOSE_PROJECT_NAME`도 보존하므로, 기존 컨테이너·볼륨을 계속 사용하려면 그 값을 유지합니다. 신규 환경의 기본 프로젝트명은 `commerce-settle`입니다. `prepare_financial_kafka.py`는 같은 Compose 환경에서 [금융 그룹의 최초 소비 위치](#금융-관측과-접수-복구)를 준비합니다. 이어서 `compose.financial-flow.yml`로 주문 이벤트 발행·수신과 결제·정산 처리를 켭니다. 정상 관측이 60초 연속 유지된 뒤 신규 DB 주문을 받고, 접수된 주문은 Finance Mock을 통한 결제까지 진행합니다. 이후 앱을 재생성할 때도 같은 두 Compose 파일을 사용합니다. 기본 `compose.yml`만으로는 후속 금융 처리가 꺼져 있어 첫 주문의 미발행 이벤트가 60초 이상 밀리면 다시 접수가 닫힙니다. `smoke.py`는 인프라 검사이며 주문·결제 검증은 [금융 왕복 시험](#주문결제정산환불-왕복)으로 확인합니다.

기존 Docker의 `buildx/activity/...: permission denied` 오류가 발생하면 전역 파일의 소유권을 바꾸는 대신 작업용 설정 경로를 지정할 수 있습니다. 현재 셸에서만 적용되며 사용이 끝나면 `unset BUILDX_CONFIG`로 해제합니다.

```bash
mkdir -p work/buildx
export BUILDX_CONFIG="$PWD/work/buildx"
```

## EC2 SSH 접속

`.env.example`의 앱·데이터 SSH 접속 항목은 비워 둡니다. 실제 호스트는 Git에서 제외된 `.env`의 `APP_SSH_HOST`·`DATA_SSH_HOST`에만 입력합니다. 사용자명도 각 EC2의 AMI·접속 안내에서 확인해 `APP_SSH_USER`·`DATA_SSH_USER`에 입력합니다. 실제 AWS 계정·서버와 키의 대응은 별도로 확인해야 합니다. 이 설정이 ECS 이름이나 배포 리전을 정하지는 않습니다.

개인키는 로컬 `.ssh/commerce-key.pem`에만 두며 `.ssh`는 디렉터리 `0700`, 키는 `0400`으로 유지합니다. Git과 Docker 빌드 컨텍스트에서 이 디렉터리를 제외합니다. 키 내용은 `.env`·문서·이슈·PR에 넣지 않습니다. 키 파일이 없으면 사용자가 지정한 원본을 확인하며 새 키를 생성하거나 다른 키로 대체하지 않습니다.

저장소 루트에서 다음 명령을 사용합니다. `app`을 `data`로 바꾸면 데이터·관측 서버를 선택합니다. `.env`에는 공백이 있는 cron 값도 있으므로 셸의 `source`로 실행하지 않고 필요한 설정만 읽습니다. SSH 사용자명·키가 준비되지 않으면 접속 전에 중단합니다.

```bash
python3 -c '
from pathlib import Path
import stat
import subprocess
import sys

target = {"app": "APP", "data": "DATA"}[sys.argv[1]]
settings = dict(line.split("=", 1) for line in Path(".env").read_text().splitlines()
                if "=" in line and not line.startswith("#"))
host, user = settings[target + "_SSH_HOST"], settings[target + "_SSH_USER"]
key = Path(settings["SSH_KEY_PATH"])
if not host or not user or not key.is_file() or stat.S_IMODE(key.stat().st_mode) != 0o400:
    raise SystemExit("SSH 호스트·사용자명과 개인키 파일·0400 권한을 확인하세요.")
subprocess.run(["ssh", "-o", "IdentitiesOnly=yes", "-i", str(key), "--", f"{user}@{host}"], check=True)
' app
```

최초 접속의 호스트 키 지문은 신뢰할 수 있는 서버 접속 안내와 대조합니다. 키를 교체·이동할 때도 Git·Docker 제외와 권한을 먼저 확인하고 `SSH_KEY_PATH`를 갱신합니다. 접속을 마치면 `exit`로 SSH 세션만 종료하며 로컬 키와 서버는 보존합니다. 이 절은 접속 설정이며 실제 SSH 성공이나 AWS 배포 완료를 뜻하지 않습니다.

## ECS EC2 데이터 서비스

PostgreSQL·Redis·Kafka는 `commerce-cluster`의 데이터 EC2 한 대에 각각 ECS Service로 배치합니다. [생성 도구](../scripts/render_ecs_data.py)는 기존 `compose.yml`의 이미지·명령·ACL·초기화·상태 검사를 재사용하며 AWS를 변경하지 않습니다. 일반 Compose 기동과 AWS Task 기동을 같은 호스트에서 병행하지 않습니다.

서울 환경은 앱 EBS 30GiB·데이터 EBS 100GiB의 gp3를 사용합니다. 앱 보안 그룹은 TCP 80/443을 공개하고 데이터의 TCP 5432/6379/9092는 앱 보안 그룹만 허용합니다. 기존 SSH 경로는 별도로 유지합니다. 설정 파일을 바꾸는 것만으로 EBS·파일시스템·보안 그룹이 변경되지는 않습니다.

### 준비

적용자는 AWS CLI·jq·Docker Compose v2와 검증한 저장소 revision을 준비합니다. 먼저 `aws sts get-caller-identity`의 계정과 `AWS_REGION=ap-northeast-2`를 확인합니다. 아래 변수의 컨테이너 인스턴스 ARN이 실제 데이터 EC2를 가리키는지 조회하고, 해당 인스턴스에만 `workload=data` 속성을 지정합니다. 앱 호스트에는 지정하지 않습니다.

```bash
aws ecs describe-container-instances --cluster "$ECS_CLUSTER" \
  --container-instances "$DATA_CONTAINER_INSTANCE_ARN" \
  --query 'containerInstances[*].{ec2:ec2InstanceId,connected:agentConnected,attributes:attributes}'
aws ecs put-attributes --cluster "$ECS_CLUSTER" --attributes \
  "name=workload,value=data,targetType=container-instance,targetId=$DATA_CONTAINER_INSTANCE_ARN"
```

`DATA_PRIVATE_IPV4`는 데이터 EC2의 사설 IPv4입니다. 자동 공인 IPv4는 중지·시작 시 바뀔 수 있으므로 SSH 접속 전에 다시 확인합니다. 앱의 `DB_URL`은 `jdbc:postgresql://<사설-IP>:5432/<소유-DB>`, Redis 호스트는 같은 사설 IP, Kafka는 `<사설-IP>:9092`로 지정합니다. Kafka의 광고 주소도 이 IP를 사용하며 `postgres`·`redis`·`kafka`라는 로컬 Compose 이름을 AWS 앱에 넣지 않습니다. Kafka controller 9093은 호스트에 공개하지 않습니다.

비밀번호는 Parameter Store의 **SecureString**에 동일한 환경변수 이름으로 준비합니다. `SSM_PARAMETER_ARN_PREFIX`는 `arn:aws:ssm:<리전>:<계정>:parameter/commerce-settle/data/` 형식이며 마지막 `/`가 필요합니다. PostgreSQL의 `POSTGRES_PASSWORD`와 서비스별 `COMMERCE_DB_PASSWORD`·`PAYMENT_DB_PASSWORD`·`SETTLEMENT_DB_PASSWORD`·`FINANCE_MOCK_DB_PASSWORD`, Redis의 `REDIS_PASSWORD`와 Gateway·Commerce·Payment·Settlement·Finance Mock의 `<서비스>_REDIS_PASSWORD`를 사용합니다. 앱에도 각자 소유한 계정·비밀번호를 연결합니다. 로컬 `.env`의 값을 복사하거나 비밀번호를 명령 이력·Task JSON·Git·PR에 넣지 않습니다.

`ECS_EXECUTION_ROLE_ARN`은 EC2의 `ecsInstanceRole`과 구분되는 **Task 실행 역할**입니다. `ecs-tasks.amazonaws.com` 신뢰와 위 파라미터의 `ssm:GetParameters`, 사용자 관리 KMS 키를 썼다면 그 키의 `kms:Decrypt`를 필요한 ARN에만 허용합니다. [SSM 주입](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-ssm-paramstore.html)은 Task 시작 시 수행되므로 값 변경 후 새 Task가 필요합니다. 기존 DB·Redis 사용자 비밀번호를 바꾸는 작업은 별도이며, 파라미터 변경으로 기존 PostgreSQL 계정 비밀번호가 바뀌지는 않습니다.

세 로그 그룹 `/ecs/commerce-settle/{postgres,redis,kafka}`를 만들고 보존 기간을 7일로 지정합니다. 로그 전송 역할에 해당 그룹의 `logs:CreateLogStream`·`logs:PutLogEvents` 권한이 필요합니다. EC2 에이전트의 `awslogs` 지원과 실행 역할 사용 시 `ECS_ENABLE_AWSLOGS_EXECUTIONROLE_OVERRIDE=true` 설정도 확인합니다. 데이터 호스트에서 이미지 저장소·SSM·KMS·CloudWatch에 도달할 수 있어야 합니다. 필요한 역할·파라미터·로그 권한 없이 서비스를 생성하지 않습니다.

데이터 EC2에서 검증한 revision의 초기 SQL과 스크립트를 배치합니다. `/srv/commerce-settle`이 의도한 영속 EBS 파일시스템에 있는지 `findmnt -T /srv`와 `df -h /srv`로 먼저 확인합니다. **최초 신규 설치에만** 아래를 사용하고, 기존 파일·데이터·Task가 있으면 기존 revision·백업·적용 절차를 확인한 뒤 진행합니다. 볼륨을 포맷하거나 기존 내용을 지우지 않습니다.

```bash
sudo install -d -m 755 /srv/commerce-settle/{postgres,redis,kafka}
sudo install -d -m 700 -o 999 -g 999 /srv/commerce-settle/postgres/data
sudo install -d -m 755 /srv/commerce-settle/postgres/schema
sudo install -m 644 infra/postgres/01-init.sh /srv/commerce-settle/postgres/01-init.sh
sudo install -m 644 infra/postgres/schema/*.sql /srv/commerce-settle/postgres/schema/
sudo install -d -m 700 -o 999 -g 999 /srv/commerce-settle/redis/data
sudo install -d -m 700 -o 1000 -g 1000 /srv/commerce-settle/kafka/data
```

PostgreSQL 18은 `/var/lib/postgresql` 부모 경로를 영속화하고, 초기화·스키마 파일은 읽기 전용으로 연결합니다. 빈 볼륨에서만 기존 [DB 초기화](migrations.md#최초-배포와-기존-데이터-변경)를 실행합니다. 네 서비스의 DB·계정·권한은 분리하며 기존 데이터의 스키마 업그레이드는 이 도구가 수행하지 않습니다. Redis는 AOF와 기존 ACL·128MiB `noeviction` 정책을 유지합니다. Kafka는 단일 broker/controller이며 복제본은 없습니다. 이미지의 실행 UID/GID가 바뀌면 디렉터리 소유권도 재검토합니다.

### 생성·기동·검증

운영자 PC에서 AWS 계정·리전·실행 역할·SSM prefix·데이터 사설 IP를 지정해 서비스별 Task JSON을 생성합니다. JSON에는 비밀번호 원문이 아닌 ARN 참조만 들어갑니다. ECS EC2의 기존 systemd cgroup driver를 유지하도록 Task 전체 CPU·메모리 제한을 넣지 않고 컨테이너 메모리 제한을 사용합니다. PostgreSQL 512MiB·Redis 256MiB·Kafka 1GiB는 기존 개발 설정의 시작값이며 OOM·지연을 측정해 조정합니다.

```bash
export AWS_REGION ECS_CLUSTER ECS_EXECUTION_ROLE_ARN SSM_PARAMETER_ARN_PREFIX DATA_PRIVATE_IPV4
TASK_DIR=$(mktemp -d)
for service in postgres redis kafka; do
  python3 -B scripts/render_ecs_data.py "$service" --region "${AWS_REGION:?}" \
    --execution-role "${ECS_EXECUTION_ROLE_ARN:?}" \
    --parameter-prefix "${SSM_PARAMETER_ARN_PREFIX:?}" \
    --data-private-ip "${DATA_PRIVATE_IPV4:?}" > "$TASK_DIR/$service.json"
  task_arn=$(aws ecs register-task-definition --cli-input-json "file://$TASK_DIR/$service.json" \
    --query taskDefinition.taskDefinitionArn --output text)
  aws ecs create-service --cluster "${ECS_CLUSTER:?}" --service-name "commerce-settle-$service" \
    --task-definition "$task_arn" --desired-count 1 --launch-type EC2 \
    --deployment-configuration maximumPercent=100,minimumHealthyPercent=0
done
aws ecs wait services-stable --cluster "$ECS_CLUSTER" \
  --services commerce-settle-postgres commerce-settle-redis commerce-settle-kafka
rm -r "$TASK_DIR"
unset TASK_DIR
```

이 명령은 최초 서비스 생성용입니다. 중간 실패 시 먼저 `describe-services`·`describe-tasks`와 로그를 조회하며 서비스·DB를 삭제하고 다시 시작하지 않습니다. 임시 JSON 폴더는 확인을 마친 뒤 해당 경로만 정리합니다. 재배포는 직전 Task ARN·설정 revision을 보존하고 `update-service --task-definition <새-ARN> --force-new-deployment`로 수행합니다. 고정 포트와 영속 디렉터리를 공유하므로 구 Task를 먼저 멈추며 전환 중 중단 시간이 있습니다. 세 서비스 모두 desired count 1을 유지합니다.

ECS 안정화 후 앱 호스트에서 세 사설 포트 도달 여부, 서비스별 DB 접속 성공·다른 DB 접속 거절, Redis의 서비스별 ACL, Kafka metadata의 사설 광고 주소와 실제 생산·소비를 확인합니다. 자격 원문을 출력하지 않고 소유 서비스의 권한으로 검사합니다. 앱을 시작하기 전에 기존 [Kafka 전달 기반](#kafka-전달-기반)의 토픽·초기 소비 위치를 준비하고, Redis 재기동 뒤에는 [세션 복구](#redis-세션-검증과-복구)의 `run_id`와 인증 세대도 승인해야 합니다. 로컬 Compose 전용 명령은 AWS 클러스터를 변경하지 않으므로 해당 Task의 실제 접속 경로로 수행합니다.

재기동·Task 재생성 뒤 같은 PostgreSQL 행·Redis AOF·Kafka 메시지가 유지되는지 확인합니다. 중지는 각 서비스의 `update-service --desired-count 0`이며 재개는 1입니다. ECS가 멈춘 것을 확인하고 데이터 디렉터리는 보존합니다. EC2 교체 시 같은 데이터 디스크·초기화 파일·권한을 복구한 뒤 시작합니다. 이 배치는 호스트 장애를 견디는 HA나 자동 백업을 제공하지 않습니다.

1000TPS를 15분 유지하면 요청은 90만 건입니다. 디스크 사용량은 요청당 DB·인덱스·WAL·Kafka 메시지·로그와 보존량에 따라 달라집니다. **시험 전 여유 30% 이상**을 확인하고 200→400→1000TPS 단계마다 실제 증가량·CPU·메모리·I/O·오류·적체를 측정합니다. 100GiB 확장이나 Task 기동 성공을 목표 처리량 달성으로 판단하지 않습니다.

## ECS EC2 앱과 태그 배포

`v0.1.0`처럼 버전 태그를 push하면 CI가 이미지 다섯 종을 ECR에 게시하고 앱 서비스 여섯 개를 갱신합니다. 배포는 현재 `main` 커밋의 태그만 허용하며, 실행끼리는 직렬화하고 진행 중 배포를 새 태그로 취소하지 않습니다. 오래 걸린 이전 CI가 새 릴리스를 덮지 않도록 배포 job 시작 시 `main`을 다시 대조합니다.

최초 준비는 [실행 정의 생성기](../scripts/render_ecs_apps.py), 이후 배포는 [배포 도구](../scripts/deploy_ecs_apps.py)가 담당합니다. 생성기는 Compose의 기본 환경·역할·건강 검사를 재사용하고 비밀값 대신 SSM ARN을 출력합니다. 자동 배포는 기존 Task Definition의 앱 이미지 digest만 바꿔 새 revision으로 등록하며, 환경·자격 참조·포트·사이드카·사용자 태그를 보존합니다. DB·Kafka·Redis·관측 도구·Caddy는 갱신하지 않습니다.

### 최초 앱 준비

앱은 `workload=app`인 기존 EC2에서 `bridge` 모드로 실행합니다. 컨테이너의 업무·관리 포트는 `8080/9091`이며 호스트 포트는 다음과 같습니다.

| 앱·ECS 서비스 접미사 | 업무 hostPort | 관리 hostPort |
| --- | --- | --- |
| gateway | 8080 | 9091 |
| commerce | 8081 | 9092 |
| payment-api | 8082 | 9093 |
| payment-worker | 8083 | 9094 |
| settlement | 8084 | 9095 |
| finance-mock | 8085 | 9096 |

서비스·Task family 이름은 `commerce-settle-<접미사>`입니다. 앱 간 요청은 앱 EC2의 사설 IP와 고정 업무 포트를 사용합니다. Caddy의 `GATEWAY_UPSTREAM`은 같은 사설 IP의 `8080`입니다. Task가 바뀌어도 주소는 유지되지만 EC2의 사설 IP가 바뀌면 모든 관련 Task 환경과 Caddy를 갱신해야 합니다.

앱 보안 그룹의 `8080–8085`는 앱 측 사설 통신에, `9091–9096`은 데이터 보안 그룹의 메트릭 수집에만 허용합니다. 데이터의 `5432/6379/9092/9411`과 Commerce가 조회하는 Prometheus `9090`은 앱 보안 그룹만 허용합니다. 공개 진입은 Caddy의 `80/443`을 유지합니다. 실제 EC2·포트 도달 여부를 확인하며 Compose 이름이나 루프백 주소를 다른 호스트에 사용하지 않습니다.

생성 Task의 메모리는 앱마다 512MiB입니다. 앱 전체·Caddy·ECS 에이전트·운영체제의 CPU·메모리 여유를 확인하고 실측에 따라 조정합니다. Task 전체 CPU·메모리 제한은 넣지 않으며 기존 EC2의 cgroup driver를 바꾸지 않습니다. 로그 그룹 `/ecs/commerce-settle/<접미사>` 여섯 개와 7일 보존, 실행 역할의 ECR pull·SSM·KMS·로그 전송 권한을 준비합니다. 호스트 속성·실행 역할·에이전트 조건은 [데이터 서비스](#ecs-ec2-데이터-서비스)와 [HTTPS 네트워크 준비](#네트워크와-저장소-준비)를 함께 확인합니다.

SSM의 `SecureString`은 `APP_SSM_PARAMETER_ARN_PREFIX=arn:aws:ssm:<리전>:<계정>:parameter/commerce-settle/app/` 아래에 준비합니다. DB·Redis 자격은 데이터 서비스와 같아야 합니다. Gateway·Commerce의 `AUTH_SESSION_EPOCH`·`AUTH_REDIS_RUN_ID`는 같은 승인값을 쓰며 임의 생성하거나 오래된 값을 재사용하지 않습니다. 필요한 ARN 전체는 생성 JSON의 `secrets`에서 확인합니다.

Payment API의 `payment-api/SPRING_APPLICATION_JSON` 파라미터에는 Settlement 발신 자격을 포함한 아래 JSON을 저장합니다. `current`는 Settlement의 `PAYMENT_SETTLEMENT_CREDENTIAL`과 같아야 합니다. 예시의 자리표시자나 실제 비밀값을 Git·명령 이력·PR에 넣지 않습니다.

```json
{"app":{"internal-auth":{"senders":{"[service:settlement]":{"current":"<승인한 자격>"}}}}}
```

생성기는 Compose의 기본 활성화 값을 유지합니다. Kafka 토픽·DB·Redis 인증 세대를 준비한 뒤 outbox·배치·시연 계정 등의 환경을 검토하고 최초 기동을 검증합니다. 기본 시연 계정이 활성인 Gateway·Commerce에는 SSM의 `DEV_FIXTURE_PASSWORD`도 필요합니다. 이전 자격이 기본 빈 값인 `*_PREVIOUS` 항목은 생략하며 자격 회전 때 Task의 `secrets`에 명시적으로 추가합니다. 이후 이미지 배포는 이 설정을 보존합니다.

최초 태그의 같은 Actions 실행에서 `release-image-*` artifact의 JSON 다섯 개를 한 `IMAGES_DIR`에 모읍니다. 앱이 아직 없으면 첫 `deploy` job은 서비스 사전 검사에서 멈추므로, 게시된 이미지를 사용해 아래 준비를 마친 뒤 실패한 배포 job만 재실행합니다. ECR 업로드 성공을 앱 배포 성공으로 보지 않습니다.

```bash
set -euo pipefail
export AWS_REGION ECS_CLUSTER ECS_EXECUTION_ROLE_ARN APP_SSM_PARAMETER_ARN_PREFIX
export APP_PRIVATE_IPV4 DATA_PRIVATE_IPV4 IMAGES_DIR
TASK_DIR=$(mktemp -d)
for service in gateway commerce payment-api payment-worker settlement finance-mock; do
  module=$service
  case $service in payment-*) module=payment ;; esac
  image=$(jq -r '.image' "$IMAGES_DIR/$module.json")
  python3 -B scripts/render_ecs_apps.py "$service" --image "$image" \
    --region "${AWS_REGION:?}" --execution-role "${ECS_EXECUTION_ROLE_ARN:?}" \
    --parameter-prefix "${APP_SSM_PARAMETER_ARN_PREFIX:?}" \
    --app-private-ip "${APP_PRIVATE_IPV4:?}" --data-private-ip "${DATA_PRIVATE_IPV4:?}" \
    > "$TASK_DIR/$service.json"
done
jq -r '.containerDefinitions[].secrets[].valueFrom' "$TASK_DIR"/*.json | sort -u
```

AWS 계정·리전과 모든 SSM 값·로그·네트워크·업무 활성화 설정을 확인한 뒤에만 서비스 생성을 수행합니다. 기존 서비스가 있으면 먼저 현재 실행 정의를 조회하며 삭제·재생성하지 않습니다.

```bash
set -euo pipefail
aws sts get-caller-identity
for service in finance-mock commerce payment-api payment-worker settlement gateway; do
  task_arn=$(aws ecs register-task-definition --cli-input-json "file://$TASK_DIR/$service.json" \
    --query taskDefinition.taskDefinitionArn --output text)
  aws ecs create-service --cluster "${ECS_CLUSTER:?}" \
    --service-name "commerce-settle-$service" --task-definition "$task_arn" \
    --desired-count 1 --launch-type EC2 \
    --deployment-configuration 'maximumPercent=100,minimumHealthyPercent=0,deploymentCircuitBreaker={enable=true,rollback=true}'
  aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "commerce-settle-$service"
done
rm -r "$TASK_DIR"
unset TASK_DIR
```

빈 앱 최초 배포에는 직전 정상 revision이 없습니다. 실패 시 해당 서비스와 로그를 확인하고 같은 준비를 수정해 이어가며, 기존 데이터나 전체 서비스를 지우지 않습니다. Caddy·DNS·인증서는 [HTTPS 절차](#배포용-https)로 연결한 뒤 실제 로그인·주문·결제·정산을 시연 계정으로 확인합니다.

### 태그 배포 설정과 실패 복구

GitHub 저장소 변수는 다음과 같습니다. AWS 연결 정보이며 비밀번호나 장기 Access Key를 등록하지 않습니다.

| 변수 | 값 |
| --- | --- |
| `AWS_ROLE_ARN` | GitHub OIDC가 맡는 배포 역할 |
| `ECR_REPOSITORY` | 앱 이미지 다섯 종을 저장하는 리포지토리 |
| `ECS_CLUSTER` | 최초 준비를 마친 앱 ECS 클러스터 이름 |
| `PUBLIC_BASE_URL` | 공개 앱의 `https://<도메인>` |

OIDC 신뢰는 해당 저장소의 `refs/tags/v*`와 `sts.amazonaws.com` audience에 한정합니다. 배포 역할은 기존 ECR 권한 외에 앱 서비스의 `ecs:DescribeServices`·`ecs:UpdateService`, 앱 실행 정의의 `ecs:DescribeTaskDefinition`·`ecs:ListTagsForResource`·`ecs:RegisterTaskDefinition`·등록 시 `ecs:TagResource`, 해당 실행·Task 역할에 대한 `iam:PassRole`이 필요합니다. 등록 API의 리소스 제약 한계를 확인하고 서비스·실행 정의 family·전달 역할을 이 앱 범위로 제한합니다. 서비스 최초 생성 권한은 CI 역할에 추가하지 않습니다.

배포 도구는 같은 태그·커밋의 이미지 다섯 개, AWS 계정·리전, 앱 서비스 여섯 개의 정상 상태를 먼저 확인합니다. 이전 Task Definition도 `ACTIVE`여야 합니다. 기존 Task가 실행 중이어도 `INACTIVE` 실행 정의로는 되돌릴 수 없습니다. 모든 새 revision을 등록한 뒤 Finance Mock → Commerce → Payment API → Worker → Settlement → Gateway 순서로 교체합니다. 각 서비스의 `services-stable`과 요청한 revision의 완료를 함께 확인하며 자동 롤백된 상태를 성공으로 오인하지 않습니다. 마지막으로 TLS 검증을 켠 공개 HTTPS에서 Commerce·Payment·Settlement의 OpenAPI 응답을 확인합니다. 이는 연결 검사이며 실제 금융 업무 검증을 대신하지 않습니다.

첫 AWS 변경 전에 `ecs-rollback-<태그>-<시도>` artifact로 직전 Task ARN과 릴리스 정보를 7일 보관합니다. 업로드가 성공해야 배포를 시작하며 저장한 파일의 SHA·릴리스와 현재 서비스 revision을 다시 대조합니다. 변경 뒤의 `ecs-deployment-<태그>-<시도>` artifact는 새 ARN·시도·복구 결과를 추가합니다. 두 기록 모두 자격이나 Task 환경 원문을 담지 않습니다.

실패하면 이번 실행이 갱신을 시도한 앱을 역순으로 되돌립니다. 조회 시 다른 revision을 감지한 서비스는 변경하지 않습니다. 다만 ECS의 조회와 갱신은 원자적이지 않아 조회 직후의 수동 변경까지 막지는 못합니다. **CI 배포가 시작되면 완료·복구까지 콘솔·CLI 수동 갱신을 중단합니다.** CI의 동시 실행 제한이 수동 AWS 작업을 잠그지는 않습니다.

`rollback_errors`에 남은 서비스는 현재 revision·로그를 대조해 적용자가 복구합니다. 강제 종료로 결과 artifact가 없더라도 사전에 보관한 `rollback-plan.json`의 `previous`를 사용합니다. 이 값은 시작 상태이므로 실제 변경된 서비스는 현재 AWS 상태로 확인합니다. 결과 기록이 없으면 현재 Task Definition의 이미지와 계획의 digest를 대조해 이번 실행의 변경인지 확인합니다. 수동 복구도 AWS 계정·리전·클러스터와 현재/직전/이번 등록 revision을 대조한 뒤 수행합니다. SSM 값·DB migration의 복구는 별도입니다.

```bash
jq '.status, .previous' work/rollback-plan.json
service=commerce-settle-commerce
task_arn=$(jq -r '.previous.commerce' work/rollback-plan.json)
aws ecs update-service --cluster "$ECS_CLUSTER" --service "$service" --task-definition "$task_arn"
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "$service"
```

서비스별 전환 중 서로 다른 버전이 공존할 수 있으므로 API·이벤트 호환성을 유지합니다. 고정 포트의 구 Task를 먼저 멈추므로 중단 시간이 있으며 무중단 배포를 보장하지 않습니다. 실제 AWS의 역할·SSM·기동·재배포·실패 복구를 확인하기 전에는 자동 배포의 운영 검증 완료로 판단하지 않습니다.

## 배포용 HTTPS

공개 경로는 **DuckDNS → 기존 앱 EC2의 Caddy → Gateway 내부 HTTP 주소**입니다. [Caddyfile](../infra/caddy/Caddyfile)과 [EC2 Task 템플릿](../infra/caddy/task-definition.json)만 배포에 사용하며, 로컬 `compose.yml`의 HTTP 실행에는 추가하지 않습니다. Caddy와 [Let's Encrypt 인증서](https://letsencrypt.org/about/), [DuckDNS 서브도메인](https://www.duckdns.org/about.jsp)은 무료입니다. EC2·공인 IPv4·디스크·트래픽·로그의 AWS 비용은 별도입니다. ALB나 추가 EC2를 만들지 않습니다.

실제 도메인·AWS 환경은 아직 연결하지 않았습니다. 아래는 적용 절차이며 공인 인증서 발급·운영 TLS 검증 완료를 뜻하지 않습니다. 전체 ECS·ECR·CD 구성은 이 절의 범위에 포함하지 않습니다.

### DuckDNS 준비와 IP 변경

1. 사용하던 계정으로 [DuckDNS](https://www.duckdns.org/)에 로그인하고 사용할 수 있는 서브도메인을 직접 등록합니다. 이름을 확보하기 전에는 예시를 확정값으로 사용하지 않습니다.
2. 해당 도메인의 IPv4에 **앱 EC2의 현재 공인 IPv4**를 명시해 갱신합니다. 작업 PC의 IP나 데이터·관측 EC2의 IP를 넣지 않습니다. 자동 감지값이 앱 EC2의 IP인지도 확인합니다.
3. 실제 값을 `PUBLIC_DOMAIN=<확보한-이름>.duckdns.org`와 `APP_PUBLIC_IPV4=<앱-EC2-공인-IPv4>`로 지정한 뒤 아래 결과를 대조합니다. A 레코드가 해당 IPv4와 같아야 합니다. IPv6 경로를 구성하지 않았다면 AAAA가 없어야 하며, 기존 AAAA가 있으면 DuckDNS에서 지우고 다시 확인합니다. 다르면 DNS 캐시 만료·전파 후 재조회하며 인증서 발급을 시작하지 않습니다.

```bash
dig +short "$PUBLIC_DOMAIN" A
dig +short "$PUBLIC_DOMAIN" A @1.1.1.1
dig +short "$PUBLIC_DOMAIN" AAAA
```

EC2 중지·시작이나 교체로 공인 IP가 달라지면 같은 DuckDNS 항목을 새 앱 공인 IPv4로 수동 갱신하고 위 DNS 검사와 아래 HTTPS 검증을 반복합니다. IP만 바뀌고 도메인이 같으면 Caddy의 도메인·인증서를 바꾸지 않습니다. Gateway의 내부 주소도 바뀌었다면 별도로 Task 환경을 갱신합니다. 상주 DDNS 갱신기·cron·예약 작업은 설치하지 않습니다.

API로 수동 갱신해야 할 때는 [공식 HTTPS API](https://www.duckdns.org/spec.jsp)의 명시적 `domains`·`ip` 값을 사용하고 응답 `OK`와 DNS 결과를 모두 확인합니다. `ip`를 생략하면 호출 위치의 IPv4가 감지되므로 작업 PC에서 생략하지 않습니다. 계정 토큰은 Git·이슈·PR·명령 이력·로그에 남기지 않으며 Caddy에도 전달하지 않습니다. 기본 Caddy 이미지의 HTTP/TLS 검증 방식에는 DuckDNS 토큰이나 DNS 플러그인이 필요하지 않습니다.

### 네트워크와 저장소 준비

적용자는 AWS CLI·jq·Docker·curl·dig와 의도한 AWS 계정을 확인하고, 기존 클러스터·앱 EC2 컨테이너 인스턴스·Gateway 서비스/Task·리전·로그 전송 권한이 있는 실행 역할을 지정합니다. 계정은 `aws sts get-caller-identity`로 확인합니다. **아직 Gateway ECS Task가 없다면 먼저 해당 배포를 준비해야 합니다.** Compose 주소를 대신 넣지 않습니다.

```bash
aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$GATEWAY_SERVICE"
aws ecs describe-task-definition --task-definition "$GATEWAY_TASK_DEFINITION" \
  --query 'taskDefinition.{mode:networkMode,containers:containerDefinitions[*].{name:name,ports:portMappings}}'
aws ecs describe-tasks --cluster "$ECS_CLUSTER" --tasks "$GATEWAY_TASK_ARN" \
  --query 'tasks[*].{host:containerInstanceArn,attachments:attachments,containers:containers[*].{name:name,bindings:networkBindings}}'
aws ecs describe-container-instances --cluster "$ECS_CLUSTER" \
  --container-instances "$APP_CONTAINER_INSTANCE_ARN" \
  --query 'containerInstances[*].{ec2:ec2InstanceId,attributes:attributes}'
```

- Caddy는 `bridge` 모드로 실행합니다. Gateway가 `awsvpc`이면 Task ENI의 사설 IPv4와 업무 포트, `bridge`이면 배치된 EC2의 사설 IPv4와 실제 `hostPort`를 `GATEWAY_UPSTREAM=<IP>:<port>`로 지정합니다. `host` 모드도 EC2의 사설 IPv4와 업무 포트를 사용합니다. Gateway 재생성으로 ENI·포트가 바뀌면 주소를 다시 조회하고 Caddy Task를 갱신합니다.
- 앱 EC2에만 `workload=app` ECS 속성을 설정하고 Caddy 한 개를 배치합니다. TCP 80/443을 쓰는 기존 프로세스가 없어야 합니다. Caddy의 128 CPU 단위·128 MiB 제한은 시작값이며 실제 부하의 CPU·메모리·OOM을 확인해 조정합니다.
- 앱 EC2 보안 그룹은 공개 TCP 80/443만 허용합니다. 80은 HTTPS 전환·HTTP 인증서 검증, 443은 HTTPS·TLS 인증서 검증에 사용합니다. IPv6를 제공할 때만 IPv6 DNS·라우팅·보안 그룹도 함께 엽니다. 이 설정은 HTTP/1.1·HTTP/2만 사용하므로 UDP 443은 열지 않습니다.
- Gateway 업무 포트는 Caddy가 있는 앱 호스트의 사설 통신에만 허용합니다. `awsvpc`는 Gateway Task 보안 그룹에서, `bridge`·`host`는 해당 EC2의 보안 그룹에서 제한합니다. 실제 Caddy 컨테이너에서의 도달 여부를 확인합니다. 업무·DB·Kafka·Redis·9091 관리 포트·2019 Caddy 관리 API를 인터넷에 열지 않습니다. Caddy 관리 API는 설정에서도 끕니다. 운영 접속은 기존 승인된 관리 경로를 사용합니다.
- Caddy의 외부 DNS 조회와 HTTPS 발급·갱신 통신을 허용하고 Docker Hub 이미지 pull·CloudWatch Logs 전송 경로를 준비합니다. `/ecs/commerce-settle/caddy` 로그 그룹은 기존 로그 정책대로 7일 보존하며 실행 역할·ECS 에이전트의 `awslogs` 권한을 확인합니다.
- 앱 EC2의 영속 EBS에 `/srv/commerce-settle/caddy/{etc,data,config}`를 준비합니다. `etc`는 읽기 전용 설정, `data`는 인증서·개인키·ACME 계정, `config`는 Caddy 실행 저장소입니다. 소유자만 접근하도록 디렉터리를 보호하고 백업도 비공개로 보관합니다. Task 중지·재생성 때 지우지 않습니다. EC2 교체 시 같은 데이터 디스크를 복구·마운트한 뒤 시작하며, 빈 디렉터리로 자동 대체해 재발급하지 않습니다. Task 템플릿만으로 EBS 생성·마운트·백업이 제공되지는 않습니다.

### 설정과 시작

검증한 저장소 revision의 파일을 앱 EC2에 전달한 뒤 실행합니다. `PUBLIC_DOMAIN`은 위에서 확보·검증한 실제 DuckDNS 도메인 하나이며 스킴·경로를 붙이지 않습니다. `GATEWAY_UPSTREAM`은 위에서 확인한 내부 `IP:port`입니다. 이 값과 `AWS_REGION`, `ECS_EXECUTION_ROLE_ARN`, `ECS_CLUSTER`가 필요합니다. 토큰·서비스 자격은 이 Task에 넣지 않습니다.

```bash
sudo install -d -m 700 /srv/commerce-settle/caddy/{etc,data,config}
sudo install -m 600 infra/caddy/Caddyfile /srv/commerce-settle/caddy/etc/Caddyfile
export PUBLIC_DOMAIN GATEWAY_UPSTREAM AWS_REGION ECS_EXECUTION_ROLE_ARN

jq --arg domain "${PUBLIC_DOMAIN:?}" --arg upstream "${GATEWAY_UPSTREAM:?}" \
  --arg region "${AWS_REGION:?}" --arg role "${ECS_EXECUTION_ROLE_ARN:?}" '
  .executionRoleArn = $role |
  .containerDefinitions[0].environment = [
    {name:"PUBLIC_DOMAIN",value:$domain}, {name:"GATEWAY_UPSTREAM",value:$upstream}] |
  .containerDefinitions[0].logConfiguration.options["awslogs-region"] = $region
' infra/caddy/task-definition.json > /tmp/commerce-settle-caddy-task.json

CADDY_IMAGE=$(jq -r '.containerDefinitions[0].image' infra/caddy/task-definition.json)
sudo docker run --rm --network none -e PUBLIC_DOMAIN="$PUBLIC_DOMAIN" \
  -e GATEWAY_UPSTREAM="$GATEWAY_UPSTREAM" \
  --mount type=bind,src=/srv/commerce-settle/caddy/etc,dst=/etc/caddy,readonly \
  "$CADDY_IMAGE" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

CADDY_TASK_DEFINITION=$(aws ecs register-task-definition \
  --cli-input-json file:///tmp/commerce-settle-caddy-task.json \
  --query taskDefinition.taskDefinitionArn --output text)
aws ecs create-service --cluster "$ECS_CLUSTER" --service-name commerce-settle-caddy \
  --task-definition "$CADDY_TASK_DEFINITION" --desired-count 1 --launch-type EC2 \
  --deployment-configuration maximumPercent=100,minimumHealthyPercent=0
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services commerce-settle-caddy
rm /tmp/commerce-settle-caddy-task.json
```

Task 환경과 같은 값으로 검사합니다. 설정 검사 통과만으로 DNS·Gateway 연결·공인 인증서 발급의 성공을 판단하지 않습니다.

### 검증·재시작·종료

외부에서 HTTP의 `308`과 같은 경로·쿼리의 HTTPS 전환, HTTPS 인증서의 호스트명·유효기간·신뢰 체인과 Swagger `200`을 확인합니다. `curl -k`로 인증서 검증을 생략하지 않습니다. 인증 API는 기존 시험용 로그인으로 확인하고 관리·내부 경로가 공개되지 않는지도 검사합니다.

```bash
curl --fail-with-body -I "http://$PUBLIC_DOMAIN/swagger-ui.html"
curl --fail-with-body -L "https://$PUBLIC_DOMAIN/swagger-ui.html" -o /dev/null
curl -i "https://$PUBLIC_DOMAIN/actuator/health"
curl -i "https://$PUBLIC_DOMAIN/internal/health"
```

Gateway 계약에 따라 뒤 두 경로는 업무·관리 처리에 도달하면 안 됩니다. ECS 안정화만으로 Gateway 장애의 `502`나 인증서 발급 실패를 검출할 수 없으므로 외부 확인과 Caddy의 CloudWatch 로그를 함께 봅니다. 인증서 오류·`502`·Task 중지·OOM이면 DNS·보안 그룹·내부 주소·영속 영역·로그를 확인합니다. 공개 경로에 문제가 있으면 Caddy의 desired count를 0으로 내려 조사합니다.

설정·내부 주소·이미지를 바꾸면 같은 절차로 설정 검사·Task 등록 후 새 Task ARN을 지정합니다. 직전 Task ARN과 설정 revision을 기록하고, 실패하면 해당 Caddyfile과 Task 환경을 되돌려 다시 검증합니다.

```bash
# 설정 변경 반영. 단순 재시작이면 --task-definition을 생략
aws ecs update-service --cluster "$ECS_CLUSTER" --service commerce-settle-caddy \
  --task-definition "$CADDY_TASK_DEFINITION" --force-new-deployment
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services commerce-settle-caddy

# 공개 진입점만 중지. 재개는 --desired-count 1
aws ecs update-service --cluster "$ECS_CLUSTER" --service commerce-settle-caddy --desired-count 0
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services commerce-settle-caddy
```

고정 80/443과 단일 호스트를 쓰므로 구 Task가 멈춘 뒤 새 Task를 띄우며 전환 중 중단 시간이 생깁니다. 재시작·강제 재생성 후 같은 영속 영역의 인증서 유지와 위 HTTPS 검증을 반복합니다. 종료할 때는 Task 중지를 확인하고 인증서를 보존한 채 공개 포트를 닫습니다. EC2를 폐기하면 다른 곳에 재할당될 IP를 계속 가리키지 않도록 DuckDNS의 해당 레코드를 관리 화면에서 해제하고 DNS를 재확인합니다. 공유 EC2·DB·다른 서비스는 이 종료 조작으로 삭제하지 않습니다.

로컬·CI의 `python3 -B scripts/smoke_caddy.py`는 전용 컨테이너·볼륨을 만들고 같은 Caddyfile의 구문, HTTPS 프록시·전달 헤더, HTTP 전환, 상위 서버 장애, 멱등 키가 있는 POST의 연결 중단 시 재전송 없음, 재시작·재생성과 인증서 저장소 유지를 검사한 뒤 삭제합니다. Caddy의 내부 HTTP 연결 재사용을 꺼 전송 계층의 자동 재시도를 막습니다. `localhost`의 내부 CA를 일시적으로 신뢰하는 검증이며 DuckDNS 갱신·공개 ACME·AWS 네트워크 검증을 대신하지 않습니다. 일반 HTTP·업무 경계 회귀는 기존 Compose smoke로 확인합니다.

### 모니터링 HTTPS 접속

팀 공용 환경은 SSH 터널 없이 도구별 HTTPS 주소로 접속합니다. 기존 `PUBLIC_DOMAIN`의 하위 도메인을 앱 EC2의 고정 공인 IP로 해석되게 하고, Caddy가 각 이름의 인증서를 발급·갱신하도록 설정합니다. 아래는 설정 후 사용할 주소이며 로컬 접속 표와 구분합니다.

| 도구 | 주소 | 인증 |
| --- | --- | --- |
| Grafana | `https://grafana.<PUBLIC_DOMAIN>/` | 기존 Grafana 계정, 팀원은 Viewer 권한 |
| Prometheus | `https://prometheus.<PUBLIC_DOMAIN>/` | Caddy Basic 인증 |
| Zipkin | `https://zipkin.<PUBLIC_DOMAIN>/zipkin/` | Caddy Basic 인증 |
| Kafka UI | `https://kafka.<PUBLIC_DOMAIN>/` | Caddy Basic 인증, 읽기 전용 |

DuckDNS를 사용할 때도 각 하위 이름의 DNS 응답을 실제 확인합니다. DNS가 이미 같은 앱 IP를 가리키면 새 레코드를 만들지 않습니다. data EC2의 자동 공인 IP는 사용하지 않고 `DATA_PRIVATE_IP`로 연결하므로 data 중지·시작에 따른 공인 IP 변경의 영향을 받지 않습니다. 인스턴스를 교체해 사설 IP가 바뀌면 Kafka의 advertised listener와 클라이언트·프록시 설정을 함께 갱신합니다.

기본 [Caddyfile](../infra/caddy/Caddyfile)은 `/etc/caddy/monitoring/*.caddy`가 있을 때만 관측 프록시를 불러옵니다. [모니터링 설정](../infra/caddy/monitoring.Caddyfile)을 앱 EC2의 `/srv/commerce-settle/caddy/etc/monitoring/tools.caddy`에 설치합니다. 기존 `/data`·`/config` 인증서 영역은 보존하고 Caddy Task에 `DATA_PRIVATE_IP` 환경 변수를 추가합니다. 기존 `PUBLIC_DOMAIN`·`GATEWAY_UPSTREAM`은 유지합니다.

Prometheus·Zipkin·Kafka UI의 Basic 인증 창에는 각자의 Grafana 계정을 입력합니다. Caddy는 Grafana의 `/api/user`에 인증을 확인한 뒤 성공한 요청만 전달합니다. Grafana의 Basic 인증은 켜고 익명 인증은 끕니다. 팀원에게 Viewer 계정을 부여하며 관리자 비밀번호는 공유하지 않습니다. Caddy에 비밀번호·해시를 저장하거나 새 SSM 파라미터·IAM 권한을 만들지 않습니다. Grafana 장애나 인증 실패 때는 다른 도구의 공개 접근도 차단됩니다.

모니터링 파일을 설치한 환경의 사전 검증은 세 환경 변수를 모두 지정해 수행합니다. 검증은 인증서 발급이나 Grafana 연결 성공을 대신하지 않습니다.

```bash
sudo docker run --rm --network none -e PUBLIC_DOMAIN="${PUBLIC_DOMAIN:?}" \
  -e GATEWAY_UPSTREAM="${GATEWAY_UPSTREAM:?}" -e DATA_PRIVATE_IP="${DATA_PRIVATE_IP:?}" \
  --mount type=bind,src=/srv/commerce-settle/caddy/etc,dst=/etc/caddy,readonly \
  "$CADDY_IMAGE" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

Grafana의 기존 관리자·SSM 비밀번호와 데이터 볼륨은 유지하고 `GF_SERVER_ROOT_URL=https://grafana.<PUBLIC_DOMAIN>/`을 지정합니다. Prometheus는 기존 보존·수집 옵션을 유지한 채 `--web.external-url=https://prometheus.<PUBLIC_DOMAIN>/`을 추가할 수 있습니다. 도구마다 별도 호스트명을 사용하므로 하위 경로 재작성은 필요 없습니다. Caddy 인증 헤더는 관측 도구에 전달하지 않으며, Grafana의 자체 API 인증 헤더는 유지합니다.

data 보안 그룹은 app 보안 그룹에서 오는 TCP 3000·9090·9411·8081만 관측 프록시에 허용합니다. 실제 기존 규칙을 조회한 뒤 부족한 포트만 추가하고 인터넷 출처는 넣지 않습니다. Grafana·Prometheus의 공개 경로와 raw 포트의 직접 접근은 별도로 검사합니다. Alertmanager와 storage metrics는 이 네 도구의 공개 접속 범위에 포함하지 않습니다.

#### Kafka UI의 ECS 실행

[Kafka UI Task 템플릿](../infra/kafka-ui/task-definition.json)은 data 배치·ARM64·`bridge` 모드의 한 Task입니다. 호스트 8081을 컨테이너 8080에 연결하며 별도 데이터 볼륨은 사용하지 않습니다. 초기 자원은 CPU 256 단위·메모리 예약 512MiB·상한 1GiB이며 실제 사용량·OOM을 확인해 조정합니다. 이미지 digest는 Compose와 같습니다.

`ECS_EXECUTION_ROLE_ARN`, `AWS_REGION`, `DATA_PRIVATE_IP`, `ECS_CLUSTER`를 기존 배포 값으로 준비하고, `/ecs/commerce-settle/kafka-ui` 로그 그룹과 7일 보존·로그 전송 권한을 확인합니다.

```bash
jq --arg role "${ECS_EXECUTION_ROLE_ARN:?}" --arg region "${AWS_REGION:?}" \
  --arg bootstrap "${DATA_PRIVATE_IP:?}:9092" '
  .executionRoleArn = $role |
  .containerDefinitions[0].logConfiguration.options["awslogs-region"] = $region |
  (.containerDefinitions[0].environment[] |
    select(.name == "KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS").value) = $bootstrap
' infra/kafka-ui/task-definition.json > /tmp/commerce-settle-kafka-ui-task.json

KAFKA_UI_TASK_DEFINITION=$(aws ecs register-task-definition \
  --cli-input-json file:///tmp/commerce-settle-kafka-ui-task.json \
  --query taskDefinition.taskDefinitionArn --output text)
aws ecs create-service --cluster "$ECS_CLUSTER" --service-name commerce-settle-kafka-ui \
  --task-definition "$KAFKA_UI_TASK_DEFINITION" --desired-count 1 --launch-type EC2 \
  --deployment-configuration maximumPercent=100,minimumHealthyPercent=0
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services commerce-settle-kafka-ui
rm /tmp/commerce-settle-kafka-ui-task.json
```

이미 있는 서비스는 `create-service` 대신 `update-service`로 갱신합니다.

Kafka UI는 `readOnly=true`이며 동적 연결 설정은 끕니다. 토픽·파티션·업무/DLT 메시지와 Consumer 그룹 지연을 조회하고 발행·삭제·설정·offset 변경을 금지합니다. Kafka 인증·ACL이 있는 환경에서는 기존 인증 방식과 전용 읽기 권한을 사용합니다. 인증·authorizer가 없는 `PLAINTEXT` 구성에서는 UI가 변경을 막는 수준이며 브로커 권한 제한은 아닙니다. 엄격한 분리에는 앱을 포함한 인증·ACL 도입이 필요합니다.

#### 적용 확인과 되돌리기

Caddy를 같은 고정 포트의 새 Task로 교체할 때는 `maximumPercent=100,minimumHealthyPercent=0`을 사용합니다. 관리 API가 꺼져 있으므로 Task 교체 중 짧은 진입점 중단이 생깁니다. 설정을 먼저 검증하고, 이전 Task revision과 설정의 정확한 복원 경로를 확인한 뒤 교체합니다.

- 각 HTTPS 인증서의 호스트명·신뢰 체인, 인증 전 차단과 인증 후 화면·API·정적 파일·깊은 링크를 확인합니다. Grafana는 로그인 화면 전환, 나머지 도구는 인증 없는 요청의 `401`을 확인합니다.
- 기존 Swagger·Grafana 대시보드와 실제 AWS 추적·Kafka 토픽·그룹 지연을 조회합니다. Kafka UI의 쓰기 API가 거절되고 기존 금융 그룹의 offset이 바뀌지 않는지 확인합니다.
- 공개 Zipkin의 span 수집 POST는 `405`, 앱의 사설 수집 경로는 정상인지 확인합니다. Prometheus의 관리·수명주기 API는 활성화하지 않습니다.
- Caddy·Kafka UI 재기동 후 같은 주소·인증·설정을 확인합니다. 실패하면 변경한 서비스만 이전 Task revision·설정으로 되돌리고, 데이터·인증서·토픽·그룹은 삭제하지 않습니다.
- 사용한 Task JSON·전송용 파일·임시 검증 자원을 제거합니다. 운영 중인 ECS 서비스·영속 데이터·사용자 SSH 터널은 유지합니다.

`python3 -B scripts/smoke_caddy.py`는 격리한 도구 대역으로 인증·호스트별 라우팅·헤더 제거·Zipkin 수집 차단과 재기동을 확인합니다. 실제 AWS 로그인·Kafka 연결·공인 인증서·보안 그룹 검증은 별도로 수행합니다.

## 개발용 DB fixture

로컬과 팀 공용 배포는 모두 시연 환경입니다. Compose는 Gateway·Commerce의 계정 fixture와 Swagger 계정 선택 로그인을 기본으로 켭니다(`COMMERCE_DEV_FIXTURES_ENABLED=true`). 두 서비스는 같은 `DEV_FIXTURE_PASSWORD`를 사용합니다. 계정 적재를 제외하는 격리 시험에서만 `COMMERCE_DEV_FIXTURES_ENABLED=false`로 끕니다. Compose 밖에서 배포할 때도 두 서비스에 `APP_DEV_FIXTURES_ENABLED=true`와 같은 비밀번호를 명시합니다.

금융 조회용 스냅샷까지 적재하려면 `APP_DEV_FIXTURES_ENABLED=true`를 지정합니다(기본 false). `APP_DEV_FIXTURE_SCENARIOS_ENABLED=true`도 지정하면 Payment 접수·정산 일배치·모의 지급을 실제 서비스 경로로 실행합니다. 기존 Worker/Relay 활성화 설정은 바꾸지 않습니다. 정산 일배치 스케줄러와 지급 Worker는 fixture 조회 상태를 바꾸지 않도록 모두 끕니다. 둘 중 하나라도 켜져 있으면 fixture 기동을 거절합니다. 시연 전용 DB에서 사용합니다.

```bash
python3 scripts/init_env.py
./gradlew bootJar
python3 scripts/sync_redis_run_id.py
APP_DEV_FIXTURES_ENABLED=true APP_DEV_FIXTURE_SCENARIOS_ENABLED=true \
SETTLEMENT_DAILY_BATCH_ENABLED=false SETTLEMENT_PAYOUT_WORKER_ENABLED=false \
docker compose up -d --build --wait --wait-timeout 300
```

계속 사용할 옵션은 `.env`에 저장합니다. 비밀번호는 `init_env.py`가 생성한 `DEV_FIXTURE_PASSWORD`이며 최초 계정에만 적용합니다. 기존 Argon2id·주소 AES-GCM 암호화를 사용합니다. 비밀번호나 주소 암호화 키를 공유·커밋하지 않습니다.

### Commerce 조회 데이터

UUID는 `00000000-0000-4000-8000-` 뒤에 아래 접미사를 12자리로 채웁니다.

| 용도 | 로그인 이메일·ID 접미사 |
| --- | --- |
| 구매자 A/B | `fixture-buyer-a@example.test` / `fixture-buyer-b@example.test`, 1 / 11 |
| 판매자 A/B | `fixture-seller-a@example.test` / `fixture-seller-b@example.test`, 2 / 22 |
| 주문 예제 | 상품 3(23,000원), 구매자 A 주소 101 |

주소 없음·다중 페이지·정지/삭제·품절·판매 중단 등의 전체 입력은 [Commerce fixture](../commerce/src/main/java/com/commercesettle/commerce/infrastructure/persistence/DevFixtureLoader.java)를 기준으로 합니다. [Swagger UI](http://localhost:8080/swagger-ui.html) 상단에서 구매자·판매자 계정을 선택해 로그인합니다. 변경한 포트는 `GATEWAY_PORT`를 사용합니다.

### Payment·Settlement·Finance Mock

금융 스냅샷은 조회용 합성 데이터이며 실제 PG 승인·서비스 간 전달 결과가 아닙니다. 시나리오 옵션은 Payment의 외부·혼합·전액 마일리지·잔액 부족 요청, 고정 Settlement 항목의 일배치, Finance Mock의 성공·거절·응답 유실·조회 장애를 기존 업무 경계로 실행합니다. 일반 대기 작업·정식 토픽·소비 offset은 변경하지 않습니다.

정확한 ID·seed·금액·예상 행 수는 서비스별 [Payment](../payment/src/main/java/com/commercesettle/payment/infrastructure/persistence/DevFixtureLoader.java)·[Settlement](../settlement/src/main/java/com/commercesettle/settlement/infrastructure/persistence/DevFixtureLoader.java)·[Finance Mock fixture](../finance-mock/src/main/java/com/commercesettle/financemock/infrastructure/persistence/DevFixtureLoader.java)가 소유합니다. 실제 인증·Worker의 Clock은 고정하지 않습니다.

정산은 fixture 대상·파라미터로 실행하며 일반 항목이 함께 선점되면 판매자 트랜잭션을 롤백하고 실패시킵니다. 커머스 지급 컨텍스트를 읽을 수 있어야 하며 FAILED·STOPPED는 원인 해결 후 같은 파라미터로 재기동합니다. COMPLETED는 재실행하지 않고, 완료됐어도 필수 fixture 항목이 남으면 기동에 실패합니다. 새 재현은 삭제 승인된 전용 개발 볼륨에서만 [초기화](#종료재실행초기화)합니다.

이미 만든 지급 작업을 Worker로 처리하려면 fixture 옵션을 끄고 다음처럼 실행합니다. 조회용 적재와 실제 금융 처리를 구분합니다.

```bash
APP_DEV_FIXTURES_ENABLED=false SETTLEMENT_DAILY_BATCH_ENABLED=false \
SETTLEMENT_PAYOUT_WORKER_ENABLED=true docker compose up -d --wait settlement
```

Payment의 본인 결제 조회는 [결제 API](api.md#기능-목록)로 확인할 수 있으며 fixture의 저장 상태 대조는 아래 서비스별 DB 조회를 사용합니다. 서비스 간 DB를 조인하거나 loader가 타 서비스 DB를 읽지 않습니다.

```bash
docker compose exec postgres psql -U postgres -d commerce -c \
  "SELECT buyer_id,id,status,total_amount,mileage_amount FROM commerce.orders ORDER BY buyer_id,id;"
docker compose exec postgres psql -U postgres -d payment -c \
  "SELECT status,count(*) FROM payment.payments GROUP BY status; SELECT kind,sum(amount) FROM payment.mileage_entries GROUP BY kind;"
docker compose exec postgres psql -U postgres -d settlement -c \
  "SELECT seller_id,amount,status FROM settlement.payouts; SELECT status,count(*) FROM settlement.settlement_items GROUP BY status;"
docker compose exec postgres psql -U postgres -d finance_mock -c \
  "SELECT account_ref,opening_balance,balance,reserved_amount FROM finance_mock.mock_accounts; SELECT status,count(*) FROM finance_mock.mock_transactions GROUP BY status;"
```

기존 수정·논리삭제·잔액·원장을 보존하며 고정 ID·소유자 충돌은 적재를 롤백합니다. 업무 시나리오는 초기 적재 후 개별 트랜잭션으로 실행하고, 중간 실패 뒤에는 고정 이벤트·업무 키로 남은 처리만 이어갑니다. 옵션을 꺼도 데이터는 남으며 전체 DB 리셋은 제공하지 않습니다.

조회용 주문 사본에는 실제 재고 예약·Outbox가 없습니다. 주문부터 금융 결과까지 시험할 때는 `POST /api/orders`로 새 주문을 생성합니다.

부하 시험용 대량 데이터는 별도로 준비합니다.

## 일반 주문 API

주문 API는 실제 DB의 `POST /api/orders`, `GET /api/orders`, `GET /api/orders/{orderId}`를 제공합니다. 개발 fixture를 켜고 구매자 A로 로그인하면 초기 DB 주문과 새 주문을 조회할 수 있습니다.

요청 필드·필수값은 [OrderCreate 명세](openapi.json), 쿠폰·이벤트 조합과 권한은 [API 사용 흐름](api.md#주문환불-사용-순서)을 따릅니다. 본인 `addressId`와 조회한 상품/이벤트 버전·총액을 보내며 마일리지 확인·예약은 Payment가 맡습니다. 주문 접수는 잔액 확보나 결제 승인을 보장하지 않습니다.

```bash
curl -i http://localhost:8080/api/orders \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: regular-order-create-001' \
  -d '{"productId":"00000000-0000-4000-8000-000000000003","quantity":1,"eventId":null,"buyerCouponId":null,"mileageAmount":0,"addressId":"00000000-0000-4000-8000-000000000101","expectedProductVersion":0,"expectedEventVersion":null,"expectedTotalAmount":23000,"externalMethod":"CREDIT_CARD","methodAccountRef":"fixture-card"}'
```

`BUYER_TOKEN`에 로그인 token을 넣습니다. `201`·`Location`은 결제 대기 주문 접수이며 결제 완료가 아닙니다. 멱등 재요청·주소 사본·예약은 [주문 계약](api.md#주문환불-사용-순서)과 [정책](policies.md#금액과-주문)을 따릅니다.

조회와 권한은 [OpenAPI](openapi.json)를 따릅니다. 배송 시험의 기본값은 `weekdays-v1`·Asia/Seoul·공휴일 없음·seed 67·`order-regular-v1`이며 seed는 `app.orders.simulation-seed`로 바꿉니다.

Outbox Relay는 `app.outbox.enabled=false`가 기본입니다. 전용 시험 인스턴스에서 켜면 저장한 주문 요청을 발행합니다. 결과 반영에는 [접수 결과 수신](#결제-접수-결과-수신)과 [주문 업무 흐름](#주문-업무-흐름)의 플래그도 필요합니다. 결과 처리기가 꺼져 있으면 대기 상태를 유지하며 승인 차단 확인 전에는 기한이 지나도 예약을 해제하지 않습니다. 재고 예약이 누적되는 실험은 전용 DB에서 실행합니다.

## 신규 주문 접수 제한

DB 주문의 신규 접수와 새 결제 회차에는 기본으로 gate를 적용합니다. 기동 시 닫히며 `APP_ORDER_ADMISSION_PROMETHEUS_URL`(기본 `http://prometheus:9090`)에서 필수 관측을 읽고 모든 재개 기준을 60초 연속 충족해야 엽니다. `APP_ORDER_ADMISSION_PAUSED=true`로 설정해 앱을 재기동하면 운영자가 접수를 중단할 수 있습니다. 해제 후에도 재개 검사를 다시 거칩니다. 조회·취소·환불·기존 업무 배출은 계속됩니다.

신규 선착순 이벤트 주문의 [이벤트별 동시 실행 상한](policies.md#선착순-상품-이벤트)은 `APP_SALE_EVENT_ORDER_MAX_CONCURRENT`(기본 3)와 `APP_SALE_EVENT_ORDER_MAX_WAIT`(기본 `PT1S`)로 바꿉니다. 인스턴스별 값이며 앱을 재기동해야 반영됩니다.

전용 단일 실행기가 Commerce DB 관측과 Prometheus instant query를 15초 간격으로 수행합니다. DB 쿼리는 2초, HTTP는 전체 5초·응답 2 MiB 상한이며 요청 트랜잭션에서 원격 조회하지 않습니다. 수집 실패·지표 누락·오래된 원본 시각이면 닫습니다. `commerce_order_admission_open`은 열림 1/닫힘 0이며, Commerce의 두 금융 대기열 값과 원본 수집 시각을 `/actuator/prometheus`로 제공합니다. 상세 metric·필수 대상·임계값은 [접수 정책](policies.md#신규-접수-제한과-재개)을 따릅니다.

### 금융 관측과 접수 복구

Payment API·Worker는 같은 소유 DB를 각각 관측합니다. Settlement도 자기 Inbox·Outbox·지급 Job을 관측하며, 중복 인스턴스를 합산하지 않습니다. 미래 재시도·유효 임대·수동 확인은 자동 실행 지연에서 제외합니다. 금융 UNKNOWN·환불·미지급·수동 확인 경보는 `infra/prometheus/financial.rules.yml`에서 기존 Alertmanager와 [Slack overlay](#dlt-관측과-조치)에 연결합니다. 격리·DLT와 알림 전용 경보는 기존 소유 규칙을 유지합니다.

Kafka는 `commerce.v1 → payment.v1 토픽`, `payment.v1 → commerce.v1·settlement.v1 토픽`, `settlement.v1 → payment.v1·commerce.v1 토픽`의 **모든 파티션**을 수집합니다. 여기서 토픽은 `commerce-settle.` 접두사를 포함합니다. 초기 offset 부재·보존 범위 밖 offset·누락/실패 파티션을 0 lag로 바꾸지 않습니다.

새 환경은 기본 Compose로 토픽을 만든 뒤, 소비자를 켜기 전에 다음 명령으로 세 그룹을 준비합니다. `COMPOSE_PROJECT_NAME`·`COMPOSE_FILE`·`.env`는 기동 때와 같은 대상을 사용합니다.

```bash
python3 -B scripts/prepare_financial_kafka.py
```

모든 계약 파티션을 관측하고, offset이 전혀 없는 비활성 그룹의 구독 토픽이 모두 시작·끝 위치 0인 경우에만 초기화합니다. 정상 offset이 이미 있는 그룹은 활성 여부와 관계없이 그대로 두므로 재실행할 수 있습니다. 부분 offset·기존 메시지·보존 이력·활성 신규 그룹·관측 실패는 자동 초기화를 거절합니다. 최초 준비 중에는 다른 소비자나 관리자가 offset을 동시에 변경하지 않습니다. 토픽과 그룹 준비만 확인하며 다른 필수 관측과 60초 재개 검사는 계속 적용합니다.

기존 데이터나 일부 offset이 있는 환경은 원인과 처리 이력을 확인하고 필요한 그룹·파티션의 시작점을 명시적으로 결정합니다. 아래 명령은 **새 전용 환경의 비활성 Payment 그룹**을 처음부터 읽도록 지정하는 예이며, 기존 그룹에 그대로 실행하면 이미 처리한 이벤트를 다시 읽을 수 있습니다.

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --group payment.v1 --reset-offsets \
  --topic commerce-settle.commerce.v1 --topic commerce-settle.settlement.v1 \
  --to-earliest --execute
```

운영 중인 그룹이나 공유 데이터에서 offset을 임의로 재설정하지 않습니다. 토픽 파티션을 늘리면 새 파티션의 시작점도 명시적으로 준비합니다. 기본 Outbox·Consumer/Worker 활성화 정책은 바꾸지 않으므로 전체 업무 시연에는 각 소유 기능의 실행 설정도 필요합니다. 미발행 금융 작업을 계속 쌓아 도래 지연이 60초에 도달하면 관측이 정상이어도 신규 접수가 닫힙니다.

`python3 scripts/test_admission.py`는 전용 Compose·빈 볼륨에서 실제 금융·Kafka·디스크 관측을 사용해 접수 개방, 수집 중단 시 신규 접수 차단과 기존 업무 유지, 60초 회복 후 재개를 검사합니다. 고정 금융 수치나 gate 우회를 사용하지 않으며 전체 금융 왕복 시험은 아닙니다. 종료 시 전용 자원을 제거하고 정리에 실패하면 시험 설정 위치를 남깁니다. 기본 CI에 포함합니다.

## 결제 접수 결과 수신

Commerce의 `PaymentAccepted` Listener와 처리기는 기본 비활성입니다. 격리한 시험 인스턴스에서 `APP_KAFKA_PAYMENT_ACCEPTED_ENABLED=true`, `APP_PAYMENT_ACCEPTED_PROCESSOR_ENABLED=true`로 각각 활성화합니다. Payment는 기존 주문 요청 Listener·Worker 처리기·Outbox Relay, Commerce는 주문 Outbox Relay를 함께 켜야 전체 왕복이 진행됩니다. 기본 `auto-offset-reset=none`은 유지하며 새 소비 그룹의 시작 offset은 시험 전에 명시적으로 준비합니다. 새 DB에는 갱신한 Commerce 초기 SQL을 적용하고, 기존 DB·볼륨에 전체 SQL을 재실행하거나 데이터를 초기화하지 않습니다.

수신·원문 충돌·ACK와 업무 반영은 [이벤트 계약](api.md#이벤트-연동)을 따릅니다. 처리기는 기본 1초·최대 100건이며 일시 실패는 [공통 재시도](policies.md#공통-복구)를 적용합니다. 이전 회차·상태 충돌과 재시도 소진은 사유가 있는 REVIEW_REQUIRED로 남으며, 이 Inbox 처리기의 운영 HTTP 재개는 지원하지 않습니다.

## 주문 업무 흐름

주문 API는 실제 PostgreSQL을 사용합니다. Swagger UI에서 구매자·판매자로 각각 로그인해 발급받은 토큰으로 호출하거나 Postman/curl을 사용합니다. 공개 요청은 Gateway로 보내고 내부 컨텍스트·운영 종결은 Gateway에 노출하지 않습니다. 판매자 목록에는 주소가 없으며, 본인 이행 대상 주문의 단건 조회에서만 주문 당시 주소 사본을 제공합니다. `deliveryAddress.isDefault=false`는 주소록 항목이 아닌 주문 사본임을 뜻합니다.

호출 순서·본문·권한·완료 의미는 [주문·환불 사용 순서](api.md#주문환불-사용-순서)와 [OpenAPI](openapi.json)를 따릅니다. 변경 요청에는 멱등키와 최신 조회의 `expectedVersion`을 사용합니다. 반품 입고는 판매자가 실제 도착·재판매 가능 검수 뒤 확정하며 금융 결과만으로 자동 생성하지 않습니다.

주문 스케줄러는 `APP_ORDER_WORKFLOW_ENABLED=true`, 결과 처리기는 `APP_ORDER_RESULT_PROCESSOR_ENABLED=true`로 시험 인스턴스에서 활성화합니다. 결과 수신은 기존 `APP_KAFKA_PAYMENT_ACCEPTED_ENABLED=true`를 공유하며, 접수 ID 처리기는 기존 플래그를 별도로 유지합니다. 기본값은 모두 false입니다. 이 설정은 인스턴스 환경에 전달해야 하며 `.env` 추가만으로 기존 Compose 컨테이너에 전달되지 않습니다. 빈 전용 DB에는 갱신한 초기 SQL을 적용하고 기존 볼륨을 초기화하지 않습니다.

Payment 내부 조회와 운영 종결은 `service:payment`·`operator:<식별자>` 자격을 인스턴스의 `app.internal-auth.senders`에 설정해야 합니다. 사용자 토큰이나 Gateway 자격으로 호출할 수 없습니다. 운영 종결은 [소유 증빙과 구매자 확인](api.md#실패-환불의-운영-종결)을 모두 대조하는 `RefundClosureEvidence`를 사용합니다. 기본은 비활성(503)이며 금융 실패만으로 주문을 PAID로 되돌리거나 자동 구매확정을 진행하지 않습니다.

증빙 읽기를 켜기 전에 Payment의 `refunds`·불변 DECLINED·RESTORE 원장과 Settlement의 실제 원예약 해제 스키마/동작을 적용·검증합니다. Payment는 필요한 스키마가 없으면 503, Settlement는 원예약이 없으면 404입니다. 두 읽기 API는 Gateway에 공개하지 않고 `SERVICE:commerce`만 허용합니다.

- Commerce: `APP_REFUND_CLOSURE_ENABLED=true`, `PAYMENT_URL`, `SETTLEMENT_URL`, 각 대상에 발급한 `PAYMENT_COMMERCE_CREDENTIAL`, `SETTLEMENT_COMMERCE_CREDENTIAL`을 설정합니다. 현재 클라이언트는 각 GET 연결/응답을 3초로 제한하고 리다이렉트·자동 재시도를 하지 않습니다. 활성화 때 자격 누락·짧은 값은 기동 실패입니다.
- Payment와 Settlement: 각각 `app.internal-auth.senders[service:commerce].current`에 자기 대상의 동일 자격을 추가합니다. Gateway·서로 다른 대상 자격을 재사용하지 않습니다. 기존 [내부 자격 설정](#내부-호출-자격)과 같이 외부 설정/Compose override로 전달합니다. 기본 Compose에는 이 선택 연동을 활성화하지 않습니다.
- 새 빈 전용 Commerce DB에는 변경한 초기 SQL을 적용합니다. 기존 공유 볼륨을 지우거나 전체 SQL을 재실행하지 않습니다.

운영 종결의 HTTP·DB 시험은 [개발·검증](#개발검증)을 따릅니다. 증빙 조회 대역·계약 fixture를 사용한 시험과 실제 원환불·예약 해제·주문 복구 왕복을 구분해 기록합니다.

쿠폰·이벤트 예약/복구는 주문 트랜잭션 안에서 처리합니다. 쿠폰 만료 설정은 `app.coupons.expiry-*`이며 실제 발급·사용 조건은 [쿠폰 정책](policies.md#쿠폰)을 따릅니다. Payment·Settlement의 회차 재접수·마일리지 재예약·승인 차단·원환불/예약 해제·주문 복구는 실제 소유 서비스와 함께 검증합니다. 미연결 금융 결과를 성공으로 반환하지 않습니다. 알림은 [모의 거래 알림](#모의-거래-알림), 접수 제한은 [금융 관측과 접수 복구](#금융-관측과-접수-복구)를 따릅니다.

월간 우수고객은 매월 1일 00:10 KST부터 10분마다 `app.coupons.grade-scheduler-enabled`로 실행합니다. 이전 월의 미완료 회차도 먼저 처리하며 원래 기한 뒤에는 발급 없이 EXPIRED로 종결합니다. 같은 회차의 중복 실행을 차단하고, 마지막 갱신이 `app.coupons.grade-stale-after`(기본 10분)보다 오래된 잔류 실행만 FAILED로 정리한 뒤 같은 회차로 재시작합니다. [운영 조회·교정](api.md#월간-혜택-운영-조회와-오류-교정)과 [혜택 정책](policies.md#발급-대상주기예산)을 따릅니다.

```bash
./gradlew :commerce:test --tests '*OrderWorkflow*' --tests '*OrderIntegrationTest' --tests '*PaymentAcceptedIntegrationTest'
```

이 명령은 전용 PostgreSQL·Kafka와 명시적 계약 결과 대역으로 구매자/판매자 HTTP 흐름, 중복·역순·기한 경합·잠금 상한·롤백을 확인합니다. 실제 PG·마일리지·정산 금융 정합성 완료를 뜻하지 않습니다.

## 주문·결제·정산·환불 왕복

세 서비스의 금융 소비자·발행기는 기본으로 꺼져 있습니다. 실제 왕복은 덮어쓰기 파일 `compose.financial-flow.yml`로 켭니다. 이 파일은 Commerce의 Outbox·결제 결과 수신·처리기, Payment Worker의 주문 요청 수신·처리기·승인·환불 Worker·Settlement 응답 수신·Outbox, Settlement의 이벤트 수신·Inbox 처리기·Outbox를 켭니다. PG 승인·외부 환불은 Finance Mock HTTP로 선택하고 정상 시나리오·지급 재원·개발 fixture를 준비합니다. 알림 소비는 켜지 않습니다. 기본 `compose.yml`과 `.env`는 바꾸지 않습니다.

빈 볼륨에서는 기본 설정으로 먼저 기동해 토픽을 만든 뒤 [금융 그룹 offset](#금융-관측과-접수-복구)을 준비하고 앱을 재생성합니다.

```bash
docker compose up -d --build --wait
python3 -B scripts/prepare_financial_kafka.py
docker compose -f compose.yml -f compose.financial-flow.yml up -d --wait commerce payment-worker settlement finance-mock
```

`python3 -B scripts/smoke_financial_flow.py`는 처음 실행과 같은 순서로 발행기·소비자가 꺼진 새 Compose 프로젝트·빈 시험용 tmpfs 데이터 볼륨에서 시작하고 소유 리소스만 지웁니다. PostgreSQL·Kafka의 실제 데이터와 저장공간 관측은 이 전용 파일시스템을 사용하므로 다른 Docker 프로젝트의 디스크 점유와 격리됩니다. 첫 주문·결제 뒤 접수 상태를 관측하고 최소 65초가 지난 후 두 번째 주문·결제도 확인합니다. 이 두 주문은 용량 초과 응답을 재시도해 성공으로 바꾸지 않습니다. 앱 재기동 동안에는 같은 볼륨을 유지합니다. 순서는 다음과 같습니다.

1. 시험 구매자·판매자·상품·재고와 Payment 마일리지 지갑을 넣고, HTTP 승인 → Kafka 정산 항목 → 취소 → 외부 HTTP 환불 → 마일리지 복원 → 원장 상쇄 → 주문 복구를 실행합니다.
2. 카드·혼합·전액 마일리지·간편결제를 대조합니다. 1,000원 중 마일리지 300원이면 외부 환불은 700원이며, 전액 마일리지는 외부 HTTP 호출이 0회여야 합니다. 원장 상쇄는 최초 5 leg 각각에 반대 부호로 한 줄씩 대응하고 복원·금융 완료 이벤트도 한 번이어야 합니다.
3. 실제 입금 작업이 현금을 받은 뒤 환불 차감을 확인합니다. 입금 전 환불은 예정 입금 0원, 간편결제 지갑은 원잔액으로 복원되며 최종 현금 원장·재원 잔액도 대조합니다.
4. Settlement 중단 중 예약 대기, 환불 확정 거절 뒤 실제 예약 해제, 응답 유실·조회 불가 중 UNKNOWN·예약 유지, 외부 성공 직후 Payment 강제 종료·재기동과 만료 임대 인계, 늦은 응답의 토큰 차단을 확인합니다.
5. 호출 전 중단(REQUESTED지만 외부 거래 없음)과 PROCESSING은 격리 DB fixture로 주입합니다. 실제 HTTP 조회에서 각각 거래 부재 뒤 같은 키 재전송·처리 중 예약 유지를 확인하며, Finance Mock이 자연스럽게 PROCESSING을 반환한 시험으로 표시하지 않습니다.
6. 발송한 주문(배송완료 전 `SHIPPED`)의 반품을 판매자 API로 승인합니다. 환불 완료만으로는 재고를 바꾸지 않고, 재판매 가능 입고가 가용 재고를 한 번 복원하는지 확인합니다. Settlement 중단 중 재판매 불가로 입고한 반품은 판매 수량만 줄이고 가용 재고에 더하지 않으며, 재기동 뒤 같은 환불이 완료되는지 확인합니다. 마지막에는 Inbox·Outbox와 지갑·재고·외부 거래 중복을 대조합니다. 반품 주문이 Settlement 정산 원장·지급 대상에서 한 번만 처리되는지는 대조하지 않습니다.

CI는 `financial-flow`와 `reconciliation` 시나리오를 각각 25분 상한으로 실행하며 다른 통합 시나리오는 기존 15분 상한을 유지합니다. 이 시험의 계정·시나리오·시각·임대 변경과 무작위 루프백 포트는 새 시험 프로젝트에만 적용하며 기존 DB·사용자 데모에는 적용하지 않습니다. 지급 배정 뒤 예약 거절(`PAYOUT_RESERVED`)은 구매확정(도착+168시간)과 지급 선점이 먼저 필요하므로 Settlement `RefundPayoutExclusionIntegrationTest`와 Payment `RefundSagaIntegrationTest`로 별도 검증합니다. 실제 금융기관과 카카오 알림은 이 smoke의 검증 범위가 아닙니다.

## 모의 거래 알림

기본은 실제 카카오·전화번호를 사용하지 않는 MOCK_KAKAO입니다. 발송·조회·재처리와 금융 상태의 분리는 [알림 정책](policies.md#거래-알림), 호출 순서는 [알림 API](api.md#거래-알림-사용-순서)를 따릅니다.

`.env`의 `APP_NOTIFICATIONS_WORKER_ENABLED=true`로 설정해 Commerce를 재기동하면 1초 주기로 선행 반영 대기와 발송을 처리합니다. 스케줄 작업은 별도 풀에서 실행하고 발송 작업·외부 호출은 각각 최대 2개입니다. 외부 호출 대기열은 8개, 호출 상한 5초·임대 30초이며 응답 유실/임대 만료는 같은 deliveryId 조회부터 재개합니다. 기본값 false에서는 기록·조회 API만 제공하고 자동 발송하지 않습니다.

Payment 알림을 보내려면 [신규 소비 그룹 offset](#kafka-전달-기반)을 준비한 뒤 `.env`에 `APP_OUTBOX_ENABLED=true`를 설정하고 `docker compose up -d payment-worker`로 재생성합니다. 기본값 false이며 Compose는 Payment Worker에만 전달합니다. 생산·본문·잔액 사본의 계약은 [알림 정책](policies.md#소유권과-복구)을 따릅니다.

Payment·Settlement notice를 받으려면 `APP_NOTIFICATIONS_CONSUMER_ENABLED=true`도 설정합니다. 그룹은 `commerce-notifications.v1`, 구독은 `commerce-settle.payment.v1`·`commerce-settle.settlement.v1`입니다. 기존 금융 그룹 offset과 분리하며 [초기 offset 준비](#kafka-전달-기반)는 두 구독 토픽 각각에 적용합니다. `auto.offset.reset=none`을 유지하고 이미 데이터나 offset이 있는 토픽을 임의 초기화하지 않습니다. 같은 토픽의 다른 금융 이벤트는 알림 그룹에서만 건너뜁니다. 정상 notice는 Inbox·알림 항목을 함께 저장하며 주문/원환불 복구가 앞서지 않았으면 원문을 영속 대기시킨 뒤 ACK합니다. 일반 선행 대기는 실패 횟수를 소진하지 않습니다.

발송·시도·모의 수신 조회와 원키 재처리는 [알림 API](api.md#거래-알림-사용-순서)를 사용합니다. 미확정 결과는 원수신을 확인하고 같은 deliveryId·본문으로만 재개합니다. 모의 확정 거절은 설정 변경·재처리로 성공으로 바뀌지 않습니다.

모의 오류는 seed 기본 67, 지연 0ms·오류 없음이 기본입니다. `APP_NOTIFICATIONS_MOCK_DELAY_MS`는 0..60000, `APP_NOTIFICATIONS_MOCK_TEMPORARY_FAILURE_PERMILLE`·`PERMANENT_FAILURE_PERMILLE`·`RESPONSE_LOSS_PERMILLE`(모두 APP_NOTIFICATIONS_MOCK_ 접두사)는 0..1000입니다. deliveryId·시도 번호·고정 seed로 결과를 재현하며 확정 수신 결과는 최초 값으로 유지합니다. 지연 중 DB 커넥션을 보유하지 않고 임대 token·version이 맞는 결과만 반영합니다. 발송·조회를 합쳐 라운드 10회, 1초부터 최대 60초 재시도이며 소진·확정 거절·본문 충돌은 REVIEW_REQUIRED입니다.

해석 불가·계약 위반 원문은 `notification_inbox_quarantines`에 bytes·키·위치·사유를 커밋한 뒤 ACK합니다. 같은 eventId의 다른 본문도 격리하며 원본 Inbox/항목을 덮어쓰지 않습니다. DB 인계/격리 실패는 알림 Listener만 중단하고 offset을 보존합니다. 저장 결과와 원인을 확인한 뒤 재기동하면 같은 원문을 재개합니다. 격리 원문의 자동 재처리/DLT 발행은 제공하지 않습니다. 생산자 오류를 고쳐 같은 원업무 ID·원결과로 정정 알림을 발행하고 격리 출처와 대조하는 소유 서비스의 복구와 통합 검증이 필요합니다. 원금융 거래 재실행·원문 삭제·임의 offset 이동으로 복구하지 않습니다.

신규 테이블은 초기 Commerce SQL에 있으므로 새 빈 개발 DB에서 검증합니다. 기존 볼륨의 앱 재시작으로 테이블이 생기지 않으며 [DB 적용 정책](migrations.md)을 따릅니다. 실제 카카오 시연은 [별도 설정](#본인-카카오톡-시연)으로 켭니다. 전체 금융 생산부·본인 카카오 실제 수신이나 처리량 목표의 완료를 자동 테스트 통과로 주장하지 않습니다.

## 주문 주소·알림 보존 실행

주문 주소와 주문 연결 알림, Settlement 배치의 정산·지급 알림을 보존 정책에 따라 처리합니다. 금융 상태·원장·작업은 삭제하지 않습니다.

변경한 Commerce·Settlement 초기 스키마와 내부 호출 자격을 새 빈 개발 DB에 적용한 뒤 `COMMERCE_RETENTION_ENABLED=true`를 사용합니다. `python3 scripts/init_env.py`가 `SETTLEMENT_COMMERCE_CREDENTIAL`을 만들고 Compose가 두 서비스에 연결합니다. 기존 자격 교체 중에는 Settlement에 `_PREVIOUS`를 함께 설정합니다. 원천 URL은 `SETTLEMENT_URL`(기본 `http://settlement:8080`)입니다. 기존 DB·볼륨은 초기화하지 않으며 [DB 변경 경계](migrations.md)를 따릅니다. 기본값은 비활성입니다. 활성화하면 전용 단일 실행 스레드에서 기동 시 한 번과 매일 03:00 KST에 실행합니다. `COMMERCE_RETENTION_CRON`은 KST cron, `COMMERCE_RETENTION_BATCH_SIZE`는 1~1000(기본 100), `COMMERCE_RETENTION_MAX_BATCHES`는 1~100(기본 10)으로 종류별 실행량을 제한합니다.

수동 재실행과 주문·배치 보존 보류의 설정/해제는 인증된 [내부 운영 경계](../commerce/src/main/java/com/commercesettle/commerce/application/service/RetentionService.java)이며 공개 HTTP API는 없습니다. 대상별 트랜잭션에서 재검사하고 잠긴 주문·발송은 건너뜁니다. 주소 잠금 1초·SQL 5초·대상 트랜잭션 10초 상한을 적용합니다. 보류·실패·한도 초과 대상은 이력과 재조회 시각을 보존해 다음 실행에서 이어갑니다.

정산·지급 알림은 묶음 단위로 Settlement의 불변 종결 사실을 확인하고 HTTP 대기 중 DB 연결을 점유하지 않습니다. 조회·검증하지 못한 배치는 보존하되 검증된 다른 배치는 계속 처리합니다. 종결·보류·삭제 기준은 [보존 정책](policies.md#보존과-삭제), 원천 장애 중 재처리와 기존 멱등 결과의 처리는 [API 계약](api.md#오류와-완료-기준)을 따릅니다.

`retention_checks`·`retention_history`에서 결과와 재조회 시각을 확인합니다. 주소는 되돌릴 수 없는 마스킹이며 삭제된 알림 본문도 복구하지 않습니다. `HELD`는 보호 조건 유지, `FAILED`는 해당 대상 롤백입니다. 로그에는 대상 ID·종류·예외 종류만 남기며 본문·주소·보류 사유는 출력하지 않습니다. 정책과 제외 범위는 [보존과 삭제](policies.md#보존과-삭제)를 따릅니다.

`./gradlew :commerce:test --tests '*RetentionIntegrationTest'`로 보존 기한·보류·경합·원자 삭제·재실행을 검사합니다. `scripts/smoke_retention.py`는 CI 전용 `commerce-settle-ci` 프로젝트에서 Commerce→Settlement 조회·삭제·미종결 보호·원천 배치 보존을 검사합니다. 일반 로컬·운영 프로젝트에서 실행하거나 사용자 DB를 초기화하지 않습니다.

## 시간대

앱 로그·API·이벤트 출력과 업무 날짜는 [공통 시간 규약](../CONTRIBUTING.md#요청과-응답)의 KST를 따릅니다. API·이벤트 DTO는 `OffsetDateTime`으로 출력하며 내부 `Instant`·DB `timestamptz`·epoch 값은 유지합니다. 업무 API·이벤트 구현 시 이 변환 경계를 적용해야 합니다.

Compose와 Gradle `bootRun`은 JVM 시간대를 `Asia/Seoul`로 지정합니다. IDE나 jar 직접 실행에는 `-Duser.timezone=Asia/Seoul`을 VM 옵션으로 넣습니다. PostgreSQL의 `TimeZone`·`log_timezone`과 Grafana 대시보드도 명시적으로 KST를 사용합니다. 기존 DB 볼륨에서도 Compose 재생성으로 설정을 적용하며 데이터 이관은 하지 않습니다.

콘솔 로그에는 `+09:00`을 포함합니다. 외부 도구의 원시 epoch·수집 시각은 유지하고 조회 화면에서 KST로 해석합니다. [현재 로그 구성](architecture.md#로그-수집)을 따릅니다.

## 접속

| 대상 | 기본 주소 | 실행 조건 |
| --- | --- | --- |
| Swagger UI | <http://localhost:8080/swagger-ui.html> | 기본 구성 |
| Commerce 명세 | <http://localhost:8080/v3/api-docs/commerce> | 기본 구성 |
| Payment 명세 | <http://localhost:8080/v3/api-docs/payment> | 기본 구성 |
| Settlement 명세 | <http://localhost:8080/v3/api-docs/settlement> | 기본 구성 |
| Zipkin | <http://localhost:9411> | 기본 구성, 메모리 저장소 |
| Prometheus | <http://localhost:9090> | 기본 구성 |
| Alertmanager | <http://localhost:9093> | `observability`, 기본 외부 발송 없음 |
| Grafana | <http://localhost:3000> | `observability`, 사용자 `admin`, 비밀번호 `.env`의 `GRAFANA_ADMIN_PASSWORD` |
| Kafka-UI | <http://localhost:8081> | `observability` |

```bash
docker compose --profile observability up -d --wait --wait-timeout 300
```

Grafana의 `commerce-settle / Runtime overview` 대시보드와 Prometheus 데이터 소스는 자동 등록됩니다. 수집 주기 때문에 첫 데이터가 표시되기까지 약 15초가 필요합니다. 이 구성은 로컬 전용이며 DB·Kafka·Redis·내부 앱·관리 포트는 호스트에 게시하지 않습니다. Zipkin은 기본 10% 샘플링이며 `.env`의 `TRACING_SAMPLING_PROBABILITY`로 바꿉니다. 모든 요청을 수집하면 부하·메모리 사용량이 달라집니다.

#### 금융 처리 시간과 비동기 추적

`commerce-settle / Financial processing` 대시보드(기존 Payment Worker UID 유지)는 Payment 단계별 평균·p95·p99, 세 서비스의 비동기 처리·대기·재시도·SQL과 대기열을 보여줍니다. 새 Commerce·Payment API/Worker·Settlement·Finance Mock 이미지와 대시보드 설정을 함께 반영합니다. 기존 DB에는 먼저 [추적 컬럼 업그레이드](migrations.md#기존-db의-추적-컬럼-추가)를 적용합니다. 작업이 실행되기 전이나 두 번의 수집이 쌓이기 전에는 데이터가 없을 수 있습니다.

| 지표 | 측정 경계 |
| --- | --- |
| `payment_queue_wait_seconds` (`queue=inbox`, `approval`) | 현재 대기 회차의 등록·변경 시각부터 첫 선점 완료까지. 재시도·임대 회수는 중복 집계하지 않습니다. |
| `payment_processing_seconds` (`stage=inbox.*`, `approval.*`, `refund.*`) | 선점·업무 반영·실패 기록 또는 임대·준비·임대 확인·외부 호출·결과 반영 단계. 트랜잭션 프록시가 반환될 때 커밋·롤백까지 측정합니다. 빈 폴링도 포함하므로 완료 TPS가 아닙니다. |
| `async_work_seconds` (`operation`) | 저장한 문맥으로 복원한 Inbox·Outbox·승인·환불·지급의 한 번의 실행. 빈 폴링은 제외하며 재시도는 별도 실행입니다. Settlement Inbox는 트랜잭션 완료까지, Outbox는 전송과 결과 콜백을 각각 측정합니다. |
| `async_stage_seconds` (`stage`) | 지급의 준비·외부 호출·결과 반영. 기존 Payment 단계 지표와 별개입니다. |
| `async_queue_wait_seconds` (`queue`, `kind`) | `initial`: 현재 대기 회차부터 선점까지, `retry`: 실패 기록부터 재선점까지, `lease-recovery`: 이전 임대 만료부터 회수까지. 기존 실행 시간은 재시도 대기에 더하지 않습니다. |
| `async_queue_backoff_seconds` | 실패 기록부터 다음 실행 가능 시각까지의 계획된 재시도 대기입니다. |
| `async_queue_ready_delay_seconds` | 실행 가능 시각부터 실제 선점까지의 지연. 슬롯·폴링·적체 원인을 백오프와 구분합니다. |
| `jdbc_query_seconds` (`stage`) | Commerce·Payment·Settlement·Finance Mock의 JDBC 쿼리 실행. 현재 단계가 없으면 `unscoped`입니다. 결과 매핑·Java 처리·flush·커밋을 포함하는 전체 트랜잭션 시간과 구분합니다. |

Timer의 `_count`·`_sum`·`_bucket`은 Trace 샘플링과 별도로 수집합니다. `process`·하위 단계·SQL은 시간이 겹치므로 합산하지 않습니다. 음수 대기 표본은 제외하며 서버 시각 동기화가 필요합니다. 기존 행의 추적 부모는 NULL이고, 과거 재시도 종류를 판단할 수 없으면 `kind=unknown`으로 실행 가능 시각 이후만 측정합니다. 수동 재처리는 원래 trace 부모를 유지하지만 새 대기 회차의 변경 시각부터 셉니다.

W3C `traceparent`는 Outbox·Inbox·금융 Job의 첫 저장 때 업무 원문과 분리해 저장하고, 실행기·콜백·재시작 뒤에 복원합니다. 같은 eventId 재전달은 원문 bytes·해시·최초 부모를 바꾸지 않습니다. Zipkin에서 `commerce.outbox.send` → Kafka → `payment.inbox.process` → `payment.approval.process`/`payment.refund.process` → Outbox·Commerce/Settlement Inbox를 같은 traceId로 따라갑니다. 지급은 배치·지급 Job을 만든 문맥에서 시작하므로 별도 trace일 수 있습니다. `.wait` span은 실행 span과 저장 부모를 공유하는 형제이며 메서드 실행 시간에 더하지 않습니다. `event.id`·`inbox.id`·`job.id`·금융 ID는 Trace에만 기록하며 metric label로 사용하지 않습니다.

JDBC 바인딩 값·쿼리 로그·행별 ResultSet 작업은 비활성화합니다. 예외는 오류 종류만 남기는 `ObservationFailure`로 정제하며 원래 예외·롤백·재시도 동작은 유지합니다. 샘플링 기본값은 10%이고 Zipkin은 메모리에 최대 10,000개 span을 보관합니다. 재시작·보관 한도·비샘플 요청의 과거 trace를 복구하거나 모든 알림·운영 작업을 계측하지는 않습니다. `python3 -B scripts/smoke_financial_flow.py`는 선택한 요청에 sampled W3C 부모를 보내 실제 승인·환불·재시작 경로의 traceId·부모 연결을 금융 정합성과 함께 검사합니다. 지급 실행기 연결은 `PayoutTracingTest`, 저장 어댑터의 중복·재시작·기존 DB 업그레이드는 `DurableTraceIntegrationTest`로 별도 확인합니다.

#### 병목 개선 전후 비교

대시보드의 `이전 구간 간격`(1시간·6시간·1일·7일)은 현재와 이전 구간의 같은 집계식을 겹쳐 표시합니다. 과거 표본이 없는 선은 빈 값이며 0이 아닙니다. 개선 전에도 새 계측을 적용한 기준선을 먼저 수집해야 합니다. 배포·워밍업·측정·배출을 섞지 않은 동일 길이의 측정 구간을 선택하고, 이전 구간 간격을 두 실행의 시작 시각 차이에 맞춥니다. 지원 간격에 맞지 않으면 각 실행의 절대 시간 구간을 따로 선택해 동일 질의 결과를 비교합니다.

기존 [부하 시험](../tests/load/README.md)의 실행 원장·summary와 함께 다음 조건을 기록합니다: commit SHA·실제 이미지 digest, CPU·메모리·DB 풀·Worker 동시성, 주입률·지속 시간·결제수단 비율·데이터 규모·PG 시나리오, 워밍업·측정·배출 시작/끝, scrape 주기·Trace 샘플링률·부하 발생기 위치. 자원·외부 지연·입력 분포가 다르면 그 차이를 먼저 명시합니다. 이전 지표가 없던 배포 전 구간과 신규 지표를 직접 비교해 개선율을 만들지 않습니다.

완료 TPS·실패/거절·p95/p99와 미완료 수·배출 시간을 함께 비교하고 금융 원장 대조가 통과했는지 확인합니다. 단계 실행 횟수는 결제 완료 건수가 아니며 낮은 지연만으로 병목 개선을 판정하지 않습니다. 느려진 구간을 Zipkin의 선택 trace로 확인하고, SQL·외부 호출·첫 대기·백오프·도래 후 지연 중 무엇이 바뀌었는지 대조합니다. 현재 변경은 비교를 위한 계측이며 성능 개선 효과를 입증한 결과는 아닙니다.

### Swagger 로그인

로컬·팀 공용 배포 모두 상단 `개발용 계정`에서 구매자·판매자 A/B를 고르고 `로그인`을 누릅니다. 별도 가입이나 이메일·비밀번호·토큰 입력 없이 기존 로그인 API로 인증합니다. Gateway·Commerce에 같은 fixture 설정과 최초 적재에 사용한 `DEV_FIXTURE_PASSWORD`가 필요하며, 비밀번호는 브라우저에 전달하지 않습니다. 이미 생성된 계정의 비밀번호는 fixture 재기동으로 바뀌지 않습니다.

성공하면 상단에 계정 종류·표시 이름·만료 시각이 나타나며 보호 요청에 토큰이 자동 연결됩니다. 가입·로그인 API 자체의 입력과 응답을 시험할 때만 Accounts·Sessions 명세를 직접 실행합니다.

로그인은 현재 탭의 메모리에서만 유지합니다. Commerce·Payment·Settlement 명세를 전환해도 활성 계정은 유지되며, 다른 계정으로 로그인하면 성공한 시점에 교체합니다. 새로고침·탭 종료 뒤에는 다시 로그인합니다. 로그인 실패는 기존 유효 세션을 지우지 않습니다.

Swagger에는 별도의 토큰 입력 버튼·인증창을 표시하지 않습니다. 로그인한 계정의 인증을 보호 요청에 자동 연결하며, 서버는 요청마다 기존 세션을 검증합니다. 로그아웃은 `DELETE /api/sessions/current`를 실행합니다.

`DELETE /api/sessions/current`의 로그아웃 성공, 서버가 발급한 고정 30분 만료, 현재 세션으로 보낸 보호 요청의 401에서는 연결을 해제합니다. 403 권한 부족과 503 확인 불가는 만료로 처리하지 않으며, 실패한 업무 요청을 자동 재전송하지 않습니다. 서버는 보호 요청마다 기존 Redis 세션을 계속 검증합니다.

자동 인증은 같은 출처의 명세된 사용자 보호 API에만 적용합니다. 공개 요청·문서 조회·외부 주소·내부 및 운영자 요청에는 사용자 토큰을 붙이지 않습니다. 브라우저의 로그인 상태를 k6나 다른 클라이언트와 공유하지 않으므로, 별도 클라이언트에는 기존처럼 발급받은 토큰을 전달합니다. 주문의 상품·배송지·가격·멱등키와 금융 처리 준비는 로그인과 별도로 충족해야 합니다.

## k6 실행 확인

서비스를 기동한 뒤 기본 스크립트를 실행합니다. 기본값은 초당 10회·10초·20 VU 사전 할당이며 Commerce OpenAPI 조회만 수행합니다. VU는 동시 실행 여유이며 초당 요청 수와 다릅니다.

```bash
docker compose --profile tools run --rm --no-deps k6
```

다른 장비에서 k6를 실행할 때는 Docker Compose 대신 해당 장비의 k6로 스크립트를 실행하고 접근 가능한 테스트 서버 주소를 지정합니다.

```bash
k6 run -e BASE_URL=http://TEST_SERVER:8080 -e RATE=100 -e DURATION_SECONDS=30 tests/load/bootstrap.js
```

Compose는 루프백에만 게시하므로 다른 장비에서 바로 접근할 수 없습니다. 원격 시험은 인증·네트워크 접근을 별도로 설계한 테스트 환경에서 진행합니다.

`RATE`, `DURATION_SECONDS`, `PRE_ALLOCATED_VUS`, `MAX_VUS`는 양의 정수이며 VU 최대값은 사전 할당값 이상이어야 합니다. Compose에서는 `-e RATE=100 -e DURATION_SECONDS=30`처럼 `run` 옵션으로 덮어씁니다. checks·HTTP 실패·`dropped_iterations` 임계값을 확인합니다. 명세 조회 처리량은 결제 승인 완료 TPS가 아닙니다. 결제 확정 처리량·PG 장애·Worker 재시작 뒤 대조는 [결제 부하·장애·복구 시험](../tests/load/README.md)에서 별도 Compose 프로젝트로 실행합니다. 이 도구는 주문 API부터의 업무 흐름·15분 지속 처리량 목표를 검증하지 않습니다.

### PG 승인·조회·전액 환불

Payment 승인 Worker는 `PAYMENT_ROLE=worker`에서 `app.payment-approval.enabled=true`로 켭니다(기본 false). `app.payment-approval.gateway`는 즉시 승인하는 개발용 `stub`이 기본이며 `PAYMENT_APPROVAL_GATEWAY=http`로 Finance Mock을 선택합니다. HTTP 대상·자격·PG·재원·시나리오는 [Payment 설정](../payment/src/main/resources/application.yml)의 `payment-approval`을 확인합니다. 대역에 없는 시나리오는 503·미확정으로 남기므로 아래 fixture 버전 등 실제 준비한 버전을 지정합니다.

`APP_DEV_FIXTURES_ENABLED=true`는 기존 지급 fixture와 함께 네 대역에 아래 불변 버전을 준비합니다. 기존 계정 잔액·시나리오는 재기동 때 덮어쓰지 않습니다. 이 fixture를 재사용하거나 아래 준비 API로 새 시험 데이터를 추가하며 실제 PG나 사용자 계정을 사용하지 않습니다.

| 금융 버전 | 승인 | 환불 | 조회 |
| --- | --- | --- | --- |
| `fixture-pg-normal-v1` | 성공 | 성공 | 정상 |
| `fixture-pg-reject-v1` | 확정 거절 | 성공 | 정상 |
| `fixture-pg-response-loss-v1` | 성공 후 504 | 성공 | 정상 |
| `fixture-pg-refund-reject-v1` | 성공 | 확정 거절 | 정상 |
| `fixture-pg-refund-loss-v1` | 성공 | 성공 후 504 | 정상 |
| `fixture-pg-lookup-fail-v1` | 성공 | 성공 | 503 |

이 fixture의 지연은 모두 0ms이고 지정한 장애 확률은 100%, 나머지는 0%입니다. 설계 문서의 분포형 시험 입력과 구분합니다. seed는 20261102, 입금 지연은 PG_A/EASY_PAY 5·PG_B 6·PG_C 7영업일입니다. 정상 조회 버전을 따로 선택해 원금융 버전을 유지하면서 조회 장애를 해소합니다.

PAYOUT 재원은 기존 `fixture-payout-funding`(기초잔액 1,000,000원), EASY_PAY 지갑은 `fixture-easy-wallet`(1,000,000원)을 사용합니다. 카드의 `accountRef`는 합성 참조를 사용하며 잔액을 차감하지 않습니다. 외부 수단은 CREDIT_CARD·DEBIT_CARD·EASY_PAY만 허용합니다. 승인·조회·환불은 `FINANCE_MOCK_PAYMENT_CREDENTIAL`로 검증한 `service:payment`만 허용하며 기존 지급은 `service:settlement`만 받습니다. `init_env.py`로 두 현재 자격을 준비하고 교체 중에만 각 `_PREVIOUS`를 설정합니다. 루프백·컨테이너 내부의 로컬 시험에 한정하며 Gateway에는 공개하지 않습니다.

기존 환경의 스키마가 현재 금융 Mock DDL과 일치하는지 먼저 확인합니다. 새 빈 전용 개발 DB가 준비된 환경에서 `./gradlew :finance-mock:bootJar` 후 다음으로 로컬 시험 인스턴스를 엽니다. 다른 작업의 포트·컨테이너와 겹치지 않는지 확인합니다.

```bash
docker compose build finance-mock
docker compose run --rm --no-deps -p 127.0.0.1:18083:8080 -e APP_DEV_FIXTURES_ENABLED=true finance-mock
```

다른 터미널에서 `.env`의 `FINANCE_MOCK_PAYMENT_CREDENTIAL`을 환경 변수로 읽은 뒤 승인하고, 응답의 `id`를 전액 환불 경로에 넣습니다. 자격은 출력하거나 버전 관리에 넣지 않습니다. 아래 업무 키는 실행마다 새 UUID로 바꾸며 재요청 시험에서는 같은 값을 유지합니다.

```bash
curl -i http://localhost:18083/mock/providers/EASY_PAY/payments \
  -H "Authorization: Bearer $FINANCE_MOCK_PAYMENT_CREDENTIAL" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: pg-approve-demo-001' \
  -d '{"businessKey":"00000000-0000-4000-8000-000000000121","method":"EASY_PAY","accountRef":"fixture-easy-wallet","fundingAccountRef":"fixture-payout-funding","amount":30000,"scenarioVersion":"fixture-pg-normal-v1"}'

curl 'http://localhost:18083/mock/providers/EASY_PAY/transactions/00000000-0000-4000-8000-000000000121?operation=APPROVE&scenarioVersion=fixture-pg-normal-v1' \
  -H "Authorization: Bearer $FINANCE_MOCK_PAYMENT_CREDENTIAL"

curl -i http://localhost:18083/mock/providers/EASY_PAY/payments/APPROVAL_ID/refunds \
  -H "Authorization: Bearer $FINANCE_MOCK_PAYMENT_CREDENTIAL" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: pg-refund-demo-001' \
  -d '{"businessKey":"00000000-0000-4000-8000-000000000122","amount":30000}'
```

`202` 본문의 SUCCEEDED/DECLINED를 확인합니다. 반환한 `Location`으로 원결과를 조회할 수 있습니다. 같은 provider·작업·businessKey·본문은 헤더를 바꿔도 같은 결과이고 다른 본문은 409입니다. 504·503·거래 부재 404를 금융 실패나 예약 해제 근거로 바꾸지 않습니다.

입금 작업은 매분 최대 100개 도래 원승인을 처리합니다. `PG_CALENDAR_VERSION`(기본 `weekdays-v1`)과 `PG_CALENDAR_HOLIDAYS`(쉼표로 구분한 ISO 날짜, 기본 공휴일 없음)를 승인 당시 사본·예정일로 저장합니다. `PG_RECEIPTS_ENABLED=false`는 수동 시험용으로 자동 입금을 끄며, 기본값은 true입니다. 입금 전 확정 환불은 예정 입금을 0으로 종결하고, 입금 후에는 같은 재원에서 원외부 결제액만 차감합니다.

`./gradlew :finance-mock:build`는 금융 거래·준비 API를 실제 PostgreSQL·HTTP 경계에서 검사합니다. Payment·Settlement의 실제 왕복이나 실금융 정합성 검증은 아닙니다.

### 시험 계정·장애 시나리오 준비

인스턴스의 `app.internal-auth.senders[operator:<식별자>].current`에 별도의 URL-safe 43자 이상 운영 자격을 설정합니다. 기존 발신자별 설정에 추가하며 값은 Git 밖 비밀 설정으로 주입합니다. 기본 Compose는 운영자 자격을 생성하거나 외부 관리 포트를 열지 않습니다. Payment·Settlement 자격, 사용자 토큰 또는 Gateway 자격을 재사용하지 않습니다. 내부 또는 위 루프백 시험 인스턴스에서만 호출합니다.

```bash
curl -i http://localhost:18083/mock/accounts \
  -H "Authorization: Bearer $MOCK_OPERATOR_CREDENTIAL" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: prepare-wallet-demo-001' \
  -d '{"providerId":"EASY_PAY","accountRef":"demo-wallet","purpose":"WALLET","balance":1000000}'

curl -i http://localhost:18083/mock/scenarios \
  -H "Authorization: Bearer $MOCK_OPERATOR_CREDENTIAL" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: prepare-scenario-demo-001' \
  -d '{"providerId":"EASY_PAY","scenarioVersion":"demo-normal-v1","minDelayMs":0,"maxDelayMs":0,"rejectionRate":0,"responseLossRate":0,"lookupFailureRate":0,"seed":20261103,"settlementDelayBusinessDays":5,"refundMinDelayMs":0,"refundMaxDelayMs":0,"refundRejectionRate":0,"refundResponseLossRate":0,"lookupMinDelayMs":0,"lookupMaxDelayMs":0}'

curl http://localhost:18083/mock/accounts/ACCOUNT_ID \
  -H "Authorization: Bearer $MOCK_OPERATOR_CREDENTIAL"
```

계정은 `201`의 `Location`으로 조회합니다. 같은 키·본문의 POST는 잔액 변경 뒤에도 원생성 응답이며 현재 잔액은 GET에서 확인합니다. 기존 계정 참조·시나리오 버전의 재등록은 409이므로 새 시험에는 새 참조·버전을 사용합니다. PAYOUT 시나리오는 입금 지연·환불 4필드를 명시적으로 null로 보냅니다. 시나리오의 `Location`은 식별 URI이며 GET은 제공하지 않습니다.

준비 API는 기존 DDL을 사용합니다. 공유 DB·볼륨을 초기화하지 않습니다.

## 정산 대조 실행과 재개

새 빈 DB에 [현재 DDL](migrations.md)을 적용하고 `python3 scripts/init_env.py`로 추가된 `PAYMENT_SETTLEMENT_CREDENTIAL`을 준비합니다. 기존 DB·사용자 데모를 초기화하지 않습니다. Settlement의 `SETTLEMENT_RECONCILIATION_ENABLED=true`와 Payment API의 같은 수신 자격, Payment API→Finance Mock의 기존 `FINANCE_MOCK_PAYMENT_CREDENTIAL`이 필요합니다. 기본 Compose는 이 자격을 전달하며 일일 대조 활성화는 별도 opt-in입니다. 운영자는 인스턴스의 `app.internal-auth.senders`에 `operator:<식별자>`를 설정합니다. Gateway·구매자·판매자에게 공개하지 않습니다.

`POST /internal/settlement-reconciliations`에 `{"cutoffAt":"2026-11-03T10:00:00+09:00"}`를 보내면 202와 회차 ID를 받습니다. 같은 마감 재요청은 같은 회차를 반환합니다. 202는 대조 완료가 아닙니다. `GET /internal/settlement-reconciliations/{runId}`, `/results?page=0&size=100`, `/history`로 고정 대상·진행·원인·최근 실행 이력을 확인합니다. 요청 마감은 안전 여유 10분 이전이어야 합니다.

부하·장애 복구 후의 전체 대조는 기존 [부하 검사](../tests/load/README.md)의 검증 범위를 유지하면서 다음 도구로 서비스별 읽기 계약을 연결합니다. URL과 환경 변수는 검증 전용 스택의 값을 사용합니다. `SETTLEMENT_OPERATOR_CREDENTIAL`, `PAYMENT_SETTLEMENT_CREDENTIAL`, `FINANCE_MOCK_SETTLEMENT_CREDENTIAL`은 기존 비밀 설정에서 환경으로 전달하고 명령 인자·기록에 넣지 않습니다.

```bash
python3 scripts/reconcile.py --cutoff-at 2026-11-03T10:00:00+09:00 \
  --settlement-url http://localhost:18084 --payment-url http://localhost:18082 \
  --finance-url http://localhost:18085
```

중단·대기 회차는 `--cutoff-at` 대신 `--run-id`로 같은 회차를 확인합니다. 원천 문제를 해결한 뒤 같은 마감 POST로 재개를 앞당길 수 있습니다. 진행 중·격리·조회 불가가 있거나 검사 차이가 있으면 도구는 성공 종료하지 않습니다. 회차·페이지·원문 증빙을 보존한 채 원인을 수정하며 새 금융 거래·금액 보정·예약 해제·offset 강제 이동으로 통과시키지 않습니다. 최종 완료 회차는 재계산하지 않으며, 이후 거래·변경의 검사는 새 마감으로 실행합니다.

`commerce_settle_reconciliation_defects`는 검증된 결함, `..._unverified`는 읽기 실패 뒤 전체 재검증을 마치지 못한 회차, `..._wait_age_seconds`는 발행·전달·반영·격리·진행 중의 최장 나이입니다. 자동 재시도의 `RUNNING` 중에도 조회 불가 계측을 유지합니다. 기존 Prometheus·Alertmanager 경로에서 결함은 CRITICAL, 조회 불가는 5분 지속 시 WARNING, 15분 대기는 CRITICAL로 알립니다. 원천 조회 실패를 금액 불일치나 0으로 해석하지 않습니다.

`python3 -B scripts/test_reconciliation.py`는 별도 Compose 프로젝트의 빈 DB·HTTP·Kafka에서 PG 승인과 발행을 실행하고, 실제 10분 안전 여유를 기다려 전달 대기·원천 조회 장애·같은 회차의 재기동을 검사합니다. 시험 자원은 종료 시 제거하며 사용자 데모는 사용하지 않습니다. 입력은 계약 `OrderPaymentRequested`부터 시작하므로 주문 API·환불·지급 전체 왕복을 검증한 결과로 해석하지 않습니다. CI의 `reconciliation` 시나리오도 같은 스크립트를 사용합니다.

## 개발·검증

```bash
./gradlew spotlessApply
python3 scripts/test_init_env.py
python3 -B scripts/test_compose.py
python3 scripts/test_sync_redis_run_id.py
python3 -B scripts/test_prepare_financial_kafka.py
./gradlew --continue build
python3 scripts/sync_redis_run_id.py
docker compose up -d --build --wait --wait-timeout 300
python3 -B scripts/prepare_financial_kafka.py
python3 scripts/smoke.py
```

운영·테스트 Java 코드의 포맷·Checkstyle, 단위·통합·ArchUnit 테스트와 패키징을 검사합니다. `spotlessApply`는 로컬 수정용이며 CI는 `spotlessCheck`로 위반을 차단합니다. 테스트 추가 기준은 [테스트 작성](../CONTRIBUTING.md#테스트-작성)을 따릅니다.

Python 테스트는 환경 초기화의 기존 값 보존을 확인합니다. [smoke](../scripts/smoke.py)는 기동·라우팅·내부 자격·DB/Redis 권한·Kafka/Redis 왕복·HTTP 추적 전파를 검사하고 임시 토픽·키를 지웁니다. CI는 낮은 부하의 k6 실행도 확인합니다. 결제·SAGA의 정합성과 성능은 별도 업무 시나리오로 검증합니다.

정적 검사만 실행할 때는 `./gradlew spotlessCheck checkstyleMain checkstyleTest`, 테스트만 실행할 때는 `./gradlew test`를 사용합니다. `build`는 이 검사들을 포함하므로 별도 실행이 필수는 아닙니다.

Payment API와 Worker는 같은 `payment` 모듈로 빌드합니다. Gradle 모듈별 명령은 `./gradlew :payment:build`처럼 실행합니다.

문서만 변경했으면 링크·앵커·예제·계약 보존과 `git diff --check`를 확인합니다. CI의 필수 검사는 [협업 규칙](../CONTRIBUTING.md#ci)을 따릅니다. 검증 결과는 해당 이슈·PR에 남기며, 대역·계약 fixture를 사용한 시험과 실제 서비스 간 왕복을 구분합니다.

### 정산 일배치 실행과 복구

`dailySettlementJob`은 `batchDate`가 식별자이며 매일 10:00 KST에 실행합니다. 기본 활성화이며 `SETTLEMENT_DAILY_BATCH_ENABLED=false`로 스케줄 실행을 끕니다. 같은 날짜의 COMPLETED는 다시 실행하지 않고 FAILED·STOPPED는 같은 식별자로 재개합니다. Job 실행 전에 날짜별 PostgreSQL 세션 advisory lock을 확보하며 다른 실행자가 있으면 중복 실행하지 않습니다.

강제 종료로 STARTED가 남으면 실행자 잠금을 얻은 뒤 Job·Step의 마지막 갱신이 `settlement.daily-batch.stale-after`(기본 10분)보다 오래된 경우에만 FAILED로 정리하고 같은 날짜로 한 번 재시작합니다. 확인할 수 없으면 정리하지 않으며 일시적인 조회·잠금 오류는 재확인합니다. 다음 날짜를 임의로 성공 처리하거나 새 날짜로 실패 회차를 덮어쓰지 않습니다.

판매자별 대상·합계·지급·작업은 한 트랜잭션에 저장하고 커머스 컨텍스트 HTTP 대기 중에는 DB 연결을 점유하지 않습니다. 재시작은 seller ID 순으로 미선점 PAYABLE 항목만 다시 처리해 완료한 판매자를 중복 지급하지 않습니다. 판매자별 검증·잠금 경합은 그 판매자를 건너뛰고 DB 자원 오류·공통 컨텍스트 조회 장애는 Step을 실패시켜 복구 후 재개합니다.

### Kafka 전달 기반

Listener·Outbox를 활성화했을 때의 기본 동시성·연결 풀은 다음과 같습니다. JDBC 값은 JVM별 상한이며, 인스턴스를 늘리면 합산해 DB·외부 대역 한도와 비교합니다.

| 앱 | Listener 동시성 | Outbox 미완료 발행 상한 | JDBC 풀 |
| --- | --- | --- | --- |
| Commerce | 1 | 8 | 10 |
| Payment | 3 | 32 | 20 |
| Settlement | 1 | 8 | 10 |
| Finance Mock | — | — | 5 |

Payment의 결제 요청 처리기는 3슬롯·슬롯별 100건, 승인 Worker는 6슬롯·조회당 최대 20건입니다. Settlement 지급은 8슬롯 중 2개를 미확정 조회·중단 복구에 전용으로 남깁니다. 실제 값은 각 앱의 `application.yml`과 명시한 환경 변수로 정하며 변경한 값·시험 조건을 함께 기록합니다. 이 상한은 처리량 달성의 근거가 아닙니다.

Commerce·Payment·Settlement는 자기 원본 토픽 `commerce-settle.<서비스>.v1`을 생성합니다. 소비자별 DLT는 `commerce-settle.<소비자>.v1.dlt`이며 소비자는 `commerce`, `commerce-notifications`, `payment`, `settlement`입니다. 모두 파티션 3개·복제 1개이고 원본은 24시간, DLT는 7일 보존합니다. Payment·Settlement의 명시적 원문 격리·DLT 발행 경로는 아래에 설명하며 다른 소비자는 토픽만 준비한 상태입니다. 기존 토픽 설정이 다르면 smoke가 실패하므로 실제 값을 대조해야 합니다.

Payment 요청 처리기는 `PAYMENT_ROLE=worker`와 `app.payment-request-processor.enabled=true`로 켭니다. 기본 동시성 3·슬롯별 최대 100건이며 승인·Outbox와 공유하는 JDBC 풀을 함께 조정합니다. 원문 인계·대기·결과 반영은 [Inbox 계약](data-model.md#inbox)을 따릅니다. 설정 원문은 [Payment 구성](../payment/src/main/resources/application.yml)을 확인합니다.

`app.outbox.enabled`와 `app.kafka.order-payment-requested.enabled`는 기본 `false`입니다. 빈 개발 DB에 서비스별 초기 SQL을 적용하고 아래 신규 그룹 초기화를 마친 뒤 각각 활성화합니다. 수신 구현 Bean이 없으면 활성화한 Listener는 시작에 실패합니다. 테스트 대역을 운영 구현으로 등록하지 않습니다. 기존 개발 컨테이너에 초기 SQL을 재실행하거나 볼륨을 임의로 초기화하지 않습니다.

Payment 환불 SAGA는 `PAYMENT_ROLE=worker`에서 `app.refund.enabled=true`로 켭니다(기본 `false`). `RefundApproved`·`RefundOrderRestored` 반영은 결제 요청 처리기(`app.payment-request-processor.enabled`)가 맡고, 이 설정은 외부 환불·마일리지 복원 Worker를 켭니다. Settlement 응답은 `app.kafka.settlement-replies.enabled`(기본 `false`)로 켠 수신기가 같은 Inbox에 넘기고 결제 요청 처리기가 반영하므로, 이 수신을 켜지 않으면 환불은 정산 응답 대기 단계에 머뭅니다. 수신 토픽 `commerce-settle.settlement.v1`은 Settlement가 만들며 없으면 Payment가 기동하지 않습니다. Settlement가 토픽을 만든 뒤 `payment.v1` 그룹에 아래 신규 그룹 초기화를 이 토픽에도 적용하고 켭니다. 외부 환불은 `app.refund.gateway`(`PAYMENT_REFUND_GATEWAY`, 기본 `stub`)로 `http`를 선택합니다. 승인과 같은 `app.payment-approval.mock-base-url`(`FINANCE_MOCK_URL`)·`mock-credential`(`FINANCE_MOCK_PAYMENT_CREDENTIAL`)을 사용하며, 환불의 연결·응답 제한 시간은 `app.refund.http-timeout`입니다. HTTP 선택 때 자격이 없거나 짧으면 기동하지 않습니다. 승인 당시 provider·외부 거래·시나리오 버전·외부 금액과 환불 업무 키를 보존하므로, 기존 stub 승인 데이터는 HTTP 환불 검증에 사용하지 않습니다. 환불 준비·결과 반영·Settlement 응답 반영은 환불 행을 잠그기 전에 잠금 대기 1초·SQL 실행 5초 상한을 걸고, 시간 초과 시 트랜잭션 전체를 롤백합니다. Worker는 잠근 뒤와 외부 호출 직전에 임대가 호출 제한 시간만큼 남았는지 다시 확인합니다. 새 테이블(`refunds`·`refund_sagas`)은 빈 개발 DB에 초기 SQL을 적용해야 생깁니다.

업무와 Outbox·Inbox의 원자 저장·원문·선행 순서·ACK는 [이벤트 연동](api.md#이벤트-연동)과 [메시지 저장 모델](data-model.md#메시지멱등성감사)을 따릅니다. 이 절의 환불·Outbox·Inbox 저장은 잠금 대기 1초·SQL 5초 상한을 적용하며 롤백 시 offset을 ACK하지 않습니다. 금융 실패나 예약 해제로 바꾸지 않습니다.

Commerce Outbox는 커밋 신호로 발행하며 누락 신호·재시도·만료 임대를 기본 1초마다 복구합니다. 기본값은 미완료 발행 8건·조회 100건·임대 240초·재시도 1~60초/10회이며 회수도 횟수에 포함합니다. 임대는 Kafka 대기 상한의 합보다 길어야 하고 설정 불일치는 기동을 거절합니다. 같은 집계의 선행 미완료 건을 추월하지 않으며 소진은 REVIEW_REQUIRED로 보존합니다. 종료 대기는 최대 30초, 운영 HTTP 재처리는 지원하지 않습니다. 실행 풀·대기열의 세부값은 [Commerce 설정](../commerce/src/main/resources/application.yml)을 따릅니다.

Kafka template·listener의 observation을 활성화해 실제 발행 시도의 traceparent를 소비자 처리로 전파합니다. Outbox는 재시작·재전달 가능한 비동기 경계이므로 발행 시도마다 새 trace를 시작하고 불변 eventId로 연결합니다. 원래 HTTP trace를 DB에 보존하거나 금융 Job까지 연결하는 구현은 포함하지 않습니다. Kafka 통합 시험은 원본 헤더와 소비자 span의 traceId·parentId, 재전달 뒤에도 같은 원본 parent를 유지하는지 확인합니다.

Consumer는 `auto.offset.reset=none`이므로 새 그룹에 offset이 없거나 보존 범위를 벗어나면 자동으로 처음/끝으로 이동하지 않습니다. **첫 생산자를 열기 전에**, 새 구독 토픽의 모든 파티션이 비어 있고 기존 그룹 offset도 없음을 확인한 경우에만 초기화합니다. 현재 Payment 수신 경계의 예시는 다음과 같습니다.

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic commerce-settle.commerce.v1 --time -2
docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic commerce-settle.commerce.v1 --time -1
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group payment.v1
# 위 조회에서 모든 파티션의 시작·끝이 0이고 기존 그룹 offset이 없을 때만 실행
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --reset-offsets --group payment.v1 --topic commerce-settle.commerce.v1 --to-earliest --execute
```

초기화 후 영속 인계 구현과 수신 경계를 활성화하고 생산자를 엽니다. 이미 메시지가 있거나 기존 offset이 있는 환경에는 위 초기화 명령을 적용하지 않습니다. 보존 이탈은 DB 원본과 대조해 복구하며 임의 offset 변경으로 정상 소비처럼 처리하지 않습니다. 다른 그룹·구독 토픽을 추가할 때도 같은 준비가 필요합니다.

수신은 메시지 종류·버전·key·필수 필드·타입을 검증하고 영속 인계가 성공한 뒤에만 ack합니다. 기본 설정은 처리 예외에 해당 Listener를 중단하며 실패한 offset을 건너뛰지 않습니다. 원인과 저장 결과를 확인한 뒤 재기동하면 마지막 커밋 위치에서 재전달됩니다.

Payment 원문 격리는 새 빈 DB의 초기 SQL을 검증한 뒤 `app.kafka.quarantine.enabled=true`로 켭니다. 기본값은 false입니다. eventType을 읽을 수 없거나 처리 대상 종류의 계약에 맞지 않는 원문과 DLT 작업을 함께 커밋한 뒤 ACK하며, DB 저장 실패는 ACK 없이 Listener를 중단합니다. 같은 eventId의 본문 충돌은 기존 `inbox_conflicts`에서 확인합니다.

Payment 격리 발행은 한 번에 하나만 임대하며 Kafka 응답 대기 중 공통 예약 스레드와 DB 트랜잭션을 점유하지 않습니다. 기본 주기는 1초, 주기당 최대 32개, 임대 240초, 결과 대기 125초입니다. `max.block.ms`와 결과 대기의 합은 임대보다 짧아야 합니다. 발행 실패는 1초부터 최대 60초 간격으로 10회까지 재시도하고, 소진하면 원문과 작업을 REVIEW_REQUIRED로 보존합니다.

원인 수정 뒤 인증된 운영 호출자는 내부 재처리 호출로 원래 토픽·키·본문을 재발행합니다. 예상 버전과 활성 재처리 부재를 검사하고 요청자·사유를 기록합니다. REVIEW_REQUIRED 작업은 내부 재개 호출로 상태·버전을 확인하고 이전 시도 수와 재개 이력을 보존하며 재개합니다. DLT 가용성과 독립적으로 요청할 수 있고, 발행 완료가 업무 성공을 뜻하지는 않습니다. 운영 HTTP는 [Payment 격리 원문 운영 API](api.md#payment-격리-원문-운영)이며 `operator:<식별자>` 자격만 받습니다. 재처리·재개는 `Idempotency-Key`(8~128자)가 필요하고, 멱등 기록은 `payment.idempotency_records`, 감사는 `payment.audit_logs`에 남습니다. 격리 운영 조회 인덱스는 Payment 초기 SQL에만 있어 기존 볼륨에는 생기지 않으므로 새 빈 개발 DB에서 사용합니다. 재처리는 원문의 원래 수신 토픽에 재발행하며 그 토픽은 다른 소비 그룹도 읽으므로, 다른 그룹이 같은 원문을 이미 처리했는지 확인한 뒤 요청합니다. 같은 eventId·bytes 재전달은 각 Inbox가 중복으로 끝냅니다. 공개 관리 UI는 제공하지 않습니다.

Settlement 원문 격리도 같은 테이블 구조와 `app.kafka.quarantine.enabled`(기본 false)를 사용합니다. `PaymentConfirmed`·`PurchaseConfirmed`의 해석 실패만 격리하고, 반영 중 DB 오류와 격리 저장 실패는 ACK 없이 Listener를 중단합니다. Settlement 격리 발행은 공통 예약 스레드(4개)에서 Kafka 응답을 기다리며 주기·묶음·임대·결과 대기·재시도 규칙은 Payment와 같습니다. 대상 토픽은 `commerce-settle.settlement.v1.dlt`입니다. 운영 재처리·재개도 Payment와 같은 검사·기록으로 원래 수신 토픽(`commerce-settle.payment.v1`·`commerce-settle.commerce.v1`)에 재발행합니다. 이 토픽은 Commerce·Payment·알림 소비 그룹도 읽으므로, 다른 그룹이 같은 원문을 이미 처리했는지 확인한 뒤 요청합니다. 같은 eventId·bytes 재전달은 각 Inbox가 중복으로 끝냅니다.

Settlement 발행 Outbox는 Payment와 같은 Relay 규칙(커밋 후·결과 반영 후·시작 시 깨움, 1초 복구 조회, 미완료 발행 최대 8건, 임대 240초, 10회 소진 REVIEW_REQUIRED)을 따르며 `app.outbox.enabled`(`APP_OUTBOX_ENABLED`, 기본 false)로 켭니다. 환불 SAGA 응답과 판매자 알림 요청은 스위치와 관계없이 업무 트랜잭션에서 저장합니다. 일배치와 지급 결과 반영도 Outbox에 쓰므로 `settlement.outbox`가 없는 기존 개발 DB에서는 일배치가 실패합니다. 새 초기 SQL을 적용한 DB에서 실행합니다.

### 업무 Inbox 관측과 재판정

새 전용 DB에 현재 Settlement 초기 SQL을 적용한 환경에서 실행합니다. 기존 DB·볼륨에 초기 SQL을 재실행하거나 사용자 확인용 데모에 fixture를 적재하지 않습니다. Worker는 `settlement.inbox-rejudgement.enabled`(환경 변수 `SETTLEMENT_INBOX_REJUDGEMENT_ENABLED`, 기본 true)로 동작하며 1초마다 최대 32건, 한 실행에서 한 대상씩 30초 임대합니다. RUNNING 실행은 앱 재기동 뒤에도 같은 고정 대상을 이어갑니다.

대상 서비스의 직접 주소와 기존 운영자 자격을 사용합니다. 자격은 환경 변수로 전달하고 명령 출력·PR에 붙이지 않습니다.

```bash
curl --fail-with-body -H "Authorization: Bearer $SETTLEMENT_OPERATOR_CREDENTIAL" "$SETTLEMENT_OPERATION_URL/internal/settlement-inbox-summary"
curl --fail-with-body -G -H "Authorization: Bearer $SETTLEMENT_OPERATOR_CREDENTIAL" --data-urlencode 'reasonCode=PAYMENT_NOT_ARRIVED' --data-urlencode 'receivedBefore=2026-11-03T23:00:00+09:00' "$SETTLEMENT_OPERATION_URL/internal/settlement-inboxes"
```

목록의 조건·totalElements와 대상 ID/version을 대조해 POST `/internal/settlement-inbox-rejudgements`에 다음 형태로 제출합니다. ID·시각·수를 실제 조회값으로 바꾸고 `Idempotency-Key`를 붙입니다. 원문·eventId·금융 업무 키·confirmedAt은 입력하지 않습니다.

```json
{
  "filter": {"reasonCode":"PAYMENT_NOT_ARRIVED","receivedBefore":"2026-11-03T23:00:00+09:00","inboxId":"00000000-0000-4000-8000-000000000001"},
  "expectedCount":1,
  "targets":[{"inboxId":"00000000-0000-4000-8000-000000000001","expectedVersion":10}],
  "reasonCode":"PREDECESSOR_VERIFIED"
}
```

응답 Location의 실행을 조회하고 `/targets`·`/attempts`에서 결과를, `/audits`에서 운영자·처리기의 행위·사유를 확인합니다. 중단·재개·취소에는 실행 조회값의 expectedVersion과 reasonCode를 제출합니다. 조건·대상 버전이 달라지면 다시 조회하며, 사유 미기록·계약·원천·종결 충돌을 정상으로 강제하지 않습니다. COMPLETED만 보지 말고 PREREQUISITE_MISSING·VERSION_CHANGED·PROCESSING_FAILED·ATTEMPTS_EXHAUSTED와 실제 Inbox/업무 결과를 함께 확인합니다. 격리 원문 재발행과 구분은 [업무 정책](policies.md#업무-inbox-재판정)을 따릅니다.

사유·이벤트별 건수와 나이, 미완료 실행의 진행 정체는 `/actuator/prometheus`에서 관측합니다. 실행·대상·시도·감사 기록은 재기동 후에도 DB에 남으며 원문은 일반 로그나 알림으로 보내지 않습니다.

### DLT 관측과 조치

격리 기능을 켠 Payment·Settlement는 15초마다 자기 DB를 전용 실행기에서 관측하고 `/actuator/prometheus`로 마지막 성공 표본을 노출합니다. SQL 제한 시간은 2초이며 scrape와 공통 예약 스레드에서 DB를 조회하지 않습니다. 원문·키·업무 ID는 지표 label에 넣지 않습니다.

Grafana의 `commerce-settle / Payment quarantine`·`Settlement quarantine`은 최근 5분 신규 격리, DLT/REPLAY 상태별 작업·도래 후 최장 대기, 수집 성공과 표본 나이를 보여줍니다. 미래 재시도와 유효 임대는 지연에서 제외합니다. 같은 논리 작업은 인스턴스 간 합산하지 않으며 수집 실패·45초 이상 지난 표본은 상태 집계에서 제외합니다. 수집 없음을 0건으로 해석하지 않습니다.

Prometheus는 신규 격리·재시도 소진을 CRITICAL, 도래 후 5분/15분 지연을 WARNING/CRITICAL로 Alertmanager에 전달합니다. DB 관측 실패·오래된 표본과 각 서비스 관측 대상 중단도 경보합니다. 지표 이름은 `payment_quarantine_*`·`settlement_quarantine_*`이며 기준은 같습니다. 실제 Alertmanager 전달 시험은 Payment 경보만 다루고 Settlement는 규칙 시험까지 합니다. 신규 격리는 DB 기록 시각 기준 최근 5분 창이며 창 종료로 알람이 해제돼도 업무 복구 완료를 뜻하지 않습니다. 상세 기준은 [운영 알람](policies.md#운영-알람)을 따릅니다.

1. 신규 격리는 원본 위치·오류 코드를 확인하고 생산자/스키마 원인을 조사합니다. 원문을 Slack·일반 로그에 붙이지 않습니다.
2. 발행 지연·REVIEW_REQUIRED는 Kafka 가용성, 발행 작업 상태·시도 수·임대를 확인합니다. DLT가 비어 있어도 DB 발행 대기를 확인합니다.
3. 원인 수정 뒤 위의 `requestReplay` 또는 `resumeDelivery`로 재처리하며 발행 완료와 실제 Inbox·업무 결과를 따로 대조합니다. Payment는 [Payment 격리 원문 운영 API](api.md#payment-격리-원문-운영), Settlement는 [격리 원문 운영 API](api.md#격리-원문-운영)로 호출합니다. Settlement 업무 Inbox는 [위 재판정 API](#업무-inbox-관측과-재판정)로 처리하며, Payment·Commerce 업무 Inbox의 수동 재개는 별도 구현 대상입니다.

기본 수신처는 Alertmanager 화면입니다. 팀 Incoming Webhook을 연결할 때만 비밀 URL을 저장소 밖 파일에 두고 다음 overlay를 사용합니다. 해당 Webhook의 채널이 수신처이며 실제 채널로 시험 메시지를 자동 발송하지 않습니다.

```bash
SLACK_WEBHOOK_URL_FILE=/path/to/slack-webhook-url \
docker compose -f compose.yml -f compose.alertmanager-slack.yml --profile observability up -d --wait --wait-timeout 300
```

`python3 scripts/test_observability.py`는 격리한 Prometheus→Alertmanager→시험 수신기로 경보·복구 전달을 검사하고 전용 자원을 제거합니다. 실제 Slack을 사용하지 않으며 Settlement는 규칙 검사까지, 금융 접수 복구는 [별도 시험](#금융-관측과-접수-복구)으로 확인합니다.

`./gradlew :commerce:test :payment:test`는 전용 Kafka·PostgreSQL 컨테이너로 전달·저장 경계를 각각 검사하고 종료 시 제거합니다. Docker가 없으면 생략하지 않고 실패합니다. Payment 수신의 인계 대역을 포함하므로 실제 주문→외부 금융 전체 왕복을 대신하지 않습니다. 기본 `build`·CI에 포함되며 개발 DB·Kafka·볼륨을 사용하지 않습니다.

### Gateway 라우팅과 오류

Gateway의 서비스 주소는 기존 `COMMERCE_URL`·`PAYMENT_URL`·`SETTLEMENT_URL`로 재정의합니다. 업무 경로를 별도로 나열하지 않고 빌드 때 `docs/openapi.json`을 `contracts/openapi.json` 리소스로 포함합니다. 명세의 `/api/` 작업을 추가·변경하면 Gateway도 다시 빌드해야 합니다. `/internal`·`/mock`·관리 경로는 대상에서 제외합니다. 같은 명세의 `x-actor`로 보호 작업의 세션을 검증하고, 상류 요청에는 대상별 Gateway 자격을 붙입니다.

`./gradlew :gateway:build`는 로컬 HTTP 대역과 실제 Redis/ACL로 라우팅·신원·거절·오류 경계를 검사합니다. 실제 업무 백엔드의 성공 검증은 아닙니다.

Gateway 자체 오류는 `application/problem+json`과 실제 추적의 `traceId`로 응답하며 쿼리·원래 예외 메시지·내부 주소를 본문에 넣지 않습니다. 미등록 경로·메서드는 `404 COMMON_RESOURCE_NOT_FOUND`, 상류 연결 실패·시간 초과는 `503 COMMON_TEMPORARILY_UNAVAILABLE`, 예상 밖 오류는 `500 COMMON_INTERNAL_ERROR`입니다. 전송 계층이 요청을 자동 재전송하지 않으므로 503·응답 유실 뒤에는 원업무 키로 결과를 확인합니다. 백엔드가 보낸 상태·오류 본문·추적 ID는 그대로 전달합니다.

### 내부 호출 자격

자격은 `[A-Za-z0-9_-]{43,}` 형식의 256비트 이상 값입니다. 발신자 ID는 `gateway`, `service:[a-z][a-z0-9-]*`, `operator:<영문·숫자·점·밑줄·하이픈 식별자>`이며 현재·이전 값 두 개까지 허용합니다. 자격 없음·불일치는 401, 미등록 메서드·경로는 404, 대상 서비스·역할·신원 헤더 권한 불일치는 403입니다. 사용자 세션 토큰을 내부 자격으로 재사용하지 않습니다.

내부 호출 자격 판정은 `internal-auth` 라이브러리의 자동 구성이 서블릿 앱의 모든 요청에서 컨트롤러 전에 수행합니다. 각 앱은 `docs/openapi.json`을 `contracts/openapi.json` 리소스로 포함하고 `app.internal-auth`에 소유 서비스(`service`), 필수 발신자(`required-sender`, 기본 `gateway`), 발신자별 현재·이전 자격(`senders`)을 둡니다. 명세 문서(`GET /v3/api-docs/**`)와 관리 포트는 판정하지 않습니다. 명세의 작업·권한을 바꾸면 Gateway와 해당 백엔드를 함께 다시 빌드합니다.

| 발신자 → 대상 | 받는 쪽 변수 | 보내는 쪽 |
| --- | --- | --- |
| `gateway` → 커머스·Payment·Settlement | `<대상>_GATEWAY_CREDENTIAL`(`_PREVIOUS`) | Gateway가 대상별 값을 붙임 |
| `service:settlement` → Finance Mock | `FINANCE_MOCK_SETTLEMENT_CREDENTIAL`(`_PREVIOUS`) | Settlement 지급 Worker와 대조 읽기 |
| `service:settlement` → Payment API | `PAYMENT_SETTLEMENT_CREDENTIAL`(`_PREVIOUS`) | 고정 금융 이벤트·Payment 소유 정합성 대조 읽기 |
| `service:settlement` → 커머스 | `COMMERCE_SETTLEMENT_CREDENTIAL`(`_PREVIOUS`) | Settlement 일배치의 상품 지급 컨텍스트 조회 |
| `service:payment` → Finance Mock | `FINANCE_MOCK_PAYMENT_CREDENTIAL`(`_PREVIOUS`) | PG 승인·환불·조회 HTTP 클라이언트와 대조 읽기 API. Compose는 Payment Worker와 Payment API에 현재 값을 전달 |

자격을 교체할 때는 받는 쪽 `_PREVIOUS`에 이전 값을 보존하고 새 현재 값을 설정한 뒤 받는 앱과 보내는 앱을 재기동합니다. 전환 후 `_PREVIOUS`를 비우고 받는 앱을 다시 기동합니다.

변수 이름은 `<대상>_<발신자>_CREDENTIAL`이며 `init_env.py`가 `.env.example`의 빈 값을 256비트 URL-safe 값으로 채웁니다. `_PREVIOUS`는 교체 중에만 받는 쪽에 둡니다. 값이 없거나 43자 미만이면 받는 쪽과 보내는 쪽 모두 기동하지 않습니다. 거절은 `internal_auth_rejections_total{sender,status}` 지표와 `<서비스>.internal-auth` 경고 로그로 확인하며, 자격 원문은 남기지 않습니다.

새 서비스·운영자 호출을 연결할 때는 아래 항목을 함께 설정합니다.

1. 받는 쪽: `application.yml`의 `app.internal-auth.senders`에 발신자를 추가합니다. `:`가 있는 키는 `"[service:payment]"`처럼 대괄호로 감쌉니다.
2. 보내는 쪽: 해당 클라이언트의 모든 요청에 `Authorization: Bearer <자격>`을 붙이고 기동 때 형식을 확인합니다. 자격 거절 `401`·`403`은 업무 결과로 확정하지 않습니다.
3. 환경: `.env.example`에 `<대상>_<발신자>_CREDENTIAL`·`_PREVIOUS`를 추가하고, Compose에서 받는 쪽에 두 값, 보내는 쪽에 현재 값을 전달합니다. smoke에 실제 호출 경로가 `401`·`403`이 아닌지 추가합니다.

운영자 자격은 API별 운영자 식별자·허용 작업과 함께 설정합니다.

Settlement의 운영 API(`/internal/payout-jobs`·`/internal/settlement-batches`·`/internal/settlement-quarantines`·`/internal/settlement-quarantine-deliveries`)는 `operator:<식별자>` 자격만 받습니다. Payment 운영 종결과 같이 인스턴스의 `app.internal-auth.senders`에 `"[operator:<식별자>]"` 키로 설정하며 Compose와 `.env.example`에는 넣지 않았습니다. 재처리·재개·배치 생성은 `Idempotency-Key`(8~128자)가 필요하고, 감사는 `settlement.audit_logs`, 재처리 사유는 `settlement.payout_job_replays`·`inbox_quarantine_deliveries`·`inbox_quarantine_resumes`에 남습니다.

컨테이너 상태·로그는 아래처럼 확인합니다.

```bash
docker compose ps -a
docker compose logs --tail=100 payment-api payment-worker
docker compose exec -T payment-api curl --fail http://localhost:9091/actuator/health
```

## Redis 세션 검증과 복구

인증 동작은 [세션 정책](policies.md#인증공통-api-기준)을 따릅니다. `init_env.py`가 생성하는 키는 교체 영향이 다릅니다.

| 설정 | 교체 영향 |
| --- | --- |
| `AUTH_SESSION_EPOCH` | 모든 기존 세션 무효화 |
| `AUTH_RATE_LIMIT_HMAC_KEY` | 진행 중 요청 제한 시간창 초기화, 24시간 안의 구매자 가입 재요청 본문 충돌(409) |
| `AUTH_RESET_DELIVERY_KEY` | 미전달 복구 토큰 복호화 불가 |
| `AUTH_REDIS_RUN_ID` | Gateway·Commerce가 승인할 Redis 실행 식별자. 인증 세대와 함께 복구 |

CI의 `smoke_redis_restart.py`는 Redis·Gateway·Commerce를 재기동하므로 공용 스택에서 실행하지 않습니다. 재시작·같은 run_id의 연결 단절·세대 교체 후 이전 세션 거절과 새 로그인을 확인하며, 인증 변경 재조정은 이 smoke의 검사 범위에 포함하지 않습니다.

앱 기동 성공과 사용자 인증 허용은 별개입니다. 승인한 Redis 실행 식별자가 맞아야 인증을 열고, 열린 뒤 단절·불일치가 발생하면 503으로 차단합니다. 재접속·스크립트 복구만으로는 다시 열지 않습니다. 공개 작업·서비스/운영자 자격·기존 금융 작업은 별도로 처리합니다. 원인은 `auth.redis-gate` 로그의 `RUN_ID_MISMATCH`·`DISCONNECTED`로 확인하고 아래 복구 절차를 수행합니다.

인증의 만료·폐기·역할·요청 제한 검증 기준은 [세션 정책](policies.md#인증공통-api-기준)을 따릅니다. Gateway의 허용 명령은 `EVALSHA_RO`·`EVAL_RO`이며 `SCRIPT LOAD`·일반 `EVAL`·`EVALSHA`와 키 쓰기는 거절돼야 합니다. 저장소/ACL 시험과 실제 재기동 smoke의 범위를 구분합니다.

Redis 인증 저장소 장애·재시작·재연결 뒤에는 자동으로 보호 요청을 다시 허용하지 않습니다. 복구 절차는 다음과 같습니다.

1. Gateway·커머스의 사용자 인증 게이트를 닫고 모든 인스턴스의 신규 보호 요청·세션 발급·비밀번호 재설정·인증 관련 계정 변경을 503으로 차단합니다. 기존 금융 작업과 서비스·운영자 자격은 별도로 처리합니다. 이미 시작한 이전 세대의 인증 변경은 DB 트랜잭션 종료·결과 확인까지 기다리거나 DB 인증 버전의 조건부 증가로 이전 버전 쓰기를 차단합니다. 종료 또는 차단을 확인하기 전에는 새 세대 가드를 구성하거나 인증을 재개하지 않습니다.
2. 계정 변경의 미확정 결과를 PostgreSQL에서 확인하고 새 인증 세대 UUID와 현재 Redis 실행 식별자를 Gateway·커머스 전체에 같은 값으로 적용합니다. 기존 세대로 동작하는 인스턴스를 남기지 않습니다. 로컬·CI의 장애 복구에서는 `python3 scripts/sync_redis_run_id.py --recover`가 `redis-cli INFO server`의 `run_id`를 `AUTH_REDIS_RUN_ID`에 넣고 `AUTH_SESSION_EPOCH`를 새 UUID로 바꾸며, 이어서 `docker compose up -d --wait --wait-timeout 300`으로 Gateway·커머스를 다시 만듭니다. run_id만 바꾸고 세대를 그대로 두면 AOF에 남은 이전 세션이 다시 유효해지므로 두 값을 항상 함께 바꿉니다. 연결만 끊겼다면 run_id는 그대로이므로 `--recover`로 세대 교체를 명시합니다. 일반 기동의 옵션 없는 동기화는 run_id가 같으면 세대를 유지합니다.
3. 새 세대의 가드는 DB 확정 상태·인증 버전으로 구성합니다. 이전 세대 세션을 AOF·DB에서 새 세대로 복사하지 않으며, 사용자는 재로그인합니다.
4. 이전 세대 토큰 거절·새 로그인·고정 만료·정지 계정 차단과 ACL을 확인한 뒤 인증 게이트를 엽니다. 이전 키 정리는 인증 영역의 해당 세대만 대상으로 하며 다른 캐시·업무 데이터를 지우지 않습니다.

복구 뒤에는 AOF의 이전 토큰과 지연된 이전 인증 변경이 다시 유효해지지 않는지 확인합니다. Redis 실행 식별자·인증 세대가 그대로인 API 프로세스 재시작은 원래 세션 만료를 유지합니다.

## 종료·재실행·초기화

```bash
# 컨테이너만 종료하고 DB·Kafka·Redis·관측 볼륨을 보존
docker compose --profile observability --profile tools down

# 같은 데이터로 재실행. Redis가 새로 떠 run_id가 바뀌므로 승인값과 세대를 다시 맞춘다
python3 scripts/sync_redis_run_id.py
docker compose -f compose.yml -f compose.financial-flow.yml up -d --wait --wait-timeout 300
python3 scripts/smoke.py

# 이 Compose 프로젝트의 로컬 데이터까지 삭제할 때만 실행
docker compose --profile observability --profile tools down -v --remove-orphans
```

DB 볼륨이 남아 있으면 초기화 스크립트가 다시 실행되지 않습니다. 따라서 `.env`의 DB 비밀번호만 바꾸면 기존 DB와 연결할 수 없습니다. 필요한 경우 기존 DB에서 비밀번호를 변경하거나, 보존할 데이터가 없는 로컬 환경에서 위 초기화 명령으로 볼륨을 삭제하고 다시 시작합니다.

위 재실행은 금융 처리를 켠 처음 실행의 설정을 유지합니다. 관측 도구 추가나 인증 복구 등 다른 절차에서 앱을 재생성할 때도 `-f compose.yml -f compose.financial-flow.yml`을 함께 지정합니다. 다른 덮어쓰기 파일을 사용한다면 이 두 파일 뒤에 추가합니다. 볼륨까지 삭제한 환경은 [처음 실행](#처음-실행) 순서로 Kafka 소비 위치부터 다시 준비합니다.

Redis 비밀번호를 바꿀 때도 앱과 Redis를 함께 재생성해야 합니다. Redis 데이터는 AOF 볼륨에 보존됩니다. Zipkin 메모리 추적은 재시작하면 사라집니다.

현재 개발 환경의 스키마 적용은 [DB 정책](migrations.md)을 따릅니다. 초기 SQL 수정·이미지 재빌드만으로 기존 DB는 바뀌지 않으며, 새 빈 개발 DB에서 검증합니다. 관측 프로필을 켠 적이 있으면 종료 시에도 `--profile observability`를 지정합니다.

버전 호환성은 [Spring Boot 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Spring Cloud 호환표](https://cloud.spring.io/), Kafka 구성은 [공식 Docker 안내](https://kafka.apache.org/42/getting-started/docker/)를 참고합니다.

## 본인 카카오톡 시연

본인 카카오 계정의 수신 시연입니다. 기본은 MOCK_KAKAO이며 명시한 구매자·판매자만 실제 채널을 선택합니다. 목적지·묶음·본문·미확정 재처리는 [채널 정책](policies.md#본인-카카오톡-시연)이 기준입니다.

### 앱 설정과 최초 동의

카카오디벨로퍼스에서 본인 앱의 카카오 로그인을 켜고 `talk_message` 동의항목, `http://127.0.0.1:8765/callback` 리다이렉트 URI, 메시지 링크의 웹 도메인을 등록합니다. 앱의 client secret을 활성화합니다. [공식 self-send 안내](https://developers.kakao.com/docs/ko/kakaotalk-message/rest-api)와 [로그인·토큰 안내](https://developers.kakao.com/docs/ko/kakaologin/rest-api)를 따릅니다. 앱·본인 카카오 사용자 ID는 해당 앱의 기존 도구에서 확인하고 다른 계정으로 대신 동의하지 않습니다.

Git·공유 폴더 밖에 본인 소유 0700 디렉터리를 준비하고 다음 도구를 실행합니다. 키와 client secret은 터미널의 숨김 입력으로 받으며 명령 인자·환경 파일에 넣지 않습니다.

```sh
python3 -B scripts/kakao_login.py --file /absolute/private/kakao/credentials.json --user-id YOUR_KAKAO_USER_ID --app-id YOUR_KAKAO_APP_ID
```

도구가 표시한 loopback 주소를 브라우저에서 열고 본인이 로그인·동의합니다. 콜백은 127.0.0.1에만 바인딩하며 5분 뒤 종료합니다. `state`, 동의 범위, 발급된 실제 계정·앱을 검사한 뒤 자격을 0600 파일로 저장하고 토큰·인가 코드·카카오 응답 원문을 출력하지 않습니다. 이 도구는 예시 메시지를 보내지 않습니다.

### 시연 설정과 재인증

`.env`에 시연 구매자·판매자 UUID 목록(`KAKAO_SELF_BUYER_IDS`, `KAKAO_SELF_SELLER_IDS`), 본인 카카오 사용자·앱 ID, 등록한 메시지 링크, 비밀 디렉터리의 절대 경로를 지정합니다. 링크는 기존 시연 안내나 API 문서처럼 실제로 열리는 주소를 사용하며 별도 프론트를 만들지 않습니다. 비밀 디렉터리는 파일 하나가 아닌 디렉터리 전체를 마운트해야 원자적 토큰 교체와 잠금이 가능합니다. `KAKAO_SELF_UID`·`KAKAO_SELF_GID`는 비밀 파일 소유자의 `id -u`·`id -g` 값이며 이 로컬 시연 overlay에서만 실행 사용자를 맞춥니다. 기존 서비스·Git 경로의 소유자나 권한은 바꾸지 않습니다.

```sh
./gradlew :commerce:bootJar
docker compose -f compose.yml -f compose.kakao-self.yml --profile observability config --quiet
docker compose -f compose.yml -f compose.kakao-self.yml --profile observability up -d --build --wait --wait-timeout 300
```

먼저 [처음 실행](#처음-실행)의 환경·인증 Redis 준비와 [Kafka 초기 offset](#kafka-전달-기반)을 마칩니다. overlay는 알림 소비·Worker, 주문 전이·결제 결과 처리·Outbox와 알림 지표를 켭니다. 신규 주문 접수는 관측값을 사용하므로 observability 프로필도 실행합니다. 변경한 초기 SQL은 새 빈 전용 DB에만 적용하며 기존 볼륨을 초기화하거나 SQL을 재실행하지 않습니다.

앱은 파일·access token의 실제 계정/앱을 대조한 뒤 갱신·사용하며 회전된 자격은 원자 저장합니다. 갱신 후 소유자 확인이 실패하면 재시작 뒤에도 검증부터 재개하고 확인 전에는 발송하지 않습니다. 갱신 거절·만료·계정 불일치·안전하지 않은 권한은 아래 재인증 절차로 처리합니다. 토큰 조회·갱신·본문 수신의 전체 제한은 4초이며 기한을 넘기면 수신도 취소합니다. 발송 후 시간 초과는 미확정입니다.

재인증은 시연 인스턴스의 알림 실행을 멈춘 뒤 같은 경로·계정·앱으로 로그인 도구를 다시 실행합니다. 앱이 도달할 수 없는 비밀 디렉터리는 권한을 넓혀 우회하지 말고 시연 사용자·마운트 설정을 바로잡습니다. 비밀 파일·잠금 파일은 실제 자격이며 작업 임시 파일이나 Git 산출물로 취급하지 않습니다.

실제 발송의 응답 유실·시간 초과·만료 임대는 `REVIEW_REQUIRED`와 UNKNOWN 시도 이력으로 멈추며 모의 수신 조회나 자동 재발송을 하지 않습니다. 본인 채팅과 마지막 오류를 확인한 운영자는 기존 재처리 API에 최신 버전·사유·멱등키를 보내 명시적으로 다시 발송할 수 있습니다. 채널·물리 계정·본문은 유지하며, 제공자 조회·멱등 보장이 없어 중복 수신 위험을 사유에 기록해야 합니다. 계정 변경·시연 해제 상태에서는 기존 실제 발송을 차단합니다.

### 검증 범위

`./gradlew :commerce:test`는 PostgreSQL과 loopback HTTP 대역을, `python3 -B scripts/test_kakao_login.py`는 비밀 파일·계정·동의 경계를 검사합니다. 실제 카카오 수신과 구분합니다.

본인 수신 시연에서는 실제 주문·결제·발송 API의 상태 전이, Payment·Settlement의 알림 생산, 발송 이력과 본인 채팅의 수신 내용을 함께 대조합니다. 금융 이벤트를 주입한 시험은 자동 검증으로 따로 표시하며 실제 금융 E2E 성공으로 보고하지 않습니다. API 성공·카카오 수신과 읽음·단말 팝업 확인도 구분합니다.

## 데모·실험 UI

`demo-ui/`는 React·TypeScript·Vite·TanStack Query 기반의 로컬 화면입니다. `demo-control/`은 같은 출처의 UI를 제공하고 명세에 있는 사용자 요청은 Gateway로, 운영 요청은 설정된 소유 서비스로 전달하는 Java 21 실행 앱입니다. 운영 자격은 서버에만 두며 DB를 직접 읽거나 수정하지 않습니다. 기본 Compose에는 이 앱을 추가하지 않습니다.

시연자 한 명이 상단의 `구매자`·`판매자`로 실행 계정을 전환해 사용합니다. 처음에는 해당 역할을 고른 뒤 상단 `회원가입` → 폼의 `연습용 가입값 채우기` → `회원가입` 순서로 진행하세요. 결과 영역의 `가입한 구매자/판매자로 로그인`을 누르면 같은 이메일·비밀번호가 채워집니다. 폼의 `로그인`을 누르고 결과 영역의 로그인 상태·다음 작업 버튼을 확인하세요. 기존 계정은 상단 `로그인`에서 시작합니다. 두 역할의 로그인은 각각 필요하며, 같은 화면에서 전환하면 각 세션을 유지합니다. 새로고침하면 메모리 세션·가입정보가 지워집니다.

정상 구매는 `상품 불러오기` → 상품의 `이 항목 사용` → 견적의 `다음 단계 실행` → 주문의 `다음 단계 실행` → `결제·배송 상태 다시 확인` 순서로 진행합니다. 선택한 상품과 견적의 구매 조건·금액·버전, 생성된 주문 ID가 다음 입력에 자동으로 채워집니다. 견적·주문 요청이 성공하면 이전 결과를 접어 남기고 다음 단계로 내려갑니다. 오류나 미확정 응답에서는 자동으로 다음 요청을 보내지 않습니다. 주문 접수와 결제 완료는 다르며 마지막 단계는 같은 주문을 조회합니다. 배송·자동 구매확정·정산의 실제 기한은 유지합니다. 로그인·상품 준비 안내는 접어서 제공하며, 판매 상품이 없으면 결과의 판매자 상품 등록 진입으로 준비합니다.

나머지 흐름과 개별 API 작업은 넓은 화면에서 요청 폼과 서버 결과를 나란히 봅니다. `할 작업`에서 개별 API를 고르면 단계 진행을 벗어나 입력을 직접 확인·실행할 수 있습니다. 좁은 화면의 `시연 흐름` 선택 메뉴로도 49개 분기와 월간 화면을 탐색합니다. 흐름 선택만으로 API를 호출하지 않습니다. 요청 원문·이전 응답은 `실행 기록 보기`, 모니터링·부하는 `실험·모니터링`에서 엽니다. 운영 작업은 기존 경로에서 서버에 설정한 운영 자격으로 실행하며 구매·판매 로그인으로 대신하지 않습니다.

로그인 화면의 데이터 흐름은 `로그인·인증` 보기를 기본으로 제공합니다. 공개 로그인 요청은 Gateway에서 Commerce로 전달하고, Commerce가 Redis 실행 식별자·시도 제한, PostgreSQL 계정·비밀번호, Redis 가드·세션 저장을 확인합니다. 이후 보호 요청에서는 Gateway가 Redis 세션을 검증한 뒤 소유 서비스로 전달합니다. 보관 중인 브라우저 세션과 실제 HTTP 응답, 내부 설계 경로를 구분하며 토큰 원문을 다시 표시하거나 추가 조회하지 않습니다.

화면은 로그인 응답의 `expiresAt`에 맞춰 만료를 표시하고, 보호 사용자 요청의 `401`을 받으면 해당 역할·세션을 `재로그인 필요`로 바꿉니다. 입력·기록·계정 ID·미확정 원요청과 키는 유지합니다. 같은 계정으로 재로그인하면 원요청을 다시 채울 수 있습니다. 공개 로그인 실패·운영자 인증 실패·`403` 권한 부족·`503` 확인 불가는 기존 사용자 세션 만료로 처리하지 않습니다. 추가 인증 폴링·자동 로그인·자동 재전송은 하지 않습니다. 로그인 완료는 세션 발급 응답이며, 서버는 보호 요청마다 유효성을 확인합니다.

Kafka 상세에서는 Spring Boot 앱 안의 Relay·Consumer 코드와 앱 밖의 Kafka 브로커·PostgreSQL을 구분합니다. 토픽은 브로커 안에 저장됩니다. Relay의 스케줄러·실행 스레드는 해당 앱 안에서 동작하며, Payment의 발행·소비 코드는 별도 실행체인 Payment Worker 안에 있습니다. Payment API와 Worker는 같은 payment DB를 사용합니다.

주문·결제·반품·환불 등의 대상은 **목록 불러오기 → 항목 선택**으로 연결합니다. 이름·상태·금액·시각으로 구분하며 UUID 직접 입력은 고급 옵션입니다. 구매자의 기존 반품 요청과 금융 환불도 본인 목록에서 찾을 수 있습니다. 성공한 응답에서 다음 작업으로 이동하면 역할별 ID·자원별 버전을 이어 쓰며, 현재 폼과 미확정 요청을 미리 바꾸지 않습니다. 이 탭에서 확인한 구매자·판매자는 다른 계정의 전체 목록을 열지 않고 명시적으로 선택할 수 있습니다.

결과나 실행 기록에서 **데이터 흐름 보기**를 누르면 선택한 거래의 실제 이벤트 기록을 조회합니다. 목록에서는 선택한 행만 연결합니다. 거래가 없으면 내 주문 또는 실행 기록에서 먼저 선택하도록 안내하고, 직접 UUID를 요구하지 않습니다. 연결 후 `이 거래 흐름 확인`으로 다시 관측할 수 있습니다. Commerce·Payment·Settlement는 각자의 DB에서 Outbox·Inbox 메타데이터를 반환합니다. 각 서비스의 운영자 자격은 Demo Control에만 두며 구매자·판매자 세션을 운영 자격으로 사용하지 않습니다. 설정 조회 전, 자격 미설정, 서비스 조회 실패와 수신 기록 없음은 구분합니다.

같은 eventId의 발행 상태·토픽·파티션·offset과 소비 그룹별 수신·반영 상태를 나란히 대조합니다. 재발행하면 Outbox의 발행 위치와 Inbox의 최초 수신 위치가 달라질 수 있습니다. APPLIED는 이벤트 반영이며 금융 최종 성공을 뜻하지 않습니다. 이벤트별로 결과 ID의 종류를 확인할 수 있으면 주문·구매자 결제·환불 조회 또는 판매자 정산 목록으로 이동해 원래 권한으로 업무 결과를 확인합니다. 종류가 불명확한 결과와 환불 예약·알림 참조는 ID만 표시하며 결제나 정산 항목으로 단정하지 않습니다.

관측은 자동 폴링 없이 수동으로 실행합니다. UUID는 최대20개, 서비스별 한 번의 snapshot은 최대50개이며 잘림을 표시합니다. 발견한 eventId·업무 참조는 세 서비스에 병렬로 최대3라운드(9회) 조회하고, ID·라운드 상한 뒤 미조회 범위도 표시합니다. 실패한 소스는 같은 관측에서 재시도하지 않습니다. 서비스별 관측 시각이 다르며 Kafka의 현재 로그를 직접 읽거나 서비스 간 원자 snapshot을 제공하지 않습니다. API·DB의 읽기 트랜잭션 제한은3초이고 raw_body·업무 payload·계정·주소·인증 원문을 반환하지 않습니다. Consumer·Relay가 비활성이면 기록이 없거나 PENDING으로 남을 수 있으며 조회 버튼이 후속 처리를 실행하지는 않습니다.

로그인에서 `503 COMMON_TEMPORARILY_UNAVAILABLE`가 나오면 HTTP 상태만으로 Redis 실패를 확정하지 말고 해당 traceId의 서버 로그와 인증 설정을 확인하세요. 승인한 Redis 실행 식별자가 현재 인스턴스와 다른 경우에는 위 [Redis 인증 복구 절차](#redis-세션-검증과-복구)를 따릅니다. 실행 중인 Compose 프로젝트의 실제 작업 폴더·설정을 사용하고 Gateway·Commerce의 실행 식별자와 새 인증 세대를 함께 맞춥니다. 이 UI를 연 워크트리와 백엔드 실행 폴더가 같다고 가정하지 않습니다.

주문에서 `503 COMMON_CAPACITY_EXCEEDED`가 나오면 [금융 관측과 접수 복구](#금융-관측과-접수-복구)의 필수 관측·Kafka 초기 offset·대기 작업을 확인하세요. 기존 offset을 임의로 덮어쓰거나 접수 제한을 끄지 않습니다. 주문의 `201`과 재조회 성공, 재고 예약은 주문 접수 확인이며 `PAYMENT_PENDING`은 결제 완료가 아닙니다. 기본 비활성인 Relay·Consumer·Worker를 켜지 않은 환경에서는 후속 처리가 대기하고 신규 접수가 다시 닫힐 수 있습니다. 금융 왕복 시연은 [전용 실행 설정](#주문결제정산환불-왕복)을 별도로 준비합니다.

화면의 **데이터 흐름**은 선택한 요청의 중계·Gateway·소유 Spring Boot·DB 경계를 인프라 지도와 시간순 왕복으로 보여줍니다. 노드를 누르면 역할과 조건을 볼 수 있고, 실행 기록을 선택하면 해당 요청의 경로·보낸 값·받은 결과가 함께 바뀝니다. Kafka 상세는 OpenAPI의 이벤트 계약에서 생산자 Outbox·토픽·소비 그룹·소비자 Inbox를 연결합니다. 여러 소비 그룹이 있으면 따로 선택합니다. 이 도식은 내부 실행 추적이 아니며 DB 접근·이벤트 발행·소비 완료를 HTTP 응답으로 판정하지 않습니다. 실제 HTTP 상태·왕복 시간만 실행 기록의 관측값입니다. 설계 도식 자체는 추가 조회하지 않습니다. 실제 이벤트 기록은 위 수동 조회를 사용하고, 추적·브로커 상태는 기존 Zipkin·Kafka UI 링크에서 별도로 확인합니다. 자세한 경계는 [인프라 구조도와 비동기 실행](architecture.md), 이벤트별 조건은 [이벤트 연동](api.md#이벤트-연동)을 따릅니다.

로컬 개발은 Node.js 24 이상에서 Vite 프록시로 실행합니다. 기존 Gateway를 먼저 켜고 저장소 루트에서 다음을 실행합니다.

```bash
npm --prefix demo-ui ci
npm --prefix demo-ui run dev
```

[개발 UI](http://127.0.0.1:5173)를 엽니다. 일반 구매자·판매자 요청은 원래 HTTP 메서드·경로·본문·인증·멱등키를 유지해 Vite의 `/api` 프록시에서 기존 Gateway로 전달하며 Java 중계를 거치지 않습니다. Gateway 기본값은 `http://127.0.0.1:8080`입니다. 다른 주소는 실행할 셸의 `DEMO_GATEWAY_URL`로 지정합니다. Vite를 종료하려면 실행한 터미널에서 Ctrl+C를 누르고, 같은 명령으로 재실행합니다. 기존 DB·계정·컨테이너를 초기화하지 않습니다.

운영자 요청과 부하 제어만 `/demo`를 통해 Java 21 제어 서버를 사용합니다. 운영 자격은 브라우저에 보내지 않으며 해당 기능이 필요할 때 다음을 실행합니다. 제어 서버가 없어도 개발 UI의 일반 API는 사용할 수 있습니다.

제어 서버 연결에 실패한 뒤 서버를 켰다면 오류 안내의 `제어 서버 다시 연결`을 누릅니다. 현재 탭의 로그인·입력·실행 기록을 유지하면서 설정만 다시 조회하며 업무 요청을 자동 재전송하지 않습니다.

```bash
npm --prefix demo-ui run build
./gradlew :demo-control:bootJar
java -jar demo-control/build/libs/app.jar
```

기존 [통합 UI](http://127.0.0.1:4174)는 빌드된 화면과 Java 중계 경로를 그대로 제공합니다. 5173 개발 프록시 경로와 구분합니다. jar를 다시 빌드할 때는 실행 중인 제어 서버를 종료하고 `npm --prefix demo-ui run build`, 이어서 `:demo-control:bootJar`를 실행한 뒤 새 jar로 시작합니다. 서버 종료는 실행 터미널의 Ctrl+C를 사용합니다.

표시된 ‘응답 확인’ 시간은 브라우저의 요청 시작부터 프록시·Gateway·서비스 왕복, 응답 본문 수신과 JSON 해석까지입니다. 서버 내부 처리시간이나 비동기 결제·정산의 최종 완료시간이 아닙니다. 서버별 시간은 기존 메트릭·추적으로 따로 확인합니다. 변경 요청의 응답이 계속 오지 않으면 ‘응답 대기 중단’으로 조회 화면으로 이동할 수 있습니다. 멱등 요청은 중단 전에 원래 입력과 키를 보존하며, 이미 접수된 거래를 취소하지 않습니다.

49개 상세 흐름은 기존 `wireframes.html`에서 생성합니다. 각 영역의 실제 명세 입력·요청·응답·목록/금액/상태를 보여주며 탐색 항목 수를 업무 실행 검증 수로 해석하지 않습니다. 구매자·판매자의 세션·조회 캐시·실행 기록은 분리하고 토큰은 메모리에만 보관합니다. ‘로컬 세션 지우기’는 브라우저의 자격만 지우며 서버 로그아웃은 계정 화면의 실제 로그아웃 작업으로 실행합니다. 비밀번호·토큰은 요청/응답 기록에서 가립니다. 멱등 요청의 응답 유실·해석 실패·5xx에는 원래 입력과 키를 계정별 메모리에 보존합니다. 조회 화면을 다녀온 뒤 원요청을 다시 채울 수 있으며 자동 재전송하지 않습니다. 보존 중에는 다른 입력·키로 같은 작업을 보내지 않고, 성공 응답 또는 사용자의 결과 확인 후 보존을 해제합니다. 같은 계정의 재로그인에는 유지하고 다른 계정 로그인·로컬 세션 지우기에는 삭제합니다.

입력 아래의 목록 불러오기로 현재 역할이 조회할 수 있는 상품·이벤트·주소·쿠폰·주문 등을 선택합니다. 실제 응답의 ID와 같은 자원의 버전을 연결하며 목록은 20건씩 이동할 수 있습니다. 이름·가격·사유는 직접 정하고 일정은 브라우저 시간대의 달력으로 입력합니다. 주문은 구매 조건을 채운 뒤 ‘현재 입력으로 견적 조회’로 서버의 예상 금액·버전을 가져옵니다. 구매 조건을 바꾸면 예상 금액을 비워 재조회를 요구합니다. 조회 중 대상이나 입력이 바뀌면 이전 응답을 적용하지 않습니다. 목록이 비어 있으면 해당 역할에서 데이터를 먼저 등록해야 하며 구매자 전체 목록이나 금융 시험계정 자격을 만들어 제공하지 않습니다.

### 운영 조회와 명령 연결

운영 자격이 없으면 해당 작업은 사용할 수 없습니다. 제어 서버의 `DEMO_<COMMERCE|PAYMENT|SETTLEMENT|FINANCE_MOCK>_URL`과 `DEMO_<동일 서비스>_OPERATOR_CREDENTIAL`을 소유 서비스 설정과 맞춥니다. 기본 내부 목적지는 루프백 18081~18084입니다. 사용자 Bearer를 운영 자격으로 대신 사용하지 않습니다.

새 전용 스택에는 `compose.demo-ui.yml`을 적용할 수 있습니다. 서비스별로 다른 43자 이상 운영 자격을 비공개 환경에서 공급합니다. overlay는 해당 자격의 `operator:demo-ui`와 인증된 내부 HTTP용 루프백 포트만 추가합니다. 기존 사용자 확인용 `commerce-settle-demo`에는 적용하지 않습니다. 동시에 실행할 스택은 Gateway·Zipkin·Prometheus·관측 도구의 호스트 포트도 기존 환경과 겹치지 않게 선택하고 위 제어 서버 목적지와 함께 맞춥니다. 이 overlay가 금융 Worker·이벤트 연결을 켜거나 fixture를 적재하지는 않습니다. 기존 기능별 활성화 절차를 따릅니다.

이 overlay의 기동·재기동 검증은 동일한 전용 프로젝트와 Compose 파일을 유지해 실행합니다.

```bash
COMPOSE_PROJECT_NAME=commerce-settle-ui-demo COMPOSE_FILE=compose.yml:compose.demo-ui.yml python3 scripts/smoke.py --demo-ui
```

`--demo-ui`는 `commerce-settle-ui-`로 시작하는 별도 프로젝트에서만 네 서비스의 지정된 loopback HTTP 포트를 허용합니다. 각 소유자의 운영 자격으로 조회하고 비인증·다른 소유자 자격의 401을 확인합니다. Worker·DB·Redis는 계속 내부 전용입니다. 옵션 없는 기본 smoke의 내부 포트 제한은 그대로 유지합니다. CI의 `demo-ui` 통합 시나리오는 빈 볼륨 기동 뒤와 앱 재기동 뒤 이 smoke를 각각 실행합니다. 재기동은 소유 서비스 네 개가 준비된 뒤 호출자인 Gateway·Payment Worker도 다시 시작하는 순서이며 Redis·DB는 유지합니다. 백엔드만 교체하며 기존 호출자 연결을 유지하는 복구 시험은 이 시나리오 범위가 아닙니다.

모니터링 주소는 `DEMO_PROMETHEUS_URL`, `DEMO_ZIPKIN_URL`, `DEMO_GRAFANA_URL`, `DEMO_KAFKA_UI_URL`, `DEMO_ALERTMANAGER_URL`로 설정합니다. 기본값은 위 접속 표와 같고, 링크가 있다는 것이 도구의 기동을 뜻하지 않습니다. 모니터링의 수집·조회 시각은 실제 시간입니다.

월간 화면은 회차 집계와 `/internal/coupon-issue-runs/{runId}/targets`의 저장된 선정 순위·구매액·혜택·발급 결과를 조회합니다. 웰컴·월간 혜택에 임의 수동 발급 버튼을 추가하지 않습니다. **런타임 업무 시간 이동과 월간 배치 수동 실행은 제공하지 않습니다.** 배송·반품·정산·월간 발급은 실제 기한과 기존 처리기의 준비가 필요합니다. 금융 연동의 준비·검증 범위는 [왕복 검증 범위](#주문결제정산환불-왕복)를 따릅니다.

### 부하 실행과 중지

화면의 부하는 기존 `tests/load/payment_load.py ramp`를 실행합니다. 측정 범위는 **Kafka 직접 주입 → Payment 수신·금융 Mock 승인**이며 주문 API·선착순 경쟁·전체 정산의 부하 시험이 아닙니다. 기존 부하 도구의 [준비·측정·대조 계약](../tests/load/README.md)을 먼저 따릅니다.

전용 프로젝트는 `commerce-settle-ui-load` 또는 그 이름에 짧은 접미사를 붙인 값만 허용합니다. 동일한 `LOAD_PROJECT`와 절대경로 `LOAD_WORK_DIR=<저장소>/tests/load/work/demo-ui/<프로젝트>`로 CLI의 up/prepare/enable을 준비한 후 제어 서버에 `DEMO_LOAD_PROJECT`와 `DEMO_LOAD_ENABLED=true`를 공급합니다. 이 준비는 새 전용 환경에서만 수행하며 기존 개발/시연 프로젝트를 사용하지 않습니다. UI는 기동·DB 준비·Worker 재생성·삭제 명령을 제공하지 않습니다.

한 번에 한 실행, 1~100건/초·1~30초 주입·30초 배출 관측·전체 120초 상한입니다. 중지는 stop 파일로 **추가 주입만 멈추고** 이미 접수한 거래는 계속 관측합니다. `COMPLETED`는 도구의 정상 종료이며 금융 정합성 검증 완료가 아닙니다. summary와 요청 원장으로 기존 reconcile을 수행합니다. 결과는 해당 LOAD_WORK_DIR의 실행 디렉터리에 보존하며 제어 서버 재시작 후에도 파일을 삭제하지 않습니다. 메모리 실행 목록은 최대20건이고 재시작 시 비워집니다. 제어 서버의 정상 종료는 중지 파일 저장이 실패해도 대기 상한 뒤 소유한 실행 프로세스를 정리합니다. 전용 환경을 삭제할 때는 준비 때 사용한 `LOAD_PROJECT`와 `LOAD_WORK_DIR`를 유지하고 [부하 환경 종료](../tests/load/README.md#종료)를 따릅니다. 다른 부하·개발 프로젝트의 이름으로 바꾸지 않습니다.

부하 입력은 화면과 서버에서 범위를 검사합니다. 제어 API의 입력·JSON·경로 타입 오류는 필드별 사유를 포함한 `application/problem+json`으로 반환합니다. 상태 관측 실패 시의 주입 중지·원장 보존은 [부하 도구](../tests/load/README.md#실행)를 따릅니다. 프로세스 강제 종료나 호스트 장애까지 금융 처리 완료·원장 보존을 보장하지는 않습니다.

### UI 검증과 종료

```bash
npm --prefix demo-ui test
npm --prefix demo-ui run build
./gradlew :demo-control:test
./gradlew :commerce:test --tests '*GradeCouponIntegrationTest'
python3 -B -m unittest discover -s tests/load -p 'test_payment_load.py'
```

API/역할 격리·미확정·중복 요청·순위의 권한/페이지·중지 동작과 실제 화면을 확인합니다. UI의 경로·요청 필드는 빌드 시 OpenAPI에서 생성하므로 계약 변경 후 다시 생성·빌드합니다. 프론트 테스트·빌드는 CI 필수 결과에 포함합니다. 종료는 시작한 UI/Java 프로세스만 종료하며 연결된 기존 백엔드·DB·사용자 데모를 함께 종료하지 않습니다.
