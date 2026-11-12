package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.domain.model.ActorKind;
import java.util.UUID;

/** Gateway가 세션을 검증하고 전달한 계정 종류·ID. */
record AuthenticatedActor(ActorKind kind, UUID id) {}
