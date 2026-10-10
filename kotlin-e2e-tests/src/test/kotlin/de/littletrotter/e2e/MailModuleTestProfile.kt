package de.littletrotter.e2e

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Marks the tests that run against the real mail module. A test resource restricted to one class
 * does not by itself make Quarkus restart a running application that an earlier test class already
 * booted without it; a distinct profile does, so the mail module is always started before the
 * application that talks to it.
 */
class MailModuleTestProfile : QuarkusTestProfile
