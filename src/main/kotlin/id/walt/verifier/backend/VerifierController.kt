package id.walt.verifier.backend
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Parser
import com.fasterxml.jackson.databind.ObjectMapper
import id.walt.issuer.backend.CredentialIssuerResponse
import id.walt.webwallet.backend.auth.JWTService
import id.walt.webwallet.backend.auth.UserRole
import id.walt.webwallet.backend.config.ExternalHostnameUrl
import id.walt.webwallet.backend.config.WalletConfig
import id.walt.webwallet.backend.config.externalHostnameUrlValueConverter
import id.walt.webwallet.backend.wallet.WalletController
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.http.HttpCode
import io.javalin.plugin.openapi.dsl.document
import io.javalin.plugin.openapi.dsl.documented
import mu.KotlinLogging
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object VerifierController {
  private val log = KotlinLogging.logger {}
  val routes
    get() =
      path("") {
        path("wallets") {
          get("list", documented(
            document().operation {
              it.summary("List wallet configurations")
                .addTagsItem("Verifier")
                .operationId("listWallets")
            }
              .jsonArray<WalletConfiguration>("200"),
            VerifierController::listWallets,
          ))
          path("holders") {
            post(
              "", documented(
                document().operation {
                  it.summary("Add a new holder wallet configuration").addTagsItem("Verifier")
                    .operationId("addHolder")
                }
                  .body<CredentialHolderRequest>()
                  .json<CredentialHolderRequest>("201"),
                VerifierController::addHolder
              ),
              UserRole.UNAUTHORIZED
            )
            delete(
              "{id}", documented(
                document().operation {
                  it.summary("Delete a holder configuration").addTagsItem("Verifier")
                    .operationId("deleteHolder")
                },
                VerifierController::deleteHolder
              ),
              UserRole.UNAUTHORIZED
            )
          }
        }
        path("present") {
          get(documented(
            document().operation {
              it.summary("Present Verifiable ID")
                .addTagsItem("Verifier")
                .operationId("presentVID")
            }
              .queryParam<String>("walletId")
              .queryParam<String>("schemaUri", isRepeatable = true)
              .result<String>("302"),
            VerifierController::presentCredential
          ))
        }
        path("verify") {
          post( documented(
            document().operation {
              it.summary("SIOPv2 request verification callback")
                .addTagsItem("Verifier")
                .operationId("verifySIOPv2Request")
            }
              .queryParam<String>("state")
              .formParamBody<String> { }
              .result<String>("302"),
            VerifierController::verifySIOPResponse
          ))
        }
        path("auth") {
          get(documented(
            document().operation {
              it.summary("Complete authentication by siopv2 verification")
                .addTagsItem("Verifier")
                .operationId("completeAuthentication")
            }
              .queryParam<String>("access_token")
              .json<SIOPResponseVerificationResult>("200"),
            VerifierController::completeAuthentication
          ))
        }
        path("protected") {
          get(documented(
            document().operation {
              it.summary("Fetch protected data (example)")
                .addTagsItem("Verifier")
                .operationId("get protected data")
            }
              .result<String>("200"),
            VerifierController::getProtectedData
          ), UserRole.AUTHORIZED)
        }
      }

  fun listWallets(ctx: Context) {
    val cf = File(VerifierConfig.CONFIG_FILE)
    if (cf.exists()) {
      log.debug { "Reloading VerifierConfig..." }
      VerifierConfig.config = Klaxon().fieldConverter(ExternalHostnameUrl::class, externalHostnameUrlValueConverter).parse<VerifierConfig>(cf) ?: VerifierConfig()
    }
    else {
      log.debug { "Verifier config file doesn't exist!!" }
      VerifierConfig.config = VerifierConfig()
    }
    ctx.json(VerifierConfig.config.wallets.values)
  }

  fun presentCredential(ctx: Context) {
    val wallet = ctx.queryParam("walletId")?.let { VerifierConfig.config.wallets.get(it) } ?: throw BadRequestResponse("Unknown or missing walletId")
    val schemaUris = ctx.queryParams("schemaUri")
    if(schemaUris.isEmpty()) {
      throw BadRequestResponse("No schema URI(s) given")
    }
    val customQueryParams = ctx.queryParamMap().keys.filter { k -> k != "walletId" && k != "schemaUri" }.flatMap { k ->
      ctx.queryParams(k).map { v -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
    }.joinToString("&" )
    ctx.status(HttpCode.FOUND).header("Location", "${wallet.url}/${wallet.presentPath}"+
          "?${VerifierManager.getService().newRequest(schemaUris.toSet(), redirectCustomUrlQuery = customQueryParams).toUriQueryString()}")
  }

  fun verifySIOPResponse(ctx: Context) {
    val state = ctx.formParam("state") ?: throw  BadRequestResponse("State not specified")
    val id_token = ctx.formParam("id_token") ?: throw BadRequestResponse("id_token not specified")
    val vp_token = ctx.formParam("vp_token") ?: throw BadRequestResponse("vp_token not specified")
    val verifierUiUrl = ctx.queryParam("verifierUiUrl") ?: VerifierConfig.config.verifierUiUrl
    val result = VerifierManager.getService().verifyResponse(state, id_token, vp_token)

    ctx.status(HttpCode.FOUND).header("Location", VerifierManager.getService().getVerificationRedirectionUri(result, verifierUiUrl).toString())
  }

  fun completeAuthentication(ctx: Context) {
    val access_token = ctx.queryParam("access_token")
    if(access_token == null) {
      ctx.status(HttpCode.FORBIDDEN)
      return
    }
    val result = VerifierManager.getService().getVerificationResult(access_token)
    if(result == null) {
      ctx.status(HttpCode.FORBIDDEN)
      return
    }
    ctx.json(result)
  }

  fun getProtectedData(ctx: Context) {
    val userInfo = JWTService.getUserInfo(ctx)
    if(userInfo != null) {
      ctx.result("Account balance: EUR 0.00")
    } else {
      ctx.status(HttpCode.FORBIDDEN)
    }
  }

  /**
   * Add the configuration for a new holder into verifier-config.json file
   * @param ctx Context of the request
   */
  private fun addHolder(ctx: Context) {
    log.debug { "Add holder configuration call.." }
    val body = ctx.bodyAsClass<CredentialHolderRequest>()
    val parser = Parser.default()
    // check if the CONFIG_FILE exists
    val CONFIG_FILE = "${id.walt.WALTID_DATA_ROOT}/config/verifier-config.json"
    val cf = File(CONFIG_FILE)
    if (!cf.exists()) {
      println("FIle not exists!!")
      val stringBuilder: StringBuilder = StringBuilder("Config file not exists!")
      ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
      return
    }
    else {
      // the file exists
      // load the file
      val json: JsonObject = parser.parse(cf.absolutePath) as JsonObject
      val keys:MutableSet<String> = (json["wallets"] as JsonObject).keys      // keys of issuers
      if (!keys.contains(body.id)) {
        println("id not exists ..  adding it into the configuration file")
        val stringBuilder: StringBuilder = java.lang.StringBuilder(
          "{\"id\":\"" + body.id + "\"," +
                  "\"url\":\"" + body.url + "\"," +
                    "\"presentPath\":\"" + body.presentPath + "\"," +
                      "\"receivePath\":\"" + body.receivePath + "\"," +
                        "\"description\":\"" + body.description + "\"," + "}"
        )
        val jsonMsg: JsonObject = parser.parse(stringBuilder) as JsonObject
        (json["wallets"] as JsonObject).put(body.id!!, jsonMsg)
        // save the new json
        val mapper = ObjectMapper()
        mapper.writeValue(File(CONFIG_FILE), json)
        // return
        ctx.json(jsonMsg).contentType(ContentType.APPLICATION_JSON).status(201)
      }
      else {
        val stringBuilder: StringBuilder = StringBuilder("This holder already exists! (same id)")
        ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
        return
      }
    }
  }

  private fun deleteHolder(ctx: Context) {
    log.debug { "Delete holder configuration call.." }
    val holderId = ctx.pathParam("id")
    log.debug { holderId }
    val CONFIG_FILE = "${id.walt.WALTID_DATA_ROOT}/config/verifier-config.json"
    val cf = File(CONFIG_FILE)
    if (!cf.exists()) {
      println("FIle not exists!!")
      val stringBuilder: StringBuilder = StringBuilder("Config file not exists!")
      ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
      return
    }
    val parser = Parser.default()
    val json: JsonObject = parser.parse(cf.absolutePath) as JsonObject
    val keys:MutableSet<String> = (json["wallets"] as JsonObject).keys      // keys of issuers
    if (!keys.contains(holderId)) {
      val stringBuilder: StringBuilder = StringBuilder("Holder ID not found!")
      ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
      return
    }
    (json["wallets"] as JsonObject).remove(holderId)
    // save the new json
    val mapper = ObjectMapper()
    mapper.writeValue(File(CONFIG_FILE), json)
    // return the response
    val result = (json["wallets"] as JsonObject)
    ctx.json(result).contentType(ContentType.APPLICATION_JSON).status(200)
  }
}
