# besser-doc — Prüfung der arc42-Dokumentation aus vier Perspektiven

Stand: 2026-10-07 · Basis: `main` @ 60c5ca9e7 · Pfade relativ zu `arc42-docs/doc/`
Bewertung: **S** = sinnvoll (umsetzen) · **Ü** = übertrieben (kürzen/streichen/nicht bauen) · Prio P1 (vor Produktivfreigabe) / P2 (nächste Welle) / P3 (bei Gelegenheit)
Verfahren: vier unabhängige Reviews, Stichproben der Kernbefunde gegen die Dateien verifiziert. „Nicht dokumentiert“ heißt nicht automatisch „nicht implementiert“.

---

## 0. Kernaussage

Die Dokumentation ist bei **Nummernkreis, Festschreibung, Validierung, Nebenläufigkeit und Qualitätsszenarien** deutlich über KMU-Niveau. Drei Bereiche reichen nicht:

1. **Widersprüche zu Archiv, Aufbewahrung und Storno.** Diese Aussagen greift eine Prüferin zuerst an.
2. **Rechtliche und betriebliche Grundlagen fehlen.** Betroffen sind die rechtlichen Randbedingungen, DSGVO, RPO/RTO, Off-site-Sicherung, WORM-Archiv und Migration ab dem ersten Live-Betrieb.
3. **Die Zielgruppe Handwerk ist fachlich nicht abgedeckt.** Es fehlen Abschlags- und Schlussrechnung, §13b-Bau, §35a, §19, Zahlungseingang und DATEV. Das Benutzerhandbuch ist zudem für Nicht-IT-Anwender nicht nutzbar.

Kapitel 08 (1.515 Zeilen) ist zu lang und falsch gewichtet. Prozess- und Skalierungsprosa gehört gestrichen.

---

## 0a. PO-Entscheide (2026-10-07) — verbindlich für die Bewertung unten

| Perspektive | Entscheid | Folge für die Befunde |
|---|---|---|
| b) Steuerberaterin | Die Anwendung ist ein **reines Rechnungsprogramm**. Rechnungen werden an die Buchhaltung übergeben (DATEV, Lexware, Steuerberatung). | B1 (DATEV), B5 (Empfang), B7 (OP/Zahlung), B10 (StB-Zugang) und B12 (Mahnwesen) sind **außerhalb des Scopes**. Die Scope-Grenze wird nur klar dokumentiert, nicht gebaut. Rechnungsinhalte (B2 §13b-Inland, B3 Abschlag/Schluss, B4 §19, B8 §35a) bleiben eine offene Produktfrage. |
| c) Finanzbeamtin | Archiv und Aufbewahrung **verantwortet der Betreiber**. Die Ablage erfolgt bei DATEV, Lexware o. Ä. Wegen des Mailversands wird ein revisionssicheres E-Mail-Archiv empfohlen. | C1 und C7 (WORM, Z3-Export) liegen beim Betreiber. Die Doku stellt das klar (neues ADR-035). C2, C3, C5 und C9 bleiben als Risiken in Kap. 11 geführt. |
| d) Kundin | Der **Profi-Einsatz** setzt eine **Server-Installation** durch einen IT-Dienstleister voraus. Docker auf dem Büro-PC ist nicht vorgesehen, der Docker-Schnellstart dient nur der Erprobung. | D1, D2 und D4 (Laien-Installation, Laien-Backup) entfallen. Die Doku kennzeichnet den Schnellstart als Erprobung. Die Ist-Architektur auf dem Server bleibt unverändert. |
| a) Architekt | Welle 1 wird sachlich vorbereitet, ohne Übertreibung. | Offene Entscheidungen (RPO/RTO, Migration, DB-Sperre) erscheinen als Risiko in Kap. 11, nicht als beschlossene Ziele. |

---

## 1. Sofort-Korrekturen (reine Doku, ein Commit, P1/P2)

Diese Widersprüche sind verifiziert und schnell behebbar.

