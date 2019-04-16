package fr.vsct.tock.bot.connector.teams.auth

import com.google.common.cache.CacheBuilder
import com.microsoft.bot.schema.models.Activity
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.vertx.core.MultiMap
import io.vertx.core.http.CaseInsensitiveHeaders
import net.minidev.json.JSONObject
import net.minidev.json.parser.JSONParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull


class AuthenticateBotConnectorServiceTest {

    private val activity = Activity().withChannelId("msteams").withServiceUrl("https://serviceurl")
    private var authenticateBotConnectorService: AuthenticateBotConnectorService =
        AuthenticateBotConnectorService("fakeAppId")

    private val notBefore = Instant.now().minus(10, ChronoUnit.SECONDS)
    private val expirationDate = Instant.now().plus(60, ChronoUnit.SECONDS)
    private val validJsonPayload: JSONObject = JSONParser(JSONParser.MODE_JSON_SIMPLE).parse(
        "{\"iss\":\"https://api.botframework.com\"," +
                "\"iat\":${notBefore.epochSecond}," +
                "\"nbf\":${notBefore.epochSecond}" +
                ",\"exp\":${expirationDate.epochSecond}," +
                "\"aud\":\"fakeAppId\"," +
                "\"sub\":\"test\"," +
                "\"serviceurl\": \"https://serviceurl\"}"
    ) as JSONObject

    private val validJsonPayloadFromBotFwkEmulator: JSONObject = JSONParser(JSONParser.MODE_JSON_SIMPLE).parse(
        "{\"iss\":\"https://login.microsoftonline.com/d6d49420-f39b-4df7-a1dc-d59a935871db/v2.0\"," +
                "\"iat\":${notBefore.epochSecond}," +
                "\"nbf\":${notBefore.epochSecond}" +
                ",\"exp\":${expirationDate.epochSecond}," +
                "\"aud\":\"fakeAppId\"," +
                "\"azp\":\"fakeAppId\"," +
                "\"sub\":\"test\"," +
                "\"serviceurl\": \"https://serviceurl\"}"
    ) as JSONObject
    private val jwk = RSAKeyGenerator(2048)
        .keyUse(KeyUse.SIGNATURE)
        .keyID(UUID.randomUUID().toString())
        .generate()
    private val rsaKey = jwk.toPublicJWK().toJSONObject().appendField("endorsements", arrayListOf("msteams"))
    private val jwks = "{\"keys\":[${rsaKey.toJSONString()}]}"


    @Test
    fun tokenMustBeValid() {

        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(validJsonPayload)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)

        val server = getMicrosoftMockServer()

        //check that it does not fail
        authenticateBotConnectorService.checkRequestValidity(headers, activity)

