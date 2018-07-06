(ns documint.pdf.signing
  "PDF document signatures.

  Largely based off <https://github.com/apache/pdfbox/blob/2.0.0-RC2/examples/src/main/java/org/apache/pdfbox/examples/signature/CreateSignatureBase.java>"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component])
  (:import [java.util Calendar]
           [java.security Security KeyStore Provider]
           [org.bouncycastle.asn1
            ASN1Primitive
            ASN1ObjectIdentifier]
           [org.bouncycastle.asn1.cms CMSObjectIdentifiers]
           [org.bouncycastle.asn1.x509 Certificate]
           [org.bouncycastle.cert X509CertificateHolder]
           [org.bouncycastle.cert.jcajce JcaCertStore]
           [org.bouncycastle.cms
            CMSSignedData
            CMSSignedDataGenerator
            CMSTypedData]
           [org.bouncycastle.cms.jcajce JcaSignerInfoGeneratorBuilder]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.operator.jcajce
            JcaContentSignerBuilder
            JcaDigestCalculatorProviderBuilder]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.pdmodel.interactive.digitalsignature
            PDSignature
            SignatureInterface]))

(set! *warn-on-reflection* true)


(defrecord CMSProcessableInputStream [^ASN1ObjectIdentifier content-type
                                      ^java.io.InputStream input-stream]
  CMSTypedData
  (^ASN1ObjectIdentifier getContentType [this]
    content-type)

  (^Object getContent [this]
    input-stream)

  (write [this output-stream]
    (clojure.java.io/copy input-stream output-stream)))


(defn- cms-processable-input-stream
  "Port of <https://github.com/apache/pdfbox/blob/2.0.0-RC2/examples/src/main/java/org/apache/pdfbox/examples/signature/CMSProcessableInputStream.java>.
  "
  [input-stream]
  (->CMSProcessableInputStream
   (ASN1ObjectIdentifier. (.getId CMSObjectIdentifiers/data))
   input-stream))


(defn- ^SignatureInterface bouncy-castle-signature-interface
  "Construct an instance of a `SignatureInterface` implementation using Bouncy
  Castle."
  [certificate-chain private-key]
  (let [store  (JcaCertStore. (vec certificate-chain))
        gen    (CMSSignedDataGenerator.)
        cert   (Certificate/getInstance
                (ASN1Primitive/fromByteArray
                 (.getEncoded ^java.security.cert.Certificate (first certificate-chain))))
        signer (.. (JcaContentSignerBuilder. "SHA256withRSA")
                   (build private-key))]
    (doto gen
      ; XXX: This is impossible to read, is it possible to write it with `..`?
      (.addSignerInfoGenerator (.build (JcaSignerInfoGeneratorBuilder.
                                        (.build (JcaDigestCalculatorProviderBuilder.)))
                                       signer
                                       (X509CertificateHolder. cert)))
      (.addCertificates store))

    (reify
      SignatureInterface
      (sign [this content]
        (let [msg         (cms-processable-input-stream content)
              signed-data (.generate gen msg false)]
          ; XXX: Is this a legitimate concern? <http://stackoverflow.com/questions/30400728/signing-pdf-with-pdfbox-and-bouncycastle>
          (.getEncoded signed-data))))))


(defprotocol ISigner
  "Document signer."
  (sign-document [this document certificate-alias location reason output]
    "Sign a `PDDocument` with the specified certificate."))


(defrecord SignerComponent [^Provider provider
                            sig-ifaces
                            certificate-passwords
                            keystore]
  ISigner
  (sign-document [this document certificate-alias location reason output]
    (if-let [^SignatureInterface sig-iface (sig-ifaces certificate-alias)]
      (let [signature (PDSignature.)]
        (doto signature
          (.setFilter PDSignature/FILTER_ADOBE_PPKLITE)
          (.setSubFilter PDSignature/SUBFILTER_ADBE_PKCS7_DETACHED)
          (.setLocation location)
          (.setReason reason)
          (.setSignDate (Calendar/getInstance)))
        ; XXX: Protect the document?
        (doto ^PDDocument document
          (.addSignature signature sig-iface)
          (.saveIncremental output)))
      (throw (ex-info "Unknown certificate alias"
                      {:causes [[:unknown-certificate-alias certificate-alias]]}))))

  component/Lifecycle
  (start [this]
    (log/info "Starting SignerComponent")
    (let [provider       (BouncyCastleProvider.)
          make-sig-iface (fn [^KeyStore keystore [cert-alias ^String password]]
                           (log/info "Building SignatureInterface"
                                     {:certificate-alias cert-alias})
                           (let [cert-alias (name cert-alias)]
                             [cert-alias
                              (bouncy-castle-signature-interface
                               (.getCertificateChain keystore
                                                     cert-alias)
                               (.getKey keystore
                                        cert-alias
                                        (.toCharArray password)))]))]
      (Security/addProvider provider)
      (merge this
             {:provider   provider
              :sig-ifaces (into {}
                                (map (partial make-sig-iface keystore)
                                     certificate-passwords))})))

  (stop [this]
    (log/info "Stopping SignerComponent")
    (Security/removeProvider (.getName provider))
    this))


(defn signer-component
  "Create a `SignerComponent`."
  [certificate-passwords]
  (map->SignerComponent
   {:certificate-passwords certificate-passwords}))
