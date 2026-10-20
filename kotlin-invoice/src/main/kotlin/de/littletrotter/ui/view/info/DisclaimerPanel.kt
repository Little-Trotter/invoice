package de.littletrotter.ui.view.info

import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.H4
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.ListItem
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.html.UnorderedList
import com.vaadin.flow.component.orderedlayout.VerticalLayout

import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs

internal class DisclaimerPanel(appVersion: String, productName: String) : VerticalLayout() {

    init {
        addClassName("disclaimer-panel")
        isPadding = true
        isSpacing = true
        add(buildBrandHero(appVersion, productName))
        add(buildLanguageTabs())
    }

    private fun buildBrandHero(appVersion: String, productName: String): VerticalLayout {
        val section = VerticalLayout()
        section.isPadding = false
        section.isSpacing = true

        // Transparent horse plus live text instead of a flat picture with burned-in lettering:
        // the name follows the application identity and the text colour follows the theme.
        val brandHorse = Image("images/brand-horse.png", "$productName Logo: stilisiertes Pferd").apply {
            addClassName("disclaimer-brand-horse")
        }
        val brandName = Span(productName).apply {
            addClassName("disclaimer-brand-name")
        }

        val versionTitle = H3("Version")
        val version = Span(appVersion).also {
            it.addClassName("disclaimer-version")
        }

        val quote = Paragraph("„Was dem Einzelnen nicht möglich ist, das vermögen viele.“ — Friedrich Wilhelm Raiffeisen").apply { addClassName("lt-disclaimer-quote") }

        section.add(brandHorse, brandName, quote, versionTitle, version)
        return section
    }

    private fun buildLanguageTabs(): VerticalLayout {
        val germanTab = Tab("Deutsch")
        val englishTab = Tab("English")
        val tabs = Tabs(germanTab, englishTab)

        val germanContent = buildGermanDisclaimer()
        val englishContent = buildEnglishDisclaimer().apply { isVisible = false }

        tabs.addSelectedChangeListener { event ->
            val selected = event.selectedTab
            germanContent.isVisible = selected == germanTab
            englishContent.isVisible = selected == englishTab
        }

        return VerticalLayout(tabs, germanContent, englishContent).apply {
            isPadding = false
            isSpacing = true
            width = "100%"
        }
    }

    private fun buildGermanDisclaimer(): VerticalLayout {
        val section = VerticalLayout()
        section.addClassName("disclaimer-section")
        section.isPadding = false
        section.width = "100%"

        section.add(H3("Haftungsausschluss & rechtliche Hinweise"))
        section.add(H4("Wichtiger Hinweis für Testnutzende"))
        section.add(Paragraph("Dieses Programm ist eine Testversion (Beta). Die Nutzung erfolgt ausschließlich zu Testzwecken und auf eigene Gefahr."))

        section.add(H4("1. Sicherheit & Verschlüsselung (BSI & DSGVO)"))
        section.add(Paragraph("WICHTIGER SICHERHEITSHINWEIS: Diese Demo-Version verwendet zur Vereinfachung keine oder nur eine unzureichende SSL/TLS-Verschlüsselung."))
        section.add(UnorderedList().apply {
            add(ListItem("BSI-Konformität: Die Datenübertragung entspricht nicht den aktuellen Richtlinien des Bundesamtes für Sicherheit in der Informationstechnik (BSI), insbesondere nicht der TR-02102-2."))
            add(ListItem("Datensicherheit: Gemäß Art. 32 DSGVO ist die Sicherheit der Verarbeitung nicht gewährleistet. Es dürfen keine realen personenbezogenen Daten eingegeben werden. Die Anwendung sollte ausschließlich in isolierten Testumgebungen betrieben werden."))
        })

        section.add(H4("2. Lizenzierung, Bildung & Open Source"))
        section.add(UnorderedList().apply {
            add(ListItem("Lizenz: Diese Anwendung steht unter der Apache License 2.0. Die Weiterentwicklung, insbesondere im Bereich der akademischen Bildung, sowie der fachliche Austausch zu Softwarearchitektur und DevOps sind ausdrücklich erwünscht."))
            add(ListItem("Verweispflicht (Attributierung): Bei der Verwendung dieses Kerns in anderen Anwendungen (auch bei kommerzieller Nutzung) ist gemäß Creative Commons Attribution (CC BY 4.0) ein deutlich sichtbarer Verweis auf das Originalprojekt zu pflegen."))
            add(ListItem("Third-Party & SBOM: Für den Betrieb werden Third-Party-Komponenten verwendet. Details finden sich in der dynamischen Software Bill of Materials (SBOM) im System, im Code-Repository sowie in der pom.xml (Maven)."))
        })

        section.add(H4("3. Fachliche & gesetzliche Compliance"))
        section.add(UnorderedList().apply {
            add(ListItem("Referenzdaten: Alle im System hinterlegten Referenzdaten dienen ausschließlich Demo-Zwecken. Vor einem produktiven Einsatz müssen diese zwingend geprüft und angepasst werden."))
            add(ListItem("Normen & Standards: Die Anwendung orientiert sich an der europäischen Norm EN 16931 (XRechnung / ZUGFeRD). Die Verantwortung für die Einhaltung spezifischer Profile verbleibt bei den Nutzenden."))
            add(ListItem("Fachliche Abstimmung: Der Einsatz innerhalb eines Unternehmens muss vorab mit der Steuerberatung, Buchhaltung oder Legal-/Compliance-Abteilung abgestimmt werden."))
            add(ListItem("Ordnungsgemäße Buchführung & Speicherbuchführung: Die Nutzenden sind allein verantwortlich für die Einhaltung der GoBD. Dies umfasst Aufbewahrungspflichten gemäß § 147 AO, Integrität und Unveränderbarkeit nach § 238 und § 239 Abs. 3 HGB, Langzeitarchivierung gemäß ISO 19005 (PDF/A-3) sowie die lückenlose Protokollierung aller Systemänderungen."))
            add(ListItem("Haftungsausschluss: Die Entwicklung übernimmt keinerlei Haftung für die Richtigkeit oder Gesetzeskonformität. Es besteht kein Anspruch auf Wartung oder Support."))
            add(ListItem("Datenschutz (DSGVO): Bei der Nutzung werden personenbezogene Daten verarbeitet. Die Betreiberseite ist hierfür die ausschließlich alleinige Verantwortliche im Sinne des Art. 4 Nr. 7 DSGVO und trägt die Sorge für die konforme Verarbeitung gemäß Art. 6 Abs. 1 lit. b & c DSGVO."))
        })

        return section
    }

