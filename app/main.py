# FastAPI 앱을 만들고, 라우터와 '공통 예외 처리'를 등록하는 최상위 진입점 파일입니다.

# Request : 예외 핸들러가 넘겨받는 '들어온 요청' 객체 (여기선 직접 쓰진 않지만 시그니처상 필요)
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import ORJSONResponse

# Starlette의 HTTPException을 가져옵니다.
# 중요: FastAPI의 HTTPException은 이 Starlette HTTPException을 상속(자식)합니다.
# 따라서 부모인 이 예외에 핸들러를 걸면, FastAPI에서 우리가 던지는 HTTPException까지 전부 잡힙니다.
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.apis.v1 import v1_routers

app = FastAPI(
    default_response_class=ORJSONResponse,
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    openapi_url="/api/openapi.json",
)


# -------------------------------------------------------------------------------------
# 공통 예외 핸들러: 모든 HTTPException의 응답 포맷을 API 명세서 규격으로 통일합니다.
# FastAPI 기본값은 {"detail": "..."} 이지만, 우리 명세서는 {"error_detail": "..."} 를 씁니다.
# 이 핸들러가 그 변환을 앱 전체(auth/user/terms ...)에 일괄 적용합니다.
# -------------------------------------------------------------------------------------
@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> ORJSONResponse:
    return ORJSONResponse(
        # status_code : 원래 예외가 가진 상태코드(401, 400, 409 ...)를 그대로 유지합니다.
        status_code=exc.status_code,
        # content     : 본문을 명세서 규격 {"error_detail": 메시지} 로 바꿉니다.
        content={"error_detail": exc.detail},
        # headers      : 인증 실패(401) 시 붙는 WWW-Authenticate 같은 헤더를 잃지 않도록 그대로 전달합니다.
        headers=getattr(exc, "headers", None),
    )


# -------------------------------------------------------------------------------------
# 요청 검증 실패(RequestValidationError)도 {"error_detail": ...} 규격으로 통일합니다.
# FastAPI 기본값은 {"detail": [ {loc, msg, type}, ... ]} (422)이지만, 우리 명세는 error_detail.
# 상태코드는 검증 실패 표준인 422를 유지하고, 문제 필드를 함께 안내합니다.
# -------------------------------------------------------------------------------------
@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> ORJSONResponse:
    # 각 오류 위치의 마지막 요소(필드명)를 모아 어떤 필드가 문제인지 알려준다.
    fields = ", ".join(str(error["loc"][-1]) for error in exc.errors() if error.get("loc"))
    detail = f"입력값이 올바르지 않습니다. (필드: {fields})" if fields else "입력값이 올바르지 않습니다."
    return ORJSONResponse(status_code=422, content={"error_detail": detail})


app.include_router(v1_routers)


@app.get("/health", tags=["system"])
async def health_check() -> dict[str, str]:
    return {"status": "ok"}
