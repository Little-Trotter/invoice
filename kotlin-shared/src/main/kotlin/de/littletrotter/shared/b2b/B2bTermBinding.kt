package de.littletrotter.shared.b2b

/**
 * Whether a [B2bTerm]'s business term already has a field in the
 * existing model.
 */
enum class B2bTermBinding {
    /** No field carries this term yet; a profile that uses it creates one and stores its value in
     *  `b2b_field_value`. */
    FREE,

    /** An existing property already carries this term ([B2bTerm.boundProperty]); a
     *  profile field for it expresses only the *requirement* (optional/mandatory/conditional), no
     *  second input and no own value row are created. */
    BOUND,
}
