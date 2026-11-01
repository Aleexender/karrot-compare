package com.example.karrot.item.application;

import com.example.karrot.item.application.exception.ItemNotFoundException;
import com.example.karrot.item.domain.Item;
import com.example.karrot.item.domain.ItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ItemReader {

    private final ItemRepository itemRepository;

    public ItemReader(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public Item get(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new ItemNotFoundException(itemId));
    }

    public Item getByIdempotencyKey(Long userId, String idempotencyKey) {
        return itemRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .orElseThrow();
    }
}
