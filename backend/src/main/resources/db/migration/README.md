`V1__initial_schema.sql` is the canonical schema for the team. Read `docs/database.md`
before mapping JPA entities or adding columns.

For later changes, add `V2__description.sql`, `V3__description.sql`, etc. Never edit
an applied migration: Flyway stores its checksum. Coordinate foreign keys and
cross-module changes in a PR. Hibernate uses `ddl-auto=validate`, so entities
must match the SQL schema instead of creating tables automatically.
