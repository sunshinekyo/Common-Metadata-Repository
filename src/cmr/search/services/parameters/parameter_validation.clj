(ns cmr.search.services.parameters.parameter-validation
  "Contains functions for validating query parameters"
  (:require [clojure.set :as set]
            [cmr.common.services.errors :as err]
            [cmr.common.services.messages :as c-msg]
            [cmr.common.parameter-parser :as parser]
            [clojure.string :as s]
            [cmr.common.date-time-parser :as dt-parser]
            [cmr.search.services.parameters.conversion :as p]
            [cmr.search.services.parameters.legacy-parameters :as lp]
            [cmr.search.services.parameters.converters.attribute :as attrib]
            [cmr.search.services.parameters.converters.science-keyword :as sk]
            [cmr.search.services.messages.attribute-messages :as attrib-msg]
            [cmr.search.services.parameters.converters.orbit-number :as on]
            [cmr.search.services.messages.orbit-number-messages :as on-msg]
            [cmr.search.services.messages.common-messages :as msg]
            [cmr.search.data.messages :as d-msg]
            [cmr.common.config :as cfg]
            [camel-snake-kebab :as csk]
            [cmr.spatial.codec :as spatial-codec]
            [clj-time.core :as t])
  (:import clojure.lang.ExceptionInfo
           java.lang.Integer))