    private fun buildEnglishDisclaimer(): VerticalLayout {
        val section = VerticalLayout()
        section.addClassName("disclaimer-section")
        section.isPadding = false
        section.width = "100%"

        section.add(H3("Disclaimer & Legal Notices"))
        section.add(H4("Important Notice for Test Users"))
        section.add(Paragraph("This program is a test version (Beta). Use of this software is at your own risk and intended for testing purposes only."))

        section.add(H4("1. Security & Encryption (BSI & GDPR)"))
        section.add(Paragraph("CRITICAL SECURITY NOTICE: For simplicity, this demo version uses little or no SSL/TLS encryption."))
        section.add(UnorderedList().apply {
            add(ListItem("BSI Compliance: Data transmission does not comply with the current guidelines of the Federal Office for Information Security (BSI), specifically TR-02102-2."))
            add(ListItem("Data Security: Pursuant to Art. 32 GDPR, the security of processing is not guaranteed. No real personal data must be entered. The application should be operated exclusively in isolated test environments."))
        })

        section.add(H4("2. Licensing, Education & Open Source"))
        section.add(UnorderedList().apply {
            add(ListItem("License: This application is licensed under the Apache License 2.0. Development in academic education and exchange regarding Software Architecture and DevOps are highly encouraged."))
            add(ListItem("Attribution Requirement: Any use of this core in other applications (including commercial use) requires a clearly visible reference to the original project in accordance with Creative Commons Attribution (CC BY 4.0)."))
            add(ListItem("Third-Party & SBOM: Details on third-party components are provided in the dynamic Software Bill of Materials (SBOM) within the system, the code repository, and the pom.xml (Maven)."))
        })

        section.add(H4("3. Professional & Legal Compliance"))
        section.add(UnorderedList().apply {
            add(ListItem("Demo Data: All reference data in the system are for demonstration purposes only and must be verified before productive use."))
            add(ListItem("Norms & Standards: The application follows the European standard EN 16931 (XRechnung / ZUGFeRD). Users remain responsible for compliance with specific profiles."))
            add(ListItem("Professional Consultation: Use within an organization must be coordinated with tax advisors, accounting departments, or legal/compliance officers."))
            add(ListItem("Proper Accounting & Electronic Record Keeping: Users are solely responsible for compliance with the German GoBD, including retention periods under Section 147 AO, integrity and immutability under Section 238 and Section 239 Para. 3 HGB, long-term archiving according to ISO 19005 (PDF/A-3), and seamless logging of all system changes."))
            add(ListItem("Limitation of Liability: The developer assumes no liability for accuracy or legal compliance. No maintenance or support is provided."))
            add(ListItem("Data Protection (GDPR): Personal data is processed during use. The operator is the sole and exclusive controller (Art. 4 No. 7 GDPR) and is responsible for compliant processing under Art. 6 Para. 1 (b) & (c) GDPR."))
        })

        return section
    }
}
