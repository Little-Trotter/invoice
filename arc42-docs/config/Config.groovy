// Little Trotter X-Invoice - docToolchain configuration
// HTML5 output only (no PDF, no microsite, no Confluence/Jira export).

outputPath = 'build'
inputPath = 'doc'

inputFiles = [
    [file: 'readme.adoc', formats: ['html']],
    [file: 'logs.adoc', formats: ['html']],
    [file: 'arc42-docs.adoc', formats: ['html']],
    [file: 'architecture-inception-canvas.adoc', formats: ['html']],
    [file: 'architecture-communication-canvas.adoc', formats: ['html']],
    [file: 'user-guide.adoc', formats: ['html']],
    [file: 'betrieb-und-wirtschaftlichkeit.adoc', formats: ['html']],
    [file: 'glossar.adoc', formats: ['html']],
    [file: 'architecture-glossary.adoc', formats: ['html']]
]

imageDirs = ['images']
failOnMissingImages = false

// docToolchain 3.4.1 liest `taskConfig` NICHT — in der gesamten Installation kommt der Name kein
// einziges Mal vor. Hier stand bis 04.09.2026 ein `taskConfig.generateHTML.attributes`-Block mit
// stylesdir/stylesheet/linkcss/icons!/source-highlighter!/toc/imagesdir/lang/docinfo. Er sah wirksam
// aus, weil bis auf `linkcss` jedes dieser Attribute zusaetzlich im Dokumentenkopf der .adoc-Dateien
// steht und von dort auch tatsaechlich gilt. `linkcss` stand nur hier — und blieb folglich wirkungslos:
// Asciidoctor bettete das Stylesheet ein, statt es zu verlinken, und weil arc42-docs.css im Kern nur
// ein `@import url("arc42-common.css")` ist, zeigte dieser Import aus einem <style>-Block heraus ins
// Leere. Ergebnis waren unformatierte Seiten und ungebremst skalierte Diagramme.
//
// Asciidoctor-Attribute gehoeren deshalb in die Dokumentenkoepfe der .adoc-Dateien, nicht hierher.
// Ein neuer Block an dieser Stelle waere wieder wirkungslos, ohne dass irgendetwas fehlschlaegt.

taskInputsDirs = [
    "${inputPath}",
    "${inputPath}/arc42",
    "${inputPath}/c4",
    "${inputPath}/css"
]

taskInputsFiles = []

//*****************************************************************************************

// Configuration for customTasks
// create a new Task with ./dtcw createTask
customTasks = [
/** customTasks **/
]
