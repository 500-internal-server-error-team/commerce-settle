package com.commercesettle.commerce.presentation.controller;

import java.util.UUID;

/** Gateway가 전달한 구매자 신원. */
public record AuthenticatedBuyer(UUID id) {}
