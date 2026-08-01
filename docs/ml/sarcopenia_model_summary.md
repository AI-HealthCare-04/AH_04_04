# 근감소증 위험 모델 개요

## 목적

이 서비스는 KNHANES(국민건강영양조사) 기반 모델로 근감소증 **선별(screening) 확률**을 연속값으로 산출한다. 이 값은 내부 선별 신호이며 **의학적 진단이 아니다.** 사용자에게 보이는 문구와 종단 시각화는 API·클라이언트 계층이 담당한다.

## AWGS 2025 일수(days) 배포본

배포 아티팩트는 KNHANES 2022–2024의 65세 이상 참여자로 학습했다. 목표 라벨은 AWGS 2025의 BIA 정의를 따른다: **키 보정 저근육량 또는 BMI 보정 저근육량**과 **저악력**의 결합. 악력과 BIA 측정값은 목표 라벨을 정의할 뿐이며 **모델 입력이 아니다.**

활동량 입력은 주간 실천 여부의 이분형 플래그 대신 **일수**를 사용한다. 종단 추이 기능에서 점수가 걷기·근력 챌린지 성공 횟수에 반응할 수 있도록 하기 위해서다.

| 아티팩트 | model version | feature set | 사용 상황 |
| --- | --- | --- | --- |
| `sarcopenia_model_minimal.joblib` | `sarcopenia_lr_self_report_minimal_days_awgs2025_days_v3` | `self_report_minimal_days` | 허리둘레 없음 |
| `sarcopenia_model_with_waist.joblib` | `sarcopenia_lr_self_report_plus_waist_days_awgs2025_days_v3` | `self_report_plus_waist_days` | 허리둘레 있음 |

- 모델 계열: 비가중 로지스틱 회귀 파이프라인
- 확률 종류: `predict_proba` 원값(raw)
- 목표 라벨: `sarcopenia_awgs2025`
- 호환용 임계값: `0.20`
- 런타임: scikit-learn `1.6.1`

임계값은 기존 내부 등급(tier) 로직과의 호환을 위해 번들에 남아 있다. 시간에 따른 변화를 보여주는 제품 기능은 등급을 주 결과로 다루지 말고 **연속값 `risk_score`** 를 사용해야 한다. 현재 predictor 기준으로 `0.20`은 상위 등급 경계이고 호환용 중간 구간은 `0.10`에서 시작한다. 이 경계들은 연속 추이의 축척이 아니며, 그래프의 주 출력으로 의도된 값이 아니다.

## 서비스 런타임 입력

두 모델 모두 앱에서 수집 가능한 입력만 쓴다.

- `age`
- `sex`: KNHANES 코드 — 남성 `1`, 여성 `2`
- `height_cm`
- `weight_kg`
- `bmi`
- `waist_cm`: 선택 입력. 값이 있으면 허리 인지 모델이 선택된다.
- `walk_days`: 주당 30분 이상 걷기 일수(0–7)
- `musc_days`: 주당 근력운동 일수(0–5). 5는 "5일 이상"을 뜻한다.

## 연동 흐름

1. `HealthProfile`이 신체 계측값과 활동 일수 필드를 저장한다.
2. `features_from_health_profile()`이 이를 배포 피처 계약에 맞게 정규화한다.
3. `RiskPredictor.predict()`가 허리둘레 유무에 따라 아티팩트를 선택한다.
4. sklearn 파이프라인이 `risk_score`(연속 확률)와 번들 메타데이터를 반환한다.
5. 서비스가 점수·모델 버전·모델 변형·입력 스냅샷을 영속화한다.

## 종단 비교 호환성

추이 계산은 **같은 모델 버전 안에서만** 점수를 비교해야 한다. 모델 버전이 바뀌면, 새 버전의 첫 점수는 이전 버전과 이어 붙여 "건강 변화"로 해석하지 않고 **새로운 기준선**으로 삼는다.

## 검증과 한계

배포 검증 요약은 [`sarcopenia_validation_awgs2025_summary.md`](sarcopenia_validation_awgs2025_summary.md)를 참고한다.

라벨 코호트는 악력과 BIA를 동시에 측정한 참여자로 한정되므로 초고령 참여자가 과소대표될 수 있다. 이 모델은 65세 이상을 대상으로 하며, 그보다 젊은 사용자에 대한 결과는 별도 검증이 필요하다. 앱 사용자가 KNHANES 참여자와 다를 수 있으므로 운영 환경의 입력 분포 변화(drift)를 모니터링해야 한다.

### 재현성 범위

성능 재현 스크립트와 산출 지표는 저장소에 커밋돼 있다.

- `scripts/ml/evaluate_model.py` — 5-fold OOF 평가 및 비교 실험(`random_state=42`)
- `scripts/ml/metrics.json` / `metrics.csv` — 산출된 지표(헤드라인·fold별·신뢰구간·비교 실험)
- `docs/ml/ARTIFACT_HASHES.txt` — 배포 아티팩트 SHA-256 정본

다만 **학습 스크립트 원본과 KNHANES 파생 데이터는 배포 불가**라 모델 담당자의 별도 분석 작업공간에 남아 있다. 따라서 저장소만으로 보장되는 것은 **아티팩트 무결성(해시)과 지표 재현 경로**이며, 원자료로부터의 전체 학습 재현은 포함되지 않는다. 재현 스크립트를 실행하려면 KNHANES 전처리본이 별도로 필요하다.
