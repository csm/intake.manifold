(ns intake.core.async-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer :all]
            intake.core.async
            [manifold.stream :as s]
            [manifold.deferred :as d]))

(deftest test-stream-integration
  (testing "that streams can be used as channels"
    (let [s (s/stream)]
      (async/put! s :foo)
      (async/take! s (fn [r] (is (= :foo r)))))
    (let [s (s/stream)]
      (async/go-loop [num 10]
        (if (pos? num)
          (let [rez (async/>! s num)]
            (is (true? rez))
            (recur (dec num)))
          (async/close! s)))
      (async/<!! (async/go-loop [num 10]
                   (when-let [m (async/<! s)]
                     (is (= num m))
                     (recur (dec num)))))
      (is (s/closed? s)))
    (let [d (d/deferred)
          s (s/->source d)]
      (async/go
        (is (= :test (async/<! s))))
      (d/success! d :test))))

(deftest test-deferred-integration
  (testing "that deferreds can be used as promise channels"
    (let [d (d/deferred)]
      (async/go (is (= :foo (async/<! d))))
      (async/go (is (= :foo (async/<! d))))
      (is (true? (async/put! d :foo)))
      (is (false? (async/put! d :bar))))
    (let [d (d/deferred)]
      (is (= nil (async/poll! d)))
      (is (true? (async/put! d :foo)))
      (is (= :foo (async/poll! d)))
      (is (= :foo (async/poll! d))))))