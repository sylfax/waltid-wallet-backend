package id.walt.webwallet.backend.wallet

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Parser
import com.fasterxml.jackson.databind.ObjectMapper
import id.walt.crypto.KeyAlgorithm
import id.walt.issuer.backend.CredentialIssuerResponse
import id.walt.model.DidMethod
import id.walt.model.oidc.SIOPv2Request
import id.walt.model.oidc.klaxon
import id.walt.rest.core.DidController
import id.walt.rest.custodian.CustodianController
import id.walt.services.context.ContextManager
import id.walt.services.did.DidService
import id.walt.services.essif.EssifClient
import id.walt.services.essif.didebsi.DidEbsiService
import id.walt.services.key.KeyService
import id.walt.webwallet.backend.auth.JWTService
import id.walt.webwallet.backend.auth.UserRole
import id.walt.webwallet.backend.config.ExternalHostnameUrl
import id.walt.webwallet.backend.config.WalletConfig
import id.walt.webwallet.backend.config.externalHostnameUrlValueConverter
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.http.HttpCode
import io.javalin.plugin.openapi.dsl.document
import io.javalin.plugin.openapi.dsl.documented
import mu.KotlinLogging
import java.io.File
import java.net.URI
import kotlin.text.StringBuilder

object WalletController {
    private val log = KotlinLogging.logger {}
    val routes
        get() = path("wallet") {
            path("did") {
                // list DIDs
                path("list") {
                    get(
                        documented(document().operation {
                            it.summary("List DIDs").operationId("listDids").addTagsItem("Wallet")
                        }
                            .jsonArray<String>("200"),
                            WalletController::listDids
                        ), UserRole.AUTHORIZED
                    )
                }
                // load DID
                get("{id}", documented(document().operation {
                    it.summary("Load DID").operationId("load").addTagsItem("Wallet")
                }
                    .json<String>("200"), DidController::load), UserRole.AUTHORIZED)
                // create new DID
                path("create") {
                    post(
                        documented(document().operation {
                            it.summary("Create new DID")
                                .description("Creates and registers a DID. Currently the DID methods: key, web and ebsi are supported. For EBSI: a  bearer token is required.")
                                .operationId("createDid").addTagsItem("Wallet")
                        }
                            .body<DidCreationRequest>()
                            .result<String>("200"),
                            WalletController::createDid
                        ), UserRole.AUTHORIZED
                    )
                }
            }
            path("credentials") {
                get(
                    "list",
                    documented(CustodianController.listCredentialIdsDocs(), CustodianController::listCredentials),
                    UserRole.AUTHORIZED
                )
                delete(
                    "delete/{alias}",
                    documented(CustodianController.deleteCredentialDocs(), CustodianController::deleteCredential),
                    UserRole.AUTHORIZED
                )
            }
            path("keys") {
                get(
                    "list",
                    documented(CustodianController.listKeysDocs(), CustodianController::listKeys),
                    UserRole.AUTHORIZED
                )
                post(
                    "import",
                    documented(CustodianController.importKeysDocs(), CustodianController::importKey),
                    UserRole.AUTHORIZED
                )
                delete(
                    "delete/{id}",
                    documented(CustodianController.deleteKeysDocs(), CustodianController::deleteKey),
                    UserRole.AUTHORIZED
                )
                post(
                    "export",
                    documented(CustodianController.exportKeysDocs(), CustodianController::exportKey),
                    UserRole.AUTHORIZED
                )
            }
            path("siopv2") {
                // called from EXTERNAL verifier
                get("initPresentation", documented(
                    document().operation {
                        it.summary("Parse siop request from URL query parameters")
                            .operationId("initPresentation")
                            .addTagsItem("siop")
                    }
                        .queryParam<String>("response_type")
                        .queryParam<String>("client_id")
                        .queryParam<String>("redirect_uri")
                        .queryParam<String>("scope")
                        .queryParam<String>("state")
                        .queryParam<String>("nonce")
                        .queryParam<String>("registration")
                        .queryParam<Long>("exp")
                        .queryParam<Long>("iat")
                        .queryParam<String>("claims")
                        .result<String>("302"),
                    WalletController::initCredentialPresentation
                ), UserRole.UNAUTHORIZED)
                // called by wallet UI
                get("continuePresentation", documented(
                    document().operation {
                        it.summary("Continue presentation requested by verifer")
                            .operationId("continuePresentation")
                            .addTagsItem("siop")
                    }
                        .queryParam<String>("sessionId")
                        .queryParam<String>("did")
                        .json<CredentialPresentationSession>("200"),
                    WalletController::continuePresentation
                ), UserRole.AUTHORIZED)
                // called by wallet UI
                post("fulfillPresentation", documented(
                    document().operation {
                        it.summary("Fullfil credentials presentation with selected credentials")
                            .operationId("fulfillPresentation")
                            .addTagsItem("siop")
                    }
                        .queryParam<String>("sessionId")
                        .body<List<PresentableCredential>>()
                        .json<PresentationResponse>("200"),
                    WalletController::fulfillPresentation
                ), UserRole.AUTHORIZED)
                // issuance // CUSTOM // called from EXTERNAL issuer
                get("initPassiveIssuance", documented(
                    document().operation {
                        it.summary("Initialize passive credential issuance through SIOP presentation flow")
                            .operationId("initPassiveIssuance")
                            .addTagsItem("siop")
                    }
                        .queryParam<String>("response_type")
                        .queryParam<String>("client_id")
                        .queryParam<String>("redirect_uri")
                        .queryParam<String>("scope")
                        .queryParam<String>("state")
                        .queryParam<String>("nonce")
                        .queryParam<String>("registration")
                        .queryParam<Long>("exp")
                        .queryParam<Long>("iat")
                        .queryParam<String>("claims")
                        .queryParam<String>("subject_did")
                        .result<String>("302"),
                    WalletController::initPassiveIssuance
                ), UserRole.UNAUTHORIZED)
                // called by wallet UI
                post("fulfillPassiveIssuance", documented(
                    document().operation {
                        it.summary("Post credentials required by issuer, to issue credentials")
                            .operationId("fulfillPassiveIssuance")
                            .addTagsItem("siop")
                    }
                        .queryParam<String>("sessionId")
                        .body<List<PresentableCredential>>()
                        .json<String>("200"), // issuance session ID
                    WalletController::fulfillPassiveIssuance
                ), UserRole.AUTHORIZED)
                // issuance // OIDC
                path("issuer") {
                    get(
                        "list", documented(
                            document().operation {
                                it.summary("List known credential issuers").addTagsItem("siop")
                                    .operationId("listIssuers")
                            },
                            WalletController::listIssuers
                        ),
                        UserRole.UNAUTHORIZED
                    )
                    post(
                        "", documented(
                            document().operation {
                                it.summary("Add a new issuer").addTagsItem("siop")
                                    .operationId("addIssuer")

                            }
                                .body<CredentialIssuerResponse>()
                                .json<CredentialIssuerResponse>("201"),
                            WalletController::addIssuer
                        ),
                        UserRole.UNAUTHORIZED
                    )
                    delete(
                        "{id}", documented(
                            document().operation {
                                it.summary("Delete an issuer").addTagsItem("siop")
                                    .operationId("deleteIssuer")
                            },
                            WalletController::deleteIssuer
                        ),
                        UserRole.UNAUTHORIZED
                    )
                    get("metadata", documented(
                        document().operation {
                            it.summary("get issuer meta data").addTagsItem("siop").operationId("issuerMeta")
                        }
                            .queryParam<String>("issuerId"),
                        WalletController::issuerMeta),
                        UserRole.UNAUTHORIZED)
                }
                path("verifier") {
                    get(
                        "list", documented(
                            document().operation {
                                it.summary("List known credential verifiers").addTagsItem("siop")
                                    .operationId("listVerifiers")
                            },
                            WalletController::listVerifiers
                        ),
                        UserRole.UNAUTHORIZED
                    )
                }
                post("initIssuance", documented(
                    document().operation {
                        it.summary("Initialize credential issuance from selected issuer").addTagsItem("siop")
                            .operationId("initIssuance")
                    }
                        .body<CredentialIssuanceRequest>()
                        .result<String>("200"),
                    WalletController::initIssuance
                ), UserRole.AUTHORIZED)
                // called from EXTERNAL issuer / user-agent
                get("finalizeIssuance", documented(
                    document().operation {
                        it.summary("Finalize credential issuance").addTagsItem("siop").operationId("finalizeIssuance")
                    }
                        .queryParam<String>("code")
                        .queryParam<String>("state")
                        .result<String>("302"),
                    WalletController::finalizeIssuance
                ), UserRole.UNAUTHORIZED)
                // called by wallet UI
                get("issuanceSessionInfo", documented(
                    document().operation {
                        it.summary("Get issuance session info, including issued credentials").addTagsItem("siop")
                            .operationId("issuanceSessionInfo")
                    }
                        .queryParam<String>("sessionId")
                        .json<CredentialIssuanceSession>("200"),
                    WalletController::getIssuanceSessionInfo
                ), UserRole.AUTHORIZED)
            }
        }

