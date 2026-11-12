package com.commercesettle.commerce.presentation.controller;

import com.commercesettle.commerce.application.service.BuyerAddressService;
import com.commercesettle.commerce.application.service.BuyerAddressService.StoredAddress;
import com.commercesettle.commerce.presentation.dto.AddressPageResponse;
import com.commercesettle.commerce.presentation.dto.AddressResponse;
import com.commercesettle.commerce.presentation.dto.CreateAddressRequest;
import com.commercesettle.commerce.presentation.dto.UpdateAddressRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Buyer addresses", description = "구매자 본인 주소록")
@RestController
@RequestMapping("/api/buyers/me/addresses")
public class BuyerAddressController {
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final BuyerAddressService addresses;

  public BuyerAddressController(BuyerAddressService addresses) {
    this.addresses = addresses;
  }

  @Operation(
      operationId = "post_api_buyers_me_addresses",
      summary = "주소 등록",
      description = "첫 주소는 기본 주소가 된다. isDefault=true면 기존 기본을 해제한다. 같은 키·본문의 재요청은 기존 주소를 반환한다.")
  @PostMapping
  public ResponseEntity<AddressResponse> register(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody CreateAddressRequest request) {
    StoredAddress address =
        addresses.register(buyer.id(), idempotencyKey, request.shipping(), request.isDefault());
    return ResponseEntity.created(URI.create("/api/buyers/me/addresses/" + address.id()))
        .body(AddressResponse.from(address));
  }

  @Operation(operationId = "get_api_buyers_me_addresses", summary = "주소 목록")
  @GetMapping
  public AddressPageResponse list(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return AddressPageResponse.from(addresses.addresses(buyer.id(), page, size));
  }

  @Operation(
      operationId = "put_api_buyers_me_addresses_addressId",
      summary = "주소 변경",
      description =
          "expectedVersion이 현재 버전과 다르면 409이다. 이미 접수한 주문의 배송지 사본은 바뀌지 않는다. 타인·삭제 주소는 404이다.")
  @PutMapping("/{addressId}")
  public AddressResponse change(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @PathVariable UUID addressId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @Valid @RequestBody UpdateAddressRequest request) {
    return AddressResponse.from(
        addresses.change(
            buyer.id(),
            addressId,
            idempotencyKey,
            request.shipping(),
            request.isDefault(),
            request.expectedVersion()));
  }

  @Operation(
      operationId = "delete_api_buyers_me_addresses_addressId",
      summary = "주소 논리삭제",
      description = "기본 주소도 삭제할 수 있으며 다른 주소에 기본을 넘기지 않는다.")
  @DeleteMapping("/{addressId}")
  public ResponseEntity<Void> delete(
      @Parameter(hidden = true) AuthenticatedBuyer buyer,
      @PathVariable UUID addressId,
      @RequestHeader(IDEMPOTENCY_KEY) @Size(min = 8, max = 128) String idempotencyKey,
      @RequestParam @Min(0) long expectedVersion) {
    addresses.delete(buyer.id(), addressId, idempotencyKey, expectedVersion);
    return ResponseEntity.noContent().build();
  }
}
