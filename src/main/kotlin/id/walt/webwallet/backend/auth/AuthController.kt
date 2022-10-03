package id.walt.webwallet.backend.auth

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializable
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.Gson
import id.walt.WALTID_DATA_ROOT
import id.walt.model.DidMethod
import id.walt.services.context.ContextManager
import id.walt.services.did.DidService
import id.walt.webwallet.backend.context.WalletContextManager
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.plugin.openapi.dsl.document
import io.javalin.plugin.openapi.dsl.documented
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object AuthController {

    private val log = KotlinLogging.logger {}
    val routes
        get() = path("auth") {
            path("login") {
                post(documented(document().operation {
                    it.summary("Login")
                        .operationId("login")
                        .addTagsItem("Authentication")
                }
                    .body<UserInfo> { it.description("Login info") }
                    .json<UserInfo>("200"),
                    AuthController::login), UserRole.UNAUTHORIZED)
            }
            path("register") {
                post(documented(document().operation {
                    it.summary("Register")
                        .operationId("register")
                        .addTagsItem("Authentication")
                }
                    .body<UserInfo> { it.description("Registration info") }
                    .json<UserInfo>("201"),
                    AuthController::register), UserRole.UNAUTHORIZED)
            }
            path("userInfo") {
                get(
                    documented(document().operation {
                        it.summary("Get current user info")
                            .operationId("userInfo")
                            .addTagsItem("Authentication")
                    }
                        .json<UserInfo>("200"),
                        AuthController::userInfo), UserRole.AUTHORIZED)
            }
        }

    fun login(ctx: Context) {
        val userInfo = ctx.bodyAsClass(UserInfo::class.java)
        val userDirectory = Paths.get(WALTID_DATA_ROOT + File.separator + "data" + File.separator + userInfo.email)
        if (Files.isDirectory(userDirectory)) {
            log.debug { "Account exists!" }
            ContextManager.runWith(WalletContextManager.getUserContext(userInfo)) {
                if(DidService.listDids().isEmpty()) {
                    DidService.create(DidMethod.key)
                }
            }
            // add the firstname & lastname from the directory
//            val userDirectory = Paths.get(WALTID_DATA_ROOT + File.separator + "data" + File.separator + userInfo.email)
            val accountFile = "$userDirectory/account.json"     // account will store the firstName & lastName
            val cf = File(accountFile)      // get the file
            if (!cf.exists()) {         // file doesn't exists!!
                val stringBuilder: StringBuilder = StringBuilder("Unable to find this account! Check your credentials.")
//            val jsonMsg: JsonObject = parser.parse(stringBuilder) as JsonObject
                ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(404)
                return
            }
            val mapper = ObjectMapper()
            val jsonNode: JsonNode = mapper.readTree(cf)
            userInfo.firstName = jsonNode.get("firstName").asText()
            userInfo.lastName = jsonNode.get("lastName").asText()
//            println("userInfo: $userInfo")
            ctx.json(UserInfo(userInfo.id, userInfo.firstName, userInfo.lastName).apply {
                token = JWTService.toJWT(userInfo)
            })
        }
        else {
            // User not exists!
            log.debug { "Account not exists!" }
            val parser = Parser.default()
            val stringBuilder: StringBuilder = StringBuilder("Unable to find this account! Check your credentials.")
//            val jsonMsg: JsonObject = parser.parse(stringBuilder) as JsonObject
            ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(404)
        }
    }

    fun register(ctx: Context) {
        val userInfo = ctx.bodyAsClass(UserInfo::class.java)
        val userDirectory = Paths.get(WALTID_DATA_ROOT + File.separator + "data" + File.separator + userInfo.email)
        if (Files.isDirectory(userDirectory)) {
            log.debug { "User already exists!" }
            log.debug { "user email: ${userInfo.email}" }
            val stringBuilder: StringBuilder = StringBuilder("Account already exists!")
            ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(400)
        } else {
            log.debug { "User doesn't exists! Creation of the account" }
            ContextManager.runWith(WalletContextManager.getUserContext(userInfo)) {
                if(DidService.listDids().isEmpty()) {
                    DidService.create(DidMethod.key)
                }
            }
            ctx.json(UserInfo(userInfo.id, userInfo.firstName, userInfo.lastName).apply {
                token = JWTService.toJWT(userInfo)
                userInfo.token = token
            })
 //           log.debug { userInfo.token }
 //           log.debug { userInfo.firstName }
            userInfo.password = ""
            // store the userinfo into the user directory
            if (Files.isDirectory(userDirectory)) {
                val accountFile = "$userDirectory/account.json"     // account will store the firstName & lastName
                val cf = File(accountFile)      // get the file
                val parser = Parser.default()   // initialize the parser
                val stringAccount: StringBuilder = java.lang.StringBuilder(
                    "{\"firstName\":\"" + userInfo.firstName + "\"," +
                            "\"lastName\":\"" + userInfo.lastName + "\"," +
                            "}"
                )
                val jsonAccount: JsonObject = parser.parse(stringAccount) as JsonObject
                val mapper = ObjectMapper()
                mapper.writeValue(cf, jsonAccount)
                log.debug { "account file is written!" }
            }
            else {
                log.debug { "User directory not exists!" }
            }
            ctx.status(201)
            ctx.result(Gson().toJson(userInfo))
        }
    }

    fun userInfo(ctx: Context) {
        ctx.json(JWTService.getUserInfo(ctx)!!)
    }
}
