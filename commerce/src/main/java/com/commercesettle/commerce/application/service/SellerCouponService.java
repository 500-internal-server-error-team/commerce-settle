package com.commercesettle.commerce.application.service;

import com.commercesettle.commerce.application.service.CouponService.CouponView;
import com.commercesettle.commerce.domain.exception.CouponCampaignConflictException;
import com.commercesettle.commerce.domain.exception.CouponCampaignConflictException.Reason;
import com.commercesettle.commerce.domain.exception.CouponRecipientNotFoundException;
import com.commercesettle.commerce.domain.exception.CouponTemplateNotFoundException;
import com.commercesettle.commerce.domain.exception.ProductNotFoundException;
import com.commercesettle.commerce.domain.model.Account;
import com.commercesettle.commerce.domain.model.ActorKind;
import com.commercesettle.commerce.domain.model.BuyerCoupon;
import com.commercesettle.commerce.domain.model.CouponTemplate;
import com.commercesettle.commerce.domain.model.Order;
import com.commercesettle.commerce.domain.repository.AccountRepository;
import com.commercesettle.commerce.domain.repository.BuyerCouponRepository;
import com.commercesettle.commerce.domain.repository.CouponHistoryRepository;
import com.commercesettle.commerce.domain.repository.CouponTemplateRepository;
import com.commercesettle.commerce.domain.repository.PageResult;
import com.commercesettle.commerce.domain.repository.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 쿠폰 캠페인의 등록·조회·발급 종료와 구매자 발급. 판매자 작업은 캠페인·상품 소유자를 대조하며 타인 캠페인은 없는 캠페인과 같게 취급한다. 쓰기는 멱등 기록과 업무
 * 변경을 한 트랜잭션에 저장한다. 발급은 캠페인 행 잠금으로 종료와 직렬화하고 조건부 증감 한 번으로 발급 수·예산을 확보하며, 같은 트랜잭션에서 쿠폰과 이력을 저장한다.
 */
@Service
public class SellerCouponService {
  private static final String SELLER = "SELLER";

  private final CouponTemplateRepository templates;
  private final BuyerCouponRepository coupons;
  private final CouponHistoryRepository histories;
  private final ProductRepository products;
  private final AccountRepository accounts;
  private final IdempotentRequests idempotency;
  private final Clock clock;

