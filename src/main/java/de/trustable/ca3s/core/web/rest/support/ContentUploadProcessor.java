package de.trustable.ca3s.core.web.rest.support;

import de.trustable.ca3s.core.domain.Certificate;
import de.trustable.ca3s.core.domain.*;
import de.trustable.ca3s.core.domain.enumeration.ContentRelationType;
import de.trustable.ca3s.core.domain.enumeration.CsrUsage;
import de.trustable.ca3s.core.domain.enumeration.ProtectedContentType;
import de.trustable.ca3s.core.exception.CAFailureException;
import de.trustable.ca3s.core.repository.CSRRepository;
import de.trustable.ca3s.core.repository.CertificateRepository;
import de.trustable.ca3s.core.repository.PipelineRepository;
import de.trustable.ca3s.core.service.AuditService;
import de.trustable.ca3s.core.service.NotificationService;
import de.trustable.ca3s.core.service.badkeys.BadKeysResult;
import de.trustable.ca3s.core.service.badkeys.BadKeysService;
import de.trustable.ca3s.core.service.dto.*;
import de.trustable.ca3s.core.service.util.*;
import de.trustable.ca3s.core.web.rest.data.*;
import de.trustable.util.CryptoUtil;
import de.trustable.util.Pkcs10RequestHolder;
import org.apache.commons.lang3.ArrayUtils;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.DecoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static de.trustable.ca3s.core.service.util.PipelineUtil.NOTIFY_RA_OFFICER_ON_PENDING;

/**
 * REST controller for processing PKCS10 requests and Certificates.
 */
@RestController
@RequestMapping("/api")
public class ContentUploadProcessor {

	private final Logger LOG = LoggerFactory.getLogger(ContentUploadProcessor.class);

    private final CryptoUtil cryptoUtil;

    private final ProtectedContentUtil protUtil;

    private final CertificateUtil certUtil;

    private final CSRRepository csrRepository;

    private final CertificateRepository certificateRepository;

    private final PipelineRepository pipelineRepository;

    private final PipelineUtil pipelineUtil;

    private final PreferenceUtil preferenceUtil;

    private final CertificateProcessingUtil cpUtil;

    private final NotificationService notificationService;

    private final BadKeysService badKeysService;

    private final AuditService auditService;

    private static final String SIGNATURE_ALG = "SHA256withRSA";
    private static final String EC_SIGNATURE_ALG = "SHA256withECDSA";

	static HashMap<String,ASN1ObjectIdentifier> nameOIDMap = new HashMap<>();
	static HashMap<String,Integer> nameGeneralNameMap = new HashMap<>();
	static {
		nameOIDMap.put("C", BCStyle.C);
		nameOIDMap.put("CN", BCStyle.CN);
		nameOIDMap.put("O", BCStyle.O);
		nameOIDMap.put("OU", BCStyle.OU);
		nameOIDMap.put("L", BCStyle.L);
        nameOIDMap.put("ST", BCStyle.ST);
        nameOIDMap.put("E", BCStyle.E);

        nameGeneralNameMap.put("DNS-NAME", GeneralName.dNSName);
        nameGeneralNameMap.put("DNS", GeneralName.dNSName);
		nameGeneralNameMap.put("IP", GeneralName.iPAddress);
	}

    public ContentUploadProcessor(CryptoUtil cryptoUtil,
                                  ProtectedContentUtil protUtil,
                                  CertificateUtil certUtil,
                                  CSRRepository csrRepository,
                                  CertificateRepository certificateRepository,
                                  PipelineRepository pipelineRepository,
                                  PipelineUtil pipelineUtil,
                                  PreferenceUtil preferenceUtil,
                                  CertificateProcessingUtil cpUtil,
                                  NotificationService notificationService,
                                  BadKeysService badKeysService,
                                  AuditService auditService) {
        this.cryptoUtil = cryptoUtil;
        this.protUtil = protUtil;
        this.certUtil = certUtil;
        this.csrRepository = csrRepository;
        this.certificateRepository = certificateRepository;
        this.pipelineRepository = pipelineRepository;
        this.pipelineUtil = pipelineUtil;
        this.preferenceUtil = preferenceUtil;
        this.cpUtil = cpUtil;
        this.notificationService = notificationService;
        this.badKeysService = badKeysService;
        this.auditService = auditService;
    }

