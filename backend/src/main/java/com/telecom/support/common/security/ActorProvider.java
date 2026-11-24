package com.telecom.support.common.security;

import java.util.Optional;

/**
 * The authenticated actor of the current request, as other modules need it (audit rows). Implemented by
 * {@code identity}, so modules read the actor without depending on {@code identity} (ADR 0001).
 */
public interface ActorProvider {

    /** User id and username of the current principal. */
    record Actor(long id, String username) {}

    /** The authenticated actor of the current request; empty when anonymous. */
    Optional<Actor> currentActor();
}
