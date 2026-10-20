<?xml version="1.0" encoding="UTF-8"?>
<!--
  Business rules for the machine-readable offer (UBL 2.1 Quotation). XPath 2.0 query binding.
  The external parameter $allowedRates (a sequence of decimals, empty when the caller supplies
  no reference data) is bound by QuotationSchematronValidator.
-->
<schema xmlns="http://purl.oclc.org/dsdl/schematron" queryBinding="xslt2">
  <ns prefix="q" uri="urn:oasis:names:specification:ubl:schema:xsd:Quotation-2"/>
  <ns prefix="cac" uri="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"/>
  <ns prefix="cbc" uri="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"/>
  <ns prefix="xs" uri="http://www.w3.org/2001/XMLSchema"/>

  <pattern id="BR-Q-01">
    <rule context="/q:Quotation">
      <assert id="BR-Q-01" test="abs(sum(cac:QuotationLine/cbc:LineExtensionAmount/xs:decimal(.)) - xs:decimal(cac:QuotedMonetaryTotal/cbc:TaxExclusiveAmount)) lt 0.005">The sum of the line amounts must equal the net total (TaxExclusiveAmount).</assert>
      <assert id="BR-Q-01" test="abs(sum(cac:TaxTotal/cac:TaxSubtotal/cbc:TaxAmount/xs:decimal(.)) - xs:decimal(cac:TaxTotal/cbc:TaxAmount)) lt 0.005">The sum of the tax subtotals must equal the total tax amount.</assert>
      <assert id="BR-Q-01" test="abs(xs:decimal(cac:QuotedMonetaryTotal/cbc:TaxExclusiveAmount) + xs:decimal(cac:TaxTotal/cbc:TaxAmount) - xs:decimal(cac:QuotedMonetaryTotal/cbc:TaxInclusiveAmount)) lt 0.005">The gross total (TaxInclusiveAmount) must equal net total plus tax amount.</assert>
      <assert id="BR-Q-01" test="abs(xs:decimal(cac:QuotedMonetaryTotal/cbc:TaxInclusiveAmount) - xs:decimal(cac:QuotedMonetaryTotal/cbc:PayableAmount)) lt 0.005">The payable amount must equal the gross total.</assert>
    </rule>
    <rule context="/q:Quotation/cac:QuotationLine">
      <assert id="BR-Q-01" test="abs(xs:decimal(cbc:Quantity) * xs:decimal(cac:LineItem/cac:Price/cbc:PriceAmount) - xs:decimal(cbc:LineExtensionAmount)) le 0.005">The line amount must equal quantity times unit price.</assert>
    </rule>
  </pattern>

  <pattern id="BR-Q-02">
    <rule context="/q:Quotation">
      <assert id="BR-Q-02" test="exists(cac:ValidityPeriod/cbc:EndDate) and xs:date(cac:ValidityPeriod/cbc:EndDate) ge xs:date(cbc:IssueDate)">The validity end date must not be before the issue date.</assert>
    </rule>
  </pattern>

  <pattern id="BR-Q-03">
    <rule context="/q:Quotation/cac:SellerSupplierParty/cac:Party">
      <assert id="BR-Q-03" test="matches(normalize-space(cac:PartyTaxScheme[cac:TaxScheme/cbc:ID = 'VAT']/cbc:CompanyID), '^[A-Z]{2}[A-Z0-9]{2,12}$')">The seller must state a VAT identification number (country prefix and 2 to 12 alphanumeric characters).</assert>
      <assert id="BR-Q-03" test="normalize-space(cac:PartyName/cbc:Name) != ''">The seller must have a name.</assert>
    </rule>
    <rule context="/q:Quotation/cac:BuyerCustomerParty/cac:Party">
      <assert id="BR-Q-03" test="normalize-space(cac:PartyName/cbc:Name) != ''">The buyer must have a name.</assert>
    </rule>
    <rule context="/q:Quotation">
      <assert id="BR-Q-03" test="exists(cac:BuyerCustomerParty)">The buyer party must be present.</assert>
    </rule>
  </pattern>

  <pattern id="BR-Q-04">
    <rule context="/q:Quotation/cac:QuotationLine/cac:LineItem/cac:Item/cac:ClassifiedTaxCategory">
      <assert id="BR-Q-04" test="cbc:ID = ('S', 'Z', 'E', 'AE', 'O', 'G', 'K')">The line tax category must be a valid duty or tax category code.</assert>
      <assert id="BR-Q-04" test="exists(cbc:Percent) and xs:decimal(cbc:Percent) ge 0 and xs:decimal(cbc:Percent) le 100">The line tax rate must be a percentage between 0 and 100.</assert>
      <assert id="BR-Q-04" test="empty($allowedRates) or (some $r in $allowedRates satisfies $r = xs:decimal(cbc:Percent))">The line tax rate is not among the configured tax rates.</assert>
      <assert id="BR-Q-04" test="if (cbc:ID = 'S') then xs:decimal(cbc:Percent) gt 0 else xs:decimal(cbc:Percent) eq 0">The standard category needs a rate above zero, every other category a rate of zero.</assert>
    </rule>
  </pattern>

  <pattern id="BR-Q-05">
    <rule context="/q:Quotation">
      <assert id="BR-Q-05" test="normalize-space(cac:TransactionConditions/cbc:Description) != ''">The offer must contain the non-binding clause (Freizeichnungsklausel).</assert>
    </rule>
  </pattern>
</schema>
