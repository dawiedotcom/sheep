(ns scheep.pattern-lang
  (:gen-class)
  (:use
   [scheep.env :only [lookup]]))

;;; Implementation for scheme's pattern language used in
;;; syntax-rules

;;; TODO
;;;   1) pass a map instead of four params
;;;   2) get util fns to test for literals and merge maps
;;;   3) match elipsis
;;;   4) match dots

(declare literal?
         same-literal?
         next-pattern
         merge-concat)

(defmulti pattern
  (fn [{[_ pattern2] :pattern :as map}] 
    ;(println "\n== pattern\n map = \n" map)
    pattern2))

(defmulti concrete-pattern
  (fn [{[pattern1] :pattern}]
    (class pattern1)))

(defn symbol-pattern
  [{[f & fs] :form
    [p & ps] :pattern
    subs :acc
    literals :literals
    def-env :dev-env
    use-env :use-env
    :as map}]
  (if-not (literal? literals p)
    (pattern (next-pattern map fs ps (assoc subs p (list f))))
    (if (same-literal? def-env p use-env f)
      (pattern (next-pattern map fs ps subs)))))
      
;;; Defaults

(defmethod pattern :default [args] (concrete-pattern args))
(defmethod concrete-pattern :default [args] nil)

;;; Pattern

(defmethod pattern
  '...
  [{form :form [p] :pattern subs :acc :as arg-map}]
  "Match the rest of the forms against the first pattern"
  (if (empty? form)
    (merge-concat subs {p nil '... nil})
    (apply merge-concat
           (cons subs
                 (map #(pattern
                        (next-pattern arg-map (list %) (list p) {'... nil}))
                      form)))))

;;; Concrete pattern

(defmethod concrete-pattern
  nil
  [{form :form subs :acc :as map}]
  "The terminal case"
  (if (empty? form) subs))

(defmethod concrete-pattern
  clojure.lang.Symbol
  [map]
  "first pattern is a symbol, but not ... or ."
  (symbol-pattern map))

(defmethod concrete-pattern
  java.util.Collection
  [{[f & fs] :form [p & ps] :pattern subs :acc :as map}]
  "first pattern is a list, match recursively into the tree"
  (let [recur-map (next-pattern map f p {})
        merged (merge-concat subs (pattern recur-map))]
    (if merged
      (pattern (next-pattern map fs ps merged)))))

;;; Helpers

(defn next-pattern [map form pattern acc]
  "Returns a new argememt map with replaced vals for
   form, pattern and acc"
  (assoc map :form form :pattern pattern :acc acc))

(defn literal? [literals p]
  (let [res (not (nil? (some #{p} literals)))]
    res))
  
(defn same-literal? [s-env-def p s-env-use f]
  (let [res (= (lookup p s-env-def)
               (lookup f s-env-use))]
    res))

(defn merge-concat [& maps]
  ;; assuming each map has only lists as values, this
  ;; does the same as merge, but concats vals that
  ;; correspond to the same key.
  (defn reducer [m1 m2]
    (if (and m1 m2)
      (let [ks (concat (keys m1) (keys m2))
            vs (map #(concat (% m1) (% m2)) ks)]
        (zipmap ks vs))))
  (reduce reducer maps))
