package de.littletrotter.application.invoice

import de.littletrotter.application.shared.DomainEventDispatcher
import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bPartyFieldValues
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.reference.NumberRangeService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.application.shared.port.inbound.AuditActorQueryPort
import de.littletrotter.application.shared.SystemAuditActorProvider
import de.littletrotter.domain.event.DomainEvent
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.domain.repository.TaxRateRepository
import de.littletrotter.domain.repository.ValidationProfileRepository
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.event.Event
import org.mockito.kotlin.mock
import java.time.Clock
import java.time.Instant
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * Z5 — builds an [InvoiceLifecycleService] for unit tests without ever passing a nullable
 * collaborator: every field defaults to a harmless fake (an unconfigured Mockito mock for
 * interfaces, or a real instance wired from mocks for the handful of concrete collaborators that
 * can't be mocked without the inline mock maker). Defaults are chosen to reproduce the old
 * nullable-collaborator behavior exactly — e.g. an unconfigured [ValidationProfileRepository]
 * mock returns `null` from `findCurrentByProfileId`, the same outcome the old `validationProfileRepo
 * ?: null` skip produced — so tests that never cared about a given collaborator don't need to
 * change anything beyond swapping the constructor call for this builder.
 *
 * Every field is individually overridable via the `withX` methods; call [build] last.
 */
class InvoiceLifecycleServiceTestBuilder(
    private var invoiceRepo: InvoiceRepository = mock(),
    private var senderRepo: SenderProfileRepository = mock(),
    private var recipientRepo: RecipientRepository = mock(),
    private var invoiceNumberService: InvoiceNumberService = InvoiceNumberService(
        NumberRangeService(mock(), mock(), mock(), ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z"))),
    ),
    private var domainEventBus: Event<DomainEvent> = mock(),
    private var addressManagementService: AddressManagementService = AddressManagementService(mock<RecipientRepository>(), mock(), mock()),
    private var auditActorProvider: AuditActorQueryPort = SystemAuditActorProvider(),
    private var configuredDefaultPageSize: Int = 60,
    private var paymentTermRepo: PaymentTermRepository = mock(),
    private var taxRateRepo: TaxRateRepository = mock(),
    private var validationProfileRepo: ValidationProfileRepository = mock(),
    private var generationJobRepo: GenerationJobRepository = mock(),
    // Mockito default for an unconfigured Boolean-returning method is false — matches
    // FeatureFlag.EXTENDED_PROFILE.defaultEnabled, so every existing test built through this
    // builder keeps exercising the off-by-default path without having to stub this explicitly.
    private var featureSettingsGate: FeatureSettingsGate = mock(),
    private var gateStageConfig: String = "OBSERVE",
    private var complianceStageConfig: String = "OBSERVE",
    private var b2bProfileRuleSet: B2bProfileRuleSet = B2bProfileRuleSet(mock(), mock(), B2bPartyFieldValues(mock(), mock())),
    private var b2bProfileRepo: B2bProfileRepository = mock(),
    private var b2bFieldValueService: B2bFieldValueService = B2bFieldValueService(
        mock(), mock(), mock(), mock(), B2bProfileFieldDefinitionService(mock(), mock(), mock()), B2bPartyFieldValues(mock(), mock()),
    ),
    private var b2bProfileFieldDefinitionService: B2bProfileFieldDefinitionService = B2bProfileFieldDefinitionService(mock(), mock(), mock()),
    private var articleRepo: ArticleRepository = mock(),
    // The real system clock, not a fixed instant: dozens of pre-existing tests assert dueDate/
    // invoiceDate/etc. relative to "today" (LocalDate.now()-style expectations from before this
    // service read TimeSource), so this default must track real time the same way.
    private var time: TimeSource = ClockTimeSource(Clock.systemUTC()),
    private var wordingProfile: de.littletrotter.shared.wording.WordingProfile = de.littletrotter.shared.wording.WordingProfile.HANDWERK,
) {
    fun withInvoiceRepo(value: InvoiceRepository) = apply { invoiceRepo = value }
    fun withSenderRepo(value: SenderProfileRepository) = apply { senderRepo = value }
    fun withRecipientRepo(value: RecipientRepository) = apply { recipientRepo = value }
    fun withInvoiceNumberService(value: InvoiceNumberService) = apply { invoiceNumberService = value }
    fun withDomainEventBus(value: Event<DomainEvent>) = apply { domainEventBus = value }
    fun withAddressManagementService(value: AddressManagementService) = apply { addressManagementService = value }
    fun withAuditActorProvider(value: AuditActorQueryPort) = apply { auditActorProvider = value }
    fun withConfiguredDefaultPageSize(value: Int) = apply { configuredDefaultPageSize = value }
    fun withPaymentTermRepo(value: PaymentTermRepository) = apply { paymentTermRepo = value }
    fun withTaxRateRepo(value: TaxRateRepository) = apply { taxRateRepo = value }
    fun withValidationProfileRepo(value: ValidationProfileRepository) = apply { validationProfileRepo = value }
    fun withGenerationJobRepo(value: GenerationJobRepository) = apply { generationJobRepo = value }
    fun withFeatureSettingsGate(value: FeatureSettingsGate) = apply { featureSettingsGate = value }
    fun withGateStageConfig(value: String) = apply { gateStageConfig = value }
    fun withComplianceStageConfig(value: String) = apply { complianceStageConfig = value }
    fun withB2bProfileRuleSet(value: B2bProfileRuleSet) = apply { b2bProfileRuleSet = value }
    fun withB2bProfileRepo(value: B2bProfileRepository) = apply { b2bProfileRepo = value }
    fun withB2bFieldValueService(value: B2bFieldValueService) = apply { b2bFieldValueService = value }
    fun withB2bProfileFieldDefinitionService(value: B2bProfileFieldDefinitionService) = apply { b2bProfileFieldDefinitionService = value }
    fun withArticleRepo(value: ArticleRepository) = apply { articleRepo = value }
    fun withTime(value: TimeSource) = apply { time = value }
    fun withWordingProfile(value: de.littletrotter.shared.wording.WordingProfile) = apply { wordingProfile = value }

    /** Reads [wordingProfile] on every call, so a profile switch after building is visible. */
    private fun buildConsumerNoteResolver() = ConsumerNoteResolver(
        object : de.littletrotter.application.settings.port.inbound.WordingProfileQueryPort {
            override fun currentProfile() = wordingProfile
        },
    )

    private fun buildSupport(): InvoiceLifecycleSupport = InvoiceLifecycleSupport(
        addressManagementService = addressManagementService,
        paymentTermRepo = paymentTermRepo,
        taxRateRepo = taxRateRepo,
        b2bProfileRepo = b2bProfileRepo,
        // way (coordinator ruling) — InvoiceLifecycleSupport takes the narrow
        // InvoiceB2bFieldsQueryPort, not the two services directly; built fresh here (not as a
        // class-level default) so a withB2bFieldValueService/withB2bProfileFieldDefinitionService
        // override made after construction is still picked up.
        invoiceB2bFieldsQueryPort = InvoiceB2bFieldsQueries(b2bFieldValueService, b2bProfileFieldDefinitionService),
        articleRepo = articleRepo,
        numberAssigner = InvoiceNumberAssignment(invoiceNumberService),
        configuredDefaultPageSize = configuredDefaultPageSize,
    )

    fun build(): InvoiceLifecycleService = InvoiceLifecycleService(
        invoiceRepo = invoiceRepo,
        parties = PartyRepositories(senderRepo, recipientRepo, buildConsumerNoteResolver()),
        eventDispatcher = DomainEventDispatcher(domainEventBus, null),
        auditActorProvider = auditActorProvider,
        support = buildSupport(),
        finalizationPolicy = FinalizationPolicy(
            time = time,
            validationProfileRepo = validationProfileRepo,
            generationJobRepo = generationJobRepo,
            b2bProfileRuleSet = b2bProfileRuleSet,
            b2bFieldValueService = b2bFieldValueService,
            b2bProfileFieldDefinitionService = b2bProfileFieldDefinitionService,
            featureSettingsGate = featureSettingsGate,
            gateStageConfig = gateStageConfig,
            complianceStageConfig = complianceStageConfig,
        ),
        time = time,
    )

    /** item-mutation collaborator wired from the same fields/mocks as [build]'s own [InvoiceLifecycleService]. */
    fun buildItemMutationService(): InvoiceItemMutationService = InvoiceItemMutationService(
        invoiceRepo = invoiceRepo,
        support = buildSupport(),
        auditActorProvider = auditActorProvider,
        eventDispatcher = DomainEventDispatcher(domainEventBus, null),
        overviewCache = de.littletrotter.application.cache.NoopInvoiceCachePort(),
        transactionSynchronizationRegistry = org.mockito.Mockito.mock(jakarta.transaction.TransactionSynchronizationRegistry::class.java),
    )

    /**
     * reverse-charge collaborator wired from the same fields/mocks as [build]'s own
     * [InvoiceLifecycleService]; also the collaborator [buildHeaderUpdateService] shares (the
     * shared re-tax decision), exactly like the real CDI wiring.
     */
    fun buildReverseChargeService(): InvoiceReverseChargeService = InvoiceReverseChargeService(
        invoiceRepo = invoiceRepo,
        support = buildSupport(),
        auditActorProvider = auditActorProvider,
        eventDispatcher = DomainEventDispatcher(domainEventBus, null), time = FIXED_TIME_SOURCE,
    )

    /** header-update collaborator wired from the same fields/mocks as [build]'s own [InvoiceLifecycleService]. */
    fun buildHeaderUpdateService(): InvoiceHeaderUpdateService = InvoiceHeaderUpdateService(
        invoiceRepo = invoiceRepo,
        parties = PartyRepositories(senderRepo, recipientRepo, buildConsumerNoteResolver()),
        eventDispatcher = DomainEventDispatcher(domainEventBus, null),
        auditActorProvider = auditActorProvider,
        support = buildSupport(),
        reverseChargeService = buildReverseChargeService(),
    )
}