    fun listDids(ctx: Context) {
        ctx.json(DidService.listDids())
    }

    fun loadDid(ctx: Context) {
        val id = ctx.pathParam("id")
        ctx.json(DidService.load(id))
    }

    fun createDid(ctx: Context) {
        val req = ctx.bodyAsClass<DidCreationRequest>()

        val keyId = req.keyId?.let { KeyService.getService().load(it).keyId.id }

        when (req.method) {
            DidMethod.ebsi -> {
                if (req.didEbsiBearerToken.isNullOrEmpty()) {
                    ctx.status(HttpCode.BAD_REQUEST)
                        .result("ebsiBearerToken form parameter is required for EBSI DID registration.")
                    return
                }

                val did = DidService.create(
                    req.method,
                    keyId ?: KeyService.getService().generate(KeyAlgorithm.ECDSA_Secp256k1).id
                )
                EssifClient.onboard(did, req.didEbsiBearerToken)
                EssifClient.authApi(did)
                DidEbsiService.getService().registerDid(did, did)
                ctx.result(did)
            }

            DidMethod.web -> {
                val didRegistryAuthority = URI.create(WalletConfig.config.walletApiUrl).authority
                val didDomain = req.didWebDomain.orEmpty().ifEmpty { didRegistryAuthority }

                val didWebKeyId = keyId ?: KeyService.getService().generate(KeyAlgorithm.EdDSA_Ed25519).id

                val didStr = DidService.create(
                    req.method,
                    didWebKeyId,
                    DidService.DidWebOptions(
                        domain = didDomain,
                        path = when (didDomain) {
                            didRegistryAuthority -> "api/did-registry/$didWebKeyId"
                            else -> req.didWebPath
                        }
                    )
                )
                val didDoc = DidService.load(didStr)
                // !! Implicit USER CONTEXT is LOST after this statement !!
                ContextManager.runWith(DidWebRegistryController.didRegistryContext) {
                    DidService.storeDid(didStr, didDoc.encodePretty())
                }
                ctx.result(didStr)
            }

            DidMethod.key -> {

                ctx.result(
                    DidService.create(
                        req.method,
                        keyId ?: KeyService.getService().generate(KeyAlgorithm.EdDSA_Ed25519).id
                    )
                )
            }
        }
    }

