package com.commercesettle.commerce.presentation.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 정수 값이 0이 아니어야 한다. null은 {@code @NotNull}이 검사한다. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NonZero.Validator.class)
public @interface NonZero {
  String message() default "0이 아니어야 합니다.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<NonZero, Long> {
    @Override
    public boolean isValid(Long value, ConstraintValidatorContext context) {
      return value == null || value != 0;
    }
  }
}
