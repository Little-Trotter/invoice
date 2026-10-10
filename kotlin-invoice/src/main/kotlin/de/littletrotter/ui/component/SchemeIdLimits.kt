package de.littletrotter.ui.component

/** Length of every `*_scheme_id` column (party identifier, legal registration, electronic
 *  address); UI scheme fields stop typing here instead of failing on save. */
const val SCHEME_ID_MAX_LENGTH = 10

/** Length of the `naf_code` column (recipient); UI nafCode field stops typing here instead
 *  of failing on save. */
const val NAF_CODE_MAX_LENGTH = 5
