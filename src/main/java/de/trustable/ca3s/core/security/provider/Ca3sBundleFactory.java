package de.trustable.ca3s.core.security.provider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Calendar;

import org.bouncycastle.asn1.x500.X500Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.trustable.ca3s.cert.bundle.BundleFactory;
import de.trustable.ca3s.cert.bundle.KeyCertBundle;
import de.trustable.util.CryptoUtil;

public class Ca3sBundleFactory implements BundleFactory {

    private static final Logger LOG = LoggerFactory.getLogger(Ca3sBundleFactory.class);

	private KeyPair rootKeyPair = null ;
	private X509Certificate issuingCertificate = null;
	
	private X500Name x500Issuer;

	private CryptoUtil cryptoUtil = new CryptoUtil();

	
	public Ca3sBundleFactory() {
	    x500Issuer = new X500Name("CN=temporary bootstrap root " + System.currentTimeMillis() + ", O=trustable Ltd, C=DE");
	}
	
	private synchronized KeyPair getRootKeyPair() throws GeneralSecurityException{
		
		if( rootKeyPair == null) {
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		    kpg.initialize(2048);
		    rootKeyPair = kpg.generateKeyPair();
			LOG.debug("created new root keypair : {}", rootKeyPair.toString());
		}
	    return rootKeyPair;
	}

	private synchronized X509Certificate getRootCertificate() throws GeneralSecurityException, IOException{

		if( issuingCertificate == null) {
		    issuingCertificate = cryptoUtil.buildSelfsignedCertificate(x500Issuer,getRootKeyPair());
			LOG.debug("created temp. root certificate with subject : {}", issuingCertificate.getSubjectDN().getName());
			
			File rootCertFile = File.createTempFile("ca3sTempRoot", ".cer");
			try(FileOutputStream fos = new FileOutputStream(rootCertFile)){
				fos.write(issuingCertificate.getEncoded());
			}
			LOG.debug("written temp. root certificate to file : '{}'", rootCertFile.getAbsolutePath());
		}
		return issuingCertificate;
	}
	
	@Override
	public KeyCertBundle newKeyBundle(String bundleName) throws GeneralSecurityException {
		
		
		KeyPair localKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		
		try {
			InetAddress ip = InetAddress.getLocalHost();
			String hostname = ip.getHostName();
			LOG.debug("requesting certificate for host : " + hostname );
			X500Name subject = new X500Name("CN=" + hostname);

			X509Certificate issuedCertificate = cryptoUtil.issueCertificate(x500Issuer,
					getRootKeyPair(), 
					subject, 
					localKeyPair.getPublic().getEncoded(),
					Calendar.HOUR, 1);

			// build the (short) chain
			X509Certificate[] certificateChain = {issuedCertificate, getRootCertificate()};

			LOG.debug("returning new  certificate : " + issuedCertificate );

			return new KeyCertBundle(bundleName, certificateChain, issuedCertificate, localKeyPair.getPrivate());
			
		} catch (IOException e) {
			// certificate creation failed with an exception not inheriting from 'GeneralSecurityException'
			throw new GeneralSecurityException(e);
		}

	}

}
