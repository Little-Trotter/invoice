package org.hibernate.annotations

// Stand-ins carrying Hibernate's annotation names, so the rule is tested against exactly the names
// it matches in the modules without pulling Hibernate into the rules module.

@Target(AnnotationTarget.FIELD)
annotation class CreationTimestamp

@Target(AnnotationTarget.FIELD)
annotation class UpdateTimestamp

@Target(AnnotationTarget.FIELD)
annotation class CurrentTimestamp
