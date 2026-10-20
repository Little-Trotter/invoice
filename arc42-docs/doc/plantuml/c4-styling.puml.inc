!include <C4/C4_Context>
!include <C4/C4_Container>
!include <C4/C4_Component>

' A42-11 — Little Trotter app color notation (verified against
' kotlin-invoice/src/main/frontend/themes/littletrotter/styles.css):
' accent-blue family for core system/container/component/database,
' secondary-grey family for people/externals. Kept in sync by hand with the
' element styles in doc/c4/workspace.dsl and doc/css/arc42-docs.css's
' token-mapping comment — there is no shared build step across DSL/PlantUML/
' CSS in this repo, see docs/build-arc42/a42-11-design-app-theme.md task 5.
!define PERSON_BG_COLOR #5F6368
!define PERSON_BORDER_COLOR #4C4F53
!define SYSTEM_BG_COLOR #1A73E8
!define SYSTEM_BORDER_COLOR #1456AE
!define EXTERNAL_BG_COLOR #9AA0A6
!define EXTERNAL_BORDER_COLOR #74787C
!define CONTAINER_BG_COLOR #3C88EB
!define CONTAINER_BORDER_COLOR #2D66B0
!define COMPONENT_BG_COLOR #81B2F2
!define COMPONENT_BORDER_COLOR #5A7DA9
!define DATABASE_BG_COLOR #1662C5
!define DATABASE_BORDER_COLOR #1456AE

skinparam defaultFontName "Roboto Condensed, Arial Narrow, sans-serif-condensed, sans-serif"
skinparam defaultFontSize 12
skinparam shadowing false
skinparam roundCorner 0

skinparam person {
  FontSize 12
  BackgroundColor PERSON_BG_COLOR
  FontColor #ffffff
  BorderColor PERSON_BORDER_COLOR
}

skinparam rectangle<<system>> {
  FontSize 14
  BackgroundColor SYSTEM_BG_COLOR
  FontColor #ffffff
  BorderColor SYSTEM_BORDER_COLOR
  roundCorner 0
}

skinparam rectangle<<external>> {
  FontSize 12
  BackgroundColor EXTERNAL_BG_COLOR
  FontColor #202124
  BorderColor EXTERNAL_BORDER_COLOR
  roundCorner 0
}

skinparam rectangle<<container>> {
  FontSize 13
  BackgroundColor CONTAINER_BG_COLOR
  FontColor #202124
  BorderColor CONTAINER_BORDER_COLOR
  roundCorner 0
}

skinparam rectangle<<component>> {
  FontSize 12
  BackgroundColor COMPONENT_BG_COLOR
  FontColor #202124
  BorderColor COMPONENT_BORDER_COLOR
  roundCorner 0
}

' Sequence diagram styling with C4 colors
skinparam sequence {
  ParticipantBackgroundColor PERSON_BG_COLOR
  ParticipantBorderColor PERSON_BORDER_COLOR
  ParticipantFontColor #ffffff
  ActorBackgroundColor PERSON_BG_COLOR
  ActorBorderColor PERSON_BORDER_COLOR
  ActorFontColor #ffffff
  LifeLineBackgroundColor SYSTEM_BG_COLOR
  LifeLineBorderColor SYSTEM_BORDER_COLOR
}

skinparam participant {
  BackgroundColor PERSON_BG_COLOR
  BorderColor PERSON_BORDER_COLOR
  FontColor #ffffff
}

skinparam actor {
  BackgroundColor PERSON_BG_COLOR
  BorderColor PERSON_BORDER_COLOR
  FontColor #ffffff
}

skinparam database {
  FontSize 12
  BackgroundColor DATABASE_BG_COLOR
  FontColor #ffffff
  BorderColor DATABASE_BORDER_COLOR
}

' Legend styling for consistent element sizes
skinparam rectangle<<legend>> {
  MinimumWidth 120
  FontSize 10
}

' C4 Model Color Legend - flush left, each element on a line, EXACT same length (18 chars)
!procedure $SystemContextLegend()
legend top left
|= **C4 Legend** |
|<back:#5F6368><color:#FFFFFF> Person            </color></back>|
|<back:#1A73E8><color:#FFFFFF> Software System   </color></back>|
|<back:#9AA0A6><color:#202124> External System   </color></back>|
endlegend
!endprocedure

!procedure $ContainerLegend()
legend top left
|= **C4 Legend** |
|<back:#5F6368><color:#FFFFFF> Person            </color></back>|
|<back:#3C88EB><color:#202124> Container         </color></back>|
|<back:#1662C5><color:#FFFFFF> Database          </color></back>|
|<back:#9AA0A6><color:#202124> External System   </color></back>|
endlegend
!endprocedure

!procedure $ComponentLegend()
legend top left
|= **C4 Legend** |
|<back:#81B2F2><color:#202124> Component         </color></back>|
|<back:#1662C5><color:#FFFFFF> Database          </color></back>|
|<back:#9AA0A6><color:#202124> External System   </color></back>|
endlegend
!endprocedure

!procedure $SequenceLegend()
legend top left
|= **C4 Legend** |
|<back:#5F6368><color:#FFFFFF> Person            </color></back>|
|<back:#3C88EB><color:#202124> Container         </color></back>|
|<back:#81B2F2><color:#202124> Component         </color></back>|
|<back:#9AA0A6><color:#202124> External System   </color></back>|
endlegend
!endprocedure
