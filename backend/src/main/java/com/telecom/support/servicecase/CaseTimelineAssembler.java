package com.telecom.support.servicecase;

import com.telecom.support.identity.UserDirectory;
import com.telecom.support.servicecase.AgentCaseQueryService.CaseActivity;
import com.telecom.support.servicecase.CaseActivityRepository.ActivityRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns {@code case_activity} rows into the {@code CaseActivity} schema for agents (design 04-outbound backend R4; shared
 * with 03 I10): actor ids become display names, and ASSIGNED rows, which store user ids in {@code old_value} /
 * {@code new_value} (R5, R6), show the users' display names. One {@link UserDirectory} call per timeline.
 */
@Component
class CaseTimelineAssembler {

    private final UserDirectory users;

    CaseTimelineAssembler(UserDirectory users) {
        this.users = users;
    }

    List<CaseActivity> assemble(List<ActivityRow> rows) {
        List<Long> ids = new ArrayList<>();
        for (ActivityRow row : rows) {
            ids.add(row.actorUserId());
            if ("ASSIGNED".equals(row.activityType())) {
                ids.add(parseId(row.oldValue()));
                ids.add(parseId(row.newValue()));
            }
        }
        Map<Long, String> names = users.displayNames(ids);
        return rows.stream().map(row -> {
            boolean assigned = "ASSIGNED".equals(row.activityType());
            return new CaseActivity(row.activityType(), row.visibility(),
                    row.actorUserId() == null ? null : names.get(row.actorUserId()),
                    assigned ? name(row.oldValue(), names) : row.oldValue(),
                    assigned ? name(row.newValue(), names) : row.newValue(),
                    row.noteText(), row.createdAt());
        }).toList();
    }

    /** A stored user id shown as the user's name; a value that is not an id (older rows) is shown as it is. */
    private static String name(String value, Map<Long, String> names) {
        Long id = parseId(value);
        return id == null ? value : names.getOrDefault(id, value);
    }

    private static Long parseId(String value) {
        if (value == null || value.isEmpty() || !value.chars().allMatch(Character::isDigit) || value.length() > 18) {
            return null;
        }
        return Long.valueOf(value);
    }
}
