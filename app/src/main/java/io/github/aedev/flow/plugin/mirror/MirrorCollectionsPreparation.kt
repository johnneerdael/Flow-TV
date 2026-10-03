package io.github.aedev.flow.plugin.mirror

import nl.neerdael.milkbeat.catalog.PersonalCollection

internal suspend fun prepareMirrorCollections(
    collections: List<PersonalCollection>,
    prepare: suspend (PersonalCollection) -> Unit,
) {
    collections.forEach { collection ->
        try {
            prepare(collection)
        } catch (e: MirrorPreparationException) {
            if (e.reason != MirrorFailure.NO_MATCHES) throw e
        }
    }
}
