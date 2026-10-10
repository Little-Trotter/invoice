{{! Minimal invoice template for kotlin-xpdf }}
# Rechnung {{invoice.number}}

Datum: {{invoice.date}}

| Pos | Beschreibung | Menge | Betrag |
|---|---:|---:|---:|
{{#invoice.items}}
| {{position}} | {{description}} | {{qty}} | {{lineTotal}} |
{{/invoice.items}}

**Nettobetrag:** {{invoice.netTotal}}

**USt:** {{invoice.vatTotal}}

**Gesamt:** {{invoice.grossTotal}}