    fun initCredentialPresentation(ctx: Context) {
        val req = SIOPv2Request.fromHttpContext(ctx)
        val session = CredentialPresentationManager.initCredentialPresentation(req, passiveIssuance = false)
        ctx.status(HttpCode.FOUND)
            .header("Location", "${WalletConfig.config.walletUiUrl}/CredentialRequest/?sessionId=${session.id}")
    }

    fun initPassiveIssuance(ctx: Context) {
        val req = SIOPv2Request.fromHttpContext(ctx)
        val session = CredentialPresentationManager.initCredentialPresentation(req, passiveIssuance = true)
        ctx.status(HttpCode.FOUND)
            .header("Location", "${WalletConfig.config.walletUiUrl}/CredentialRequest/?sessionId=${session.id}")
    }

    fun continuePresentation(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId") ?: throw BadRequestResponse("sessionId not specified")
        val did = ctx.queryParam("did") ?: throw BadRequestResponse("did not specified")
        ctx.json(CredentialPresentationManager.continueCredentialPresentationFor(sessionId, did))
    }

    fun fulfillPresentation(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId") ?: throw BadRequestResponse("sessionId not specified")
        val selectedCredentials = ctx.body().let { klaxon.parseArray<PresentableCredential>(it) }
            ?: throw BadRequestResponse("No selected credentials given")

        ctx.json(
            CredentialPresentationManager.fulfillPresentation(sessionId, selectedCredentials)
                .let { PresentationResponse.fromSiopResponse(it) })
    }

