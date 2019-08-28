(ns intake.core.async
  "Namespace for adapting manifold streams as core.async
  channels.

  This namespace doesn't provide much of anything interesting
  aside from extending protocols; just require this namespace
  to make manifold streams usable with core.async operations."
  (:require [clojure.core.async.impl.protocols :as p]
            [manifold.stream :as s]
            manifold.stream.core
            [manifold.deferred :as d])
  (:import [clojure.lang IDeref]
           [java.util.concurrent.locks Lock]
           [manifold.deferred IDeferred IMutableDeferred]
           [manifold.stream.core IEventStream IEventSink IEventSource]))

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

(extend-protocol p/ReadPort
  IEventSource
  (take! [this handler]
    (p/take! (s/take! this) handler))

  IDeferred
  (take! [this handler]
    (if (d/realized? this)
      this
      (let [completion (fn [result]
                         (.lock ^Lock handler)
                         (let [put-cb (and (p/active? handler) (p/commit handler))]
                           (.unlock handler)
                           (when put-cb
                             (put-cb result))))]
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