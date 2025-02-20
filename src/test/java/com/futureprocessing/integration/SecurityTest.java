package com.futureprocessing.integration;

import com.futureprocessing.spring.Application;
import com.futureprocessing.spring.api.ApiController;
import com.futureprocessing.spring.api.samplestuff.ServiceGateway;
import com.futureprocessing.spring.infrastructure.AuthenticatedExternalWebService;
import com.futureprocessing.spring.infrastructure.security.ExternalServiceAuthenticator;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.config.SSLConfig;
import com.jayway.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.Objects;

import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.RestAssured.when;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        classes = {Application.class}
)
@Profile("test")
public class SecurityTest {

    private static final String X_AUTH_USERNAME = "X-Auth-Username";
    private static final String X_AUTH_PASSWORD = "X-Auth-Password";
    private static final String X_AUTH_TOKEN = "X-Auth-Token";

    @Value("${local.server.port}")
    int port;

    @Value("${keystore.file}")
    String keystoreFile;

    @Value("${keystore.pass}")
    String keystorePass;

    @MockBean
    ExternalServiceAuthenticator mockedExternalServiceAuthenticator;

    @MockBean
    ServiceGateway mockedServiceGateway;

    @BeforeEach
    public void setup() {
        RestAssured.baseURI = "https://localhost";
        RestAssured.port = port;
        Mockito.reset(mockedExternalServiceAuthenticator, mockedServiceGateway);

        RestAssured.config = RestAssured.config().sslConfig(SSLConfig.sslConfig()
            .trustStore(Objects.requireNonNull(
                this.getClass().getResource(keystoreFile)).getFile(), keystorePass).trustStoreType("JKS"));

    }

    @Test
    public void healthEndpoint_isAvailableToEveryone() {
        when().get("/health").
                then().statusCode(HttpStatus.OK.value()).body("status", equalTo("UP"));
    }

    @Test
    public void metricsEndpoint_withoutBackendAdminCredentials_returnsUnauthorized() {
        when().get("/metrics").
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void metricsEndpoint_withInvalidBackendAdminCredentials_returnsUnauthorized() {
        String username = "test_user_2";
        String password = "InvalidPassword";
        given().header(X_AUTH_USERNAME, username).header(X_AUTH_PASSWORD, password).
                when().get("/metrics").
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void metricsEndpoint_withCorrectBackendAdminCredentials_returnsOk() {
        String username = "backend_admin";
        String password = "remember_to_change_me_by_external_property_on_deploy";
        given().header(X_AUTH_USERNAME, username).header(X_AUTH_PASSWORD, password).
                when().get("/metrics").
                then().statusCode(HttpStatus.OK.value());
    }

    @Test
    public void authenticate_withoutPassword_returnsUnauthorized() {
        given().header(X_AUTH_USERNAME, "SomeUser").
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());

        BDDMockito.verifyNoMoreInteractions(mockedExternalServiceAuthenticator);
    }

    @Test
    public void authenticate_withoutUsername_returnsUnauthorized() {
        given().header(X_AUTH_PASSWORD, "SomePassword").
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());

        BDDMockito.verifyNoMoreInteractions(mockedExternalServiceAuthenticator);
    }

    @Test
    public void authenticate_withoutUsernameAndPassword_returnsUnauthorized() {
        when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());

        BDDMockito.verifyNoMoreInteractions(mockedExternalServiceAuthenticator);
    }

    @Test
    public void authenticate_withValidUsernameAndPassword_returnsToken() {
        authenticateByUsernameAndPasswordAndGetToken();
    }

    @Test
    public void authenticate_withInvalidUsernameOrPassword_returnsUnauthorized() {
        String username = "test_user_2";
        String password = "InvalidPassword";

        BDDMockito.when(mockedExternalServiceAuthenticator.authenticate(anyString(), anyString())).
                thenThrow(new BadCredentialsException("Invalid Credentials"));

        given().header(X_AUTH_USERNAME, username).header(X_AUTH_PASSWORD, password).
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void gettingStuff_withoutToken_returnsUnauthorized() {
        when().get(ApiController.STUFF_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void gettingStuff_withInvalidToken_returnsUnathorized() {
        given().header(X_AUTH_TOKEN, "InvalidToken").
                when().get(ApiController.STUFF_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void gettingStuff_withValidToken_returnsData() {
        String generatedToken = authenticateByUsernameAndPasswordAndGetToken();

        given().header(X_AUTH_TOKEN, generatedToken).
                when().get(ApiController.STUFF_URL).
                then().statusCode(HttpStatus.OK.value());
    }

    private String authenticateByUsernameAndPasswordAndGetToken() {
        String username = "test_user_2";
        String password = "ValidPassword";

        AuthenticatedExternalWebService authenticationWithToken = new AuthenticatedExternalWebService(username, null,
                AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_DOMAIN_USER"));
        BDDMockito.when(mockedExternalServiceAuthenticator.authenticate(eq(username), eq(password))).
                thenReturn(authenticationWithToken);

        ValidatableResponse validatableResponse = given().header(X_AUTH_USERNAME, username).
                header(X_AUTH_PASSWORD, password).
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.OK.value());
        String generatedToken = authenticationWithToken.getToken();
        validatableResponse.body("token", equalTo(generatedToken));

        return generatedToken;
    }
}
