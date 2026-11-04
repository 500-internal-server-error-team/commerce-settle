package com.commercesettle.payment.presentation.controller;

import com.commercesettle.payment.application.service.PaymentIntegrityService;
import com.commercesettle.payment.presentation.dto.PaymentIntegrityResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentIntegrityController {
  private final PaymentIntegrityService service;
  private final ObjectMapper mapper;

  public PaymentIntegrityController(PaymentIntegrityService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @GetMapping("/internal/payment-reconciliation-checks")
  public PaymentIntegrityResponse checks(
      @RequestParam OffsetDateTime cutoffAt,
      @RequestParam(required = false) OffsetDateTime snapshotAt,
      @RequestParam(required = false) @Size(min = 1, max = 2048) String cursor,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size,
      @RequestParam(defaultValue = "PAYMENT") String direction) {
    return mapper.convertValue(
        service.read(
            cutoffAt.toInstant(),
            snapshotAt == null ? null : snapshotAt.toInstant(),
            cursor,
            size,
            direction),
        PaymentIntegrityResponse.class);
  }
}
