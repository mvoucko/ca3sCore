/*^
  ===========================================================================
  ACME server
  ===========================================================================
  Copyright (C) 2017-2018 DENIC eG, 60329 Frankfurt am Main, Germany
  ===========================================================================
  Permission is hereby granted, free of charge, to any person obtaining a copy
  of this software and associated documentation files (the "Software"), to deal
  in the Software without restriction, including without limitation the rights
  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  copies of the Software, and to permit persons to whom the Software is
  furnished to do so, subject to the following conditions:

  The above copyright notice and this permission notice shall be included in
  all copies or substantial portions of the Software.

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
  THE SOFTWARE.
  ===========================================================================
*/

package de.trustable.ca3s.core.web.rest.acme;

import de.trustable.ca3s.core.domain.ACMEAccount;
import de.trustable.ca3s.core.domain.AcmeAuthorization;
import de.trustable.ca3s.core.domain.AcmeChallenge;
import de.trustable.ca3s.core.domain.AcmeOrder;
import de.trustable.ca3s.core.domain.enumeration.AcmeOrderStatus;
import de.trustable.ca3s.core.domain.enumeration.ChallengeStatus;
import de.trustable.ca3s.core.repository.AcmeChallengeRepository;
import de.trustable.ca3s.core.repository.AcmeOrderRepository;
import de.trustable.ca3s.core.service.AuditService;
import de.trustable.ca3s.core.service.dto.acme.ChallengeResponse;
import de.trustable.ca3s.core.service.dto.acme.problem.AcmeProblemException;
import de.trustable.ca3s.core.service.dto.acme.problem.ProblemDetail;
import de.trustable.ca3s.core.service.util.ACMEUtil;
import de.trustable.ca3s.core.service.util.PreferenceUtil;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.GeneralName;
import org.jose4j.jwt.consumer.JwtContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.UriComponentsBuilder;
import org.xbill.DNS.*;

import javax.net.ssl.*;
import javax.validation.constraints.NotNull;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.ResponseEntity.ok;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentRequestUri;
import static org.xbill.DNS.Name.*;
import static org.xbill.DNS.Type.TXT;
import static org.xbill.DNS.Type.string;


@Controller
@RequestMapping("/acme/{realm}/challenge")
public class ChallengeController extends ACMEController {

    private static final Logger LOG = LoggerFactory.getLogger(ChallengeController.class);

    public static final Name ACME_CHALLENGE_PREFIX = fromConstantString("_acme-challenge");

    /**
     * OID of the {@code acmeValidation} extension.
     */
    public static final String ACME_VALIDATION_OID = "1.3.6.1.5.5.7.1.31";
    public static final String ACME_TLS_1_PROTOCOL = "acme-tls/1";


    @Value("${ca3s.acme.reject.get:true}")
    boolean rejectGet;

    private final AcmeChallengeRepository challengeRepository;

    private final AcmeOrderRepository orderRepository;

	private final PreferenceUtil preferenceUtil;

    private final SimpleResolver dnsResolver;

    private final AuditService auditService;

    public ChallengeController(AcmeChallengeRepository challengeRepository,
                               AcmeOrderRepository orderRepository,
                               PreferenceUtil preferenceUtil,
                               @Value("${ca3s.dns.server:}") String resolverHost,
                               @Value("${ca3s.dns.port:53}") int resolverPort,
                               AuditService auditService) throws UnknownHostException {
        this.challengeRepository = challengeRepository;
        this.orderRepository = orderRepository;
        this.preferenceUtil = preferenceUtil;

        this.dnsResolver = new SimpleResolver(resolverHost);
        this.auditService = auditService;
        this.dnsResolver.setPort(resolverPort);
        LOG.info("Applying default DNS resolver {}", this.dnsResolver.getAddress());
    }

