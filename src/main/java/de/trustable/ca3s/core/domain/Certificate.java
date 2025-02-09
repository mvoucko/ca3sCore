package de.trustable.ca3s.core.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A Certificate.
 */
@Entity
@Table(name = "certificate")
@NamedQueries({
	@NamedQuery(name = "Certificate.findByTBSDigest",
	query = "SELECT c FROM Certificate c WHERE " +
			"c.tbsDigest = :tbsDigest"
    ),
    @NamedQuery(name = "Certificate.findByIssuerSerial",
    query = "SELECT c FROM Certificate c WHERE " +
            "LOWER(c.issuer) = LOWER( :issuer ) AND " +
            " c.serial = :serial"
    ),
    @NamedQuery(name = "Certificate.findCACertByIssuer",
    query = "SELECT distinct c FROM Certificate c JOIN c.certificateAttributes att1 WHERE " +
    	"c.subject = :issuer AND " +
        " att1.name = 'CA3S:CA' and LOWER(att1.value) = 'true' "
    ),
    @NamedQuery(name = "Certificate.findBySearchTermNamed",
    query = "SELECT c FROM Certificate c WHERE " +
        "LOWER(c.subject) LIKE LOWER(CONCAT('%', :subject, '%')) OR " +
        " c.serial = :serial"
    ),
    @NamedQuery(name = "Certificate.findByAttributeValue",
        query = "SELECT distinct c FROM Certificate c JOIN c.certificateAttributes att1 WHERE " +
            " att1.name = :name and att1.value = :value "
    ),
    @NamedQuery(name = "Certificate.findByAttributeValueLowerThan",
        query = "SELECT distinct c FROM Certificate c JOIN c.certificateAttributes att1 WHERE " +
            " att1.name = :name and att1.value < :value "
    ),
    @NamedQuery(name = "Certificate.findByAttributeValue_",
    query = "SELECT distinct c FROM Certificate c JOIN c.certificateAttributes att1 WHERE " +
            "att1.name = :name and " +
            "att1.value LIKE LOWER(CONCAT( :value, '%')" +
        " )"
    ),
    @NamedQuery(name = "Certificate.findBySearchTermNamed1",
    query = "SELECT distinct c FROM Certificate c JOIN c.certificateAttributes att1 WHERE " +
        " att1.name = :name and att1.value like CONCAT( :value, '%')"
        ),
    @NamedQuery(name = "Certificate.findBySearchTermNamed2",
    query = "SELECT distinct c FROM Certificate c JOIN c.certificateAttributes att1 JOIN c.certificateAttributes att2  WHERE " +
        " att1.name = :name1 and att1.value like CONCAT( :value1, '%') AND" +
        " att2.name = :name2 and att2.value like CONCAT( :value2, '%') "
        ),

    @NamedQuery(name = "Certificate.findByTermNamed2",
    query = "SELECT distinct c FROM Certificate c JOIN c.certificateAttributes att1 JOIN c.certificateAttributes att2  WHERE " +
        " att1.name = :name1 and att1.value = :value1 AND" +
        " att2.name = :name2 and att2.value = :value2 "
        ),
    @NamedQuery(name = "Certificate.findByValidTo",
    query = "SELECT c FROM Certificate c WHERE " +
        " c.validTo >= :after and " +
        " c.validTo <= :before and " +
        " c.revoked = FALSE " +
        " order by c.validTo asc"
    ),
    @NamedQuery(name = "Certificate.findByValidToGroupedByDay",
        query = "SELECT concat(YEAR(c.validTo), '.', MONTH(c.validTo), '.', DAY(c.validTo)), count(c) FROM Certificate c WHERE " +
            "c.validTo >= :after and " +
            " c.validTo <= :before " +
            " group by YEAR(c.validTo), MONTH(c.validTo), DAY(c.validTo)"
    ),
    @NamedQuery(name = "Certificate.countAll",
    query = "SELECT count(c) FROM Certificate c "
    ),
    @NamedQuery(name = "Certificate.findActiveCertificatesByHashAlgo",
    query = "SELECT c.hashingAlgorithm, count(c) as total FROM Certificate c WHERE " +
        "c.validTo >= :now and " +
        " c.validFrom <= :now and " +
        " c.revoked = FALSE " +
        " GROUP BY c.hashingAlgorithm ORDER BY c.hashingAlgorithm ASC"
    ),

    @NamedQuery(name = "Certificate.findActiveCertificatesByKeyAlgo",
    query = "SELECT c.keyAlgorithm, count(c) as total FROM Certificate c WHERE " +
        "c.validTo >= :now and " +
        " c.validFrom <= :now and " +
        " c.revoked = FALSE " +
        " GROUP BY c.keyAlgorithm ORDER BY c.keyAlgorithm ASC"
    ),

    @NamedQuery(name = "Certificate.findActiveCertificatesByKeyLength",
    query = "SELECT c.keyLength, count(c) as total FROM Certificate c WHERE " +
        "c.validTo >= :now and " +
        " c.validFrom <= :now and " +
        " c.revoked = FALSE " +
        " GROUP BY c.keyLength ORDER BY c.keyLength ASC"
    ),

    @NamedQuery(name = "Certificate.findActiveCertificatesBySANs",
    query = "SELECT c as total FROM Certificate c " +
    	" JOIN c.certificateAttributes certAtt " +
    	" WHERE " +
        " c.validTo >= :now and " +
        " c.validFrom <= :now and " +
        " c.revoked = FALSE and " +
        " ( certAtt.name = 'TYPED_SAN' or certAtt.name = 'TYPED_VSAN') and " +
        " certAtt.value in :sans " +
        " group by c " +
        " order by count(certAtt) desc"
    ),

    @NamedQuery(name = "Certificate.findInactiveCertificatesByValidFrom",
    query = "SELECT c FROM Certificate c WHERE " +
        "c.validFrom >= :now and " +
        "c.validTo < :now and " +
        "c.revoked = FALSE and " +
        "c.active = FALSE "
    ),
    @NamedQuery(name = "Certificate.findActiveCertificatesByValidTo",
        query = "SELECT c FROM Certificate c WHERE " +
            "c.validTo <= :now and " +
            "c.active = TRUE "
    ),
    @NamedQuery(name = "Certificate.findActiveTLSCertificate",
        query = "SELECT distinct c, certAtt.value FROM Certificate c JOIN c.certificateAttributes certAtt WHERE " +
            " certAtt.name = 'TLS_KEY' and " +
            " c.active = TRUE " +
            " order by c.validTo desc"
    ),
    @NamedQuery(name = "Certificate.findActiveCertificateOrderedByCrlURL",
        query = "SELECT distinct c, certAtt.value FROM Certificate c JOIN c.certificateAttributes certAtt WHERE " +
            " certAtt.name = 'CRL_URL' and " +
            " c.active = TRUE and" +
            " certAtt.value not like 'ldap%'" +
            " order by certAtt.value "
    ),
    @NamedQuery(name = "Certificate.findActiveCertificateBySerial",
        query = "SELECT distinct c FROM Certificate c WHERE " +
            " c.serial = :serial and " +
            " c.active = TRUE "
    ),
    @NamedQuery(name = "Certificate.findCrlURLForActiveCertificates",
        query = "SELECT distinct certAtt.value FROM Certificate c JOIN c.certificateAttributes certAtt WHERE " +
            " certAtt.name = 'CRL_URL' and " +
            " c.active = TRUE " +
            " order by certAtt.value "
    ),
    @NamedQuery(name = "Certificate.findTimestampNotExistForCA",
    query = "SELECT c  FROM Certificate c JOIN c.certificateAttributes att1 WHERE " +
        " att1.name = '" +CertificateAttribute.ATTRIBUTE_PROCESSING_CA+"' and att1.value = :caName AND" +
        " NOT EXISTS (select att2 from CertificateAttribute att2 WHERE att2.certificate = c AND " +
        " att2.name = :timestamp )"
    ),
    @NamedQuery(name = "Certificate.findMaxTimestampForCA",
        query = "SELECT max(att2.value)  FROM Certificate c JOIN c.certificateAttributes att1 JOIN c.certificateAttributes att2  WHERE " +
            " att1.name = '" +CertificateAttribute.ATTRIBUTE_PROCESSING_CA+"' and att1.value = :caName AND" +
            " att2.name = :timestamp"
    ),
    @NamedQuery(name = "Certificate.findByRequestor",
        query = "SELECT c  FROM Certificate c JOIN c.certificateAttributes att1 WHERE " +
            " att1.name = '" +CertificateAttribute.ATTRIBUTE_REQUESTED_BY+"' and att1.value = :requestor"
    ),

})
public class Certificate implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "tbs_digest", nullable = false)
    private String tbsDigest;

    @NotNull
    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "sans")
    private String sans;

    @NotNull
    @Column(name = "issuer", nullable = false)
    private String issuer;

    @Column(name = "root")
    private String root;

    @NotNull
    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "description")
    private String description;

    @Column(name = "fingerprint")
    private String fingerprint;

    @NotNull
    @Column(name = "serial", nullable = false)
    private String serial;

    @NotNull
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @NotNull
    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Column(name = "key_algorithm")
    private String keyAlgorithm;

    @Column(name = "key_length")
    private Integer keyLength;

    @Column(name = "curve_name")
    private String curveName;

    @Column(name = "hashing_algorithm")
    private String hashingAlgorithm;

    @Column(name = "padding_algorithm")
    private String paddingAlgorithm;

    @Column(name = "signing_algorithm")
    private String signingAlgorithm;

    @Column(name = "creation_execution_id")
    private String creationExecutionId;

    @Column(name = "content_added_at")
    private Instant contentAddedAt;

    @Column(name = "revoked_since")
    private Instant revokedSince;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Column(name = "revoked")
    private Boolean revoked;

    @Column(name = "revocation_execution_id")
    private String revocationExecutionId;

    @Lob
    @Column(name = "administration_comment")
    private String administrationComment;

    @Column(name = "end_entity")
    private Boolean endEntity;

    @Column(name = "selfsigned")
    private Boolean selfsigned;

    @Column(name = "trusted")
    private Boolean trusted;

    @Column(name = "active")
    private Boolean active;


    @Lob
    @Column(name = "content", nullable = false)
    private String content;

    @OneToOne
    @JoinColumn(unique = true)
    private CSR csr;

    @JsonIgnoreProperties(value = { "certificate" }, allowSetters = true)
    @OneToOne
    @JoinColumn(unique = true)
    private CertificateComment comment;

    @OneToMany(fetch = FetchType.EAGER, mappedBy = "certificate", cascade = {CascadeType.ALL})
    private Set<CertificateAttribute> certificateAttributes = new HashSet<>();

    @ManyToOne
    @JsonIgnoreProperties({"rootCertificate", "issuingCertificate", "certificateAttributes"})
    private Certificate issuingCertificate;

    @ManyToOne
    @JsonIgnoreProperties({"rootCertificate", "issuingCertificate", "certificateAttributes"})
    private Certificate rootCertificate;

    @ManyToOne
    @JsonIgnoreProperties("certificates")
    private CAConnectorConfig revocationCA;

    // jhipster-needle-entity-add-field - JHipster will add fields here, do not remove
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTbsDigest() {
        return tbsDigest;
    }

    public Certificate tbsDigest(String tbsDigest) {
        this.tbsDigest = tbsDigest;
        return this;
    }

    public void setTbsDigest(String tbsDigest) {
        this.tbsDigest = tbsDigest;
    }

    public String getSubject() {
        return subject;
    }

    public Certificate subject(String subject) {
        this.subject = subject;
        return this;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSans() {
        return sans;
    }

    public Certificate sans(String sans) {
        this.sans = sans;
        return this;
    }

    public void setSans(String sans) {
        this.sans = sans;
    }

    public String getIssuer() {
        return issuer;
    }

    public Certificate issuer(String issuer) {
        this.issuer = issuer;
        return this;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getRoot() {
        return root;
    }

    public Certificate root(String root) {
        this.root = root;
        return this;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public String getType() {
        return type;
    }

    public Certificate type(String type) {
        this.type = type;
        return this;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public Certificate description(String description) {
        this.description = description;
        return this;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Certificate fingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
        return this;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSerial() {
        return serial;
    }

    public Certificate serial(String serial) {
        this.serial = serial;
        return this;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Certificate validFrom(Instant validFrom) {
        this.validFrom = validFrom;
        return this;
    }

    public void setValidFrom(Instant validFrom) {
        this.validFrom = validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public Certificate validTo(Instant validTo) {
        this.validTo = validTo;
        return this;
    }

    public void setValidTo(Instant validTo) {
        this.validTo = validTo;
    }

    public String getKeyAlgorithm() {
        return keyAlgorithm;
    }

    public Certificate keyAlgorithm(String keyAlgorithm) {
        this.keyAlgorithm = keyAlgorithm;
        return this;
    }

    public void setKeyAlgorithm(String keyAlgorithm) {
        this.keyAlgorithm = keyAlgorithm;
    }

    public Integer getKeyLength() {
        return keyLength;
    }

    public Certificate keyLength(Integer keyLength) {
        this.keyLength = keyLength;
        return this;
    }

    public void setKeyLength(Integer keyLength) {
        this.keyLength = keyLength;
    }

    public String getCurveName() {
        return curveName;
    }

    public Certificate curveName(String curveName) {
        this.curveName = curveName;
        return this;
    }

    public void setCurveName(String curveName) {
        this.curveName = curveName;
    }

    public String getHashingAlgorithm() {
        return hashingAlgorithm;
    }

    public Certificate hashingAlgorithm(String hashingAlgorithm) {
        this.hashingAlgorithm = hashingAlgorithm;
        return this;
    }

    public void setHashingAlgorithm(String hashingAlgorithm) {
        this.hashingAlgorithm = hashingAlgorithm;
    }

    public String getPaddingAlgorithm() {
        return paddingAlgorithm;
    }

    public Certificate paddingAlgorithm(String paddingAlgorithm) {
        this.paddingAlgorithm = paddingAlgorithm;
        return this;
    }

    public void setPaddingAlgorithm(String paddingAlgorithm) {
        this.paddingAlgorithm = paddingAlgorithm;
    }

    public String getSigningAlgorithm() {
        return signingAlgorithm;
    }

    public Certificate signingAlgorithm(String signingAlgorithm) {
        this.signingAlgorithm = signingAlgorithm;
        return this;
    }

    public void setSigningAlgorithm(String signingAlgorithm) {
        this.signingAlgorithm = signingAlgorithm;
    }

    public String getCreationExecutionId() {
        return creationExecutionId;
    }

    public Certificate creationExecutionId(String creationExecutionId) {
        this.creationExecutionId = creationExecutionId;
        return this;
    }

    public void setCreationExecutionId(String creationExecutionId) {
        this.creationExecutionId = creationExecutionId;
    }

    public Instant getContentAddedAt() {
        return contentAddedAt;
    }

    public Certificate contentAddedAt(Instant contentAddedAt) {
        this.contentAddedAt = contentAddedAt;
        return this;
    }

    public void setContentAddedAt(Instant contentAddedAt) {
        this.contentAddedAt = contentAddedAt;
    }

    public Instant getRevokedSince() {
        return revokedSince;
    }

    public Certificate revokedSince(Instant revokedSince) {
        this.revokedSince = revokedSince;
        return this;
    }

    public void setRevokedSince(Instant revokedSince) {
        this.revokedSince = revokedSince;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public Certificate revocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
        return this;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }

    public Boolean isRevoked() {
        return revoked;
    }

    public Certificate revoked(Boolean revoked) {
        this.revoked = revoked;
        return this;
    }

    public void setRevoked(Boolean revoked) {
        this.revoked = revoked;
    }

    public String getRevocationExecutionId() {
        return revocationExecutionId;
    }

    public Certificate revocationExecutionId(String revocationExecutionId) {
        this.revocationExecutionId = revocationExecutionId;
        return this;
    }

    public void setRevocationExecutionId(String revocationExecutionId) {
        this.revocationExecutionId = revocationExecutionId;
    }

    public String getAdministrationComment() {
        return administrationComment;
    }

    public Certificate administrationComment(String administrationComment) {
        this.administrationComment = administrationComment;
        return this;
    }

    public void setAdministrationComment(String administrationComment) {
        this.administrationComment = administrationComment;
    }

    public Boolean isEndEntity() {
        return endEntity;
    }

    public Certificate endEntity(Boolean endEntity) {
        this.endEntity = endEntity;
        return this;
    }

    public void setEndEntity(Boolean endEntity) {
        this.endEntity = endEntity;
    }

    public Boolean isSelfsigned() {
        return selfsigned;
    }

    public Certificate selfsigned(Boolean selfsigned) {
        this.selfsigned = selfsigned;
        return this;
    }

    public void setSelfsigned(Boolean selfsigned) {
        this.selfsigned = selfsigned;
    }

    public Boolean isTrusted() {
        return trusted;
    }

    public Certificate trusted(Boolean trusted) {
        this.trusted = trusted;
        return this;
    }

    public void setTrusted(Boolean trusted) {
        this.trusted = trusted;
    }

    public Boolean isActive() {
        return active;
    }

    public Certificate active(Boolean active) {
        this.active = active;
        return this;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getContent() {
        return content;
    }

    public Certificate content(String content) {
        this.content = content;
        return this;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public CSR getCsr() {
        return csr;
    }

    public Certificate csr(CSR cSR) {
        this.csr = cSR;
        return this;
    }

    public void setCsr(CSR cSR) {
        this.csr = cSR;
    }

    public CertificateComment getComment() {
        return this.comment;
    }

    public Certificate comment(CertificateComment certificateComment) {
        this.setComment(certificateComment);
        return this;
    }

    public void setComment(CertificateComment certificateComment) {
        this.comment = certificateComment;
    }


    public Set<CertificateAttribute> getCertificateAttributes() {
        return certificateAttributes;
    }

    public Certificate certificateAttributes(Set<CertificateAttribute> certificateAttributes) {
        this.certificateAttributes = certificateAttributes;
        return this;
    }

    public Certificate addCertificateAttributes(CertificateAttribute certificateAttribute) {
        this.certificateAttributes.add(certificateAttribute);
        certificateAttribute.setCertificate(this);
        return this;
    }

    public Certificate removeCertificateAttributes(CertificateAttribute certificateAttribute) {
        this.certificateAttributes.remove(certificateAttribute);
        certificateAttribute.setCertificate(null);
        return this;
    }

    public void setCertificateAttributes(Set<CertificateAttribute> certificateAttributes) {
        this.certificateAttributes = certificateAttributes;
    }

    public Certificate getIssuingCertificate() {
        return issuingCertificate;
    }

    public Certificate issuingCertificate(Certificate certificate) {
        this.issuingCertificate = certificate;
        return this;
    }

    public void setIssuingCertificate(Certificate certificate) {
        this.issuingCertificate = certificate;
    }

    public Certificate getRootCertificate() {
        return rootCertificate;
    }

    public Certificate rootCertificate(Certificate certificate) {
        this.rootCertificate = certificate;
        return this;
    }

    public void setRootCertificate(Certificate certificate) {
        this.rootCertificate = certificate;
    }

    public CAConnectorConfig getRevocationCA() {
        return revocationCA;
    }

    public Certificate revocationCA(CAConnectorConfig cAConnectorConfig) {
        this.revocationCA = cAConnectorConfig;
        return this;
    }

    public void setRevocationCA(CAConnectorConfig cAConnectorConfig) {
        this.revocationCA = cAConnectorConfig;
    }
    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here, do not remove

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Certificate)) {
            return false;
        }
        return id != null && id.equals(((Certificate) o).id);
    }

    @Override
    public int hashCode() {
        return 31;
    }

    @Override
    public String toString() {
        return "Certificate{" +
            "id=" + getId() +
            ", tbsDigest='" + getTbsDigest() + "'" +
            ", subject='" + getSubject() + "'" +
            ", sans='" + getSans() + "'" +
            ", issuer='" + getIssuer() + "'" +
            ", root='" + getRoot() + "'" +
            ", type='" + getType() + "'" +
            ", description='" + getDescription() + "'" +
            ", fingerprint='" + getFingerprint() + "'" +
            ", serial='" + getSerial() + "'" +
            ", validFrom='" + getValidFrom() + "'" +
            ", validTo='" + getValidTo() + "'" +
            ", keyAlgorithm='" + getKeyAlgorithm() + "'" +
            ", keyLength=" + getKeyLength() +
            ", curveName='" + getCurveName() + "'" +
            ", hashingAlgorithm='" + getHashingAlgorithm() + "'" +
            ", paddingAlgorithm='" + getPaddingAlgorithm() + "'" +
            ", signingAlgorithm='" + getSigningAlgorithm() + "'" +
            ", creationExecutionId='" + getCreationExecutionId() + "'" +
            ", contentAddedAt='" + getContentAddedAt() + "'" +
            ", revokedSince='" + getRevokedSince() + "'" +
            ", revocationReason='" + getRevocationReason() + "'" +
            ", revoked='" + isRevoked() + "'" +
            ", revocationExecutionId='" + getRevocationExecutionId() + "'" +
            ", administrationComment='" + getAdministrationComment() + "'" +
            ", endEntity='" + isEndEntity() + "'" +
            ", selfsigned='" + isSelfsigned() + "'" +
            ", trusted='" + isTrusted() + "'" +
            ", active='" + isActive() + "'" +
            ", content='" + getContent() + "'" +
            "}";
    }
}
