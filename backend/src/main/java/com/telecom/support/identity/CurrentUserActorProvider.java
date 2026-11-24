package com.telecom.support.identity;

import com.telecom.support.common.security.ActorProvider;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link ActorProvider} backed by the session's {@link CurrentUser}. */
@Component
class CurrentUserActorProvider implements ActorProvider {

    @Override
    public Optional<Actor> currentActor() {
        return CurrentUser.get().map(user -> new Actor(user.id(), user.getUsername()));
    }
}
