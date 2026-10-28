package com.example.drinkshop.entity;

// Row mapped by PosRepository; no automatic Hibernate schema changes.
public record Staff(long id, String username, String passwordHash, String name,
                    String level, String pinHash, boolean active) {}
