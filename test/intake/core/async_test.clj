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
      (async/take! s (fn [r] (is (= :foo r)))))))

(deftest test-stream-integration-2
  (testing "putting and taking multiple values"
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
      (is (s/closed? s)))))

(deftest test-wrapped-deferreds
  (testing "deferred wrapped as a source"
    (let [d (d/deferred)
          s (s/->source d)]
      (async/go
        (is (= :test (async/<! s))))
      (d/success! d :test))))

(deftest test-<!-after-poll
  (testing "that takes work after polling"
    (let [s (s/stream)]
      (is (nil? (async/poll! s)))
      (async/go (is (= :after-poll (async/<! s))))
      (is (true? (async/put! s :after-poll))))))

(deftest test-take
  (testing "that take works as expected"
    (let [s (s/stream)
          rez (atom nil)]
      (async/take! s
                   (fn [r] (reset! rez r)))
      (async/>!! s :foo)
      (is (= :foo @rez)))))

(deftest test-<!-after-timeout
  (testing "that another take works after a timeout"
    (let [s (s/stream)]
      (is (= :timeout (async/alt!! s ([v] v)
                                   (async/timeout 10) :timeout)))
      (s/put! s :after-timeout)
      (is (= :after-timeout (async/<!! s))))))

(comment
  (deftest test-poll-streams
    "I can't get poll! to work on streams; punting for now"
    (let [s (s/stream)]
      (is (nil? (async/poll! s)))
      (s/put! s :poll-after)
      (async/go (is (= :poll-after (async/poll! s)))))))

(deftest test-deferred-integration
  (testing "that deferreds can be used as promise channels"
    (let [d (d/deferred)]
      (async/go (is (= :foo (async/<! d))))
      (async/go (is (= :foo (async/<! d))))
      (is (true? (async/put! d :foo)))
      (is (false? (async/put! d :bar))))))

(deftest test-deferred-poll
  (testing "that we can poll deferreds"
    (let [d (d/deferred)]
      (is (= nil (async/poll! d)))
      (is (true? (async/put! d :foo)))
      (is (= :foo (async/poll! d)))
      (is (= :foo (async/poll! d))))))