(ns cmr.ingest.services.granule-bulk-update.s3.umm-g-test
  "Unit tests for UMM-G s3 url update"
  (:require
   [clojure.test :refer :all]
   [cmr.common.util :as util :refer [are3]]
   [cmr.ingest.services.granule-bulk-update.s3.s3-util :as s3-util]
   [cmr.ingest.services.granule-bulk-update.s3.umm-g :as umm-g]))

(def ^:private doc-related-url
  "Sample document RelatedUrl"
  {:URL "http://example.com/doc.html"
   :Type "VIEW RELATED INFORMATION"
   :Subtype "USER'S GUIDE"
   :Description "ORNL DAAC Data Set Documentation"
   :Format "HTML"
   :MimeType "text/html"})

(def ^:private sample-urls
  "Sample RelatedUrls with s3 urls and other url"
  [doc-related-url
   {:URL "s3://abc/to_be_updated"
    :Type "GET DATA VIA DIRECT ACCESS"
    :Subtype "MAP"
    :Description "some S3 description"}
   {:URL "s3://abc/to_be_updated_2"
    :Type "GET DATA VIA DIRECT ACCESS"
    :Description "other s3 link"}])

(deftest add-s3-url
  (testing "add or update s3 url to UMM-G"
    (are3 [url-value source result]
      (let [urls (s3-util/validate-url url-value)]
        (is (= result
               (umm-g/add-s3-url source urls))))

      "no RelatedUrls in metadata"
      "s3://abc/foo"
      {}
      {:RelatedUrls [{:URL "s3://abc/foo"
                      :Type "GET DATA VIA DIRECT ACCESS"
                      :Description "This link provides direct download access via S3 to the granule."}]}

      "non-matching S3 RelatedUrls in metadata"
      "s3://abc/foo"
      {:RelatedUrls [doc-related-url]}
      {:RelatedUrls [{:URL "s3://abc/foo"
                      :Type "GET DATA VIA DIRECT ACCESS"
                      :Description "This link provides direct download access via S3 to the granule."}
                     doc-related-url]}

      "non-matching S3 RelatedUrls in metadata, multiple s3 urls update"
      "s3://abc/foo, s3://abc/bar"
      {:RelatedUrls [doc-related-url]}
      {:RelatedUrls [{:URL "s3://abc/foo"
                      :Type "GET DATA VIA DIRECT ACCESS"
                      :Description "This link provides direct download access via S3 to the granule."}
                     {:URL "s3://abc/bar"
                      :Type "GET DATA VIA DIRECT ACCESS"
                      :Description "This link provides direct download access via S3 to the granule."}
                     doc-related-url]}

      "existing S3 RelatedUrls in metadata"
      "s3://abc/foo"
      {:RelatedUrls sample-urls}
      {:RelatedUrls [{:URL "s3://abc/foo"
                      :Type "GET DATA VIA DIRECT ACCESS"
                      :Description "This link provides direct download access via S3 to the granule."}
                     doc-related-url]}

      "existing S3 RelatedUrls in metadata, multiple S3 urls update"
      "s3://abc/foo, s3://abc/bar"
      {:RelatedUrls sample-urls}
      {:RelatedUrls [{:URL "s3://abc/foo"
                      :Type "GET DATA VIA DIRECT ACCESS"
                      :Description "This link provides direct download access via S3 to the granule."}
                     {:URL "s3://abc/bar"
                      :Type "GET DATA VIA DIRECT ACCESS"
                      :Description "This link provides direct download access via S3 to the granule."}
                     doc-related-url]})))
