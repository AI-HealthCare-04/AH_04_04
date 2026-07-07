from fastapi import APIRouter, status

physical_assessment_router = APIRouter(prefix="/physical-assessments", tags=["physical-assessments"])


@physical_assessment_router.post("", status_code=status.HTTP_201_CREATED)
async def create_physical_assessment() -> dict[str, str]:
    return {"detail": "physical assessment scaffold"}
