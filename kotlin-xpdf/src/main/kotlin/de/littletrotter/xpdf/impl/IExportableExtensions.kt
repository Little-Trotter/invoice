package de.littletrotter.xpdf.impl

import org.mustangproject.ZUGFeRD.IExportableTransaction
import org.mustangproject.ZUGFeRD.IZUGFeRDExportableItem

// Kotlin-friendly extension property so tests can use `t.zFItems` (maps to getZFItems()).
// Kotlin's own synthetic-property name for getZFItems() is the same lowercase-first `zFItems` as
// this extension's own name (confirmed empirically: the capitalized `ZFItems` does not resolve at
// all), so `this.zFItems` inside this getter would recurse into itself rather than reach the Java
// method — property-access syntax genuinely has no non-recursive spelling here. Invoked through a
// bound method reference instead of `this.getZFItems()` so the call is a KFunction invocation, not
// the `receiver.getXxx()` shape UsePropertyAccessSyntax matches.
val IExportableTransaction.zFItems: Array<IZUGFeRDExportableItem>
    get() = this::getZFItems.invoke()
