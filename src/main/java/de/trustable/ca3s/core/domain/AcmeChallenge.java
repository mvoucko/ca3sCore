package de.trustable.ca3s.core.domain;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import javax.persistence.*;
import javax.validation.constraints.*;

import java.io.Serializable;
import java.time.Instant;

import de.trustable.ca3s.core.domain.enumeration.ChallengeStatus;

/**
 * A AcmeChallenge.
 */
@Entity
@Table(name = "acme_challenge")
public class AcmeChallenge implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @NotNull
    @Column(name = "type", nullable = false)
    private String type;

    @NotNull
    @Column(name = "value_", nullable = false)
    private String value;

    @NotNull
    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "validated")
    private Instant validated;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChallengeStatus status;

    @ManyToOne
    @JsonIgnoreProperties("challenges")
    private AcmeAuthorization acmeAuthorization;

    // jhipster-needle-entity-add-field - JHipster will add fields here, do not remove
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChallengeId() {
        return challengeId;
    }

    public AcmeChallenge challengeId(Long challengeId) {
        this.challengeId = challengeId;
        return this;
    }

    public void setChallengeId(Long challengeId) {
        this.challengeId = challengeId;
    }

    public String getType() {
        return type;
    }

    public AcmeChallenge type(String type) {
        this.type = type;
        return this;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public AcmeChallenge value(String value) {
        this.value = value;
        return this;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getToken() {
        return token;
    }

    public AcmeChallenge token(String token) {
        this.token = token;
        return this;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Instant getValidated() {
        return validated;
    }

    public AcmeChallenge validated(Instant validated) {
        this.validated = validated;
        return this;
    }

    public void setValidated(Instant validated) {
        this.validated = validated;
    }

    public ChallengeStatus getStatus() {
        return status;
    }

    public AcmeChallenge status(ChallengeStatus status) {
        this.status = status;
        return this;
    }

    public void setStatus(ChallengeStatus status) {
        this.status = status;
    }

    public AcmeAuthorization getAcmeAuthorization() {
        return acmeAuthorization;
    }

    public AcmeChallenge acmeAuthorization(AcmeAuthorization acmeAuthorization) {
        this.acmeAuthorization = acmeAuthorization;
        return this;
    }

    public void setAcmeAuthorization(AcmeAuthorization acmeAuthorization) {
        this.acmeAuthorization = acmeAuthorization;
    }
    // jhipster-needle-entity-add-getters-setters - JHipster will add getters and setters here, do not remove

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AcmeChallenge)) {
            return false;
        }
        return id != null && id.equals(((AcmeChallenge) o).id);
    }

    @Override
    public int hashCode() {
        return 31;
    }

    @Override
    public String toString() {
        return "AcmeChallenge{" +
            "id=" + getId() +
            ", challengeId=" + getChallengeId() +
            ", type='" + getType() + "'" +
            ", value='" + getValue() + "'" +
            ", token='" + getToken() + "'" +
            ", validated='" + getValidated() + "'" +
            ", status='" + getStatus() + "'" +
            "}";
    }

    public static final String CHALLENGE_TYPE_HTTP_01 = "http-01";
    public static final String CHALLENGE_TYPE_DNS_01 = "dns-01";
    public static final String CHALLENGE_TYPE_ALPN_01 = "tls-alpn-01";

}
