(ns intake.core.async-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer :all]
            intake.core.async
            [manifold.stream :as s]
            [manifold.deferred :as d]))

(use-fixtures :each
  (fn [test]
    (let [result (async/alt!! (async/thread (test)) :ok
                              (async/timeout 10000) :timeout)]
      (is (not= :timeout result)))))

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
                   (if (neg? num)
                     (is (not (neg? num)))
                     (when-let [m (async/<! s)]
                       (is (= num m))
                       (recur (dec num))))))
      (is (s/closed? s)))))

(deftest test-wrapped-deferreds
  (testing "deferred wrapped as a source"
    (let [d (d/deferred)
          s (s/->source d)
          chan (async/go
                 (is (= :test (async/<! s))))]
      (d/success! d :test)
      (async/<!! chan))))

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

(deftest test-poll-streams
  "I can't get poll! to work on streams; punting for now"
  (let [s (s/stream)]
    (is (nil? (async/poll! s)))
    (s/put! s :poll-after)
    (async/<!! (async/go (is (= :poll-after (async/poll! s)))))))

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

(deftest test-multiple-takes
  (testing "that we can take from multiple go blocks"
    (let [s (s/stream)
          values1 (atom [])
          values2 (atom [])
          go1 (async/go-loop []
                (when-let [v (async/<! s)]
                  (println (str "go1 received: " v))
                  (swap! values1 conj v)
                  (recur)))
          go2 (async/go-loop []
                (when-let [v (async/<! s)]
                  (println (str "go2 received: " v))
                  (swap! values2 conj v)
                  (recur)))
          puts (atom [])]
      (dotimes [i 10]
        (async/>!! s i)
        (swap! puts conj i))
      (async/close! s)
      (is (not= :timeout (async/alt!! go1 :ok
                                      (async/timeout 1000) :timeout)))
      (is (not= :timeout (async/alt!! go2 :ok
                                      (async/timeout 1000) :timeout)))
      (is (= (+ (count @values1) (count @values2)) (count @puts))))))

(deftest test-mult
  (testing "that core.async mult/tap works"
    (let [s (s/stream)
          mult (async/mult s)
          tap1 (async/tap mult (s/stream))
          tap2 (async/tap mult (s/stream))
          items1 (atom [])
          items2 (atom [])]
      (async/go-loop []
        (when-let [item (async/<! tap1)]
          (swap! items1 conj item)
          (recur)))
      (async/go-loop []
        (when-let [item (async/<! tap2)]
          (swap! items2 conj item)
          (recur)))
      (async/<!!
        (async/go-loop [n (range 10)]
          (when-let [x (first n)]
            (async/>! s x)
            (recur (rest n)))))
      (is (= (range 10) @items1))
      (is (= (range 10) @items2)))))

(deftest test-sub
  (testing "that core.async pub/sub works"
    (let [s (s/stream)
          pub (async/pub s identity)
          sub1 (async/sub pub :foo (s/stream))
          sub2 (async/sub pub :bar (s/stream))
          items1 (atom [])
          items2 (atom [])
          ch1 (async/go-loop []
                (when-let [x (async/<! sub1)]
                  (swap! items1 conj x)
                  (recur)))
          ch2 (async/go-loop []
                (when-let [x (async/<! sub2)]
                  (swap! items2 conj x)
                  (recur)))]
      (async/<!!
        (async/go-loop [vals (interleave (repeat 5 :foo) (repeat 5 :bar) (repeat 5 :baz))]
          (when-let [v (first vals)]
            (async/>! s v)
            (recur (rest vals)))))
      (async/close! s)
      (is (= :ok (async/alt!! ch1 :ok
                              (async/timeout 1000) :timeout)))
      (is (= :ok (async/alt!! ch2 :ok
                              (async/timeout 1000) :timeout)))
      (is (= (repeat 5 :foo) @items1))
      (is (= (repeat 5 :bar) @items2)))))