package de.trustable.ca3sjh.service.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECParameterSpec;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jcajce.provider.asymmetric.util.EC5Util;
import org.bouncycastle.jce.provider.JCEECPublicKey;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import de.trustable.ca3sjh.domain.CSR;
import de.trustable.ca3sjh.domain.Certificate;
import de.trustable.ca3sjh.domain.CertificateAttribute;
import de.trustable.ca3sjh.repository.CertificateAttributeRepository;
import de.trustable.ca3sjh.repository.CertificateRepository;
import de.trustable.util.OidNameMapper;


@Service
public class CertificateUtil {

	private static final String SERIAL_PADDING_PATTERN = "000000000000000000000";


	private static final Logger LOG = LoggerFactory.getLogger(CertificateUtil.class);

	@Autowired
	private CertificateRepository certificateRepository;

	@Autowired
	private CertificateAttributeRepository certificateAttributeRepository;

	@Autowired
	private CryptoService cryptoUtil;

	
    public Certificate createCertificate(final byte[] encodedCert, final CSR csr, final String executionId, final boolean reimport) throws GeneralSecurityException, IOException {

    	CertificateFactory factory = CertificateFactory.getInstance("X.509");
    	X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(encodedCert));
    	
    	String pemCert = cryptoUtil.x509CertToPem(cert);

        return createCertificate(pemCert, csr, executionId, reimport);

    }

	/**
	 * 
	 * @param pemCert
	 * @param csr
	 * @param executionId
	 * @return
	 * @throws GeneralSecurityException
	 * @throws IOException
	 */
	public Certificate createCertificate(final String pemCert, final CSR csr, 
			final String executionId) throws GeneralSecurityException, IOException {
		
		return createCertificate(pemCert, csr, executionId, false);
	}

	/**
	 * 
	 * @param pemCert
	 * @param csr
	 * @param executionId
	 * @param reimport
	 * @return
	 * @throws GeneralSecurityException
	 * @throws IOException
	 */
	public Certificate createCertificate(final String pemCert, final CSR csr, 
			final String executionId,
			final boolean reimport) throws GeneralSecurityException, IOException {

		Certificate cert = null;
		X509Certificate x509Cert = CryptoService.convertPemToCertificate(pemCert);

		// check for existing instance
		String tbsDigestBase64 = Base64.encodeBase64String(cryptoUtil.getSHA256Digest(x509Cert.getTBSCertificate())).toLowerCase();
		List<Certificate> certList = certificateRepository.findByTBSDigest(tbsDigestBase64);

		if (certList.isEmpty()) {
			cert = createCertificate(pemCert, csr, executionId, x509Cert, tbsDigestBase64);
		} else {
			LOG.info("certificate '" + x509Cert.getSubjectDN().getName() +"' already exists");

			cert = certList.get(0);

			if( reimport ) {
				LOG.debug("existing certificate '" + x509Cert.getSubjectDN().getName() +"' overwriting some attributes, only");
				
				addAdditionalCertificateAttributes(x509Cert, cert);
			}
		}
		return cert;
	}

	/**
	 * @param pemCert
	 * @param csr
	 * @param executionId
	 * @param x509Cert
	 * @param tbsDigestBase64
	 * @return
	 * @throws CertificateEncodingException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 * @throws CertificateParsingException
	 * @throws CertificateException
	 * @throws InvalidKeyException
	 * @throws NoSuchProviderException
	 * @throws SignatureException
	 */
	private Certificate createCertificate(final String pemCert, final CSR csr, final String executionId,
			X509Certificate x509Cert, String tbsDigestBase64)
			throws CertificateEncodingException, IOException, NoSuchAlgorithmException, CertificateParsingException,
			CertificateException, InvalidKeyException, NoSuchProviderException, SignatureException {
		
		Certificate cert;
		LOG.debug("creating new certificate '" + x509Cert.getSubjectDN().getName() +"'");
		
		byte[] certBytes = x509Cert.getEncoded();
		X509CertificateHolder x509CertHolder = new X509CertificateHolder(certBytes);
		
		cert = new Certificate();

		cert.setContent(pemCert);

		cert.setCsr(csr);
		
		// indexed key for searching
		cert.setTbsDigest(tbsDigestBase64);

		String type = "X509V3|" + x509Cert.getVersion();
		cert.setType(type);
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_TYPE, type);

		// derive a readable description
		String desc = cryptoUtil.getDescription(x509Cert);
		cert.setDescription(CryptoService.limitLength(desc, 250));

		String issuer = CryptoService.limitLength(x509Cert.getIssuerDN().getName(), 250);
		cert.setIssuer(issuer);
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_ISSUER, issuer.toLowerCase());

		X500Name x500NameIssuer = x509CertHolder.getIssuer();
		insertNameAttributes(cert, CertificateAttribute.ATTRIBUTE_ISSUER, x500NameIssuer);