    @RequestMapping(value = "/{challengeId}", method = GET, produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getChallenge(@PathVariable final long challengeId) {

	  	LOG.debug("Received Challenge request ");

	    final HttpHeaders additionalHeaders = buildNonceHeader();

        if( rejectGet ){
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(additionalHeaders).build();
        }

        Optional<AcmeChallenge> challengeOpt = challengeRepository.findById(challengeId);
		if(!challengeOpt.isPresent()) {
		    return ResponseEntity.notFound().headers(additionalHeaders).build();
		}else {
			AcmeChallenge challengeDao = challengeOpt.get();

			LOG.debug( "returning challenge {}", challengeDao.getId());

			ChallengeResponse challenge = buildChallengeResponse(challengeDao);

			if(challengeDao.getStatus() == ChallengeStatus.VALID ) {
				URI authUri = locationUriOfAuthorization(challengeDao.getAcmeAuthorization().getAcmeAuthorizationId(), fromCurrentRequestUri());
			    additionalHeaders.set("Link", "<" + authUri.toASCIIString() + ">;rel=\"up\"");
			    return ok().headers(additionalHeaders).body(challenge);
			}else {
			    return ok().headers(additionalHeaders).body(challenge);
			}
		}

    }

    @RequestMapping(value = "/{challengeId}", method = POST, produces = APPLICATION_JSON_VALUE, consumes = APPLICATION_JOSE_JSON_VALUE)
    public ResponseEntity<?> postChallenge(@RequestBody final String requestBody,
          @PathVariable final long challengeId, @PathVariable final String realm) {

        LOG.debug("Received Challenge request ");

        try {
            JwtContext context = jwtUtil.processFlattenedJWT(requestBody);

            ACMEAccount acctDao = checkJWTSignatureForAccount(context, realm);

            final HttpHeaders additionalHeaders = buildNonceHeader();

            Optional<AcmeChallenge> challengeOpt = challengeRepository.findById(challengeId);
            if(!challengeOpt.isPresent()) {
                return ResponseEntity.notFound().headers(additionalHeaders).build();
            }else {
                AcmeChallenge challengeDao = challengeOpt.get();

                AcmeOrder order = challengeDao.getAcmeAuthorization().getOrder();

                if(!order.getAccount().getAccountId().equals(acctDao.getAccountId())) {
                    LOG.warn("Account of signing key {} does not match account id {} associated to given challenge{}", acctDao.getAccountId(), challengeDao.getAcmeAuthorization().getOrder().getAccount().getAccountId(), challengeId);
                    final ProblemDetail problem = new ProblemDetail(ACMEUtil.MALFORMED, "Account / Auth mismatch",
                        BAD_REQUEST, "", ACMEController.NO_INSTANCE);
                    throw new AcmeProblemException(problem);
                }

                boolean solved = isChallengeSolved(challengeDao);

                ChallengeResponse challenge = buildChallengeResponse(challengeDao);

                if( solved) {
                    LOG.debug("validation of challenge{} of type '{}' succeeded", challengeId, challengeDao.getType());
                }else {
                    LOG.warn("validation of challenge{} of type '{}' failed", challengeId, challengeDao.getType());
                }

                URI authUri = locationUriOfAuthorization(challengeDao.getAcmeAuthorization().getAcmeAuthorizationId(), fromCurrentRequestUri());
                additionalHeaders.set("Link", "<" + authUri.toASCIIString() + ">;rel=\"up\"");
                return ok().headers(additionalHeaders).body(challenge);
            }

        } catch (AcmeProblemException e) {
            return buildProblemResponseEntity(e);
        }
    }

    public boolean isChallengeSolved(AcmeChallenge challengeDao) {

        LOG.debug( "checking challenge {}", challengeDao.getId());

        boolean solved = false;
        ChallengeStatus newChallengeState = null;
        if( AcmeChallenge.CHALLENGE_TYPE_HTTP_01.equals(challengeDao.getType())) {
            if (checkChallengeHttp(challengeDao)) {
                newChallengeState = ChallengeStatus.VALID;
                solved = true;
            } else {
                newChallengeState = ChallengeStatus.PENDING;
            }
        }else if( AcmeChallenge.CHALLENGE_TYPE_DNS_01.equals(challengeDao.getType())){
            if (checkChallengeDNS(challengeDao)) {
                newChallengeState = ChallengeStatus.VALID;
                solved = true;
            } else {
                newChallengeState = ChallengeStatus.PENDING;
            }
        }else if( AcmeChallenge.CHALLENGE_TYPE_ALPN_01.equals(challengeDao.getType())){
            if (checkChallengeALPN(challengeDao)) {
                newChallengeState = ChallengeStatus.VALID;
                solved = true;
            } else {
                newChallengeState = ChallengeStatus.PENDING;
            }
        }else{
            LOG.warn("Unexpected type '{}' of challenge{}", challengeDao.getType(), challengeDao.getId());
        }

        if( newChallengeState != null) {
            ChallengeStatus oldChallengeState = challengeDao.getStatus();
            if(!oldChallengeState.equals(newChallengeState)) {
                challengeDao.setStatus(newChallengeState);
                challengeDao.setValidated(Instant.now());
                challengeRepository.save(challengeDao);

                LOG.debug("{} challengeDao set to '{}' at {}", challengeDao.getType(), challengeDao.getStatus().toString(), challengeDao.getValidated());
            }
        }

        alignOrderState(challengeDao.getAcmeAuthorization().getOrder());

        return solved;
    }

    void alignOrderState(AcmeOrder orderDao){

        if( orderDao.getStatus().equals(AcmeOrderStatus.READY) ){
          LOG.info("order status already '{}', no re-check after challenge state change required", orderDao.getStatus() );
          return;
        }

        if( orderDao.getStatus() != AcmeOrderStatus.PENDING) {
          LOG.warn("unexpected order status '{}' (!= Pending), no re-check after challenge state change required", orderDao.getStatus() );
          return;
        }

        boolean orderReady = true;

        /*
        * check all authorizations having at least one successfully validated challenge
        */
        for (AcmeAuthorization authDao : orderDao.getAcmeAuthorizations()) {

            boolean authReady = false;
            for (AcmeChallenge challDao : authDao.getChallenges()) {
                if (challDao.getStatus() == ChallengeStatus.VALID) {
                    LOG.debug("challenge {} of type {} is valid ", challDao.getChallengeId(), challDao.getType());
                    authReady = true;
                    break;
                }
            }
            if (authReady) {
                LOG.debug("found valid challenge, authorization id {} is valid ", authDao.getAcmeAuthorizationId());
            } else {
                LOG.debug("no valid challenge, authorization id {} and order {} still pending",
                    authDao.getAcmeAuthorizationId(), orderDao.getOrderId());
                orderReady = false;
                break;
            }
        }
        if( orderReady ){
          LOG.debug("order status set to READY" );
            auditService.saveAuditTrace(
                auditService.createAuditTraceACMEOrderSucceeded(orderDao.getAccount(), orderDao));

            orderDao.setStatus(AcmeOrderStatus.READY);
          orderRepository.save(orderDao);
        }
    }

    private boolean checkChallengeDNS(AcmeChallenge challengeDao) {

        String identifierValue = challengeDao.getValue();
        String token = challengeDao.getToken();

        final Name nameToLookup;
        try {
            final Name nameOfIdentifier = fromString(identifierValue, root);
            nameToLookup = concatenate(ACME_CHALLENGE_PREFIX, nameOfIdentifier);

        } catch (TextParseException | NameTooLongException e) {
            throw new RuntimeException(identifierValue + " invalid", e);
        }

        final Lookup lookupOperation = new Lookup(nameToLookup, TXT);
        lookupOperation.setResolver(dnsResolver);
        lookupOperation.setCache(null);
        LOG.info("DNS lookup: {} records of '{}' (via resolver '{}')", string(TXT), nameToLookup, this.dnsResolver.getAddress());

        final Instant startedAt = Instant.now();
        final org.xbill.DNS.Record[] lookupResult = lookupOperation.run();
        final Duration lookupDuration = Duration.between(startedAt, Instant.now());
        LOG.info("DNS lookup yields: {} (took {})", Arrays.toString(lookupResult), lookupDuration);

        final Collection<String> retrievedToken = extractTokenFrom(lookupResult);
        if (retrievedToken.isEmpty()) {
            LOG.info("Found no DNS entry solving '{}'", identifierValue);
            return false;
        } else {
            final boolean matchingDnsEntryFound = retrievedToken.stream().anyMatch(token::equals);
            if (matchingDnsEntryFound) {
                return true;
            } else {
                LOG.info("Did not find matching token '{}' in TXT record DNS response", token);
                return false;
            }
        }
    }

    /**
     * @param lookupResult Optional
     * @return Never <code>null</code>
     */
    private @NotNull List<String> extractTokenFrom(final Record[] lookupResult) {

        List<String> tokenList = new ArrayList<>();
        if( lookupResult != null) {
            for (Record record : lookupResult) {
                LOG.debug("Found DNS entry solving '{}'", record);
                tokenList.addAll(((TXTRecord) record).getStrings());
            }
        }
        return tokenList;
    }


    private boolean checkChallengeHttp(AcmeChallenge challengeDao) {

		int[] ports = {80, 5544, 8800};

		long timeoutMilliSec = preferenceUtil.getAcmeHTTP01TimeoutMilliSec();
		String portList = preferenceUtil.getAcmeHTTP01CallbackPorts();

		if(portList != null && !portList.trim().isEmpty()) {
			String[] parts = portList.split(",");
			ports = new int[parts.length];
		    for( int i = 0; i < parts.length; i++) {
		    	ports[i] = -1;
		    	try {
		    		ports[i] = Integer.parseInt(parts[i].trim());
		    		LOG.debug("checkChallengeHttp port number '" + ports[i] + "' configured for HTTP callback");
		    	} catch( NumberFormatException nfe) {
					LOG.warn("checkChallengeHttp port number parsing fails for '" + ports[i] + "', ignoring", nfe);
		    	}
		    }

		}

        AcmeOrder acmeOrder = challengeDao.getAcmeAuthorization().getOrder();
	    String token = challengeDao.getToken();
        String expectedContent = buildKeyAuthorization(challengeDao);

	    String fileNamePath = "/.well-known/acme-challenge/" + token;
	    String host = challengeDao.getAcmeAuthorization().getValue();

        String ioExceptionMsg = "";
	    for( int port: ports) {

		    try {
				URL url = new URL("http", host, port, fileNamePath);
				LOG.debug("Opening connection to  : " + url);

				HttpURLConnection con = (HttpURLConnection) url.openConnection();

				// Just wait for two seconds
				con.setConnectTimeout((int) timeoutMilliSec);
				con.setReadTimeout((int) timeoutMilliSec);

				// optional default is GET
				con.setRequestMethod("GET");

				// add request header
				con.setRequestProperty("User-Agent", "CA3S_ACME");

				int responseCode = con.getResponseCode();
				LOG.debug("\nSending 'GET' request to URL : " + url);
				LOG.debug("Response Code : " + responseCode);

				if( responseCode != 200) {
					LOG.info("read challenge responded with unexpected code : " + responseCode);
					continue;
				}

				BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
				String inputLine;
				StringBuffer response = new StringBuffer();

				while ((inputLine = in.readLine()) != null) {
					response.append(inputLine);
				}
				in.close();

				String actualContent = response.toString().trim();
                if( actualContent.length() > 100){
                    LOG.debug("read challenge response (truncated): " + actualContent.substring(0,100) + " ...");
                }else {
                    LOG.debug("read challenge response: " + actualContent);
                }
				LOG.debug("expected content: '{}'", expectedContent);

                boolean matches = expectedContent.equals( actualContent);

                if(matches) {
                    auditService.saveAuditTrace(
                        auditService.createAuditTraceACMEChallengeSucceeded(acmeOrder.getAccount(), acmeOrder,
                            "challenge response matches at host '" + host + ":" + port + "'"));
                }else{
                    auditService.saveAuditTrace(
                        auditService.createAuditTraceACMEChallengeFailed(acmeOrder.getAccount(), acmeOrder,
                            "challenge response mismatch at host '" + host + ":" + port + "'"));
                }
				return matches;

		    } catch(UnknownHostException uhe) {
				LOG.debug("unable to resolve hostname ", uhe);
                auditService.saveAuditTrace(
                    auditService.createAuditTraceACMEChallengeFailed(acmeOrder.getAccount(), acmeOrder, "unable to resolve hostname '" + host + "'"));
                return false;
		    } catch(IOException ioe) {
                ioExceptionMsg += "unable to read challenge response on '" + host + ":" + port + "' ";
				LOG.info("problem reading challenge response on {}:{} for challenge id {} : {}", host, port, challengeDao.getId(), ioe.getMessage());
				LOG.debug("exception occurred reading challenge response", ioe);
		    }
	    }

//        auditService.saveAuditTrace(
//            auditService.createAuditTraceACMEChallengeFailed(acmeOrder.getAccount(), acmeOrder, ioExceptionMsg));

		return false;
	}

    private boolean checkChallengeALPN(AcmeChallenge challengeDao) {

        int[] ports = {443, 8443};

        AcmeOrder acmeOrder = challengeDao.getAcmeAuthorization().getOrder();
        String expectedContent = Base64.getEncoder().encodeToString(buildKeyAuthorizationHash(challengeDao));

        String host = challengeDao.getAcmeAuthorization().getValue();

        // this is rare case where a trustAll-Manager makes sense as the details of the certificate get checked later on
        // please think twice before using the trustAll-Manager in a productive context !!
        TrustManager[] trustAllCerts = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            public void checkClientTrusted(X509Certificate[] certs, String authType) {
            }

            public void checkServerTrusted(X509Certificate[] certs, String authType) {
            }
        } };

