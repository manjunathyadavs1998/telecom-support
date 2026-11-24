---
name: spring-jdbc-endpoint
description: Scaffold or extend a backend REST endpoint in this project — controller, service, Spring JDBC repository (NamedParameterJdbcTemplate, no JPA), DTO records, ProblemDetail errors, @PreAuthorize permission, audit and tests. Use whenever adding or changing any /api/v1 endpoint, repository query or backend feature package.
---

# Spring JDBC endpoint recipe

Follow these steps **in order**. Every endpoint in this repo looks the same, so reviewers can spot deviations.
Templates are in `templates/` next to this file. Copy them and rename `Xxx`/`xxx`.

## 1. Decide the contract
- Path: `/api/v1/<feature-plural>` (kebab-case). Use `/me/...` for the caller's own resources (customer scope from the principal).
- Permission: pick from `com.telecom.support.security.Permissions` (BRD §8 strings). If none fits, run the **rbac-permission** skill first.
- Request DTO: a `record` with Jakarta Validation. Response DTO: a `record` named `…Response` (an `OpenApiCustomizer`
  marks all its properties required in the contract). Annotate nullable components with `@Schema(nullable = true)`.
  Never expose row records directly when they contain internal fields.
- Lists: `PageResponse<T>` with `page` (0-based), `size` (max 100), plus bounded date filters (`from`, `to`). The default window is the last 3 months when the BRD says so (BR-05/06).

## 2. Repository (`<feature>/XxxRepository.java`)
- `@Repository`, constructor-inject `NamedParameterJdbcTemplate`.
- SQL in `private static final String` **text blocks** with `:named` parameters. **Never concatenate values.**
- Dynamic filters: append fixed fragments (`" AND c.status = :status"`) and bind the value. Sorting uses `SortWhitelist`.
- Map rows with a `private static final RowMapper<XxxRow>`. Convert timestamps with `rs.getTimestamp(..).toInstant()` through `JdbcUtils.instant(rs, "col")`.
- Insert → `GeneratedKeyHolder`. Update with optimistic lock → `WHERE id = :id AND version = :version`; a return value of 0 → `ConflictException`.
- No business rules here.

## 3. Service (`<feature>/XxxService.java`)
- `@Service`, `@Transactional(readOnly = true)` on the class, `@Transactional` on writes.
- Resolve the caller with `CurrentUser.require()`. For customers use `user.customerId()`, **never** an id from the request.
- Record-level scope checks → `ForbiddenException` / `NotFoundException`. Prefer 404 for other people's records so existence doesn't leak.
- Business rule violations → `BusinessRuleException(code, message)`.
- High-risk writes → `auditService.record(AuditAction.X, "entity", id, details)` (XC-06).

## 4. Controller (`<feature>/XxxController.java`)
- `@RestController @RequestMapping("/api/v1/...") @Tag(name = "...")`.
- `@PreAuthorize("hasAuthority(T(com.telecom.support.security.Permissions).XXX)")` on **every** method (BR-10).
- `@Valid @RequestBody`, `@Validated` for query params. Return DTOs or `ResponseEntity` (201 + Location for creates).
- Create/payment/reactivation: accept `@RequestHeader("Idempotency-Key")` and delegate to `IdempotencyService`.
- No JdbcTemplate, no SQL, no business logic.

## 5. Tests
- `XxxServiceTest` (unit, Mockito) covering rules and scope.
- `XxxControllerIT extends AbstractIntegrationTest` (MockMvc, real MySQL), with at minimum:
  1. happy path as an allowed role (`@WithAppUser("customer1")` or the `loginAs` helper),
  2. validation failure → 400 ProblemDetail with `errors[]`,
  3. **forbidden role → 403**, and unauthenticated → 401.

## 6. Finish
- Run `cd backend && ./mvnw -q verify`.
- Update `docs/requirements/traceability.md` for the BRD IDs touched (brd-to-stories skill).

## Anti-patterns (rejected in review)
`jakarta.persistence.*`, `JpaRepository`, `@Query`, `"... WHERE id = " + id`, `@PreAuthorize("hasRole(...)")` (use permissions),
returning `Map<String,Object>` from controllers, catching `Exception` in controllers, `@Autowired` field injection.
