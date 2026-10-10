package de.littletrotter.domain.masterdata.converters

import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

@Converter(autoApply = false)
class VatIdVerificationStatusConverter : OrdinalEnumConverter<VatIdVerificationStatus>(VatIdVerificationStatus.entries)
