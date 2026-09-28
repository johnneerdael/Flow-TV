package nl.neerdael.milkbeat.catalog

import kotlinx.coroutines.flow.Flow

/** A source of catalog pages. Milkbeat renders what it returns and never reaches into its internals. */
interface MetadataProvider {
    val id: String

    /** Whose catalog the provider is serving right now; pages from another account are stale. */
    val account: Flow<ProviderAccount>

    suspend fun home(request: HomeRequest): Result<MetadataPage>
}

data class HomeRequest(
    val filterId: String? = null,
    val cursor: String? = null,
)

sealed interface ProviderAccount {
    data object Anonymous : ProviderAccount

    /** [key] changes whenever another account signs in; it carries no credentials. */
    data class SignedIn(
        val key: String,
    ) : ProviderAccount

    data object Expired : ProviderAccount
}
