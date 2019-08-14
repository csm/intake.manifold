(ns intake.manifold
  "Equivalents to some functions in manifold.deferred, but which
  preserve dynamic variable bindings."
  (:require [manifold.deferred :as d]))

(defmacro chain
  "Equivalent to manifold.deferred/chain, but preserves dynamic
  variable bindings."
  [d & forms]
  (let [bindings-symbol (gensym "bindings-")]
    `(let [~bindings-symbol (get-thread-bindings)]
       (d/chain
         ~d
         ~@(map (fn [form]
                  `(fn [x#]
                     (with-bindings ~bindings-symbol
                       (~form x#))))
                forms)))))

(defmacro catch
  "Equivalent to manifold.deferred/catch, but preserves dynamic
  variable bindings."
  [d & args]
  (let [[error-class handler] (condp = (count args)
                                1 [nil (first args)]
                                2 args
                                (throw (IllegalArgumentException. "catch takes 2 or 3 arguments")))
        bindings-symbol (gensym "bindings-")]
    `(let [~bindings-symbol (get-thread-bindings)]
       (d/catch ~d ~error-class
         (fn [x#]
           (with-bindings ~bindings-symbol
             (~handler x#)))))))

(defmacro finally
  "Equivalent to manifold.deferred/finally, but preserves dynamic
  variable bindings."
  [d finally-fn]
  (let [bindings-symbol (gensym "bindings-")]
    `(let [~bindings-symbol (get-thread-bindings)]
       (d/finally ~d
                  (fn []
                    (with-bindings ~bindings-symbol
                      (~finally-fn)))))))

(defmacro on-realized
  "Equivalent to manifold.deferred/on-realized, but preserves dynamic
  variable bindings."
  [d on-success on-error]
  (let [bindings-symbol (gensym "bindings-")]
    `(let [~bindings-symbol (get-thread-bindings)]
       (d/on-realized
         ~d
         (fn [x#]
           (with-bindings ~bindings-symbol
             (~on-success x#)))
         (fn [x#]
           (with-bindings ~bindings-symbol
             (~on-error x#)))))))