| # | Widerspruch | Fundstellen | Korrektur |
|---|---|---|---|
| K1 | Laut Handbuch liegt das Archiv „unverändert im angeschlossenen Rechnungsarchiv“. ADR-034 sagt dagegen: „not a document archive … no retention“. Das Archiv kommt laut Handbuch erst in einer kommenden Ausbaustufe. | `user-guide.adoc:166`, `arc42/decisions/034-missing-original-document.adoc:10-11`, `user-guide.adoc:588` | Handbuch an ADR-034 angleichen und klar sagen, dass das Archiv in der Verantwortung des Betreibers liegt. **P1** |
| K2 | Der Excel-Export verspricht eine „Lückenlose GoBD-Änderungshistorie“. Dasselbe Handbuch nennt das Journal „nicht revisionssicher“. | `user-guide.adoc:351`, `:581-584` | Durch „Änderungshistorie (Statuswechsel, Zeitstempel, Nutzer)“ ersetzen. **P1** |
| K3 | Stornobeleg: Laut Glossar ist er „independently numbered“. Laut Kap. 08 entsteht er direkt als `CANCELLED`, Nummern gibt es aber nur beim Übergang nach `SENT`. | `arc42/12-glossary.adoc:179-181`, `arc42/08-crosscutting.adoc:324-327`, `:434-435` | Festlegen und dokumentieren, ob der Storno eine eigene fortlaufende Nummer erhält und wie Journal und Auswertungen ihn erfassen. **P1** |
| K4 | Typ 381 wird als „Abrechnung durch den Leistungsempfänger“ beschrieben. Nach UNTDID 1001 ist 381 = Credit Note und 389 = Self-billed Invoice (Gutschrift i. S. v. §14 Abs. 2 S. 2 UStG). | `user-guide.adoc:571` | Typcodes korrigieren. Storno als 384 mit voller Vorzeichenumkehr mit der Steuerberatung abstimmen. **P2** |
| K5 | Die Doku nennt 10 Jahre Aufbewahrung. Seit BEG IV (2025) gelten für Rechnungen und Buchungsbelege 8 Jahre (§147 Abs. 3 AO, §14b UStG). | `arc42/01-introduction.adoc:80`, `arc42/08-crosscutting.adoc:184`, `user-guide.adoc:715` | Auf 8 Jahre (Rechnungen/Belege) bzw. 10 Jahre (Bücher, Abschlüsse) differenzieren. **P2** |
| K6 | „IDW PS 880“ wird als E-Mail-Archivierungsstandard dargestellt. Tatsächlich ist er ein Standard zur Prüfung von Softwareprodukten. | `user-guide.adoc:588` | Korrigieren oder streichen. **P3** |
| K7 | Kapitel 10 nennt „five quality goals“, Kapitel 01 und 04 nennen sechs (inkl. Green IT). | `arc42/10-quality-requirements.adoc:5,13`, `arc42/04-solution.adoc:5` | Angleichen. **P2** |
| K8 | Der Recovery-Scan läuft laut Text alle 5 Minuten, der dokumentierte Default ist `1h`. | `arc42/08-crosscutting.adoc:981,1474` vs. `:882`, `arc42/10-quality-requirements.adoc:122` | Einen Wert festlegen. **P2** |
| K9 | Authentik heißt einmal „optional overlay“ und einmal „IdP of every instance“. | `arc42/08-crosscutting.adoc:1161` vs. `arc42/07-deployment.adoc:30-32`, `arc42/03-context.adoc:54` | Angleichen. **P2** |
| K10 | Laut README gibt es SBOM „je Image“ mit OWASP-Prüfung, laut arc42 je Maven-Modul. OWASP fehlt in arc42. | `readme.adoc:129` vs. `arc42/04-solution.adoc:215-216` | Angleichen. **P2** |
| K11 | ADR-034 widerspricht sich: Erst heißt es „ohne Kennzeichnung neu erzeugt“, dann „always … a reproduction“. Die Kennzeichnung steht nur in DB/UI, nicht in der Datei. | `decisions/034-…adoc:22-23` vs. `:52-54` | Widerspruch auflösen und entscheiden, ob die Reproduktion in der Datei selbst erkennbar sein muss. **P2** |
| K12 | Der Begriff „local-first“ meint hier Self-hosted/On-Prem, nicht Offline-Fähigkeit. | `arc42/01-introduction.adoc:93` | Durch „self-hosted“ ersetzen. **P3** |
| K13 | Der Filter „überfällige Posten“ setzt einen Zahlungsstatus voraus. Das Statusmodell endet aber bei `SENT`/`CANCELLED`. | `user-guide.adoc:325`, `arc42/08-crosscutting.adoc:319-322` | Filter korrekt benennen („Fälligkeit überschritten“) oder Zahlungsstatus einführen (siehe H5). **P2** |

---

