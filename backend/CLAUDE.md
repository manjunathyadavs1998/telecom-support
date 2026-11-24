# Backend rules (Spring Boot 3.5, Java 21, Spring JDBC, MySQL 8)

## Package layout — package by feature
`com.telecom.support.<feature>` with these classes (only those needed):
- `XxxController` — `@RestController @RequestMapping("/api/v1/...")`, validation with `@Valid`, `@PreAuthorize`. No SQL, no JdbcTemplate.
- `XxxService` — business rules, record-scope checks, transactions (`@Transactional`), audit events.
- `XxxRepository` — `@Repository` holding a `NamedParameterJdbcTemplate`; SQL as `private static final String` text blocks.
- `dto/` — request/response `record`s with Jakarta Validation annotations. Domain rows are records too.
Shared code: `common` (errors, paging, web filters, time, idempotency), `security`, `audit`.

## JDBC conventions
```java
private static final String FIND_BY_ID = """
    SELECT id, account_ref, full_name FROM customers WHERE id = :id
    """;
return jdbc.query(FIND_BY_ID, Map.of("id", id), MAPPER).stream().findFirst();
```
- Inserts: `KeyHolder` + `GeneratedKeyHolder`; batch with `batchUpdate(SqlParameterSource[])`.
- Optimistic locking: `UPDATE … SET …, version = version + 1 WHERE id = :id AND version = :version` → 0 rows ⇒ `ConflictException`.
- Atomic claim: `UPDATE cases SET assigned_to = :agent … WHERE id = :id AND assigned_to IS NULL` → exactly 1 row.
- Sorting: map the requested sort key through a whitelist `Map<String,String>` to a column; never interpolate user input.
- Pagination: `LIMIT :limit OFFSET :offset` + separate `COUNT(*)`; return `PageResponse<T>`.
- Columns snake_case; Java camelCase; `DATETIME(6)` UTC ↔ `Instant`.

## Errors
Throw `NotFoundException`, `ForbiddenException`, `ConflictException`, `BusinessRuleException` (from `common.error`);
`GlobalExceptionHandler` maps them to `ProblemDetail` with `correlationId` and `code`.

## Security
- Session-based auth (Spring Session JDBC), CSRF cookie `XSRF-TOKEN`, BCrypt passwords.
- Authorities = permission strings from BRD §8 plus `ROLE_<ROLE>`.
- `CurrentUser` (from `SecurityContext`) gives `userId`, `customerId` (customers only), `teamId`.

## Tests
- Unit: JUnit 5 + AssertJ + Mockito for services.
- Integration: `@SpringBootTest` + `@AutoConfigureMockMvc` extending `AbstractIntegrationTest` (Testcontainers MySQL, reused).
- Every new endpoint: happy path, validation failure, and a forbidden role.
