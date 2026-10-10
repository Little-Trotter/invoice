' Shared styling for all Little Trotter sequence diagrams
' Ensures consistent fonts, font sizes, colors, and line styles across all 8 runtime flows.

skinparam defaultFontName "Roboto, Segoe UI, sans-serif"
skinparam defaultFontSize 12
skinparam sequenceMessageAlign center
skinparam shadowing false
skinparam linetype ortho
skinparam roundCorner 0

<style>
sequenceDiagram {
  lifeLine {
    LineColor #5F6368
    LineThickness 1.5
    LineStyle 6-4
  }
}
</style>

skinparam maxMessageSize 180
skinparam sequenceReferenceBackgroundColor #F8F9FA
skinparam noteBackgroundColor #FEF5E0
skinparam noteBorderColor #E65100

skinparam sequence {
  ParticipantFontSize 12
  ActorFontSize 12
  ArrowFontSize 11
  MessageFontSize 11
  GroupFontSize 11
  ReferenceFontSize 11
  ParticipantBackgroundColor #3C88EB
  ParticipantBorderColor #2D66B0
  ParticipantFontColor #ffffff
  ActorBackgroundColor #5F6368
  ActorBorderColor #4C4F53
  ActorFontColor #ffffff
  LifeLineBackgroundColor #E8F0FE
  LifeLineBorderColor #5F6368
  ArrowColor #5F6368
  ArrowFontColor #202124
  ActivationBackgroundColor #E8F0FE
  ActivationBorderColor #1A73E8
}

skinparam database {
  FontSize 12
  BackgroundColor #1662C5
  BorderColor #1456AE
  FontColor #ffffff
}

skinparam database<<cache>> {
  FontSize 12
  BackgroundColor #D97706
  BorderColor #B45309
  FontColor #ffffff
}

skinparam database<<storage>> {
  FontSize 12
  BackgroundColor #0D9488
  BorderColor #0F766E
  FontColor #ffffff
}

skinparam queue {
  FontSize 12
  BackgroundColor #1250A2
  BorderColor #0E3C7A
  FontColor #ffffff
}

skinparam legend {
  BackgroundColor #FFFFFF
  BorderColor #DADCE0
  FontSize 10
  FontColor #202124
}

hide stereotype

skinparam note {
  FontSize 11
  FontColor #202124
}

' Storage & Messaging Legend for sequence diagrams
!procedure $SequenceStorageLegend()
legend top left
|= **Storage & Messaging Legend** |
|<#1662C5> <color:#FFFFFF> Database (PostgreSQL)    </color> |
|<#D97706> <color:#FFFFFF> Cache (Valkey)           </color> |
|<#0D9488> <color:#FFFFFF> Object Store (RustFS S3) </color> |
|<#1250A2> <color:#FFFFFF> Stream (NATS JetStream)  </color> |
endlegend
!endprocedure

