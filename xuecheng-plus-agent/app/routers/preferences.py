"""用户长期偏好读写接口。"""
from __future__ import annotations

from fastapi import APIRouter, Depends

import app.db as db
from app.auth import get_current_user
from app.schemas import PreferenceResponse, PreferenceUpdate

router = APIRouter(prefix="/api/preferences", tags=["preferences"])


@router.get("", response_model=PreferenceResponse)
def get_prefs(user_id: str = Depends(get_current_user)) -> PreferenceResponse:
    prefs = db.get_preferences(user_id)
    return PreferenceResponse(
        user_id=user_id,
        learning_stage=prefs.get("learning_stage"),
        explanation_style=prefs.get("explanation_style"),
        focus_directions=prefs.get("focus_directions"),
        extra=prefs.get("extra") or {},
    )


@router.put("", response_model=PreferenceResponse)
def update_prefs(
    update: PreferenceUpdate, user_id: str = Depends(get_current_user)
) -> PreferenceResponse:
    prefs = db.upsert_preferences(
        user_id,
        learning_stage=update.learning_stage,
        explanation_style=update.explanation_style,
        focus_directions=update.focus_directions,
        extra=update.extra,
    )
    return PreferenceResponse(
        user_id=user_id,
        learning_stage=prefs.get("learning_stage"),
        explanation_style=prefs.get("explanation_style"),
        focus_directions=prefs.get("focus_directions"),
        extra=prefs.get("extra") or {},
    )