    fun fulfillPassiveIssuance(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId") ?: throw BadRequestResponse("sessionId not specified")
        val selectedCredentials = ctx.body().let { klaxon.parseArray<PresentableCredential>(it) }
            ?: throw BadRequestResponse("No selected credentials given")
        val issuanceSession = CredentialPresentationManager.fulfillPassiveIssuance(
            sessionId,
            selectedCredentials,
            JWTService.getUserInfo(ctx)!!
        )
        ctx.result(issuanceSession.id)
    }

    fun listIssuers(ctx: Context) {
        val cf = File(WalletConfig.CONFIG_FILE)
        if (cf.exists()) {
            log.debug { "Reloading WalletConfig..." }
            WalletConfig.config = Klaxon().fieldConverter(ExternalHostnameUrl::class, externalHostnameUrlValueConverter).parse<WalletConfig>(cf) ?: WalletConfig()
        }
        else {
            log.debug { "Wallet config file doesn't exist!!" }
            WalletConfig.config = WalletConfig()
        }
        ctx.json(WalletConfig.config.issuers.values)
    }

    fun listVerifiers(ctx: Context) {
        val cf = File(WalletConfig.CONFIG_FILE)
        if (cf.exists()) {
            log.debug { "Reloading WalletConfig..." }
            WalletConfig.config = Klaxon().fieldConverter(ExternalHostnameUrl::class, externalHostnameUrlValueConverter).parse<WalletConfig>(cf) ?: WalletConfig()
        }
        else {
            log.debug { "Wallet config file doesn't exist!!" }
            WalletConfig.config = WalletConfig()
        }
        ctx.json(WalletConfig.config.verifiers.values)
    }

    fun issuerMeta(ctx: Context) {
        val metadata = ctx.queryParam("issuerId")?.let { WalletConfig.config.issuers[it] }?.ciSvc?.metadata
        if (metadata != null)
            ctx.json(metadata.toJSONObject())
        else
            ctx.status(HttpCode.NOT_FOUND)
    }

    fun initIssuance(ctx: Context) {
        val issuance = ctx.bodyAsClass<CredentialIssuanceRequest>()
        val location = CredentialIssuanceManager.initIssuance(issuance, JWTService.getUserInfo(ctx)!!)
        if (location != null) {
            ctx.result(location.toString())
        } else {
            ctx.status(HttpCode.INTERNAL_SERVER_ERROR)
        }
    }

