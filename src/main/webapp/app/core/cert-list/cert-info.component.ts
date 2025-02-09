import { Component, Inject } from 'vue-property-decorator';
import { Fragment } from 'vue-fragment';

import { mixins } from 'vue-class-component';
import JhiDataUtils from '@/shared/data/data-utils.service';
import AlertMixin from '@/shared/alert/alert.mixin';
import CopyClipboardButton from '@/shared/clipboard/clipboard.vue';
import HelpTag from '@/core/help/help-tag.vue';
import AuditTag from '@/core/audit/audit-tag.vue';

import { ICertificateView } from '@/shared/model/transfer-object.model';
import CertificateViewService from '../../entities/certificate/certificate-view.service';

import axios, { AxiosError } from 'axios';
import { ICertificateAdministrationData } from '@/shared/model/transfer-object.model';

@Component({
  components: {
    Fragment,
    CopyClipboardButton,
    HelpTag,
    AuditTag
  }
})
export default class CertificateDetails extends mixins(AlertMixin, JhiDataUtils) {
  @Inject('certificateViewService') private certificateViewService: () => CertificateViewService;

  public certificateView: ICertificateView = {};
  public certificateAdminData: ICertificateAdministrationData = {};
  public p12Alias = 'alias';
  public p12Pbe = 'aes-sha256';
  public p12KeyEx = false;
  public downloadFormat = 'pkix';

  public collapsed = true;
  public setCollapsed(collapsed: boolean) {
    this.collapsed = collapsed;
  }

  public getRevocationStyle(revoked: boolean): string {
    return revoked ? 'text-decoration:line-through;' : 'font-weight:bold;';
  }
  public getP12Pbe(): string {
    if (
      this.$store.state.uiConfigStore.config.cryptoConfigView !== undefined &&
      this.$store.state.uiConfigStore.config.cryptoConfigView.defaultPBEAlgo !== undefined
    ) {
      return this.$store.state.uiConfigStore.config.cryptoConfigView.defaultPBEAlgo;
    }
    return 'aes-sha256';
  }

  public getP12PbeAlgoArr(): string[] {
    return this.$store.state.uiConfigStore.config.cryptoConfigView.validPBEAlgoArr;
  }

  public getDownloadFilename(): string {
    let extension = '.crt';
    if (this.downloadFormat === 'pem') {
      extension = '.pem';
    } else if (this.downloadFormat === 'pemPart') {
      extension = '.part.pem';
    } else if (this.downloadFormat === 'pemFull') {
      extension = '.full.pem';
    }
    return this.certificateView.downloadFilename + extension;
  }

  public downloadItem() {
    const filename = this.getDownloadFilename();

    let url = '/publicapi/certPKIX/' + this.certificateView.id + '/' + filename;
    let mimetype = 'application/pkix-cert';

    if (this.downloadFormat === 'pem') {
      url = '/publicapi/certPEM/' + this.certificateView.id + '/' + filename;
      mimetype = 'application/pem-certificate';
    } else if (this.downloadFormat === 'pemPart') {
      url = '/publicapi/certPEMPart/' + this.certificateView.id + '/' + filename;
      mimetype = 'application/x-pem-certificate-chain';
    } else if (this.downloadFormat === 'pemFull') {
      url = '/publicapi/certPEMFull/' + this.certificateView.id + '/' + filename;
      mimetype = 'application/pem-certificate-chain';
    }

    const headers: any = { Accept: mimetype };

    this.download(url, filename, mimetype, headers);
  }

  public downloadKeystore(extension: string, mimetype: string) {
    const filename = this.certificateView.downloadFilename + extension;
    const url =
      '/publicapi/keystore/' + this.certificateView.id + '/' + encodeURIComponent(filename) + '/' + encodeURIComponent(this.p12Alias);

    const headers: any = {
      Accept: mimetype,
      X_pbeAlgo: this.getP12Pbe(),
      X_keyEx: this.p12KeyEx
    };

    this.download(url, filename, mimetype, headers);
  }

  public download(url: string, filename: string, mimetype: string, headers: any) {
    const self = this;
    const config = new Object();
    config['responseType'] = 'blob';
    config['headers'] = headers;

    axios
      //      .get(url, { responseType: 'blob', headers: headers })
      .get(url, config)
      .then(response => {
        const blob = new Blob([response.data], { type: mimetype, endings: 'transparent' });
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = filename;
        link.type = mimetype;

        window.console.info('tmp download lnk : ' + link.download);

        link.click();
        URL.revokeObjectURL(link.href);
      })
      .catch(function(error) {
        console.log(error);
        const message = self.$t('problem processing request: ' + error);

        const err = error as AxiosError;
        if (err.response) {
          console.log(err.response.status);
          console.log(err.response.data);
          if (err.response.status === 401) {
            self.alertService().showAlert('Action not allowed', 'warn');
          } else {
            self.alertService().showAlert(message, 'info');
          }
        } else {
          self.alertService().showAlert(message, 'info');
        }
        self.getAlertFromStore();
      });
  }

  beforeRouteEnter(to, from, next) {
    next(vm => {
      window.console.info('################ to.params : ' + to.params.certificateId);
      if (to.params.certificateId) {
        vm.retrieveCertificate(to.params.certificateId);
      }
    });
  }

