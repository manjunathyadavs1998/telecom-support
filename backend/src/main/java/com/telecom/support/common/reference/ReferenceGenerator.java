package com.telecom.support.common.reference;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Human-readable reference IDs {@code <PREFIX>-<yyyyMMdd>-<seq>} (00-overview §8, 01 backend §6.2; BR-01, XC-05).
 *
 * <p>The day is today's {@code app.display-time-zone} (Asia/Kolkata) date from the {@link Clock} bean. Each value is
 * taken in its own {@code REQUIRES_NEW} transaction, so it is committed even if the caller rolls back: IDs are unique
 * and never reused, gaps are allowed. The sequence is zero-padded to 6 digits and simply grows past 999999.
 */
@Service
public class ReferenceGenerator {

    public static final String SERVICE_REQUEST = "SR";
    public static final String PAYMENT = "PAY";
    public static final String REACTIVATION = "REA";

    private static final Set<String> PREFIXES = Set.of(SERVICE_REQUEST, PAYMENT, REACTIVATION);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final RefSequenceRepository repository;
    private final Clock clock;
    private final ZoneId zone;
    private final TransactionTemplate requiresNew;

    public ReferenceGenerator(RefSequenceRepository repository, Clock clock,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.display-time-zone:Asia/Kolkata}") ZoneId zone) {
        this.repository = repository;
        this.clock = clock;
        this.zone = zone;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Returns the next reference for {@code prefix} ({@code SR}, {@code PAY} or {@code REA}). */
    public String next(String prefix) {
        if (prefix == null || !PREFIXES.contains(prefix)) {
            throw new IllegalArgumentException("Unknown reference prefix: " + prefix);
        }
        LocalDate day = LocalDate.ofInstant(clock.instant(), zone);
        Long seq = requiresNew.execute(status -> repository.next(prefix, day));
        return String.format("%s-%s-%06d", prefix, DAY.format(day), seq);
    }
}