    fun finalizeIssuance(ctx: Context) {
        val state = ctx.queryParam("state")
        val code = ctx.queryParam("code")
        if (state.isNullOrEmpty() || code.isNullOrEmpty()) {
            ctx.status(HttpCode.BAD_REQUEST).result("No state or authorization code given")
            return
        }
        val issuance = CredentialIssuanceManager.finalizeIssuance(state, code)
        if (issuance?.credentials != null) {
            ctx.status(HttpCode.FOUND)
                .header("Location", "${WalletConfig.config.walletUiUrl}/ReceiveCredential/?sessionId=${issuance.id}")
        } else {
            ctx.status(HttpCode.FOUND).header("Location", "${WalletConfig.config.walletUiUrl}/IssuanceError/")
        }
    }

    fun getIssuanceSessionInfo(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId")
        val issuanceSession = sessionId?.let { CredentialIssuanceManager.getSession(it) }
        if (issuanceSession == null) {
            ctx.status(HttpCode.BAD_REQUEST).result("Invalid or expired session id given")
            return
        }
        ctx.json(issuanceSession)
    }

    private fun addIssuer(ctx: Context) {
        val body = ctx.bodyAsClass<CredentialIssuerResponse>()
        val parser = Parser.default()
        // check if the CONFIG_FILE exists
        val CONFIG_FILE = "${id.walt.WALTID_DATA_ROOT}/config/wallet-config.json"
        val cf = File(CONFIG_FILE)
        if (!cf.exists()) {
            println("FIle not exists!!")
            val stringBuilder: StringBuilder = StringBuilder("{\"msg\":\"Config file not exists!\"}")
            val jsonMsg: JsonObject = parser.parse(stringBuilder) as JsonObject
            ctx.json(jsonMsg).contentType(ContentType.APPLICATION_JSON).status(400)
            return
        }
        else {
            // the file exists
            // load the file
            val json: JsonObject = parser.parse(cf.absolutePath) as JsonObject
//            val jsonMap: MutableMap<String, Any?> = json.map
            val keys:MutableSet<String> = (json["issuers"] as JsonObject).keys      // keys of issuers
            if (!keys.contains(body.id)) {
                println("key not exists ..  adding it")
                val stringBuilder: StringBuilder = java.lang.StringBuilder(
                    "{\"id\":\"" + body.id + "\"," +
                          "\"url\":\"" + body.url + "\"," +
                            "\"description\":\"" + body.description + "\"," +
                            "}"
                )
                val jsonMsg: JsonObject = parser.parse(stringBuilder) as JsonObject
                (json["issuers"] as JsonObject).put(body.id!!, jsonMsg)
                // save the new json
                val mapper = ObjectMapper()
                mapper.writeValue(File(CONFIG_FILE), json)
                // return
//                val response = CredentialIssuerResponse(body.id, body.url, body.description)
                ctx.json(json).contentType(ContentType.APPLICATION_JSON).status(201)
            }
            else {
                val stringBuilder: StringBuilder = StringBuilder("ID already exists!")
                ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
                return
           }
        }
    }

    private fun deleteIssuer(ctx: Context) {
        log.debug { "Deleting an issuer..." }
        val issuerId = ctx.pathParam("id")
        log.debug { issuerId }
        val CONFIG_FILE = "${id.walt.WALTID_DATA_ROOT}/config/wallet-config.json"
        val cf = File(CONFIG_FILE)
        if (!cf.exists()) {
            println("FIle not exists!!")
            val stringBuilder: StringBuilder = StringBuilder("Config file not exists!")
            ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
            return
        }
        val parser = Parser.default()
        val json: JsonObject = parser.parse(cf.absolutePath) as JsonObject
        val keys:MutableSet<String> = (json["issuers"] as JsonObject).keys      // keys of issuers
        if (!keys.contains(issuerId)) {
            val stringBuilder: StringBuilder = StringBuilder("Issuer ID not found!")
            ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
            return
        }
        (json["issuers"] as JsonObject).remove(issuerId)
        // save the new json
        val mapper = ObjectMapper()
        mapper.writeValue(File(CONFIG_FILE), json)
        // return the response
        val result = (json["issuers"] as JsonObject)
        ctx.json(result).contentType(ContentType.APPLICATION_JSON).status(200)
    }
}