  public mounted(): void {
    window.console.info('++++++++++++++++++ route.query : ' + this.$route.query.certificateId);
    if (this.$route.query.certificateId) {
      this.retrieveCertificate(this.$route.query.certificateId);
    }
    this.p12Pbe = this.getP12Pbe();
  }

  public retrieveCertificate(certificateId) {
    const self = this;
    this.certificateViewService()
      .find(certificateId)
      .then(res => {
        self.certificateView = res;
        if (self.certificateAdminData === undefined) {
          self.certificateAdminData = {};
        }
        self.certificateAdminData.arAttributes = this.certificateView.arArr;
        self.certificateAdminData.comment = this.certificateView.comment;
        self.certificateAdminData.trusted = this.certificateView.trusted;
        window.console.info('certificate loaded successfully : ' + self.certificateView.id);
      });
  }

  public previousState() {
    this.$router.go(-1);
  }

  public get roles(): string {
    return this.$store.getters.account ? this.$store.getters.account.authorities[0] : '';
  }

  public getUsername(): string {
    return this.$store.getters.account ? this.$store.getters.account.login : '';
  }

  public isTrustable() {
    return this.isRAOrAdmin() && !this.certificateView.revoked && this.certificateView.selfsigned;
  }

  public isEditable() {
    return this.isRAOfficer() || this.isOwnCertificate();
  }

  public isRevocable() {
    return (
      !this.certificateView.revoked &&
      this.certificateView.validTo &&
      //      ( this.certificate.validTo.getMilliseconds() < Date.now()) &&
      (this.isRAOfficer() || this.isOwnCertificate())
    );
  }

  public isRemovableFromCRL() {
    return (
      this.certificateView.revocationReason === 'certificateHold' &&
      this.certificateView.validTo &&
      //      ( this.certificate.validTo.getMilliseconds() < Date.now()) &&
      (this.isRAOfficer() || this.isOwnCertificate())
    );
  }

  public isRAOfficer() {
    return this.hasRole('ROLE_RA') || this.hasRole('ROLE_RA_DOMAIN');
  }

  public isAdmin() {
    return this.hasRole('ROLE_ADMIN');
  }

  public isRAOrAdmin() {
    return this.isRAOfficer() || this.isAdmin();
  }

  public hasRole(targetRole: string) {
    if (this.$store.getters.account === null || this.$store.getters.account.authorities === null) {
      return false;
    }

    for (const role of this.$store.getters.account.authorities) {
      if (targetRole === role) {
        return true;
      }
    }
    return false;
  }

  public isOwnCertificate() {
    return this.getUsername() === this.certificateView.requestedBy;
  }

  public updateCertificate() {
    this.certificateAdminData.certificateId = this.certificateView.id;
    this.certificateAdminData.administrationType = 'UPDATE';
    this.certificateAdminData.trusted = this.certificateView.trusted;
    this.sendAdministrationAction('api/administerCertificate');
  }
  public updateCRL() {
    this.certificateAdminData.certificateId = this.certificateView.id;
    this.certificateAdminData.administrationType = 'UPDATE_CRL';
    this.sendAdministrationAction('api/administerCertificate');
  }

  public removeCertificateFromCRL() {
    this.certificateAdminData.certificateId = this.certificateView.id;
    this.certificateAdminData.revocationReason = 'removeFromCRL';
    this.certificateAdminData.administrationType = 'REVOKE';
    this.certificateAdminData.trusted = this.certificateView.trusted;
    this.sendAdministrationAction('api/administerCertificate');
  }

  public revokeCertificate() {
    this.certificateAdminData.certificateId = this.certificateView.id;
    this.certificateAdminData.administrationType = 'REVOKE';
    this.certificateAdminData.trusted = this.certificateView.trusted;
    this.sendAdministrationAction('api/administerCertificate');
  }

  public selfAdministerCertificate() {
    this.certificateAdminData.certificateId = this.certificateView.id;
    this.certificateAdminData.administrationType = 'UPDATE';
    this.certificateAdminData.trusted = this.certificateView.trusted;
    this.sendAdministrationAction('api/selfAdministerCertificate');
  }

  public withdrawCertificate() {
    this.certificateAdminData.certificateId = this.certificateView.id;
    this.certificateAdminData.administrationType = 'REVOKE';
    this.certificateAdminData.trusted = this.certificateView.trusted;
    this.sendAdministrationAction('api/withdrawOwnCertificate');
  }

  sendAdministrationAction(adminUrl: string) {
    document.body.style.cursor = 'wait';
    const self = this;

    if (this.certificateAdminData.trusted === null) {
      this.certificateAdminData.trusted = false;
    }

    axios({
      method: 'post',
      url: adminUrl,
      data: this.certificateAdminData,
      responseType: 'stream'
    })
      .then(function(response) {
        console.log(response.status);

        if (response.status === 201) {
          self.$router.push({ name: 'CertInfo', params: { certificateId: response.data.toString() } });
        } else {
          self.previousState();
        }
      })
      .catch(function(error) {
        console.log(error);
        self.previousState();
        const message = self.$t('problem processing request: ' + error);
        self.alertService().showAlert(message, 'info');
      })
      .then(function() {
        // always executed
        document.body.style.cursor = 'default';
      });
  }
}
