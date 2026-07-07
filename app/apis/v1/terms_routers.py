from fastapi import APIRouter, status

terms_router = APIRouter(tags=["terms"])


@terms_router.get("/terms", status_code=status.HTTP_200_OK)
async def get_terms() -> dict[str, list[dict[str, str | bool]]]:
    return {
        "terms": [
            {"terms_type": "service", "version": "v1", "is_required": True},
            {"terms_type": "privacy", "version": "v1", "is_required": True},
        ]
    }


@terms_router.post("/users/me/agreements", status_code=status.HTTP_201_CREATED)
async def agree_terms() -> dict[str, str]:
    return {"detail": "terms agreement scaffold"}
