package com.example.karrot.category.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "item_categories")
public class ItemCategory {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String name;

    protected ItemCategory() {
    }

    public Long getId() {
        return id;
    }
}
