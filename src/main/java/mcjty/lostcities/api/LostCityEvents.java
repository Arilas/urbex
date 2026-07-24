package mcjty.lostcities.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.function.Consumer;

/**
 * Fabric event hooks for the Lost Cities generation events. On NeoForge these events were
 * posted on the NeoForge event bus; on Fabric register a listener on the appropriate
 * {@link Event} below. Cancellable events (PreGenCityChunk, PreExplosion) can be canceled
 * by calling {@link LostCityEvent#setCanceled(boolean)} from within the listener.
 */
public final class LostCityEvents {

    public static final Event<Consumer<LostCityEvent.CharacteristicsEvent>> CHARACTERISTICS = create();
    public static final Event<Consumer<LostCityEvent.PreGenCityChunkEvent>> PRE_GEN_CITY_CHUNK = create();
    public static final Event<Consumer<LostCityEvent.PostGenCityChunkEvent>> POST_GEN_CITY_CHUNK = create();
    public static final Event<Consumer<LostCityEvent.PostGenOutsideChunkEvent>> POST_GEN_OUTSIDE_CHUNK = create();
    public static final Event<Consumer<LostCityEvent.PreExplosionEvent>> PRE_EXPLOSION = create();

    private LostCityEvents() {
    }

    /**
     * Convenience: dispatch an event to the right hook and return it (so cancellation state can be read).
     */
    public static <T extends LostCityEvent> T post(Event<Consumer<T>> hook, T event) {
        hook.invoker().accept(event);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static <T extends LostCityEvent> Event<Consumer<T>> create() {
        return EventFactory.createArrayBacked((Class<Consumer<T>>) (Class<?>) Consumer.class,
                callbacks -> event -> {
                    for (Consumer<T> callback : callbacks) {
                        callback.accept(event);
                    }
                });
    }
}