        server.shutdown()

    }


    @Test
    fun unauthorizedDueToMissingAuthorizationHeader() {
        val headers: MultiMap = CaseInsensitiveHeaders()
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }
    }

    @Test
    fun unauthorizedDueToMissingBearerAuthorizationHeader() {
        val bearerAuthorization = "thisisawrongtoken"
        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }
    }

    @Test
    fun unauthorizedDueToNotValidJwt() {
        val bearerAuthorization = "Bearer thisisawrongtoken"
        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }
    }

    @Test
    fun unauthorizedDueToMissingIssuerClaim() {
        val jsonPayloadWithoutIssuer: JSONObject = JSONParser(JSONParser.MODE_JSON_SIMPLE).parse(
            "{\"iat\":${notBefore.epochSecond}," +
                    "\"nbf\":${notBefore.epochSecond}" +
                    ",\"exp\":${expirationDate.epochSecond}," +
                    "\"aud\":\"fakeAppId\"," +
                    "\"sub\":\"test\"," +
                    "\"serviceurl\": \"https://serviceurl\"}"
        ) as JSONObject
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(jsonPayloadWithoutIssuer)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")
        val server = getMicrosoftMockServer()

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }

        server.shutdown()
    }

    @Test
    fun unauthorizedDueToWrongAudienceClaim() {
        val jsonPayloadWithWrongAppId: JSONObject = JSONParser(JSONParser.MODE_JSON_SIMPLE).parse(
            "{\"iss\":\"https://api.botframework.com\"," +
                    "\"iat\":${notBefore.epochSecond}," +
                    "\"nbf\":${notBefore.epochSecond}" +
                    ",\"exp\":${expirationDate.epochSecond}," +
                    "\"aud\":\"wrongAppId\"," +
                    "\"sub\":\"test\"," +
                    "\"serviceurl\": \"https://serviceurl\"}"
        ) as JSONObject
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(jsonPayloadWithWrongAppId)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")
        val server = getMicrosoftMockServer()

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }

        server.shutdown()
    }

    @Test
    fun unauthorizedDueToExpiredToken() {
        val jsonPayloadWithWrongAppId: JSONObject = JSONParser(JSONParser.MODE_JSON_SIMPLE).parse(
            "{\"iss\":\"https://api.botframework.com\"," +
                    "\"iat\":${notBefore.epochSecond}," +
                    "\"nbf\":${notBefore.epochSecond}" +
                    ",\"exp\":${notBefore.epochSecond}," +
                    "\"aud\":\"fakeAppId\"," +
                    "\"sub\":\"test\"," +
                    "\"serviceurl\": \"https://serviceurl\"}"
        ) as JSONObject
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(jsonPayloadWithWrongAppId)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")
        val server = getMicrosoftMockServer()

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }

        server.shutdown()
    }

    @Test
    fun unauthorizedDueToNotValidYetToken() {
        val jsonPayloadWithWrongAppId: JSONObject = JSONParser(JSONParser.MODE_JSON_SIMPLE).parse(
            "{\"iss\":\"https://api.botframework.com\"," +
                    "\"iat\":${notBefore.epochSecond}," +
                    "\"nbf\":${expirationDate.epochSecond}" +
                    ",\"exp\":${expirationDate.epochSecond}," +
                    "\"aud\":\"fakeAppId\"," +
                    "\"sub\":\"test\"," +
                    "\"serviceurl\": \"https://serviceurl\"}"
        ) as JSONObject
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(jsonPayloadWithWrongAppId)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")
        val server = getMicrosoftMockServer()

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }

        server.shutdown()
    }

    @Test
    fun unauthorizedDueToNotValidKey() {
        val anotherJwk = RSAKeyGenerator(2048)
            .keyUse(KeyUse.SIGNATURE)
            .keyID(UUID.randomUUID().toString())
            .generate()
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(validJsonPayload)
        )
        jwsObject.sign(RSASSASigner(anotherJwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")


        val server = getMicrosoftMockServer()

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, activity)
        }
        server.shutdown()
    }

    @Test
    fun unauthorizedDueToDifferentServiceUrl() {
        val otherActivity = Activity().withChannelId("msteams").withServiceUrl("https://wrongServiceurl")
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(validJsonPayload)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        val authenticateBotConnectorService = AuthenticateBotConnectorService("fakeAppId")


        val server = getMicrosoftMockServer()

        assertFailsWith(ForbiddenException::class) {
            authenticateBotConnectorService.checkRequestValidity(headers, otherActivity)
        }
        server.shutdown()
    }

    @Test
    fun theJwkShouldBeFilledFromMicrosoftApi() {
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(validJsonPayload)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)

        val server = getMicrosoftMockServer()

        //check that it does not fail
        authenticateBotConnectorService.checkRequestValidity(headers, activity)
        val firstRecordedRequest = server.takeRequest()
        assertEquals("GET /.well-known/openidconfiguration/ HTTP/1.1", firstRecordedRequest.requestLine)
        val secondRecordedResponse = server.takeRequest()
        assertEquals("GET / HTTP/1.1", secondRecordedResponse.requestLine)
        server.shutdown()
    }

    @Test
    fun theJwkShouldBeFilledFromCacheTheSecondTime() {
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(validJsonPayload)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)

        val server = getMicrosoftMockServer()

        //check that it does not fail
        authenticateBotConnectorService.checkRequestValidity(headers, activity)
        val firstRecordedRequest = server.takeRequest()
        assertEquals("GET /.well-known/openidconfiguration/ HTTP/1.1", firstRecordedRequest.requestLine)
        val secondRecordedResponse = server.takeRequest()
        assertEquals("GET / HTTP/1.1", secondRecordedResponse.requestLine)
        //check that it does not fail
        authenticateBotConnectorService.checkRequestValidity(headers, activity)

        val thirdRecordedRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertNull(thirdRecordedRequest)
        server.shutdown()
    }

    @Test
    fun cacheShouldBeReloadedWhenExpired() {
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(validJsonPayload)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val server = getMicrosoftMockServer()
        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        authenticateBotConnectorService.cacheKeys = CacheBuilder.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(500, TimeUnit.MILLISECONDS)
            .build()

        //check that it does not fail
        authenticateBotConnectorService.checkRequestValidity(headers, activity)
        val firstRecordedRequest = server.takeRequest()
        assertEquals("GET /.well-known/openidconfiguration/ HTTP/1.1", firstRecordedRequest.requestLine)
        val secondRecordedResponse = server.takeRequest()
        assertEquals("GET / HTTP/1.1", secondRecordedResponse.requestLine)

        Thread.sleep(1000)

        //check that it does not fail
        authenticateBotConnectorService.checkRequestValidity(headers, activity)
        val thirdRecordedRequest = server.takeRequest(1, TimeUnit.SECONDS)
        val fourthRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("GET /.well-known/openidconfiguration/ HTTP/1.1", thirdRecordedRequest.requestLine)
        assertEquals("GET / HTTP/1.1", fourthRequest.requestLine)

        server.shutdown()
    }

    @Test
    fun testRequestFromTheBotConnectorService() {
        val jwsObject = JWSObject(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(jwk.keyID).build(),
            Payload(validJsonPayloadFromBotFwkEmulator)
        )
        jwsObject.sign(RSASSASigner(jwk))
        val token = jwsObject.serialize()
        val bearerAuthorization = "Bearer $token"

        val headers: MultiMap = CaseInsensitiveHeaders().add("Authorization", bearerAuthorization)
        authenticateBotConnectorService.cacheKeys = CacheBuilder.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(500, TimeUnit.MILLISECONDS)
            .build()

        val server = getMicrosoftMockServer()

        //check that it does not fail
        authenticateBotConnectorService.checkRequestValidity(headers, activity)
        val firstRecordedRequest = server.takeRequest()
        assertEquals("GET /.well-known/openidconfiguration/ HTTP/1.1", firstRecordedRequest.requestLine)
        val secondRecordedResponse = server.takeRequest()
        assertEquals("GET / HTTP/1.1", secondRecordedResponse.requestLine)

        server.shutdown()
    }

    private fun getMicrosoftMockServer(): MockWebServer {
        val server = MockWebServer()

        authenticateBotConnectorService = AuthenticateBotConnectorService(
            "fakeAppId",
            "http://${server.hostName}:${server.port}/",
            "http://${server.hostName}:${server.port}/"
        )

        val mockResponse = MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(
                "{\"issuer\":\"https://api.botframework.com\"," +
                        "\"authorization_endpoint\":\"https://invalid.botframework.com\"," +
                        "\"jwks_uri\":\"http://${server.hostName}:${server.port}/\"," +
                        "\"id_token_signing_alg_values_supported\":[\"RS256\"]," +
                        "\"token_endpoint_auth_methods_supported\":[\"private_key_jwt\"]}"
            )
            .setResponseCode(200)
        val mockResponseFromJks = MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(jwks)
            .setResponseCode(200)
        server.enqueue(mockResponse)
        server.enqueue(mockResponseFromJks)

        //To test the cache
        server.enqueue(mockResponse)
        server.enqueue(mockResponseFromJks)

        return server
    }
}