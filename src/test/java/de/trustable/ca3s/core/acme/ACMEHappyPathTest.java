package de.trustable.ca3s.core.acme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Metadata;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.takes.Take;
import org.takes.facets.fork.FkRegex;
import org.takes.facets.fork.TkFork;
import org.takes.http.Exit;
import org.takes.http.FtBasic;

import de.trustable.ca3s.cert.bundle.TimedRenewalCertMap;
import de.trustable.ca3s.core.Ca3SJhApp;
import de.trustable.ca3s.core.domain.enumeration.AccountStatus;
import de.trustable.ca3s.core.security.provider.Ca3sFallbackBundleFactory;
import de.trustable.ca3s.core.security.provider.Ca3sKeyManagerProvider;
import de.trustable.ca3s.core.security.provider.Ca3sKeyStoreProvider;
import de.trustable.ca3s.core.security.provider.TimedRenewalCertMapHolder;
import de.trustable.util.JCAManager;



@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Ca3SJhApp.class)
@RunWith(SpringRunner.class)
@ActiveProfiles("int")
@TestPropertySource(locations = "classpath:config/application_test.yml")
public class ACMEHappyPathTest {

    private static final Logger LOG = LoggerFactory.getLogger(ACMEHappyPathTest.class);

	@Value("${local.server.port}")
	int serverPort; // random port chosen by spring test

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		
		JCAManager.getInstance();
		
