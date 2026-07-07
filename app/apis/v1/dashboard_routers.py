from fastapi import APIRouter, status

dashboard_router = APIRouter(tags=["dashboard"])


@dashboard_router.get("/home", status_code=status.HTTP_200_OK)
async def get_home() -> dict[str, dict | None]:
    return {
        "user": {"nickname": "회원님"},
        "point_balance": {"current_points": 0},
        "activity_profile": None,
        "latest_prediction": None,
        "today_summary": {"counted_mission_count": 0, "daily_result": "none"},
        "available_mission_summary": {"meal": 0, "exercise": 0, "walking": 0, "game": 0},
    }


@dashboard_router.get("/dashboard/stamps", status_code=status.HTTP_200_OK)
async def get_stamps(month: str) -> dict:
    return {"month": month, "days": []}


@dashboard_router.get("/dashboard/summary", status_code=status.HTTP_200_OK)
async def get_dashboard_summary(days: int = 14) -> dict:
    return {"range_days": days, "activity_trend": [], "lifestyle_records": {}, "risk_change": []}


@dashboard_router.get("/users/me/points", status_code=status.HTTP_200_OK)
async def get_points() -> dict:
    return {"current_points": 0, "earn_logs": [], "spend_logs": []}
