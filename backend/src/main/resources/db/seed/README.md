Dev/demo seed data. These are Flyway **repeatable** migrations (`R__<order>_<name>.sql`). They run after all versioned schema migrations and run again whenever their checksum changes, so every statement must be idempotent (`INSERT IGNORE`, `INSERT … ON DUPLICATE KEY UPDATE`).

They are loaded only when the `dev` profile is active (see `application-dev.yml`), and production config never references them.
