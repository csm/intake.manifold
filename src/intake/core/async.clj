(ns intake.core.async
  "Namespace for adapting manifold streams as core.async
  channels.

  This namespace doesn't provide much of anything interesting
  aside from extending protocols; just require this namespace
  to make manifold streams usable with core.async operations."
  (:require [clojure.core.async.impl.protocols :as p]
            [intake.manifold :as m]
            [manifold.stream :as s]
            manifold.stream.core
            [manifold.deferred :as d]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.core.async.impl.mutex :as mutex]
            [clojure.core.async :as async])
  (:import [clojure.lang IDeref]
           [java.util.concurrent.locks Lock ReentrantLock]
           [manifold.deferred IDeferred IMutableDeferred]
           [manifold.stream.core IEventStream IEventSink IEventSource]
           (java.util.concurrent ConcurrentHashMap)
           (java.util.function BiConsumer)
           (java.util LinkedList)))

(defn- box
  [value]
  (reify IDeref
    (deref [_] value)))

(extend-protocol p/WritePort
  IEventSink
  (put! [this val handler]
    (p/take! (s/put! this val) handler))

  IMutableDeferred
  (put! [this val _handler]
    (box (d/success! this val))))

(def ^:private pending-reads (ConcurrentHashMap.))

(defprotocol ^:private ITakeQueue
  (drained? [this])
  (take-from-queue! [this stream handler]))

(defmacro with-lock
  [lock & body]
  `(do (.lock ^Lock ~lock)
       (try ~@body
            (finally (.unlock ^Lock ~lock)))))

(defn- handle-realized
  [take-queue stream handler exp-token]
  (fn [result]
    (with-lock (.-lock take-queue)
      (when (= exp-token @(.-token take-queue))
        (.remove ^LinkedList (.-queue take-queue) handler)
        (.lock ^Lock handler)
        (let [cb (and (p/active? handler) (p/commit handler))]
          (.unlock ^Lock handler)
          (when cb
            (let [next-token (vswap! (.-token take-queue) unchecked-inc)]
              (if (empty? (.-queue take-queue))
                (vreset! (.-deferred take-queue) nil)
                (let [new-d (vreset! (.-deferred take-queue) (s/take! stream ::default))]
                  (doseq [h (.-queue take-queue)]
                    (let [cb (handle-realized take-queue stream h next-token)]
                      (m/on-realized new-d cb cb))))))
            (cb (when (not= ::default result) result))))))))

(deftype ^:private TakeQueue [deferred token queue lock]
  ITakeQueue
  (drained? [_] (and (nil? @deferred) (.isEmpty queue)))
  (take-from-queue! [this stream handler]
    (with-lock lock
      (.addLast ^LinkedList queue handler)
      (let [d (vswap! deferred #(or % (s/take! stream ::default)))
            exp-token @token
            callback (handle-realized this stream handler exp-token)]
        (m/on-realized d callback callback)))))

(defn- ->take-queue
  []
  (->TakeQueue (volatile! nil)
               (volatile! 0)
               (LinkedList.)
               (ReentrantLock.)))

(extend-protocol p/ReadPort
  IEventSource
  (take! [this handler]
    (.forEach pending-reads (reify BiConsumer
                              (accept [_ k queue]
                                (when (drained? queue)
                                  (.remove pending-reads k)))))
    (if (impl/blockable? handler)
      (let [q (->take-queue)
            prev (.putIfAbsent pending-reads this q)
            queue (or prev q)]
        (take-from-queue! queue this handler)
        nil)
      (let [d (or (some-> (.get pending-reads this) (.-deferred) deref)
                  (s/try-take! this ::default 0.0 ::timeout))]
        (p/take! d handler))))

  IDeferred
  (take! [this handler]
    (if (d/realized? this)
      (d/chain this
               #(when (and (not= ::default %) (not= ::timeout %)) %))
      (let [completion (fn [result]
                         (when (not= ::timeout result)
                           (.lock ^Lock handler)
                           (let [put-cb (and (p/active? handler) (p/commit handler))]
                             (.unlock handler)
                             (when put-cb
                               (put-cb (when (not= ::default result) result))))))]
        (m/on-realized this
                       completion
                       completion)
        nil))))

(extend-protocol p/Channel
  IEventStream
  (close! [this] (s/close! this))
  (closed? [this] (s/closed? this))

  IDeferred
  (close! [this] (d/success! this nil))
  (closed? [this] (d/realized? this)))