		TimedRenewalCertMap certMap = new TimedRenewalCertMap(null, new Ca3sFallbackBundleFactory());
		Security.addProvider(new Ca3sKeyStoreProvider(certMap, "ca3s"));
    	Security.addProvider(new Ca3sKeyManagerProvider(certMap));
    	new TimedRenewalCertMapHolder().setCertMap(certMap);
	}

	
	@Test
	public void testAccountHandling() throws AcmeException {

		String dirUrl = "http://localhost:" + serverPort + "/acme/foo/directory";
		System.out.println("connecting to " + dirUrl );
		Session session = new Session(dirUrl);
		Metadata meta = session.getMetadata();
		
		URI tos = meta.getTermsOfService();
		URL website = meta.getWebsite();
		LOG.debug("TermsOfService {}, website {}", tos, website);
		
		KeyPair accountKeyPair = KeyPairUtils.createKeyPair(2048);
		
//		KeyPair accountKeyPair = KeyPairUtils.createECKeyPair("secp256r1");
		
		
		Account account = new AccountBuilder()
		        .addContact("mailto:acmeTest@ca3s.org")
		        .agreeToTermsOfService()
		        .useKeyPair(accountKeyPair)
		        .create(session);

		assertNotNull("created account MUST NOT be null", account);
		URL accountLocationUrl = account.getLocation();
		LOG.debug("accountLocationUrl {}", accountLocationUrl);
		
		
		Account retrievedAccount = new AccountBuilder()
		        .onlyExisting()         // Do not create a new account
		        .useKeyPair(accountKeyPair)
		        .create(session);
		
		assertNotNull("created account MUST NOT be null", retrievedAccount);
		assertEquals("expected to fimnd the smae account (URL)", accountLocationUrl, retrievedAccount.getLocation());
		
		account.modify()
	      .addContact("mailto:acmeHappyPathTest@ca3s.org")
	      .commit();

		KeyPair accountNewKeyPair = KeyPairUtils.createKeyPair(2048);

		account.changeKey(accountNewKeyPair);
		
		assertNotNull("account contacts MUST NOT be null", account.getContacts());

		KeyPair accountECKeyPair = KeyPairUtils.createECKeyPair("secp256r1");
		account.changeKey(accountECKeyPair);

		account.modify()
	      .addContact("mailto:acmeHappyPathECTest@ca3s.org")
	      .commit();

		assertEquals("three account contact expected", 3, account.getContacts().size());

		account.deactivate();
		
		assertEquals("account status 'deactivated' expected", AccountStatus.DEACTIVATED.toString().toLowerCase(), account.getStatus().toString().toLowerCase() );
	}

	@Test
	public void testOrderHandling() throws AcmeException, IOException, InterruptedException {

		String dirUrl = "http://localhost:" + serverPort + "/acme/foo/directory";
		System.out.println("connecting to " + dirUrl );
		Session session = new Session(dirUrl);
		Metadata meta = session.getMetadata();
		
		URI tos = meta.getTermsOfService();
		URL website = meta.getWebsite();
		LOG.debug("TermsOfService {}, website {}", tos, website);
		
		KeyPair accountKeyPair = KeyPairUtils.createKeyPair(2048);
		
//		KeyPair accountKeyPair = KeyPairUtils.createECKeyPair("secp256r1");
		
		
		Account account = new AccountBuilder()
		        .addContact("mailto:acmeOrderTest@ca3s.org")
		        .agreeToTermsOfService()
		        .useKeyPair(accountKeyPair)
		        .create(session);
		
		Order order = account.newOrder()
		        .domains("localhost")
//		        .identifier(Identifier.ip(InetAddress.getByName("127.0.0.1")))
//		        .domains("localhost", "example.org", "www.example.org", "m.example.org")
//		        .identifier(Identifier.ip(InetAddress.getByName("192.168.56.10")))
		        .notAfter(Instant.now().plus(Duration.ofDays(20L)))
		        .create();
		
		
		for (Authorization auth : order.getAuthorizations()) {
			LOG.debug("checking auth id {} for {} with status {}", auth.getIdentifier(), auth.getLocation(), auth.getStatus());
			if (auth.getStatus() == Status.PENDING) {

				Http01Challenge challenge = auth.findChallenge(Http01Challenge.TYPE);

				provideAuthEndpoint(challenge, order);

				challenge.trigger();
			}
		}

		KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);

		CSRBuilder csrb = new CSRBuilder();
		csrb.addDomain("example.org");
		csrb.addDomain("www.example.org");
		csrb.addDomain("m.example.org");
		csrb.setOrganization("The Example Organization");
		csrb.sign(domainKeyPair);
		byte[] csr = csrb.getEncoded();
		
		order.execute(csr);
		Certificate acmeCert = order.getCertificate();
		assertNotNull("Expected to receive a certificate", acmeCert);
		
		java.security.cert.X509Certificate x509Cert = acmeCert.getCertificate();
		assertNotNull("Expected to receive a x509Cert", x509Cert);
		
		Iterator<Order> orderIt = account.getOrders();
		assertNotNull("Expected to find at least one order", orderIt.hasNext());

		for( int i = 0; i < 100; i++) {
			buildOrder(account, i);
		}

		orderIt = account.getOrders();
		assertNotNull("Expected to find at least one order", orderIt.hasNext());
		
		
		for(int i = 0; orderIt.hasNext(); i++) {
			Order orderRetrieved = orderIt.next();
			LOG.debug("order {} : {}", i, orderRetrieved);
		}

	}

	@Test
	public void testWinStore() throws AcmeException, IOException, GeneralSecurityException, InterruptedException {

		String dirUrl = "http://localhost:" + serverPort + "/acme/foo/directory";
		System.out.println("connecting to " + dirUrl );
		Session session = new Session(dirUrl);
		Metadata meta = session.getMetadata();
		
		URI tos = meta.getTermsOfService();
		URL website = meta.getWebsite();
		LOG.debug("TermsOfService {}, website {}", tos, website);
		
		KeyPair accountKeyPair = KeyPairUtils.createKeyPair(2048);
				
		Account account = new AccountBuilder()
		        .addContact("mailto:acmeOrderWinStoreTest@ca3s.org")
		        .agreeToTermsOfService()
		        .useKeyPair(accountKeyPair)
		        .create(session);
		
		Order order = account.newOrder()
		        .domains("localhost")
//		        .domains("WinStore.example.org")
//		        .identifier(Identifier.ip(InetAddress.getByName("192.168.56.20")))
		        .notAfter(Instant.now().plus(Duration.ofDays(20L)))
		        .create();
		
		for (Authorization auth : order.getAuthorizations()) {
			if (auth.getStatus() == Status.PENDING) {
				LOG.debug("auth {}", auth);
				Http01Challenge challenge = auth.findChallenge(Http01Challenge.TYPE);

				provideAuthEndpoint(challenge, order);

				challenge.trigger();
			}
		}

	    KeyStore keyStore = KeyStore.getInstance("Windows-MY");
	    keyStore.load(null, null);  // Load keystore
		
		KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);

		CSRBuilder csrb = new CSRBuilder();
		csrb.addDomain("WinStore.example.org");
		csrb.setOrganization("The Example Organization' windows client");
		csrb.sign(domainKeyPair);
		byte[] csr = csrb.getEncoded();
		
		order.execute(csr);
		Certificate acmeCert = order.getCertificate();
		assertNotNull("Expected to receive a certificate", acmeCert);
		
		java.security.cert.X509Certificate x509Cert = acmeCert.getCertificate();
		assertNotNull("Expected to receive a x509Cert", x509Cert);

		X509Certificate[] chain = new X509Certificate[acmeCert.getCertificateChain().size()];
		acmeCert.getCertificateChain().toArray(chain);
	    keyStore.setKeyEntry("acmeKey", domainKeyPair.getPrivate(), null, chain);
	     
	    keyStore.store(null, null);

	}
	

	void buildOrder(Account account, int n) throws AcmeException { 
		account.newOrder()
	        .domains("example_"+n+".org")
	        .notAfter(Instant.now().plus(Duration.ofDays(20L)))
	        .create();
	}

	void provideAuthEndpoint(final Http01Challenge challenge, Order order) throws IOException, InterruptedException {

		int callbackPort = 8800;
		final String fileNameRegEx = "/\\.well-known/acme-challenge/" + challenge.getToken();
		String fileContent = challenge.getAuthorization();

		LOG.debug("Handling authorization for {} servingf {}", fileNameRegEx, fileContent);

		Take tk = new TkFork(new FkRegex(fileNameRegEx, fileContent));
		
		FtBasic webBasicTmp = null;
		try {
			webBasicTmp = new FtBasic(tk, callbackPort);
		}catch(BindException be) {
			Thread.sleep(1000L);
			webBasicTmp = new FtBasic(tk, callbackPort);
		}
		final FtBasic webBasic = webBasicTmp;

		final Exit exitOnValid = new Exit() {
			@Override
			public boolean ready() {
				boolean bTerminate = !(order.getStatus().equals( Status.PENDING));
				LOG.debug("exitOnValid {}", order.getStatus().toString());
				return (bTerminate);
			}
		};

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					LOG.debug("ACME callback webserver started for {}", fileNameRegEx);
					webBasic.start(exitOnValid);
					LOG.debug("ACME callback webserver finished for {}", fileNameRegEx);
				} catch (IOException ioe) {
					LOG.warn("exception occur running webserver in extra thread", ioe);
				}
			}
		}).start();

		LOG.debug("started ACME callback webserver for {} on port {}", fileNameRegEx, callbackPort);

	}
}
