from fastapi import APIRouter

from app.apis.v1.activity_profile_routers import activity_profile_router
from app.apis.v1.auth_routers import auth_router
from app.apis.v1.daily_tip_routers import daily_tip_router
from app.apis.v1.dashboard_routers import dashboard_router
from app.apis.v1.health_check_routers import health_check_router
from app.apis.v1.health_profile_routers import health_profile_router
from app.apis.v1.mission_log_routers import mission_log_router
from app.apis.v1.mission_routers import mission_router
from app.apis.v1.physical_assessment_routers import physical_assessment_router
from app.apis.v1.risk_prediction_routers import risk_prediction_router
from app.apis.v1.sensor_routers import sensor_router
from app.apis.v1.support_routers import support_router
from app.apis.v1.terms_routers import terms_router
from app.apis.v1.user_routers import user_router

v1_routers = APIRouter(prefix="/api/v1")
v1_routers.include_router(auth_router)
v1_routers.include_router(user_router)
v1_routers.include_router(terms_router)
v1_routers.include_router(health_check_router)
v1_routers.include_router(health_profile_router)
v1_routers.include_router(physical_assessment_router)
v1_routers.include_router(risk_prediction_router)
v1_routers.include_router(activity_profile_router)
v1_routers.include_router(mission_router)
v1_routers.include_router(mission_log_router)
v1_routers.include_router(sensor_router)
v1_routers.include_router(dashboard_router)
v1_routers.include_router(support_router)
v1_routers.include_router(daily_tip_router)