  public SellerCouponService(
      CouponTemplateRepository templates,
      BuyerCouponRepository coupons,
      CouponHistoryRepository histories,
      ProductRepository products,
      AccountRepository accounts,
      IdempotentRequests idempotency,
      Clock clock) {
    this.templates = templates;
    this.coupons = coupons;
    this.histories = histories;
    this.products = products;
    this.accounts = accounts;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** 등록 요청의 캠페인 조건. */
  public record Terms(
      UUID productId,
      String name,
      long discountAmount,
      long minOrderAmount,
      int issuanceLimit,
      Instant validFrom,
      Instant validUntil,
      long totalBudget,
      long minSellerNetAmount,
      Set<Order.ExternalMethod> allowedMethods) {}

  @Transactional
  public CouponTemplate register(UUID sellerId, String idempotencyKey, Terms terms) {
    UUID templateId = UUID.randomUUID();
    List<Object> body = new ArrayList<>();
    body.add(terms.productId());
    body.add(terms.name());
    body.add(terms.discountAmount());
    body.add(terms.minOrderAmount());
    body.add(terms.issuanceLimit());
    body.add(terms.validFrom());
    body.add(terms.validUntil());
    body.add(terms.totalBudget());
    body.add(terms.minSellerNetAmount());
    body.add(terms.allowedMethods().stream().map(Enum::name).sorted().toList());
    return idempotency.execute(
        sellerRequest(sellerId, "COUPON_TEMPLATE_REGISTER", idempotencyKey, body),
        templateId,
        201,
        recordId -> {
          if (terms.productId() != null) {
            products
                .findById(terms.productId())
                .filter(product -> !product.isDeleted() && product.isOwnedBy(sellerId))
                .orElseThrow(() -> new ProductNotFoundException(terms.productId()));
          }
          return templates.save(
              CouponTemplate.seller(
                  templateId,
                  sellerId,
                  terms.productId(),
                  terms.name(),
                  terms.discountAmount(),
                  terms.minOrderAmount(),
                  terms.issuanceLimit(),
                  terms.validFrom(),
                  terms.validUntil(),
                  terms.totalBudget(),
                  terms.minSellerNetAmount(),
                  terms.allowedMethods(),
                  clock.instant()));
        },
        existingId -> owned(sellerId, existingId));
  }

  @Transactional(readOnly = true)
  public PageResult<CouponTemplate> campaigns(UUID sellerId, int page, int size) {
    return templates.findSellerCampaigns(sellerId, page, size);
  }

  @Transactional(readOnly = true)
  public CouponTemplate campaign(UUID sellerId, UUID templateId) {
    return owned(sellerId, templateId);
  }

  /** 신규 발급을 종료한다. 이미 발급한 쿠폰의 조건·기한은 그대로다. */
  @Transactional
  public CouponTemplate close(
      UUID sellerId, UUID templateId, String idempotencyKey, long expectedVersion) {
    return idempotency.execute(
        sellerRequest(
            sellerId,
            "COUPON_TEMPLATE_CLOSE",
            idempotencyKey,
            List.of(templateId, expectedVersion)),
        templateId,
        200,
        recordId -> {
          CouponTemplate template = ownedForUpdate(sellerId, templateId);
          template.close(expectedVersion, clock.instant());
          return templates.save(template);
        },
        existingId -> owned(sellerId, existingId));
  }

  /**
   * 등록된 활성 구매자에게 한 장 발급한다. 같은 캠페인·구매자의 새 요청은 이미 발급됨, 발급 수·예산 소진은 소진으로 거절한다. 같은 키·본문의 재요청은 기존 쿠폰을
   * 돌려준다.
   */
  @Transactional
  public CouponView issue(UUID sellerId, UUID templateId, String idempotencyKey, UUID buyerId) {
    UUID couponId = UUID.randomUUID();
    return idempotency.execute(
        sellerRequest(sellerId, "COUPON_ISSUE", idempotencyKey, List.of(templateId, buyerId)),
        couponId,
        201,
        recordId -> {
          CouponTemplate template = ownedForUpdate(sellerId, templateId);
          Instant now = clock.instant();
          template.requireIssuableAt(now);
          accounts
              .findById(ActorKind.BUYER, buyerId)
              .filter(Account::canLogIn)
              .orElseThrow(() -> new CouponRecipientNotFoundException(buyerId));
          if (coupons.existsByTemplateAndBuyer(templateId, buyerId)) {
            throw new CouponCampaignConflictException(Reason.ALREADY_ISSUED);
          }
          if (!templates.takeSellerIssue(templateId, template.getDiscountAmount())) {
            throw new CouponCampaignConflictException(Reason.EXHAUSTED);
          }
          BuyerCoupon.Issued issued = BuyerCoupon.issueSeller(couponId, template, buyerId, now);
          try {
            coupons.save(issued.coupon());
          } catch (DataIntegrityViolationException e) {
            throw new CouponCampaignConflictException(Reason.ALREADY_ISSUED);
          }
          histories.save(issued.history());
          return view(issued.coupon(), template, now);
        },
        existingId -> {
          BuyerCoupon coupon =
              coupons
                  .findById(existingId)
                  .filter(value -> value.getTemplateId().equals(templateId))
                  .orElseThrow(() -> new CouponTemplateNotFoundException(templateId));
          return view(coupon, owned(sellerId, templateId), clock.instant());
        });
  }

  private static CouponView view(BuyerCoupon coupon, CouponTemplate template, Instant now) {
    return new CouponView(coupon, template.getName(), coupon.statusAt(now));
  }

  /** 발급과 종료가 같은 행 잠금 안에서 최신 상태를 판정한다. */
  private CouponTemplate ownedForUpdate(UUID sellerId, UUID templateId) {
    return templates
        .findByIdForUpdate(templateId)
        .filter(template -> template.isSellerCampaignOf(sellerId))
        .orElseThrow(() -> new CouponTemplateNotFoundException(templateId));
  }

  private CouponTemplate owned(UUID sellerId, UUID templateId) {
    return templates
        .findById(templateId)
        .filter(template -> template.isSellerCampaignOf(sellerId))
        .orElseThrow(() -> new CouponTemplateNotFoundException(templateId));
  }

  private static IdempotentRequests.Request sellerRequest(
      UUID sellerId, String operation, String idempotencyKey, List<?> body) {
    return new IdempotentRequests.Request(
        SELLER, sellerId.toString(), operation, idempotencyKey, body);
  }
}