        String ioExceptionMsg = "";
        for( int port: ports) {

            try {
                if(validateALPNChallenge(acmeOrder, expectedContent, host, trustAllCerts, port)){
                    LOG.debug("alpn challenge validation successful on '" + host + ":" + port + "' ");
                    return true;
                }

            } catch(UnknownHostException uhe) {
                LOG.debug("unable to resolve hostname ", uhe);
                auditService.saveAuditTrace(
                    auditService.createAuditTraceACMEChallengeFailed(acmeOrder.getAccount(), acmeOrder, "unable to resolve hostname '" + host + "'"));
                return false;
            } catch(IOException ioe) {
                ioExceptionMsg += "unable to read challenge response on '" + host + ":" + port + "' ";
                LOG.info("problem reading challenge response on {}:{} for challenge id {} : {}", host, port, challengeDao.getId(), ioe.getMessage());
                LOG.debug("exception occurred reading challenge response", ioe);
            } catch (CertificateException ce) {
                ioExceptionMsg += "unable to read alpn challenge response in certificate provided by '" + host + ":" + port + "' ";
                LOG.info("problem reading alpn challenge response in certificate provided by {}:{} for challenge id {} : {}", host, port, challengeDao.getId(), ce.getMessage());
                LOG.debug("exception occurred reading alpn challenge response certificate", ce);
            }
        }

//        auditService.saveAuditTrace(
//            auditService.createAuditTraceACMEChallengeFailed(acmeOrder.getAccount(), acmeOrder, ioExceptionMsg));

