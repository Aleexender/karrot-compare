package com.example.karrot.post;

import org.springframework.data.domain.Page;

import java.util.List;

public record PostPageResponse(
        List<PostResponse> posts,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static PostPageResponse from(Page<Post> page) {
        return new PostPageResponse(
                page.map(PostResponse::from).getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
