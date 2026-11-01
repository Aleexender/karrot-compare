package com.example.karrot.item.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Entity
@Table(name = "items")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Embedded
    private Title title;

    // null means the item has no content.
    @Embedded
    private Content content;

    @Embedded
    private Price price;

    @Column(nullable = false)
    private boolean negotiable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Item() {
    }

    public Item(Long userId, Long categoryId, Title title, Content content, Price price, boolean negotiable,
            String idempotencyKey) {
        this.userId = Objects.requireNonNull(userId);
        this.categoryId = Objects.requireNonNull(categoryId);
        this.title = Objects.requireNonNull(title);
        this.content = content;
        this.price = Objects.requireNonNull(price);
        this.negotiable = negotiable;
        this.status = ItemStatus.ON_SALE;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        // DATETIME(6) keeps microseconds; truncating here makes the 201 body match a later GET.
        this.createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public Title getTitle() {
        return title;
    }

    public Content getContent() {
        return content;
    }

    public Price getPrice() {
        return price;
    }

    public boolean isNegotiable() {
        return negotiable;
    }

    public ItemStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
