package de.trustable.ca3s.core.web.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.trustable.ca3s.core.security.KeycloakUserDetails;
import de.trustable.ca3s.core.security.OIDCRestService;
import de.trustable.ca3s.core.security.jwt.JWTFilter;
import de.trustable.ca3s.core.security.jwt.TokenProvider;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.keycloak.OAuth2Constants;
import org.keycloak.TokenVerifier;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.adapters.rotation.PublicKeyLocator;
import org.keycloak.common.VerificationException;
import org.keycloak.common.util.KeycloakUriBuilder;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.adapters.config.AdapterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.openid.OpenIDAttribute;
import org.springframework.security.openid.OpenIDAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for managing the current user login using KeyCloak.
 */
@RestController
@RequestMapping("/oidc")
public class OIDCAuthenticationResource {

    private final Logger log = LoggerFactory.getLogger(OIDCAuthenticationResource.class);

    private final TokenProvider tokenProvider;
    private final String keycloakAuthorizationUri;
    private final String flowType;
    private final OIDCRestService oidcRestService;

    private KeycloakDeployment deployment;

    public OIDCAuthenticationResource(TokenProvider tokenProvider,
                                      @Value("${ca3s.oidc.authorization-uri:}") String keycloakAuthorizationUri,
                                      @Value("${ca3s.oidc.client-id}") String clientId,
                                      @Value("${ca3s.oidc.flow-type:code}") String flowType,
                                      OIDCRestService OIDCRestService) {
        this.tokenProvider = tokenProvider;
        this.keycloakAuthorizationUri = keycloakAuthorizationUri;
        this.oidcRestService = OIDCRestService;
        this.flowType = flowType;

        if (keycloakAuthorizationUri.isEmpty()) {
            log.info("OIDC not configured, 'ca3s.oidc.authorization-uri' is empty!");
        } else {

            String authServerUrl = keycloakAuthorizationUri;

            try {
                String authUrl = StringUtils.substringBefore(authServerUrl, "/realms/");
                String postRealms = StringUtils.substringAfter(authServerUrl, "/realms/");
                String realm = StringUtils.substringBefore(postRealms, '/');
                log.info("authUrl : {}, realm : {}", authUrl, realm);

                AdapterConfig adapterConfig = new AdapterConfig();
                adapterConfig.setRealm(realm);
                adapterConfig.setAuthServerUrl(authUrl);
                adapterConfig.setResource(clientId);

                deployment = KeycloakDeploymentBuilder.build(adapterConfig);
                log.info("Using oidcDeployment: {}", deployment);

            } catch (Exception cause) {
                throw new RuntimeException("Failed to parse the realm name.", cause);
            }
        }
    }

