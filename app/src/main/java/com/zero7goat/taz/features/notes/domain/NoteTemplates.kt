package com.zero7goat.taz.features.notes.domain

/** A starting point for a new note. */
data class NoteTemplate(val id: String, val name: String, val title: String, val body: String, val builtIn: Boolean)

/** The templates that ship with TAZ. Their text uses the same Markdown the editor understands. */
object NoteTemplates {
    val builtIn: List<NoteTemplate> = listOf(
        NoteTemplate(
            "builtin:meeting", "Meeting", "Meeting",
            "**Date:** \n**People:** \n\n## Agenda\n- \n\n## Notes\n\n\n## Actions\n- [ ] ",
            true
        ),
        NoteTemplate(
            "builtin:journal", "Journal", "Journal",
            "**How I feel:** \n\n## Today\n\n\n## Grateful for\n- \n\n## Tomorrow\n- [ ] ",
            true
        ),
        NoteTemplate(
            "builtin:todo", "To-do list", "To do",
            "- [ ] \n- [ ] \n- [ ] ",
            true
        ),
        NoteTemplate(
            "builtin:recipe", "Recipe", "Recipe",
            "**Serves:** \n**Time:** \n\n## Ingredients\n- \n\n## Steps\n1. ",
            true
        ),
        NoteTemplate(
            "builtin:travel", "Trip", "Trip",
            "**Dates:** \n**Where:** \n\n## Bookings\n- \n\n## Packing\n- [ ] \n\n## Plans\n",
            true
        )
    )
}
