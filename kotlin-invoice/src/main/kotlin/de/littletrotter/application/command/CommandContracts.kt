package de.littletrotter.application.command

/**
 * Marks an application-layer write command: an immutable data carrier
 * crossing the UI -> application boundary (identity + expected version + the fields one operation
 * needs), never a JPA entity and never a transactional handler itself. Commands share the
 * `application.invoice` package with services (the persistence decision's own inventory note), so
 * a blanket package-based ArchUnit rule cannot tell them apart — this marker is what
 * `CommandBoundaryArchitectureTest` scans for instead of a class-name suffix.
 */
interface Command

/**
 * Marks an application-layer read result crossing the boundary back to
 * the UI: a DTO/value snapshot, never a managed JPA entity the UI could mutate or re-persist.
 */
interface CommandResult

/**
 * Marks the interface(s) that own the UI -> application write boundary.
 * Every method on a [CommandEntryPoint] is a command entry point in the persistence decision's
 * sense: it takes commands/ids/value types and returns a [CommandResult] (or a plain value/ids),
 * never an entity — the exact surface `CommandBoundaryArchitectureTest`'s check inspects.
 */
interface CommandEntryPoint
