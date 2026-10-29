package com.commercesettle.commerce.application.port;

import com.commercesettle.commerce.domain.model.SealedShippingAddress;
import com.commercesettle.commerce.domain.model.ShippingAddress;
import java.util.UUID;

/** 주소록 배송 정보의 암호화. 암호문은 주소 ID에 묶여 다른 주소 행으로 옮기면 열리지 않는다. */
public interface AddressCipher {
  SealedShippingAddress seal(UUID addressId, ShippingAddress address);

  ShippingAddress open(UUID addressId, SealedShippingAddress sealed);
}
