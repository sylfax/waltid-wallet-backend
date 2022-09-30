package id.walt.webwallet.backend.rest

import cc.vileda.openapi.dsl.components
import cc.vileda.openapi.dsl.info
import cc.vileda.openapi.dsl.security
import com.beust.klaxon.Klaxon
import id.walt.issuer.backend.IssuerController
import id.walt.onboarding.backend.OnboardingController
import id.walt.verifier.backend.VerifierController
import id.walt.webwallet.backend.auth.AuthController
import id.walt.webwallet.backend.auth.JWTService
import id.walt.webwallet.backend.context.WalletContextManager
import id.walt.webwallet.backend.wallet.DidWebRegistryController
import id.walt.webwallet.backend.wallet.WalletController
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder
import io.javalin.core.security.AccessManager
import io.javalin.core.util.RouteOverviewPlugin
import io.javalin.plugin.json.JavalinJackson
import io.javalin.plugin.json.JsonMapper
import io.javalin.plugin.openapi.InitialConfigurationCreator
import io.javalin.plugin.openapi.OpenApiOptions
import io.javalin.plugin.openapi.OpenApiPlugin
import io.javalin.plugin.openapi.ui.ReDocOptions
import io.javalin.plugin.openapi.ui.SwaggerOptions
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import mu.KotlinLogging
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.ssl.SslContextFactory

object RestAPI {

  private val log = KotlinLogging.logger {}
  var LIST_SSL_ENABLED: String = System.getenv("LIST_SSL_ENABLED") ?: "false"

  val DEFAULT_ROUTES = {
    ApiBuilder.path("api") {
      AuthController.routes
      WalletController.routes
      DidWebRegistryController.routes
    }
    ApiBuilder.path("verifier-api") {
      VerifierController.routes
    }
    ApiBuilder.path("issuer-api") {
      IssuerController.routes
    }
    ApiBuilder.path("onboarding-api") {
      OnboardingController.routes
    }
  }

  var apiTitle = "walt.id wallet backend API"

  fun createJavalin(accessManager: AccessManager): Javalin = Javalin.create { config ->
      config.apply {

        // SSL
        println("LIST_SSL_ENABLED: $LIST_SSL_ENABLED")
        if (LIST_SSL_ENABLED.toBoolean()) {
          enforceSsl = true
          println("SSL activated")
        }

        enableDevLogging()
        enableCorsForAllOrigins()
        requestLogger { ctx, ms ->
          log.debug { "Received req.: ${ctx.url()} - Time: ${ms}ms" }
        }
        accessManager(accessManager)
        registerPlugin(RouteOverviewPlugin("/api-routes"))
        registerPlugin(OpenApiPlugin(OpenApiOptions(InitialConfigurationCreator {
          OpenAPI().apply {
            info {
              title = apiTitle
            }
            servers = listOf(Server().url("/"))
            components {
              addSecuritySchemes("bearerAuth", SecurityScheme().apply {
                name = "bearerAuth"
                type = SecurityScheme.Type.HTTP
                scheme = "bearer"
                `in` = SecurityScheme.In.HEADER
                bearerFormat = "JWT"
              })
            }
            security {
              addList("bearerAuth")
            }
          }
        }).apply {
          path("/api/api-documentation")
          swagger(SwaggerOptions("/api/swagger").title(apiTitle))
          reDoc(ReDocOptions("/api/redoc").title(apiTitle))
        }))

        this.jsonMapper(object : JsonMapper {
          override fun toJsonString(obj: Any): String {
            return Klaxon().toJsonString(obj)
          }

          override fun <T : Any?> fromJsonString(json: String, targetClass: Class<T>): T {
            return JavalinJackson().fromJsonString(json, targetClass)
          }
        })
      }
    }

  fun start(bindAddress: String, port: Int, accessManager: AccessManager, routes: () -> Unit= DEFAULT_ROUTES): Javalin {
    val javalin = createJavalin(accessManager)
    // SSL
    if (LIST_SSL_ENABLED.toBoolean()) {
      val sslConnector = ServerConnector(javalin.jettyServer()?.server(), getSslContextFactory())
      sslConnector.port = port
      javalin.jettyServer()?.server()?.connectors = arrayOf(sslConnector)
      println("sslConnector activated")
    }
    javalin.routes(routes)
    println("binding adress: $bindAddress")
    println("port: $port")
    javalin.start(bindAddress, port)
    println("web wallet backend started at: http://$bindAddress:$port")
    println("swagger docs are hosted at: http://$bindAddress:$port/api/swagger")
    return javalin
  }

  private fun getSslContextFactory(): SslContextFactory.Server? {
    val sslContextFactory = SslContextFactory.Server()
    sslContextFactory.keyStorePath = RestAPI::class.java.getResource("/itd-ebsilux-iss_list_lu.pkcs12").toExternalForm()
    sslContextFactory.setKeyStorePassword("mdp!sfx")
    return sslContextFactory
  }

}
