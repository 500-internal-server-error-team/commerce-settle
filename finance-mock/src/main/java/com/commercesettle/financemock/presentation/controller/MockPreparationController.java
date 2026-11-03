package com.commercesettle.financemock.presentation.controller;

import com.commercesettle.financemock.application.dto.MockAccountView;
import com.commercesettle.financemock.application.dto.MockScenarioView;
import com.commercesettle.financemock.application.service.MockPreparationService;
import com.commercesettle.financemock.domain.model.MockScenario;
import com.commercesettle.financemock.presentation.dto.CreateMockAccountRequest;
import com.commercesettle.financemock.presentation.dto.CreateMockScenarioRequest;
import com.commercesettle.internalauth.InternalCallerFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 자격을 검증한 운영자만 시험 데이터를 준비한다. Gateway에 공개하지 않는다. */
@RestController
@RequestMapping("/mock")
class MockPreparationController {
  private final MockPreparationService preparations;

  MockPreparationController(MockPreparationService preparations) {
    this.preparations = preparations;
  }

  @InitBinder
  void addScenarioValidation(WebDataBinder binder) {
    if (binder.getTarget() instanceof CreateMockScenarioRequest) {
      binder.addValidators(
          new Validator() {
            @Override
            public boolean supports(Class<?> type) {
              return type == CreateMockScenarioRequest.class;
            }

            @Override
            public void validate(Object target, Errors errors) {
              ((CreateMockScenarioRequest) target).validateRelations(errors);
            }
          });
    }
  }

  @Operation(operationId = "post_mock_accounts", summary = "격리 시험 계정 준비")
  @ApiResponse(responseCode = "201", description = "최초 생성 결과와 계정 조회 Location. 재요청도 원생성 결과를 반환한다.")
  @ApiResponse(responseCode = "409", description = "키·본문 충돌 또는 이미 등록한 계정 참조")
  @PostMapping("/accounts")
  ResponseEntity<MockAccountView> createAccount(
      @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER) String caller,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody CreateMockAccountRequest request) {
    var result =
        preparations.createAccount(
            caller,
            key,
            request.providerId(),
            request.accountRef(),
            request.purpose(),
            request.balance());
    return ResponseEntity.created(URI.create("/mock/accounts/" + result.id())).body(result);
  }

  @Operation(operationId = "get_mock_accounts_accountId", summary = "격리 시험 잔액 조회")
  @GetMapping("/accounts/{accountId}")
  MockAccountView findAccount(@PathVariable UUID accountId) {
    return preparations.findAccount(accountId);
  }

  @Operation(operationId = "post_mock_scenarios", summary = "새 버전 장애 설정")
  @ApiResponse(responseCode = "201", description = "불변 설정과 식별 Location. 시나리오 GET은 제공하지 않는다.")
  @ApiResponse(responseCode = "409", description = "키·본문 충돌 또는 이미 등록한 대역·버전")
  @PostMapping("/scenarios")
  ResponseEntity<MockScenarioView> createScenario(
      @RequestAttribute(InternalCallerFilter.VERIFIED_CALLER) String caller,
      @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String key,
      @Valid @RequestBody CreateMockScenarioRequest request) {
    var result =
        preparations.createScenario(
            caller,
            key,
            request.providerId(),
            request.scenarioVersion(),
            new MockScenario.Delay(request.minDelayMs(), request.maxDelayMs()),
            request.rejectionRate(),
            request.responseLossRate(),
            new MockScenario.Delay(request.lookupMinDelayMs(), request.lookupMaxDelayMs()),
            request.lookupFailureRate(),
            request.settlementDelayBusinessDays(),
            request.refundMinDelayMs(),
            request.refundMaxDelayMs(),
            request.refundRejectionRate(),
            request.refundResponseLossRate(),
            request.seed());
    return ResponseEntity.created(URI.create("/mock/scenarios/" + result.id())).body(result);
  }
}
