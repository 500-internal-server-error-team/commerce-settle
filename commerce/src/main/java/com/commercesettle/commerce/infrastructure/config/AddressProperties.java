package com.commercesettle.commerce.infrastructure.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 주소록 설정. 값이 없거나 잘못되면 기동하지 않는다.
 *
 * @param encryptionKey 주소록 배송 정보 암호문의 서버 키. 바꾸면 저장된 주소를 열 수 없다.
 */
@Validated
@ConfigurationProperties("app.address")
public record AddressProperties(@NotNull @Size(min = 32) String encryptionKey) {}