        return false;
    }

    private boolean validateALPNChallenge(AcmeOrder acmeOrder, String expectedContent, String host, TrustManager[] trustAllCerts, int port) throws IOException, CertificateException {
        LOG.debug("Opening ALPN connection to {}:{} ", host, port);

        Certificate[] serverCerts = new Certificate[0];
        SSLSocket sslSocket = null;
        try {
            // Code for creating a client side SSLSocket
            SSLContext sslContext = SSLContext.getInstance("TLS");;

            sslContext.init(null, trustAllCerts, new SecureRandom());
            SSLSocketFactory sslsf = sslContext.getSocketFactory();

            sslSocket = (SSLSocket) sslsf.createSocket(host, port);

            // Get an SSLParameters object from the SSLSocket
            SSLParameters sslp = sslSocket.getSSLParameters();

            SNIHostName serverName = new SNIHostName(host);
            sslp.setServerNames(Collections.singletonList(serverName));

            // Populate SSLParameters with the ALPN values
            // On the client side the order doesn't matter as
            // when connecting to a JDK server, the server's list takes priority
            String[] clientAPs = {ACME_TLS_1_PROTOCOL};
            sslp.setApplicationProtocols(clientAPs);


            // Populate the SSLSocket object with the SSLParameters object
            // containing the ALPN values
            sslSocket.setSSLParameters(sslp);

            sslSocket.startHandshake();

            // After the handshake, get the application protocol that has been negotiated
            String ap = sslSocket.getApplicationProtocol();
            LOG.debug("Application Protocol server side: \"" + ap + "\"");

            serverCerts = sslSocket.getSession().getPeerCertificates();

        } catch (NoSuchAlgorithmException| KeyManagementException e) {
            LOG.warn("algorithm initialization problem ",e);
            return false;
        } finally {
            if( sslSocket != null) {
                sslSocket.close();
            }
        }

        if(serverCerts.length == 0){
            LOG.warn("no certificate available after connection with {}:{}", host, port);
            return false;
        }else if(serverCerts.length > 1){
            LOG.warn("more than one (#{}) certificate returned {}:{}, expecting a single selfsigned certificate",serverCerts.length, host, port);
            return false;
        }

        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        InputStream in = new ByteArrayInputStream(serverCerts[0].getEncoded());
        X509Certificate cert = (X509Certificate)certFactory.generateCertificate(in);

        if (checkALPNCertificate(host, port, cert)){
            return false;
        }

        byte[] acmeValidationExtBytes = cert.getExtensionValue(ACME_VALIDATION_OID);
        ASN1OctetString octetString = (ASN1OctetString) ASN1OctetString.fromByteArray(acmeValidationExtBytes);
        ASN1OctetString rfc8737OctetString = (ASN1OctetString) ASN1OctetString.fromByteArray(octetString.getOctets());
        String actualContent = Base64.getEncoder().encodeToString(rfc8737OctetString.getOctets());

        if( rfc8737OctetString.getOctets().length > 32){
            LOG.debug("actualContent has unexpected length: {}",  rfc8737OctetString.getOctets().length);
/*
            byte[] challenge = new byte[32];
            System.arraycopy(rfc8737OctetString.getOctets(), rfc8737OctetString.getOctets().length - 32, challenge, 0, 32);
            actualContent = Base64.getEncoder().encodeToString(challenge);
*/
            return false;
        }

        LOG.debug("read challenge response: " + actualContent);
        LOG.debug("expected content: '{}'", expectedContent);

        boolean matches = expectedContent.equals(actualContent);

        if(matches) {
            auditService.saveAuditTrace(
                auditService.createAuditTraceACMEChallengeSucceeded(acmeOrder.getAccount(), acmeOrder,
                    "alpn challenge response matches at host '" + host + ":" + port + "'"));
        }else{
            auditService.saveAuditTrace(
                auditService.createAuditTraceACMEChallengeFailed(acmeOrder.getAccount(), acmeOrder,
                    "alpn challenge response mismatch at host '" + host + ":" + port + "'"));
        }
        return matches;
    }

    public static boolean checkALPNCertificate(String host, int port, X509Certificate cert) throws CertificateParsingException {

        if( LOG.isDebugEnabled()){
            try {
                LOG.debug("alpn certificate : {}", Base64.getEncoder().encodeToString(cert.getEncoded()));
            } catch (CertificateEncodingException e) {
                e.printStackTrace();
            }
        }
        // Check SAN entry
        if( cert.getSubjectAlternativeNames().isEmpty()){
            LOG.warn("no SAN entry available in certificate provided by {}:{}", host, port);
            return true;
        } else if( cert.getSubjectAlternativeNames().size() > 1){
            LOG.warn("more than one SAN entry (#{}) included in certificate provided by {}:{}", cert.getSubjectAlternativeNames().size(), host, port);
            return true;
        }

        Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
        if (altNames != null) {
            for (List<?> altName : altNames) {
                int altNameType = (Integer) altName.get(0);

                if (GeneralName.dNSName == altNameType){
                    String sanValue = "";
                    if (altName.get(1) instanceof String) {
                        sanValue = ((String) altName.get(1)).toLowerCase();
                    } else if (altName.get(1) instanceof byte[]) {
                        sanValue = new String((byte[]) (altName.get(1))).toLowerCase();
                    }

                    if( host.equalsIgnoreCase(sanValue)){
                        LOG.debug("SAN entry '{}' machtes expected host '{}'", sanValue, host);
                    }else{
                        LOG.warn("SAN entry value ({}) in certificate provided by {}:{}, does not match expected  host '{}'", sanValue, host, port, host);
                        return true;
                    }
                }else{
                    LOG.warn("unexpected SAN entry type ({}) included in certificate provided by {}:{}, 'DNS' (2) expected.", altNameType, host, port);
                    return true;
                }
            }
        }

        // Check ACME extension
        if( cert.getCriticalExtensionOIDs().contains(ACME_VALIDATION_OID) ){
            LOG.debug("ACME validation oid is present and marked as critical!");
        }else{
            LOG.warn("ACME validation oid is NOT present and NOT marked as critical in certificate provided by  {}:{}", host, port);
            return true;
        }
        return false;
    }


    private byte[] buildKeyAuthorizationHash(AcmeChallenge challengeDao) {

        try {
            return cryptoUtil.getSHA256Digest(buildKeyAuthorization(challengeDao).getBytes());
        } catch (NoSuchAlgorithmException e) {
            LOG.warn("Hashing challenge data failed", e);
            return new byte[0];
        }
    }

    private String buildKeyAuthorization(AcmeChallenge challengeDao){
        String token = challengeDao.getToken();
        String pkThumbprint = challengeDao.getAcmeAuthorization().getOrder().getAccount().getPublicKeyHash();
        String authorization =  token + '.' + pkThumbprint;
        LOG.debug("authorization: {}", authorization);
        return authorization;
    };

    ChallengeResponse buildChallengeResponse(final AcmeChallenge challengeDao){
        return new ChallengeResponse(challengeDao, locationUriOfChallenge(challengeDao.getId(), fromCurrentRequestUri()).toString());
    }

    private URI locationUriOfChallenge(final long challengeId, final UriComponentsBuilder uriBuilder) {
	    return challengeResourceUriBuilderFrom(uriBuilder.path("../..")).path("/").path(Long.toString(challengeId)).build().normalize().toUri();
	}

    private URI locationUriOfAuthorization(final long authorizationId, final UriComponentsBuilder uriBuilder) {
	    return authorizationResourceUriBuilderFrom(uriBuilder.path("../..")).path("/").path("..").path("/").path(Long.toString(authorizationId)).build().normalize().toUri();
	}

}
