package com.commercesettle.commerce.presentation.dto;

import com.commercesettle.commerce.application.service.BuyerAddressService.StoredAddress;
import com.commercesettle.commerce.domain.repository.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "AddressPage", description = "기본 주소 먼저, 그다음 createdAt DESC, id DESC 순서")
public record AddressPageResponse(
    List<AddressResponse> items, int page, int size, long totalElements, int totalPages) {
  public static AddressPageResponse from(PageResult<StoredAddress> result) {
    return new AddressPageResponse(
        result.items().stream().map(AddressResponse::from).toList(),
        result.page(),
        result.size(),
        result.totalElements(),
        result.totalPages());
  }
}
