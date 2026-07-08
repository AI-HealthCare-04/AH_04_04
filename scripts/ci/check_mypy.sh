set -eo pipefail

COLOR_GREEN=$(tput setaf 2)
COLOR_BLUE=$(tput setaf 4)
COLOR_RED=$(tput setaf 1)
COLOR_NC=$(tput sgr0)

cd "$(dirname "$0")/../.."

echo "${COLOR_BLUE}Run Mypy${COLOR_NC}"
# --group app --group dev: 깨끗한 체크아웃에서도 fastapi/sqlalchemy 등(app 그룹)이 설치되어야
# mypy가 missing import 없이 타입 검사를 수행할 수 있습니다.
if ! uv run --group app --group dev mypy . ; then
  echo ""
  echo "${COLOR_RED}✖ Mypy found issues.${COLOR_NC}"
  echo "${COLOR_RED}→ Please fix the issues above manually and re-run the command.${COLOR_NC}"
  exit 1
fi

echo "${COLOR_GREEN}Successfully Ended.${COLOR_NC}"
