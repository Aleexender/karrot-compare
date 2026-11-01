package com.example.karrot.item.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
