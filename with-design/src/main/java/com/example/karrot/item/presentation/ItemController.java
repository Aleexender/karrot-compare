package com.example.karrot.item.presentation;

import com.example.karrot.item.application.ItemReader;
import com.example.karrot.item.application.ItemRegistration;
import com.example.karrot.item.application.ItemService;
import com.example.karrot.item.application.RegisterItemCommand;
import com.example.karrot.item.application.exception.UnauthenticatedUserException;
import com.example.karrot.item.presentation.dto.CreateItemRequest;
import com.example.karrot.item.presentation.dto.ItemResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/posts")
public class ItemController {

    private final ItemService itemService;
    private final ItemReader itemReader;

    public ItemController(ItemService itemService, ItemReader itemReader) {
        this.itemService = itemService;
        this.itemReader = itemReader;
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(
            // Read as text so a missing or malformed id becomes 401, not the framework's 400.
            @RequestHeader(name = "X-User-Id", required = false) String userIdHeader,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) @Pattern(regexp = "\\p{ASCII}*")
            String idempotencyKey,
            @Valid @RequestBody CreateItemRequest request) {
        RegisterItemCommand command = request.toCommand(parseUserId(userIdHeader), idempotencyKey);
        ItemRegistration registration = itemService.register(command);
        ItemResponse response = ItemResponse.from(registration.item());
        if (!registration.created()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create("/posts/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable Long id) {
        return ItemResponse.from(itemReader.get(id));
    }

    private Long parseUserId(String userIdHeader) {
        if (userIdHeader == null) {
            throw new UnauthenticatedUserException("Missing X-User-Id header");
        }
        try {
            return Long.valueOf(userIdHeader);
        } catch (NumberFormatException exception) {
            throw new UnauthenticatedUserException("Malformed X-User-Id header: " + userIdHeader);
        }
    }
}
