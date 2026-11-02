package com.commercesettle.commerce.presentation.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.OffsetDateTime;

/** 시작 시각이 종료 시각보다 앞서야 한다. 위반은 endsAt 필드 오류로 알린다. null은 {@code @NotNull}이 검사한다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EventPeriod.Validator.class)
public @interface EventPeriod {
  String message() default "시작 시각보다 뒤여야 합니다.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** 기간을 가진 요청. */
  interface Period {
    OffsetDateTime startsAt();

    OffsetDateTime endsAt();
  }

  class Validator implements ConstraintValidator<EventPeriod, Period> {
    @Override
    public boolean isValid(Period value, ConstraintValidatorContext context) {
      if (value == null
          || value.startsAt() == null
          || value.endsAt() == null
          || value.startsAt().isBefore(value.endsAt())) {
        return true;
      }
      context.disableDefaultConstraintViolation();
      context
          .buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
          .addPropertyNode("endsAt")
          .addConstraintViolation();
      return false;
    }
  }
}
