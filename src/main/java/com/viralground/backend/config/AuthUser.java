package com.viralground.backend.config;

import com.viralground.backend.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthUser {
    private final Integer id;
    private final String email;
    private final Role role;
    private final String name;
}
