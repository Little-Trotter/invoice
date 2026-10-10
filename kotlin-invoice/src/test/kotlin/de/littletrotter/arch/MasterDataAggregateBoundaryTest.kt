package de.littletrotter.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.ArticleTaxVariant
import de.littletrotter.domain.masterdata.SellerVatRegistration
import org.junit.jupiter.api.Test

class MasterDataAggregateBoundaryTest {
    private val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("de.littletrotter")

    @Test
    fun `persistence collections cannot be replaced outside their aggregate`() {
        listOf(
            Article::class.java to "taxVariantRows",
            Article::class.java to "recipientRefRows",
            SenderProfile::class.java to "additionalVatRegistrationRows",
        ).forEach { (owner, field) ->
            fields().that().areDeclaredIn(owner).and().haveName(field).should().bePrivate().check(classes)
        }
        listOf("setTaxVariants", "setRecipientRefs", "setAdditionalVatRegistrations").forEach { setter ->
            noMethods().that().areDeclaredIn(Article::class.java).or().areDeclaredIn(SenderProfile::class.java)
                .should().haveName(setter).check(classes)
        }
    }

    @Test
    fun `application and UI cannot write child fields directly`() {
        noClasses().that().resideInAnyPackage("..application..", "..ui..")
            .should().setField(SellerVatRegistration::class.java, "vatId")
            .orShould().setField(ArticleRecipientRef::class.java, "buyerArticleNumber")
            .orShould().setField(ArticleRecipientRef::class.java, "buyerArticleName")
            .orShould().setField(ArticleTaxVariant::class.java, "taxRateRef")
            .check(classes)
        noClasses().that().resideInAnyPackage("..application..", "..ui..")
            .should().callMethod(SellerVatRegistration::class.java, "setVatId", String::class.java)
            .orShould().callMethod(ArticleRecipientRef::class.java, "setBuyerArticleNumber", String::class.java)
            .orShould().callMethod(ArticleRecipientRef::class.java, "setBuyerArticleName", String::class.java)
            .check(classes)
    }
}
