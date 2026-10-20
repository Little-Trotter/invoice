package de.littletrotter.shared.edge

/**
 * Decides which of the group names the edge sent become roles of the security identity.
 *
 * One small role interface, so each application states its own vocabulary without the edge module
 * knowing it. The mapping decides nothing about permissions; it only names the roles a request holds.
 */
fun interface EdgeRoleMapping {

    fun rolesOf(groups: List<String>): Set<String>
}

/** Every group name the edge sent is a role, exactly as sent; the application decides later which mean anything. */
val GroupsAsRoles: EdgeRoleMapping = EdgeRoleMapping { groups -> groups.toSet() }
