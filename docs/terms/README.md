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

## 실제 반영 순서
1. 이 초안을 법무 검토 → 확정.
2. 확정 문서를 웹에 **호스팅**(예: 사내 페이지, GitHub Pages, 문서 호스팅 등).
3. 각 문서의 실제 URL을 **배포 환경변수**(`TERMS_*_URL`)로 주입.
4. 약관 내용/버전이 바뀌면 이 파일과 `terms_catalog.py`의 `version`을 갱신하고 재배포.