    /**
     * {@code GET  /authenticate} : check if the user is authenticated, and return its login.
     *
     * @param request the HTTP request.
     * @return the login if the user is authenticated.
     */
    @CrossOrigin
    @GetMapping("/authenticate")
    public ResponseEntity<String> getAuthenticatedUser(HttpServletRequest request,
                                                       @RequestParam Map<String,String> allParams) {

        if (keycloakAuthorizationUri.isEmpty()) {
            log.info("oidc Authentication not configured");
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        HttpHeaders httpHeaders = new HttpHeaders();

        if (request.getUserPrincipal() == null) {
            // not authenticated, yet
            log.info("Not authenticated, forwarding");

            ServletUriComponentsBuilder servletUriComponentsBuilder = ServletUriComponentsBuilder.fromRequestUri(request);
            String redirectUrlPart = "/../code";
            if( "implicit".equalsIgnoreCase(this.flowType)){
                redirectUrlPart = "../../..";
            }
            String redirectCodeUri = servletUriComponentsBuilder.path(redirectUrlPart).build().normalize().toString();

            KeycloakUriBuilder builder = deployment.getAuthUrl().clone()
                .queryParam(OAuth2Constants.CLIENT_ID, deployment.getResourceName())
                .queryParam(OAuth2Constants.REDIRECT_URI, redirectCodeUri)
                .queryParam(OAuth2Constants.SCOPE, OAuth2Constants.SCOPE_OPENID)
                ;

            if( "implicit".equalsIgnoreCase(this.flowType)){
                builder
                    .queryParam(OAuth2Constants.RESPONSE_TYPE, OAuth2Constants.TOKEN)
                    .queryParam("nonce", "" + System.currentTimeMillis());
            }else{
                builder
                    .queryParam(OAuth2Constants.RESPONSE_TYPE, OAuth2Constants.CODE)
                    .queryParam(OAuth2Constants.STATE, UUID.randomUUID().toString());
            }

            for( String key: allParams.keySet()){
                if( !key.equalsIgnoreCase(OAuth2Constants.CLIENT_ID) &&
                    !key.equalsIgnoreCase(OAuth2Constants.REDIRECT_URI) &&
                    !key.equalsIgnoreCase(OAuth2Constants.SCOPE) &&
                    !key.equalsIgnoreCase(OAuth2Constants.STATE) &&
                    !key.equalsIgnoreCase(OAuth2Constants.RESPONSE_TYPE) &&
                    !key.equalsIgnoreCase("nonce")){

                    log.debug("passing query parameter '{}' with value '{}' as redirect uri", key, allParams.get(key));
                    builder.queryParam(key, allParams.get(key));
                }
            }

            String redirectUrl = builder.build().toString();
            log.info("redirectUrl : '{}'", redirectUrl);

            httpHeaders.add("Access-Control-Allow-Origin", "*");
            httpHeaders.add("Access-Control-Allow-Methods", "GET, POST, PATCH, PUT, DELETE, OPTIONS");
            httpHeaders.add("Access-Control-Allow-Headers", "Origin, Content-Type, X-Auth-Token");
            httpHeaders.add("Location", redirectUrl);
            return new ResponseEntity<>(httpHeaders, HttpStatus.OK);
        }

        log.info("Bearer Token created for oidc Authentication");
        return new ResponseEntity<>(request.getRemoteUser(), httpHeaders, HttpStatus.OK);
    }

    @GetMapping(value={"/code", "/code/"})
    public ResponseEntity<String> getCode(HttpServletRequest request,
                                          @RequestParam(required = false, name = "code") String code,
                                          @RequestParam(required = false, name = "accessToken") String accessToken) {

        log.debug("getCode() code : '{}', accessToken '{}'", code, accessToken);

        if (keycloakAuthorizationUri.isEmpty()) {
            log.info("oidc Authentication not configured");
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        try {
            if( accessToken == null || accessToken.trim().isEmpty()){
                ServletUriComponentsBuilder servletUriComponentsBuilder = ServletUriComponentsBuilder.fromRequestUri(request);
                String redirectUri = servletUriComponentsBuilder.path("/../code").build().normalize().toString();

                String token = oidcRestService.exchangeCodeToToken(deployment.getTokenUrl(), code, redirectUri);
                log.debug("getCode() code : '{}', token was '{}'", code, token);

                String userinfoURL = deployment.getTokenUrl().replace("/token", "/userinfo");
                log.debug("connecting for user info to  '{}' with token was '{}'", userinfoURL, token);

                KeycloakUserDetails keycloakUserDetails = oidcRestService.getUserInfo(userinfoURL, token);

                if (keycloakUserDetails != null) {
                    return buildAndForwardJWT(servletUriComponentsBuilder, keycloakUserDetails);
                } else {
                    log.info("keycloakUserDetails == null, token was '{}'", token);
                }
            }else{
                log.info("ToDo : authToken = '{}'", accessToken);
            }

        } catch (RuntimeException re) {
            log.warn("problem processing OIDC code, token or authentication", re);
        } catch (JsonProcessingException | UnsupportedEncodingException e) {
            log.warn("problem processing OIDC code and token", e);
        }

        return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
    }

    @NotNull
    private ResponseEntity<String> buildAndForwardJWT(ServletUriComponentsBuilder servletUriComponentsBuilder, KeycloakUserDetails keycloakUserDetails) {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        log.info("Current authentication in SecurityContext: " + securityContext.getAuthentication());

        List<OpenIDAttribute> attributes = new ArrayList<>();

        OpenIDAuthenticationToken authentication = new OpenIDAuthenticationToken(
            oidcRestService.retrieveUserName(keycloakUserDetails),
            oidcRestService.getAuthorities(keycloakUserDetails),
            "identityUrl",
            attributes);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        securityContext.setAuthentication(authentication);
        String jwt = tokenProvider.createToken(authentication, false);

        HttpHeaders httpHeaders = new HttpHeaders();
        String startUri = servletUriComponentsBuilder.path("/../..").queryParam("bearer", jwt).build().normalize().toString();

        httpHeaders.add("Location", startUri);
        httpHeaders.add(JWTFilter.AUTHORIZATION_HEADER, "Bearer " + jwt);
        return new ResponseEntity<>(keycloakUserDetails.getName(), httpHeaders, HttpStatus.TEMPORARY_REDIRECT);
    }

    @GetMapping(value={"/tokenImplicit"})
    public ResponseEntity<String> getToken(HttpServletRequest request,
                                        @RequestParam(required = false, name = "access_token") String access_token) {

        log.debug("getToken(): retrieved token '{}'", access_token);

        if( access_token != null && !access_token.trim().isEmpty()) {

            TokenVerifier<AccessToken> tokenVerifier = TokenVerifier.create(access_token, AccessToken.class);

            tokenVerifier.withDefaultChecks()
                .realmUrl(deployment.getRealmInfoUrl());

            try {
                String kid = tokenVerifier.getHeader().getKeyId();
                PublicKey publicKey = getPublicKey(kid, deployment);
                tokenVerifier.publicKey(publicKey);

                AccessToken accessToken = tokenVerifier.verify().getToken();
                log.info("accessToken Name: {}", accessToken.getName());

                KeycloakUserDetails keycloakUserDetails = oidcRestService.getUserInfo(accessToken);

                if (keycloakUserDetails != null) {
                    ServletUriComponentsBuilder servletUriComponentsBuilder = ServletUriComponentsBuilder.fromRequestUri(request);
                    return buildAndForwardJWT(servletUriComponentsBuilder, keycloakUserDetails);
                } else {
                    log.info("keycloakUserDetails == null, token was '{}'", access_token);
                }

            } catch (VerificationException e) {
                log.warn("problem processing access token", e);

            }
        }

        return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
    }

    private PublicKey getPublicKey(String kid, KeycloakDeployment deployment) throws VerificationException {
        PublicKeyLocator pkLocator = deployment.getPublicKeyLocator();

        PublicKey publicKey = pkLocator.getPublicKey(kid, deployment);
        if (publicKey == null) {
            log.warn("Didn't find publicKey for kid: {}", kid);
            throw new VerificationException("Didn't find publicKey for specified kid");
        }

        return publicKey;
    }

    @CrossOrigin
    @PostMapping("/logout")
    public ResponseEntity logout(HttpServletRequest request) {

        ServletUriComponentsBuilder servletUriComponentsBuilder = ServletUriComponentsBuilder.fromRequestUri(request);
        String redirectCodeUri = servletUriComponentsBuilder.path("/../..").build().normalize().toString();
        KeycloakUriBuilder builder = deployment.getLogoutUrl().clone()
            .queryParam(OAuth2Constants.REDIRECT_URI, redirectCodeUri);

        String redirectUrl = builder.build().toString();
        log.info("logout redirectUrl : '{}'", redirectUrl);

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Access-Control-Allow-Origin", "*");
        httpHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        httpHeaders.add("Access-Control-Allow-Headers", "Origin, Content-Type, X-Auth-Token");
        httpHeaders.add("Location", redirectUrl);

        return new ResponseEntity<>(httpHeaders, HttpStatus.OK);
    }
}
