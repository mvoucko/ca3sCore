package de.trustable.ca3s.core.web.rest;

import de.trustable.ca3s.core.domain.CAConnectorConfig;
import de.trustable.ca3s.core.domain.ProtectedContent;
import de.trustable.ca3s.core.domain.enumeration.ContentRelationType;
import de.trustable.ca3s.core.domain.enumeration.ProtectedContentType;
import de.trustable.ca3s.core.repository.CAConnectorConfigRepository;
import de.trustable.ca3s.core.repository.ProtectedContentRepository;
import de.trustable.ca3s.core.service.CAConnectorConfigService;
import de.trustable.ca3s.core.service.util.ProtectedContentUtil;
import de.trustable.ca3s.core.web.rest.errors.BadRequestAlertException;

import io.github.jhipster.web.util.HeaderUtil;
import io.github.jhipster.web.util.ResponseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.net.URI;
import java.net.URISyntaxException;

import java.util.List;
import java.util.Optional;

/**
 * REST controller for managing {@link de.trustable.ca3s.core.domain.CAConnectorConfig}.
 */
@RestController
@RequestMapping("/api")
public class CAConnectorConfigResource {

    public static final String PLAIN_SECRET_PLACEHOLDER = "******";

	private final Logger log = LoggerFactory.getLogger(CAConnectorConfigResource.class);

    private static final String ENTITY_NAME = "cAConnectorConfig";

    @Value("${jhipster.clientApp.name}")
    private String applicationName;

	@Autowired
	private ProtectedContentUtil protUtil;
	
	@Autowired
	private ProtectedContentRepository protContentRepository;
	
	@Autowired
	private CAConnectorConfigRepository caConfigRepository;
	

    private final CAConnectorConfigService cAConnectorConfigService;

    public CAConnectorConfigResource(CAConnectorConfigService cAConnectorConfigService) {
        this.cAConnectorConfigService = cAConnectorConfigService;
    }

