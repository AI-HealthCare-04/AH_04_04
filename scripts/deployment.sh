#!/bin/bash
set -eo pipefail

COLOR_GREEN=$(tput setaf 2)
COLOR_BLUE=$(tput setaf 4)
COLOR_RED=$(tput setaf 1)
COLOR_NC=$(tput sgr0)

cd "$(dirname "$0")/.."

if [[ ! -f envs/.prod.env ]]; then
  echo "${COLOR_RED}envs/.prod.env 파일이 없습니다. envs/example.prod.env를 복사해 먼저 설정하세요.${COLOR_NC}"
  exit 1
fi

source ./envs/.prod.env

echo "${COLOR_BLUE}Docker Hub 계정 정보를 입력하세요.${COLOR_NC}"
read -r -p "username: " docker_user
read -r -s -p "password or PAT: " docker_pw
echo ""

echo "${COLOR_BLUE}Docker login${COLOR_NC}"
if ! docker login -u "${docker_user}" -p "${docker_pw}"; then
  echo "${COLOR_RED}Docker login에 실패했습니다.${COLOR_NC}"
  exit 1
fi

read -r -p "Docker repository name (${DOCKER_REPOSITORY:-senior-challenge}): " docker_repo
docker_repo=${docker_repo:-${DOCKER_REPOSITORY:-senior-challenge}}

read -r -p "FastAPI app version (${APP_VERSION:-v1.0.0}): " app_version
app_version=${app_version:-${APP_VERSION:-v1.0.0}}

echo "${COLOR_BLUE}FastAPI Docker image build${COLOR_NC}"
docker build --platform linux/amd64 -t "${docker_user}/${docker_repo}:app-${app_version}" -f app/Dockerfile .

echo "${COLOR_BLUE}FastAPI Docker image push${COLOR_NC}"
docker push "${docker_user}/${docker_repo}:app-${app_version}"

echo "${COLOR_GREEN}Image pushed: ${docker_user}/${docker_repo}:app-${app_version}${COLOR_NC}"

read -r -p "SSH key file name in ~/.ssh (ex. ai_health_key.pem): " ssh_key_file
read -r -p "EC2 IP: " ec2_ip

echo "${COLOR_BLUE}Protocol 선택${COLOR_NC}"
echo "1) http"
echo "2) https"
read -r -p "choice (1 or 2): " protocol_choice

ssh_key_path="${HOME}/.ssh/${ssh_key_file}"
chmod 400 "${ssh_key_path}"

tmp_nginx_conf=$(mktemp)
if [[ "${protocol_choice}" == "2" ]]; then
  read -r -p "Domain (ex. api.example.com): " domain
  sed "s/server_name .*/server_name ${domain};/g; s|/etc/letsencrypt/live/[^/]*|/etc/letsencrypt/live/${domain}|g" \
    infra/nginx/prod_https.conf > "${tmp_nginx_conf}"
else
  sed "s/server_name .*/server_name ${ec2_ip};/g" infra/nginx/prod_http.conf > "${tmp_nginx_conf}"
fi

echo "${COLOR_BLUE}Upload deployment files${COLOR_NC}"
ssh -i "${ssh_key_path}" "ubuntu@${ec2_ip}" "mkdir -p ~/project/infra/docker ~/project/infra/nginx"
scp -i "${ssh_key_path}" envs/.prod.env "ubuntu@${ec2_ip}:~/project/.env"
scp -i "${ssh_key_path}" infra/docker/docker-compose.prod.yml "ubuntu@${ec2_ip}:~/project/infra/docker/docker-compose.prod.yml"
scp -i "${ssh_key_path}" "${tmp_nginx_conf}" "ubuntu@${ec2_ip}:~/project/infra/nginx/default.conf"
rm -f "${tmp_nginx_conf}"

echo "${COLOR_BLUE}Deploy on EC2${COLOR_NC}"
ssh -i "${ssh_key_path}" "ubuntu@${ec2_ip}" \
  "DOCKER_USERNAME='${docker_user}' \
   DOCKER_PAT='${docker_pw}' \
   DOCKER_USER='${docker_user}' \
   DOCKER_REPOSITORY='${docker_repo}' \
   APP_VERSION='${app_version}' \
   bash -s" << 'EOF'
  set -e
  cd ~/project

  docker login -u "$DOCKER_USERNAME" -p "$DOCKER_PAT"
  export DOCKER_USER DOCKER_REPOSITORY APP_VERSION

  # --env-file .env: compose 변수 보간(${DB_PASSWORD} 등)이 .env를 읽게 한다.
  #   (없으면 MySQL이 compose 기본값으로 초기화돼, .env의 강한 비번을 쓰는 앱과 불일치 → Access denied)
  COMPOSE="docker compose --env-file .env -f infra/docker/docker-compose.prod.yml"
  $COMPOSE pull fastapi

  # 마이그레이션은 앱 기동 "전"에, 일회성 컨테이너로 끝낸다.
  #   앱을 먼저 띄우면 새 코드가 구 스키마로 시작 못 해 재시작 루프에 빠지고,
  #   그러면 exec 자체가 실패해 스키마를 올릴 방법이 없다. 실패한 배포가 노출되는 문제도 있다.
  # 1) DB만 healthy 될 때까지 대기
  $COMPOSE up -d --wait mysql
  # 2) DB 스키마 최신화 (Dockerfile이 uvicorn만 실행하므로 배포 시 명시적으로 마이그레이션).
  #    --no-deps: 위에서 이미 띄운 mysql을 다시 건드리지 않는다.
  $COMPOSE run --rm --no-deps fastapi uv run --no-sync alembic upgrade head
  # 3) 마이그레이션 성공 후에만 전체 서비스 기동 (실패 시 set -e로 여기서 중단)
  $COMPOSE up -d
  docker image prune -af
EOF

echo "${COLOR_GREEN}Deployment finished.${COLOR_NC}"
