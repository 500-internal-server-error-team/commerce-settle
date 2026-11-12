package com.commercesettle.payment.presentation.controller;

import java.util.UUID;

/** Gateway가 세션을 검증하고 전달한 구매자 신원. */
public record AuthenticatedBuyer(UUID id) {}
