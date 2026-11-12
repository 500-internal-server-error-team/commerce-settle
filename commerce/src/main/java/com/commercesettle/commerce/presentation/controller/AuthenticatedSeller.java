package com.commercesettle.commerce.presentation.controller;

import java.util.UUID;

/** Gateway가 검증해 전달한 판매자 신원. 컨트롤러 인자로 받으면 판매자 인증이 필요한 작업이 된다. */
public record AuthenticatedSeller(UUID id) {}
