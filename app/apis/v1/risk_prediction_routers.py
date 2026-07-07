from fastapi import APIRouter, status

risk_prediction_router = APIRouter(prefix="/risk-predictions", tags=["risk-predictions"])


@risk_prediction_router.get("/me/latest", status_code=status.HTTP_200_OK)
async def get_latest_risk_prediction() -> dict[str, str]:
    return {"detail": "latest risk prediction scaffold"}


@risk_prediction_router.post("", status_code=status.HTTP_201_CREATED)
async def create_risk_prediction() -> dict[str, str]:
    return {"detail": "risk prediction scaffold"}


@risk_prediction_router.post("/reassess", status_code=status.HTTP_201_CREATED)
async def reassess_risk_prediction() -> dict[str, str]:
    return {"detail": "risk reassessment scaffold"}
