(ns cmr.common.test.xml
  (:require [clojure.test :refer :all]
            [cmr.common.xml :as cx]
            [clojure.data.xml :as x]))


(def sample-xml
  "<top>
  <inner ia=\"1\" ib=\"foo\">
  <alpha>45</alpha>
  <bravo>ovarb</bravo>
  </inner>
  </top>")

(def parsed-sample-xml
  (x/parse-str sample-xml))

(deftest element-at-path-test
  (are [xml path] (= (when xml (x/parse-str xml))
                     (cx/element-at-path parsed-sample-xml path))
       sample-xml [:top]
       "<alpha>45</alpha>" [:top :inner :alpha]
       "<bravo>ovarb</bravo>" [:top :inner :bravo]
       nil [:foo]
       nil [:top :foo]
       nil [:foo :bar]))

(deftest content-at-path-test
  (are [expected path] (= expected (cx/content-at-path parsed-sample-xml path))
       ["45"] [:top :inner :alpha]
       ["ovarb"] [:top :inner :bravo]
       nil [:foo]))

(deftest string-at-path-test
  (are [expected path] (= expected (cx/string-at-path parsed-sample-xml path))
       "45" [:top :inner :alpha]
       "ovarb" [:top :inner :bravo]
       nil [:top :foo]
       nil [:top :inner :foo]))

(deftest long-at-path-test
  (is (= 45 (cx/long-at-path parsed-sample-xml [:top :inner :alpha])))
  (is (= nil (cx/long-at-path parsed-sample-xml [:top :inner :foo]))))

(deftest double-at-path-test
  (is (= 45.0 (cx/double-at-path parsed-sample-xml [:top :inner :alpha])))
  (is (= nil (cx/double-at-path parsed-sample-xml [:top :inner :foo]))))

(deftest attrs-at-path-test
  (is (= {:ia "1" :ib "foo"}
         (cx/attrs-at-path parsed-sample-xml
                           [:top :inner])))
  (is (= nil (cx/attrs-at-path parsed-sample-xml [:top :foo]))))
