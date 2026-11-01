CREATE TABLE IF NOT EXISTS users (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    nickname   VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS item_categories (
    id   BIGINT PRIMARY KEY,
    name VARCHAR(30) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS items (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    category_id     BIGINT       NOT NULL,
    title           VARCHAR(100) NOT NULL,
    content         TEXT,
    price           BIGINT       NOT NULL,
    negotiable      BOOLEAN      NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    -- The default utf8mb4_0900_ai_ci collation treats 'abc' and 'ABC' as the same key.
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_items_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_items_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_items_category FOREIGN KEY (category_id) REFERENCES item_categories (id),
    CONSTRAINT ck_items_price CHECK (price >= 0),
    CONSTRAINT ck_items_status CHECK (status IN ('ON_SALE', 'SOLD', 'STOPPED'))
);