/*			
		for( RDN rdn: x500NameIssuer.getRDNs() ){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_ISSUER, rdn.getFirst().getValue().toString().toLowerCase());
		}
*/			
		String subject = CryptoService.limitLength(x509Cert.getSubjectDN().getName(), 250);
		cert.setSubject(subject);
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SUBJECT, subject.toLowerCase());

		X500Name x500NameSubject = x509CertHolder.getSubject();
		insertNameAttributes(cert, CertificateAttribute.ATTRIBUTE_SUBJECT, x500NameSubject);

		JcaX509ExtensionUtils util = new JcaX509ExtensionUtils();
		
		// build two SKI variants for cert identification
		SubjectKeyIdentifier ski = util.createSubjectKeyIdentifier(x509Cert.getPublicKey());
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SKI,
				Base64.encodeBase64String(ski.getKeyIdentifier()));
		
		SubjectKeyIdentifier skiTruncated = util.createTruncatedSubjectKeyIdentifier(x509Cert.getPublicKey());
		if( !ski.equals(skiTruncated)){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SKI,
					Base64.encodeBase64String(skiTruncated.getKeyIdentifier()));
		}

		// good old SHA1 finerprint
		String fingerprint = Base64.encodeBase64String(generateSHA1Fingerprint(certBytes));
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_FINGERPRINT, fingerprint);
		
		// guess some details from basic constraint
		int basicConstraint = x509Cert.getBasicConstraints();
		if (Integer.MAX_VALUE == basicConstraint) {
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_CA, "true");
		} else if (-1 == basicConstraint) {
			addCertAttribute(cert,
					CertificateAttribute.ATTRIBUTE_END_ENTITY, "true");
		} else {
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_CA, "true");
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_CHAIN_LENGTH, "" + basicConstraint);
		}

		// add the basic key usages a attributes
		usageAsCertAttributes( x509Cert.getKeyUsage(), cert );
		
		// add the extended key usages a attributes
		List<String> extKeyUsageList = x509Cert.getExtendedKeyUsage();
		if (extKeyUsageList != null) {
			for (String extUsage : extKeyUsageList) {
				addCertAttribute(cert, OidNameMapper.lookupOid(extUsage), extUsage);
			}
		}


		// add two serial variants
		String serial = x509Cert.getSerialNumber().toString();
		cert.setSerial(serial);
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SERIAL, serial);
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SERIAL_PADDED, getPaddedSerial(serial));

		// add validity period
		cert.setValidFrom( DateUtil.asLocalDate(x509Cert.getNotBefore()));
		addCertAttribute(cert,
				CertificateAttribute.ATTRIBUTE_VALID_FROM_TIMESTAMP, ""
						+ x509Cert.getNotBefore().getTime());

		cert.setValidTo(DateUtil.asLocalDate(x509Cert.getNotAfter()));
		addCertAttribute(cert,
				CertificateAttribute.ATTRIBUTE_VALID_TO_TIMESTAMP, ""
						+ x509Cert.getNotAfter().getTime());

		addAdditionalCertificateAttributes(x509Cert, cert);

		//initialize revocation details
		cert.setRevokedSince(null);
		cert.setRevocationReason(null);
		cert.setRevoked(false);

		if (executionId != null) {
			cert.setCreationExecutionId(executionId);
		}

		if( x500NameIssuer.equals(x500NameSubject) ){
			
			// check whether is really selfsigned 
			x509Cert.verify(x509Cert.getPublicKey());
			cert.setIssuingCertificate(cert);
			LOG.debug("certificate '" + x509Cert.getSubjectDN().getName() +"' is selfsigned");
			
		}else{
			// try to build cert chain
			try{
				Certificate issuingCert = findIssuingCertificate(x509CertHolder);
				cert.setIssuingCertificate(issuingCert);
				if( LOG.isDebugEnabled()){
					LOG.debug("certificate '" + x509Cert.getSubjectDN().getName() +"' issued by " + issuingCert.getSubject());
				}
			} catch( GeneralSecurityException gse){
				LOG.info("unable to find issuer for certificate '" + x509Cert.getSubjectDN().getName() +"' right now ...");
			}
		}

		certificateRepository.save(cert);
		return cert;
	}


	/**
	 * @param x509Cert
	 * @param cert
	 * @throws CertificateParsingException
	 */
	private void addAdditionalCertificateAttributes(X509Certificate x509Cert, Certificate cert)
			throws CertificateParsingException {
		
		// extract signature algo
		String sigAlgName = x509Cert.getSigAlgName().toLowerCase();
		
		dropCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SIGNATURE_ALGO);
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SIGNATURE_ALGO, sigAlgName);

		String keyAlgName = sigAlgName;
		String hashAlgName = null;
		String paddingAlgName = null;
		
		if( sigAlgName.contains("with")) {
			String[] parts = sigAlgName.split("with");
			if(parts.length > 1) {
				hashAlgName = parts[0];
				if(parts[1].contains("and")) {
					String[] parts2 = parts[1].split("and");
					keyAlgName = parts2[0];
					if(parts2.length > 1) {
						paddingAlgName = parts2[1];
					}
				}else {
					keyAlgName = parts[1];
				}
			}
		}

		dropCertAttribute(cert, CertificateAttribute.ATTRIBUTE_KEY_ALGO);
		addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_KEY_ALGO, keyAlgName);
		if(hashAlgName != null) {
			dropCertAttribute(cert, CertificateAttribute.ATTRIBUTE_HASH_ALGO);
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_HASH_ALGO, hashAlgName);
		}
		if(paddingAlgName != null) {
			dropCertAttribute(cert, CertificateAttribute.ATTRIBUTE_PADDING_ALGO);
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_PADDING_ALGO, paddingAlgName);
		}

		try {
			String curveName = deriveCurveName(x509Cert.getPublicKey());
			LOG.info("found curve name "+ curveName +" for certificate '" + x509Cert.getSubjectDN().getName() +"' with key algo " + keyAlgName);
			
			dropCertAttribute(cert, CertificateAttribute.ATTRIBUTE_CURVE_NAME);
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_CURVE_NAME, curveName);
			
		} catch (GeneralSecurityException e) {
			if( keyAlgName.contains("ec")) {
				LOG.info("unable to derive curve name for certificate '" + x509Cert.getSubjectDN().getName() +"' with key algo " + keyAlgName);
			}
		}


		// list all SANs
		if (x509Cert.getSubjectAlternativeNames() != null) {
			dropCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SAN);
			for (List<?> names : x509Cert.getSubjectAlternativeNames()) {

				for (Object name : names) {
					addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_SAN, name.toString().toLowerCase());
				}
			}
		}

		int keyLength = getKeyLength(x509Cert.getPublicKey());
		if(keyLength > 0) {
			dropCertAttribute(cert, CertificateAttribute.ATTRIBUTE_KEY_LENGTH);
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_KEY_LENGTH, keyLength);
		}
		
	}

	
	/**
	 * Gets the key length of supported keys
	 * @param pk PublicKey used to derive the keysize
	 * @return -1 if key is unsupported, otherwise a number >= 0. 0 usually means the length can not be calculated, 
	 * for example if the key is an EC key and the "implicitlyCA" encoding is used.
	 */
	public static int getKeyLength(final PublicKey pk) {
	    int len = -1;
	    if (pk instanceof RSAPublicKey) {
	        final RSAPublicKey rsapub = (RSAPublicKey) pk;
	        len = rsapub.getModulus().bitLength();
	    } else if (pk instanceof JCEECPublicKey) {
	        final JCEECPublicKey ecpriv = (JCEECPublicKey) pk;
	        final org.bouncycastle.jce.spec.ECParameterSpec spec = ecpriv.getParameters();
	        if (spec != null) {
	            len = spec.getN().bitLength();              
	        } else {
	            // We support the key, but we don't know the key length
	            len = 0;
	        }
	    } else if (pk instanceof ECPublicKey) {
	        final ECPublicKey ecpriv = (ECPublicKey) pk;
	        final java.security.spec.ECParameterSpec spec = ecpriv.getParams();
	        if (spec != null) {
	            len = spec.getOrder().bitLength(); // does this really return something we expect?
	        } else {
	            // We support the key, but we don't know the key length
	            len = 0;
	        }
	    } else if (pk instanceof DSAPublicKey) {
	        final DSAPublicKey dsapub = (DSAPublicKey) pk;
	        if ( dsapub.getParams() != null ) {
	            len = dsapub.getParams().getP().bitLength();
	        } else {
	            len = dsapub.getY().bitLength();
	        }
	    } 
	    return len;
	}
	
	/**
	 * derive the curve name
	 * 
	 * @param ecParameterSpec
	 * @return
	 * @throws GeneralSecurityException
	 */
	public static final String deriveCurveName(org.bouncycastle.jce.spec.ECParameterSpec ecParameterSpec)
			throws GeneralSecurityException {
		for (@SuppressWarnings("rawtypes")
		Enumeration names = ECNamedCurveTable.getNames(); names.hasMoreElements();) {
			final String name = (String) names.nextElement();

			final X9ECParameters params = ECNamedCurveTable.getByName(name);

			if (params.getN().equals(ecParameterSpec.getN()) && params.getH().equals(ecParameterSpec.getH())
					&& params.getCurve().equals(ecParameterSpec.getCurve())
					&& params.getG().equals(ecParameterSpec.getG())) {
				return name;
			}
		}

		throw new GeneralSecurityException("Could not find name for curve");
	}

	public static final String deriveCurveName(PublicKey publicKey) throws GeneralSecurityException{
	    if(publicKey instanceof java.security.interfaces.ECPublicKey){
	        final java.security.interfaces.ECPublicKey pk = (java.security.interfaces.ECPublicKey) publicKey;
	        final ECParameterSpec params = pk.getParams();
	        return deriveCurveName(EC5Util.convertSpec(params, false));
	    } else if(publicKey instanceof org.bouncycastle.jce.interfaces.ECPublicKey){
	        final org.bouncycastle.jce.interfaces.ECPublicKey pk = (org.bouncycastle.jce.interfaces.ECPublicKey) publicKey;
	        return deriveCurveName(pk.getParameters());
	    } else throw new GeneralSecurityException("Can only be used with instances of ECPublicKey (either jce or bc implementation)");
	}

	public static final String deriveCurveName(PrivateKey privateKey) throws GeneralSecurityException{
	    if(privateKey instanceof java.security.interfaces.ECPrivateKey){
	        final java.security.interfaces.ECPrivateKey pk = (java.security.interfaces.ECPrivateKey) privateKey;
	        final ECParameterSpec params = pk.getParams();
	        return deriveCurveName(EC5Util.convertSpec(params, false));
	    } else if(privateKey instanceof org.bouncycastle.jce.interfaces.ECPrivateKey){
	        final org.bouncycastle.jce.interfaces.ECPrivateKey pk = (org.bouncycastle.jce.interfaces.ECPrivateKey) privateKey;
	        return deriveCurveName(pk.getParameters());
	    } else throw new GeneralSecurityException("Can only be used with instances of ECPrivateKey (either jce or bc implementation)");
	}


	
	public void insertNameAttributes(Certificate cert, String attributeName, X500Name x500NameSubject) {
		for( RDN rdn: x500NameSubject.getRDNs() ){
			for( org.bouncycastle.asn1.x500.AttributeTypeAndValue atv: rdn.getTypesAndValues()){
				String value = atv.getValue().toString().toLowerCase();
				addCertAttribute(cert, attributeName, value);
				addCertAttribute(cert, attributeName, atv.getType().getId().toLowerCase() +"="+ value);
			}
		}
	}

	public String getCertAttribute(Certificate certDao, String name) {
		for( CertificateAttribute certAttr:certDao.getCertificateAttributes()) {
			if( certAttr.getName().equals(name)) {
				return certAttr.getValue();
			}
		}
		return null;
	}

	public void addCertAttribute(Certificate certDao, String name, long value) {
		addCertAttribute(certDao, name, Long.toString(value));
	}


	public void dropCertAttribute(Certificate cert, String name) {

		
		Collection<CertificateAttribute> certAttrList = cert.getCertificateAttributes();
		for( CertificateAttribute certAttr : certAttrList) {
			if( certAttr.getName().equals(name)) {
				certificateAttributeRepository.delete(certAttr);
			}
		}

        LOG.debug("attribute " + name + " dropped" );

	}
	
	public void addCertAttribute(Certificate cert, String name, String value) {
	
		Collection<CertificateAttribute> certAttrList = cert.getCertificateAttributes();
		for( CertificateAttribute certAttr : certAttrList) {
			if( certAttr.getName().equals(name) &&
					certAttr.getValue().equals(value)) {
				// attribute already present, no use in duplication here
				return;
			}
		}
		
		CertificateAttribute cAtt = new CertificateAttribute();
		cAtt.setCertificate(cert);
		cAtt.setName(name);
		cAtt.setValue(value);
		
		cert.getCertificateAttributes().add(cAtt);
	}

	
	/**
	 * 
	 * @param serial
	 * @return
	 */
	public static String getPaddedSerial(final String serial){
	
		int len = serial.length();
		if( len >= SERIAL_PADDING_PATTERN.length() ){
			return serial;
		}
		return SERIAL_PADDING_PATTERN.substring(serial.length()) + serial; 
	}
	
    /**
     * Generate a SHA1 fingerprint from a byte array containing a X.509 certificate
     *
     * @param ba Byte array containing DER encoded X509Certificate.
     * @return Byte array containing SHA1 hash of DER encoded certificate.
     */
    public static byte[] generateSHA1Fingerprint(byte[] ba) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            return md.digest(ba);
        } catch (NoSuchAlgorithmException nsae) {
            LOG.error("SHA1 algorithm not supported", nsae);
        }
        return null;
    } // generateSHA1Fingerprint


	/**
	 * convert the usage-bits to a readable string
	 * @param usage
	 * @return descriptive text representing the key usage
	 */
	public static String usageAsString( boolean[] usage ){

		if( ( usage == null ) || ( usage.length == 0 ) ){
			return( "unspecified usage" );
		}

		String desc = "valid for ";
		if ( (usage.length > 0) && usage[0]) desc += "digitalSignature ";
		if ( (usage.length > 1) && usage[1]) desc += "nonRepudiation ";
		if ( (usage.length > 2) && usage[2]) desc += "keyEncipherment ";
		if ( (usage.length > 3) && usage[3]) desc += "dataEncipherment ";
		if ( (usage.length > 4) && usage[4]) desc += "keyAgreement ";
		if ( (usage.length > 5) && usage[5]) desc += "keyCertSign ";
		if ( (usage.length > 6) && usage[6]) desc += "cRLSign ";
		if ( (usage.length > 7) && usage[7]) desc += "encipherOnly ";
		if ( (usage.length > 8) && usage[8]) desc += "decipherOnly ";

		return (desc);
	}

	/**
	 * convert the usage-bits to a readable string
	 * @param usage
	 * @return descriptive text representing the key usage
	 */
	public void usageAsCertAttributes( boolean[] usage, Certificate cert ){

		if( ( usage == null ) || ( usage.length == 0 ) ){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "unspecified" );
			return;
		}

		if ( (usage.length > 0) && usage[0]){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "digitalSignature ");
		}
		if ( (usage.length > 1) && usage[1]){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "nonRepudiation ");
		}
		if ( (usage.length > 2) && usage[2]){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "keyEncipherment ");
		}
		if ( (usage.length > 3) && usage[3]){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "dataEncipherment ");
		}
		if ( (usage.length > 4) && usage[4]){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "keyAgreement ");
		}
		if ( (usage.length > 5) && usage[5]){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "keyCertSign ");
		}
		if ( (usage.length > 6) && usage[6]){
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "cRLSign ");
		}
		if ( (usage.length > 7) && usage[7]) {
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "encipherOnly ");
		}
		if ( (usage.length > 8) && usage[8]) {
			addCertAttribute(cert, CertificateAttribute.ATTRIBUTE_USAGE,  "decipherOnly ");
		}

	}

	/*
	public Certificate findCertificateById(long id) {

		Optional<Certificate> certOpt = certificateRepository.findById(id);
		if( certList.isEmpty()) {
			return null;
		}
		return certList.iterator().next();
	}
*/
	
	
	public Certificate findIssuingCertificate(Certificate cert) throws GeneralSecurityException {
		Certificate issuingCert = cert.getIssuingCertificate();
		if( issuingCert == null){
			issuingCert = findIssuingCertificate(convertPemToCertificateHolder(cert.getContent()));
			if( issuingCert != null){
				cert.setIssuingCertificate(issuingCert);
				certificateRepository.save(cert);
			}else {
				LOG.debug("not able to find and store issuing certificate for '" + cert.getDescription() + "'");
			}
		}
		return issuingCert;
	}

	  /**
	   * 
	   * @param pem
	   * @return
	   * @throws GeneralSecurityException
	   */
	  public X509CertificateHolder convertPemToCertificateHolder (final String pem) throws GeneralSecurityException {
		  
		X509Certificate x509Cert = convertPemToCertificate (pem);
		try {
			return new X509CertificateHolder(x509Cert.getEncoded());
		} catch (IOException e) {
			throw new GeneralSecurityException(e);
		}
		
	  }
	  
		/**
		 * 
		 * @param pem
		 * @return
		 * @throws GeneralSecurityException
		 */
		public X509Certificate convertPemToCertificate(final String pem)
				throws GeneralSecurityException {

			X509Certificate cert = null;
			ByteArrayInputStream pemStream = null;
			try {
				pemStream = new ByteArrayInputStream(pem.getBytes("UTF-8"));
			} catch (UnsupportedEncodingException ex) {
				LOG.error("UnsupportedEncodingException, convertPemToPublicKey",
						ex);
				throw new GeneralSecurityException(
						"Parsing of PublicKey failed due to encoding problem! Not PEM encoded?");
			}

			Reader pemReader = new InputStreamReader(pemStream);
			PEMParser pemParser = new PEMParser(pemReader);

			try {
				Object parsedObj = pemParser.readObject();

				if (parsedObj == null) {
					throw new GeneralSecurityException(
							"Parsing of certificate failed! Not PEM encoded?");
				}

//				LOGGER.debug("PemParser returned: " + parsedObj);

				if (parsedObj instanceof X509CertificateHolder) {
					cert = new JcaX509CertificateConverter().setProvider("BC")
							.getCertificate((X509CertificateHolder) parsedObj);

				} else {
					throw new GeneralSecurityException(
							"Unexpected parsing result: "
									+ parsedObj.getClass().getName());
				}
			} catch (IOException ex) {
				LOG.error("IOException, convertPemToCertificate", ex);
				throw new GeneralSecurityException(
						"Parsing of certificate failed! Not PEM encoded?");
			} finally {
				try {
					pemParser.close();
				} catch (IOException e) {
					// just ignore
					LOG.debug("IOException on close()", e);
				}
			}

			return cert;
		}

		/**
		 * 
		 * @param pem
		 * @return
		 * @throws GeneralSecurityException
		 */
		public PrivateKey convertPemToPrivateKey(final String pem)
				throws GeneralSecurityException {

			PrivateKey privKey = null;
			ByteArrayInputStream pemStream = null;
			try {
				pemStream = new ByteArrayInputStream(pem.getBytes("UTF-8"));
			} catch (UnsupportedEncodingException ex) {
				LOG.error("UnsupportedEncodingException, PrivateKey", ex);
				throw new GeneralSecurityException(
						"Parsing of PEM file failed due to encoding problem! Not PEM encoded?");
			}

			Reader pemReader = new InputStreamReader(pemStream);
			PEMParser pemParser = new PEMParser(pemReader);

			try {
				Object parsedObj = pemParser.readObject();

				if (parsedObj == null) {
					throw new GeneralSecurityException(
							"Parsing of certificate failed! Not PEM encoded?");
				}

//				LOGGER.debug("PemParser returned: " + parsedObj);

				if (parsedObj instanceof PrivateKeyInfo) {
					privKey = new JcaPEMKeyConverter().setProvider("BC")
							.getPrivateKey((PrivateKeyInfo) parsedObj);
				} else {
					throw new GeneralSecurityException(
							"Unexpected parsing result: "
									+ parsedObj.getClass().getName());
				}

			} catch (IOException ex) {
				LOG.error("IOException, convertPemToCertificate", ex);
				throw new GeneralSecurityException(
						"Parsing of certificate failed! Not PEM encoded?");
			} finally {
				try {
					pemParser.close();
				} catch (IOException e) {
					// just ignore
					LOG.debug("IOException on close()", e);
				}
			}

			return privKey;
		}
 
	/**
	 * 
	 * @param x509CertHolder
	 * @return
	 * @throws GeneralSecurityException
	 */
	public Certificate findIssuingCertificate(X509CertificateHolder x509CertHolder) throws GeneralSecurityException {
		
		X509ExtensionUtils x509ExtensionUtils = getX509UtilInstance();
		
		AuthorityKeyIdentifier akiCalculated = x509ExtensionUtils.createAuthorityKeyIdentifier( x509CertHolder.getSubjectPublicKeyInfo());
		List<Certificate> issuingCertList = findCertsByAKI(x509CertHolder, akiCalculated);

		if( issuingCertList.isEmpty()){			
			LOG.debug("calculated AKI not found, trying AKI from crt extension");
		
			if( (x509CertHolder != null) && (x509CertHolder.getExtensions() != null)) {
				AuthorityKeyIdentifier aki = AuthorityKeyIdentifier.fromExtensions(x509CertHolder.getExtensions());
				if( aki != null) {
					issuingCertList = findCertsByAKI(x509CertHolder, aki);
				}
			}

		}

		// no issuing certificate found 
		//  @todo
		// may not be a reason for a GeneralSecurityException
		if( issuingCertList.isEmpty()){			
			throw new GeneralSecurityException("no issuing certificate for '" + x509CertHolder.getSubject().toString() +"' in certificate store.");
		}

		// that's wierd!!
		if( issuingCertList.size() > 1){
			throw new GeneralSecurityException("more than one ("+issuingCertList.size()+") issuing certificate for '" + x509CertHolder.getSubject().toString() +"' in certificate store.");
		}

		Certificate issuerDao = issuingCertList.iterator().next();

		if( LOG.isDebugEnabled()) {
			LOG.debug("issuerDao has attributes: ");
			
			for( CertificateAttribute cad: issuerDao.getCertificateAttributes()){
				LOG.debug("Name '" + cad.getName() +"' got value '" + cad.getValue() + "'");
			}
		}

		return issuerDao;
	}

	/**
	 * @param x509CertHolder
	 * @param aki
	 * @return
	 */
	private List<Certificate> findCertsByAKI(X509CertificateHolder x509CertHolder, AuthorityKeyIdentifier aki) {
		String aKIBase64 = Base64.encodeBase64String(aki.getKeyIdentifier());
		LOG.debug("looking for certificate '" + x509CertHolder.getSubject().toString() +"' having AKI '" + aKIBase64 + "'");
		List<Certificate> issuingCertList = certificateRepository.findByAttributeValue(CertificateAttribute.ATTRIBUTE_SKI, aKIBase64);
		if( issuingCertList.isEmpty()) {
			LOG.debug("no certificate found for AKI {}", aKIBase64);
		}
		return issuingCertList;
	}


	/**
	 * @return
	 * @throws GeneralSecurityException
	 */
	private X509ExtensionUtils getX509UtilInstance() throws GeneralSecurityException {
		DigestCalculator digCalc;
		try {
			digCalc = new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
		} catch (OperatorCreationException e) {
			LOG.warn("Problem instatiating digest calculator for SHA1", e);
			throw new GeneralSecurityException(e.getMessage());
		}
		X509ExtensionUtils x509ExtensionUtils = new X509ExtensionUtils(digCalc);
		return x509ExtensionUtils;
	}

}
