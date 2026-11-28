package com.telecom.support.servicecase;

import com.telecom.support.identity.CurrentUser;
import com.telecom.support.identity.Permissions;
import java.util.HashMap;
import java.util.Map;

/**
 * Record scope S of an outbound actor (design 04-outbound backend §3, §4; OUT-01.4, §14 #3, BR-10): with
 * {@code case.assign.any} every case; otherwise the actor's own cases, the team queue and the unrouted pool. A case
 * outside S is 404. Built from the principal only, never from the request.
 *
 * @param userId the actor
 * @param teamId the actor's team (null: no team queue)
 * @param all    {@code case.assign.any} (supervisor, OQ 1)
 */
record OutboundScope(long userId, Long teamId, boolean all) {

    /**
     * The fixed SQL fragment for S over cases aliased {@code c}; bind {@link #params()}. {@code assigned_team_id = NULL} is
     * never true, so an actor without a team sees own + pool only.
     */
    static final String SQL = """
            (:scopeAll OR c.assigned_user_id = :scopeUser OR c.assigned_team_id = :scopeTeam
             OR (c.assigned_user_id IS NULL AND c.assigned_team_id IS NULL))""";

    static OutboundScope of(CurrentUser user) {
        return new OutboundScope(user.id(), user.teamId(), user.has(Permissions.CASE_ASSIGN_ANY));
    }

    /** The named parameters of {@link #SQL}. */
    Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.put("scopeAll", all);
        params.put("scopeUser", userId);
        params.put("scopeTeam", teamId);
        return params;
    }

    /** Whether a case with this assignment is in S (for rows already read). */
    boolean covers(Long assignedUserId, Long assignedTeamId) {
        return all
                || (assignedUserId != null && assignedUserId == userId)
                || (assignedTeamId != null && assignedTeamId.equals(teamId))
                || (assignedUserId == null && assignedTeamId == null);
    }
}
