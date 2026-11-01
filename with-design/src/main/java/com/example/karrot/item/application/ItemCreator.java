package com.example.karrot.item.application;

import com.example.karrot.category.domain.ItemCategoryRepository;
import com.example.karrot.item.application.exception.UnauthenticatedUserException;
import com.example.karrot.item.application.exception.UnknownCategoryException;
import com.example.karrot.item.domain.Content;
import com.example.karrot.item.domain.Item;
import com.example.karrot.item.domain.ItemRepository;
import com.example.karrot.item.domain.Price;
import com.example.karrot.item.domain.Title;
import com.example.karrot.user.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemCreator {

    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final ItemCategoryRepository itemCategoryRepository;

    public ItemCreator(ItemRepository itemRepository, UserRepository userRepository,
            ItemCategoryRepository itemCategoryRepository) {
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.itemCategoryRepository = itemCategoryRepository;
    }

    // Existence checks run before the value objects are built so that an unknown user stays 401
    // even when the rest of the input is invalid.
    @Transactional
    public Item create(RegisterItemCommand command) {
        if (!userRepository.existsById(command.userId())) {
            throw new UnauthenticatedUserException("Unknown user: " + command.userId());
        }
        if (!itemCategoryRepository.existsById(command.categoryId())) {
            throw new UnknownCategoryException(command.categoryId());
        }
        Content content = command.content() == null ? null : new Content(command.content());
        Item item = new Item(command.userId(), command.categoryId(), new Title(command.title()), content,
                new Price(command.price()), command.negotiable(), command.idempotencyKey());
        return itemRepository.save(item);
    }
}