    /**
     * {@code POST  /ca-connector-configs} : Create a new cAConnectorConfig.
     *
     * @param cAConnectorConfig the cAConnectorConfig to create.
     * @return the {@link ResponseEntity} with status {@code 201 (Created)} and with body the new cAConnectorConfig, or with status {@code 400 (Bad Request)} if the cAConnectorConfig has already an ID.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @PostMapping("/ca-connector-configs")
    public ResponseEntity<CAConnectorConfig> createCAConnectorConfig(@Valid @RequestBody CAConnectorConfig cAConnectorConfig) throws URISyntaxException {
        log.debug("REST request to save CAConnectorConfig : {}", cAConnectorConfig);
        if (cAConnectorConfig.getId() != null) {
            throw new BadRequestAlertException("A new cAConnectorConfig cannot already have an ID", ENTITY_NAME, "idexists");
        }
        
        if((cAConnectorConfig.getPlainSecret() == null) || (cAConnectorConfig.getPlainSecret().trim().length() == 0))  {
            log.debug("REST request to save CAConnectorConfig : cAConnectorConfig.getPlainSecret() == null");
	        cAConnectorConfig.setSecret(null);
	        cAConnectorConfig.setPlainSecret("");
        }else {	
        	if( protUtil == null) {
        		System.err.println("Autowired failed ...");
        	}
        	
	        ProtectedContent protSecret = protUtil.createProtectedContent(cAConnectorConfig.getPlainSecret(), ProtectedContentType.PASSWORD, ContentRelationType.CONNECTION, -1L);
	        protContentRepository.save(protSecret);
	        cAConnectorConfig.setSecret(protSecret);
	        cAConnectorConfig.setPlainSecret(PLAIN_SECRET_PLACEHOLDER);
        }
        
        CAConnectorConfig result = cAConnectorConfigService.save(cAConnectorConfig);
        return ResponseEntity.created(new URI("/api/ca-connector-configs/" + result.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(applicationName, true, ENTITY_NAME, result.getId().toString()))
            .body(result);
    }

    /**
     * {@code PUT  /ca-connector-configs} : Updates an existing cAConnectorConfig.
     *
     * @param cAConnectorConfig the cAConnectorConfig to update.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the updated cAConnectorConfig,
     * or with status {@code 400 (Bad Request)} if the cAConnectorConfig is not valid,
     * or with status {@code 500 (Internal Server Error)} if the cAConnectorConfig couldn't be updated.
     * @throws URISyntaxException if the Location URI syntax is incorrect.
     */
    @Transactional
    @PutMapping("/ca-connector-configs")
    public ResponseEntity<CAConnectorConfig> updateCAConnectorConfig(@Valid @RequestBody CAConnectorConfig cAConnectorConfig) throws URISyntaxException {
        log.debug("REST request to update CAConnectorConfig : {}", cAConnectorConfig);
        if (cAConnectorConfig.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        
        
        if((cAConnectorConfig.getPlainSecret() == null) || (cAConnectorConfig.getPlainSecret().trim().length() == 0))  {
            
        	log.debug("REST request to update CAConnectorConfig : cAConnectorConfig.getPlainSecret() == null");

    		if(cAConnectorConfig.getSecret() != null ) {
            	log.debug("REST request to update CAConnectorConfig : protContentRepository.delete() ");
    			protContentRepository.delete(cAConnectorConfig.getSecret());
    		}

	        cAConnectorConfig.setSecret(null);
	        cAConnectorConfig.setPlainSecret("");
        } else {
        	if( PLAIN_SECRET_PLACEHOLDER.equals(cAConnectorConfig.getPlainSecret().trim())) {
	        	log.debug("REST request to update CAConnectorConfig : PLAIN_SECRET_PLACEHOLDER.equals(cAConnectorConfig.getPlainSecret())");
	        	
	        	// no passphrase change received from the UI, just do nothing
	        	// leave the secret unchanged
	        	
	        	cAConnectorConfig.setSecret(caConfigRepository.getOne(cAConnectorConfig.getId()).getSecret());
        	}else {
	        	log.debug("REST request to update CAConnectorConfig : PlainSecret modified");
	        	
        		if(cAConnectorConfig.getSecret() != null ) {
                	log.debug("REST request to update CAConnectorConfig : protContentRepository.delete() ");
        			protContentRepository.delete(cAConnectorConfig.getSecret());
        		}
        		
                ProtectedContent protSecret = protUtil.createProtectedContent(cAConnectorConfig.getPlainSecret(), ProtectedContentType.PASSWORD, ContentRelationType.CONNECTION, cAConnectorConfig.getId());
                protContentRepository.save(protSecret);
                
                cAConnectorConfig.setSecret(protSecret);
    	        cAConnectorConfig.setPlainSecret(PLAIN_SECRET_PLACEHOLDER);
        	}
        }
        
        if( cAConnectorConfig.isDefaultCA()) {
        	for( CAConnectorConfig other: caConfigRepository.findAll() ) {
        		if( other.getId() != cAConnectorConfig.getId() &&
        				other.isDefaultCA()) {

                	log.debug("REST request to update CAConnectorConfig : remove 'deaultCA' flag from caConfig {} ", other.getId());
        			other.setDefaultCA(false);
        			caConfigRepository.save(other);
        		}
        	}
        }
        CAConnectorConfig result = cAConnectorConfigService.save(cAConnectorConfig);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(applicationName, true, ENTITY_NAME, cAConnectorConfig.getId().toString()))
            .body(result);
    }

    /**
     * {@code GET  /ca-connector-configs} : get all the cAConnectorConfigs.
     *

     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and the list of cAConnectorConfigs in body.
     */
    @GetMapping("/ca-connector-configs")
    public List<CAConnectorConfig> getAllCAConnectorConfigs() {
        log.debug("REST request to get all CAConnectorConfigs");
        return cAConnectorConfigService.findAll();
    }

    /**
     * {@code GET  /ca-connector-configs/:id} : get the "id" cAConnectorConfig.
     *
     * @param id the id of the cAConnectorConfig to retrieve.
     * @return the {@link ResponseEntity} with status {@code 200 (OK)} and with body the cAConnectorConfig, or with status {@code 404 (Not Found)}.
     */
    @GetMapping("/ca-connector-configs/{id}")
    public ResponseEntity<CAConnectorConfig> getCAConnectorConfig(@PathVariable Long id) {
        log.debug("REST request to get CAConnectorConfig : {}", id);
        Optional<CAConnectorConfig> cAConnectorConfig = cAConnectorConfigService.findOne(id);
        return ResponseUtil.wrapOrNotFound(cAConnectorConfig);
    }

    /**
     * {@code DELETE  /ca-connector-configs/:id} : delete the "id" cAConnectorConfig.
     *
     * @param id the id of the cAConnectorConfig to delete.
     * @return the {@link ResponseEntity} with status {@code 204 (NO_CONTENT)}.
     */
    @DeleteMapping("/ca-connector-configs/{id}")
    public ResponseEntity<Void> deleteCAConnectorConfig(@PathVariable Long id) {
        log.debug("REST request to delete CAConnectorConfig : {}", id);
        cAConnectorConfigService.delete(id);
        return ResponseEntity.noContent().headers(HeaderUtil.createEntityDeletionAlert(applicationName, true, ENTITY_NAME, id.toString())).build();
    }
}
