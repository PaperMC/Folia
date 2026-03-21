package net.azisaba.vanilife.annotations;

public enum Op {
    ADD_ARGUMENT,
    ADD_FIELD,
    ADD_METHOD,
    ADD_ENUM,
    ADD_COLLECTION_ELEMENT,
    ADD_SWITCH_CASE,
    ADD_EVENT_CALL,
    MAKE_PUBLIC,
    MAKE_PRIVATE,
    CHANGE_FIELD_INITIALIZATION,
    COMPAT,

    @Deprecated(forRemoval = true)
    // Use more specific Op.
    CHANGE
}
