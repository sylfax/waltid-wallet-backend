package id.walt.verifier.backend

data class CredentialHolderRequest(
    val id: String?,
    val url: String?,
    val description: String?,
    val presentPath: String?,
    val receivePath: String?
)
