(ns intake.manifold-test
  (:require [clojure.test :refer :all]
            [intake.manifold :as m]
            [manifold.deferred :as d]))

(def ^:dynamic *var*)

(deftest test-binding
  (testing "that bindings are preserved"
    (let [d (d/deferred)
          dd (binding [*var* :chain]
               (m/chain d
                        (fn [result]
                          (is (= :chain *var*))
                          (str *var* result))))]
      (d/success! d :test)
      (is (= ":chain:test" @dd)))

    (let [d (d/deferred)
          dd (binding [*var* :catch]
               (m/catch d
                        (fn [e]
                          (is (= :catch *var*))
                          *var*)))]
      (d/error! d (ex-info "fail" {}))
      (is (= :catch @dd)))

    (let [d (d/deferred)
          rez (volatile! nil)
          dd (binding [*var* :finally]
               (m/finally d
                          (fn [] (vreset! rez *var*))))]
      (d/success! d :test)
      (is (= :test @dd))
      (is (= :finally @rez)))

    (let [d (d/deferred)
          rez (volatile! nil)
          dd (binding [*var* :finally2]
               (m/finally d
                          (fn [] (vreset! rez *var*))))]
      (d/error! d (ex-info "foo" {}))
      (is (thrown? Exception @dd))
      (is (= :finally2 @rez)))

    (let [d (d/deferred)]
      (binding [*var* :test]
        (m/on-realized d
                       (fn [x] (is (= :test *var*)))
                       (fn [e] (is false))))
      (d/success! d :result))

    (let [d (d/deferred)]
      (binding [*var* :test2]
        (m/on-realized d
                       (fn [x] (is false))
                       (fn [e] (is (= :test2 *var*)))))
      (d/error! d (ex-info "error" {})))))