## a) Software-Architekt: Ist alles für eine zeitgemäße Anwendung erfüllt?

**Urteil:** Fachlogik, Gates, Nebenläufigkeit und Restart-Resilienz sind gut und prüfbar dokumentiert. Es fehlen Recht, Datenschutz, Betriebssicherheit und der Pfad zum ersten Live-Betrieb.

| # | Befund | Beleg | S/Ü | Prio | Empfehlung |
|---|---|---|---|---|---|
| A1 | Kap. 02 enthält keine rechtlichen Randbedingungen (UStG, AO, GoBD, EN 16931, DSGVO). Gerade diese Normen treiben die Architektur. | `arc42/02-constraints.adoc:5-72` | S | P1 | Abschnitt „Rechtliche Randbedingungen“ mit Norm → Architekturauswirkung anlegen. |
| A2 | DSGVO kommt in arc42 nicht vor: kein Löschkonzept, kein Umgang mit dem Konflikt zwischen Löschpflicht und Aufbewahrungspflicht (Art. 17 Abs. 3 lit. b), keine Auskunft. | arc42/* (0 Treffer), nur `betrieb-und-wirtschaftlichkeit.adoc:465` | S | P1 | ADR „Sperren statt Löschen bis Fristablauf“ und ein Löschlauf nach Fristende. |
| A3 | Backup ohne RPO/RTO. Off-site nur „optional“, kein Restore-Test-Rhythmus. Bei einem Single Host droht Totalverlust. | `arc42/10-quality-requirements.adoc:146-154`, `arc42/07-deployment.adoc:110,292` | S | P1 | RPO ≤ 24 h und RTO ≤ 4 h festlegen, Off-site verpflichtend, Restore-Test quartalsweise. |
| A4 | Keine Migrationsstrategie ab dem ersten Live-Betrieb. Das Greenfield-Baseline-Prinzip endet dort ohne Folgeplan. | `arc42/08-crosscutting.adoc:616`, `arc42/07-deployment.adoc:243` | S | P1 | Folge-ADR mit Auslöser („erste produktive Instanz“) und Risiko in Kap. 11. |
| A5 | Keine Verschlüsselung im Ruhezustand. Nur die Backups sind mit age verschlüsselt, ein Mini-PC im Büro ist diebstahlgefährdet. | `arc42/07-deployment.adoc:104-111`, `arc42/08-crosscutting.adoc:1156` | S | P2 | Entscheidung „LUKS auf dem Host“ in einem Satz dokumentieren, kein App-Feature. |
| A6 | Login per `LT_APP_AUTH_ENABLED` abschaltbar, ein Schutz für Prod ist nicht dokumentiert. Kein Bedrohungsmodell. | `arc42/08-crosscutting.adoc:1336-1338` | S | P2 | Start mit `%prod` und deaktiviertem Auth ausdrücklich verhindern (Fail-fast). Ein kurzes STRIDE-Modell genügt. |
| A7 | Kein Qualitätsszenario für Sicherheit und Antwortzeit. | `arc42/10-quality-requirements.adoc:19-179` | S | P2 | Je ein Szenario, z. B. „Rechnung festschreiben < 2 s p95“. |
| A8 | Die Lücke bei Logging/Tracing ist bekannt („honest gap“), fehlt aber in Kap. 11. „Alerts“ werden erwähnt, aber nicht definiert. | `arc42/08-crosscutting.adoc:1447-1451`, `arc42/03-context.adoc:59` | S | P2 | Als technische Schuld führen und Minimal-Alerting definieren (Disk, Backup fehlgeschlagen, Zertifikat). Volles OTel wäre **Ü**. |
| A9 | Die E-Rechnungspflicht (Empfang 2025, Versand 2027/2028) ist nicht eingeordnet, der Empfang nicht abgegrenzt. | `arc42/01-introduction.adoc:97`, `arc42/03-context.adoc:197` | S | P2 | Scope-Satz: „Empfang erfolgt in einem Fremdsystem“ (siehe B5). |
| A10 | VIES und oMLX fehlen im Kontextdiagramm. | `arc42/03-context.adoc:19-75`, `decisions/023-…adoc:17` | S | P3 | Ergänzen. |
| A11 | Zwei CI-Plattformen (Forgejo + GitHub Actions) und Dagger, ohne ADR. | `arc42/04-solution.adoc:208-211,278,285` | S | P3 | ADR oder eine Plattform streichen. |
| A12 | Kap. 08 hat 1.515 Zeilen: Concurrent Editing rund 120 Zeilen, Security & Operations nur 24. Die Gewichtung ist umgekehrt. | `arc42/08-crosscutting.adoc:628-745` vs. `:1142-1165` | Ü | P2 | ADR-Duplikate (028, 014, 032) auf Verweise kürzen, Ziel −30 %. Security ausbauen. |
| A13 | „AI Delegation Model“ und R-8 Kotlin-Daemon sind Entwicklungsprozess, keine Architektur. | `arc42/08-crosscutting.adoc:1500ff`, `arc42/11-risks-and-technical-debts.adoc:105-116` | Ü | P3 | Aus arc42 entfernen und ins Entwicklerhandbuch verschieben. |
| A14 | „NATS vs. Kafka Enterprise Evolution“ bei 20–200 Rechnungen pro Monat. | `arc42/08-crosscutting.adoc:804,1466` | Ü | P3 | Streichen, ADR-020 reicht. |
| A15 | Termine für Phase 1/2 (Q4/2027) stehen 23-mal im Text. | Kap. 01, 03, 04, 05, 07, 08, 11, 12, Canvas | Ü | P3 | Einmal in Kap. 07 halten, sonst verlinken. |
| A16 | Betriebsaufwand des Stacks: NATS + Valkey + RustFS + Authentik + Prometheus/Grafana + Mail-SCS für ein Büro mit einer Person. | `arc42/07-deployment.adoc:28-33` | S/Ü | P3 | Technisch begründet (ADR-015/020). Valkey mit Kosten/Nutzen belegen oder streichen. Ein „Minimal-Profil“ für Kleinstbetriebe prüfen. |

**Gut so:** prüfbare Qualitätsszenarien mit Testnamen (`arc42/10-quality-requirements.adoc:17-179`), Risiken an ADRs gekoppelt (`arc42/11-…:12-38`), Gitleaks/Trivy/SBOM (`arc42/04-solution.adoc:280-286`), kein `%prod`-Secret-Fallback (`arc42/08-crosscutting.adoc:1292-1304`), bewusst keine Mandantenfähigkeit (`arc42/01-introduction.adoc:100`), ADR-Hygiene (`arc42/09-decisions.adoc:11,36`). Für Barrierefreiheit reicht WCAG-AA plus ein manueller Durchgang (TD-1), das BFSG greift für ein B2B-Bürowerkzeug eher nicht.

---

## b) Steuerberaterin: Ist die lokal betriebene Anwendung gut für Mandanten?

**Urteil:** Für Freiberufler mit Inlands- oder EU-B2B-Rechnungen ist sie brauchbar: valide EN-16931-Rechnungen, sauberer Storno und Nummernkreis, Excel mit Kontrollformeln. Für Handwerker-Mandanten und die Zusammenarbeit mit der Kanzlei reicht sie nicht. Es gibt keinen DATEV-Export und keinen Zahlungsstatus, die Kanzlei muss also neu erfassen.

| # | Thema | Sachverhalt | Norm / Auswirkung | S/Ü | Prio |
|---|---|---|---|---|---|
| B1 | DATEV-Export | Kein Treffer in der gesamten Doku. Übergabe nur als Excel/CSV (`user-guide.adoc:104,330-352`). | Die Kanzlei erfasst doppelt, das kostet Zeit und Honorar. | S | P1 |
| B2 | §13b-Bauleistungen im Inland | Reverse Charge nur grenzüberschreitend, Kategorie AE (`decisions/003-…adoc:8`, `user-guide.adoc:403-405`). | §13b Abs. 2 Nr. 4, Abs. 5 UStG. Ohne Lösung wird USt falsch ausgewiesen, es droht Steuerschuld nach §14c. | S | P1 |
| B3 | Abschlags- und Schlussrechnung | Nicht dokumentiert. | §14 Abs. 5 UStG: Die Schlussrechnung muss die Teilentgelte und die darauf entfallende Steuer abziehen, sonst droht eine doppelte Steuerschuld. Für das Handwerk ist das zentral. | S | P1 |
| B4 | Kleinunternehmer §19 | Nur Nebensatz (`user-guide.adoc:391`). | §19 UStG n. F. (25k/100k), Kategorie E, Pflichthinweis. | S | P1 |
| B5 | Empfang von E-Rechnungen | Nicht dokumentiert. Der Belegpool ist „kein Belegarchiv“ (`user-guide.adoc:166`). | §14 Abs. 2 UStG: Empfangspflicht seit 1.1.2025, auch für Kleinunternehmer. Mindestens als Scope-Grenze klar benennen. | S | P1 (Doku) / P3 (Feature) |
| B6 | Backup und Langzeitstand | 7 tägliche + 4 wöchentliche Stände (`arc42/10-…:151`). Skripte erst Q4/2027 öffentlich (`arc42/07-deployment.adoc:270-274`). | §147 AO: 8 Jahre lesbar. Lokal haftet der Mandant. | S | P1 |
| B7 | Zahlungseingang / OP / Ist-Versteuerung | Kein Zahlungsstatus (`arc42/08-crosscutting.adoc:319-322`). | Ohne Zahlungsdatum ist die Ist-Versteuerung (§20 UStG) nicht auswertbar. | S | P2 |
| B8 | §35a Lohnanteil | Nicht dokumentiert, obwohl B2C vorgesehen ist (`arc42/01-introduction.adoc:122`). | §35a Abs. 5 EStG: Privatkunden brauchen den getrennten Ausweis von Arbeitslohn. | S | P2 |
| B9 | Verfahrensdokumentation | Nur ein Hinweis auf die Pflicht des Betreibers (`user-guide.adoc:589`). | GoBD Rz. 151 ff. Eine Vorlage spart Mandant und Kanzlei viel Aufwand. | S | P2 |
| B10 | Zugang für die Steuerberatung | Nur Dateiübergabe (`arc42/03-context.adoc:27-29`). | Eine Leserolle genügt. | S | P2 |
| B11 | Teilkorrektur / Gutschrift 389 | Nur Vollstorno (`user-guide.adoc:570-574`). | Für die Zielgruppe selten. | S | P3 |
| B12 | Mahnwesen, Mandantenportal | Nicht dokumentiert. | Nice-to-have. Erst nach B7 sinnvoll. | Ü (jetzt) | P3 |
| B13 | Werbende Aussagen | „Verhindert die Ablehnung … durch ZRE/OZG-RE“ (`user-guide.adoc:179`), „lückenlos“ neben „nicht revisionssicher“. | Widerspricht der Wording-Regel. | S | P2 |

**Vorhanden und gut:** lückenloser Nummernkreis mit Hash-Kette (`arc42/08-crosscutting.adoc:418-432`), KoSIT-Validierung vor der Festschreibung (`arc42/01-introduction.adoc:47-48`), Storno 384 mit Bezug BT-25/26 (`arc42/12-glossary.adoc:174-180`), Excel mit nachrechenbaren Formeln (`decisions/018-…adoc:8`).

---

## c) Finanzbeamtin: Gibt es Auffälligkeiten für Prüfungen?

**Urteil:** Bei Nummernvergabe und Festschreibung liegt die Architektur über typischer KMU-Software. Die erste Prüfungsfrage wäre: Wo liegen die unveränderbaren Originale für 8 Jahre? Danach folgen der dokumentierte `psql`-Korrekturpfad mit frei setzbarem Zeitstempel und die unklare Storno-Nummer.

| # | Sachverhalt | Norm | Risiko bei Prüfung | Prio |
|---|---|---|---|---|
| C1 | Kein WORM-Archiv (ADR-034), aber das Handbuch behauptet eins (K1). Die Backup-Historie reicht nur rund 5 Wochen. | §147 Abs. 3 AO, §14b UStG, GoBD Rz. 58 ff. | Verlust oder Manipulation, die erst nach mehr als 5 Wochen bemerkt wird, ist nicht wiederherstellbar. Das kann zur Verwerfung der Ordnungsmäßigkeit führen. | **P1** |
| C2 | Direkte `psql`-Korrekturen an `invoice`, `invoice_item` und `invoice_document` sind als Verfahren dokumentiert (`arc42/07-deployment.adoc:335-352`). Die Schreibsperre für `SENT` greift nur in der Anwendung, DB-seitig sind nur Belege gesperrt (`arc42/05-building-blocks.adoc:573-574`). | GoBD Rz. 58-60, 107 ff. | Der Admin kann festgeschriebene Rechnungen ändern. | **P1** |
| C3 | `app.now` setzt der Operator frei (`arc42/07-deployment.adoc:347`, `decisions/029-…adoc:47-51`). Eine Plausibilitäts- oder Monotonieprüfung ist nicht dokumentiert. | GoBD Rz. 46 ff., 64 | Journal-Einträge lassen sich rückdatieren. Die Hash-Kette erkennt nur spätere Änderungen. | **P1** |
| C4 | Storno-Nummerierung unklar (K3). Status `CANCELLED`: Auswertungen nur über `SENT` übersehen negative Umsätze. | §14 Abs. 4 Nr. 4 UStG | Umsatzverprobung fehlerhaft. | **P1** |
| C5 | Ein älteres Backup wird mit `pg_restore --clean` eingespielt (`arc42/07-deployment.adoc:357`), die Nummernzähler springen zurück. | §14 Abs. 4 Nr. 4 UStG | Doppelte Rechnungsnummern. | P2 |
| C6 | Original und Reproduktion sind außerhalb des Systems nicht unterscheidbar (K11). | GoBD Rz. 58, §14b UStG | Echtheit der Herkunft nicht belegbar. | P2 |
| C7 | Datenzugriff nur über Excel/CSV und einzelne PDF/XML. Es fehlt ein Sammel-Export der Original-XML (Z3) und eine Leserolle für Prüfer (Z1/Z2). | §147 Abs. 6 AO | Die Datenträgerüberlassung ist aufwendig und fehleranfällig. Maßgeblich ist das XML, nicht das PDF. | P2 |
| C8 | Leistungsdatum: Pflicht nur über ein B2B-Profil belegt (`user-guide.adoc:648`). | §14 Abs. 4 Nr. 6 UStG | Rechnung ohne Leistungsdatum, kein Vorsteuerabzug beim Empfänger. | P2 |
| C9 | Append-only ist nur für das Nummernjournal belegt. Die Postgres-Rule fängt `TRUNCATE` und `DROP RULE` durch den Tabelleneigentümer nicht ab (`arc42/08-crosscutting.adoc:454-456`). | GoBD Rz. 64 | Journal manipulierbar durch den DB-Owner. | P2 |
| C10 | Löschkonzept und Softwarewechsel/Migration nicht dokumentiert. | Art. 17 Abs. 3 DSGVO, GoBD Rz. 142 | Daten müssen nach einem Wechsel lesbar bleiben. | P3 |

**Gut gelöst:** Nummer erst beim Übergang nach `SENT` in derselben Transaktion, verworfene Entwürfe ohne Nummer (`arc42/08-crosscutting.adoc:434`). Siegel pro Periode und unabhängiger Go-Prüfer (`:427-476`). Abgleich gegen doppelte Nummern (`:462-464`). Hash-Prüfung beim Ausliefern (`decisions/034-…adoc:18-20`). Jahreswechsel richtig in Europe/Berlin (`arc42/08-crosscutting.adoc:437-442`). Testdaten und Echtdaten getrennt (`arc42/07-deployment.adoc:25,60`).
**Übertrieben, aber vertretbar:** Die unabhängige Go-Neuberechnung geht über die GoBD hinaus. Sie ist ein starkes Beweismittel und sollte bleiben.

---

## d) Kundin: Handwerksbetrieb, Ehefrau macht abends die Buchhaltung

**Urteil (sinngemäß):** „Ich bekomme das nicht zum Laufen. Der Schnellstart heißt Docker und `make`, und wer Updates und Sicherung macht, steht nirgends. Das Handbuch erklärt mir Validierungsketten und Profilregeln, aber nicht, wie ich eine Abschlagsrechnung schreibe oder eine Zahlung abhake. Angebotsübernahme, PDF mit GiroCode und der Excel-Export für die Steuerberaterin helfen mir.“

| # | Befund | Beleg | Klasse | Prio |
|---|---|---|---|---|
| D1 | Der Schnellstart ist ein Entwicklerstart (`make secrets`/`make dev`, Docker Compose). | `user-guide.adoc:61-71`, `readme.adoc:98-111` | verstehe ich nicht | P1 |
| D2 | Keine Laien-Anleitung für Installation, Update, Backup und Restore. „Was, wenn der PC kaputtgeht?“ bleibt unbeantwortet. | `arc42/07-deployment.adoc:19-31` (nur Admin-Sicht), `betrieb-…:321-326` | fehlt mir | P1 |
| D3 | Handwerker-Workflow fehlt: Abschlag/Schluss, Zahlungseingang, Mahnung, §35a, §13b, Kleinunternehmer (0 Treffer). | `user-guide.adoc` | fehlt mir | P1 |
| D4 | Keine Anleitung „Erste Rechnung in 10 Minuten“. Das Handbuch hat 743 Zeilen und ist als Referenz statt als Aufgabenfolge aufgebaut. | `user-guide.adoc` | fehlt mir | P1 |
| D5 | Technikjargon in der Anwenderdoku: Quarkus, Vaadin, NATS, Valkey, S3 (`:63-80`), Mustang/KoSIT/veraPDF/„4-Hop-Kette“ (`:495-519,683-715`), BT-Nummern und XOR-Regeln (`:639-662`), Status als `DRAFT`/`SENT`/`isReadOnly` (`:540-565`). | s. Spalte Befund | verstehe ich nicht | P2 |
| D6 | ZUGFeRD und XRechnung werden für Laien nicht erklärt. Im README steht dazu nur der Namensursprung (`readme.adoc:146`). | — | fehlt mir | P2 |
| D7 | Fehlermeldungen zeigen `BR-DE-15` und VIES ohne Lösungsschritt in Alltagssprache. | `user-guide.adoc:198-213` | verstehe ich nicht | P2 |
| D8 | Nicht dokumentiert: Kunden- und Artikelimport (CSV), Anfahrtspauschale/Stunden, Aufmaß/Regie, Tablet auf der Baustelle, FAQ. | — | fehlt mir | P2 (Import, FAQ) / P3 (Aufmaß, Tablet) |
| D9 | Leitidee „Digitale Souveränität, Genossenschaftliche Selbsthilfe“ (`:144`), „Vertiefung für Geschäftsführung und IT-Leitung“ (`:726`), FinOps/Shadow IT/Managed Kubernetes/CO₂e (`betrieb-…:328-333,646`). | s. Spalte Befund | brauche ich nicht | P3 |
| D10 | Hinweis „KI-gestützt erstellt“ (`user-guide.adoc:47`). Er ist ehrlich, senkt aber das Vertrauen von Laien. | — | — | P3 (Platzierung prüfen, nicht streichen) |

**Hilft mir:** 1-Klick-Übernahme von Angebot zu Rechnung (`user-guide.adoc:90`), DIN-5008-PDF mit GiroCode (`:109-133`), Umschaltung auf Handwerksbegriffe/Material (`:274-288`), Excel für die Steuerberaterin (`:327-353`), viele Screenshots.

---

## 2. Bewertung: logisch vs. übertrieben

| Kategorie | Logisch umsetzen | Übertrieben, nicht verfolgen |
|---|---|---|
| Doku-Hygiene | K1–K13, Kap. 02 Recht, DSGVO-ADR, Kap. 08 kürzen | Phase-Termine 23×, NATS-vs-Kafka, AI-Delegation in arc42 |
| Betrieb | RPO/RTO, Off-site-Pflicht, Restore-Test, LUKS-Satz, Auth-Fail-fast | Volles OTel/Tracing, Managed Kubernetes, FinOps |
| Prüfung | WORM bzw. Archivverantwortung klar, DB-Sperre für `SENT`, `app.now`-Monotonie, Z3-XML-Export | Eigenes zertifiziertes Archiv bauen (statt Object-Lock oder externem Archiv) |
| Fachlich Handwerk | Abschlag/Schluss, §13b-Inland, §19, Zahlungsdatum, DATEV-EXTF | Aufmaß/Regie, Mahnwesen, Mandantenportal, Tablet-App (erst nach Kernfunktionen) |
| Anwenderdoku | Laien-Schnellstart, Betriebshandbuch für Nicht-IT, Handwerker-Kapitel, Glossar in Alltagssprache | Technikabschnitte im Handbuch (in arc42 verschieben, nicht duplizieren) |

---

## 3. Handlungsempfehlung

1. **Welle 1 (Doku, sofort, 1 Worktree):** K1–K13 bereinigen, Kap. 02 um rechtliche Randbedingungen ergänzen, ADRs zu (a) Löschen/Aufbewahren, (b) Backup RPO/RTO + Off-site + WORM/Archivverantwortung, (c) Migration ab erstem Live-Betrieb. Scope-Satz für E-Rechnungs-Empfang und DATEV. Kap. 08 um rund 30 % kürzen.
2. **Welle 2 (Produktentscheid PO):** Zielgruppe festlegen. Bleibt das Handwerk Zielgruppe, sind Abschlags-/Schlussrechnung, §13b-Inland, §19 Kategorie E, Zahlungsdatum und DATEV-Buchungsstapel (EXTF) das fachliche Minimum. Dazu technische Prüffestigkeit: DB-Trigger-Sperre für `SENT`, `app.now`-Monotonie, Storno-Nummer, Z3-Sammelexport. Anschließend das Handbuch in eine Laien-Fassung (Aufgabenfolge) und einen Technikanhang teilen.

---

## 4. Umsetzungsstand Welle 1 (2026-10-07)

| Erledigt | Wo |
|---|---|
| Scope: reines Rechnungsprogramm, Übergabe an die Buchhaltung, Out-of-Scope-Liste, Erprobung vs. Server-Installation | `arc42/01-introduction.adoc`, `arc42/07-deployment.adoc`, `user-guide.adoc`, `readme.adoc` |
| Rechtliche Randbedingungen als Klartext-Tabelle ohne Paragraphen | `arc42/02-constraints.adoc` |
| ADR-035: Archiv liegt beim Betreiber, E-Mail-Archiv empfohlen | `arc42/decisions/035-…adoc`, `arc42/09-decisions.adoc` |
| K1, K2, K4, K5, K6, K12, K13, Werbesätze | `user-guide.adoc`, `arc42/01`, `arc42/08` |
| K3 Storno: Der Code vergibt die Nummer bei der Erstellung (`InvoiceStornoService.kt`). Glossar war korrekt, Kap. 08 und 02 ergänzt. | `arc42/08-crosscutting.adoc`, `arc42/02-constraints.adoc` |
| K7: sechs Qualitätsziele, Qualitätsbaum um Green IT ergänzt | `arc42/10-quality-requirements.adoc`, `plantuml/quality-tree.puml` |
| K8: zwei Scans (Jobs 1 h, Maintenance 5 min) benannt | `arc42/08-crosscutting.adoc` |
| K9, K10, K11 angeglichen | `arc42/08`, `arc42/04-solution.adoc`, `readme.adoc`, `decisions/034-…adoc` |
| DSGVO-Abschnitt; Auth-Schalter nur für Entwicklung, ohne Startprüfung | `arc42/08-crosscutting.adoc` |
| Entfernt: AI Delegation Model, NATS-vs-Kafka-Tabelle samt Diagramm | `arc42/08-crosscutting.adoc`, `plantuml/event-backbone-evolution.puml` |
| Offene PO-Entscheide als Risiken: R-9 (RPO/RTO, Off-site), R-10 (Migration ab Go-live), R-11 (DB-Schreibrechte, `app.now`), R-12 (Restore und Nummern), TD-2 (Alerting) | `arc42/11-risks-and-technical-debts.adoc` |

**Offen:**
- Kap. 08 um rund 30 % kürzen (ADR-Duplikate)
- Phase-Termine entdoppeln
- VIES und oMLX im Kontextdiagramm ergänzen
- Produktfragen zum Rechnungsinhalt: §13b im Inland, Abschlag/Schluss, §19, §35a
- Handbuch-Jargon (D5–D7)

## 5. PO-Entscheide Rechnungsinhalt (2026-10-07)

| Punkt | Entscheid | Stand |
|---|---|---|
| B4 Kleinunternehmer | Kommt nie vor: Absender ist immer umsatzsteuerpflichtig. Alle Erwähnungen entfernt. | Doku, KDoc, Hilfetext zu BT-32 (de/en), Testdaten und Angebots-Planung bereinigt. Nur die externen ZUGFeRD-Beispiele und Altdokumente unter `docs/old` nennen den Begriff noch. |
| B3 Abschlags-/Schlussrechnung | Wird nicht angeboten. | In Out of Scope (Kap. 01) und Handbuch eingetragen. |
| B8 Lohnanteil für Privatkunden | Keine Pflichtangabe, aber Kundennutzen; Entscheidung offen. | Siehe Empfehlung im Gespräch: optionale Angabe „enthaltene Arbeits-, Maschinen- und Fahrtkosten (brutto)“. |
| B2 Umkehr der Steuerschuld bei Bauleistungen im Inland | Nicht im Standard. Bisherige Handwerkskunden brauchten es nicht, Schwerpunkt ist normales B2B. Zusatzfunktion nur auf Beauftragung. | In Out of Scope (Kap. 01) und Handbuch eingetragen. |