(def search-paging-depth-limit
  "The maximum value for page-num * page-size"
  (cfg/config-value-fn :search-paging-depth-limit 1000000 #(Integer. %)))

(def case-sensitive-params
  "Parameters which do not allow option with ingnore_case set to true."
  (set #{:concept-id :echo-collection-id :echo-granule-id}))

(def params-that-disallow-pattern-search-option
  "Parameters which do not allow pattern search option."
  (set #{:concept-id :echo-collection-id :echo-granule-id}))

(def params-that-allow-or-option
  "Parameter which allow search with the OR option."
  #{:attribute :science-keywords})

(def exclude-params
  "Lists parameters which can be used to exclude items from results."
  (set #{:concept-id}))

(defn- concept-type->valid-param-names
  "A set of the valid parameter names for the given concept-type."
  [concept-type]
  (set (concat
         (keys (get p/concept-param->type concept-type))
         (keys lp/param-aliases)
         [:options])))

(defn- get-ivalue-from-params
  "Get a value from the params as an Integer or nil value. Throws NumberFormatException
  if the value cannot be converted to an Integer."
  [params value-keyword]
  (when-let [value-str (value-keyword params)]
    (Integer. value-str)))

(defn page-size-validation
  "Validates that the page-size (if present) is a number in the valid range."
  [concept-type params]
  (try
    (if-let [page-size-i (get-ivalue-from-params params :page-size)]
      (cond
        (< page-size-i 0 )
        ["page_size must be a number between 0 and 2000"]

        (> page-size-i 2000)
        ["page_size must be a number between 0 and 2000"]

        :else
        [])
      [])
    (catch NumberFormatException e
      ["page_size must be a number between 0 and 2000"])))

(defn page-num-validation
  "Validates that the page-num (if present) is a number in the valid range."
  [concept-type params]
  (try
    (if-let [page-num-i (get-ivalue-from-params params :page-num)]
      (if (> 1 page-num-i)
        ["page_num must be a number greater than or equal to 1"]
        [])
      [])
    (catch NumberFormatException e
      ["page_num must be a number greater than or equal to 1"])))

(defn paging-depth-validation
  "Validates that the paging depths (page-num * page-size) does not exceed a set limit."
  [concept-type params]
  (try
    (let [limit (search-paging-depth-limit)
          page-size (get-ivalue-from-params params :page-size)
          page-num (get-ivalue-from-params params :page-num)]
      (when (and page-size
                 page-num
                 (> (* page-size page-num) limit))
        [(format "The paging depth (page_num * page_size) of [%d] exceeds the limit of %d."
                 (* page-size page-num)
                 limit)]))
    (catch NumberFormatException e
      ;; This should be handled separately by page-size and page-num validiation
      [])))

(def concept-type->valid-sort-keys
  "A map of concept type to sets of valid sort keys"
  {:collection #{:entry-title
                 :dataset-id
                 :start-date
                 :end-date
                 :provider
                 :platform
                 :instrument
                 :sensor
                 :score}
   :granule #{:granule-ur
              :producer-granule-id
              :readable-granule-name
              :start-date
              :end-date
              :entry-title
              :dataset-id
              :short-name
              :version
              :provider
              :data-size
              :cloud-cover
              :campaign
              :platform
              :instrument
              :sensor
              :project
              :day-night
              :downloadable
              :browsable}})

(defn sort-key-validation
  "Validates the sort-key parameter if present"
  [concept-type params]
  (if-let [sort-key (:sort-key params)]
    (let [sort-keys (if (sequential? sort-key) sort-key [sort-key])]
      (mapcat (fn [sort-key]
                (let [[_ field] (re-find #"[\-+]?(.*)" sort-key)
                      valid-params (concept-type->valid-sort-keys concept-type)]
                  (when-not (valid-params (keyword field))
                    [(msg/invalid-sort-key (csk/->snake_case_string field ) concept-type)])))
              sort-keys))
    []))


(defn unrecognized-params-validation
  "Validates that no invalid parameters were supplied"
  [concept-type params]
  ;; this test does not apply to page_size, page_num, etc.
  (let [params (dissoc params :page-size :page-num :sort-key :result-format :pretty :echo-compatible)
        params (if (= :collection concept-type)
                 ;; Parameters only supported on collections
                 (dissoc params :include-granule-counts :include-has-granules :include-facets)
                 params)]
    (map #(format "Parameter [%s] was not recognized." (csk/->snake_case_string %))
         (set/difference (set (keys params))
                         (concept-type->valid-param-names concept-type)))))

(defn unrecognized-params-in-options-validation
  "Validates that no invalid parameters names in the options were supplied"
  [concept-type params]
  (if-let [options (:options params)]
    (map #(str "Parameter [" (csk/->snake_case_string %)"] with option was not recognized.")
         (set/difference (set (keys options))
                         (concept-type->valid-param-names concept-type)))
    []))

(defn unrecognized-params-settings-in-options-validation
  "Validates that no invalid parameters names in the options were supplied"
  [concept-type params]
  (if-let [options (:options params)]
    (apply concat
           (map
             (fn [[param settings]]
               (map #(str "Option [" (csk/->snake_case_string %)
                          "] for param [" (csk/->snake_case_string param) "] was not recognized.")
                    (set/difference (set (keys settings)) (set [:ignore-case :pattern :and :or]))))
             options))
    []))

(defn option-case-sensitive-params-validation
  "Validates ignore case option setting is not set to true for identified params."
  [concept-type params]
  (if-let [options (:options params)]
    (apply concat
           (map
             (fn [[param settings]]
               (if (and (contains? case-sensitive-params param)
                        (= "true" (:ignore-case settings)))
                 [(c-msg/invalid-ignore-case-opt-setting-msg case-sensitive-params)]
                 []))
             options))
    []))

(defn option-pattern-params-validation
  "Validates pattern option setting is not set to true for identified params."
  [concept-type params]
  (if-let [options (:options params)]
    (apply concat
           (map
             (fn [[param settings]]
               (if (and (contains? params-that-disallow-pattern-search-option param)
                        (= "true" (:pattern settings)))
                 [(c-msg/invalid-pattern-opt-setting-msg params-that-disallow-pattern-search-option)]
                 []))
             options))
    []))

(defn options-or-params-validation
  "Validates or option setting is not set to true for anything but attribute"
  [concept-type params]
  (when-let [options (:options params)]
    (apply concat
           (map
             (fn [[param settings]]
               (when (and (= "true" (:or settings))
                        (not (contains? params-that-allow-or-option param)))
                 [(c-msg/invalid-or-opt-setting-msg param)]))
             options))))

(defn- validate-date-time
  "Validates datetime string is in the given format"
  [date-name dt]
  (try
    (when-not (s/blank? dt)
      (dt-parser/parse-datetime dt))
    []
    (catch ExceptionInfo e
      [(format "%s datetime is invalid: %s." date-name (first (:errors (ex-data e))))])))

(defn- day-valid?
  "Validates if the given day in temporal is an integer between 1 and 366 inclusive"
  [day tag]
  (if-not (s/blank? day)
    (try
      (let [num (Integer/parseInt day)]
        (when (or (< num 1) (> num 366))
          [(format "%s [%s] must be an integer between 1 and 366" tag day)]))
      (catch NumberFormatException e
        [(format "%s [%s] must be an integer between 1 and 366" tag day)]))
    []))

(defn temporal-format-validation
  "Validates that temporal datetime parameter conforms to the :date-time-no-ms format,
  start-day and end-day are integer between 1 and 366"
  [concept-type params]
  (if-let [temporal (:temporal params)]
    (let [temporal (if (sequential? temporal)
                     temporal
                     [temporal])]
      (mapcat
        (fn [value]
          (let [[start-date end-date start-day end-day] (map s/trim (s/split value #","))]
            (concat
              (validate-date-time "temporal start" start-date)
              (validate-date-time "temporal end" end-date)
              (day-valid? start-day "temporal_start_day")
              (day-valid? end-day "temporal_end_day"))))
        temporal))
    []))

(defn updated-since-validation
  "Validates updated-since parameter conforms to formats in data-time-parser NS"
  [concept-type params]
  (if-let [param-value (:updated-since params)]
    (if (and (sequential? (:updated-since params)) (> (count (:updated-since params)) 1))
      [(format "search not allowed with multiple updated_since values s%: " (:updated-since params))]
      (let [updated-since-val (if (sequential? param-value) (first param-value) param-value)]
        (validate-date-time "updated_since" updated-since-val)))
    []))

(defn attribute-validation
  [concept-type params]
  (if-let [attributes (:attribute params)]
    (if (sequential? attributes)
      (mapcat #(-> % attrib/parse-value :errors) attributes)
      [(attrib-msg/attributes-must-be-sequence-msg)])
    []))

(defn science-keywords-validation
  [concept-type params]
  (if-let [science-keywords (:science-keywords params)]
    (if (map? science-keywords)
      (let [values (vals science-keywords)]
        (if (some #(not (map? %)) values)
          [(msg/science-keyword-invalid-format-msg)]
          (reduce
            (fn [array param]
              (if-not (some #{param} (conj sk/science-keyword-fields :any))
                (conj array (format "parameter [%s] is not a valid science keyword search term." (name param)))
                array))
            []
            (mapcat keys values))))
      [(msg/science-keyword-invalid-format-msg)])
    []))

;; This method is for processing legacy numeric ranges in the form of
;; param_nam[value], param_name[minValue], and param_name[maxValue].
;; It simply validates that the provided values are numbers and that
;; at least one is present.
(defn- validate-legacy-numeric-range-param
  "Validates a numeric parameter in the form of a map, appending the message argument
  to the error array on failure."
  [param-map error-message-fn & args]
  (let [{:keys [value min-value max-value]} param-map]
    (try
      (when value
        (Double. value))
      (when min-value
        (Double. min-value))
      (when max-value
        (Double. max-value))
      (if (or value min-value max-value)
        []
        (if error-message-fn
          [(apply error-message-fn args)]
          [(d-msg/nil-min-max-msg)]))
      (catch NumberFormatException e
        [(apply error-message-fn args)]))))

(defn- validate-numeric-range-param
  "Validates a numeric parameter in the form parameter=value or
  parameter=min,max, appending the message argument to the error array on failure."
  [param error-message-fn & args]
  (let [errors (parser/numeric-range-string-validation param)]
    (if-not (empty? errors)
      (if error-message-fn
        (concat [(apply error-message-fn args)] errors)
        errors)
      [])))

(defn cloud-cover-validation
  "Validates cloud cover range values are numeric"
  [concept-type params]
  (if-let [cloud-cover (:cloud-cover params)]
    (if (string? cloud-cover)
      (validate-numeric-range-param cloud-cover nil)
      (validate-legacy-numeric-range-param cloud-cover nil))
    []))

(defn orbit-number-validation
  "Validates that the orbital number is either a single number or a range in the format
  start,stop, or in the catlog-rest style orbit_number[value], orbit_number[minValue],
  orbit_number[maxValue]."
  [concept-type params]
  (if-let [orbit-number-param (:orbit-number params)]
    (if (string? orbit-number-param)
      (validate-numeric-range-param orbit-number-param on-msg/invalid-orbit-number-msg)
      (validate-legacy-numeric-range-param orbit-number-param on-msg/invalid-orbit-number-msg))
    []))

(defn equator-crossing-longitude-validation
  "Validates that the equator-crossing-longitude parameter is a single number or
  a valid range string."
  [concept-type params]
  (if-let [equator-crossing-longitude (:equator-crossing-longitude params)]
    (if (string? equator-crossing-longitude)
      (validate-numeric-range-param equator-crossing-longitude nil)
      (validate-legacy-numeric-range-param equator-crossing-longitude
                                           on-msg/non-numeric-equator-crossing-longitude-parameter))
    []))

(defn equator-crossing-date-validation
  "Validates that the equator_crossing_date parameter is a valid date range string."
  [concept-type params]
  (if-let [equator-crossing-date (:equator-crossing-date params)]
    (parser/date-time-range-string-validation equator-crossing-date)
    []))

(defn exclude-validation
  "Validates that the key(s) supplied in 'exclude' param value are in exclude-params set"
  [concept-type params]
  (if-let [exclude-kv (:exclude params)]
    (let [invalid-exclude-params (set/difference (set (keys exclude-kv)) exclude-params)]
      (if (empty? invalid-exclude-params)
        (let [exclude-values (flatten (vals exclude-kv))]
          (if (some #(.startsWith % "C") exclude-values)
            [(str "Exclude collection is not supported, " exclude-kv)]
            []))
        [(c-msg/invalid-exclude-param-msg invalid-exclude-params)]))
    []))

(defn boolean-value-validation
  [concept-type params]
  (let [bool-params (select-keys params [:downloadable :browsable :include-granule-counts
                                         :include-has-granules :include-facets])]
    (mapcat
      (fn [[param value]]
        (if (or (= "true" value) (= "false" value) (= "unset" (s/lower-case value)))
          []
          [(format "Parameter %s must take value of true, false, or unset, but was %s"
                   (csk/->snake_case_string param) value)]))
      bool-params)))

(defn polygon-validation
  [concept-type params]
  (some->> params
           :polygon
           (spatial-codec/url-decode :polygon)
           :errors))

(defn bounding-box-validation
  [concept-type params]
  (some->> params
           :bounding-box
           (spatial-codec/url-decode :bounding-box)
           :errors))

(defn point-validation
  [concept-type params]
  (some->> params
           :point
           (spatial-codec/url-decode :point)
           :errors))

(defn line-validation
  [concept-type params]
  (some->> params
           :line
           (spatial-codec/url-decode :line)
           :errors))

(defn unrecognized-aql-params-validation
  [concept-type params]
  (map #(str "Parameter [" (csk/->snake_case_string % )"] was not recognized.")
       (set/difference (set (keys params))
                       (set [:page-size :page-num :sort-key :result-format :pretty :options
                             :include-granule-counts :include-has-granules :include-facets
                             :echo-compatible]))))

(defn timeline-start-date-validation
  "Validates the timeline start date parameter"
  [concept-type params]
  (let [start-date (:start-date params)]
    (if-not (s/blank? start-date)
      (validate-date-time "Timeline parameter start_date" start-date)
      ["start_date is a required parameter for timeline searches"])))

(defn timeline-end-date-validation
  "Validates the timeline end date parameter"
  [concept-type params]
  (let [end-date (:end-date params)]
    (if-not (s/blank? end-date)
      (validate-date-time "Timeline parameter end_date" end-date)
      ["end_date is a required parameter for timeline searches"])))

(defn timeline-range-validation
  "Validates the start date is before the end date"
  [concept-type params]
  (try
    (let [{:keys [start-date end-date]} params]
      (when (and start-date end-date
                 (t/after? (dt-parser/parse-datetime start-date)
                           (dt-parser/parse-datetime end-date)))
        [(format "start_date [%s] must be before the end_date [%s]"
                 start-date end-date)]))
    (catch ExceptionInfo e
      ;; The date times are invalid. This error should be handled by other validations
      [])))


(def valid-timeline-intervals
  "A list of the valid values for timeline intervals."
  #{"year" "month" "day" "hour" "minute" "second"})

(defn timeline-interval-validation
  "Validates the timeline interval parameter"
  [concept-type params]
  (if-let [interval (:interval params)]
    (when-not (valid-timeline-intervals interval)
      [(str "Timeline interval is a required parameter for timeline search and must be one of"
            " year, month, day, hour, minute, or second.")])
    ["interval is a required parameter for timeline searches"]))

(def parameter-validations
  "A list of the functions that can validate parameters. They all accept parameters as an argument
  and return a list of errors."
  [page-size-validation
   page-num-validation
   paging-depth-validation
   sort-key-validation
   unrecognized-params-validation
   unrecognized-params-in-options-validation
   unrecognized-params-settings-in-options-validation
   option-case-sensitive-params-validation
   option-pattern-params-validation
   options-or-params-validation
   temporal-format-validation
   updated-since-validation
   orbit-number-validation
   equator-crossing-longitude-validation
   equator-crossing-date-validation
   cloud-cover-validation
   attribute-validation
   science-keywords-validation
   exclude-validation
   boolean-value-validation
   polygon-validation
   bounding-box-validation
   point-validation])

(def aql-parameter-validations
  "A list of functions that can validate the query parameters passed in with an AQL search.
  They all accept parameters as an argument and return a list of errors."
  [page-size-validation
   page-num-validation
   paging-depth-validation
   sort-key-validation
   unrecognized-aql-params-validation])

(def timeline-parameter-validations
  "A list of function that can validate timeline query parameters. It will only validate the timeline
  parameters specifically. Parameter validation on the "
  [timeline-start-date-validation
   timeline-end-date-validation
   timeline-interval-validation
   timeline-range-validation])

(defn validate-parameters
  "Validates parameters. Throws exceptions to send to the user. Returns parameters if validation
  was successful so it can be chained with other calls."
  [concept-type params]
  (let [errors (mapcat #(% concept-type params) parameter-validations)]
    (when (seq errors)
      (err/throw-service-errors :bad-request errors)))
  params)

(defn validate-aql-parameters
  "Validates the query parameters passed in with an AQL search.
  Throws exceptions to send to the user. Returns parameters if validation
  was successful so it can be chained with other calls."
  [concept-type params]
  (let [errors (mapcat #(% concept-type params) aql-parameter-validations)]
    (when (seq errors)
      (err/throw-service-errors :bad-request errors)))
  params)

(defn validate-timeline-parameters
  "Validates the query parameters passed in with a timeline search.
  Throws exceptions to send to the user. Returns parameters if validation
  was successful so it can be chained with other calls."
  [params]
  (let [timeline-params (select-keys params [:interval :start-date :end-date])
        regular-params (dissoc params :interval :start-date :end-date)
        errors (concat (mapcat #(% :granule regular-params) parameter-validations)
                       (mapcat #(% :granule timeline-params) timeline-parameter-validations))]
    (when (seq errors)
      (err/throw-service-errors :bad-request errors)))
  params)
