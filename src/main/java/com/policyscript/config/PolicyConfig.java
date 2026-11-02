package com.policyscript.config;

import java.util.Set;

public record PolicyConfig(Set<String> roles, Set<String> resources) {
    public PolicyConfig {
        roles = Set.copyOf(roles);
        resources = Set.copyOf(resources);
    }
}
