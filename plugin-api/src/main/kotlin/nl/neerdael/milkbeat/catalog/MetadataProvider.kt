package nl.neerdael.milkbeat.catalog

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A source of catalog pages. Milkbeat renders what it returns and never reaches into its internals. */
interface MetadataProvider {
    val id: String

    /** Whose catalog the provider is serving right now; pages from another account are stale. */
    val account: Flow<ProviderAccount>

    suspend fun home(request: HomeRequest): Result<MetadataPage>

    /** The page of an artist, album or playlist; [cursor] continues a page that has more. */
    suspend fun page(
        entity: EntityRef,
        cursor: String? = null,
    ): Result<MetadataPage>
}

@Serializable
data class HomeRequest(
    val filterId: String? = null,
    val cursor: String? = null,
)

@Serializable
sealed interface ProviderAccount {
    @Serializable
    @SerialName("anonymous")
    data object Anonymous : ProviderAccount

    /** [key] changes whenever another account signs in; it carries no credentials. */
    @Serializable
    @SerialName("signedIn")
    data class SignedIn(
        val key: String,
        val name: String? = null,
        val avatar: Artwork? = null,
    ) : ProviderAccount

    @Serializable
    @SerialName("expired")
    data object Expired : ProviderAccount
}
