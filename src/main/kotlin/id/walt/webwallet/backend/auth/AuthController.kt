package id.walt.webwallet.backend.auth

import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
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
//        ContextManager.runWith(WalletContextManager.getUserContext(userInfo)) {
//            if(DidService.listDids().isEmpty()) {
//                DidService.create(DidMethod.key)
//            }
//        }
//        ctx.json(UserInfo(userInfo.id).apply {
//            token = JWTService.toJWT(userInfo)
//        })
    }

    fun register(ctx: Context) {
        val userInfo = ctx.bodyAsClass(UserInfo::class.java)
//        log.debug { "id: " + userInfo.id }
//        log.debug { "first name: " + userInfo.firstName }
//        log.debug { "last name: " + userInfo.lastName }
//        log.debug { "email: " + userInfo.email }
        // check if the account already exists == same email
        val userDirectory = Paths.get(WALTID_DATA_ROOT + File.separator + "data" + File.separator + userInfo.email)
//        log.debug { userDirectory }
        if (Files.isDirectory(userDirectory)) {
            log.debug { "User already exists!" }
//            val parser = Parser.default()
            val stringBuilder: StringBuilder = StringBuilder("Account already exists!")
            ctx.result(stringBuilder.toString()).contentType(ContentType.TEXT_PLAIN).status(409)
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

            ctx.status(201)
            ctx.result(Gson().toJson(userInfo))
        }
    }

    fun userInfo(ctx: Context) {
        ctx.json(JWTService.getUserInfo(ctx)!!)
    }
}