    /**
     * {@code POST  /csrContent} : Process a PKCSXX-object encoded as PEM.
     *
     * @param uploaded a structure holding some crypto-related content, e.g. CSR, certificate, P12 container
     * @return the {@link ResponseEntity} .
     */
    @PostMapping("/uploadContent")
	@Transactional
    public ResponseEntity<PkcsXXData> uploadContent(@Valid @RequestBody UploadPrecheckData uploaded) {

    	Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    	String requestorName = auth.getName();

    	CreationMode creationMode = uploaded.getCreationMode();
    	if( CreationMode.COMMANDLINE_TOOL.equals(creationMode)) {
			LOG.debug("not supported creation mode {}, requested by user '{}' ", creationMode, requestorName);
    	}else if( CreationMode.SERVERSIDE_KEY_CREATION.equals(creationMode)) {

            Preferences prefs = preferenceUtil.getSystemPrefs();
            if( prefs.isServerSideKeyCreationAllowed()){
                return buildServerSideKeyAndRequest(uploaded, requestorName);
            }else{
                LOG.warn("creating serverside csr not allowed! Requested by user '{}'", requestorName);
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

        }else if( CreationMode.CSR_AVAILABLE.equals(creationMode)) {
            return buildCertificateFromCSR(uploaded, requestorName);
    	}

		return new ResponseEntity<>(HttpStatus.BAD_REQUEST);

	}

	private ResponseEntity<PkcsXXData> buildCertificateFromCSR(UploadPrecheckData uploaded, String requestorName){

        String content = uploaded.getContent();
        LOG.debug("Request to upload a PEM clob : {} by user {}", content, requestorName);

        PkcsXXData p10ReqData = new PkcsXXData();
        try {
            // try to read a DER encoded, non-PEM certificate and convert it to PEM
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(Base64.decode(content)));
                content = cryptoUtil.x509CertToPem(cert);
                LOG.debug("certificate parsed from base64 (non-pem) content");
            } catch (GeneralSecurityException | IOException | DecoderException gse) {
                LOG.debug("certificate parsing from base64 (non-pem) content failed: " + gse.getMessage());
            }

            // try to read the content as a PEM certificate
            X509CertificateHolder certHolder = cryptoUtil.convertPemToCertificateHolder(content);
            List<Certificate> certList = findCertificateByIssuerSerial(certHolder);
            if(!certList.isEmpty()){
                // certificate already present in db
                LOG.info("certificate already present");
                return new ResponseEntity<>(HttpStatus.CONFLICT);
            }

            // insert or read a certificate and return Certificate object
            Certificate cert = insertCertificate(content, requestorName);

            // certificate inserted into the db
            p10ReqData = new PkcsXXData(certHolder, content, true );
            certUtil.setCertAttribute(cert, CsrAttribute.ATTRIBUTE_REQUESTED_BY, requestorName);

            return new ResponseEntity<>(p10ReqData, HttpStatus.CREATED);

        } catch (DecoderException de){
            // not parseable ...
            p10ReqData.setDataType(PKCSDataType.UNKNOWN);
            LOG.debug("certificate parsing problem of uploaded content:" + de.getMessage());
        } catch (GeneralSecurityException | IOException e) {

            LOG.debug("not a certificate, trying to parse it as CSR ");

            try {

                Pkcs10RequestHolder p10ReqHolder = cryptoUtil.parseCertificateRequest(cryptoUtil.convertPemToPKCS10CertificationRequest(content));

                List<CSR> csrList = csrRepository.findNonRejectedByPublicKeyHash(p10ReqHolder.getPublicKeyHash());
                LOG.debug("public key with hash '{}' already used in #{} CSRs.", p10ReqHolder.getPublicKeyHash(), csrList.size());

                Pkcs10RequestHolderShallow p10ReqHolderShallow = new Pkcs10RequestHolderShallow( p10ReqHolder);
                p10ReqData = new PkcsXXData(p10ReqHolderShallow);

                if( badKeysService.isInstalled()){
                    BadKeysResult badKeysResult = badKeysService.checkCSR(content);
                    if( !badKeysResult.isValid()){

                        LOG.debug("badKeysResult '{}'", badKeysResult.getResponse().getResults().getResultType());
                        String [] messages = ArrayUtils.add( p10ReqData.getWarnings(),
                            badKeysResult.getResponse().getResults().getResultType());
                        p10ReqData.setWarnings(messages);
                    }else{
                        LOG.debug("BadKeys not installed");
                    }
                }

                p10ReqData.setCsrPublicKeyPresentInDB(!csrList.isEmpty());
                if(csrList.isEmpty()) {

                    Optional<Pipeline> optPipeline = pipelineRepository.findById(uploaded.getPipelineId());
                    if( optPipeline.isPresent()) {
                        List<String> messageList = new ArrayList<>();
                        if (pipelineUtil.isPipelineRestrictionsResolved(optPipeline.get(), p10ReqHolder, uploaded.getArAttributes(), messageList)) {
                            LOG.debug("pipeline restrictions for pipeline '{}' solved", optPipeline.get().getName());
                        }else {
                            p10ReqData.setWarnings(messageList.toArray(new String[0]));
                            return new ResponseEntity<>(p10ReqData, HttpStatus.BAD_REQUEST);
                        }
                    }else{
                        LOG.info("pipeline id '{}' not found", uploaded.getPipelineId());
                    }

                    CSR csr;
                    try{
                        csr = startCertificateCreationProcess(content, p10ReqData, requestorName, uploaded.getRequestorcomment(), uploaded.getArAttributes(), optPipeline );
                    }catch (CAFailureException caFailureException) {
                        LOG.info("problem creating certificate", caFailureException);
                        String [] messages = ArrayUtils.add( p10ReqData.getWarnings(), caFailureException.getMessage() );
                        p10ReqData.setWarnings(messages);
                        return new ResponseEntity<>(p10ReqData, HttpStatus.OK);
                    }

                    if( csr != null ){
                        Certificate cert = csr.getCertificate();
                        if( cert != null) {

                            // return the id of the freshly created certificate
                            X509CertificateHolder certHolder = cryptoUtil.convertPemToCertificateHolder(cert.getContent());
                            p10ReqData = new PkcsXXData(certHolder, cert);

                            return new ResponseEntity<>(p10ReqData, HttpStatus.CREATED);
                        }
                    }
                }

            } catch (IOException | GeneralSecurityException e2) {
                LOG.debug("describeCSR : " + e2.getMessage());
                LOG.debug("not a certificate, not a CSR, trying to parse it as a P12 container");
                try {

                    KeyStore pkcs12Store = KeyStore.getInstance("PKCS12", "BC");

                    ByteArrayInputStream bais = new ByteArrayInputStream( Base64.decode(content));

                    char[] passphrase = new char[0];
                    if( ( uploaded.getPassphrase() != null ) && (uploaded.getPassphrase().trim().length() > 0)) {
                        passphrase = uploaded.getPassphrase().toCharArray();
                    }

                    pkcs12Store.load(bais, passphrase);
                    LOG.debug("keystore loaded successfully!");

                    List<X509CertificateHolderShallow> certList = new ArrayList<>();

                    for (Enumeration<String> en = pkcs12Store.aliases(); en.hasMoreElements();)
                    {
                        String alias = en.nextElement();
                        LOG.debug("iterating keystore, found alias {}, isCertificateEntry {}, isKeyEntry {}", alias, pkcs12Store.isCertificateEntry(alias), pkcs12Store.isKeyEntry(alias));

                        if (pkcs12Store.isCertificateEntry(alias) || pkcs12Store.isKeyEntry(alias)){

                            X509Certificate x509cert = (X509Certificate)pkcs12Store.getCertificate(alias);
                            if( x509cert == null) {
                                LOG.debug("alias {} does NOT refer to a certificate entry", alias);
                                continue;
                            }
                            LOG.debug("certificate {} found in PKCS12 for alias '{}'", x509cert.getSubjectX500Principal().getName(), alias);

                            String b64Content = cryptoUtil.x509CertToPem(x509cert);
                            X509CertificateHolder certHolder = cryptoUtil.convertPemToCertificateHolder(b64Content);
                            X509CertificateHolderShallow x509Holder = new X509CertificateHolderShallow(certHolder);
                            x509Holder.setPemCertificate(b64Content);

                            Certificate cert;
                            List<Certificate> certListDB = findCertificateByIssuerSerial(certHolder);
                            LOG.debug("certListDB has # {} item", certListDB.size());
                            if(!certListDB.isEmpty()){
                                cert = certListDB.get(0);
                                if( certListDB.size() > 1 ) {
                                    LOG.info("problem: found more than one matching certificate for issuer {}, serial {}", certHolder.getIssuer().toString(), certHolder.getSerialNumber().toString());
                                }
                            }else {
                                // insert certificate
                                cert = insertCertificate(b64Content, requestorName);
                            }
                            x509Holder.setCertificateId(cert.getId());
                            x509Holder.setCertificatePresentInDB(true);

                            if (pkcs12Store.isKeyEntry(alias)){

                                Key key = pkcs12Store.getKey(alias, passphrase);
                                LOG.debug("key {} found alongside certificate in PKCS12 for alias {}", "*****", alias);

                                KeyPair keyPair = new KeyPair(x509cert.getPublicKey(), (PrivateKey) key);
                                certUtil.storePrivateKey(cert, keyPair, cert.getValidTo());
                                x509Holder.setKeyPresent(true);
                                LOG.debug("key {} stored for certificate {}", "*****", cert.getId());

                            }
                            certList.add(x509Holder);
                        }
                    }

                    p10ReqData = new PkcsXXData();
                    X509CertificateHolderShallow[] chsArr = new X509CertificateHolderShallow[certList.size()];
                    certList.toArray(chsArr);
                    p10ReqData.setCertsHolder(chsArr);

                    p10ReqData.setDataType(PKCSDataType.CONTAINER);

                } catch( IOException ioe) {
                    // not able to process, presumable passphrase required ...
                    p10ReqData.setPassphraseRequired(true);
                    p10ReqData.setDataType(PKCSDataType.CONTAINER_REQUIRING_PASSPHRASE);
                    LOG.debug("p12 missing a passphrase: " + ioe.getMessage());
                } catch (org.bouncycastle.util.encoders.DecoderException de){
                    // no parseable ...
                    p10ReqData.setDataType(PKCSDataType.UNKNOWN);
                    LOG.debug("p12 parsing problem of uploaded content: " + de.getMessage());
                }catch(GeneralSecurityException e3) {
                    LOG.debug("general problem with uploaded content: " + e3.getMessage());
                    return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                }
            }
        }
        return new ResponseEntity<>(p10ReqData, HttpStatus.OK);

    }

    private ResponseEntity<PkcsXXData> buildServerSideKeyAndRequest(UploadPrecheckData uploaded, String requestorName) {

        try{
            Optional<Pipeline> optPipeline = pipelineRepository.findById(uploaded.getPipelineId());

            KeyAlgoLength keyAlgoLength = uploaded.getKeyAlgoLength();
            KeyPair keypair = generateKeyPair(keyAlgoLength);

            NamedValues[] certAttr = uploaded.getCertificateAttributes();

            X500NameBuilder namebuilder = new X500NameBuilder(X500Name.getDefaultStyle());
            List<GeneralName> gnList = new ArrayList<>();

            for(NamedValues nv: certAttr) {

                String name = nv.getName();
                if( nameOIDMap.containsKey(name)) {
                    ASN1ObjectIdentifier oid = nameOIDMap.get(name);
                    for( TypedValue typedValue: nv.getValues()) {
                        if( typedValue.getValue() != null && !typedValue.getValue().isEmpty()) {
                            namebuilder.addRDN(oid, typedValue.getValue());
                        }
                    }
                }else if( "SAN".equalsIgnoreCase(name)){

                    for( TypedValue typedValue: nv.getValues()) {
                        String content = typedValue.getValue().trim();
                        if( content.isEmpty()) {
                            continue;
                        }

                        Integer sanType = GeneralName.dNSName;
                        if(nameGeneralNameMap.containsKey(typedValue.getType().toUpperCase() )) {
                            sanType = nameGeneralNameMap.get(typedValue.getType().toUpperCase());
                        }else {
                            LOG.warn("SAN certificate attribute has unknown type '{}'", typedValue.getType());
                        }
                        gnList.add(new GeneralName(sanType, content));
                    }

                }else {
                    LOG.warn("certificate attribute '{}' unknown ", name);
                }
            }

            PKCS10CertificationRequestBuilder p10Builder =
                    new JcaPKCS10CertificationRequestBuilder(namebuilder.build(), keypair.getPublic());

            if( !gnList.isEmpty()) {
                GeneralName[] gns = new GeneralName[gnList.size()];
                gnList.toArray(gns);
                GeneralNames subjectAltName = new GeneralNames(gns);
                ExtensionsGenerator extensionsGenerator = new ExtensionsGenerator();
                extensionsGenerator.addExtension(Extension.subjectAlternativeName, false, subjectAltName);

                if(optPipeline.isPresent()){
                    Pipeline p = optPipeline.get();
                    PipelineView pv = pipelineUtil.from(p);
                    if(CsrUsage.TLS_SERVER.equals(pv.getCsrUsage())) {
                        extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
                        extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                    } else if(CsrUsage.TLS_CLIENT.equals(pv.getCsrUsage())){
                        extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                        extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
                    } else if(CsrUsage.DOC_SIGNING.equals(pv.getCsrUsage())){
                        extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
                    } else if(CsrUsage.CODE_SIGNING.equals(pv.getCsrUsage())){
                        extensionsGenerator.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
                        extensionsGenerator.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_codeSigning));
                    }else{
                        LOG.warn("unexpected CsrUsage found '{}'", pv.getCsrUsage());
                    }
                }
                p10Builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensionsGenerator.generate());
            }


            PrivateKey pk = keypair.getPrivate();
            JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder(pk instanceof ECKey ? EC_SIGNATURE_ALG : SIGNATURE_ALG);
            ContentSigner signer = csBuilder.build(pk);

            PKCS10CertificationRequest p10CR = p10Builder.build(signer);
            String csrAsPem = CryptoUtil.pkcs10RequestToPem(p10CR);
            LOG.debug("created csr on behalf of user '{}':\n{}", requestorName, csrAsPem);

            Pkcs10RequestHolder p10ReqHolder = cryptoUtil.parseCertificateRequest(p10CR);

            Pkcs10RequestHolderShallow p10ReqHolderShallow = new Pkcs10RequestHolderShallow( p10ReqHolder);
            PkcsXXData p10ReqData = new PkcsXXData(p10ReqHolderShallow);

            CSR csr;
            try{
                csr = startCertificateCreationProcess(csrAsPem, p10ReqData, requestorName, uploaded.getRequestorcomment(), uploaded.getArAttributes(), optPipeline );
            }catch (CAFailureException caFailureException) {
                LOG.info("problem creating certificate", caFailureException);
                String [] messages = ArrayUtils.add( p10ReqData.getWarnings(), caFailureException.getMessage() );
                p10ReqData.setWarnings(messages);
                return new ResponseEntity<>(p10ReqData, HttpStatus.OK);
            }

            if( csr != null ){
                csr.setServersideKeyGeneration(true);
                csrRepository.save(csr);

                Instant validTo = Instant.now().plus(30, ChronoUnit.DAYS);

                certUtil.storePrivateKey(csr, keypair, validTo);
                protUtil.createProtectedContent(uploaded.getSecret(),
                    ProtectedContentType.PASSWORD,
                    ContentRelationType.CSR,
                    csr.getId(),
                    -1,
                    validTo);

                Certificate cert = csr.getCertificate();
                if( cert != null) {
                    // return the id of the freshly created certificate
                    X509CertificateHolder certHolder = cryptoUtil.convertPemToCertificateHolder(cert.getContent());
                    p10ReqData = new PkcsXXData(certHolder, cert);
                }
                return new ResponseEntity<>(p10ReqData, HttpStatus.CREATED);
            }
            LOG.warn("problem creating serverside csr object from CSR PEM: \n{}", csrAsPem );
            return new ResponseEntity<>(p10ReqData, HttpStatus.OK);

        }catch(IOException | OperatorCreationException | GeneralSecurityException ex) {
            LOG.warn("problem creating serverside csr: " + ex.getMessage());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }


    private KeyPair generateKeyPair(KeyAlgoLength keyAlgoLength) throws NoSuchAlgorithmException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(keyAlgoLength.algoName());
        kpg.initialize(keyAlgoLength.keyLength());
		return kpg.generateKeyPair();
	}


	private Certificate insertCertificate(String content, String requestorName)
			throws GeneralSecurityException, IOException {
		// insert certificate
		Certificate cert = certUtil.createCertificate(content, null, null, false);
        auditService.saveAuditTrace(auditService.createAuditTraceCertificate(AuditService.AUDIT_MANUAL_CERTIFICATE_IMPORTED, cert));

		// save the source of the certificate
		certUtil.setCertAttribute(cert, CertificateAttribute.ATTRIBUTE_UPLOADED_BY, requestorName);
		certificateRepository.save(cert);

		LOG.info("created new certificate entry with id {} uploaded by {}", cert.getId(), requestorName);

		return cert;
	}



	private CSR startCertificateCreationProcess(final String csrAsPem, PkcsXXData p10ReqData, final String requestorName, String requestorComment, NamedValues[] nvArr, Optional<Pipeline> optPipeline )  {

		if( optPipeline.isPresent()) {

			Pipeline pipeline = optPipeline.get();
            if( pipeline.isActive()) {
                List<String> messageList = new ArrayList<>();

                CSR csr = cpUtil.buildCSR(csrAsPem, requestorName, AuditService.AUDIT_WEB_CERTIFICATE_REQUESTED, requestorComment, pipeline, nvArr, messageList);

                p10ReqData.setWarnings(messageList.toArray(new String[0]));

                if (csr != null) {
                    if (pipeline.isApprovalRequired()) {
                        LOG.debug("deferring certificate creation for csr #{}", csr.getId());
                        p10ReqData.setCsrPending(true);
                        p10ReqData.setCreatedCSRId(csr.getId().toString());

                        if( "TRUE".equalsIgnoreCase(pipelineUtil.getPipelineAttribute(pipeline,NOTIFY_RA_OFFICER_ON_PENDING, "" + preferenceUtil.isNotifyRAOnRequest()))) {
                            notificationService.notifyRAOfficerOnRequest(csr);
                        }

                    } else {
                        auditService.saveAuditTrace(auditService.createAuditTraceWebAutoAccepted(csr));
                        cpUtil.processCertificateRequest(csr, requestorName, AuditService.AUDIT_WEB_CERTIFICATE_CREATED, pipeline);
                    }
                    return csr;
                } else {
                    LOG.warn("startCertificateCreationProcess: creation of CSR failed");
                }
            } else {
                LOG.warn("startCertificateCreationProcess: pipeline {} not active", pipeline.getName());
            }
		}else {
            LOG.warn("startCertificateCreationProcess: no processing pipeline defined");
		}
		return null;
	}

	private List<Certificate> findCertificateByIssuerSerial(X509CertificateHolder certHolder) {
		return certificateRepository.findByIssuerSerial(certHolder.getIssuer().toString(), certHolder.getSerialNumber().toString());

	}

}
