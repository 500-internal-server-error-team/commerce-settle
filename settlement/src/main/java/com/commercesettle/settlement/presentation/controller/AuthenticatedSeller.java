package com.commercesettle.settlement.presentation.controller;

import java.util.UUID;

/** Gateway가 검증해 보낸 판매자 신원. */
public record AuthenticatedSeller(UUID id) {}
