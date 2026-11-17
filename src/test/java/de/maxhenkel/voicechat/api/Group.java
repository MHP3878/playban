package de.maxhenkel.voicechat.api;

import java.util.UUID;

public interface Group {
    String getName();

    boolean hasPassword();

    UUID getId();

    boolean isPersistent();

    boolean isHidden();

    Type getType();

    interface Type {
        Type NORMAL = new Type() {
        };
    }

    interface Builder {
        Builder setId(UUID id);

        Builder setName(String name);

        Builder setPassword(String password);

        Builder setPersistent(boolean persistent);

        Builder setHidden(boolean hidden);

        Builder setType(Type type);

        Group build();
    }
}
