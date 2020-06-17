(ns intake.core.async
  "Namespace for adapting manifold streams as core.async
  channels.

  This namespace doesn't provide much of anything interesting
  aside from extending protocols; just require this namespace
  to make manifold streams usable with core.async operations."
  (:require [clojure.core.async.impl.protocols :as p]
            [manifold.stream :as s]
            manifold.stream.core
            [manifold.deferred :as d]
            [clojure.core.async.impl.protocols :as impl])
  (:import [clojure.lang IDeref]
           [java.util.concurrent.locks Lock]
           [manifold.deferred IDeferred IMutableDeferred]
           [manifold.stream.core IEventStream IEventSink IEventSource]
           (java.util.concurrent ConcurrentHashMap)))

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

; here is the problem:
; If I take! from a manifold stream, the deferred I get will get
; completed at some point; but if I say timeout! a

(def ^:private pending-reads (ConcurrentHashMap.))

(extend-protocol p/ReadPort
  IEventSource
  (take! [this handler]
    (if (impl/blockable? handler)
      (locking this
        (if-let [d (.get pending-reads this)]
          (p/take! d handler)
          (let [d (s/take! this ::default)
                completion (fn [_]
                             (locking [this]
                               (when (impl/active? handler)
                                 (.remove pending-reads this))))]
            (d/on-realized d completion completion)
            (when-not (d/realized? d)
              (.put pending-reads this d))
            (p/take! d handler))))
      (p/take! (s/try-take! this ::default 0.0 ::timeout) handler)))

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
        (d/on-realized this
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