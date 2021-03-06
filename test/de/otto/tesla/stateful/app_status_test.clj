(ns de.otto.tesla.stateful.app-status-test
  (:require [clojure.test :refer :all]
            [de.otto.tesla.stateful.app-status :as app-status]
            [com.stuartsierra.component :as c]
            [de.otto.tesla.stateful.configuring :as configuring]
            [de.otto.tesla.stateful.metering :as metering]
            [de.otto.tesla.stateful.routes :as routes]
            [environ.core :as env]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [de.otto.tesla.util.test-utils :as u]
            [de.otto.tesla.system :as system]))

(defn- test-system [runtime-config]
  (dissoc
    (system/empty-system runtime-config)
    :server))

(deftest ^:unit should-have-system-status-for-runtime-config
  (u/with-started [system (test-system {:host-name "bar" :host-port "0123"})]
                  (let [status (:app-status system)
                        system-status (:system (app-status/status-response-body status))]
                    (is (= (:hostname system-status) "bar"))
                    (is (= (:port system-status) "0123"))
                    (is (not (nil? (:systemTime system-status)))))))

(deftest ^:unit should-have-system-status-for-env-config
  (with-redefs-fn {#'env/env {:host-name "foo" :host-port "1234"}}
                  #(u/with-started [system (test-system {})]
                                   (let [status (:app-status system)
                                         system-status (:system (app-status/status-response-body status))]
                                     (is (= (:hostname system-status) "foo"))
                                     (is (= (:port system-status) "1234"))
                                     (is (not (nil? (:systemTime system-status))))))))

(deftest ^:unit should-sanitize-passwords
  (is (= (app-status/sanitize {:somerandomstuff                        "not-so-secret"
                               :somerandomstuff-passwd-somerandomstuff "secret"
                               :somerandomstuff-pwd-somerandomstuff    "secret"} ["passwd" "pwd"])
         {:somerandomstuff                        "not-so-secret"
          :somerandomstuff-passwd-somerandomstuff "******"
          :somerandomstuff-pwd-somerandomstuff    "******"})))

(defrecord MockStatusSource [response]
  c/Lifecycle
  (start [self]
    (app-status/register-status-fun (:app-status self) #(:response self))
    self)
  (stop [self]
    self))

(defn- mock-status-system [response]
  (-> (c/system-map
        :config (configuring/new-config {})
        :metering (c/using
                    (metering/new-metering)
                    [:config])
        :routes (routes/new-routes)
        :app-status (c/using (app-status/new-app-status) [:config :routes])
        :mock-status (c/using (map->MockStatusSource {:response response}) [:app-status]))))


(deftest ^:unit should-show-applicationstatus
  (u/with-started [started (mock-status-system {:mock {:status  :ok
                                                       :message "nevermind"}})]
                  (let [status (:app-status started)
                        page (app-status/status-response status)
                        _ (log/info page)
                        application-body (get (json/read-str (:body page)) "application")]
                    (testing "it shows OK as application status"
                      (is (= (get application-body "status")
                             "OK")))

                    (testing "it shows the substatus"
                      (is (= (get application-body "statusDetails")
                             {"mock" {"message" "nevermind" "status" "OK"}}))))))

(deftest ^:unit should-show-warning-as-application-status
  (u/with-started [started (mock-status-system {:mock {:status  :warning
                                                       :message "nevermind"}})]
                  (let [status (:app-status started)
                        page (app-status/status-response status)
                        applicationStatus (get (get (json/read-str (:body page)) "application") "status")]
                    (is (= applicationStatus "WARNING")))))
