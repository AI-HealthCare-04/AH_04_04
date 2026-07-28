package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.R

/**
 * 단백질 식사 챌린지의 7개 카테고리 정의.
 *
 * `id` 는 백엔드 계약(app/core/protein_categories.py PROTEIN_CATEGORY_IDS)과 **글자 그대로** 일치해야 한다.
 *   여기 값이 어긋나면 서버가 400(정의되지 않은 카테고리)으로 저장을 거부한다.
 * `label` 은 시니어가 한눈에 아는 우리말 이름, `imageRes` 는 res/drawable-nodpi 의 정사각 사진이다.
 *
 * 오늘 목표: 서로 다른 [PROTEIN_DAILY_GOAL] 종류 이상을 고르면 '오늘 단백질 챙김'으로 인정된다.
 */
data class ProteinCategory(
    val id: String,
    val label: String,
    val imageRes: Int,   // R.drawable.protein_cat_* (res/drawable-nodpi 정사각 사진)
)

/**
 * 서버 PROTEIN_DAILY_GOAL_COUNT 과 같은 값. 1종 이상 = 오늘 목표 달성.
 * 팀 결정(2026-07-28): '한 가지라도 챙겨 먹으면 성공' — 기존 3은 다른 챌린지와 혼동된 값(#227).
 */
const val PROTEIN_DAILY_GOAL: Int = 1

/**
 * 화면에 노출하는 순서. id 는 백엔드 7-id 와 동일:
 *   meat / fish / egg / soy / dairy / shellfish / nuts
 */
val PROTEIN_CATEGORIES: List<ProteinCategory> = listOf(
    ProteinCategory("meat", "고기", R.drawable.protein_cat_meat),
    ProteinCategory("fish", "생선", R.drawable.protein_cat_fish),
    ProteinCategory("egg", "달걀", R.drawable.protein_cat_egg),
    ProteinCategory("soy", "콩·두부", R.drawable.protein_cat_soy),
    ProteinCategory("dairy", "우유·유제품", R.drawable.protein_cat_dairy),
    ProteinCategory("shellfish", "조개·새우", R.drawable.protein_cat_shellfish),
    ProteinCategory("nuts", "견과류", R.drawable.protein_cat_nuts),
)
