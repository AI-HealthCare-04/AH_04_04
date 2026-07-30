# 약관 문서 (초안)

> ⚠️ 여기 문서들은 **법무 검토 전 초안(Draft)**입니다. 데모/리뷰용이며, 실제 출시 전 반드시 법률 전문가의 검토·수정이 필요합니다. 특히 개인정보/민감정보(건강정보) 관련 문서는 「개인정보 보호법」 요건을 충족해야 합니다.

## 문서 목록
| 파일 | 약관 종류(`terms_type`) | 버전 | 필수 | 연결 config(env) |
|---|---|---|---|---|
| `service-1.0.md` | `service` | 1.0 | 필수 | `TERMS_SERVICE_URL` |
| `privacy-1.0.md` | `privacy` | 1.0 | 필수 | `TERMS_PRIVACY_URL` |
| `sensitive-health-1.0.md` | `sensitive_health` | 1.0 | 필수 | `TERMS_SENSITIVE_HEALTH_URL` |
| `marketing-1.0.md` | `marketing` | 1.0 | 선택 | `TERMS_MARKETING_URL` |

- `terms_type`/`version`/필수 여부는 `app/core/terms_catalog.py`(정적 카탈로그)와 일치해야 하며, `GET /api/v1/terms` 응답의 출처입니다.

## 백엔드와의 관계
- 백엔드는 약관 **전문(내용)을 저장·서빙하지 않습니다.** `GET /terms`는 `{terms_type, version, title, url}`만 응답합니다.
- 앱/웹은 그 `url`을 열어 **호스팅된 문서**를 보여줍니다.

## 실제 반영 순서 (자체 호스팅 — #244 원천화)
1. 이 초안을 검토·수정 → 확정. (문안의 단일 원천은 이 디렉터리의 md 파일이다)
2. `uv run --no-sync python -m scripts.publish_terms` 로 `build/terms/*.html` 생성.
3. 산출물을 서버 `/opt/ah0404/media/terms/` 에 업로드(스크립트 출력의 scp 예시 참고).
   nginx 가 `https://aigo-health.duckdns.org/terms/<파일명(확장자 없이)>` 로 서빙한다
   (`infra/nginx/default.conf` 의 `/terms/` location — 반영 절차는 그 파일 상단 주석).
   업로드 후 **4개 버전 URL 이 전부 200 + UTF-8 로 열리는지** 확인하고 기록한다(#268 리뷰):
   ```bash
   for t in service-1.0 privacy-1.0 sensitive-health-1.0 marketing-1.0; do
     curl -fsSI https://aigo-health.duckdns.org/terms/$t | grep -iE '^(HTTP|content-type)'
   done   # 기대: 각각 200 / content-type: text/html; charset=utf-8
   ```
   본문이 깨지지 않았는지는 게시 전 `app/tests/test_publish_terms.py` 가 잡는다(표·목록·인용이
   원시 Markdown 기호로 새면 실패). 브라우저에서 privacy 의 표가 표로 보이는지도 한 번 눈으로 확인.
4. 실제 URL 을 **배포 환경변수**(`TERMS_*_URL`) 3곳에 주입: GitHub secret `PROD_ENV`(자동 배포 원천),
   `envs/.prod.env`(수동 배포), 서버 `~/project/.env`(즉시 반영 시) → fastapi 재기동.
5. 약관 내용/버전이 바뀌면: md 파일명·이 README·`terms_catalog.py` 의 `version` 을 갱신하고
   2~4 를 반복한다. (URL 이 버전 포함이라 옛 버전 문서도 그대로 남는다)
