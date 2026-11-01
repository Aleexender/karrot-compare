package com.example.karrot.post;

import java.time.LocalDateTime;

public record PostResponse(
        Long id,
        Long sellerId,
        String title,
        String content,
        Integer price,
        String location,
        PostStatus status,
        LocalDateTime createdAt
) {
    public static PostResponse from(Post post) {
        return new PostResponse(
                post.getId(),
                post.getSellerId(),
                post.getTitle(),
                post.getContent(),
                post.getPrice(),
                post.getLocation(),
                post.getStatus(),
                post.getCreatedAt()
        );
    }
}
