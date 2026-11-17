package de.maxhenkel.voicechat.api.events;

public interface Event {
    boolean isCancellable();

    boolean cancel();

    boolean isCancelled();
}
